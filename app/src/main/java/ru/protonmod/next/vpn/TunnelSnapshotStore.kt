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
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import ru.protonmod.next.utils.ProtonLogger
import java.io.File

/**
 * Keeps the last CONNECT command of the VPN service on disk, inside the ":vpn" process.
 *
 * Android restarts a killed VPN service with a null intent (START_STICKY) and starts it with the
 * `android.net.VpnService` action for Always-on VPN. Neither carries a configuration, and the app
 * process that normally builds one may be dead too. With this snapshot the service can bring the
 * last tunnel back on its own instead of stopping itself and leaving the device offline.
 *
 * The file lives in no-backup storage because the configuration contains the WireGuard key.
 */
internal class TunnelSnapshotStore(context: Context) {
    private val file = File(context.noBackupFilesDir, FILE_NAME)
    private val flagFile = File(context.noBackupFilesDir, USER_STOPPED_FLAG)

    fun save(intent: Intent) {
        val config = intent.getStringExtra(ProtonVpnService.EXTRA_CONFIG) ?: return
        val json = JSONObject().apply {
            put(ProtonVpnService.EXTRA_CONFIG, config)
            putOpt(ProtonVpnService.EXTRA_LOGICAL_SERVER_ID, intent.getStringExtra(ProtonVpnService.EXTRA_LOGICAL_SERVER_ID))
            BOOLEAN_EXTRAS.forEach { key ->
                if (intent.hasExtra(key)) put(key, intent.getBooleanExtra(key, false))
            }
            putOpt(ProtonVpnService.EXTRA_VERIFICATION_MODE, intent.getStringExtra(ProtonVpnService.EXTRA_VERIFICATION_MODE))
            putOpt(ProtonVpnService.EXTRA_SPLIT_TUNNELING_MODE, intent.getStringExtra(ProtonVpnService.EXTRA_SPLIT_TUNNELING_MODE))
            if (intent.hasExtra(ProtonVpnService.EXTRA_HANDSHAKE_TIMEOUT_SECONDS)) {
                put(
                    ProtonVpnService.EXTRA_HANDSHAKE_TIMEOUT_SECONDS,
                    intent.getIntExtra(ProtonVpnService.EXTRA_HANDSHAKE_TIMEOUT_SECONDS, 0)
                )
            }
            put(ProtonVpnService.EXTRA_EXCLUDED_APPS, JSONArray(intent.getStringArrayListExtra(ProtonVpnService.EXTRA_EXCLUDED_APPS).orEmpty()))
            put(ProtonVpnService.EXTRA_EXCLUDED_IPS, JSONArray(intent.getStringArrayListExtra(ProtonVpnService.EXTRA_EXCLUDED_IPS).orEmpty()))
        }
        runCatching {
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }.onFailure { ProtonLogger.w(TAG, "Could not persist the tunnel snapshot: ${it.message}") }
    }

    /** Rebuilds the last CONNECT command, or null when there is none or it cannot be read. */
    fun load(context: Context): Intent? = runCatching {
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        Intent(context, ProtonVpnService::class.java).apply {
            action = ProtonVpnService.ACTION_CONNECT
            putExtra(ProtonVpnService.EXTRA_CONFIG, json.getString(ProtonVpnService.EXTRA_CONFIG))
            json.optStringOrNull(ProtonVpnService.EXTRA_LOGICAL_SERVER_ID)?.let {
                putExtra(ProtonVpnService.EXTRA_LOGICAL_SERVER_ID, it)
            }
            BOOLEAN_EXTRAS.forEach { key -> if (json.has(key)) putExtra(key, json.getBoolean(key)) }
            json.optStringOrNull(ProtonVpnService.EXTRA_VERIFICATION_MODE)?.let {
                putExtra(ProtonVpnService.EXTRA_VERIFICATION_MODE, it)
            }
            json.optStringOrNull(ProtonVpnService.EXTRA_SPLIT_TUNNELING_MODE)?.let {
                putExtra(ProtonVpnService.EXTRA_SPLIT_TUNNELING_MODE, it)
            }
            if (json.has(ProtonVpnService.EXTRA_HANDSHAKE_TIMEOUT_SECONDS)) {
                putExtra(
                    ProtonVpnService.EXTRA_HANDSHAKE_TIMEOUT_SECONDS,
                    json.getInt(ProtonVpnService.EXTRA_HANDSHAKE_TIMEOUT_SECONDS)
                )
            }
            putStringArrayListExtra(ProtonVpnService.EXTRA_EXCLUDED_APPS, json.optStringList(ProtonVpnService.EXTRA_EXCLUDED_APPS))
            putStringArrayListExtra(ProtonVpnService.EXTRA_EXCLUDED_IPS, json.optStringList(ProtonVpnService.EXTRA_EXCLUDED_IPS))
        }
    }.onFailure {
        ProtonLogger.w(TAG, "Discarding an unreadable tunnel snapshot: ${it.message}")
        file.delete()
    }.getOrNull()

    /** Remembers that the user turned the VPN off, so a sticky restart must not undo it. */
    var userStopped: Boolean
        get() = flagFile.exists()
        set(value) {
            runCatching { if (value) flagFile.createNewFile() else flagFile.delete() }
        }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    private fun JSONObject.optStringList(key: String): ArrayList<String> {
        val array = optJSONArray(key) ?: return arrayListOf()
        return ArrayList((0 until array.length()).map(array::getString))
    }

    private companion object {
        const val TAG = "TunnelSnapshotStore"
        const val FILE_NAME = "vpn_last_tunnel.json"
        const val USER_STOPPED_FLAG = "vpn_user_stopped"
        val BOOLEAN_EXTRAS = listOf(
            ProtonVpnService.EXTRA_NOTIFICATIONS_ENABLED,
            ProtonVpnService.EXTRA_KILL_SWITCH_ENABLED,
            ProtonVpnService.EXTRA_VERIFICATION_REQUIRED,
            ProtonVpnService.EXTRA_FAILURE_DETECTION_ENABLED,
            ProtonVpnService.EXTRA_AUTO_RECONNECT_ENABLED,
            ProtonVpnService.EXTRA_SPLIT_TUNNELING_ENABLED,
        )
    }
}
