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

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.core.net.toUri
import ru.protonmod.next.utils.ProtonLogger
import retrofit2.HttpException
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.Inet4Address
import java.net.InetAddress
import ru.protonmod.next.data.local.SettingsManager
import ru.protonmod.next.data.local.ConnectionVerificationMode
import ru.protonmod.next.netshield.LocalNetShield
import ru.protonmod.next.data.local.SessionEntity
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import ru.protonmod.next.data.local.SessionDao
import ru.protonmod.next.data.network.LogicalServer
import ru.protonmod.next.data.network.PhysicalServer
import ru.protonmod.next.data.repository.AuthRepository
import ru.protonmod.next.data.repository.VpnRepository
import ru.protonmod.next.data.state.ConnectedServerState
import ru.protonmod.next.di.ApplicationScope
import ru.protonmod.next.utils.coroutines.DispatcherProvider
import ru.protonmod.next.utils.crypto.CryptoWrapper
import ru.protonmod.next.utils.system.SystemContextWrapper
import java.io.ByteArrayInputStream
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

@Singleton
class AmneziaVpnManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsManager: SettingsManager,
    private val vpnRepositoryProvider: Provider<VpnRepository>,
    private val sessionDao: SessionDao,
    private val connectedServerState: ConnectedServerState,
    private val systemContextWrapper: SystemContextWrapper,
    private val cryptoWrapper: CryptoWrapper,
    private val awgBoxConfigGenerator: AwgBoxConfigGenerator,
    private val localNetShield: LocalNetShield,
    private val nextVpnManager: NextVpnManager,
    private val authRepositoryProvider: Provider<AuthRepository>,
    private val vpnNetworkMonitor: VpnNetworkMonitor,
    private val trafficStatsRecorder: TrafficStatsRecorder,
    private val dispatcherProvider: DispatcherProvider,
    @ApplicationScope private val applicationScope: CoroutineScope,
    private val obfuscationAdvisor: ObfuscationAdvisor
) {
    companion object {
        private const val TAG = "AmneziaVpnManager"
        private const val PROTON_CLIENT_IP = "10.2.0.2"
        private const val PROTON_DNS_IP = "10.2.0.1" // Fallback default DNS
        private const val DNS_RETRY_COUNT = 5
        private const val DNS_RETRY_DELAY_MS = 1000L
        private const val STATE_CONNECTING = "CONNECTING"

        private const val REFRESH_THRESHOLD_MS = 1 * 3600 * 1000L // 1 hour
        private const val RETRY_DELAY_MS = 15 * 60 * 1000L // 15 minutes
        private const val PERIODIC_REFRESH_MS = 2 * 3600 * 1000L // 2 hours

        /** The HTTP check through a fresh tunnel got no answer. */
        private const val REASON_PROBE_FAILED = "probe_failed"
        /**
         * Handshake failures this soon after a network change are not held against the
         * obfuscation: on a lift ride or a fall back to 3G every variant fails for a while, and
         * counting that marked working variants as blocked.
         */
        private const val LADDER_NETWORK_SETTLE_MS = 60_000L
        /** How often a tunnel stuck in "Verifying" after a failed probe is probed again. */
        private const val PROBE_RECHECK_MS = 15_000L

        /** Ports tried, in this order, when the port setting is "automatic". */
        private val AUTO_PORTS = listOf(443, 51820, 1194, 123)
        private const val MAX_FAILOVER_ATTEMPTS = 8
        /** Endpoints of one server to try before a scoped connection moves to another server. */
        private const val ATTEMPTS_PER_SCOPED_SERVER = 2
        private const val STANDARD_OBFUSCATION_PROFILE_ID = "standard_1"
        /** An exact server gets this many endpoints before the rest of its country is tried. */
        private const val ATTEMPTS_PER_EXACT_SERVER = 4
        /** Endpoints that failed longer ago than this may work again (another network, a lifted block). */
        private const val FAILOVER_MEMORY_MS = 5 * 60_000L
        /** How long a restarted VPN service may take to report back before the tunnel counts as down. */
        private val SERVICE_RESTART_GRACE = 15.seconds
    }

    sealed class CertificateState {
        data object Valid : CertificateState()
        data class ExpiringSoon(val hoursRemaining: Int) : CertificateState()
        data object Expired : CertificateState()
        data class RefreshFailed(val error: String, val isFullyExpired: Boolean) : CertificateState()
        data object Refreshing : CertificateState()
        data class Error(val message: String) : CertificateState()
    }

    private val _certState = MutableStateFlow<CertificateState>(CertificateState.Valid)
    val certState: StateFlow<CertificateState> = _certState.asStateFlow()

    sealed interface ConnectionWarning {
        data object Ipv6OnlyEndpoint : ConnectionWarning
        data object InvalidProxyConfiguration : ConnectionWarning
        /** Every server and port the connection was allowed to try stayed silent. */
        data object ServerNotResponding : ConnectionWarning
    }

    private class ExpectedConnectionException(
        val warning: ConnectionWarning,
        message: String
    ) : IllegalArgumentException(message)

    private val _connectionWarning = MutableStateFlow<ConnectionWarning?>(null)
    val connectionWarning: StateFlow<ConnectionWarning?> = _connectionWarning.asStateFlow()

    data class ObfuscationParams(
        val jc: Int, val jmin: Int, val jmax: Int,
        val s1: Int, val s2: Int, val s3: Int = 0, val s4: Int = 0,
        val h1: String, val h2: String, val h3: String, val h4: String,
        val i1: String, val i2: String = "", val i3: String = "", val i4: String = "", val i5: String = "",
        val headerProtectionKey: String = "",
        val contentPaddingAddition: String = "",
        val rekeyAfterTime: String = "",
        val rekeyTimeout: String = "",
        val rejectAfterTime: String = "",
        val keepaliveTimeout: String = "",
        val maxHandshakeAttempts: String = "",
        val persistentKeepalive: String = ""
    )

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting

    enum class VpnState {
        DISCONNECTED,
        CONNECTING,
        VERIFYING,
        CONNECTED,
        DISCONNECTING
    }

    private val _vpnState = MutableStateFlow(VpnState.DISCONNECTED)
    val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()

    private fun updateVpnState(newState: VpnState) {
        _vpnState.value = newState
        nextVpnManager.setState(newState)
    }

    private val _isRecovering = MutableStateFlow(false)
    /** The VPN service is restoring a tunnel that was working (no network, server silent). */
    val isRecovering: StateFlow<Boolean> = _isRecovering.asStateFlow()

    private val _speed = MutableStateFlow<String?>(null)
    val speed: StateFlow<String?> = _speed.asStateFlow()

    private val _trafficRx = MutableStateFlow<String?>(null)
    val trafficRx: StateFlow<String?> = _trafficRx.asStateFlow()

    private val _trafficTx = MutableStateFlow<String?>(null)
    val trafficTx: StateFlow<String?> = _trafficTx.asStateFlow()

    private val _tunnelState = MutableStateFlow(VpnTunnelState.DOWN)
    val tunnelState: StateFlow<VpnTunnelState> = _tunnelState

    private val _rawTunnelState = MutableStateFlow(VpnTunnelState.DOWN)

    /** Parameters of the last connection attempt, replayed by [reconnectCurrent]. */
    private data class LastConnectionRequest(
        val logicalServerId: String,
        val server: PhysicalServer,
        val overridePort: Int?,
        val overrideObfuscation: Boolean?,
        val obfuscationParams: ObfuscationParams?,
        /** Where a silent server may be swapped for another one; null keeps the chosen server. */
        val failoverScope: ServerScope? = null,
        /** The UDP port this attempt used; 0 until the attempt picked one. */
        val port: Int = 0,
        /** Obfuscation the ladder chose for this attempt; null when the ladder does not apply. */
        val obfuscationVariant: ObfuscationLadder.Variant? = null,
    )

    /**
     * Endpoints that did not answer during the current connection. A server that stays silent is
     * replaced by its other physical servers and ports, then — when the user asked for "fastest",
     * a country or a city rather than one exact server — by the next best server in that scope.
     */
    private class FailoverState {
        val failedEndpoints = mutableSetOf<String>()
        val failedServers = mutableSetOf<String>()
        var attempts = 0
        var attemptsOnServer = 0
        val startedAt = SystemClock.elapsedRealtime()
        val triedVariants = mutableSetOf<ObfuscationLadder.Variant>()
    }

    @Volatile
    private var lastConnectionRequest: LastConnectionRequest? = null
    @Volatile
    private var failover = FailoverState()
    private val failoverMutex = Mutex()
    /** When the VPN service last reported its state (elapsedRealtime). */
    @Volatile
    private var lastServiceMessageAt = 0L

    private var isReconnecting = false
    private var isPaused = false
    private var pauseJob: Job? = null
    private var currentServerId: String? = null
    private var connectionJob: Job? = null
    private var verificationJob: Job? = null
    private var verificationCycle: VpnNetworkMonitor.VerificationCycle? = null
    private var refreshJob: Job? = null
    private val refreshMutex = Mutex()

    init {
        // Debug builds only: the app's verification and failover steps go into the same event
        // log as the VPN service's, so a stuck state can be traced after the fact.
        VpnEventLog.init(context)
        val filter = IntentFilter().apply {
            addAction(ProtonVpnService.ACTION_STATE_CHANGED)
            addAction(ProtonVpnService.ACTION_STATS_UPDATED)
            addAction(ProtonVpnService.ACTION_TUNNEL_FAILED)
        }
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    ProtonVpnService.ACTION_STATE_CHANGED -> {
                        lastServiceMessageAt = SystemClock.elapsedRealtime()
                        val stateStr = intent.getStringExtra(ProtonVpnService.EXTRA_STATE)
                        val serverId = intent.getStringExtra(ProtonVpnService.EXTRA_LOGICAL_SERVER_ID)
                        val isServiceReconnecting = intent.getBooleanExtra(ProtonVpnService.EXTRA_IS_RECONNECTING, false)
                        _isRecovering.value = stateStr != VpnTunnelState.DOWN.name &&
                            intent.getBooleanExtra(ProtonVpnService.EXTRA_RECOVERING, false)
                        
                        if (serverId != null && serverId != currentServerId && stateStr != VpnTunnelState.DOWN.name) {
                            currentServerId = serverId
                            applicationScope.launch {
                                val resolved = vpnRepositoryProvider.get().getCachedServers().find { it.id == serverId }
                                if (resolved != null) {
                                    connectedServerState.setConnectedServer(resolved)
                                }
                            }
                        }

                        stateStr?.let { stateLabel ->
                            if (stateLabel == STATE_CONNECTING) {
                                if (!_isConnecting.value) {
                                    verificationCycle = vpnNetworkMonitor.beginVerificationCycle()
                                }
                                // While the service (re)starts the engine there is no tunnel;
                                // keeping the old UP here hid restarts from everything else.
                                verificationJob?.cancel()
                                _rawTunnelState.value = VpnTunnelState.DOWN
                                _tunnelState.value = VpnTunnelState.DOWN
                                _isConnecting.value = true
                                updateVpnState(VpnState.CONNECTING)
                                return@let
                            }

                            val newState = runCatching { VpnTunnelState.valueOf(stateLabel) }
                                .getOrElse {
                                    ProtonLogger.e(TAG, "Failed to parse tunnel state: $stateLabel")
                                    return@let
                                }
                            val previousState = _rawTunnelState.value
                            val serviceVerified = intent.getBooleanExtra(
                                ProtonVpnService.EXTRA_VERIFIED,
                                false
                            )

                            _rawTunnelState.value = newState
                            _tunnelState.value = newState
                            _isConnecting.value = false

                            when (newState) {
                                VpnTunnelState.UP -> {
                                    if (previousState != VpnTunnelState.UP) onTunnelCameUp()
                                    when {
                                        // The service saw the WireGuard handshake: the server
                                        // answered. Confirm traffic end to end before "Connected".
                                        serviceVerified -> if (_vpnState.value != VpnState.CONNECTED) {
                                            startTunnelVerification()
                                        }
                                        // Engine up, no handshake yet: never show "Connected".
                                        _vpnState.value != VpnState.VERIFYING -> {
                                            verificationJob?.cancel()
                                            updateVpnState(VpnState.VERIFYING)
                                        }
                                        else -> ProtonLogger.v(TAG, "Waiting for the WireGuard handshake")
                                    }
                                }
                                VpnTunnelState.DOWN -> {
                                    verificationCycle = null
                                    if (isReconnecting || isServiceReconnecting) {
                                        ProtonLogger.d(TAG, "Tunnel DOWN during reconnection, preserving server state")
                                    } else if (previousState != VpnTunnelState.DOWN ||
                                        _vpnState.value != VpnState.DISCONNECTED) {
                                        handleTunnelStateChange(VpnTunnelState.DOWN)
                                    }
                                }
                            }
                        }
                    }
                    ProtonVpnService.ACTION_TUNNEL_FAILED -> {
                        val reason = intent.getStringExtra(ProtonVpnService.EXTRA_FAILURE_REASON).orEmpty()
                        onTunnelFailed(reason)
                    }
                    ProtonVpnService.ACTION_STATS_UPDATED -> {
                        val serverId = intent.getStringExtra(ProtonVpnService.EXTRA_LOGICAL_SERVER_ID)
                        if (serverId != null && serverId != currentServerId && _vpnState.value != VpnState.DISCONNECTED && _vpnState.value != VpnState.DISCONNECTING) {
                            currentServerId = serverId
                            applicationScope.launch {
                                val resolved = vpnRepositoryProvider.get().getCachedServers().find { it.id == serverId }
                                if (resolved != null) {
                                    connectedServerState.setConnectedServer(resolved)
                                }
                            }
                        }

                        _speed.value = intent.getStringExtra(ProtonVpnService.EXTRA_SPEED)
                        _trafficRx.value = intent.getStringExtra(ProtonVpnService.EXTRA_TRAFFIC_RX)
                        _trafficTx.value = intent.getStringExtra(ProtonVpnService.EXTRA_TRAFFIC_TX)
                        val deltaRx = intent.getLongExtra(ProtonVpnService.EXTRA_TRAFFIC_DELTA_RX, 0L)
                        val deltaTx = intent.getLongExtra(ProtonVpnService.EXTRA_TRAFFIC_DELTA_TX, 0L)
                        val deltaSeconds = intent.getLongExtra(ProtonVpnService.EXTRA_TRAFFIC_DELTA_SECONDS, 0L)
                        if (deltaRx > 0L || deltaTx > 0L || deltaSeconds > 0L) {
                            applicationScope.launch(dispatcherProvider.io()) {
                                runCatching { trafficStatsRecorder.record(deltaRx, deltaTx, deltaSeconds) }
                                    .onFailure { ProtonLogger.w(TAG, "Traffic stats recording deferred: ${it.message}") }
                            }
                        }
                    }
                }
            }
        }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Query current VPN state from service on startup
        systemContextWrapper.queryVpnState()

        // Monitor settings changes and update the service accordingly.
        // We use a single coroutine with a small initial delay to avoid competing 
        // with the main thread during critical app boot/injection window.
        applicationScope.launch {
            delay(1000.milliseconds)
            merge(
                settingsManager.notificationsEnabled.map { Unit },
                settingsManager.killSwitchEnabled.map { Unit },
                settingsManager.sentryNonFatalEnabled.map { Unit },
                settingsManager.analyticsEnabled.map { Unit },
                settingsManager.connectionVerificationMode.map { Unit },
                settingsManager.connectionVerificationRequired.map { Unit },
                settingsManager.handshakeReconnectTimeoutSeconds.map { Unit },
                settingsManager.connectionFailureDetection.map { Unit },
                settingsManager.connectionAutoReconnect.map { Unit },
            ).collectLatest {
                updateServiceSettings()
            }
        }

        watchServiceProcess()

        applicationScope.launch {
            combine(
                settingsManager.ipRotationEnabled,
                settingsManager.ipRotationIntervalMinutes,
                tunnelState,
            ) { enabled, intervalMinutes, state -> Triple(enabled, intervalMinutes, state) }
                .collectLatest { (enabled, intervalMinutes, state) ->
                    if (!enabled || state != VpnTunnelState.UP) return@collectLatest
                    delay(intervalMinutes.toLong().minutes)
                    if (_tunnelState.value == VpnTunnelState.UP) rotateIp()
                }
        }

        applicationScope.launch {
            delay(1500.milliseconds) // Staggered initialization
            val session = sessionDao.getSession()
            if (session != null) {
                updateCertificateState(session.wgCertificate)
                if (_certState.value !is CertificateState.Valid) {
                    checkAndRefreshCertificateProactively()
                }
            }
        }
    }

    internal fun handleTunnelStateChange(newState: VpnTunnelState) {
        _rawTunnelState.value = newState
        _tunnelState.value = newState
        when (newState) {
            VpnTunnelState.UP -> {
                onTunnelCameUp()
                startTunnelVerification()
            }
            VpnTunnelState.DOWN -> {
                verificationJob?.cancel()
                if (!isReconnecting) {
                    updateVpnState(VpnState.DISCONNECTED)
                    currentServerId = null
                    connectedServerState.setConnectedServer(null)
                    _speed.value = null
                    _trafficRx.value = null
                    _trafficTx.value = null
                    applicationScope.launch(dispatcherProvider.io()) {
                        trafficStatsRecorder.flush()
                    }
                } else {
                    updateVpnState(VpnState.CONNECTING)
                }
            }
        }
    }

    /**
     * Binds to the VPN service without BIND_AUTO_CREATE, which neither starts nor keeps it alive
     * but makes Android report its death here. A killed ":vpn" process (an OEM battery manager,
     * a crash) cannot send a DOWN broadcast, so the app used to keep showing "Connected" with no
     * tunnel at all.
     */
    private fun watchServiceProcess() {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                ProtonLogger.d(TAG, "Watching the VPN service process")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                onServiceProcessDied()
            }
        }
        runCatching {
            context.bindService(
                Intent(context, ProtonVpnService::class.java).setAction(ProtonVpnService.ACTION_BIND_STATUS),
                connection,
                0
            )
        }.onFailure { ProtonLogger.w(TAG, "Could not watch the VPN service process: ${it.message}") }
    }

    private fun onServiceProcessDied() {
        if (_vpnState.value == VpnState.DISCONNECTED || _vpnState.value == VpnState.DISCONNECTING) return
        ProtonLogger.w(TAG, "The VPN service process died while the tunnel was ${_vpnState.value}")
        verificationJob?.cancel()
        _rawTunnelState.value = VpnTunnelState.DOWN
        _tunnelState.value = VpnTunnelState.DOWN
        applicationScope.launch {
            val request = lastConnectionRequest
            val session = sessionDao.getSession()
            if (request == null || session == null || isPaused || !settingsManager.connectionAutoReconnect.first()) {
                handleTunnelStateChange(VpnTunnelState.DOWN)
                return@launch
            }
            // Start the service again with the same target. If Android refuses a foreground start
            // because the app is in the background, the state falls back to "disconnected" rather
            // than claiming a tunnel that does not exist.
            val restartAt = SystemClock.elapsedRealtime()
            startBackgroundAttempt(request.logicalServerId)
            val result = connectInternal(request, session)
            if (result.isFailure) ProtonLogger.w(TAG, "Could not restart the VPN service after its death")
            delay(SERVICE_RESTART_GRACE)
            if (lastServiceMessageAt < restartAt) {
                ProtonLogger.w(TAG, "The VPN service did not come back; showing the tunnel as down")
                _isConnecting.value = false
                handleTunnelStateChange(VpnTunnelState.DOWN)
            }
        }
    }

    /** Housekeeping once per tunnel that comes up, verified or not yet. */
    private fun onTunnelCameUp() {
        isPaused = false
        pauseJob?.cancel()
        applicationScope.launch { settingsManager.setPauseEndTime(0) }
        checkAndRefreshCertificateProactively()
    }

    /**
     * Decides whether the tunnel may be shown as connected. The service reports the WireGuard
     * handshake; the balanced and aggressive modes additionally require a real HTTP exchange
     * through the new VPN network. A tunnel that fails this is no longer reported as connected
     * ("keeping the established tunnel" used to paint a dead tunnel green); another endpoint is
     * tried instead.
     */
    private fun startTunnelVerification() {
        if (verificationJob?.isActive == true) {
            ProtonLogger.v(TAG, "Connectivity verification is already running")
            return
        }

        verificationJob = applicationScope.launch {
            val mode = settingsManager.connectionVerificationMode.first()
            if (mode == ConnectionVerificationMode.DISABLED || mode.handshakeOnly) {
                verificationCycle = null
                onTunnelVerified()
                return@launch
            }

            val cycle = verificationCycle ?: vpnNetworkMonitor.beginVerificationCycle().also {
                verificationCycle = it
            }
            updateVpnState(VpnState.VERIFYING)
            ProtonLogger.d(TAG, "Checking that traffic flows through the tunnel (${mode.name.lowercase()})")

            try {
                val usable = vpnNetworkMonitor.awaitUsable(
                    cycle = cycle,
                    timeout = mode.verificationTimeoutMs.milliseconds,
                    retryDelay = mode.verificationRetryDelayMs.milliseconds,
                )
                if (_tunnelState.value != VpnTunnelState.UP) return@launch

                if (usable) {
                    ProtonLogger.i(TAG, "VPN connectivity confirmed")
                    VpnEventLog.log("app: probe passed")
                    onTunnelVerified()
                } else {
                    ProtonLogger.w(TAG, "No traffic through the tunnel; trying another endpoint")
                    VpnEventLog.log("app: probe failed")
                    onTunnelFailed(REASON_PROBE_FAILED)
                    recheckWhileVerifying(cycle, mode)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                ProtonLogger.w(TAG, "VPN usability verification failed: ${error.message}")
                if (_tunnelState.value == VpnTunnelState.UP) onTunnelFailed(REASON_PROBE_FAILED)
            } finally {
                // A cancelled check finishes after the next attempt may have begun its own cycle.
                if (verificationCycle === cycle) verificationCycle = null
            }
        }
    }

    /**
     * A failed probe hands the tunnel to failover, but failover may have nothing to switch to
     * (every endpoint tried, auto-reconnect off, no saved request) while the service keeps a
     * tunnel whose handshakes work. The screen then said "Verifying…" for good although traffic
     * flowed. Probe such a tunnel again and report it connected once a round trip passes; a
     * failover that does reconnect cancels this job.
     */
    private suspend fun recheckWhileVerifying(
        cycle: VpnNetworkMonitor.VerificationCycle,
        mode: ConnectionVerificationMode,
    ) {
        while (true) {
            delay(PROBE_RECHECK_MS)
            if (_tunnelState.value != VpnTunnelState.UP || _vpnState.value != VpnState.VERIFYING) return
            val usable = vpnNetworkMonitor.awaitUsable(
                cycle = cycle,
                timeout = mode.verificationTimeoutMs.milliseconds,
                retryDelay = mode.verificationRetryDelayMs.milliseconds,
            )
            if (_tunnelState.value != VpnTunnelState.UP || _vpnState.value != VpnState.VERIFYING) return
            if (usable) {
                ProtonLogger.i(TAG, "VPN connectivity confirmed on a later probe")
                VpnEventLog.log("app: probe passed on recheck")
                onTunnelVerified()
                return
            }
        }
    }

    private suspend fun onTunnelVerified() {
        updateVpnState(VpnState.CONNECTED)
        systemContextWrapper.setVpnVerified()
        if (_connectionWarning.value == ConnectionWarning.ServerNotResponding) {
            _connectionWarning.value = null
        }
        failover = FailoverState()
        val request = lastConnectionRequest ?: return
        request.obfuscationVariant?.let { variant ->
            withContext(dispatcherProvider.io()) { obfuscationAdvisor.recordSuccess(variant) }
        }
        if (request.port != 0 && isAutoPort(request.overridePort)) {
            settingsManager.setLastWorkingAutoPort(request.port)
        }
    }

    /**
     * The service found that a tunnel does not carry traffic. A server that never answered the
     * handshake, or answered but passed no traffic, is replaced by the next candidate. A tunnel
     * that worked and then stalled is first restarted as is by the service; only if the restart
     * does not answer either does it come back here as a handshake timeout.
     */
    private fun onTunnelFailed(reason: String) {
        when (reason) {
            ProtonVpnService.FAILURE_HANDSHAKE_TIMEOUT, REASON_PROBE_FAILED -> applicationScope.launch {
                failoverMutex.withLock { performFailover(reason) }
            }
            ProtonVpnService.FAILURE_PERMANENT -> {
                ProtonLogger.w(TAG, "VPN service gave up on the tunnel")
                _isConnecting.value = false
            }
            else -> ProtonLogger.d(TAG, "VPN service is recovering the tunnel ($reason)")
        }
    }

    private suspend fun performFailover(reason: String) {
        if (isPaused || _vpnState.value == VpnState.DISCONNECTED || _vpnState.value == VpnState.DISCONNECTING) return
        if (!settingsManager.connectionAutoReconnect.first()) {
            VpnEventLog.log("app: failover skipped ($reason, auto-reconnect off)")
            return
        }
        val request = lastConnectionRequest ?: run {
            VpnEventLog.log("app: failover skipped ($reason, no saved connection)")
            return
        }
        val session = sessionDao.getSession() ?: run {
            VpnEventLog.log("app: failover skipped ($reason, no session)")
            return
        }
        // A handshake that never came back may be the network dropping WireGuard, not the server:
        // the next attempt also climbs the obfuscation ladder. A failed probe after a handshake
        // is not about obfuscation and keeps it.
        val failedVariant = request.obfuscationVariant
        val networkSettling = vpnNetworkMonitor.physicalNetworkChangedWithin(LADDER_NETWORK_SETTLE_MS)
        // Without internet on the network itself every variant fails, and counting that taught
        // the ladder that everything but the habitual variant is blocked (0 successes each in
        // field data, as they were only ever tried while the network was dead).
        val networkHasInternet = vpnNetworkMonitor.hasValidatedUnderlyingNetwork()
        val nextVariant = if (failedVariant != null && reason == ProtonVpnService.FAILURE_HANDSHAKE_TIMEOUT) {
            if (networkSettling || !networkHasInternet) {
                val why = if (networkSettling) "network changed" else "no internet on the network"
                ProtonLogger.i(TAG, "Not counting the failure against ${failedVariant.id}: $why")
                VpnEventLog.log("ladder: ${failedVariant.id} failure not counted ($why)")
                failedVariant
            } else withContext(dispatcherProvider.io()) {
                VpnEventLog.log("ladder: ${failedVariant.id} failed on a network with internet")
                obfuscationAdvisor.nextAfterFailure(failedVariant, ladderFavorite(request), failover.triedVariants)
            }
        } else failedVariant
        val candidate = nextFailoverCandidate(request)
            // Every endpoint failed, but an obfuscation variant is still untried: retry with it.
            ?: request.takeIf { nextVariant != null && nextVariant !in failover.triedVariants }
        val next = candidate?.copy(obfuscationVariant = nextVariant) ?: run {
            ProtonLogger.w(TAG, "No other endpoint left to try; the VPN service keeps retrying")
            VpnEventLog.log("app: failover found no other endpoint ($reason)")
            _connectionWarning.value = ConnectionWarning.ServerNotResponding
            return
        }
        ProtonLogger.w(
            TAG,
            "Endpoint ${request.server.domain}:${request.port} failed ($reason); trying ${next.server.domain}:${next.port}" +
                (next.obfuscationVariant?.let { " with obfuscation ${it.id}" } ?: "")
        )
        if (next.logicalServerId != request.logicalServerId) {
            vpnRepositoryProvider.get().getCachedServers().find { it.id == next.logicalServerId }
                ?.let(connectedServerState::setConnectedServer)
        }
        VpnEventLog.log(
            "app: failover ($reason) to " +
                (if (next.logicalServerId == request.logicalServerId) "the same server" else "another server") +
                (next.obfuscationVariant?.let { " with ${it.id}" } ?: "")
        )
        currentServerId = next.logicalServerId
        connectionJob?.cancel()
        verificationJob?.cancel()
        updateVpnState(VpnState.CONNECTING)
        connectionJob = applicationScope.launch(dispatcherProvider.io()) {
            connectInternal(next, session, background = true)
        }
    }

    private suspend fun nextFailoverCandidate(request: LastConnectionRequest): LastConnectionRequest? {
        val state = failover
        state.failedEndpoints += endpointKey(request.server.id, request.port)
        state.attempts++
        state.attemptsOnServer++
        if (state.attempts > MAX_FAILOVER_ATTEMPTS) return null

        val servers = vpnRepositoryProvider.get().getCachedServers()
        val ports = if (isAutoPort(request.overridePort)) autoPortOrder()
        else listOf(request.port.takeIf { it != 0 } ?: resolvePort(request.overridePort))
        val current = servers.find { it.id == request.logicalServerId }
        // An exact server that does not answer at all is replaced by the best one in its country:
        // its ports used to be retried in a circle for as long as the block lasted.
        val scope = request.failoverScope ?: current?.let { ServerScope.Country(it.exitCountry) }
        val sameServerBudget =
            if (request.failoverScope == null) ATTEMPTS_PER_EXACT_SERVER else ATTEMPTS_PER_SCOPED_SERVER

        if (current != null && state.attemptsOnServer < sameServerBudget) {
            val physicals = ServerSelector.onlinePhysicalServers(current).take(2)
            for (port in ports) {
                for (physical in physicals) {
                    if (endpointKey(physical.id, port) !in state.failedEndpoints) {
                        return request.copy(server = physical, port = port)
                    }
                }
            }
        }

        state.failedServers += request.logicalServerId
        if (scope == null) return null
        val next = ServerSelector.fastest(servers.filter(scope::accepts), exclude = state.failedServers)
            ?: return null
        val physical = ServerSelector.pickPhysical(next) ?: return null
        state.attemptsOnServer = 0
        return request.copy(logicalServerId = next.id, server = physical, port = ports.first())
    }

    private fun endpointKey(physicalId: String, port: Int) = "$physicalId:$port"

    private suspend fun ladderApplies(request: LastConnectionRequest): Boolean {
        if (request.obfuscationParams != null || !settingsManager.autoObfuscationEnabled.first()) return false
        val obfuscation = request.overrideObfuscation ?: settingsManager.obfuscationEnabled.first()
        return !obfuscation || settingsManager.selectedProfileId.first() == STANDARD_OBFUSCATION_PROFILE_ID
    }

    /** The variant the user's own settings would use: it gets the head start on a new network. */
    private suspend fun ladderFavorite(request: LastConnectionRequest): ObfuscationLadder.Variant =
        if (request.overrideObfuscation ?: settingsManager.obfuscationEnabled.first()) ObfuscationLadder.Variant.STANDARD
        else ObfuscationLadder.Variant.NONE

    private suspend fun isAutoPort(overridePort: Int?): Boolean =
        (overridePort == null || overridePort == 0) && settingsManager.vpnPort.first() == 0

    /** Automatic ports, the last one that worked first. */
    private suspend fun autoPortOrder(): List<Int> {
        val preferred = settingsManager.lastWorkingAutoPort.first().takeIf { it in AUTO_PORTS }
        return (listOfNotNull(preferred) + AUTO_PORTS).distinct()
    }

    private suspend fun resolvePort(overridePort: Int?): Int =
        overridePort?.takeIf { it != 0 }
            ?: settingsManager.vpnPort.first().takeIf { it != 0 }
            ?: autoPortOrder().first()

    private fun updateCertificateState(certPem: String?) {
        if (certPem.isNullOrEmpty()) {
            _certState.value = CertificateState.Expired
            return
        }
        try {
            val cf = CertificateFactory.getInstance("X.509")
            val x509 = cf.generateCertificate(ByteArrayInputStream(certPem.toByteArray())) as X509Certificate
            val now = System.currentTimeMillis()
            val expiry = x509.notAfter.time

            if (now >= expiry) {
                _certState.value = CertificateState.Expired
            } else if (expiry - now < REFRESH_THRESHOLD_MS) {
                val hours = ((expiry - now) / (3600 * 1000L)).toInt()
                _certState.value = CertificateState.ExpiringSoon(hours)
            } else {
                _certState.value = CertificateState.Valid
            }
        } catch (e: Exception) {
            _certState.value = CertificateState.Expired
        }
    }

    private suspend fun performCertificateRefresh(force: Boolean = false): Result<String> {
        val result = performCertificateRefreshInternal(force)
        
        if (result.isFailure) {
            val error = result.exceptionOrNull()
            if (error is HttpException && error.code() == 401) {
                ProtonLogger.w(TAG, "Certificate refresh failed with 401. Attempting session refresh.")
                val session = sessionDao.getSession()
                if (session != null) {
                    val authResult = authRepositoryProvider.get().refreshSession(session.sessionId, session.refreshToken)
                    if (authResult.isSuccess) {
                        ProtonLogger.i(TAG, "Session refreshed successfully. Retrying certificate refresh.")
                        return performCertificateRefreshInternal(force)
                    }
                }
            }
        }
        
        return result
    }

    private suspend fun performCertificateRefreshInternal(force: Boolean = false): Result<String> = refreshMutex.withLock {
        val currentSession = sessionDao.getSession() ?: return Result.failure<String>(Exception("No session")).also {
            ProtonLogger.e(TAG, "Certificate refresh failed: No active session found in database")
        }

        val previousState = _certState.value
        _certState.value = CertificateState.Refreshing
        ProtonLogger.i(TAG, "Starting certificate refresh (force=$force, previous state: $previousState)")

        try {
            ProtonLogger.v(TAG, "Requesting new VPN keypair and certificate from API")

            val mode = if (currentSession.isExtendedCertEnabled) "persistent" else null
            val result = vpnRepositoryProvider.get().registerWireGuardKey(
                accessToken = currentSession.accessToken,
                sessionId = currentSession.sessionId,
                mode = mode
            )

            if (result.isSuccess) {
                val pair = result.getOrNull()
                val response = pair?.first
                val keyPair = pair?.second
                val newCert = response?.certificate
                
                if (newCert != null && keyPair != null) {
                    ProtonLogger.i(TAG, "Successfully obtained WireGuard certificate")

                    // Metrics
                    ProtonLogger.recordCount("cert_refresh_success", 1.0)

                    // Persist the NEW private key along with the NEW certificate and expiration times
                    sessionDao.updateVpnKeys(
                        privateKey = keyPair.privateKeyX25519,
                        publicKeyPem = keyPair.publicKeyPem,
                        certificate = newCert,
                        expiresAt = response.expirationTime ?: 0,
                        refreshAt = response.refreshTime ?: 0
                    )

                    updateCertificateState(newCert)
                    Result.success(newCert)
                } else {
                    ProtonLogger.e(TAG, "Server returned success but certificate or keys are missing")
                    _certState.value = previousState
                    Result.failure(Exception("Empty certificate in response"))
                }
            } else {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                ProtonLogger.e(TAG, "Failed to register WireGuard key with Proton API: $error", result.exceptionOrNull())

                // Metrics
                ProtonLogger.recordCount("cert_refresh_error", 1.0)

                val isFullyExpired = previousState is CertificateState.Expired ||
                        (previousState is CertificateState.RefreshFailed && previousState.isFullyExpired)
                _certState.value = CertificateState.RefreshFailed(error, isFullyExpired)
                Result.failure(result.exceptionOrNull() ?: Exception(error))
            }
        } finally {
        }
    }

    fun checkAndRefreshCertificateProactively(force: Boolean = false) {
        if (force) {
            refreshJob?.cancel()
        } else if (refreshJob?.isActive == true) {
            return
        }
        refreshJob = applicationScope.launch {
            var firstRun = force
            var currentRetryDelay = 60000L // Start retrying after 1 minute
            while (isActive) {
                val session = sessionDao.getSession() ?: break
                updateCertificateState(session.wgCertificate)

                val isConnected = _tunnelState.value == VpnTunnelState.UP

                if (_certState.value is CertificateState.Valid) {
                    // All good, check again in 2 hours
                    delay(PERIODIC_REFRESH_MS.milliseconds)
                    currentRetryDelay = 5000L
                    continue
                }

                // If VPN is inactive, we only refresh certificate when connecting or already connected.
                if (!firstRun && !isConnected && !_isConnecting.value) {
                    ProtonLogger.d(TAG, "Proactive refresh: VPN inactive. Skipping background periodic refresh.")
                    delay(PERIODIC_REFRESH_MS.milliseconds)
                    continue
                }

                firstRun = false
                ProtonLogger.d(TAG, "Proactive refresh starting (cert state: ${_certState.value})")
                val result = performCertificateRefresh(force = false)
                
                if (result.isSuccess) {
                    currentRetryDelay = 5000L
                    delay(PERIODIC_REFRESH_MS.milliseconds)
                } else {
                    // API access is expected to be preserved, so we retry with backoff.
                    // This covers cases where internet is temporarily down.
                    ProtonLogger.w(TAG, "Proactive refresh failed, retrying in ${currentRetryDelay}ms")
                    delay(currentRetryDelay.milliseconds)
                    currentRetryDelay = (currentRetryDelay * 2).coerceAtMost(RETRY_DELAY_MS)
                }
            }
        }
    }

    fun isEffectivelyExpired(): Boolean {
        val state = _certState.value
        return state is CertificateState.Expired || (state is CertificateState.RefreshFailed && state.isFullyExpired)
    }

    fun simulateExpiredCertificate() {
        _certState.value = CertificateState.Expired
    }

    suspend fun forceRefreshCertificate(): Result<String> {
        return performCertificateRefresh(force = true)
    }

    private suspend fun updateServiceSettings() {
        // If VPN is paused, we shouldn't be updating or trying to reconnect
        if (isPaused || settingsManager.pauseEndTime.first() > System.currentTimeMillis()) {
            ProtonLogger.d(TAG, "Skipping service settings update because VPN is paused.")
            return
        }

        systemContextWrapper.updateVpnSettings(
            notificationsEnabled = settingsManager.notificationsEnabled.first(),
            killSwitchEnabled = settingsManager.killSwitchEnabled.first(),
            nonFatalEnabled = settingsManager.sentryNonFatalEnabled.first(),
            analyticsEnabled = settingsManager.analyticsEnabled.first(),
            verificationMode = settingsManager.connectionVerificationMode.first(),
            verificationRequired = settingsManager.connectionVerificationRequired.first(),
            handshakeTimeoutSeconds = settingsManager.handshakeReconnectTimeoutSeconds.first(),
            failureDetectionEnabled = settingsManager.connectionFailureDetection.first(),
            autoReconnectEnabled = settingsManager.connectionAutoReconnect.first(),
        )
    }

    fun connect(
        logicalServerId: String,
        server: PhysicalServer,
        session: SessionEntity,
        overridePort: Int? = null,
        overrideObfuscation: Boolean? = null,
        obfuscationParams: ObfuscationParams? = null,
        logicalServer: LogicalServer? = null,
        forceFallback: Boolean = false,
        failoverScope: ServerScope? = null
    ) {
        // Immediate UI update to avoid "VPN" placeholder
        if (logicalServer != null) {
            connectedServerState.setConnectedServer(logicalServer)
        }

        applicationScope.launch {
            if (isPaused || settingsManager.pauseEndTime.first() > System.currentTimeMillis()) {
                val persistentEnd = settingsManager.pauseEndTime.first()
                ProtonLogger.d(TAG, "Connection blocked: VPN is currently paused (Local isPaused: $isPaused, Persistent: $persistentEnd)")
                return@launch
            }

            // Only a verified tunnel counts as "already connected". A tunnel that is up but does
            // not carry traffic used to swallow every tap on the same server.
            if (currentServerId == logicalServerId && _vpnState.value == VpnState.CONNECTED) {
                ProtonLogger.d(TAG, "Already connected to $logicalServerId")
                return@launch
            }

            connectionJob?.cancel()
            verificationJob?.cancel()

            _connectionWarning.value = null
            failover = FailoverState()
            updateVpnState(VpnState.CONNECTING)
            _isConnecting.value = true

            connectionJob = applicationScope.launch(dispatcherProvider.io()) {
                currentServerId = logicalServerId

                // Resolve logical server if not provided earlier
                if (connectedServerState.connectedServer.value?.id != logicalServerId) {
                    val resolved = vpnRepositoryProvider.get().getCachedServers().find { it.id == logicalServerId }
                    connectedServerState.setConnectedServer(resolved)
                }

                connectInternal(
                    LastConnectionRequest(
                        logicalServerId, server, overridePort, overrideObfuscation, obfuscationParams, failoverScope
                    ),
                    session,
                    forceFallback = forceFallback
                )

                // Track connection attempt
                ProtonLogger.recordCount("vpn_connection_attempt", 1.0)
            }
        }
    }

    /**
     * Connects from the background on behalf of the VPN service (Always-on start without a saved
     * tunnel, or a saved tunnel that no longer answers) and returns once the service has the
     * new configuration.
     */
    suspend fun connectForService(
        logicalServer: LogicalServer,
        server: PhysicalServer,
        session: SessionEntity,
        failoverScope: ServerScope?
    ) {
        connectedServerState.setConnectedServer(logicalServer)
        startBackgroundAttempt(logicalServer.id)
        connectInternal(
            LastConnectionRequest(logicalServer.id, server, null, null, null, failoverScope),
            session,
            background = true
        )
    }

    /**
     * Replays the last connection of this process for the VPN service; false if there is none.
     * A request that arrives while this process is already moving to another endpoint is left
     * alone: that attempt is the answer.
     */
    suspend fun reconnectCurrentForService(): Boolean {
        val request = lastConnectionRequest ?: return false
        if (connectionJob?.isActive == true || _vpnState.value == VpnState.CONNECTED) {
            ProtonLogger.d(TAG, "VPN service request ignored: a connection is already being handled")
            return true
        }
        val session = sessionDao.getSession() ?: return false
        // Continue the current round of failover instead of starting it over: a fresh state
        // retried the same ports again and again while nothing answered.
        val keepFailover = SystemClock.elapsedRealtime() - failover.startedAt < FAILOVER_MEMORY_MS
        startBackgroundAttempt(request.logicalServerId, resetFailover = !keepFailover)
        connectInternal(request, session, background = true)
        return true
    }

    private fun startBackgroundAttempt(logicalServerId: String, resetFailover: Boolean = true) {
        connectionJob?.cancel()
        verificationJob?.cancel()
        if (resetFailover) failover = FailoverState()
        currentServerId = logicalServerId
        updateVpnState(VpnState.CONNECTING)
        _isConnecting.value = true
    }

    /**
     * The configuration never reached the VPN service. The state used to stay on CONNECTING
     * forever here, and every later reconnect was skipped as "already connecting".
     */
    private fun markConnectFailed() {
        _isConnecting.value = false
        _rawTunnelState.value = VpnTunnelState.DOWN
        _tunnelState.value = VpnTunnelState.DOWN
        updateVpnState(VpnState.DISCONNECTED)
        connectedServerState.setConnectedServer(null)
        currentServerId = null
    }

    private fun normalizeIpv4Address(address: String): String? = runCatching {
        InetAddress.getByName(address)
            .takeIf { it is Inet4Address }
            ?.hostAddress
    }.getOrNull()

    /**
     * Builds the configuration for [request] and hands it to the VPN service.
     *
     * @param background true for attempts nobody is waiting on (failover, service requests): a
     *   failure then leaves the state alone because the service is still retrying on its own.
     */
    private suspend fun connectInternal(
        request: LastConnectionRequest,
        session: SessionEntity,
        forceFallback: Boolean = false,
        background: Boolean = false
    ): Result<Unit> = withContext(dispatcherProvider.io()) {
        val logicalServerId = request.logicalServerId
        val server = request.server
        val overridePort = request.overridePort
        val overrideObfuscation = request.overrideObfuscation
        val obfuscationParams = request.obfuscationParams
        try {
            // Pick the port once per attempt so a failover knows which endpoint failed.
            val attemptPort = request.port.takeIf { it != 0 } ?: resolvePort(overridePort)
            lastConnectionRequest = request.copy(port = attemptPort)

            val serverLogInfo = "${server.id} (Domain: ${server.domain}, LogicalID: $logicalServerId)"
            ProtonLogger.i(TAG, "Initiating connection to server: $serverLogInfo")
            ProtonLogger.addSentryBreadcrumb(TAG, "VPN Connection Step: Start ($serverLogInfo)", "INFO", "vpn.connect")

            _isConnecting.value = true
            var currentSession = session

            // Proactively refresh certificate if it's not valid (Expired or ExpiringSoon)
            updateCertificateState(currentSession.wgCertificate)
            if (_certState.value !is CertificateState.Valid) {
                ProtonLogger.i(TAG, "Certificate state is ${_certState.value}, attempting refresh before connection.")
                performCertificateRefresh()

                if (isEffectivelyExpired()) {
                    ProtonLogger.w(TAG, "Certificate is still effectively expired after refresh attempt. Proceeding anyway as Proton API might allow grace period.")
                }
                
                // Refresh session from DB to get the new certificate and any other potential updates
                currentSession = sessionDao.getSession() ?: currentSession
            }

            val wgPrivateKeyB64 = currentSession.wgPrivateKey ?: throw Exception("Offline VPN private key missing!").also {
                ProtonLogger.e(TAG, "Critical: VPN Private Key is null in session data")
            }
            var targetIp: String? = null
            val proxyChainEnabled = settingsManager.proxyChainEnabled.first()
            val proxyChainConfig = settingsManager.proxyChainConfig.first().trim()
            if (proxyChainEnabled && !ProxyLinkParser.isValid(proxyChainConfig)) {
                throw ExpectedConnectionException(
                    ConnectionWarning.InvalidProxyConfiguration,
                    "Proxy chain is enabled but its vless:// or vmess:// configuration is invalid"
                )
            }

            // Resolve and probe over the physical network before VpnService creates/reloads TUN.
            // This prevents server switching from routing the next endpoint lookup into the old VPN.
            val preflight = if (forceFallback) null else runCatching {
                vpnNetworkMonitor.prepareUnderlyingConnection(
                    endpointHost = server.domain,
                    proxyChainConfig = proxyChainConfig.takeIf { proxyChainEnabled },
                )
            }.getOrElse { error ->
                if (settingsManager.connectionPreflightRequired.first()) throw error
                ProtonLogger.w(TAG, "Connection preflight failed but is optional: ${error.message}")
                null
            }
            val proxyServerOverrides = preflight?.proxyServerOverrides.orEmpty()

            if (forceFallback) {
                // Skip DNS entirely and use exitIp only when it is IPv4.
                val fallbackIp = server.exitIp?.takeIf(String::isNotBlank)
                targetIp = fallbackIp?.let(::normalizeIpv4Address)
                if (targetIp != null) {
                    ProtonLogger.i(TAG, "forceFallback=true: skipping DNS resolution, using IPv4 exitIp: $targetIp")
                } else if (fallbackIp != null) {
                    throw ExpectedConnectionException(
                        ConnectionWarning.Ipv6OnlyEndpoint,
                        "The fallback endpoint is not reachable over IPv4"
                    )
                } else {
                    _isConnecting.value = false
                    _tunnelState.value = VpnTunnelState.DOWN
                    throw Exception("forceFallback=true but no exitIp available for ${server.domain}").also {
                        ProtonLogger.e(TAG, it.message!!)
                    }
                }
            } else {
                if (preflight != null) {
                    targetIp = preflight.endpointIpv4
                    ProtonLogger.i(TAG, "Connection preflight resolved ${server.domain} to IPv4 $targetIp on the underlying network")
                } else {
                    // Compatibility fallback for environments where a physical Network is not exposed.
                    ProtonLogger.d(TAG, "Resolving IPv4 for ${server.domain} (Max retries: $DNS_RETRY_COUNT)")
                    for (i in 1..DNS_RETRY_COUNT) {
                        if (!isActive) break
                        try {
                            targetIp = InetAddress.getAllByName(server.domain)
                                .filterIsInstance<Inet4Address>()
                                .firstOrNull()
                                ?.hostAddress
                            if (targetIp != null) {
                                ProtonLogger.i(TAG, "DNS resolved ${server.domain} to IPv4 $targetIp on attempt $i")
                                break
                            }
                            ProtonLogger.w(TAG, "DNS retry $i returned no IPv4 address for ${server.domain}")
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (e: Exception) {
                            ProtonLogger.w(TAG, "DNS retry $i failed for ${server.domain}: ${e.message}")
                        }
                        if (i < DNS_RETRY_COUNT) delay((DNS_RETRY_DELAY_MS * i).milliseconds)
                    }
                }

                if (targetIp == null) {
                    val fallbackIp = server.exitIp?.takeIf(String::isNotBlank)
                    targetIp = fallbackIp?.let(::normalizeIpv4Address)
                    if (targetIp != null) {
                        ProtonLogger.w(TAG, "DNS returned no IPv4 for ${server.domain}; using IPv4 exitIp: $targetIp")
                    } else if (fallbackIp != null) {
                        throw ExpectedConnectionException(
                            ConnectionWarning.Ipv6OnlyEndpoint,
                            "The selected VPN server is only reachable over IPv6"
                        )
                    } else {
                        _isConnecting.value = false
                        _tunnelState.value = VpnTunnelState.DOWN
                        throw Exception("DNS resolution failed for ${server.domain} after $DNS_RETRY_COUNT attempts")
                    }
                }
            }

            val serverPubKey = server.wgPublicKey ?: throw Exception("Missing WG Public Key for Server").also {
                ProtonLogger.e(TAG, "Critical: Server ${server.id} has no WireGuard Public Key")
            }
            
            val splitTunnelingEnabled = settingsManager.splitTunnelingEnabled.first()
            val stMode = settingsManager.splitTunnelingMode.first()
            val isIncludeMode = splitTunnelingEnabled && stMode == "include"
            val allowLan = settingsManager.allowLanEnabled.first()

            val selectedApps = if (splitTunnelingEnabled) settingsManager.excludedApps.first() else emptySet()
            val selectedIps = if (splitTunnelingEnabled) settingsManager.excludedIps.first().toMutableSet() else mutableSetOf()
            val selectedDomains = if (splitTunnelingEnabled) settingsManager.excludedDomains.first() else emptySet()

            ProtonLogger.d(TAG, "Split Tunneling: enabled=$splitTunnelingEnabled, mode=$stMode, allowLan=$allowLan, apps=${selectedApps.size}, IPs=${selectedIps.size}, domains=${selectedDomains.size}")

            // Resolve split tunneling domains
            if (splitTunnelingEnabled && selectedDomains.isNotEmpty()) {
                ProtonLogger.i(TAG, "Resolving ${selectedDomains.size} split-tunneling domains...")
                SplitTunnelingDomainRule.exactDomains(selectedDomains).forEach { domain ->
                    try {
                        val underlayIp = vpnNetworkMonitor.resolveIpv4OnUnderlying(domain)
                        val addresses = underlayIp?.let(::listOf)
                            ?: InetAddress.getAllByName(domain)
                                .filterIsInstance<Inet4Address>()
                                .mapNotNull(InetAddress::getHostAddress)
                        addresses.forEach { ip ->
                            selectedIps.add("$ip/32")
                            ProtonLogger.v(TAG, "Split-tunnel domain $domain resolved to $ip")
                        }
                    } catch (e: Exception) {
                        ProtonLogger.w(TAG, "Failed to resolve split-tunneling domain $domain: ${e.message}")
                    }
                }
            }

            // "Automatic" used to draw a random port on every connect, so whether a port blocked
            // by the network was hit was luck. Now the last working port goes first and the
            // failover walks through the rest.
            val selectedPort = attemptPort
            val isObfuscationEnabled = !proxyChainEnabled &&
                (overrideObfuscation ?: settingsManager.obfuscationEnabled.first())

            val torModeEnabled = settingsManager.torModeEnabled.first()
            ProtonLogger.i(
                TAG,
                "Connection parameters: Port=$selectedPort, AWG obfuscation=$isObfuscationEnabled, proxy chain=$proxyChainEnabled, Tor=$torModeEnabled"
            )

            val configuredParams = if (isObfuscationEnabled) {
                obfuscationParams ?: ObfuscationParams(
                    jc = settingsManager.awgJc.first(), jmin = settingsManager.awgJmin.first(), jmax = settingsManager.awgJmax.first(),
                    s1 = settingsManager.awgS1.first(), s2 = settingsManager.awgS2.first(),
                    s3 = settingsManager.awgS3.first(), s4 = settingsManager.awgS4.first(),
                    h1 = settingsManager.awgH1.first(), h2 = settingsManager.awgH2.first(), h3 = settingsManager.awgH3.first(), h4 = settingsManager.awgH4.first(),
                    i1 = settingsManager.awgI1.first(), i2 = settingsManager.awgI2.first(), i3 = settingsManager.awgI3.first(), i4 = settingsManager.awgI4.first(), i5 = settingsManager.awgI5.first(),
                    headerProtectionKey = settingsManager.awgHeaderProtectionKey.first(),
                    contentPaddingAddition = settingsManager.awgContentPaddingAddition.first(),
                    rekeyAfterTime = settingsManager.awgRekeyAfterTime.first(),
                    rekeyTimeout = settingsManager.awgRekeyTimeout.first(),
                    rejectAfterTime = settingsManager.awgRejectAfterTime.first(),
                    keepaliveTimeout = settingsManager.awgKeepaliveTimeout.first(),
                    maxHandshakeAttempts = settingsManager.awgMaxHandshakeAttempts.first(),
                    persistentKeepalive = settingsManager.awgPersistentKeepalive.first()
                )
            } else {
                ObfuscationParams(0, 0, 0, 0, 0, 0, 0, "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "")
            }

            // The obfuscation ladder replaces the junk and I1 settings when the user lets it: only
            // with the standard profile or no obfuscation, never over a custom profile, a profile's
            // own parameters or a proxy chain.
            val ladderVariant = if (!proxyChainEnabled && ladderApplies(request)) {
                request.obfuscationVariant ?: withContext(dispatcherProvider.io()) {
                    obfuscationAdvisor.pickForNewConnection(ladderFavorite(request))
                }
            } else null
            val params = if (ladderVariant != null) {
                lastConnectionRequest = request.copy(port = attemptPort, obfuscationVariant = ladderVariant)
                failover.triedVariants += ladderVariant
                ProtonLogger.i(TAG, "Obfuscation ladder: ${ladderVariant.id}")
                ObfuscationLadder.paramsFor(ladderVariant, configuredParams, obfuscationAdvisor.installSeed)
            } else configuredParams

            // Use assigned IP/DNS from session if available, fallback to defaults.
            // Paid users (Tier > 0) are often assigned unique internal IPs by the Proton API.
            val localIp = currentSession.vpnIpv4 ?: PROTON_CLIENT_IP
            val fallbackDns = currentSession.vpnDns?.split(",")?.firstOrNull() ?: PROTON_DNS_IP

            // Retrieve Custom DNS IP or fallback to Proton Assigned/Default
            val userDns = settingsManager.customDns.first().trim()
            val isValidDns = userDns.isNotEmpty() && normalizeIpv4Address(userDns) != null
            if (userDns.isNotEmpty() && !isValidDns) {
                ProtonLogger.w(TAG, "Custom DNS is not a numeric IPv4 address; falling back to the Proton DNS server")
            }
            val activeDns = if (isValidDns) userDns else fallbackDns
            ProtonLogger.i(TAG, "Using DNS Server: $activeDns, Client IP: $localIp")

            ProtonLogger.addSentryBreadcrumb(TAG, "VPN Connection Step: Building Config", "DEBUG", "vpn.connect")
            val configStr = awgBoxConfigGenerator.buildConfig(
                serverPublicKey = serverPubKey,
                privateKey = wgPrivateKeyB64,
                localIp = localIp,
                dnsServer = activeDns,
                targetIp = targetIp,
                isIncludeMode = isIncludeMode,
                allowLan = allowLan,
                // Android app ownership is applied directly by AwgBoxPlatform. Keep the
                // selection free of implementation packages so Exclude and Include cannot leak
                // package filters into one another during an engine reload.
                selectedApps = selectedApps,
                selectedIps = selectedIps,
                selectedDomains = selectedDomains,
                port = selectedPort,
                certificate = currentSession.wgCertificate,
                obfuscationParams = params,
                proxyChainConfig = proxyChainConfig.takeIf { proxyChainEnabled },
                netShieldRuleSets = localNetShield.activeRuleSets(settingsManager.netShieldLevel.first()),
                proxyServerOverrides = proxyServerOverrides,
                torModeEnabled = torModeEnabled,
                torDataDirectory = File(context.noBackupFilesDir, "tor").absolutePath,
                torExecutablePath = File(context.applicationInfo.nativeLibraryDir, "libtor.so").absolutePath
            )
            
            ProtonLogger.d(TAG, "Generated awgbox config (length=${configStr.length}, endpoint=$targetIp:$selectedPort)")

            val sessionId = System.currentTimeMillis()
            ProtonLogger.addSentryBreadcrumb(TAG, "VPN Connection Step: Starting Service (Session: $sessionId)", "INFO", "vpn.connect")
            systemContextWrapper.startVpnService(
                configStr = configStr,
                logicalServerId = logicalServerId,
                sessionId = sessionId,
                notificationsEnabled = settingsManager.notificationsEnabled.first(),
                killSwitchEnabled = settingsManager.killSwitchEnabled.first(),
                verificationMode = settingsManager.connectionVerificationMode.first(),
                verificationRequired = settingsManager.connectionVerificationRequired.first(),
                handshakeTimeoutSeconds = settingsManager.handshakeReconnectTimeoutSeconds.first(),
                failureDetectionEnabled = settingsManager.connectionFailureDetection.first(),
                autoReconnectEnabled = settingsManager.connectionAutoReconnect.first(),
                splitTunnelingEnabled = splitTunnelingEnabled,
                splitTunnelingMode = stMode,
                excludedApps = selectedApps,
                excludedIps = selectedIps
            )

            ProtonLogger.i(TAG, "VPN start command issued successfully")
            
            // Track connection success
            ProtonLogger.recordCount("vpn_connection_success", 1.0)
            
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            ProtonLogger.d(TAG, "VPN connection preparation cancelled")
            throw cancellation
        } catch (e: ExpectedConnectionException) {
            // This is an expected, actionable configuration/network condition. Keep it out of
            // Sentry and expose it to the dashboard as a warning instead of reporting a crash.
            ProtonLogger.w(TAG, "VPN connection blocked: ${e.message}")
            _connectionWarning.value = e.warning
            if (!background) markConnectFailed()
            Result.failure(e)
        } catch (e: Exception) {
            ProtonLogger.e(TAG, "Failed to connect to VPN", e)

            // Track connection failure
            ProtonLogger.recordCount("vpn_connection_failure", 1.0)

            // A background attempt runs next to a service that keeps retrying the previous
            // tunnel, so its failure must not flip the UI to "disconnected".
            if (!background) markConnectFailed()
            Result.failure(e)
        }
    }

    fun reconnect(
        logicalServerId: String,
        server: PhysicalServer,
        session: SessionEntity,
        overridePort: Int? = null,
        overrideObfuscation: Boolean? = null,
        obfuscationParams: ObfuscationParams? = null,
        logicalServer: LogicalServer? = null,
        forceFallback: Boolean = false,
        failoverScope: ServerScope? = null
    ) {
        // Immediate UI update
        if (logicalServer != null) {
            connectedServerState.setConnectedServer(logicalServer)
        }

        applicationScope.launch {
            if (isPaused || settingsManager.pauseEndTime.first() > System.currentTimeMillis()) {
                val persistentEnd = settingsManager.pauseEndTime.first()
                ProtonLogger.d(TAG, "Reconnect blocked: VPN is currently paused (Local isPaused: $isPaused, Persistent: $persistentEnd)")
                return@launch
            }

            // Ignore a second tap on the same server while its reconnect is being prepared. Any
            // other request replaces the running one: "already connecting" used to block every
            // reconnect for as long as a stuck attempt claimed to be connecting.
            if (isReconnecting && connectionJob?.isActive == true && currentServerId == logicalServerId) {
                ProtonLogger.d(TAG, "Reconnect to $logicalServerId is already in progress")
                return@launch
            }

            connectionJob?.cancel()
            verificationJob?.cancel()

            _connectionWarning.value = null
            failover = FailoverState()
            updateVpnState(VpnState.CONNECTING)
            _isConnecting.value = true

            connectionJob = applicationScope.launch {
                try {
                    isReconnecting = true
                    _isConnecting.value = true
                    currentServerId = logicalServerId

                    // Resolve logical server if not provided earlier
                    if (connectedServerState.connectedServer.value?.id != logicalServerId) {
                        val resolved = vpnRepositoryProvider.get().getCachedServers().find { it.id == logicalServerId }
                        connectedServerState.setConnectedServer(resolved)
                    }

                    disconnectInternal()
                    try {
                        withTimeout(5000.milliseconds) {
                            _rawTunnelState.first { it == VpnTunnelState.DOWN }
                        }
                    } catch (_: Exception) {
                    }
                    delay(500.milliseconds)
                    connectInternal(
                        LastConnectionRequest(
                            logicalServerId, server, overridePort, overrideObfuscation, obfuscationParams, failoverScope
                        ),
                        session,
                        forceFallback = forceFallback
                    )
                } finally {
                    isReconnecting = false
                }
            }
        }
    }

    /**
     * True when the current tunnel was established by this process, so its parameters (server,
     * profile port and obfuscation overrides) can be replayed by [reconnectCurrent].
     */
    fun canReconnectCurrent(): Boolean = lastConnectionRequest != null

    /**
     * Re-establishes the active tunnel with the same target and overrides, picking up connection
     * settings that were changed after it was established.
     */
    private suspend fun rotateIp() {
        val current = connectedServerState.connectedServer.value ?: return
        val session = sessionDao.getSession() ?: return
        val candidate = IpRotationSelector.select(
            servers = vpnRepositoryProvider.get().getCachedServers(),
            current = current,
            maxTier = session.userTier,
            keepCountry = settingsManager.ipRotationKeepCountry.first(),
        ) ?: run {
            ProtonLogger.w(TAG, "IP rotation skipped: no eligible alternative server")
            return
        }
        val physicalServer = ServerSelector.pickPhysical(candidate) ?: return
        val previous = lastConnectionRequest
        val keepCountry = settingsManager.ipRotationKeepCountry.first()
        ProtonLogger.action(TAG, "Rotating IP from ${current.id} to ${candidate.id}")
        reconnect(
            logicalServerId = candidate.id,
            server = physicalServer,
            session = session,
            overridePort = previous?.overridePort,
            overrideObfuscation = previous?.overrideObfuscation,
            obfuscationParams = previous?.obfuscationParams,
            logicalServer = candidate,
            failoverScope = if (keepCountry) ServerScope.Country(candidate.exitCountry) else ServerScope.AnyServer,
        )
    }

    fun reconnectCurrent() {
        val request = lastConnectionRequest ?: run {
            ProtonLogger.w(TAG, "Reconnect requested, but no previous connection is known")
            return
        }

        applicationScope.launch {
            val session = sessionDao.getSession() ?: run {
                ProtonLogger.w(TAG, "Reconnect requested without an active session")
                return@launch
            }
            ProtonLogger.action(TAG, "Reconnecting to apply changed connection settings")
            reconnect(
                logicalServerId = request.logicalServerId,
                server = request.server,
                session = session,
                overridePort = request.overridePort,
                overrideObfuscation = request.overrideObfuscation,
                obfuscationParams = request.obfuscationParams,
                logicalServer = connectedServerState.connectedServer.value,
                failoverScope = request.failoverScope
            )
        }
    }

    fun disconnect() {
        ProtonLogger.action(TAG, "User clicked Disconnect")
        connectionJob?.cancel()
        verificationJob?.cancel()
        pauseJob?.cancel()
        _isConnecting.value = false
        isPaused = false
        // "Server not responding" is about a connection that no longer exists.
        if (_connectionWarning.value == ConnectionWarning.ServerNotResponding) _connectionWarning.value = null
        applicationScope.launch { settingsManager.setPauseEndTime(0) }

        applicationScope.launch {
            isReconnecting = false
            currentServerId = null
            updateVpnState(VpnState.DISCONNECTING)
            disconnectInternal()
        }
    }

    fun pauseVpn(durationMs: Long) {
        ProtonLogger.action(TAG, "Pausing VPN for $durationMs ms")
        val endTime = System.currentTimeMillis() + durationMs
        isPaused = true
        
        pauseJob?.cancel()
        pauseJob = applicationScope.launch {
            settingsManager.setPauseEndTime(endTime)
            
            // Critical: Ensure no other connection jobs are running
            connectionJob?.cancel()
            verificationJob?.cancel()

            disconnectInternal()
        }
    }

    suspend fun resumeVpn() {
        val persistentPauseEnd = settingsManager.pauseEndTime.first()
        if (!isPaused && persistentPauseEnd == 0L) return

        ProtonLogger.action(TAG, "Resuming VPN (Local isPaused: $isPaused, Persistent: $persistentPauseEnd)")
        isPaused = false
        pauseJob?.cancel()
        settingsManager.setPauseEndTime(0)
    }

    private suspend fun disconnectInternal() = withContext(dispatcherProvider.io()) {
        systemContextWrapper.stopVpnService()
    }

    /**
     * Connect & Go: Centralized logic to wait for the VPN tunnel to be ready
     * and then open the specified URL in the system browser.
     */
    fun awaitTunnelAndOpenUrl(url: String) {
        if (url.isEmpty()) return

        var finalUrl = url.trim()
        if (finalUrl.isNotEmpty() && !finalUrl.contains("://")) {
            finalUrl = "https://$finalUrl"
        }

        val targetUrl = finalUrl

        applicationScope.launch(dispatcherProvider.main()) {
            ProtonLogger.d(TAG, "Connect & Go: Waiting for tunnel UP to open URL: $targetUrl")
            try {
                // Initial delay to allow the connection attempt to start and set isConnecting=true
                delay(1500.milliseconds)

                withTimeout(40000.milliseconds) {
                    // 1. If we are currently in the middle of connecting, wait for it to finish
                    if (_isConnecting.value) {
                        ProtonLogger.d(TAG, "Connect & Go: VPN is connecting, waiting...")
                        _isConnecting.first { !it }
                    }
                    
                    // 2. Then wait for the tunnel state to be UP
                    ProtonLogger.d(TAG, "Connect & Go: VPN attempt finished, waiting for UP state...")
                    _vpnState.first { it == VpnState.CONNECTED }
                }

                // 3. Extra delay to ensure routing and DNS are fully established and browser can reach the site
                ProtonLogger.d(TAG, "Connect & Go: Tunnel is UP, waiting for routing stabilization...")
                delay(3000.milliseconds)

                if (_vpnState.value == VpnState.CONNECTED) {
                    val intent = Intent(Intent.ACTION_VIEW, targetUrl.toUri()).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ProtonLogger.d(TAG, "Connect & Go: URL opened successfully: $targetUrl")
                } else {
                    ProtonLogger.w(TAG, "Connect & Go: Tunnel is not UP anymore, skipping URL open.")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.TimeoutCancellationException) {
                    // A connection that never comes up is already surfaced by the tunnel state; the
                    // unopened URL is a consequence, not a separate defect.
                    ProtonLogger.w(TAG, "Connect & Go: Timed out waiting for VPN to connect for URL: $targetUrl")
                } else {
                    ProtonLogger.e(TAG, "Connect & Go: Failed to handle URL: $targetUrl", e)
                }
            }
        }
    }
}
