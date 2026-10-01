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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import ru.protonmod.next.utils.ProtonLogger
import ru.protonmod.next.vpn.ObfuscationLadder.ArmStats
import ru.protonmod.next.vpn.ObfuscationLadder.Variant
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which [ObfuscationLadder] variant works on which network and picks the next one.
 *
 * Networks are told apart by the mobile operator code or, for Wi-Fi, by a hash of the gateway
 * address and DHCP domain, so nothing readable about a Wi-Fi network is stored. Everything stays
 * in the app's private storage.
 *
 * A new connection reuses what last worked on the network. Only after a failure does the ladder
 * explore, by Thompson sampling over the decayed results. Once a week a heavier variant that has
 * become the habit is set aside for one try of the user's own choice, so a lifted block does not
 * leave the app sending more junk than needed forever.
 */
@Singleton
class ObfuscationAdvisor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val lock = Any()

    /** Per-install seed for the variants' packet sizes; drawn once. */
    val installSeed: Long
        get() = synchronized(lock) {
            prefs.getLong(KEY_SEED, 0L).takeIf { it != 0L } ?: SecureRandom().nextLong().also {
                prefs.edit().putLong(KEY_SEED, it).apply()
            }
        }

    fun pickForNewConnection(favorite: Variant, now: Long = System.currentTimeMillis()): Variant = synchronized(lock) {
        val key = currentNetworkKey()
        val network = load(key)
        val last = network.last
        val choice = when {
            last != null && last != favorite && now - network.lastSince > RETRY_LIGHTER_AFTER_MS -> {
                save(key, network.copy(lastSince = now, seen = now))
                favorite
            }
            last != null && ObfuscationLadder.decay(network.arms[last] ?: ArmStats(), now)
                .let { it.successes >= it.failures } -> last
            network.arms.isEmpty() -> favorite
            else -> ObfuscationLadder.choose(network.arms, favorite, emptySet(), now)
        }
        ProtonLogger.i(TAG, "Obfuscation for a new connection on ${kindOf(key)}: ${choice.id}")
        choice
    }

    /** Records that [failed] got no handshake and returns the variant for the next attempt. */
    fun nextAfterFailure(
        failed: Variant,
        favorite: Variant,
        tried: Set<Variant>,
        now: Long = System.currentTimeMillis()
    ): Variant = synchronized(lock) {
        val key = currentNetworkKey()
        val network = load(key)
        val arms = network.arms + (failed to ObfuscationLadder.recordFailure(network.arms[failed], now))
        save(key, network.copy(arms = arms, seen = now))
        val untried = Variant.entries.any { it !in tried && it != failed }
        // Once every variant failed in this round, start over with all but the one that just failed.
        val exclude = if (untried) tried + failed else setOf(failed)
        val next = ObfuscationLadder.choose(arms, favorite, exclude, now)
        ProtonLogger.i(TAG, "Obfuscation ${failed.id} got no handshake on ${kindOf(key)}; trying ${next.id}")
        next
    }

    fun recordSuccess(variant: Variant, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val key = currentNetworkKey()
        val network = load(key)
        val arms = network.arms + (variant to ObfuscationLadder.recordSuccess(network.arms[variant], now))
        val lastSince = if (network.last == variant) network.lastSince else now
        save(key, network.copy(arms = arms, last = variant, lastSince = lastSince, seen = now))
    }

    /** What last worked on the current network, for the settings screen. */
    fun currentNetworkVariant(): Variant? = synchronized(lock) { load(currentNetworkKey()).last }

    private data class NetworkState(
        val arms: Map<Variant, ArmStats> = emptyMap(),
        val last: Variant? = null,
        val lastSince: Long = 0L,
        val seen: Long = 0L
    )

    private fun load(key: String): NetworkState {
        val json = prefs.getString(PREFIX + key, null) ?: return NetworkState()
        return runCatching {
            val obj = JSONObject(json)
            val armsJson = obj.optJSONObject("arms") ?: JSONObject()
            val arms = armsJson.keys().asSequence().mapNotNull { id ->
                val variant = Variant.fromId(id) ?: return@mapNotNull null
                val arm = armsJson.getJSONObject(id)
                variant to ArmStats(arm.optDouble("s", 0.0), arm.optDouble("f", 0.0), arm.optLong("t", 0L))
            }.toMap()
            NetworkState(
                arms = arms,
                last = Variant.fromId(obj.optString("last", "")),
                lastSince = obj.optLong("lastSince"),
                seen = obj.optLong("seen")
            )
        }.getOrDefault(NetworkState())
    }

    private fun save(key: String, state: NetworkState) {
        val arms = JSONObject()
        state.arms.forEach { (variant, arm) ->
            arms.put(variant.id, JSONObject().put("s", arm.successes).put("f", arm.failures).put("t", arm.updatedAt))
        }
        val json = JSONObject().put("arms", arms).put("lastSince", state.lastSince).put("seen", state.seen)
        state.last?.let { json.put("last", it.id) }
        val editor = prefs.edit().putString(PREFIX + key, json.toString())
        // Keep the store small: forget the networks not seen for the longest time.
        val others = prefs.all.keys.filter { it.startsWith(PREFIX) && it != PREFIX + key }
        if (others.size >= MAX_NETWORKS) {
            others.sortedBy { other ->
                runCatching { JSONObject(prefs.getString(other, null) ?: "{}").optLong("seen") }.getOrDefault(0L)
            }.take(others.size - MAX_NETWORKS + 1).forEach(editor::remove)
        }
        editor.apply()
    }

    /** The physical network the tunnel runs over, as an opaque key. */
    private fun currentNetworkKey(): String {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val best = connectivity.allNetworks.mapNotNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) return@mapNotNull null
            network to caps
        }.sortedWith(
            compareByDescending<Pair<Network, NetworkCapabilities>> {
                it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }.thenByDescending { it.second.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
        ).firstOrNull() ?: return "unknown"
        val (network, caps) = best
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                val operator = runCatching { telephony?.networkOperator }.getOrNull().orEmpty()
                "cell:" + operator.ifBlank { "unknown" }
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val properties = connectivity.getLinkProperties(network)
                val gateway = properties?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress.orEmpty()
                "wifi:" + hash(gateway + "|" + properties?.domains.orEmpty())
            }
            else -> "other"
        }
    }

    /** Logs say only "cell" or "wifi": they may be shared, the operator code should not be. */
    private fun kindOf(key: String) = key.substringBefore(':')

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "ObfuscationAdvisor"
        const val PREFS_NAME = "obfuscation_ladder"
        const val KEY_SEED = "install_seed"
        const val PREFIX = "net:"
        const val MAX_NETWORKS = 16
        const val RETRY_LIGHTER_AFTER_MS = 7L * 24 * 60 * 60 * 1000
    }
}
