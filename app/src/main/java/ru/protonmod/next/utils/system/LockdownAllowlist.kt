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

package ru.protonmod.next.utils.system

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import ru.protonmod.next.utils.ProtonLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android's lockdown exception list: apps in it may reach the network directly while Always-on
 * VPN blocks connections without VPN. Settings has no screen for it; the shell can write it, so
 * this goes through Shizuku when the user asks. Android reads the list when the phone starts.
 *
 * Shizuku gives this app the shell's rights. They are used for exactly two commands, reading and
 * writing this one setting, and only on a tap in the split tunnelling screen.
 */
@Singleton
class LockdownAllowlist @Inject constructor() {

    enum class Access { UNAVAILABLE, NEEDS_PERMISSION, READY }

    fun access(): Access = runCatching {
        when {
            !Shizuku.pingBinder() || Shizuku.isPreV11() -> Access.UNAVAILABLE
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> Access.READY
            else -> Access.NEEDS_PERMISSION
        }
    }.getOrDefault(Access.UNAVAILABLE)

    /** Shows Shizuku's permission prompt; [onResult] gets whether it was granted. */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        runCatching {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(REQUEST_CODE)
        }.onFailure {
            Shizuku.removeRequestPermissionResultListener(listener)
            onResult(false)
        }
    }

    /** The packages in the list now, or null when Shizuku cannot be used. */
    fun read(): List<String>? = shell("settings", "get", "secure", SETTING)?.let { value ->
        if (value.isBlank() || value == "null") emptyList()
        else value.split(',').map(String::trim).filter(String::isNotEmpty)
    }

    /** Writes [packages] as the list, or removes the list when it is empty; true if it stuck. */
    fun write(packages: Collection<String>): Boolean {
        val valid = packages.filter(PACKAGE_NAME::matches).distinct().sorted()
        val written = if (valid.isEmpty()) shell("settings", "delete", "secure", SETTING)
        else shell("settings", "put", "secure", SETTING, valid.joinToString(","))
        if (written == null) return false
        val stored = read()?.sorted()
        ProtonLogger.i(TAG, "Lockdown exception list now holds ${stored?.size ?: "?"} apps")
        return stored == valid
    }

    /** Runs one command as the shell user, without a shell interpreter; null if it failed. */
    private fun shell(vararg command: String): String? = runCatching {
        if (access() != Access.READY) return null
        // newProcess is private in the Shizuku API; the replacement (a bound UserService) is a
        // separate APK component, far more than two settings commands need.
        val newProcess = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
        val process = newProcess.invoke(null, arrayOf(*command), null, null) as Process
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            ProtonLogger.w(TAG, "${command.take(3).joinToString(" ")} exited with $exitCode")
            null
        } else output
    }.onFailure { ProtonLogger.w(TAG, "Shizuku command failed: ${it.message}") }.getOrNull()

    private companion object {
        const val TAG = "LockdownAllowlist"
        const val SETTING = "always_on_vpn_lockdown_whitelist"
        const val REQUEST_CODE = 4917
        val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    }
}
