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

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import ru.protonmod.next.utils.ProtonLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import retrofit2.HttpException
import ru.protonmod.next.data.local.SessionDao
import ru.protonmod.next.data.local.SessionEntity
import ru.protonmod.next.data.network.*
import ru.protonmod.next.ui.screens.CaptchaRequiredException
import ru.protonmod.next.ui.screens.ProtonErrorResponse
import ru.protonmod.next.utils.DeviceInfoProvider
import ru.protonmod.next.utils.coroutines.DispatcherProvider
import ru.protonmod.next.utils.crypto.Base64Utils
import ru.protonmod.next.utils.crypto.CryptoWrapper
import ru.protonmod.next.utils.crypto.VpnKeyPair
import ru.protonmod.next.vpn.AmneziaVpnManager
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import java.net.SocketTimeoutException
import java.net.ConnectException

/**
 * An error response the Proton API returned verbatim.
 *
 * Carrying the status code instead of a bare [Exception] lets the UI and the crash reporter tell an
 * expected outcome (wrong password, captcha needed, rate limit) apart from a genuine defect.
 */
class ProtonApiException(val code: Int, message: String) : Exception(message)

@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authApi: ProtonAuthApi,
    private val vpnRepository: VpnRepository,
    private val sessionDao: SessionDao,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val cryptoWrapper: CryptoWrapper,
    private val dispatcherProvider: DispatcherProvider,
    private val amneziaVpnManager: Provider<AmneziaVpnManager>,
    private val sessionManager: SessionManager
) {
    companion object {
        private const val TAG = "AuthRepository"
        private val jsonParser = Json { ignoreUnknownKeys = true }
        private val FORCE_LOGOUT_HTTP_CODES = listOf(400, 401, 422)
    }

    @Volatile
    private var pendingAnonToken: String? = null
    @Volatile
    private var pendingAnonUid: String? = null
    @Volatile
    private var pendingAuthInfo: AuthInfoResponse? = null
    @Volatile
    private var pendingUsername: String? = null

    // Cache the challenge payload to ensure cryptographic hash matches during CAPTCHA retry
    @Volatile
    private var pendingChallengePayload: JsonObject? = null

    /**
     * A login that passed authentication but whose VPN certificate request asked for human
     * verification. The captcha is solved for this session, and the next login call with the
     * token only finishes the certificate step instead of logging in again.
     */
    private data class PendingCertificate(
        val accessToken: String,
        val refreshToken: String,
        val sessionId: String,
        val userId: String,
        val response: LoginResponse,
    )

    @Volatile
    private var pendingCertificate: PendingCertificate? = null

    private val authMutex = Mutex()

    /**
     * SupervisorJob for auth operations that allows cancellation of pending login/anonymous operations.
     * Prevents JNI reference leaks when activity is destroyed mid-login.
     */
    private var authJob = SupervisorJob()

    /**
     * Cancel all pending authentication operations.
     * Called when ViewModel is cleared to prevent JNI reference leaks.
     */
    fun cancelPendingOperations() {
        ProtonLogger.d(TAG, "Cancelling pending auth operations")
        authJob.cancel()
        authJob = SupervisorJob()
        clearPendingAuth()
    }

    /**
     * Resets temporary authentication state and cached payloads.
     */
    fun clearPendingAuth() {
        pendingAnonToken = null
        pendingAnonUid = null
        pendingAuthInfo = null
        pendingUsername = null
        pendingChallengePayload = null
        pendingCertificate = null
    }

    fun getPendingUid(): String? = pendingAnonUid

    suspend fun exportSession(): String? = withContext(dispatcherProvider.io()) {
        sessionDao.getSession()?.let { jsonParser.encodeToString(it) }
    }

    suspend fun loginBySession(session: SessionEntity): Result<Unit> = withContext(dispatcherProvider.io()) {
        try {
            sessionDao.saveSession(session)
            vpnRepository.getServers(session.accessToken, session.sessionId, session.userTier)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Clears local session and stops background tasks.
     */
    suspend fun logout() = withContext(dispatcherProvider.io()) {
        ProtonLogger.d(TAG, "Logging out user...")

        // 1. Disconnect VPN first
        try {
            amneziaVpnManager.get().disconnect()
        } catch (e: Exception) {
            ProtonLogger.w(TAG, "Failed to disconnect VPN during logout: ${e.message}")
        }

        // 2. Notify server (best effort)
        try {
            sessionDao.getSession()?.let { session ->
                if (session.accessToken.isNotEmpty()) {
                    authApi.performLogout("Bearer ${session.accessToken}", session.sessionId)
                    ProtonLogger.i(TAG, "Server-side logout successful")
                }
            }
        } catch (e: Exception) {
            ProtonLogger.w(TAG, "Server-side logout failed: ${e.message}")
        }

        // 3. Clear local state
        vpnRepository.stopAutoUpdate()
        vpnRepository.clearCache()
        sessionDao.clearSession()
        clearPendingAuth()
    }

    /**
     * Main login flow using SRP (Secure Remote Password) protocol.
     * Handles Captcha verification by refreshing sessions if a token is provided.
     */
    suspend fun login(username: String, passwordRaw: String, captchaToken: String? = null): Result<LoginResponse> = authMutex.withLock {
        withContext(dispatcherProvider.io() + authJob) {
            try {
                val waitingCertificate = pendingCertificate
                if (waitingCertificate != null && captchaToken != null) {
                    ProtonLogger.i(TAG, "[Login] Captcha solved for the certificate; finishing the login")
                    pendingCertificate = null
                    return@withContext finishLogin(waitingCertificate, captchaToken)
                }
                ProtonLogger.i(TAG, "Starting Kotlin SRP login flow (Have Captcha: ${captchaToken != null})")
                
                val challengePayload = pendingChallengePayload ?: buildChallengePayload().also { pendingChallengePayload = it }
                val captchaTokenType = if (captchaToken != null) "captcha" else null

                // Phase 0: Anonymous Session
                // We pass captchaToken ONLY here to "unblock" the session (UID)
                if (pendingAnonToken == null || captchaToken != null) {
                    ProtonLogger.d(TAG, "[Login] Phase 0: Creating/Updating anonymous session")
                    val anonSession = authApi.createAnonymousSession(challengePayload, captchaToken, captchaTokenType)
                    pendingAnonToken = anonSession.accessToken
                    pendingAnonUid = anonSession.sessionId
                }

                val anonToken = pendingAnonToken ?: throw Exception("Failed to get anonymous session")
                val anonUid = pendingAnonUid ?: throw Exception("Failed to get anonymous UID")
                val bearer = "Bearer $anonToken"

                // Phase 1: Auth Info
                ProtonLogger.d(TAG, "[Login] Phase 1: Getting auth info")
                val authInfo = authApi.getAuthInfo(bearer, anonUid, AuthInfoRequest(username), captchaToken, captchaTokenType)
                if (authInfo.code != 1000) {
                    throw Exception("Failed to get auth info: ${authInfo.code}")
                }

                // Phase 2: SRP Proofs
                ProtonLogger.d(TAG, "[Login] Phase 2: Generating SRP proofs")
                val proofs = cryptoWrapper.generateSrpProofs(
                    username = username,
                    passwordRaw = passwordRaw.toByteArray(),
                    salt = authInfo.salt ?: "",
                    modulus = authInfo.modulus ?: "",
                    serverEphemeral = Base64Utils.decode(authInfo.serverEphemeral ?: "")
                )

                // Phase 3: Final Login
                ProtonLogger.d(TAG, "[Login] Phase 3: Performing final login")
                val loginReq = LoginRequest(
                    username = username,
                    clientEphemeral = proofs.clientEphemeral,
                    clientProof = proofs.clientProof,
                    srpSession = authInfo.srpSession ?: "",
                    payload = challengePayload["Payload"]?.jsonObject
                )

                val response = authApi.performLogin(bearer, anonUid, loginReq, captchaToken, captchaTokenType)

                if (response.code != 1000) {
                    throw Exception("Login failed: ${response.code}")
                }

                val finalAccessToken = response.accessToken ?: anonToken
                val finalRefreshToken = response.refreshToken ?: ""
                val finalUid = response.sessionId ?: anonUid

                // If 2FA is not required, proceed to complete setup
                if (!response.scopes.contains("twofactor")) {
                    ProtonLogger.d(TAG, "[Login] Completing authentication. Registering VPN cert...")
                    val pending = PendingCertificate(
                        accessToken = finalAccessToken,
                        refreshToken = finalRefreshToken,
                        sessionId = finalUid,
                        userId = response.userId ?: "",
                        response = response,
                    )
                    return@withContext finishLogin(pending, captchaToken = null)
                }

                ProtonLogger.d(TAG, "[Login] Success. Scopes: ${response.scopes.joinToString()}")
                Result.success(response.copy(
                    accessToken = finalAccessToken,
                    refreshToken = finalRefreshToken,
                    sessionId = finalUid
                ))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ProtonLogger.e(TAG, "[Login] Exception thrown", e)
                handleHttpError(e)
            }
        }
    }

    /**
     * Anonymous login flow (Guest login).
     */
    suspend fun loginAnonymous(captchaToken: String? = null): Result<LoginResponse> = authMutex.withLock {
        withContext(dispatcherProvider.io() + authJob) {
            try {
                if (ru.protonmod.next.BuildConfig.ALLOW_LOGCAT) {
                    android.util.Log.d("AuthLogging", "Starting Anonymous Login...")
                }
                val tokenType = if (captchaToken != null) "captcha" else null
                val challengePayload = pendingChallengePayload ?: buildChallengePayload().also { pendingChallengePayload = it }

                ProtonLogger.d(TAG, "[AnonymousLogin] Starting flow. Have Captcha: ${captchaToken != null}")

                var usedTokenInPhase0 = false
                if (pendingAnonToken == null) {
                    ProtonLogger.d(TAG, "[AnonymousLogin] Requesting initial anonymous session")
                    val anonSession = authApi.createAnonymousSession(challengePayload, captchaToken, tokenType)
                    pendingAnonToken = anonSession.accessToken
                    pendingAnonUid = anonSession.sessionId
                    if (captchaToken != null) usedTokenInPhase0 = true
                }

                val anonToken = pendingAnonToken ?: throw Exception("Failed to get anonymous session")
                val anonUid = pendingAnonUid ?: throw Exception("Failed to get anonymous UID")
                val bearer = "Bearer $anonToken"

                // Ensure token is not sent twice
                val phase1Token = if (usedTokenInPhase0) null else captchaToken
                val phase1TokenType = if (phase1Token != null) "captcha" else null

                ProtonLogger.d(TAG, "[AnonymousLogin] Upgrading to credentialless session using UID: $anonUid")
                val response = authApi.performLoginLess(bearer, anonUid, challengePayload, phase1Token, phase1TokenType)

                if (response.code == 1000) {
                    ProtonLogger.d(TAG, "[AnonymousLogin] Success. Registering VPN cert...")

                    clearPendingAuth()

                    val finalAccessToken = response.accessToken ?: anonToken
                    val finalUid = response.sessionId ?: anonUid
                    val keys = registerAndGetVpnKeys(finalAccessToken, finalUid)

                    saveSessionLocally(
                        accessToken = finalAccessToken,
                        refreshToken = response.refreshToken ?: "",
                        sessionId = finalUid,
                        userId = response.userId ?: "",
                        userTier = 0,
                        wgPrivateKey = keys.first.privateKeyX25519,
                        wgPublicKeyPem = keys.first.publicKeyPem,
                        wgCertificate = keys.third,
                        vpnIpv4 = keys.second.ipv4,
                        vpnIpv6 = keys.second.ipv6,
                        vpnDns = keys.second.dns?.joinToString(",")
                    )

                    vpnRepository.getServers(finalAccessToken, finalUid, 0)
                    Result.success(response.copy(accessToken = finalAccessToken, sessionId = finalUid))
                } else {
                    Result.failure(Exception("Guest login failed: Code ${response.code}"))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e

                if (e is SocketTimeoutException || e is ConnectException) {
                    ProtonLogger.w(TAG, "[AnonymousLogin] Network timeout error: ${e.message}")
                    clearPendingAuth()
                    return@withContext Result.failure(e)
                }

                if (e !is HttpException) ProtonLogger.e(TAG, "[AnonymousLogin] Exception thrown", e)
                handleHttpError(e)
            }
        }
    }

    suspend fun refreshSession(sessionId: String, refreshToken: String): Result<LoginResponse> = withContext(dispatcherProvider.io()) {
        val session = sessionDao.getSession()
        if (session == null || session.sessionId != sessionId) {
            return@withContext Result.failure(Exception("No matching session found"))
        }

        sessionManager.refreshSession(session).map { updated ->
            LoginResponse(
                code = 1000,
                accessToken = updated.accessToken,
                refreshToken = updated.refreshToken,
                sessionId = updated.sessionId,
                userId = updated.userId
            )
        }.onFailure { e ->
            if (e is HttpException && e.code() in FORCE_LOGOUT_HTTP_CODES) {
                logout()
            }
        }
    }

    suspend fun verify2FA(
        sessionId: String,
        tempAccessToken: String,
        refreshToken: String,
        totpCode: String
    ): Result<LoginResponse> = authMutex.withLock {
        withContext(dispatcherProvider.io() + authJob) {
            try {
                ProtonLogger.i(TAG, "Verifying 2FA")
                val bearer = "Bearer $tempAccessToken"
                val response = authApi.performSecondFactor(bearer, sessionId, SecondFactorRequest(totpCode))

                if (!response.isSuccessful || response.body()?.code != 1000) {
                    val code = response.body()?.code ?: response.code()
                    return@withContext Result.failure(Exception("2FA rejected: $code"))
                }

                val response2fa = response.body()!!
                
                // Check if session ID changed in headers
                val newSessionId = response.headers()["X-PM-Session-ID"]
                    ?: response.headers()["x-pm-session-id"]
                    ?: response2fa.sessionId
                    ?: sessionId
                
                if (newSessionId != sessionId) {
                    ProtonLogger.i(TAG, "Session ID changed during 2FA")
                }

                // AFTER successful 2FA, we MUST refresh the session to get a token with full scopes.
                // The tempAccessToken only had 'twofactor' scope.
                ProtonLogger.i(TAG, "2FA successful, refreshing session to promote token")
                
                val tempSession = SessionEntity(
                    sessionId = newSessionId,
                    accessToken = response2fa.accessToken ?: tempAccessToken,
                    refreshToken = response2fa.refreshToken ?: refreshToken,
                    userId = ""
                )

                val refreshResult = sessionManager.refreshSession(tempSession)
                if (refreshResult.isFailure) {
                    ProtonLogger.e(TAG, "Failed to refresh session after 2FA", refreshResult.exceptionOrNull())
                    return@withContext Result.failure(Exception("Session promotion failed after 2FA"))
                }

                val promotedSession = refreshResult.getOrNull()!!
                val fullToken = promotedSession.accessToken
                val fullBearer = "Bearer $fullToken"
                val finalSessionId = promotedSession.sessionId

                ProtonLogger.d(TAG, "Session promoted. Fetching user info...")
                val userResponse = authApi.getUser(fullBearer, finalSessionId)
                val finalUserId = userResponse.user?.id ?: ""

                val keys = registerAndGetVpnKeys(fullToken, finalSessionId)
                val vpnInfoResult = vpnRepository.getVpnInfo(fullToken, finalSessionId)
                val userTier = vpnInfoResult.getOrNull()?.vpnInfo?.maxTier ?: 0

                saveSessionLocally(
                    accessToken = fullToken,
                    refreshToken = promotedSession.refreshToken,
                    sessionId = finalSessionId,
                    userId = finalUserId,
                    userTier = userTier,
                    wgPrivateKey = keys.first.privateKeyX25519,
                    wgPublicKeyPem = keys.first.publicKeyPem,
                    wgCertificate = keys.third,
                    vpnIpv4 = keys.second.ipv4,
                    vpnIpv6 = keys.second.ipv6,
                    vpnDns = keys.second.dns?.joinToString(",")
                )

                vpnRepository.getServers(fullToken, finalSessionId, userTier)
                
                Result.success(LoginResponse(
                    code = 1000,
                    accessToken = fullToken,
                    refreshToken = promotedSession.refreshToken,
                    sessionId = finalSessionId,
                    userId = finalUserId
                ))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e !is HttpException) ProtonLogger.e(TAG, "[verify2FA] Exception thrown", e)
                handleHttpError(e)
            }
        }
    }

    suspend fun getAvailableDomains(type: String = "login"): Result<List<String>> = withContext(dispatcherProvider.io()) {
        try {
            val session = sessionDao.getSession()
            val authHeader = session?.accessToken?.let { "Bearer $it" }
            val response = authApi.getAvailableDomains(
                authorization = authHeader,
                sessionId = session?.sessionId,
                type = type
            )
            if (response.code == 1000) {
                Result.success(response.domains)
            } else {
                Result.failure(Exception("Failed to get available domains: Code ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildChallengePayload(): JsonObject {
        return buildJsonObject {
            putJsonObject("Payload") {
                putJsonObject("vpn-android-v4-challenge-0") {
                    put("type", "me.proton.core.challenge.data.frame.ChallengeFrame.Device")
                    put("v", DeviceInfoProvider.SPOOFED_APP_VERSION)
                    put("appLang", deviceInfoProvider.getAppLanguage())
                    put("timezone", deviceInfoProvider.getTimezone())
                    put("deviceName", deviceInfoProvider.getDeviceHash())
                    put("regionCode", deviceInfoProvider.getRegionCode())
                    put("timezoneOffset", deviceInfoProvider.getTimezoneOffset())
                    put("isJailbreak", deviceInfoProvider.isJailbreak())
                    put("preferredContentSize", deviceInfoProvider.getPreferredContentSize())
                    put("storageCapacity", deviceInfoProvider.getStorageCapacity())
                    put("isDarkmodeOn", deviceInfoProvider.isDarkModeOn())
                    putJsonArray("keyboards") {
                        deviceInfoProvider.getInstalledKeyboards().forEach { add(it) }
                    }
                }
            }
        }
    }

    /**
     * Last step of a login: VPN certificate, plan, local session, server list. When Proton asks
     * for a captcha on the certificate, the authenticated session is kept and the captcha is
     * shown for it; [login] with the solved token resumes here.
     */
    private suspend fun finishLogin(pending: PendingCertificate, captchaToken: String?): Result<LoginResponse> {
        val keys = try {
            registerAndGetVpnKeys(pending.accessToken, pending.sessionId, captchaToken)
        } catch (e: CaptchaRequiredException) {
            ProtonLogger.w(TAG, "[Login] The VPN certificate needs a captcha for this session")
            pendingCertificate = pending
            return Result.failure(e)
        }
        val vpnInfoResult = vpnRepository.getVpnInfo(pending.accessToken, pending.sessionId)
        val userTier = vpnInfoResult.getOrNull()?.vpnInfo?.maxTier ?: 0

        saveSessionLocally(
            accessToken = pending.accessToken,
            refreshToken = pending.refreshToken,
            sessionId = pending.sessionId,
            userId = pending.userId,
            userTier = userTier,
            wgPrivateKey = keys.first.privateKeyX25519,
            wgPublicKeyPem = keys.first.publicKeyPem,
            wgCertificate = keys.third,
            vpnIpv4 = keys.second.ipv4,
            vpnIpv6 = keys.second.ipv6,
            vpnDns = keys.second.dns?.joinToString(",")
        )
        vpnRepository.getServers(pending.accessToken, pending.sessionId, userTier)
        clearPendingAuth()

        ProtonLogger.d(TAG, "[Login] Success. Scopes: ${pending.response.scopes.joinToString()}")
        return Result.success(pending.response.copy(
            accessToken = pending.accessToken,
            refreshToken = pending.refreshToken,
            sessionId = pending.sessionId
        ))
    }

    private suspend fun registerAndGetVpnKeys(
        accessToken: String,
        sessionId: String,
        captchaToken: String? = null
    ): Triple<VpnKeyPair, CreateCertificateResponse, String> {
        try {
            val regResult = vpnRepository.registerWireGuardKey(accessToken, sessionId, captchaToken = captchaToken)

            if (regResult.isSuccess) {
                val pair = regResult.getOrNull()!!
                val response = pair.first
                val vpnKeyPair = pair.second
                return Triple(vpnKeyPair, response, response.certificate ?: "")
            } else {
                val error = regResult.exceptionOrNull()
                if (error is CaptchaRequiredException) throw error
                throw Exception("WireGuard key registration failed: ${error?.message ?: "unknown error"}")
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    private suspend fun saveSessionLocally(
        accessToken: String, refreshToken: String, sessionId: String, userId: String,
        userTier: Int, wgPrivateKey: String?, wgPublicKeyPem: String?, wgCertificate: String?,
        vpnIpv4: String? = null, vpnIpv6: String? = null, vpnDns: String? = null
    ) {
        require(!wgPrivateKey.isNullOrEmpty()) { "Cannot save session without a VPN private key" }
        sessionDao.saveSession(
            SessionEntity(
                accessToken = accessToken, refreshToken = refreshToken, sessionId = sessionId,
                userId = userId, userTier = userTier, wgPrivateKey = wgPrivateKey,
                wgPublicKeyPem = wgPublicKeyPem, wgCertificate = wgCertificate,
                vpnIpv4 = vpnIpv4, vpnIpv6 = vpnIpv6, vpnDns = vpnDns
            )
        )
    }

    private fun handleHttpError(e: Exception): Result<LoginResponse> {
        if (e is HttpException) {
            val response = e.response()
            val errorBody = response?.errorBody()?.string()
            val code = e.code()

            // Try to capture session ID from headers if present
            val sessionHeader = response?.headers()?.get("X-PM-Session-ID")
                ?: response?.headers()?.get("x-pm-session-id")
            if (sessionHeader != null && pendingAnonUid == null) {
                pendingAnonUid = sessionHeader
            }

            val shouldClearAuth = (code == 401 || code == 403 || code == 422)

            if (code == 422 && errorBody != null) {
                try {
                    val parsedError = jsonParser.decodeFromString<ProtonErrorResponse>(errorBody)
                    // 9001 = Needs Captcha
                    if (parsedError.code == 9001) {
                        val url = parsedError.details?.webUrl ?: ""
                        val token = parsedError.details?.humanVerificationToken ?: ""
                        val pendingUid = getPendingUid()

                        // Failsafe: if we don't have a UID, we MUST NOT throw CaptchaRequiredException
                        // because WebView won't have x-pm-uid to bind the token properly.
                        if (pendingUid == null) {
                            clearPendingAuth()
                            return Result.failure(
                                ProtonApiException(code, "Missing session for CAPTCHA. Please try again.")
                            )
                        }

                        ProtonLogger.w(TAG, "CAPTCHA Verification Required. Token extracted. PendingUID: $pendingUid")
                        return Result.failure(CaptchaRequiredException(url, token, pendingUid))
                    }
                    // 12087 = Captcha validation failed due to payload mismatch or session reset.
                    if (parsedError.code == 12087) {
                        // Expected whenever a captcha session ages out; the UI recovers on the next
                        // attempt, so this is a warning rather than a reportable error.
                        ProtonLogger.w(TAG, "Captcha validation failed (12087): session invalidated. Forcing fresh restart.")
                        clearPendingAuth()
                        // FIX: We must NOT return CaptchaRequiredException here. The session is dead.
                        // Returning a standard exception forces the UI to reset, and the user's next attempt
                        // will cleanly create a new session and hit 9001 again safely.
                        return Result.failure(
                            ProtonApiException(code, "Captcha session expired. Please click Login to try again.")
                        )
                    }
                } catch (ex: Exception) {
                    ProtonLogger.w(TAG, "Failed to parse 422 error body: ${ex.message}")
                }
            }

            if (shouldClearAuth) {
                clearPendingAuth()
            }

            if (code == 422 && errorBody != null) {
                return Result.failure(ProtonApiException(code, "HTTP 422: $errorBody"))
            }
            return Result.failure(ProtonApiException(code, "HTTP $code: ${errorBody ?: e.message()}"))
        }
        return Result.failure(e)
    }
}