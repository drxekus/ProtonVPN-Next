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

package ru.protonmod.next.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.graphics.toColorInt
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.protonmod.next.vpn.ServerScope
import ru.protonmod.next.vpn.ServerSelector
import ru.protonmod.next.vpn.VpnTunnelState
import ru.protonmod.next.MainActivity
import ru.protonmod.next.R
import ru.protonmod.next.data.local.SessionDao
import ru.protonmod.next.data.local.SettingsManager
import ru.protonmod.next.data.network.LogicalServer
import ru.protonmod.next.data.repository.VpnRepository
import ru.protonmod.next.data.state.ConnectedServerState
import ru.protonmod.next.di.ApplicationScope
import ru.protonmod.next.vpn.AmneziaVpnManager
import ru.protonmod.next.vpn.ProtonVpnService
import ru.protonmod.next.utils.ProtonLogger
import javax.inject.Inject

@AndroidEntryPoint
class VpnWidgetProvider : AppWidgetProvider() {

    @Inject
    lateinit var amneziaVpnManager: AmneziaVpnManager

    @Inject
    lateinit var vpnRepository: VpnRepository

    @Inject
    lateinit var sessionDao: SessionDao

    @Inject
    lateinit var settingsManager: SettingsManager

    @Inject
    lateinit var connectedServerState: ConnectedServerState

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    companion object {
        const val ACTION_WIDGET_CLICK = "ru.protonmod.next.WIDGET_CLICK"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_CLICK) {
            val currentState = amneziaVpnManager.tunnelState.value
            if (currentState == VpnTunnelState.UP) {
                amneziaVpnManager.disconnect()
            } else {
                applicationScope.launch {
                    performQuickConnect()
                }
            }
        } else if (intent.action == ProtonVpnService.ACTION_STATE_CHANGED) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, VpnWidgetProvider::class.java))
            onUpdate(context, mgr, ids)
        }
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        ProtonLogger.d("VpnWidgetProvider", "Updating widget $appWidgetId")
        val views = RemoteViews(context.packageName, R.layout.widget_vpn)
        
        try {
            val currentState = amneziaVpnManager.tunnelState.value
            val isConnected = currentState == VpnTunnelState.UP
            val connectedServer = connectedServerState.connectedServer.value
            
            ProtonLogger.d("VpnWidgetProvider", "Status: isConnected=$isConnected, server=${connectedServer?.name}")
            
            if (isConnected && connectedServer != null) {
                views.setTextViewText(R.id.widget_status_text, context.getString(R.string.status_connected))
                views.setTextColor(R.id.widget_status_text, "#4CAF50".toColorInt())
                
                val countryName = ru.protonmod.next.ui.utils.CountryUtils.getCountryName(context, connectedServer.exitCountry)
                views.setTextViewText(R.id.widget_location_pill_text, "$countryName")
                
                views.setTextViewText(R.id.widget_server_country, countryName)
                views.setTextViewText(R.id.widget_server_name, connectedServer.name)
                
                val flagResId = ru.protonmod.next.ui.utils.CountryUtils.getFlagResource(context, connectedServer.exitCountry)
                if (flagResId != 0) {
                    views.setImageViewResource(R.id.widget_flag, flagResId)
                }
                
                views.setTextViewText(R.id.widget_button, context.getString(R.string.btn_disconnect))
                views.setInt(R.id.widget_button, "setBackgroundResource", R.drawable.widget_button_background_connected)
            } else {
                views.setTextViewText(R.id.widget_status_text, context.getString(R.string.status_disconnected))
                views.setTextColor(R.id.widget_status_text, "#E53935".toColorInt())
                
                views.setTextViewText(R.id.widget_location_pill_text, context.getString(R.string.status_not_connected))
                
                views.setTextViewText(R.id.widget_server_country, context.getString(R.string.qc_strategy_fastest))
                views.setTextViewText(R.id.widget_server_name, context.getString(R.string.label_select_location))
                views.setImageViewResource(R.id.widget_flag, R.drawable.flag_fastest)
                
                views.setTextViewText(R.id.widget_button, context.getString(R.string.btn_quick_connect))
                views.setInt(R.id.widget_button, "setBackgroundResource", R.drawable.widget_button_background_disconnected)
            }
            
            val intent = Intent(context, VpnWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_CLICK
            }
            val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            
            // Set click listener ONLY on the container or ONLY on the button to avoid rendering issues
            views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
            ProtonLogger.d("VpnWidgetProvider", "Widget $appWidgetId updated successfully")
        } catch (e: Exception) {
            ProtonLogger.e("VpnWidgetProvider", "Error updating widget $appWidgetId", e)
        }
    }

    private suspend fun performQuickConnect() {
        sessionDao.getSession() ?: return
        val servers = vpnRepository.getCachedServers()
        if (servers.isEmpty()) return

        val bestServer = ServerSelector.fastest(servers)
        if (bestServer != null) {
            initiateConnection(bestServer)
        }
    }

    private suspend fun initiateConnection(server: LogicalServer) {
        val session = sessionDao.getSession() ?: return
        val physicalServer = ServerSelector.pickPhysical(server) ?: return

        amneziaVpnManager.connect(
            server.id, physicalServer, session,
            logicalServer = server,
            failoverScope = ServerScope.AnyServer
        )
    }
}
