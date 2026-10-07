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

package ru.protonmod.next.ui.screens.settings

import ru.protonmod.next.vpn.QuicInitialSamples
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.protonmod.next.vpn.VpnTunnelState
import ru.protonmod.next.netshield.NetShieldLevel
import ru.protonmod.next.data.local.ServerLoadDisplayMode
import ru.protonmod.next.data.local.SettingsManager
import ru.protonmod.next.data.model.ObfuscationProfile
import ru.protonmod.next.data.repository.AuthRepository
import ru.protonmod.next.ota.OTAUpdateManager
import ru.protonmod.next.ui.theme.AppTheme
import ru.protonmod.next.utils.RegionUtils
import ru.protonmod.next.utils.system.SystemUtils
import ru.protonmod.next.utils.crypto.QuicI1Generator
import ru.protonmod.next.vpn.AmneziaVpnManager
import ru.protonmod.next.data.local.SessionDao
import ru.protonmod.next.data.network.byedpi.ByeDpiManager
import ru.protonmod.next.data.network.byedpi.ByeDpiStrategyTester
import ru.protonmod.next.data.model.eventbypass.EventBypassCache
import ru.protonmod.next.data.model.eventbypass.EventBypassEntry
import ru.protonmod.next.data.repository.EventBypassResult
import ru.protonmod.next.data.repository.UpdateRepository
import ru.protonmod.next.data.repository.VpnRepository
import ru.protonmod.next.eventbypass.EventBypassManager
import ru.protonmod.next.eventbypass.EventBypassSyncState
import ru.protonmod.next.vpn.ProxyLinkParser
import ru.protonmod.next.vpn.SentryConfigurator
import java.security.SecureRandom
import javax.inject.Inject

data class SettingsUiState(
    val killSwitchEnabled: Boolean = false,
    val netShieldEnabled: Boolean = false,
    val autoConnectEnabled: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val allowLanEnabled: Boolean = false,
    val reconnectHintEnabled: Boolean = true,

    // Connection configs
    val splitTunnelingEnabled: Boolean = false,
    val splitTunnelingMode: String = "exclude",
    val excludedApps: Set<String> = emptySet(),
    val excludedIps: Set<String> = emptySet(),
    val excludedDomains: Set<String> = emptySet(),
    val vpnPort: Int = 0,

    // API Bypass Feature
    val apiBypassEnabled: Boolean = false,
    val apiBypassStrategy: String = "netlify",
    val apiProxyHost: String = "",
    val apiProxyPort: Int = 1080,
    val apiProxyType: String = SettingsManager.PROXY_TYPE_SOCKS,
    val apiProxyUsername: String = "",
    val apiProxyPassword: String = "",
    val isAnyVpnActive: Boolean = false,

    // ByeDPI state
    val isByeDpiTesting: Boolean = false,
    val byeDpiTestProgress: Float = 0f,
    val byeDpiCurrentStrategy: String = "",
    val byeDpiFlags: String = "",
    val byeDpiSni: String = "google.com",
    val byeDpiResults: List<ByeDpiStrategyTester.TestResult> = emptyList(),

    // API Mirroring / Spoofing
    val spoofCountryEnabled: Boolean = false,
    val spoofCountryNull: Boolean = false,
    val spoofCountryCode: String = "",

    // Customization
    val appTheme: AppTheme = AppTheme.DARK,
    val serverLoadDisplayMode: ServerLoadDisplayMode = ServerLoadDisplayMode.ALL,

    // OTA Update Settings
    val otaUpdateFrequency: String = "daily",
    val isCheckingForUpdates: Boolean = false,
    val isUpdateAvailable: Boolean = false,

    // AWG low-level params
    val awgJc: Int = 3,
    val awgJmin: Int = 1,
    val awgJmax: Int = 3,
    val awgS1: Int = 0,
    val awgS2: Int = 0,
    val awgS3: Int = 0,
    val awgS4: Int = 0,
    val awgH1: String = "1",
    val awgH2: String = "2",
    val awgH3: String = "3",
    val awgH4: String = "4",
    val awgI1: String = SettingsManager.DEFAULT_I1,
    val awgI2: String = "",
    val awgI3: String = "",
    val awgI4: String = "",
    val awgI5: String = "",
    val awgHeaderProtectionKey: String = "",
    val awgContentPaddingAddition: String = "",
    val awgRekeyAfterTime: String = "",
    val awgRekeyTimeout: String = "",
    val awgRejectAfterTime: String = "",
    val awgKeepaliveTimeout: String = "",
    val awgMaxHandshakeAttempts: String = "",
    val awgPersistentKeepalive: String = "",
    val awgJunkLevel: Int = 0, // 0: Low, 1: Medium, 2: High, 3: Custom

    // States
    val isVpnConnected: Boolean = false,

    // Obfuscation configuration state
    val isObfuscationEnabled: Boolean = false,
    val isObfuscationAdvancedMode: Boolean = false,
    val customObfuscationProfiles: List<ObfuscationProfile> = emptyList(),
    val selectedProfileId: String = "standard_1",
    val customDns: String = "",
    /** Preferred encrypted resolver. Empty means the built-in order is used. */
    val dnsProviderId: String = "",
    val dnsOverTlsFallbackEnabled: Boolean = true,
    /** Set when the last submitted custom resolver was refused for being Russian. */
    val customDnsRejected: Boolean = false,
    /**
     * Whether the device looks Russian. DoT is mandatory there, so the screen
     * renders its switch locked on rather than merely pre-enabled.
     */
    val isRussianRegion: Boolean = false,
    val proxyChainEnabled: Boolean = false,
    val proxyChainConfig: String = "",
    val isProxyChainConfigValid: Boolean = false,
    val torModeEnabled: Boolean = false,

    // Privacy & Analytics
    val isAnalyticsEnabled: Boolean = false,
    val isCrashReportsEnabled: Boolean = true,
    val isSentryPerformanceEnabled: Boolean = false,
    val isSentryNonFatalEnabled: Boolean = true,
    val isSentrySessionReplayEnabled: Boolean = false,
    val isSentryAnrEnabled: Boolean = false,
    val isSentryMetricsEnabled: Boolean = false,
    val isSentryLogsEnabled: Boolean = false,

    // Event bypass (temporary strategy whose endpoints are fetched at runtime)
    val eventBypassOptions: List<EventBypassEntry> = emptyList(),
    val eventBypassSelectedId: String = "",
    val eventBypassName: String = "",
    val eventBypassUrl: String = "",
    val eventBypassLastSync: Long = 0L,
    /** When the config itself was last edited, as published by the website. */
    val eventBypassUpdatedAt: String = "",
    val isEventBypassRefreshing: Boolean = false,
    val eventBypassLastResult: EventBypassResult? = null,

    val isPrivacyBuild: Boolean = ru.protonmod.next.BuildConfig.IS_PRIVACY_BUILD
)

@HiltViewModel
@Suppress("UNCHECKED_CAST")
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val amneziaVpnManager: AmneziaVpnManager,
    private val vpnRepository: VpnRepository,
    private val sessionDao: SessionDao,
    private val settingsManager: SettingsManager,
    private val authRepository: AuthRepository,
    private val updateRepository: UpdateRepository,
    private val otaUpdateManager: OTAUpdateManager,
    private val eventBypassManager: EventBypassManager,
    private val byeDpiManager: ByeDpiManager,
    private val byeDpiStrategyTester: ByeDpiStrategyTester
) : ViewModel() {

    // Internal state tracking if any VPN is operating at the OS level
    private val _isAnyVpnActive = MutableStateFlow(false)
    private val _isCheckingForUpdates = MutableStateFlow(false)
    private val _isUpdateAvailable = MutableStateFlow(false)

    /**
     * Raised when the store refuses a custom resolver. Kept here rather than in
     * the store because it describes one submission, not a persisted setting.
     */
    private val _customDnsRejected = MutableStateFlow(false)

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _isAnyVpnActive.value = true
        }
        override fun onLost(network: Network) {
            _isAnyVpnActive.value = false
        }
    }

    private val awgUpdateFlow = MutableSharedFlow<Pair<String, Any>>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        viewModelScope.launch {
            otaUpdateManager.latestUpdate.collect { update ->
                _isUpdateAvailable.value = update != null
            }
        }

        // Process debounced AWG updates to prevent UI flood and OOM
        viewModelScope.launch {
            awgUpdateFlow.collectLatest { (key, value) ->
                // Short debounce for typing, but immediate for non-text params
                if (value is String) {
                    delay(400)
                }
                settingsManager.setAwgParam(key, value)
            }
        }

        // Monitor system networks to automatically detect active VPN connections
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)

            // Initial synchronous check
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            _isAnyVpnActive.value = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } catch (e: Exception) {
            // Ignore if missing permissions in some edge cases
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Ignore
        }
    }

    // Using array combine to bypass the 5 Flow limit in coroutines
    val uiState: StateFlow<SettingsUiState> = combine(
        settingsManager.killSwitchEnabled,
        settingsManager.autoConnectEnabled,
        settingsManager.notificationsEnabled,
        settingsManager.splitTunnelingEnabled,
        settingsManager.splitTunnelingMode,
        settingsManager.excludedApps,
        settingsManager.excludedIps,
        settingsManager.excludedDomains,
        settingsManager.vpnPort,
        settingsManager.awgJc,
        settingsManager.awgJmin,
        settingsManager.awgJmax,
        settingsManager.awgS1,
        settingsManager.awgS2,
        settingsManager.awgS3,
        settingsManager.awgS4,
        settingsManager.awgH1,
        settingsManager.awgH2,
        settingsManager.awgH3,
        settingsManager.awgH4,
        settingsManager.awgI1,
        settingsManager.awgI2,
        settingsManager.awgI3,
        settingsManager.awgI4,
        settingsManager.awgI5,
        settingsManager.awgHeaderProtectionKey,
        settingsManager.awgContentPaddingAddition,
        settingsManager.awgRekeyAfterTime,
        settingsManager.awgRekeyTimeout,
        settingsManager.awgRejectAfterTime,
        settingsManager.awgKeepaliveTimeout,
        settingsManager.awgMaxHandshakeAttempts,
        settingsManager.awgPersistentKeepalive,
        settingsManager.awgJunkLevel,
        amneziaVpnManager.tunnelState,
        settingsManager.obfuscationEnabled,
        settingsManager.obfuscationAdvancedMode,
        settingsManager.customProfiles,
        settingsManager.selectedProfileId,
        settingsManager.customDns,
        settingsManager.analyticsEnabled,
        settingsManager.crashReportsEnabled,
        settingsManager.sentryPerformanceEnabled,
        settingsManager.sentryNonFatalEnabled,
        settingsManager.sentrySessionReplayEnabled,
        settingsManager.sentryAnrEnabled,
        settingsManager.sentryMetricsEnabled,
        settingsManager.sentryLogsEnabled,
        settingsManager.apiBypassEnabled,
        settingsManager.apiBypassStrategy,
        settingsManager.apiProxyHost,
        settingsManager.apiProxyPort,
        settingsManager.apiProxyType,
        settingsManager.apiProxyUsername,
        settingsManager.apiProxyPassword,
        settingsManager.appTheme,
        settingsManager.serverLoadDisplayMode,
        settingsManager.spoofCountryEnabled,
        settingsManager.spoofCountryNull,
        settingsManager.spoofCountryCode,
        settingsManager.otaUpdateFrequency,
        settingsManager.allowLanEnabled,
        settingsManager.byeDpiFlags,
        settingsManager.byeDpiSni,
        byeDpiStrategyTester.isTesting,
        byeDpiStrategyTester.progress,
        byeDpiStrategyTester.currentStrategy,
        byeDpiStrategyTester.testResults,
        _isAnyVpnActive,
        _isCheckingForUpdates,
        _isUpdateAvailable,
        settingsManager.proxyChainEnabled,
        settingsManager.proxyChainConfig,
        settingsManager.torModeEnabled,
        settingsManager.reconnectHintEnabled,
        settingsManager.netShieldLevel,
        // New flows must be appended here: the transform below reads them positionally,
        // so inserting one in the middle would silently shift every later index.
        settingsManager.eventBypassName,
        settingsManager.eventBypassUrl,
        settingsManager.eventBypassLastSync,
        eventBypassManager.syncState,
        settingsManager.eventBypassEvents,
        settingsManager.eventBypassSelectedId,
        settingsManager.eventBypassUpdatedAt,
        settingsManager.dnsProviderId,
        settingsManager.dnsOverTlsFallbackEnabled,
        _customDnsRejected
    ) { args: Array<Any?> ->
        SettingsUiState(
            killSwitchEnabled = args[0] as Boolean,
            autoConnectEnabled = args[1] as Boolean,
            notificationsEnabled = args[2] as Boolean,
            splitTunnelingEnabled = args[3] as Boolean,
            splitTunnelingMode = args[4] as String,
            excludedApps = args[5] as Set<String>,
            excludedIps = args[6] as Set<String>,
            excludedDomains = args[7] as Set<String>,
            vpnPort = args[8] as Int,
            awgJc = args[9] as Int,
            awgJmin = args[10] as Int,
            awgJmax = args[11] as Int,
            awgS1 = args[12] as Int,
            awgS2 = args[13] as Int,
            awgS3 = args[14] as Int,
            awgS4 = args[15] as Int,
            awgH1 = args[16] as String,
            awgH2 = args[17] as String,
            awgH3 = args[18] as String,
            awgH4 = args[19] as String,
            awgI1 = args[20] as String,
            awgI2 = args[21] as String,
            awgI3 = args[22] as String,
            awgI4 = args[23] as String,
            awgI5 = args[24] as String,
            awgHeaderProtectionKey = args[25] as String,
            awgContentPaddingAddition = args[26] as String,
            awgRekeyAfterTime = args[27] as String,
            awgRekeyTimeout = args[28] as String,
            awgRejectAfterTime = args[29] as String,
            awgKeepaliveTimeout = args[30] as String,
            awgMaxHandshakeAttempts = args[31] as String,
            awgPersistentKeepalive = args[32] as String,
            awgJunkLevel = args[33] as Int,
            isVpnConnected = args[34] == VpnTunnelState.UP,
            isObfuscationEnabled = args[35] as Boolean,
            isObfuscationAdvancedMode = args[36] as Boolean,
            customObfuscationProfiles = args[37] as List<ObfuscationProfile>,
            selectedProfileId = args[38] as String,
            customDns = args[39] as String,
            isAnalyticsEnabled = args[40] as Boolean,
            isCrashReportsEnabled = args[41] as Boolean,
            isSentryPerformanceEnabled = args[42] as Boolean,
            isSentryNonFatalEnabled = args[43] as Boolean,
            isSentrySessionReplayEnabled = args[44] as Boolean,
            isSentryAnrEnabled = args[45] as Boolean,
            isSentryMetricsEnabled = args[46] as Boolean,
            isSentryLogsEnabled = args[47] as Boolean,
            apiBypassEnabled = args[48] as Boolean,
            apiBypassStrategy = args[49] as String,
            apiProxyHost = args[50] as String,
            apiProxyPort = args[51] as Int,
            apiProxyType = args[52] as String,
            apiProxyUsername = args[53] as String,
            apiProxyPassword = args[54] as String,
            appTheme = args[55] as AppTheme,
            serverLoadDisplayMode = args[56] as ServerLoadDisplayMode,
            spoofCountryEnabled = args[57] as Boolean,
            spoofCountryNull = args[58] as Boolean,
            spoofCountryCode = args[59] as String,
            otaUpdateFrequency = args[60] as String,
            allowLanEnabled = args[61] as Boolean,
            byeDpiFlags = args[62] as String,
            byeDpiSni = args[63] as String,
            isByeDpiTesting = args[64] as Boolean,
            byeDpiTestProgress = args[65] as Float,
            byeDpiCurrentStrategy = args[66] as String,
            byeDpiResults = args[67] as List<ByeDpiStrategyTester.TestResult>,
            isAnyVpnActive = args[68] as Boolean,
            isCheckingForUpdates = args[69] as Boolean,
            isUpdateAvailable = args[70] as Boolean,
            proxyChainEnabled = args[71] as Boolean,
            proxyChainConfig = args[72] as String,
            isProxyChainConfigValid = ProxyLinkParser.isValid(args[72] as String),
            torModeEnabled = args[73] as Boolean,
            reconnectHintEnabled = args[74] as Boolean,
            netShieldEnabled = (args[75] as NetShieldLevel) != NetShieldLevel.DISABLED,
            eventBypassName = args[76] as String,
            eventBypassUrl = args[77] as String,
            eventBypassLastSync = args[78] as Long,
            isEventBypassRefreshing = (args[79] as EventBypassSyncState).isRefreshing,
            eventBypassLastResult = (args[79] as EventBypassSyncState).lastResult,
            eventBypassOptions = EventBypassCache.decode(args[80] as String),
            eventBypassSelectedId = args[81] as String,
            eventBypassUpdatedAt = args[82] as String,
            dnsProviderId = args[83] as String,
            dnsOverTlsFallbackEnabled = args[84] as Boolean,
            customDnsRejected = args[85] as Boolean,
            // Read directly rather than as a flow: the region cannot change
            // while the process is alive.
            isRussianRegion = RegionUtils.isRussianRegion(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    fun setAutoConnect(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setAutoConnect(enabled)
        }
    }

    fun setNotifications(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setNotifications(enabled)
        }
    }

    fun setAllowLanEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setAllowLanEnabled(enabled)
        }
    }

    fun setReconnectHintEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setReconnectHintEnabled(enabled)
        }
    }

    fun setAppTheme(theme: AppTheme) {
        viewModelScope.launch {
            settingsManager.setAppTheme(theme)
        }
    }

    fun setServerLoadDisplayMode(mode: ServerLoadDisplayMode) {
        viewModelScope.launch {
            settingsManager.setServerLoadDisplayMode(mode)
        }
    }

    fun setOtaUpdateFrequency(frequency: String) {
        viewModelScope.launch {
            settingsManager.setOtaUpdateFrequency(frequency)
            otaUpdateManager.scheduleUpdateCheck()
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            _isCheckingForUpdates.value = true
            try {
                otaUpdateManager.checkForUpdatesNow()
            } finally {
                _isCheckingForUpdates.value = false
            }
        }
    }

    /** Switches to another published bypass. Cached, so it works offline. */
    fun selectEventBypass(id: String) {
        viewModelScope.launch {
            eventBypassManager.selectEvent(id)
        }
    }

    /**
     * Manual refresh of the event bypass address. The repository refuses the fetch
     * when there is no internet or a third-party VPN is up, and reports why.
     */
    fun refreshEventBypass() {
        viewModelScope.launch {
            eventBypassManager.refreshNow()
        }
    }

    fun setSplitTunneling(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setSplitTunnelingEnabled(enabled)
        }
    }

    fun setSplitTunnelingMode(mode: String) {
        viewModelScope.launch {
            settingsManager.setSplitTunnelingMode(mode)
        }
    }

    fun setVpnPort(port: Int) {
        viewModelScope.launch {
            settingsManager.setVpnPort(port)
        }
    }

    /**
     * Saves a custom resolver.
     *
     * The store refuses Russian resolvers, so the outcome is surfaced instead of
     * discarded. Without this the screen would close as though the address had
     * been accepted, leaving the old value in place with no explanation.
     */
    fun setCustomDns(dns: String) {
        viewModelScope.launch {
            val accepted = settingsManager.setCustomDns(dns)
            _customDnsRejected.value = !accepted
        }
    }

    fun clearCustomDnsRejection() {
        _customDnsRejected.value = false
    }

    fun setDnsProviderId(providerId: String) {
        viewModelScope.launch {
            settingsManager.setDnsProviderId(providerId)
        }
    }

    fun setDnsOverTlsFallbackEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setDnsOverTlsFallbackEnabled(enabled)
        }
    }

    fun setApiBypassEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setApiBypassEnabled(enabled)
        }
    }

    fun setApiBypassStrategy(strategy: String) {
        viewModelScope.launch {
            settingsManager.setApiBypassStrategy(strategy)
        }
    }

    fun startByeDpiTesting(mode: String = "full") {
        viewModelScope.launch {
            val sites = try {
                context.assets.open("proxytest_proton.sites").bufferedReader().readLines().filter { it.isNotBlank() }
            } catch (e: Exception) {
                listOf("google.com", "proton.me", "github.com")
            }
            byeDpiStrategyTester.startTesting(mode, sites)
        }
    }

    fun stopByeDpiTesting() {
        byeDpiStrategyTester.stopTesting()
    }

    fun setByeDpiSni(sni: String) {
        viewModelScope.launch {
            settingsManager.setByeDpiSni(sni)
        }
    }

    fun setByeDpiFlags(flags: String) {
        viewModelScope.launch {
            settingsManager.setByeDpiFlags(flags)
        }
    }

    fun setApiProxyHost(host: String) {
        viewModelScope.launch {
            settingsManager.setApiProxyHost(host)
        }
    }

    fun setApiProxyPort(port: Int) {
        viewModelScope.launch {
            settingsManager.setApiProxyPort(port)
        }
    }

    fun setApiProxyType(type: String) {
        viewModelScope.launch {
            settingsManager.setApiProxyType(type)
        }
    }

    fun setApiProxyUsername(username: String) {
        viewModelScope.launch {
            settingsManager.setApiProxyUsername(username)
        }
    }

    fun setApiProxyPassword(password: String) {
        viewModelScope.launch {
            settingsManager.setApiProxyPassword(password)
        }
    }

    fun setSpoofCountryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setSpoofCountryEnabled(enabled)
            // Trigger refresh if we have a session
            sessionDao.getSession()?.let {
                vpnRepository.refreshServersBackground(it.accessToken, it.sessionId, it.userTier, forceRefresh = true)
            }
        }
    }

    fun setSpoofCountryNull(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setSpoofCountryNull(enabled)
            // Trigger refresh
            sessionDao.getSession()?.let {
                vpnRepository.refreshServersBackground(it.accessToken, it.sessionId, it.userTier, forceRefresh = true)
            }
        }
    }

    fun setSpoofCountryCode(code: String) {
        viewModelScope.launch {
            settingsManager.setSpoofCountryCode(code)
        }
    }

    fun refreshServersAfterSpoofChange() {
        viewModelScope.launch {
            sessionDao.getSession()?.let {
                vpnRepository.refreshServersBackground(it.accessToken, it.sessionId, it.userTier, forceRefresh = true)
            }
        }
    }

    fun setObfuscationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setObfuscationEnabled(enabled)
        }
    }

    fun setObfuscationAdvancedMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setObfuscationAdvancedMode(enabled)
        }
    }

    fun setProxyChainEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setProxyChainEnabled(enabled)
            if (!enabled) settingsManager.setObfuscationEnabled(true)
        }
    }

    fun setConnectionProtectionMode(
        proxyChainEnabled: Boolean,
        obfuscationEnabled: Boolean
    ) {
        viewModelScope.launch {
            settingsManager.setProxyChainEnabled(proxyChainEnabled)
            settingsManager.setObfuscationEnabled(obfuscationEnabled && !proxyChainEnabled)
        }
    }

    fun setProxyChainConfig(config: String) {
        viewModelScope.launch { settingsManager.setProxyChainConfig(config) }
    }

    fun setTorModeEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsManager.setTorModeEnabled(enabled) }
    }

    fun setAnalyticsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setAnalyticsEnabled(enabled)
            ru.protonmod.next.vpn.SentryConfigurator.applySettings(settingsManager)
        }
    }

    fun setCrashReportsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setCrashReportsEnabled(enabled)
            ru.protonmod.next.vpn.SentryConfigurator.applySettings(settingsManager)
        }
    }

    fun setSentryPerformanceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setSentryPerformanceEnabled(enabled)
            ru.protonmod.next.vpn.SentryConfigurator.applySettings(settingsManager)
        }
    }

    fun setSentryNonFatalEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setSentryNonFatalEnabled(enabled)
            ru.protonmod.next.vpn.SentryConfigurator.applySettings(settingsManager)
        }
    }

    fun setSentrySessionReplayEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setSentrySessionReplayEnabled(enabled)
            ru.protonmod.next.vpn.SentryConfigurator.applySettings(settingsManager)
        }
    }

    fun setSentryAnrEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setSentryAnrEnabled(enabled)
            ru.protonmod.next.vpn.SentryConfigurator.applySettings(settingsManager)
        }
    }

    fun setSentryMetricsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setSentryMetricsEnabled(enabled)
            ru.protonmod.next.vpn.SentryConfigurator.applySettings(settingsManager)
        }
    }

    fun setSentryLogsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setSentryLogsEnabled(enabled)
            SentryConfigurator.applySettings(settingsManager)
        }
    }

    fun updateAwgParam(key: String, value: Any) {
        awgUpdateFlow.tryEmit(key to value)
    }

    fun setAwgParams(
        jc: Int, jmin: Int, jmax: Int, s1: Int, s2: Int, s3: Int = 0, s4: Int = 0,
        h1: String, h2: String, h3: String, h4: String,
        i1: String, i2: String = "", i3: String = "", i4: String = "", i5: String = "",
        headerProtectionKey: String = "",
        contentPaddingAddition: String = "",
        rekeyAfterTime: String = "",
        rekeyTimeout: String = "",
        rejectAfterTime: String = "",
        keepaliveTimeout: String = "",
        maxHandshakeAttempts: String = "",
        persistentKeepalive: String = "",
        junkLevel: Int = 3
    ) {
        viewModelScope.launch {
            settingsManager.setAwgParams(jc, jmin, jmax, s1, s2, s3, s4, h1, h2, h3, h4, i1, i2, i3, i4, i5, headerProtectionKey, contentPaddingAddition, rekeyAfterTime, rekeyTimeout, rejectAfterTime, keepaliveTimeout, maxHandshakeAttempts, persistentKeepalive, junkLevel)
        }
    }

    fun selectObfuscationProfile(profile: ObfuscationProfile) {
        viewModelScope.launch {
            settingsManager.setSelectedProfileId(profile.id)
            setAwgParams(
                jc = profile.jc, jmin = profile.jmin, jmax = profile.jmax,
                s1 = profile.s1, s2 = profile.s2, s3 = profile.s3, s4 = profile.s4,
                h1 = profile.h1, h2 = profile.h2, h3 = profile.h3, h4 = profile.h4,
                i1 = profile.i1, i2 = profile.i2, i3 = profile.i3, i4 = profile.i4, i5 = profile.i5,
                headerProtectionKey = profile.headerProtectionKey,
                contentPaddingAddition = profile.contentPaddingAddition,
                rekeyAfterTime = profile.rekeyAfterTime,
                rekeyTimeout = profile.rekeyTimeout,
                rejectAfterTime = profile.rejectAfterTime,
                keepaliveTimeout = profile.keepaliveTimeout,
                maxHandshakeAttempts = profile.maxHandshakeAttempts,
                persistentKeepalive = profile.persistentKeepaliveInterval,
                junkLevel = profile.junkLevel
            )
        }
    }

    fun saveObfuscationProfile(profile: ObfuscationProfile) {
        viewModelScope.launch {
            val currentList = uiState.value.customObfuscationProfiles
            val index = currentList.indexOfFirst { it.id == profile.id }
            val newList = if (index != -1) {
                currentList.toMutableList().apply { this[index] = profile }
            } else {
                currentList + profile
            }
            settingsManager.saveCustomProfiles(newList)
            // Ensure parameters are synced to current selection if it was the edited profile
            if (uiState.value.selectedProfileId == profile.id) {
                selectObfuscationProfile(profile)
            }
        }
    }

    fun applyJunkPreset(level: Int) {
        val (jc, jmin, jmax) = when (level) {
            0 -> Triple(3, 1, 3)     // Low (Standard)
            1 -> Triple(10, 50, 100)  // Medium
            2 -> Triple(20, 400, 800) // High (Safer values to prevent native crash)
            else -> return
        }

        val currentState = uiState.value
        setAwgParams(
            jc = jc, jmin = jmin, jmax = jmax,
            s1 = currentState.awgS1, s2 = currentState.awgS2,
            s3 = currentState.awgS3, s4 = currentState.awgS4,
            h1 = currentState.awgH1, h2 = currentState.awgH2, h3 = currentState.awgH3, h4 = currentState.awgH4,
            i1 = currentState.awgI1, i2 = currentState.awgI2, i3 = currentState.awgI3, i4 = currentState.awgI4, i5 = currentState.awgI5,
            headerProtectionKey = currentState.awgHeaderProtectionKey,
            contentPaddingAddition = currentState.awgContentPaddingAddition,
            rekeyAfterTime = currentState.awgRekeyAfterTime,
            rekeyTimeout = currentState.awgRekeyTimeout,
            rejectAfterTime = currentState.awgRejectAfterTime,
            keepaliveTimeout = currentState.awgKeepaliveTimeout,
            maxHandshakeAttempts = currentState.awgMaxHandshakeAttempts,
            persistentKeepalive = currentState.awgPersistentKeepalive,
            junkLevel = level
        )
    }

    fun randomizeI1() {
        val i1List = QuicInitialSamples.ALL
        val randomHex = i1List.random()
        
        val currentState = uiState.value
        setAwgParams(
            jc = currentState.awgJc, jmin = currentState.awgJmin, jmax = currentState.awgJmax,
            s1 = currentState.awgS1, s2 = currentState.awgS2, s3 = currentState.awgS3, s4 = currentState.awgS4,
            h1 = currentState.awgH1, h2 = currentState.awgH2, h3 = currentState.awgH3, h4 = currentState.awgH4,
            i1 = randomHex, i2 = currentState.awgI2, i3 = currentState.awgI3, i4 = currentState.awgI4, i5 = currentState.awgI5,
            headerProtectionKey = currentState.awgHeaderProtectionKey,
            contentPaddingAddition = currentState.awgContentPaddingAddition,
            rekeyAfterTime = currentState.awgRekeyAfterTime,
            rekeyTimeout = currentState.awgRekeyTimeout,
            rejectAfterTime = currentState.awgRejectAfterTime,
            keepaliveTimeout = currentState.awgKeepaliveTimeout,
            maxHandshakeAttempts = currentState.awgMaxHandshakeAttempts,
            persistentKeepalive = currentState.awgPersistentKeepalive,
            junkLevel = currentState.awgJunkLevel
        )
    }

    fun generateI1FromDomain(domain: String) {
        viewModelScope.launch {
            val i1 = QuicI1Generator.generateI1(domain)
            val currentState = uiState.value
            setAwgParams(
                jc = currentState.awgJc, jmin = currentState.awgJmin, jmax = currentState.awgJmax,
                s1 = currentState.awgS1, s2 = currentState.awgS2, s3 = currentState.awgS3, s4 = currentState.awgS4,
                h1 = currentState.awgH1, h2 = currentState.awgH2, h3 = currentState.awgH3, h4 = currentState.awgH4,
                i1 = i1, i2 = currentState.awgI2, i3 = currentState.awgI3, i4 = currentState.awgI4, i5 = currentState.awgI5,
                headerProtectionKey = currentState.awgHeaderProtectionKey,
                contentPaddingAddition = currentState.awgContentPaddingAddition,
                rekeyAfterTime = currentState.awgRekeyAfterTime,
                rekeyTimeout = currentState.awgRekeyTimeout,
                rejectAfterTime = currentState.awgRejectAfterTime,
                keepaliveTimeout = currentState.awgKeepaliveTimeout,
                maxHandshakeAttempts = currentState.awgMaxHandshakeAttempts,
                persistentKeepalive = currentState.awgPersistentKeepalive,
                junkLevel = currentState.awgJunkLevel
            )
        }
    }

    fun generateHeaderProtectionKey() {
        val key = SecureRandom().let { sr ->
            val bytes = ByteArray(32)
            sr.nextBytes(bytes)
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
        val currentState = uiState.value
        setAwgParams(
            jc = currentState.awgJc, jmin = currentState.awgJmin, jmax = currentState.awgJmax,
            s1 = currentState.awgS1, s2 = currentState.awgS2, s3 = currentState.awgS3, s4 = currentState.awgS4,
            h1 = currentState.awgH1, h2 = currentState.awgH2, h3 = currentState.awgH3, h4 = currentState.awgH4,
            i1 = currentState.awgI1, i2 = currentState.awgI2, i3 = currentState.awgI3, i4 = currentState.awgI4, i5 = currentState.awgI5,
            headerProtectionKey = key,
            contentPaddingAddition = currentState.awgContentPaddingAddition,
            rekeyAfterTime = currentState.awgRekeyAfterTime,
            rekeyTimeout = currentState.awgRekeyTimeout,
            rejectAfterTime = currentState.awgRejectAfterTime,
            keepaliveTimeout = currentState.awgKeepaliveTimeout,
            maxHandshakeAttempts = currentState.awgMaxHandshakeAttempts,
            persistentKeepalive = currentState.awgPersistentKeepalive,
            junkLevel = currentState.awgJunkLevel
        )
    }

    fun resetToStandard() {
        val standard = ObfuscationProfile.getStandardProfile()
        selectObfuscationProfile(standard)
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }
}
