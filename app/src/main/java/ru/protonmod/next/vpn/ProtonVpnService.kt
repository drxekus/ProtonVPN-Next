/*
 * Copyright (C) 2026 SMH01
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.protonmod.next.vpn

import ru.protonmod.next.netshield.LocalNetShield
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.TrafficStats
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.protonmod.next.BuildConfig
import ru.protonmod.next.R
import ru.protonmod.next.data.state.ConnectedServerState
import ru.protonmod.next.data.local.ConnectionVerificationMode
import ru.protonmod.next.data.local.SettingsManager
import ru.protonmod.next.utils.ProtonLogger
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

internal fun isAwgHandshakeSuccess(message: String): Boolean {
    val normalized = message.lowercase(Locale.ROOT)
    return "received handshake response" in normalized ||
        "handshake response received" in normalized
}

internal fun isAwgHandshakeAttempt(message: String): Boolean =
    "sending handshake initiation" in message.lowercase(Locale.ROOT)

private const val FALLBACK_LIBBOX_LOCALE = "en"

private fun String.hasOnlyAsciiLetters(): Boolean =
    isNotEmpty() && all { it in 'a'..'z' || it in 'A'..'Z' }

/**
 * Converts Android's permissive Locale model into the conservative language[_REGION] form
 * accepted by libbox. OEM builds can expose malformed legacy variants such as ru__RUzhzh;
 * variants and scripts are intentionally omitted because localization must never block VPN start.
 */
internal fun libboxLocale(locale: Locale): String {
    val language = locale.language
        .takeIf { it.length in 2..3 && it.hasOnlyAsciiLetters() }
        ?.lowercase(Locale.ROOT)
        ?: FALLBACK_LIBBOX_LOCALE
    val region = locale.country
        .takeIf { it.length == 2 && it.hasOnlyAsciiLetters() }
        ?.uppercase(Locale.ROOT)
    return if (region == null) language else "${language}_$region"
}

/**
 * Android VPN service backed by amnezia-box (sing-box + AWG/AWG2).
 *
 * Once it has a configuration the service owns the tunnel's life: it restores the last tunnel
 * when Android restarts it (sticky restart or Always-on VPN), reports "verified" only after a
 * real WireGuard handshake, notices when the server stops answering, and keeps trying — waiting
 * for a network first — instead of quietly stopping after a short loss of connectivity.
 */
@AndroidEntryPoint
class ProtonVpnService : VpnService(), CommandServerHandler {
    @Inject lateinit var connectedServerState: ConnectedServerState
    @Inject lateinit var localNetShield: LocalNetShield
    @Inject lateinit var vpnNetworkMonitor: VpnNetworkMonitor

    companion object {
        private const val TAG = "ProtonVpnService"
        const val ACTION_CONNECT = "ru.protonmod.next.vpn.CONNECT"
        const val ACTION_DISCONNECT = "ru.protonmod.next.vpn.DISCONNECT"
        const val ACTION_STATE_CHANGED = "ru.protonmod.next.vpn.STATE_CHANGED"
        const val ACTION_UPDATE_SETTINGS = "ru.protonmod.next.vpn.UPDATE_SETTINGS"
        const val ACTION_STATS_UPDATED = "ru.protonmod.next.vpn.STATS_UPDATED"
        const val ACTION_SET_VERIFIED = "ru.protonmod.next.vpn.SET_VERIFIED"
        const val ACTION_QUERY_STATE = "ru.protonmod.next.vpn.QUERY_STATE"
        /** Restore the last tunnel unless the user turned the VPN off (e.g. after an app update). */
        const val ACTION_RESUME = "ru.protonmod.next.vpn.RESUME"
        /** Sent to the app whenever a tunnel attempt turns out not to carry traffic. */
        const val ACTION_TUNNEL_FAILED = "ru.protonmod.next.vpn.TUNNEL_FAILED"
        /**
         * Bind action for the app process. A binding made without BIND_AUTO_CREATE neither starts
         * nor keeps this service alive, but it tells the app immediately when this process dies,
         * which a killed process could never announce with a broadcast.
         */
        const val ACTION_BIND_STATUS = "ru.protonmod.next.vpn.BIND_STATUS"

        const val EXTRA_CONFIG = "config_string"
        const val EXTRA_EXCLUDED_APPS = "excluded_apps"
        const val EXTRA_EXCLUDED_IPS = "excluded_ips"
        const val EXTRA_SPLIT_TUNNELING_ENABLED = "split_tunneling_enabled"
        const val EXTRA_SPLIT_TUNNELING_MODE = "split_tunneling_mode"
        const val EXTRA_STATE = "state"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_TRAFFIC_RX = "traffic_rx"
        const val EXTRA_TRAFFIC_TX = "traffic_tx"
        const val EXTRA_TRAFFIC_DELTA_RX = "traffic_delta_rx"
        const val EXTRA_TRAFFIC_DELTA_TX = "traffic_delta_tx"
        const val EXTRA_TRAFFIC_DELTA_SECONDS = "traffic_delta_seconds"
        const val EXTRA_NOTIFICATIONS_ENABLED = "notifications_enabled"
        const val EXTRA_KILL_SWITCH_ENABLED = "kill_switch_enabled"
        const val EXTRA_NON_FATAL_ENABLED = "non_fatal_enabled"
        const val EXTRA_ANALYTICS_ENABLED = "analytics_enabled"
        const val EXTRA_LOGICAL_SERVER_ID = "logical_server_id"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_IS_RECONNECTING = "is_reconnecting"
        const val EXTRA_VERIFIED = "verified"
        const val EXTRA_VERIFICATION_MODE = "verification_mode"
        const val EXTRA_VERIFICATION_REQUIRED = "verification_required"
        const val EXTRA_HANDSHAKE_TIMEOUT_SECONDS = "handshake_timeout_seconds"
        const val EXTRA_FAILURE_DETECTION_ENABLED = "failure_detection_enabled"
        const val EXTRA_AUTO_RECONNECT_ENABLED = "auto_reconnect_enabled"
        const val EXTRA_FAILURE_REASON = "failure_reason"
        /** Marks a CONNECT rebuilt from the on-disk snapshot rather than freshly built by the app. */
        const val EXTRA_RESTORED = "restored_from_snapshot"
        const val STATE_CONNECTING = "CONNECTING"

        const val FAILURE_HANDSHAKE_TIMEOUT = "handshake_timeout"
        const val FAILURE_HANDSHAKE_STALLED = "handshake_stalled"
        const val FAILURE_TRANSPORT = "transport_failure"
        const val FAILURE_ENGINE = "engine_failure"
        const val FAILURE_PERMANENT = "permanent_failure"
        const val FAILURE_NETWORK_CHANGED = "network_changed"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "vpn_status_channel"
        private const val CHANNEL_SILENT_ID = "vpn_status_channel_silent"
        private const val FULL_CONFIG_LOG_TAG = "ProtonVpnConfig"
        private const val CRASH_REPORT_SOURCE = "ProtonVpnService"
        private const val LOGCAT_CHUNK_SIZE = 3_500
        private val libboxInitialized = AtomicBoolean(false)
        private val libboxInitDeferred = CompletableDeferred<Unit>()

        /** Delays between automatic recovery attempts; the last one repeats. */
        private val RECOVERY_DELAYS_MS = longArrayOf(1_000, 3_000, 5_000, 10_000, 20_000, 30_000, 60_000)
        /** WireGuard retries a handshake every 5 s; this long without an answer is a dead tunnel. */
        private const val HANDSHAKE_STALL_MS = 25_000L
        private const val HEALTH_TICK_MS = 5_000L
        /** How long the tunnel may take to answer on a new network before it is restarted. */
        private const val NETWORK_CHANGE_SETTLE_MS = 1_500L
        /** Upper bound for keeping the phone awake while one recovery runs. */
        private const val RECOVERY_WAKE_LOCK_MS = 60_000L
        private const val NOTIFICATION_REFRESH_MS = 5_000L
        private const val APP_REQUEST_THROTTLE_MS = 30_000L
        private val APP_CONFIG_WAIT = 60.seconds
        /** A restored snapshot may carry an expired certificate; ask the app for a fresh one then. */
        private const val RESTORED_ATTEMPTS_BEFORE_APP_REQUEST = 2
        private const val ATTEMPTS_BEFORE_APP_REQUEST = 3

        /** Engine messages worth keeping in the on-device event log (never with addresses). */
        private val ENGINE_EVENT_MARKERS = listOf(
            "sending handshake initiation",
            "received handshake response",
            "updated default interface",
            "missing default interface",
            "update bind",
            "network is unreachable",
            "handshake did not complete",
        )

        /** Engine start failures that only mean the device currently has no usable network. */
        private val TRANSIENT_NETWORK_ERROR_MARKERS = listOf(
            "no available network interface",
            "network is unreachable",
            "no route to host"
        )

        internal fun shouldShowNotification(stateName: String, enabled: Boolean): Boolean {
            return enabled && stateName != VpnTunnelState.DOWN.name
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engineMutex = Mutex()
    private lateinit var platform: AwgBoxPlatform
    private lateinit var snapshotStore: TunnelSnapshotStore
    private var commandServer: CommandServer? = null
    private var tunDescriptor: ParcelFileDescriptor? = null
    private var statsJob: Job? = null
    private var reconnectJob: Job? = null
    private var handshakeVerificationJob: Job? = null
    private var healthJob: Job? = null
    private var appConfigWaitJob: Job? = null
    private var networkChangeJob: Job? = null
    private var recoveryWakeLock: PowerManager.WakeLock? = null
    private var engineJob: Job? = null
    private var shutdownJob: Job? = null
    private val lifecycleGeneration = AtomicLong(0)
    private val closingCommandServer = AtomicBoolean(false)
    private val closedCommandServers = Collections.newSetFromMap(
        IdentityHashMap<CommandServer, Boolean>()
    )
    @Volatile private var startingCommandServer: CommandServer? = null
    private var foregroundStarted = false
    private var state = VpnTunnelState.DOWN
    private var connecting = false
    private var verified = false
    private var manualDisconnect = false
    private var notificationsEnabled = true
    private var killSwitchEnabled = false
    private var verificationMode = ConnectionVerificationMode.BALANCED
    private var verificationRequired = false
    private var handshakeTimeoutSeconds = SettingsManager.DEFAULT_HANDSHAKE_RECONNECT_TIMEOUT_SECONDS
    private var failureDetectionEnabled = true
    private var autoReconnectEnabled = true
    private var logicalServerId: String? = null
    private var lastConnectIntent: Intent? = null
    private var persistedConfig: String? = null
    private var restoredFromSnapshot = false
    private var recoveryAttempt = 0
    private var lastAppRequestAt = 0L
    private var lastNotificationAt = 0L
    @Volatile private var handshakeObserved = false
    /** When the current unanswered handshake initiation started, or 0 when none is pending. */
    @Volatile private var pendingInitiationSince = 0L
    @Volatile private var lastHandshakeResponseAt = 0L
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastSpeed: String? = null
    private var transportFailureCount = 0
    private var lastTransportFailureAt = 0L
    private var lastHealthReconnectAt = 0L

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                // The app falls back to a broadcast when Android refuses a foreground-service
                // start from the background; the service is already running in that case.
                ACTION_CONNECT -> startTunnel(intent)
                ACTION_DISCONNECT -> stopTunnel()
                ACTION_UPDATE_SETTINGS -> applySettings(intent)
                ACTION_SET_VERIFIED -> markVerified()
                ACTION_QUERY_STATE -> sendState(if (connecting) null else state)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        snapshotStore = TunnelSnapshotStore(this)
        VpnEventLog.init(this)
        VpnEventLog.log("service created")
        // Libbox.setup is a long-running native call; run it on IO to avoid blocking the main
        // thread and triggering a Background ANR.
        scope.launch(Dispatchers.IO) { initializeLibbox() }
        platform = AwgBoxPlatform(this, vpnNetworkMonitor, ::onNetworkPathChanged) { descriptor ->
            tunDescriptor?.close()
            tunDescriptor = descriptor
        }
        ContextCompat.registerReceiver(
            this,
            settingsReceiver,
            IntentFilter().apply {
                addAction(ACTION_CONNECT)
                addAction(ACTION_DISCONNECT)
                addAction(ACTION_UPDATE_SETTINGS)
                addAction(ACTION_SET_VERIFIED)
                addAction(ACTION_QUERY_STATE)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun initializeLibbox() {
        if (!libboxInitialized.compareAndSet(false, true)) {
            // Already initializing or done; just wait for the deferred to complete elsewhere.
            return
        }
        val workingDir = getExternalFilesDir(null)?.takeIf { it.exists() || it.mkdirs() } ?: filesDir
        val options = SetupOptions().apply {
            basePath = filesDir.absolutePath
            workingPath = workingDir.absolutePath
            tempPath = cacheDir.absolutePath
            logMaxLines = 2_000
            // libbox only invokes CommandServerHandler.writeDebugMessage when this flag is on.
            // NetShield and the handshake monitor read engine messages through that callback in
            // every build type; raw engine messages are still written to Logcat only in debug builds.
            debug = true
            fixAndroidStack = true
            // Libbox.setup() now owns the stderr redirect that Libbox.redirectStderr() used to
            // perform; it writes native panics to "CrashReport-$crashReportSource.log" in
            // workingPath.
            crashReportSource = CRASH_REPORT_SOURCE
        }
        try {
            Libbox.setup(options)
            val preferredLocale = libboxLocale(Locale.getDefault())
            try {
                Libbox.setLocale(preferredLocale)
            } catch (error: Exception) {
                // Locale only controls engine messages. A malformed OEM locale must not make the
                // service unavailable; English is known to exist in every libbox build.
                ProtonLogger.w(TAG, "Libbox rejected locale $preferredLocale; using English")
                if (preferredLocale != FALLBACK_LIBBOX_LOCALE) {
                    runCatching { Libbox.setLocale(FALLBACK_LIBBOX_LOCALE) }
                }
            }
            // Log the version from this IO thread only. Touching the Libbox class on the main
            // thread (as onCreate did) races this coroutine: gomobile class init is ABBA-deadlock
            // prone (SetupOptions.<clinit> -> Libbox.touch vs Libbox.<clinit> -> _init), which
            // blocked onCreate, skipped startForeground() and ANR-killed the :vpn process.
            ProtonLogger.i(TAG, "amnezia-box ${Libbox.version()} initialized")
            libboxInitDeferred.complete(Unit)
        } catch (t: Throwable) {
            libboxInitDeferred.completeExceptionally(t)
        }
    }

    /** Handed to the app process so it learns at once when this process dies. */
    private val statusBinder = Binder()

    override fun onBind(intent: Intent): IBinder? =
        if (intent.action == ACTION_BIND_STATUS) statusBinder else super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() call must be answered with a foreground promotion within
        // a few seconds, otherwise the OS kills us with ForegroundServiceDidNotStartInTimeException
        // (ANDROID-21N). Commands other than CONNECT, and CONNECT itself when it bails out on a
        // malformed intent, used to return without ever promoting the service.
        if (!foregroundStarted) {
            updateNotification(if (connecting) STATE_CONNECTING else state.name, ensureForeground = true)
        }

        when (intent?.action) {
            ACTION_CONNECT -> startTunnel(intent)
            ACTION_DISCONNECT -> stopTunnel()
            ACTION_UPDATE_SETTINGS -> applySettings(intent)
            ACTION_SET_VERIFIED -> markVerified()
            ACTION_QUERY_STATE -> sendState(if (connecting) null else state)
            // A null intent is Android restarting the service after its process was killed; the
            // VpnService action is Always-on VPN asking for a tunnel. Both used to stop the
            // service on the spot, which with "Block connections without VPN" left the phone
            // offline until the app was opened again.
            null, SERVICE_INTERFACE -> handleSystemStart(alwaysOn = intent != null)
            ACTION_RESUME -> handleSystemStart(alwaysOn = false)
            else -> {
                if (state == VpnTunnelState.DOWN && !connecting) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }
        return if (state == VpnTunnelState.DOWN && !connecting) START_NOT_STICKY else START_STICKY
    }

    private fun handleSystemStart(alwaysOn: Boolean) {
        VpnEventLog.log("system start (always-on=$alwaysOn, connecting=$connecting, state=$state)")
        if (connecting || state == VpnTunnelState.UP) return
        if (!alwaysOn && snapshotStore.userStopped) {
            ProtonLogger.i(TAG, "Restarted by the system after the user disconnected; staying off")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val restored = snapshotStore.load(this)?.apply { putExtra(EXTRA_RESTORED, true) }
        if (restored != null) {
            ProtonLogger.i(
                TAG,
                if (alwaysOn) "Always-on VPN start: restoring the last tunnel"
                else "Restarted by the system: restoring the last tunnel"
            )
            startTunnel(restored)
            return
        }

        ProtonLogger.i(TAG, "No saved tunnel to restore; asking the app for a configuration")
        connecting = true
        updateNotification(STATE_CONNECTING, ensureForeground = true)
        sendState(null)
        requestConfigFromApp("no saved tunnel")
        appConfigWaitJob?.cancel()
        appConfigWaitJob = scope.launch {
            delay(APP_CONFIG_WAIT)
            if (lastConnectIntent == null && state == VpnTunnelState.DOWN) {
                ProtonLogger.w(TAG, "The app did not provide a configuration; stopping")
                connecting = false
                sendState(VpnTunnelState.DOWN)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Wakes the app process (it may be dead) and asks it to build a fresh configuration, e.g. when
     * a restored snapshot's certificate has expired. Throttled so a dead network cannot spin it.
     */
    private fun requestConfigFromApp(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (lastAppRequestAt != 0L && now - lastAppRequestAt < APP_REQUEST_THROTTLE_MS) return
        lastAppRequestAt = now
        ProtonLogger.i(TAG, "Asking the app to reconnect: $reason")
        runCatching {
            sendBroadcast(
                Intent(VpnControlReceiver.ACTION_REQUEST_CONNECT)
                    .setClass(this, VpnControlReceiver::class.java)
                    .putExtra(EXTRA_FAILURE_REASON, reason)
            )
        }.onFailure { ProtonLogger.w(TAG, "Could not reach the app: ${it.message}") }
    }

    private fun markVerified() {
        // While a new engine is starting, a handshake answer still belongs to the old one.
        if (verified || state != VpnTunnelState.UP || connecting) return
        VpnEventLog.log("verified")
        verified = true
        connecting = false
        recoveryAttempt = 0
        releaseRecoveryWakeLock()
        handshakeVerificationJob?.cancel()
        handshakeVerificationJob = null
        sendState(VpnTunnelState.UP)
        updateNotification(VpnTunnelState.UP.name)
    }

    private fun startTunnel(intent: Intent) {
        appConfigWaitJob?.cancel()
        appConfigWaitJob = null
        val isReconnect = intent.getBooleanExtra(EXTRA_IS_RECONNECTING, false)
        if (!isReconnect) {
            localNetShield.beginSessionStats()
        }
        val config = intent.getStringExtra(EXTRA_CONFIG) ?: run {
            ProtonLogger.e(TAG, "Missing awgbox configuration")
            return
        }
        logicalServerId = intent.getStringExtra(EXTRA_LOGICAL_SERVER_ID)
        notificationsEnabled = intent.getBooleanExtra(EXTRA_NOTIFICATIONS_ENABLED, true)
        killSwitchEnabled = intent.getBooleanExtra(EXTRA_KILL_SWITCH_ENABLED, false)
        readHealthSettings(intent)
        platform.configureSplitTunneling(
            enabled = intent.getBooleanExtra(EXTRA_SPLIT_TUNNELING_ENABLED, false),
            mode = intent.getStringExtra(EXTRA_SPLIT_TUNNELING_MODE) ?: "exclude",
            selectedApps = intent.getStringArrayListExtra(EXTRA_EXCLUDED_APPS).orEmpty().toSet()
        )
        restoredFromSnapshot = intent.getBooleanExtra(EXTRA_RESTORED, false)
        VpnEventLog.log("start tunnel (reconnect=$isReconnect, restored=$restoredFromSnapshot)")
        // Every restart replays this exact command, so split tunnelling and the health settings
        // survive automatic reconnects (the retry intents used to be rebuilt without them).
        val connectIntent = Intent(intent).apply {
            setClass(this@ProtonVpnService, ProtonVpnService::class.java)
            action = ACTION_CONNECT
            removeExtra(EXTRA_IS_RECONNECTING)
        }
        lastConnectIntent = connectIntent
        if (!restoredFromSnapshot && config != persistedConfig) {
            snapshotStore.save(connectIntent)
            persistedConfig = config
        }
        snapshotStore.userStopped = false
        logFullConfigToLogcat(config)
        manualDisconnect = false
        val generation = lifecycleGeneration.incrementAndGet()
        handshakeVerificationJob?.cancel()
        handshakeVerificationJob = null
        networkChangeJob?.cancel()
        networkChangeJob = null
        stopHealthMonitor()
        stopTrafficUpdates()
        // The running engine is being replaced. Leaving the state UP let a late handshake answer
        // of the old engine "verify" the new one before it existed, which then skipped starting
        // the new engine's health monitor: the tunnel stayed "connected" and dead all night.
        state = VpnTunnelState.DOWN
        handshakeObserved = false
        pendingInitiationSince = 0L
        // Only a WireGuard handshake proves the server accepted us; "engine started" does not.
        verified = verificationMode == ConnectionVerificationMode.DISABLED
        connecting = true
        updateNotification(STATE_CONNECTING, ensureForeground = true)
        sendState(null)

        reconnectJob?.cancel()
        engineJob?.cancel()
        val pendingShutdown = shutdownJob
        engineJob = scope.launch(Dispatchers.IO) {
            try {
                libboxInitDeferred.await()
                pendingShutdown?.join()
                currentCoroutineContext().ensureActive()
                if (lifecycleGeneration.get() != generation) return@launch

                engineMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (lifecycleGeneration.get() != generation) return@withLock
                    closeEngine()
                    handshakeObserved = false
                    pendingInitiationSince = 0L

                    if (!vpnNetworkMonitor.hasUsableUnderlyingNetwork()) {
                        // Without an underlying network the engine cannot bind an outbound
                        // socket and fails with "no available network interface" (ANDROID-22A).
                        // The recovery path waits for connectivity instead.
                        ProtonLogger.w(TAG, "Skipping tunnel start: no usable underlying network")
                        withContext(Dispatchers.Main) {
                            if (lifecycleGeneration.get() == generation) {
                                handleEngineFailure(permanent = false, reason = FAILURE_ENGINE)
                            }
                        }
                        return@withLock
                    }

                    try {
                        Libbox.checkConfig(config)
                    } catch (error: Exception) {
                        // A configuration the engine rejects will be rejected on every retry.
                        ProtonLogger.e(TAG, "amnezia-box rejected the configuration", error)
                        withContext(Dispatchers.Main) {
                            if (lifecycleGeneration.get() == generation) {
                                handleEngineFailure(permanent = true, reason = FAILURE_PERMANENT)
                            }
                        }
                        return@withLock
                    }

                    val server = CommandServer(this@ProtonVpnService, platform).also { it.start() }
                    startingCommandServer = server
                    var adopted = false
                    try {
                        server.startOrReloadService(config, OverrideOptions())
                        currentCoroutineContext().ensureActive()
                        if (lifecycleGeneration.get() != generation) return@withLock

                        commandServer = server
                        startingCommandServer = null
                        adopted = true
                        withContext(Dispatchers.Main) {
                            if (lifecycleGeneration.get() != generation) return@withContext
                            state = VpnTunnelState.UP
                            connecting = false
                            VpnEventLog.log("engine up")
                            resetTransportFailures()
                            sendState(VpnTunnelState.UP)
                            updateNotification(VpnTunnelState.UP.name)
                            startTrafficUpdates()
                            startHealthMonitor(generation)
                            if (!verified) {
                                if (handshakeObserved) markVerified()
                                else startHandshakeVerificationWatchdog(generation)
                            } else {
                                recoveryAttempt = 0
                            }
                        }
                    } finally {
                        if (!adopted) closeCommandServer(server)
                        if (startingCommandServer === server) startingCommandServer = null
                    }
                }
            } catch (_: CancellationException) {
                // A newer connect or disconnect owns the lifecycle now.
            } catch (error: Exception) {
                if (lifecycleGeneration.get() != generation) return@launch
                val permanent = error.message?.contains(VPN_PERMISSION_REVOKED) == true
                if (vpnNetworkMonitor.hasUsableUnderlyingNetwork() && !isMissingNetworkInterfaceError(error)) {
                    ProtonLogger.e(TAG, "Failed to start amnezia-box tunnel", error)
                } else {
                    // The device lost connectivity between the preflight and the engine start, so
                    // the engine cannot bind an outbound socket. Transient, and not a defect.
                    ProtonLogger.w(TAG, "Tunnel start aborted without connectivity: ${error.message}")
                }
                withContext(Dispatchers.Main) {
                    if (lifecycleGeneration.get() == generation) {
                        handleEngineFailure(
                            permanent = permanent,
                            reason = if (permanent) FAILURE_PERMANENT else FAILURE_ENGINE
                        )
                    }
                }
            }
        }
    }

    /**
     * Emits the exact generated sing-box configuration to local Logcat only.
     *
     * This deliberately bypasses ProtonLogger so the private key, proxy UUIDs and other
     * credentials can never become Sentry breadcrumbs or Sentry logs. Full configuration
     * logging is restricted to debug builds because Logcat is not an appropriate secret store.
     */
    private fun logFullConfigToLogcat(config: String) {
        if (!BuildConfig.DEBUG) return

        val chunks = config.chunked(LOGCAT_CHUNK_SIZE).ifEmpty { listOf("") }
        Log.d(FULL_CONFIG_LOG_TAG, "----- BEGIN AWGBOX CONFIG (${config.length} chars) -----")
        chunks.forEachIndexed { index, chunk ->
            Log.d(FULL_CONFIG_LOG_TAG, "[${index + 1}/${chunks.size}] $chunk")
        }
        Log.d(FULL_CONFIG_LOG_TAG, "----- END AWGBOX CONFIG -----")
    }

    /**
     * True when the engine refused to start because the OS had no usable network interface to
     * bind an outbound socket to. This is a transient connectivity condition (airplane mode,
     * mobile data toggling, roaming handover), not a defect, so it must not be reported as an
     * error. See ANDROID-22A / ANDROID-233.
     */
    private fun isMissingNetworkInterfaceError(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            val message = cause.message?.lowercase(Locale.ROOT)
            if (message != null && TRANSIENT_NETWORK_ERROR_MARKERS.any { message.contains(it) }) return true
            cause = cause.cause
        }
        return false
    }

    private fun canRecoverAutomatically(): Boolean =
        autoReconnectEnabled && !manualDisconnect && lastConnectIntent != null

    /**
     * The engine could not start or stopped by itself. A transient failure (no network during a
     * cell handover, a server that did not answer) is retried until it works or the user
     * disconnects. This used to depend on a hidden kill-switch flag that was off for everyone,
     * so the tunnel simply stopped as if the user had pressed "Disconnect".
     */
    private fun handleEngineFailure(permanent: Boolean, reason: String) {
        VpnEventLog.log("engine failure (permanent=$permanent, reason=$reason)")
        stopTrafficUpdates()
        stopHealthMonitor()
        handshakeVerificationJob?.cancel()
        handshakeVerificationJob = null
        state = VpnTunnelState.DOWN
        verified = false
        if (!permanent && canRecoverAutomatically()) {
            scheduleRecovery(reason)
            return
        }
        connecting = false
        sendState(VpnTunnelState.DOWN)
        sendTunnelFailed(reason)
        updateNotification(VpnTunnelState.DOWN.name)
        scope.launch(Dispatchers.IO) { localNetShield.finishSessionStats() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Restarts the last tunnel after a growing delay, but only once a physical network exists:
     * attempts made while the phone is between cells are wasted and used to end the session.
     */
    private fun scheduleRecovery(reason: String) {
        val retry = lastConnectIntent ?: return
        holdRecoveryWakeLock()
        connecting = true
        verified = false
        sendTunnelFailed(reason)
        val delayMs = RECOVERY_DELAYS_MS[recoveryAttempt.coerceAtMost(RECOVERY_DELAYS_MS.lastIndex)]
        recoveryAttempt++
        val attempt = recoveryAttempt
        ProtonLogger.w(TAG, "Recovering the tunnel ($reason), attempt $attempt in ${delayMs}ms")
        VpnEventLog.log("recovery scheduled (reason=$reason, attempt=$attempt, delay=${delayMs}ms)")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            sendState(null)
            updateNotification(STATE_CONNECTING)
            delay(delayMs)
            if (!vpnNetworkMonitor.hasUsableUnderlyingNetwork()) {
                ProtonLogger.i(TAG, "Waiting for a network before reconnecting")
                VpnEventLog.log("waiting for a network")
                // Waiting can take hours; the network callback wakes the phone when one appears.
                releaseRecoveryWakeLock()
                vpnNetworkMonitor.awaitUsableUnderlyingNetwork()
                holdRecoveryWakeLock()
            }
            // This process can only replay the same configuration. After a few failures, wake
            // the app so it can refresh the certificate or move to another server.
            val appRequestThreshold =
                if (restoredFromSnapshot) RESTORED_ATTEMPTS_BEFORE_APP_REQUEST else ATTEMPTS_BEFORE_APP_REQUEST
            if (attempt >= appRequestThreshold) {
                requestConfigFromApp("tunnel does not answer after $attempt attempts")
            }
            startTunnel(Intent(retry).putExtra(EXTRA_IS_RECONNECTING, true))
        }
    }

    /** The engine runs but the server does not answer: restart it through the recovery path. */
    private fun onTunnelUnresponsive(reason: String) {
        if (manualDisconnect || connecting || state != VpnTunnelState.UP) return
        VpnEventLog.log("tunnel unresponsive ($reason)")
        if (!autoReconnectEnabled) {
            ProtonLogger.w(TAG, "Tunnel does not carry traffic ($reason); auto-reconnect is off")
            if (verified) {
                verified = false
                sendState(VpnTunnelState.UP)
                updateNotification(VpnTunnelState.UP.name)
            }
            sendTunnelFailed(reason)
            return
        }
        stopTrafficUpdates()
        stopHealthMonitor()
        state = VpnTunnelState.DOWN
        scheduleRecovery(reason)
    }

    private fun sendTunnelFailed(reason: String) {
        sendInternalBroadcast(Intent(ACTION_TUNNEL_FAILED).apply {
            putExtra(EXTRA_FAILURE_REASON, reason)
            putExtra(EXTRA_LOGICAL_SERVER_ID, logicalServerId)
            setPackage(packageName)
        })
    }

    private fun stopTunnel() {
        VpnEventLog.log("stop requested")
        manualDisconnect = true
        networkChangeJob?.cancel()
        releaseRecoveryWakeLock()
        snapshotStore.userStopped = true
        val generation = lifecycleGeneration.incrementAndGet()
        reconnectJob?.cancel()
        appConfigWaitJob?.cancel()
        handshakeVerificationJob?.cancel()
        handshakeVerificationJob = null
        stopHealthMonitor()
        engineJob?.cancel()
        connecting = false
        verified = false
        recoveryAttempt = 0
        state = VpnTunnelState.DOWN
        sendState(VpnTunnelState.DOWN)
        stopTrafficUpdates()
        updateNotification(VpnTunnelState.DOWN.name)

        val previousShutdown = shutdownJob
        shutdownJob = scope.launch(Dispatchers.IO) {
            // libbox StartOrReloadService is blocking and CloseService is not safe to call
            // concurrently with it. Cancellation marks the attempt stale; after native startup
            // returns, that same engine coroutine closes its candidate before releasing the mutex.
            previousShutdown?.join()
            localNetShield.finishSessionStats()
            engineMutex.withLock { closeEngine() }
            withContext(Dispatchers.Main) {
                // A new connect may already be waiting for this shutdown to finish. Do not
                // stop the service underneath that connection attempt.
                if (lifecycleGeneration.get() == generation && !connecting) {
                    stopSelf()
                }
            }
        }
    }

    private fun closeEngine() {
        val server = commandServer
        commandServer = null
        closeCommandServer(server)
        runCatching { tunDescriptor?.close() }
        tunDescriptor = null
    }

    @Synchronized
    private fun closeCommandServer(server: CommandServer?) {
        if (server == null || !closedCommandServers.add(server)) return
        closingCommandServer.set(true)
        try {
            runCatching { server.closeService() }
            runCatching { server.close() }
        } finally {
            closingCommandServer.set(false)
        }
    }

    private fun applySettings(intent: Intent) {
        notificationsEnabled = intent.getBooleanExtra(EXTRA_NOTIFICATIONS_ENABLED, notificationsEnabled)
        killSwitchEnabled = intent.getBooleanExtra(EXTRA_KILL_SWITCH_ENABLED, killSwitchEnabled)
        if (intent.hasExtra(EXTRA_NON_FATAL_ENABLED)) {
            ProtonLogger.isNonFatalEnabled = intent.getBooleanExtra(EXTRA_NON_FATAL_ENABLED, true)
        }
        if (intent.hasExtra(EXTRA_ANALYTICS_ENABLED)) {
            ProtonLogger.isAnalyticsEnabled = intent.getBooleanExtra(EXTRA_ANALYTICS_ENABLED, true)
        }
        readHealthSettings(intent)
        if (verificationMode == ConnectionVerificationMode.DISABLED) {
            if (state == VpnTunnelState.UP) markVerified()
        } else if (state == VpnTunnelState.UP && !verified && handshakeVerificationJob?.isActive != true) {
            startHandshakeVerificationWatchdog(lifecycleGeneration.get())
        }
        updateNotification(if (connecting) STATE_CONNECTING else state.name)
    }

    private fun readHealthSettings(intent: Intent) {
        if (intent.hasExtra(EXTRA_VERIFICATION_MODE)) {
            verificationMode = runCatching {
                ConnectionVerificationMode.valueOf(intent.getStringExtra(EXTRA_VERIFICATION_MODE).orEmpty())
            }.getOrDefault(ConnectionVerificationMode.BALANCED)
        }
        verificationRequired = intent.getBooleanExtra(EXTRA_VERIFICATION_REQUIRED, verificationRequired)
        handshakeTimeoutSeconds = intent.getIntExtra(EXTRA_HANDSHAKE_TIMEOUT_SECONDS, handshakeTimeoutSeconds)
            .coerceIn(
                SettingsManager.MIN_HANDSHAKE_RECONNECT_TIMEOUT_SECONDS,
                SettingsManager.MAX_HANDSHAKE_RECONNECT_TIMEOUT_SECONDS,
            )
        failureDetectionEnabled = intent.getBooleanExtra(EXTRA_FAILURE_DETECTION_ENABLED, failureDetectionEnabled)
        autoReconnectEnabled = intent.getBooleanExtra(EXTRA_AUTO_RECONNECT_ENABLED, autoReconnectEnabled)
        if (!failureDetectionEnabled || verificationMode == ConnectionVerificationMode.DISABLED) {
            resetTransportFailures()
        }
    }

    private fun isDeadSystemFailure(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause.javaClass.name == "android.os.DeadSystemRuntimeException" ||
                cause.javaClass.name == "android.os.DeadSystemException"
            ) return true
            cause = cause.cause
        }
        return false
    }

    /** Returns false only when Android is already tearing down its system services. */
    private fun sendInternalBroadcast(intent: Intent): Boolean = try {
        sendBroadcast(intent)
        true
    } catch (error: RuntimeException) {
        if (isDeadSystemFailure(error)) false else throw error
    }

    private fun sendState(explicitState: VpnTunnelState?) {
        sendInternalBroadcast(Intent(ACTION_STATE_CHANGED).apply {
            putExtra(EXTRA_STATE, explicitState?.name ?: STATE_CONNECTING)
            putExtra(EXTRA_LOGICAL_SERVER_ID, logicalServerId)
            putExtra(EXTRA_IS_RECONNECTING, reconnectJob?.isActive == true)
            putExtra(EXTRA_VERIFIED, verified)
            setPackage(packageName)
        })
    }

    private fun startTrafficUpdates() {
        stopTrafficUpdates()
        val uid = applicationInfo.uid
        lastRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
        lastTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
        statsJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1.seconds)
                val rx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(lastRx)
                val tx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(lastTx)
                val deltaRx = rx - lastRx
                val deltaTx = tx - lastTx
                lastRx = rx
                lastTx = tx
                lastSpeed = getString(R.string.vpn_speed_format, formatBytes(deltaTx, true), formatBytes(deltaRx, true))
                if (!sendInternalBroadcast(Intent(ACTION_STATS_UPDATED).apply {
                    putExtra(EXTRA_SPEED, lastSpeed)
                    putExtra(EXTRA_TRAFFIC_RX, formatBytes(rx, false))
                    putExtra(EXTRA_TRAFFIC_TX, formatBytes(tx, false))
                    putExtra(EXTRA_TRAFFIC_DELTA_RX, deltaRx)
                    putExtra(EXTRA_TRAFFIC_DELTA_TX, deltaTx)
                    putExtra(EXTRA_TRAFFIC_DELTA_SECONDS, 1L)
                    putExtra(EXTRA_LOGICAL_SERVER_ID, logicalServerId)
                    setPackage(packageName)
                })) return@launch
                // Re-posting the notification every second kept system_server busy and made
                // aggressive OEM battery managers treat the tunnel as a background hog.
                if (state == VpnTunnelState.UP &&
                    SystemClock.elapsedRealtime() - lastNotificationAt >= NOTIFICATION_REFRESH_MS
                ) {
                    withContext(Dispatchers.Main) { updateNotification(state.name) }
                }
            }
        }
    }

    private fun stopTrafficUpdates() {
        statsJob?.cancel()
        statsJob = null
    }

    private fun formatBytes(bytes: Long, speed: Boolean): String {
        val value = bytes.coerceAtLeast(0).toDouble()
        val (scaled, unit) = when {
            value >= 1024 * 1024 * 1024 -> value / (1024 * 1024 * 1024) to if (speed) R.string.unit_gb_s else R.string.unit_gb
            value >= 1024 * 1024 -> value / (1024 * 1024) to if (speed) R.string.unit_mb_s else R.string.unit_mb
            value >= 1024 -> value / 1024 to if (speed) R.string.unit_kb_s else R.string.unit_kb
            else -> value to if (speed) R.string.unit_b_s else R.string.unit_b
        }
        return String.format(Locale.US, if (scaled >= 1024) "%.0f %s" else "%.1f %s", scaled, getString(unit))
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val name = getString(R.string.notification_channel_name)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_SILENT_ID, getString(R.string.notification_channel_silent_name), NotificationManager.IMPORTANCE_MIN))
    }

    private fun createNotification(stateName: String): Notification {
        val serverName = connectedServerState.connectedServer.value?.name ?: getString(R.string.app_name)
        val title = when {
            stateName == VpnTunnelState.UP.name && verified -> getString(R.string.notification_title_connected, serverName)
            stateName == VpnTunnelState.UP.name -> getString(R.string.notification_title_verifying)
            stateName == STATE_CONNECTING -> getString(R.string.notification_title_connecting)
            else -> getString(R.string.notification_title_disconnected)
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ProtonVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, if (notificationsEnabled) CHANNEL_ID else CHANNEL_SILENT_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(if (stateName == VpnTunnelState.UP.name) lastSpeed else null)
            .setContentIntent(contentIntent)
            .setOngoing(stateName != VpnTunnelState.DOWN.name)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(0, getString(R.string.notification_action_disconnect), disconnectIntent)
            .build()
    }

    private fun updateNotification(stateName: String, ensureForeground: Boolean = false) {
        lastNotificationAt = SystemClock.elapsedRealtime()
        if (!shouldShowNotification(stateName, notificationsEnabled)) {
            // startForegroundService() still requires one foreground promotion. Satisfy it for
            // a disabled notification setting, then remove the notification completely.
            if (ensureForeground && !foregroundStarted) {
                startForegroundNotification(createNotification(stateName))
            }
            removeNotification()
            return
        }
        startForegroundNotification(createNotification(stateName))
    }

    private fun startForegroundNotification(notification: Notification) {
        // Some OEM builds reject the foreground notification (blocked channel, notification quota,
        // POST_NOTIFICATIONS denied) and answer with CannotPostForegroundServiceNotificationException,
        // which the system rethrows on the main thread and kills the process (ANDROID-21T).
        // Recreating the channels and swallowing the failure keeps the tunnel alive; the worst
        // case is a missing status notification.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
        } catch (error: Exception) {
            ProtonLogger.w(TAG, "Could not post the foreground notification: ${error.message}")
            runCatching { createNotificationChannels() }
        }
    }

    private fun removeNotification() {
        if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
    }

    override fun serviceStop() {
        if (closingCommandServer.get()) return
        scope.launch {
            if (closingCommandServer.get() || manualDisconnect) return@launch
            if (state != VpnTunnelState.UP && !connecting) return@launch
            // The engine stopped on its own. Treat it like any other failure rather than as a
            // user disconnect, so the tunnel comes back.
            ProtonLogger.w(TAG, "amnezia-box stopped by itself; recovering")
            handleEngineFailure(permanent = false, reason = FAILURE_ENGINE)
        }
    }
    override fun serviceReload() = Unit
    override fun getSystemProxyStatus() = SystemProxyStatus().apply { available = false; enabled = false }
    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit

    // Only reachable through Tailscale SSH agent forwarding, which this AAR does not build.
    override fun connectSSHAgent(): Int =
        throw UnsupportedOperationException("SSH agent forwarding is not supported")

    // A debug-only command from the sing-box GUI clients; deliberately never honoured here.
    override fun triggerNativeCrash(): Unit =
        throw UnsupportedOperationException("Native crash trigger is disabled")

    override fun writeDebugMessage(message: String?) {
        val logMessage = message.orEmpty()
        if (BuildConfig.DEBUG) {
            ProtonLogger.d("awgbox", logMessage)
        }
        localNetShield.recordEngineLog(logMessage)
        recordEngineEvent(logMessage)
        observeHandshake(logMessage)
        observeTransportHealth(logMessage)
    }

    /** Copies the engine messages that explain connectivity into the on-device event log. */
    private fun recordEngineEvent(message: String) {
        val normalized = message.lowercase(Locale.ROOT)
        val relevant = ENGINE_EVENT_MARKERS.firstOrNull { it in normalized } ?: return
        VpnEventLog.log("engine: $relevant")
    }

    /**
     * Follows WireGuard's handshakes. The first answered handshake verifies the tunnel. Later, an
     * initiation that stays unanswered (WireGuard retries every 5 s) means the server or the path
     * to it is gone, and [startHealthMonitor] turns that into a restart. Periodic rekeying no
     * longer flips a working tunnel back to "verifying" every two minutes.
     */
    private fun observeHandshake(message: String) {
        if (verificationMode == ConnectionVerificationMode.DISABLED) return
        when {
            isAwgHandshakeSuccess(message) -> {
                handshakeObserved = true
                pendingInitiationSince = 0L
                lastHandshakeResponseAt = SystemClock.elapsedRealtime()
                scope.launch {
                    if (state == VpnTunnelState.UP && !verified) {
                        ProtonLogger.i(TAG, "AmneziaWG handshake confirmed")
                        markVerified()
                    }
                }
            }
            isAwgHandshakeAttempt(message) -> {
                if (pendingInitiationSince == 0L) pendingInitiationSince = SystemClock.elapsedRealtime()
            }
        }
    }

    /**
     * The phone moved to another network (Wi-Fi/cellular, a new cell session or address). In
     * field logs the AWG socket rebind never brought the tunnel back by itself: every time it
     * took the 25 s stall detector and a restart. Restart right away unless the server answers
     * a handshake on the new path within a moment.
     */
    private fun onNetworkPathChanged() {
        scope.launch {
            if (state != VpnTunnelState.UP || connecting || manualDisconnect) return@launch
            val changedAt = SystemClock.elapsedRealtime()
            holdRecoveryWakeLock()
            val uid = applicationInfo.uid
            val rxAtChange = TrafficStats.getUidRxBytes(uid)
            VpnEventLog.log("network path changed while connected")
            networkChangeJob?.cancel()
            networkChangeJob = scope.launch {
                delay(NETWORK_CHANGE_SETTLE_MS)
                if (state != VpnTunnelState.UP || connecting || manualDisconnect) return@launch
                // A handshake or any data from the server on the new path means it roamed fine.
                if (lastHandshakeResponseAt > changedAt) {
                    releaseRecoveryWakeLock()
                    return@launch
                }
                if (rxAtChange >= 0 && TrafficStats.getUidRxBytes(uid) > rxAtChange) {
                    VpnEventLog.log("tunnel kept receiving after the network change")
                    releaseRecoveryWakeLock()
                    return@launch
                }
                onTunnelUnresponsive(FAILURE_NETWORK_CHANGED)
            }
        }
    }

    /**
     * Coroutine timers do not wake a sleeping phone: in field logs a 1 s recovery delay after a
     * network change ran 51 s late, and the 25 s stall check took 90 s. Keep the CPU awake while
     * the tunnel is being checked or rebuilt; the lock is dropped once it is verified and can
     * never outlive [RECOVERY_WAKE_LOCK_MS].
     */
    private fun holdRecoveryWakeLock() {
        val lock = recoveryWakeLock ?: runCatching {
            (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ProtonNext:tunnel-recovery")
                .apply { setReferenceCounted(false) }
        }.getOrNull()?.also { recoveryWakeLock = it } ?: return
        runCatching { lock.acquire(RECOVERY_WAKE_LOCK_MS) }
    }

    private fun releaseRecoveryWakeLock() {
        recoveryWakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
    }

    private fun startHandshakeVerificationWatchdog(generation: Long) {
        handshakeVerificationJob?.cancel()
        handshakeVerificationJob = scope.launch {
            delay(handshakeTimeoutSeconds.toLong().seconds)
            if (lifecycleGeneration.get() != generation || verified || manualDisconnect ||
                verificationMode == ConnectionVerificationMode.DISABLED || state != VpnTunnelState.UP
            ) return@launch
            ProtonLogger.w(TAG, "No AmneziaWG handshake in $handshakeTimeoutSeconds seconds")
            onTunnelUnresponsive(FAILURE_HANDSHAKE_TIMEOUT)
        }
    }

    private fun startHealthMonitor(generation: Long) {
        stopHealthMonitor()
        if (verificationMode == ConnectionVerificationMode.DISABLED) return
        healthJob = scope.launch {
            while (isActive) {
                delay(HEALTH_TICK_MS)
                if (lifecycleGeneration.get() != generation || state != VpnTunnelState.UP || connecting) return@launch
                val since = pendingInitiationSince
                if (verified && since != 0L && SystemClock.elapsedRealtime() - since > HANDSHAKE_STALL_MS) {
                    ProtonLogger.w(TAG, "WireGuard handshake unanswered for ${HANDSHAKE_STALL_MS / 1000}s")
                    onTunnelUnresponsive(FAILURE_HANDSHAKE_STALLED)
                    return@launch
                }
            }
        }
    }

    private fun stopHealthMonitor() {
        healthJob?.cancel()
        healthJob = null
    }

    /**
     * Proxy-chain outbounds (VLESS/VMess over TCP) do not show up in WireGuard's handshake log,
     * so their failures are still counted from engine errors. AWG itself is covered by the
     * handshake monitor; counting its transient errors during a cell handover used to restart a
     * tunnel that was about to recover by itself.
     */
    private fun observeTransportHealth(message: String) {
        if (!failureDetectionEnabled || verificationMode == ConnectionVerificationMode.DISABLED) return
        val normalized = message.lowercase(Locale.ROOT)
        if (isTransportFailure(normalized)) scope.launch { recordTransportFailure() }
    }

    private fun isTransportFailure(message: String): Boolean {
        val timedOut = "context deadline exceeded" in message ||
            "i/o timeout" in message ||
            "tls handshake timeout" in message
        val transportError = "connection reset by peer" in message ||
            "broken pipe" in message ||
            "network is unreachable" in message
        val relevantPath = "outbound/vless" in message || "outbound/vmess" in message
        return relevantPath && (timedOut || transportError)
    }

    private fun recordTransportFailure() {
        if (state != VpnTunnelState.UP || connecting || manualDisconnect) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastTransportFailureAt > verificationMode.failureWindowMs) {
            transportFailureCount = 0
        }
        lastTransportFailureAt = now
        transportFailureCount++
        ProtonLogger.w(
            TAG,
            "Tunnel transport health failure $transportFailureCount/${verificationMode.failureThreshold}"
        )

        if (transportFailureCount < verificationMode.failureThreshold) return
        if (lastHealthReconnectAt != 0L && now - lastHealthReconnectAt < verificationMode.reconnectCooldownMs) return
        if (!vpnNetworkMonitor.hasUsableUnderlyingNetwork()) return

        lastHealthReconnectAt = now
        transportFailureCount = 0
        onTunnelUnresponsive(FAILURE_TRANSPORT)
    }

    private fun resetTransportFailures() {
        transportFailureCount = 0
        lastTransportFailureAt = 0L
    }

    override fun onRevoke() {
        // Another VPN took over or the user revoked the permission: that is a real stop.
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(settingsReceiver) }
        networkChangeJob?.cancel()
        releaseRecoveryWakeLock()
        lifecycleGeneration.incrementAndGet()
        reconnectJob?.cancel()
        appConfigWaitJob?.cancel()
        handshakeVerificationJob?.cancel()
        handshakeVerificationJob = null
        stopHealthMonitor()
        engineJob?.cancel()
        shutdownJob?.cancel()
        // Never race CloseService against blocking StartOrReloadService; process teardown will
        // release a still-starting native candidate.
        startingCommandServer = null
        stopTrafficUpdates()
        removeNotification()
        closeEngine()
        platform.release()
        scope.cancel()
        super.onDestroy()
    }
}
