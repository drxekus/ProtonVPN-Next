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
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import ru.protonmod.next.utils.ProtonLogger

/**
 * Installing an update kills the running VPN process. Some ROMs (Realme/ColorOS among them) do
 * not restart an Always-on VPN after that, and with "Block connections without VPN" the phone
 * then stays offline until the app is opened. Bring the last tunnel back unless the user had
 * turned the VPN off.
 */
class AppUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val store = TunnelSnapshotStore(context)
        if (store.userStopped || store.load(context) == null) return
        // Without VPN consent the service could not establish a tunnel anyway.
        if (VpnService.prepare(context) != null) return
        ProtonLogger.i(TAG, "App updated while the VPN was on; restoring the tunnel")
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ProtonVpnService::class.java).setAction(ProtonVpnService.ACTION_RESUME)
            )
        }.onFailure { ProtonLogger.w(TAG, "Could not restart the VPN after the update: ${it.message}") }
    }

    private companion object {
        const val TAG = "AppUpdateReceiver"
    }
}
