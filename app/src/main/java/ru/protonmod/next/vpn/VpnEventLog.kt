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
import ru.protonmod.next.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A small on-device timeline of tunnel events (network changes, handshakes, stalls, recoveries)
 * for debug builds. Logcat on many phones keeps only a few minutes, far too little to explain
 * what happened during a walk across several cells. Addresses are never written here.
 *
 * Read it with: adb shell run-as <package> cat files/vpn-events.log
 */
internal object VpnEventLog {
    private const val FILE_NAME = "vpn-events.log"
    private const val MAX_BYTES = 1_000_000L

    private val writer = Executors.newSingleThreadExecutor { Thread(it, "vpn-event-log").apply { isDaemon = true } }
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var file: File? = null

    fun init(context: Context) {
        // The app and the VPN process both append here; each line is one small append.
        val dir = context.filesDir ?: return
        if (BuildConfig.DEBUG) file = File(dir, FILE_NAME)
    }

    fun log(event: String) {
        val target = file ?: return
        val line = "${synchronized(timeFormat) { timeFormat.format(Date()) }} $event\n"
        writer.execute {
            runCatching {
                if (target.length() > MAX_BYTES) {
                    val previous = File(target.parentFile, "$FILE_NAME.1")
                    previous.delete()
                    target.renameTo(previous)
                }
                target.appendText(line)
            }
        }
    }
}
