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

import android.content.Context
import ru.protonmod.next.utils.ProtonLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.protonmod.next.vpn.VpnTunnelState
import ru.protonmod.next.data.local.RecentConnectionDao
import ru.protonmod.next.data.local.SessionDao
import ru.protonmod.next.data.local.SessionEntity
import ru.protonmod.next.data.local.SettingsManager
import ru.protonmod.next.data.network.LogicalServer
import ru.protonmod.next.data.network.PhysicalServer
import ru.protonmod.next.data.repository.VpnRepository
import ru.protonmod.next.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnAutomationManager @Inject constructor(
    private val amneziaVpnManager: AmneziaVpnManager,
    private val settingsManager: SettingsManager,
    private val vpnRepository: VpnRepository,
    private val sessionDao: SessionDao,
    private val recentConnectionDao: RecentConnectionDao,
    @ApplicationScope private val applicationScope: CoroutineScope
) {
    private val TAG = "VpnAutomationManager"

    init {
        applicationScope.launch {
            // Monitor Pause state with a real timer
            var timerJob: Job? = null
            
            settingsManager.pauseEndTime.collect { endTime ->
                timerJob?.cancel()
                val now = System.currentTimeMillis()
                
                if (endTime > now) {
                    val delayMs = endTime - now
                    ProtonLogger.d(TAG, "VPN is paused. Waiting ${delayMs}ms to auto-resume.")
                    
                    timerJob = applicationScope.launch {
                        delay(delayMs)
                        
                        // Final check: did someone manually resume or change the timer?
                        val currentEndTime = settingsManager.pauseEndTime.first()
                        if (currentEndTime != 0L && currentEndTime <= System.currentTimeMillis()) {
                            withContext(NonCancellable) {
                                ProtonLogger.i(TAG, "Pause expired, auto-resuming...")
                                amneziaVpnManager.resumeVpn()
                                triggerAutoConnect()
                            }
                        }
                    }
                } else if (endTime > 0) {
                    // Already expired but not cleared (e.g. app just started)
                    // Add a small safety delay to avoid race condition on immediate pause calls
                    timerJob = applicationScope.launch {
                        delay(500)
                        val currentEndTime = settingsManager.pauseEndTime.first()
                        if (currentEndTime > 0 && currentEndTime <= System.currentTimeMillis()) {
                            withContext(NonCancellable) {
                                ProtonLogger.i(TAG, "Detected expired pause, clearing and resuming...")
                                amneziaVpnManager.resumeVpn()
                                triggerAutoConnect()
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun resumeVpn() {
        withContext(NonCancellable) {
            ProtonLogger.i(TAG, "resumeVpn: Manually requested resumption.")
            amneziaVpnManager.resumeVpn()
            triggerAutoConnect()
        }
    }

    private suspend fun triggerAutoConnect() {
        val target = preferredTarget("triggerAutoConnect") ?: return
        ProtonLogger.i(TAG, "triggerAutoConnect: Initiating connection to ${target.logical.name}")
        amneziaVpnManager.connect(
            target.logical.id, target.physical, target.session,
            logicalServer = target.logical,
            failoverScope = target.scope
        )
    }

    /**
     * Called when the VPN service, running without the app, needs a fresh configuration: Always-on
     * VPN started it with nothing saved, or its saved tunnel no longer answers. Replays the last
     * connection of this process when there is one, otherwise the most recent server.
     */
    suspend fun reconnectForService() {
        if (amneziaVpnManager.reconnectCurrentForService()) return
        val target = preferredTarget("reconnectForService") ?: return
        ProtonLogger.i(TAG, "reconnectForService: connecting to ${target.logical.name}")
        amneziaVpnManager.connectForService(target.logical, target.physical, target.session, target.scope)
    }

    private class Target(
        val logical: LogicalServer,
        val physical: PhysicalServer,
        val session: SessionEntity,
        val scope: ServerScope?,
    )

    /** The most recent server if it is online, otherwise the fastest one. */
    private suspend fun preferredTarget(caller: String): Target? {
        val pauseEndTime = settingsManager.pauseEndTime.first()
        if (pauseEndTime > System.currentTimeMillis()) {
            ProtonLogger.d(TAG, "$caller: Still paused until $pauseEndTime. Skipping.")
            return null
        }

        val session = sessionDao.getSession() ?: run {
            ProtonLogger.w(TAG, "$caller: No active session found.")
            return null
        }

        val servers = vpnRepository.getCachedServers()
        if (servers.isEmpty()) {
            ProtonLogger.w(TAG, "$caller: Server cache is empty.")
            return null
        }

        // Follow the quick-connect setting. "Last used" is the last choice of the user; it used to
        // be the most recent server, which kept an automatic pick forever.
        val choice = when (settingsManager.quickConnectStrategy.first()) {
            "fastest" -> LastChoice.Fastest
            "server" -> settingsManager.quickConnectTargetId.first()?.let(LastChoice::Server) ?: LastChoice.Fastest
            else -> LastChoice.decode(settingsManager.lastConnectChoice.first()) ?: LastChoice.Fastest
        }
        val (logical, scope) = choice.resolve(servers) ?: run {
            ProtonLogger.w(TAG, "$caller: No online server available.")
            return null
        }
        ProtonLogger.i(TAG, "$caller: ${choice.encode()} -> ${logical.name}; ranking: ${ServerSelector.describeTop(servers)}")
        val physical = ServerSelector.pickPhysical(logical) ?: return null
        return Target(logical, physical, session, scope)
    }
}
