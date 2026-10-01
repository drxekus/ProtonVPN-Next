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

package ru.protonmod.next.data.repository

import ru.protonmod.next.utils.ProtonLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import ru.protonmod.next.data.local.SettingsManager
import ru.protonmod.next.data.network.*
import ru.protonmod.next.data.local.AppDatabase
import ru.protonmod.next.data.local.VpnProfileEntity
import ru.protonmod.next.data.local.ServerDao
import ru.protonmod.next.data.local.ServerMapper
import ru.protonmod.next.data.local.SessionDao
import ru.protonmod.next.data.local.ServersCacheDao
import ru.protonmod.next.data.local.ServersCacheEntity
import ru.protonmod.next.data.local.CityTranslationDao
import ru.protonmod.next.data.local.CityTranslationEntity
import ru.protonmod.next.data.local.CityCacheEntity
import ru.protonmod.next.data.local.ProfileDao
import ru.protonmod.next.data.local.RecentConnectionDao
import androidx.room.withTransaction
import ru.protonmod.next.di.ApplicationScope
import ru.protonmod.next.utils.coroutines.DispatcherProvider
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import ru.protonmod.next.utils.crypto.CryptoWrapper
import ru.protonmod.next.utils.crypto.VpnKeyPair
import ru.protonmod.next.vpn.ServerSelector
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
open class VpnRepository @Inject constructor(
    private val vpnApi: ProtonVpnApi,
    private val database: AppDatabase,
    private val serverDao: ServerDao,
    private val sessionDao: SessionDao,
    private val serversCacheDao: ServersCacheDao,
    private val cityTranslationDao: CityTranslationDao,
    private val profileDao: ProfileDao,
    private val recentConnectionDao: RecentConnectionDao,
    private val cityRepository: CityRepository,
    private val dispatcherProvider: DispatcherProvider,
    private val cryptoWrapper: CryptoWrapper,
    @ApplicationScope private val managerScope: CoroutineScope,
    private val settingsManager: SettingsManager
) {
    /**
     * Helper to wrap Room transactions in a mockable way for unit tests.
     */
    protected open suspend fun <R> performTransaction(block: suspend () -> R): R {
        return database.withTransaction(block)
    }

    private var autoUpdateJob: Job? = null
    private var networkChangeJob: Job? = null
    private val fetchMutex = Mutex()

    // Variable for storing the currently executing fetch request
    private var activeFetch: Deferred<Result<List<LogicalServer>>>? = null
    private var cachedServers: List<LogicalServer> = emptyList()

    // Set to true when a force-refresh is requested while a fetch is already in-flight.
    // Checked in the fetch's finally block so a new force-refresh is triggered once safe.
    private val pendingForceRefresh = AtomicBoolean(false)

    private val _isUpdating = MutableStateFlow(false)
    val isUpdating: StateFlow<Boolean> = _isUpdating.asStateFlow()

    companion object {
        private const val TAG = "VpnRepository"
        private val json = Json { ignoreUnknownKeys = true }
        private const val CACHE_DURATION_MILLIS = 2 * 60 * 60 * 1000L // 2 hours
        private const val CITY_CACHE_DURATION_MILLIS = 24 * 60 * 60 * 1000L // 24 hours
        private const val AUTO_UPDATE_INTERVAL_MINUTES = 120L
        private const val AUTO_UPDATE_STARTUP_DELAY_MILLIS = 5_000L // 5 seconds

        /** `a.b.c.d` becomes `a.b.c.0`, the format of Proton's `x-pm-netzone`; null otherwise. */
        internal fun netzoneOf(ip: String?): String? {
            val octets = ip?.trim()?.split('.') ?: return null
            if (octets.size != 4 || octets.any { it.toIntOrNull() !in 0..255 }) return null
            return "${octets[0]}.${octets[1]}.${octets[2]}.0"
        }
    }

    fun startAutoUpdate() {
        if (autoUpdateJob?.isActive == true) return

        autoUpdateJob = managerScope.launch {
            ProtonLogger.i(TAG, "Starting periodic server load/list auto-update loop")
            // Delay the first cycle so it does not contend with MainActivity/MainViewModel
            // initialization for Room database resources during app startup, which could
            // cause lock contention and an ANR on the main thread.
            delay(AUTO_UPDATE_STARTUP_DELAY_MILLIS)
            while (isActive) {
                val session = withContext(dispatcherProvider.io()) { sessionDao.getSession() }
                if (session != null) {
                    ProtonLogger.d(TAG, "Auto-update: Fetching fresh server data for user tier ${session.userTier}")
                    getServers(
                        session.accessToken,
                        session.sessionId,
                        session.userTier,
                        forceRefresh = false
                    )
                } else {
                    ProtonLogger.w(TAG, "Auto-update: No active session, skipping this cycle")
                }
                delay(TimeUnit.MINUTES.toMillis(AUTO_UPDATE_INTERVAL_MINUTES))
            }
        }
    }

    fun stopAutoUpdate() {
        autoUpdateJob?.cancel()
        autoUpdateJob = null
    }

    fun getServersFlow(): Flow<List<LogicalServer>> {
        return serverDao.getServersFlow().map { entities ->
            // Extract tier from the current active session dynamically.
            val userTier = sessionDao.getSession()?.userTier ?: 0
            val servers = entities
                .map { ServerMapper.toDomain(it) }
                .filter { it.tier <= userTier } // Filter dynamically based on session tier
            
            // Localize cities
            servers.forEach { server ->
                server.localizedCity = cityRepository.getLocalizedCityName(
                    server.exitCountry, 
                    server.city
                )
            }
            servers
        }.flowOn(dispatcherProvider.io()) // Ensure the entire map block (including DB access) runs on the IO dispatcher,
        // regardless of the collector's context. This prevents unsafe database access from
        // the main thread, which can cause JNI/native crashes (SIGSEGV) in the Android Runtime.
    }

    suspend fun getCachedServers(): List<LogicalServer> = withContext(dispatcherProvider.io()) {
        val userTier = sessionDao.getSession()?.userTier ?: 0
        val servers = serverDao.getAllServers()
            .map { ServerMapper.toDomain(it) }
            .filter { it.tier <= userTier } // Filter dynamically based on session tier
        
        servers.forEach { server ->
            server.localizedCity = cityRepository.getLocalizedCityName(
                server.exitCountry,
                server.city
            )
        }
        servers
    }

    /**
     * Finds the best logical server for a given VPN profile from the provided list.
     * Logic: Target ID > City Match > Country Match > Lowest Load.
     */
    fun findBestServerForProfile(profile: VpnProfileEntity, allServers: List<LogicalServer>): LogicalServer? =
        ServerSelector.forTarget(allServers, profile.targetServerId, profile.targetCountry, profile.targetCity)

    suspend fun getServers(
        accessToken: String,
        sessionId: String,
        userTier: Int = 0,
        forceRefresh: Boolean = false
    ): Result<List<LogicalServer>> {
        val deferred = fetchMutex.withLock {
            if (activeFetch != null && activeFetch?.isActive == true) {
                if (forceRefresh) {
                    ProtonLogger.d(TAG, "Force refresh requested while fetch is in-flight; deferring until current fetch completes")
                    pendingForceRefresh.set(true)
                } else {
                    ProtonLogger.d(TAG, "Joining existing servers fetch request")
                }
                activeFetch!!
            } else {
                _isUpdating.value = true
                val capturedAccessToken = accessToken
                val capturedSessionId = sessionId
                val capturedUserTier = userTier
                val newFetch = managerScope.async {
                    try {
                        performGetServers(capturedAccessToken, capturedSessionId, capturedUserTier, forceRefresh)
                    } finally {
                        fetchMutex.withLock {
                            activeFetch = null
                            _isUpdating.value = false
                        }
                        // If a force-refresh was requested while this fetch was running,
                        // kick off a new fetch now that OkHttp has cleanly finished.
                        if (pendingForceRefresh.getAndSet(false)) {
                            val session = sessionDao.getSession()
                            if (session != null) {
                                getServers(session.accessToken, session.sessionId, session.userTier, forceRefresh = true)
                            }
                        }
                    }
                }
                activeFetch = newFetch
                newFetch
            }
        }

        return try {
            deferred.await()
        } catch (e: CancellationException) {
            // Re-throw CancellationException so caller knows they were cancelled,
            // but the background async task in managerScope continues.
            throw e
        }
    }

    /**
     * Triggers a server update in the application-level background scope.
     * Use this when you don't need to wait for the result immediately (e.g., during login).
     */
    fun refreshServersBackground(accessToken: String, sessionId: String, userTier: Int, forceRefresh: Boolean = false) {
        managerScope.launch {
            getServers(accessToken, sessionId, userTier, forceRefresh)
        }
    }

    private suspend fun performGetServers(
        accessToken: String,
        sessionId: String,
        userTier: Int,
        forceRefresh: Boolean
    ): Result<List<LogicalServer>> = withContext(dispatcherProvider.io()) {
        val startTime = System.currentTimeMillis()
        try {
            val now = System.currentTimeMillis()

            val cacheInfo = serversCacheDao.getCacheInfo()

            val shouldCheckApi = forceRefresh || cacheInfo == null || now > cacheInfo.expiresAt
            val isStale = cacheInfo != null && (now - cacheInfo.cachedAt > TimeUnit.MINUTES.toMillis(AUTO_UPDATE_INTERVAL_MINUTES))

            ProtonLogger.d(TAG, "Server sync check: force=$forceRefresh, hasCache=${cacheInfo != null}, expired=${now > (cacheInfo?.expiresAt ?: 0)}, stale=$isStale")

            if (!shouldCheckApi && !isStale) {
                val dbServers = serverDao.getAllServers().map { ServerMapper.toDomain(it) }
                // A cache written before scores were stored would keep "Fastest" on load alone.
                if (dbServers.isNotEmpty() && dbServers.any { it.score > 0.0 }) {
                    val result = dbServers.filter { it.tier <= userTier }
                    cachedServers = result
                    ProtonLogger.i(TAG, "Returning ${result.size} servers from local cache (API skip)")
                    return@withContext Result.success(result)
                }
            }

            val bearer = "Bearer $accessToken"
            val ifModifiedSince = if (!forceRefresh) cacheInfo?.lastModified else null

            // Ensure city translations are up-to-date at the start of any sync
            refreshCityTranslations(accessToken, sessionId)

            ProtonLogger.i(TAG, "Fetching servers from Proton API... (If-Modified-Since: $ifModifiedSince, StatusID: ${cacheInfo?.statusId})")
            // Proton scores servers by the address a request comes from. Through a tunnel or an
            // API mirror that is the wrong place (a connection via Mozambique made African servers
            // "fastest"), so tell it where the user really is, as the official client does.
            val netzone = netzoneOf(settingsManager.getCachedRealIpSync())
            val response = vpnApi.getLogicalServers(
                authorization = bearer,
                sessionId = sessionId,
                lastModified = ifModifiedSince,
                protocols = "wireguard",
                netzone = netzone
            )

            val (serversList, newLastModified, newStatusId) = when (response.code()) {
                304 -> {
                    ProtonLogger.i(TAG, "Proton API: Servers not modified (304). Re-using existing DB entries.")
                    val dbServers = serverDao.getAllServers().map { ServerMapper.toDomain(it) }
                    Triple(dbServers, cacheInfo?.lastModified, cacheInfo?.statusId)
                }
                200 -> {
                    val body = response.body()
                    if (body?.code == 1000) {
                        ProtonLogger.i(TAG, "Proton API: Received ${body.logicalServers.size} logical servers (StatusID: ${body.statusId})")
                        ProtonLogger.addSentryBreadcrumb(TAG, "VPN Repository: Servers Updated (${body.logicalServers.size})", "INFO", "vpn.repo")
                        
                        val isSameStatus = body.statusId != null && body.statusId == cacheInfo?.statusId
                        if (isSameStatus && !forceRefresh) {
                            ProtonLogger.i(TAG, "StatusID matches. Skipping full server list processing.")
                            val dbServers = serverDao.getAllServers().map { ServerMapper.toDomain(it) }
                            Triple(dbServers, response.headers()["Last-Modified"] ?: cacheInfo.lastModified, body.statusId)
                        } else {
                            Triple(body.logicalServers, response.headers()["Last-Modified"], body.statusId)
                        }
                    } else {
                        ProtonLogger.e(TAG, "Proton API Error: Code ${body?.code}")
                        return@withContext Result.failure(Exception("API error: ${body?.code}"))
                    }
                }
                else -> {
                    ProtonLogger.w(TAG, "Proton API: Unexpected response code ${response.code()}")
                    val dbServers = serverDao.getAllServers().map { ServerMapper.toDomain(it) }
                    if (dbServers.isNotEmpty()) {
                        ProtonLogger.i(TAG, "Falling back to DB servers due to API error")
                        return@withContext Result.success(dbServers.filter { it.tier <= userTier })
                    }
                    return@withContext Result.failure(Exception("Network error: ${response.code()}"))
                }
            }

            if (serversList.isEmpty()) {
                ProtonLogger.w(TAG, "Proton API: Server list is empty in response")
                return@withContext Result.failure(Exception("No servers available"))
            }

            // Step 1: Persist the server list to the DB and immediately drop the reference
            // so the large in-memory object graph can be garbage-collected before we
            // decompress the loads response. Capture serverCount inside run{} so that
            // serversList goes out of scope as soon as the block exits.
            val serverCount = run {
                performTransaction {
                    if (response.code() == 200 && (newStatusId != cacheInfo?.statusId || forceRefresh)) {
                        val existingServers = serverDao.getAllServers().associateBy { it.id }
                        val entities = serversList.map { server ->
                            val entity = ServerMapper.toEntity(server)
                            val old = existingServers[server.id]
                            if (old != null) {
                                // Preserve logical load if the new one is 0 (API placeholder)
                                val preservedLoad = if (entity.averageLoad == 0) old.averageLoad else entity.averageLoad
                                val preservedScore = if (entity.score <= 0.0) old.score else entity.score
                                
                                // Preserve physical loads by merging the JSON
                                val mergedPhysicalJson = try {
                                    val oldPhysicals = json.decodeFromString<List<PhysicalServer>>(old.physicalServersJson).associateBy { it.id }
                                    val newPhysicals = json.decodeFromString<List<PhysicalServer>>(entity.physicalServersJson).map { p ->
                                        if (p.load == 0 && oldPhysicals.containsKey(p.id)) {
                                            p.copy(load = oldPhysicals[p.id]!!.load)
                                        } else p
                                    }
                                    json.encodeToString(newPhysicals)
                                } catch (e: Exception) {
                                    entity.physicalServersJson
                                }
                                
                                entity.copy(averageLoad = preservedLoad, physicalServersJson = mergedPhysicalJson, score = preservedScore)
                            } else entity
                        }
                        serverDao.upsertServers(entities)
                        ProtonLogger.d(TAG, "Saved ${entities.size} servers to local database (merged loads)")
                    }

                    // Update cache metadata
                    val newCacheInfo = ServersCacheEntity(
                        cachedAt = now,
                        expiresAt = now + CACHE_DURATION_MILLIS,
                        lastModified = newLastModified,
                        statusId = newStatusId
                    )
                    serversCacheDao.saveCacheInfo(newCacheInfo)
                }

                serversList.size // capture size; serversList goes out of scope after this block
            }

            // Step 2: Hint GC before the loads fetch so the heap has room to decompress
            // the gzip response. serversList is no longer referenced from this point
            // forward; we will read the final result back from the DB instead.
            System.gc()

            // Step 3: Fetch server loads and apply them directly to the DB rows.
            ProtonLogger.d(TAG, "Fetching server loads for $serverCount servers...")
            val loadsResponse = try {
                vpnApi.getLoads(bearer, sessionId, netzone)
            } catch (e: Exception) {
                ProtonLogger.w(TAG, "Failed to initiate loads request: ${e.message}")
                null
            }

            if (loadsResponse?.isSuccessful == true) {
                val loadsBody = loadsResponse.body()?.string()
                val loadsData = loadsBody?.let {
                    try { json.decodeFromString<LoadsResponse>(it) } catch (e: Exception) {
                        ProtonLogger.e(TAG, "Failed to parse loads JSON", e)
                        null
                    }
                }

                val loadsMap = loadsData?.loads?.associate { it.id to it.load } ?: emptyMap()
                val scoresMap = loadsData?.loads?.mapNotNull { load -> load.score?.takeIf { it > 0.0 }?.let { load.id to it } }?.toMap().orEmpty()
                if (loadsMap.isNotEmpty()) {
                    ProtonLogger.d(TAG, "Applying fresh loads for ${loadsMap.size} IDs to database (scores for ${scoresMap.size}, netzone sent: ${netzone != null})...")
                    
                    val allEntities = serverDao.getAllServers()
                    val updatedEntities = allEntities.mapNotNull { entity ->
                        var modified = false
                        var newAverageLoad = entity.averageLoad
                        if (loadsMap.containsKey(entity.id)) {
                            newAverageLoad = loadsMap[entity.id]!!
                            modified = true
                        }
                        val newScore = scoresMap[entity.id] ?: entity.score
                        if (newScore != entity.score) modified = true
                        
                        val (updatedPhysicalJson, physicalModified) = try {
                            val physicals = json.decodeFromString<List<PhysicalServer>>(entity.physicalServersJson)
                            var pMod = false
                            val updatedP = physicals.map { p ->
                                if (loadsMap.containsKey(p.id)) {
                                    pMod = true
                                    p.copy(load = loadsMap[p.id]!!)
                                } else p
                            }
                            if (pMod) json.encodeToString(updatedP) to true else entity.physicalServersJson to false
                        } catch (e: Exception) {
                            entity.physicalServersJson to false
                        }
                        
                        if (modified || physicalModified) {
                            entity.copy(averageLoad = newAverageLoad, physicalServersJson = updatedPhysicalJson, score = newScore)
                        } else null
                    }

                    if (updatedEntities.isNotEmpty()) {
                        serverDao.upsertServers(updatedEntities)
                        ProtonLogger.i(TAG, "Successfully updated loads for ${updatedEntities.size} logical server entries")
                    }
                }
            } else {
                ProtonLogger.w(TAG, "Failed to fetch fresh server loads (HTTP ${loadsResponse?.code()}). Keeping existing loads.")
            }

            // Step 4: Read the final server list back from the DB (with loads applied).
            val logicalServers = serverDao.getAllServers()
                .map { ServerMapper.toDomain(it) }
                .filter { it.tier <= userTier }

            // Localize cities for the result
            logicalServers.forEach { server ->
                server.localizedCity = cityRepository.getLocalizedCityName(
                    server.exitCountry,
                    server.city
                )
            }

            cachedServers = logicalServers
            ProtonLogger.i(TAG, "Fastest ranking after sync: ${ServerSelector.describeTop(logicalServers)}")

            // Metrics
            val duration = System.currentTimeMillis()            // Metrics
            ProtonLogger.recordDistribution("server_fetch_latency", duration.toDouble())
            ProtonLogger.recordCount("server_fetch_success", 1.0)
            
            Result.success(logicalServers)
        } catch (e: Exception) {
            ProtonLogger.e(TAG, "Critical error in performGetServers", e)
            
            // Metrics
            ProtonLogger.recordCount("server_fetch_error", 1.0)

            val dbServers = serverDao.getAllServers().map { ServerMapper.toDomain(it) }
            if (dbServers.isNotEmpty()) Result.success(dbServers.filter { it.tier <= userTier })
            else Result.failure(e)
        }
    }

    suspend fun getUserLocation(accessToken: String, sessionId: String): Result<String> = withContext(dispatcherProvider.io()) {
        try {
            val response = vpnApi.getUserLocation("Bearer $accessToken", sessionId)
            val body = response.body()?.string()
            if (response.isSuccessful && body != null) {
                Result.success(body)
            } else {
                // Proton's error JSON ({"Code":…,"Error":…}) says why; it holds no personal data.
                val reason = runCatching { response.errorBody()?.string()?.take(300) }.getOrNull()
                Result.failure(Exception("Failed to get location: ${response.code()} $reason"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getVpnInfo(accessToken: String, sessionId: String): Result<VpnInfoResponse> = withContext(dispatcherProvider.io()) {
        try {
            val bearer = "Bearer $accessToken"
            
            // Refresh city translations whenever we refresh vpn info or servers
            // This ensures city names stay localized if the user changes system language
            refreshCityTranslations(accessToken, sessionId)

            val response = vpnApi.getVpnInfo(bearer, sessionId)
            val body = response.body()?.string()

            ProtonLogger.d(TAG, "getVpnInfo raw body: $body")

            if (response.isSuccessful && body != null) {
                Result.success(json.decodeFromString<VpnInfoResponse>(body))
            } else {
                Result.failure(Exception("Failed to fetch VPN info: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getServerDomain(accessToken: String, sessionId: String, serverId: String): Result<String> = withContext(dispatcherProvider.io()) {
        try {
            val response = vpnApi.getServerDomain("Bearer $accessToken", sessionId, serverId)
            if (response.code == 1000 && response.domain != null) {
                Result.success(response.domain)
            } else {
                Result.failure(Exception("Failed to get server domain: Code ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun registerWireGuardKey(
        accessToken: String,
        sessionId: String,
        mode: String? = null
    ): Result<Pair<CreateCertificateResponse, VpnKeyPair>> = withContext(dispatcherProvider.io()) {
        try {
            val keyPair = cryptoWrapper.generateVpnKeyPair()
            val bearer = "Bearer $accessToken"
            val request = CreateCertificateRequest(clientPublicKey = keyPair.publicKeyPem, mode = mode)
            val response = vpnApi.registerVpnKey(bearer, sessionId, request)

            ProtonLogger.d(TAG, "registerWireGuardKey response code: ${response.code}, cert length: ${response.certificate?.length ?: 0}")
            if (response.code == 1000) {
                if (response.certificate != null) {
                    sessionDao.updateCertificateAndKeys(
                        cert = response.certificate,
                        expiresAt = response.expirationTime ?: 0L,
                        refreshAt = response.refreshTime ?: 0L,
                        privKey = keyPair.privateKeyX25519,
                        pubKey = keyPair.publicKeyPem
                    )
                }

                // Update vpn information (ipv4, ipv6, dns) returned from the certificate response.
                // This is crucial for paid users (Tier > 0) who might be assigned unique internal IPs
                // different from the default "10.2.0.2".
                sessionDao.updateVpnConnectionInfo(
                    ipv4 = response.ipv4,
                    ipv6 = response.ipv6,
                    dns = response.dns?.joinToString(",")
                )

                Result.success(Pair(response, keyPair))
            } else {
                Result.failure(Exception("Proton Cert Error: ${response.code}"))
            }
        } catch (e: CancellationException) {
            // Re-throw cancellation exceptions to allow proper coroutine cancellation propagation
            // when user navigates away during certificate registration.
            throw e
        } catch (e: Exception) {
            ProtonLogger.e(TAG, "Error in registerWireGuardKey", e)
            Result.failure(e)
        }
    }

    /**
     * Triggered by NetworkMonitor to refresh servers when connectivity changes.
     * Debounced by 2 seconds so rapid network transitions (e.g. Wi-Fi → mobile)
     * do not queue up multiple parallel force-refresh requests.
     */
    fun refreshServersOnNetworkChange() {
        // Cancel any pending (not yet started) debounce job; never cancels an in-flight OkHttp call.
        networkChangeJob?.cancel()
        networkChangeJob = managerScope.launch {
            delay(2_000L)
            val session = sessionDao.getSession() ?: return@launch
            ProtonLogger.i(TAG, "Network changed. Refreshing server list.")
            getServers(session.accessToken, session.sessionId, session.userTier, forceRefresh = true)
        }
    }

    suspend fun clearCache() = withContext(dispatcherProvider.io()) {
        ProtonLogger.d(TAG, "Clearing VPN cache and user data...")
        serverDao.clearAllServers()
        serversCacheDao.clearCacheInfo()
        cityTranslationDao.clearAll()
        cityTranslationDao.clearCacheInfo()
        profileDao.deleteAllProfiles()
        recentConnectionDao.clearHistory()
        cityRepository.clearCache()
        cachedServers = emptyList()
    }

    /**
     * Checks if the current certificate is valid and returns it.
     * If expired or missing, triggers a fresh registration.
     */
    suspend fun getOrRegisterWireGuardKey(
        accessToken: String,
        sessionId: String,
        publicKeyPem: String
    ): Result<String> = withContext(dispatcherProvider.io()) {
        val session = sessionDao.getSession()
        val now = System.currentTimeMillis() / 1000

        if (session != null && !session.wgCertificate.isNullOrEmpty() &&
            session.wgPublicKeyPem == publicKeyPem &&
            (session.certExpiresAt == 0L || session.certExpiresAt > now)
        ) {
            // Check if we should refresh in background
            if (session.certRefreshAt != 0L && session.certRefreshAt < now) {
                ProtonLogger.i(TAG, "Certificate is valid but needs refresh. Triggering background update.")
                managerScope.launch {
                    val mode = if (session.isExtendedCertEnabled) "persistent" else null
                    registerWireGuardKey(accessToken, sessionId, mode)
                }
            }
            return@withContext Result.success(session.wgCertificate)
        }

        ProtonLogger.i(TAG, "No valid certificate found. Registering new WireGuard key.")
        val mode = if (session?.isExtendedCertEnabled == true) "persistent" else null
        registerWireGuardKey(accessToken, sessionId, mode).map { it.first.certificate ?: "" }
    }

    suspend fun setExtendedCertEnabled(enabled: Boolean, accessToken: String, sessionId: String): Result<Boolean> = withContext(dispatcherProvider.io()) {
        val session = sessionDao.getSession() ?: return@withContext Result.failure(Exception("No session"))
        if (session.isExtendedCertEnabled == enabled) return@withContext Result.success(true)

        val mode = if (enabled) "persistent" else null
        val result = registerWireGuardKey(accessToken, sessionId, mode)
        if (result.isFailure) {
            return@withContext Result.failure(result.exceptionOrNull() ?: Exception("Unknown error"))
        }

        sessionDao.updateExtendedCertEnabled(enabled)
        Result.success(true)
    }

    suspend fun forceRefreshCertificate(accessToken: String, sessionId: String): Result<CreateCertificateResponse> = withContext(dispatcherProvider.io()) {
        val session = sessionDao.getSession()
        val mode = if (session?.isExtendedCertEnabled == true) "persistent" else null
        registerWireGuardKey(accessToken, sessionId, mode).map { it.first }
    }

    private suspend fun refreshCityTranslations(accessToken: String, sessionId: String) {
        try {
            val languageTag = java.util.Locale.getDefault().toLanguageTag()
            val now = System.currentTimeMillis()
            
            // Check if we already have fresh translations for this language
            val lastUpdated = cityTranslationDao.getLastUpdated(languageTag) ?: 0L
            val isExpired = now - lastUpdated > CITY_CACHE_DURATION_MILLIS
            
            if (!isExpired && cityTranslationDao.getCount(languageTag) > 0) {
                ProtonLogger.d(TAG, "City translations for $languageTag are fresh, skipping fetch")
                return
            }

            ProtonLogger.i(TAG, "Fetching city translations for $languageTag...")
            val response = vpnApi.getServerCities("Bearer $accessToken", sessionId, languageTag)
            
            val entities = mutableListOf<CityTranslationEntity>()
            response.cities.forEach { (countryCode, cityMap) ->
                cityMap.forEach { (englishName, localizedName) ->
                    if (localizedName != null) {
                        entities.add(
                            CityTranslationEntity(
                                countryCode = countryCode,
                                englishName = englishName,
                                localizedName = localizedName,
                                languageCode = languageTag
                            )
                        )
                    }
                }
            }
            
            if (entities.isNotEmpty()) {
                // Use upsertTranslations to atomically clear old translations and insert new ones
                // in a single transaction, avoiding N+1 query patterns.
                cityTranslationDao.upsertTranslations(languageTag, entities)
                cityTranslationDao.saveCacheInfo(CityCacheEntity(languageTag, now))
                cityRepository.clearCache()
                ProtonLogger.i(TAG, "Saved ${entities.size} city translations for $languageTag")
            }
        } catch (e: Exception) {
            ProtonLogger.w(TAG, "Failed to refresh city translations: ${e.message}")
        }
    }
}
