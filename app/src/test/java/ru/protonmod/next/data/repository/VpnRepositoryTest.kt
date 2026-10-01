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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*
import retrofit2.Response
import ru.protonmod.next.data.local.AppDatabase
import ru.protonmod.next.data.local.ServerDao
import ru.protonmod.next.data.local.ServerEntity
import ru.protonmod.next.data.local.ServersCacheDao
import ru.protonmod.next.data.local.ServersCacheEntity
import ru.protonmod.next.data.local.SessionDao
import ru.protonmod.next.data.local.SessionEntity
import ru.protonmod.next.data.network.*
import ru.protonmod.next.utils.coroutines.DispatcherProvider
import java.util.concurrent.Executor

import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class VpnRepositoryTest {

    @Mock
    private lateinit var vpnApi: ProtonVpnApi
    
    @Mock
    private lateinit var database: AppDatabase

    @Mock
    private lateinit var serverDao: ServerDao
    
    @Mock
    private lateinit var sessionDao: SessionDao
    
    @Mock
    private lateinit var serversCacheDao: ServersCacheDao

    @Mock
    private lateinit var cityTranslationDao: ru.protonmod.next.data.local.CityTranslationDao

    @Mock
    private lateinit var profileDao: ru.protonmod.next.data.local.ProfileDao

    @Mock
    private lateinit var recentConnectionDao: ru.protonmod.next.data.local.RecentConnectionDao

    @Mock
    private lateinit var cityRepository: ru.protonmod.next.data.repository.CityRepository

    @Mock
    private lateinit var cryptoWrapper: ru.protonmod.next.utils.crypto.CryptoWrapper

    @Mock
    private lateinit var settingsManager: ru.protonmod.next.data.local.SettingsManager

    private val testDispatcher = StandardTestDispatcher()
    
    private val testDispatcherProvider = object : DispatcherProvider {
        override fun main(): CoroutineDispatcher = testDispatcher
        override fun io(): CoroutineDispatcher = testDispatcher
        override fun default(): CoroutineDispatcher = testDispatcher
    }
    
    private val testScope = CoroutineScope(testDispatcher + SupervisorJob())

    private lateinit var repository: VpnRepository

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        
        repository = object : VpnRepository(
            vpnApi, database, serverDao, sessionDao, serversCacheDao,
            cityTranslationDao, profileDao, recentConnectionDao,
            cityRepository, testDispatcherProvider, cryptoWrapper, testScope, settingsManager
        ) {
            override suspend fun <R> performTransaction(block: suspend () -> R): R = block()
        }
    }

    @Test
    fun `getServers maps logical loads correctly and saves to DB`() = runTest(testDispatcher) {
        // Arrange
        val logicalId = "logical_1"
        val physicalId = "physical_1"
        val loadValue = 75

        val logicalServersResponse = LogicalServersResponse(
            code = 1000,
            logicalServers = listOf(
                LogicalServer(
                    id = logicalId,
                    name = "Test Server",
                    tier = 0,
                    features = 0,
                    entryCountry = "US",
                    exitCountry = "US",
                    city = "New York",
                    servers = listOf(
                        PhysicalServer(id = physicalId, domain = "test.protonvpn.com", status = 1)
                    )
                )
            )
        )

        val loadsJson = """
            {
                "Code": 1000,
                "LogicalServers": [
                    {
                        "ID": "$logicalId",
                        "Load": $loadValue
                    },
                    {
                        "ID": "$physicalId",
                        "Load": $loadValue
                    }
                ]
            }
        """.trimIndent()

        // Mock Session
        whenever(sessionDao.getSession()).thenReturn(
            SessionEntity(
                accessToken = "token",
                refreshToken = "refresh",
                sessionId = "session",
                userId = "user",
                userTier = 0
            )
        )

        // Mock DAO to simulate DB behavior
        val dbServers = mutableListOf<ServerEntity>()
        whenever(serverDao.upsertServers(any())).thenAnswer { invocation ->
            val list = invocation.getArgument<List<ServerEntity>>(0)
            dbServers.clear()
            dbServers.addAll(list)
            null
        }
        whenever(serverDao.getAllServers()).thenAnswer { dbServers.toList() }
        whenever(serverDao.updateServerLoad(any(), any())).thenAnswer { invocation ->
            val id = invocation.getArgument<String>(0)
            val load = invocation.getArgument<Int>(1)
            val index = dbServers.indexOfFirst { it.id == id }
            if (index != -1) {
                dbServers[index] = dbServers[index].copy(averageLoad = load)
            }
            null
        }

        // Mock Cache (empty)
        whenever(serversCacheDao.getCacheInfo()).thenReturn(null)

        // Mock API with flexible matching
        whenever(vpnApi.getLogicalServers(
            authorization = any(),
            sessionId = any(),
            lastModified = anyOrNull(),
            locale = anyOrNull(),
            protocols = anyOrNull(),
            withState = any(),
            netzone = anyOrNull()
        )).thenReturn(Response.success(logicalServersResponse))

        whenever(vpnApi.getLoads(any(), any(), anyOrNull())).thenReturn(
            Response.success(loadsJson.toResponseBody())
        )
        
        whenever(vpnApi.getServerCities(any(), any(), any())).thenReturn(
            CityTranslationsResponse(languageCode = "en", cities = emptyMap(), states = emptyMap())
        )

        // Act
        val result = repository.getServers("token", "session", 0, forceRefresh = true)

        // Assert
        assertTrue("Expected success but got ${result.exceptionOrNull()}", result.isSuccess)
        val servers = result.getOrNull()!!
        assertEquals(1, servers.size)
        assertEquals(loadValue, servers[0].averageLoad)
        
        // Verify DB interactions
        verify(serverDao, atLeastOnce()).upsertServers(any())
        verify(serversCacheDao, atLeastOnce()).saveCacheInfo(any())
    }

    @Test
    fun `netzone keeps the network and drops the host part`() {
        assertEquals("212.32.192.0", VpnRepository.netzoneOf("212.32.192.34"))
        assertEquals(null, VpnRepository.netzoneOf("2001:db8::1"))
        assertEquals(null, VpnRepository.netzoneOf("unknown"))
        assertEquals(null, VpnRepository.netzoneOf(null))
    }
}
