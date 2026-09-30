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
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.protonmod.next.di.ApplicationScope
import ru.protonmod.next.utils.ProtonLogger
import kotlin.time.Duration.Companion.seconds

/**
 * Lets the VPN service, which runs in its own ":vpn" process, wake the app process and ask for a
 * freshly built configuration: when Always-on VPN starts the service with nothing saved, or when
 * a restored tunnel no longer answers (for example because its certificate expired meanwhile).
 */
class VpnControlReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ControlEntryPoint {
        fun vpnAutomationManager(): VpnAutomationManager

        @ApplicationScope
        fun applicationScope(): CoroutineScope
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REQUEST_CONNECT) return
        val reason = intent.getStringExtra(ProtonVpnService.EXTRA_FAILURE_REASON).orEmpty()
        ProtonLogger.i(TAG, "VPN service asked for a configuration: $reason")

        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, ControlEntryPoint::class.java)
        val pending = goAsync()
        entryPoint.applicationScope().launch {
            try {
                // A broadcast may run for about ten seconds; building a configuration (certificate
                // refresh, endpoint lookup) normally fits, and the service retries otherwise.
                withTimeoutOrNull(RECEIVER_BUDGET) {
                    entryPoint.vpnAutomationManager().reconnectForService()
                }
            } catch (error: Exception) {
                ProtonLogger.w(TAG, "Could not reconnect for the VPN service: ${error.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "VpnControlReceiver"
        const val ACTION_REQUEST_CONNECT = "ru.protonmod.next.vpn.REQUEST_CONNECT"
        private val RECEIVER_BUDGET = 9.seconds
    }
}
