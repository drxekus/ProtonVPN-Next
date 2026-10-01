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

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.protonmod.next.vpn.VpnTunnelState
import ru.protonmod.next.MainActivity
import ru.protonmod.next.R
import ru.protonmod.next.data.local.ProfileDao
import ru.protonmod.next.data.local.RecentConnectionDao
import ru.protonmod.next.data.local.SessionDao
import ru.protonmod.next.data.local.SettingsManager
import ru.protonmod.next.data.local.VpnProfileEntity
import ru.protonmod.next.data.model.ObfuscationProfile
import ru.protonmod.next.data.network.LogicalServer
import ru.protonmod.next.data.repository.VpnRepository
import ru.protonmod.next.data.state.ConnectedServerState
import ru.protonmod.next.di.ApplicationScope
import ru.protonmod.next.utils.ProtonLogger
import javax.inject.Inject

@AndroidEntryPoint
class VpnTileService : TileService() {

    @Inject
    lateinit var amneziaVpnManager: AmneziaVpnManager

    @Inject
    lateinit var vpnRepository: VpnRepository

    @Inject
    lateinit var sessionDao: SessionDao

    @Inject
    lateinit var settingsManager: SettingsManager

    @Inject
    lateinit var profileDao: ProfileDao

    @Inject
    lateinit var recentConnectionDao: RecentConnectionDao

    @Inject
    lateinit var connectedServerState: ConnectedServerState

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    /**
     * Service-scoped coroutine scope. Cancelled in [onDestroy] to prevent the
     * destroyed service instance from being retained by running coroutines
     * (the framework's internal IPC binder holds `this` as a JNI global ref
     * and won't release it until all work referencing the service is done).
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var observationJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        observationJob?.cancel()
        observationJob = serviceScope.launch {
            combine(
                amneziaVpnManager.tunnelState,
                settingsManager.quickConnectStrategy,
                settingsManager.quickConnectTargetId,
                connectedServerState.connectedServer
            ) { state, strategy, targetId, connectedServer ->
                UpdateParams(state, strategy, targetId, connectedServer)
            }.collect { params ->
                updateTile(params.state, params.strategy, params.targetId, params.connectedServer)
            }
        }
    }

    private data class UpdateParams(
        val state: VpnTunnelState,
        val strategy: String,
        val targetId: String?,
        val connectedServer: LogicalServer?
    )

    override fun onStopListening() {
        super.onStopListening()
        observationJob?.cancel()
        observationJob = null
    }

    override fun onDestroy() {
        serviceScope.cancel()
        observationJob = null
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val currentState = amneziaVpnManager.tunnelState.value
        
        if (currentState == VpnTunnelState.UP) {
            amneziaVpnManager.disconnect()
        } else {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                val wrapper = PendingIntentActivityWrapper(
                    this,
                    0,
                    intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                    false
                )
                TileServiceCompat.startActivityAndCollapse(this, wrapper)
            } else {
                // Launch on applicationScope: the VPN connection must proceed
                // even if this TileService instance is destroyed mid-connect.
                applicationScope.launch {
                    performQuickConnect()
                }
            }
        }
    }

    private suspend fun performQuickConnect() {
        val session = sessionDao.getSession() ?: return
        val strategy = settingsManager.quickConnectStrategy.first()
        val targetId = settingsManager.quickConnectTargetId.first()
        
        val servers = vpnRepository.getCachedServers()
        if (servers.isEmpty()) return

        when (strategy) {
            "recent" -> {
                // The last choice of the user, as on the dashboard (see LastChoice).
                val choice = LastChoice.decode(settingsManager.lastConnectChoice.first()) ?: LastChoice.Fastest
                choice.resolve(servers)?.let { (server, scope) -> initiateConnection(server, failoverScope = scope) }
            }
            "server" -> {
                val target = servers.find { it.id == targetId }
                if (target != null) {
                    initiateConnection(target)
                } else {
                    connectToFastest(servers)
                }
            }
            "profile" -> {
                val profile = profileDao.getAllProfilesFlow().first().find { it.id == targetId }
                if (profile != null) {
                    val targetServer = findBestServerForProfile(profile, servers)
                    if (targetServer != null) {
                        val physicalServer = ServerSelector.pickPhysical(targetServer)

                        if (physicalServer != null) {
                            var obfuscationParams: AmneziaVpnManager.ObfuscationParams? = null
                            if (profile.isObfuscationEnabled && profile.obfuscationProfileId != null) {
                                val customProfiles = settingsManager.customProfiles.first()
                                val selectedConfig = customProfiles.find { it.id == profile.obfuscationProfileId }
                                    ?: if (profile.obfuscationProfileId == "standard_1") {
                                        ObfuscationProfile.getStandardProfile()
                                    } else null

                                selectedConfig?.let {
                                    obfuscationParams = AmneziaVpnManager.ObfuscationParams(
                                        jc = it.jc, jmin = it.jmin, jmax = it.jmax,
                                        s1 = it.s1, s2 = it.s2, s3 = it.s3, s4 = it.s4,
                                        h1 = it.h1, h2 = it.h2, h3 = it.h3, h4 = it.h4,
                                        i1 = it.i1, i2 = it.i2, i3 = it.i3, i4 = it.i4, i5 = it.i5
                                    )
                                }
                            }

                            amneziaVpnManager.connect(
                                targetServer.id, physicalServer, session,
                                overridePort = profile.port,
                                overrideObfuscation = profile.isObfuscationEnabled,
                                obfuscationParams = obfuscationParams,
                                logicalServer = targetServer
                            )
                        }
                    }
                } else {
                    connectToFastest(servers)
                }
            }
            else -> connectToFastest(servers)
        }
    }

    private suspend fun connectToFastest(servers: List<LogicalServer>) {
        ServerSelector.fastest(servers)?.let { initiateConnection(it, failoverScope = ServerScope.AnyServer) }
    }

    private fun findBestServerForProfile(profile: VpnProfileEntity, allServers: List<LogicalServer>): LogicalServer? {
        return vpnRepository.findBestServerForProfile(profile, allServers)
    }

    private suspend fun initiateConnection(server: LogicalServer, failoverScope: ServerScope? = null) {
        val session = sessionDao.getSession() ?: return
        val physicalServer = ServerSelector.pickPhysical(server) ?: return

        amneziaVpnManager.connect(
            server.id, physicalServer, session,
            logicalServer = server,
            failoverScope = failoverScope
        )
    }

    private suspend fun updateTile(
        state: VpnTunnelState,
        strategy: String,
        targetId: String?,
        connectedServer: LogicalServer?
    ) {
        val tile = qsTile ?: return
        
        tile.state = when (state) {
            VpnTunnelState.UP -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }

        val subtitle = if (state == VpnTunnelState.UP && connectedServer != null) {
            getString(R.string.tile_subtitle_connected, connectedServer.name)
        } else {
            when (strategy) {
                "fastest" -> getString(R.string.tile_subtitle_fastest)
                "recent" -> getString(R.string.tile_subtitle_last)
                "server" -> {
                    val serverName = vpnRepository.getCachedServers().find { it.id == targetId }?.name ?: "..."
                    getString(R.string.tile_subtitle_specific, serverName)
                }
                "profile" -> {
                    val profileName = profileDao.getAllProfilesFlow().first().find { it.id == targetId }?.name ?: "..."
                    getString(R.string.tile_subtitle_specific, profileName)
                }
                else -> getString(R.string.tile_subtitle_fastest)
            }
        }

        tile.subtitle = subtitle

        tile.updateTile()
    }
}
