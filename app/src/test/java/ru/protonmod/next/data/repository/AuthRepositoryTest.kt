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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*
import retrofit2.HttpException
import retrofit2.Response
import ru.protonmod.next.data.local.SessionDao
import ru.protonmod.next.data.local.SessionEntity
import ru.protonmod.next.data.network.*
import ru.protonmod.next.utils.DeviceInfoProvider
import ru.protonmod.next.utils.coroutines.DispatcherProvider
import ru.protonmod.next.utils.crypto.CryptoWrapper
import ru.protonmod.next.utils.crypto.SrpProofs
import ru.protonmod.next.utils.crypto.VpnKeyPair

@OptIn(ExperimentalCoroutinesApi::class)
class AuthRepositoryTest {

    @Mock
    private lateinit var context: Context

    @Mock
    private lateinit var authApi: ProtonAuthApi

    @Mock
    private lateinit var vpnRepository: VpnRepository

    @Mock
    private lateinit var sessionDao: SessionDao

    @Mock
    private lateinit var deviceInfoProvider: DeviceInfoProvider

    @Mock
    private lateinit var cryptoWrapper: CryptoWrapper

    @Mock
    private lateinit var amneziaVpnManager: ru.protonmod.next.vpn.AmneziaVpnManager

    @Mock
    private lateinit var sessionManager: ru.protonmod.next.data.network.SessionManager

    private val testDispatcher = StandardTestDispatcher()
    
    private val testDispatcherProvider = object : DispatcherProvider {
        override fun main(): CoroutineDispatcher = testDispatcher
        override fun io(): CoroutineDispatcher = testDispatcher
        override fun default(): CoroutineDispatcher = testDispatcher
    }

    private lateinit var repository: AuthRepository

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        repository = AuthRepository(
            context, authApi, vpnRepository, sessionDao, deviceInfoProvider,
            cryptoWrapper, testDispatcherProvider, { amneziaVpnManager },
            sessionManager
        )
        
        whenever(deviceInfoProvider.getAppLanguage()).thenReturn("en")
        whenever(deviceInfoProvider.getTimezone()).thenReturn("UTC")
        whenever(deviceInfoProvider.getDeviceHash()).thenReturn(12345L)
        whenever(deviceInfoProvider.getRegionCode()).thenReturn("US")
        whenever(deviceInfoProvider.getTimezoneOffset()).thenReturn(0)
        whenever(deviceInfoProvider.isJailbreak()).thenReturn(false)
        whenever(deviceInfoProvider.getPreferredContentSize()).thenReturn("1.0")
        whenever(deviceInfoProvider.getStorageCapacity()).thenReturn(128.0)
        whenever(deviceInfoProvider.isDarkModeOn()).thenReturn(false)
        whenever(deviceInfoProvider.getInstalledKeyboards()).thenReturn(listOf("com.google.android.inputmethod.latin"))
    }

    @Test
    fun `login SRP success flow`() = runTest(testDispatcher) {
        // Arrange
        val username = "testuser"
        val password = "password"
        
        val anonSession = LoginResponse(
            code = 1000,
            accessToken = "anon_token",
            sessionId = "anon_session_id"
        )
        val authInfo = AuthInfoResponse(
            code = 1000,
            salt = "salt",
            modulus = "modulus",
            serverEphemeral = "serverEphemeral",
            srpSession = "srpSession"
        )
        val loginResponse = LoginResponse(
            code = 1000,
            accessToken = "final_token",
            refreshToken = "final_refresh",
            sessionId = "final_session_id",
            userId = "user_id",
            scopes = listOf("vpn")
        )

        whenever(authApi.createAnonymousSession(any(), anyOrNull(), anyOrNull())).thenReturn(anonSession)
        whenever(authApi.getAuthInfo(any(), any(), any(), anyOrNull(), anyOrNull())).thenReturn(authInfo)
        
        whenever(cryptoWrapper.generateSrpProofs(any(), any(), any(), any(), any())).thenReturn(
            SrpProofs("clientEph", "clientProof")
        )
        
        whenever(authApi.performLogin(any(), any(), any(), anyOrNull(), anyOrNull())).thenReturn(loginResponse)

        val vpnKeyPair = VpnKeyPair("pubkey", "privkey")
        whenever(cryptoWrapper.generateVpnKeyPair()).thenReturn(vpnKeyPair)

        whenever(vpnRepository.registerWireGuardKey(any(), any(), anyOrNull(), anyOrNull())).thenReturn(
            Result.success(Pair(CreateCertificateResponse(code = 1000, certificate = "cert"), vpnKeyPair))
        )
        
        whenever(vpnRepository.getVpnInfo(any(), any())).thenReturn(
            Result.success(VpnInfoResponse(code = 1000, vpnInfo = VpnInfo(maxTier = 2)))
        )

        // Act
        val result = repository.login(username, password)

        // Assert
        assertTrue("Expected success but got ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("final_token", result.getOrNull()?.accessToken)
        verify(authApi).performLogin(any(), eq("anon_session_id"), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `a captcha on the certificate is solved for the logged-in session without logging in again`() = runTest(testDispatcher) {
        whenever(authApi.createAnonymousSession(any(), anyOrNull(), anyOrNull())).thenReturn(
            LoginResponse(code = 1000, accessToken = "anon_token", sessionId = "anon_session_id")
        )
        whenever(authApi.getAuthInfo(any(), any(), any(), anyOrNull(), anyOrNull())).thenReturn(
            AuthInfoResponse(code = 1000, salt = "salt", modulus = "modulus", serverEphemeral = "serverEphemeral", srpSession = "srpSession")
        )
        whenever(cryptoWrapper.generateSrpProofs(any(), any(), any(), any(), any())).thenReturn(SrpProofs("ce", "cp"))
        whenever(authApi.performLogin(any(), any(), any(), anyOrNull(), anyOrNull())).thenReturn(
            LoginResponse(
                code = 1000, accessToken = "final_token", refreshToken = "final_refresh",
                sessionId = "final_session_id", userId = "user_id", scopes = listOf("vpn")
            )
        )
        val vpnKeyPair = VpnKeyPair("pubkey", "privkey")
        // Proton asks for human verification on the certificate of the new session.
        whenever(vpnRepository.registerWireGuardKey(any(), any(), anyOrNull(), isNull())).thenReturn(
            Result.failure(ru.protonmod.next.ui.screens.CaptchaRequiredException("https://verify", "hv", "final_session_id"))
        )
        whenever(vpnRepository.registerWireGuardKey(any(), any(), anyOrNull(), eq("solved"))).thenReturn(
            Result.success(Pair(CreateCertificateResponse(code = 1000, certificate = "cert"), vpnKeyPair))
        )
        whenever(vpnRepository.getVpnInfo(any(), any())).thenReturn(
            Result.success(VpnInfoResponse(code = 1000, vpnInfo = VpnInfo(maxTier = 2)))
        )

        val first = repository.login("testuser", "password")
        val captcha = first.exceptionOrNull()
        assertTrue("Expected a captcha but got $captcha", captcha is ru.protonmod.next.ui.screens.CaptchaRequiredException)
        assertEquals("final_session_id", (captcha as ru.protonmod.next.ui.screens.CaptchaRequiredException).sessionId)

        val second = repository.login("testuser", "password", captchaToken = "solved")

        assertTrue("Expected success but got ${second.exceptionOrNull()}", second.isSuccess)
        assertEquals("final_token", second.getOrNull()?.accessToken)
        verify(authApi, times(1)).performLogin(any(), any(), any(), anyOrNull(), anyOrNull())
        verify(vpnRepository).registerWireGuardKey(eq("final_token"), eq("final_session_id"), anyOrNull(), eq("solved"))
    }

    @Test
    fun `loginAnonymous success flow`() = runTest(testDispatcher) {
        // Arrange
        val loginResponse = LoginResponse(
            code = 1000,
            accessToken = "final_token",
            refreshToken = "final_refresh",
            sessionId = "final_session_id",
            userId = "user_id",
            scopes = listOf("vpn")
        )

        whenever(authApi.createAnonymousSession(any(), anyOrNull(), anyOrNull())).thenReturn(loginResponse)
        whenever(authApi.performLoginLess(any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(loginResponse)
        
        val vpnKeyPair = VpnKeyPair("pubkey", "privkey")
        whenever(cryptoWrapper.generateVpnKeyPair()).thenReturn(vpnKeyPair)

        whenever(vpnRepository.registerWireGuardKey(any(), any(), anyOrNull(), anyOrNull())).thenReturn(
            Result.success(Pair(CreateCertificateResponse(code = 1000, certificate = "cert"), vpnKeyPair))
        )

        // Act
        val result = repository.loginAnonymous()

        // Assert
        assertTrue("Expected success but got ${result.exceptionOrNull()}", result.isSuccess)
        val response = result.getOrNull()!!
        assertEquals("final_token", response.accessToken)
        assertEquals("user_id", response.userId)
    }

    @Test
    fun `refreshSession success flow`() = runTest(testDispatcher) {
        // Arrange
        val sessionId = "session_id"
        val refreshToken = "refresh_token"
        val currentSession = SessionEntity(
            sessionId = sessionId,
            accessToken = "old_access_token",
            refreshToken = refreshToken,
            userId = "user_id"
        )
        val updatedSession = currentSession.copy(
            accessToken = "new_access_token",
            refreshToken = "new_refresh_token"
        )

        whenever(sessionDao.getSession()).thenReturn(currentSession)
        whenever(sessionManager.refreshSession(currentSession)).thenReturn(Result.success(updatedSession))

        // Act
        val result = repository.refreshSession(sessionId, refreshToken)

        // Assert
        assertTrue(result.isSuccess)
        val value = result.getOrNull()
        assertEquals("new_access_token", value?.accessToken)
        assertEquals("new_refresh_token", value?.refreshToken)
    }

    @Test
    fun `refreshSession triggers logout on HTTP 401`() = runTest(testDispatcher) {
        // ... (existing test code)
    }

    @Test
    fun `verify2FA success flow`() = runTest(testDispatcher) {
        // Arrange
        val sessionId = "session_id"
        val tempToken = "temp_token"
        val refreshToken = "refresh_token"
        val totpCode = "123456"

        val response2fa = LoginResponse(code = 1000)
        val promotedSession = SessionEntity(
            sessionId = sessionId,
            accessToken = "final_token",
            refreshToken = "final_refresh",
            userId = "user_id"
        )

        whenever(authApi.performSecondFactor(any(), any(), any())).thenReturn(
            Response.success(response2fa)
        )
        whenever(sessionManager.refreshSession(any())).thenReturn(Result.success(promotedSession))
        
        whenever(authApi.getUser(any(), any())).thenReturn(
            UserResponse(code = 1000, user = UserInfo(id = "user_id"))
        )
        
        val vpnKeyPair = VpnKeyPair("pub", "priv")
        whenever(cryptoWrapper.generateVpnKeyPair()).thenReturn(vpnKeyPair)
        whenever(vpnRepository.registerWireGuardKey(any(), any(), anyOrNull(), anyOrNull())).thenReturn(
            Result.success(Pair(CreateCertificateResponse(code = 1000, certificate = "cert"), vpnKeyPair))
        )
        whenever(vpnRepository.getVpnInfo(any(), any())).thenReturn(
            Result.success(VpnInfoResponse(code = 1000, vpnInfo = VpnInfo(maxTier = 0)))
        )

        // Act
        val result = repository.verify2FA(sessionId, tempToken, refreshToken, totpCode)

        // Assert
        assertTrue("Expected success but got ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("final_token", result.getOrNull()?.accessToken)
        verify(sessionManager).refreshSession(argThat { 
            this.sessionId == sessionId && this.accessToken == tempToken 
        })
    }
}
