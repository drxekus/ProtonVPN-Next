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

import ru.protonmod.next.data.local.SettingsManager
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Obfuscation the app can switch between when a network silently drops WireGuard handshakes.
 *
 * Proton runs plain WireGuard, so every variant keeps the handshake itself unchanged (S1–S4 = 0,
 * H1–H4 = 1–4) and only varies what is sent right before it: junk packets (Jc, Jmin, Jmax) and
 * the I1 packet that imitates the start of a QUIC connection. Sizes are drawn once per install,
 * so installs of this app do not all send identical packets that a DPI could learn.
 */
object ObfuscationLadder {

    enum class Variant(val id: String) {
        /** No obfuscation: a plain WireGuard handshake. */
        NONE("none"),
        /** The app's standard profile: three tiny junk packets and the default QUIC I1. */
        STANDARD("standard"),
        /** 4–6 junk packets of 40–120 bytes and another recorded QUIC Initial. */
        MEDIUM("medium"),
        /** 8–12 junk packets of 100–700 bytes and another recorded QUIC Initial. */
        STRONG("strong"),
        /** QUIC Initial with a fresh random connection ID and payload on every handshake. */
        LIVE_QUIC("live_quic");

        companion object {
            fun fromId(id: String?): Variant? = entries.firstOrNull { it.id == id }
        }
    }

    /** Learned results of one variant on one network. Counts decay with time. */
    data class ArmStats(val successes: Double = 0.0, val failures: Double = 0.0, val updatedAt: Long = 0L)

    /** A junk packet must fit a 1280-byte path MTU together with its IP and UDP headers. */
    private const val MAX_JUNK_SIZE = 1200

    /** Results lose half their weight after this long: blocks come and go. */
    const val HALF_LIFE_MS = 14L * 24 * 60 * 60 * 1000

    /** Prior successes of the variant the user's own settings point to, so it is tried first. */
    private const val FAVORITE_PRIOR = 3.0

    fun paramsFor(
        variant: Variant,
        base: AmneziaVpnManager.ObfuscationParams,
        installSeed: Long
    ): AmneziaVpnManager.ObfuscationParams {
        if (variant == Variant.NONE) return AmneziaVpnManager.ObfuscationParams(
            jc = 0, jmin = 0, jmax = 0, s1 = 0, s2 = 0, h1 = "", h2 = "", h3 = "", h4 = "", i1 = ""
        )
        val random = Random(installSeed * 31 + variant.ordinal)
        val samples = QuicInitialSamples.ALL
        // Recorded samples other than the default, which every install of the app shares.
        fun alternativeSample() = samples[1 + random.nextInt(samples.size - 1)]
        val (jc, jmin, jmax, i1) = when (variant) {
            Variant.STANDARD -> Quad(3, 1, 3, SettingsManager.DEFAULT_I1)
            Variant.MEDIUM -> {
                val min = 40 + random.nextInt(21)
                Quad(4 + random.nextInt(3), min, min + 40 + random.nextInt(21), alternativeSample())
            }
            Variant.STRONG -> {
                val min = 100 + random.nextInt(101)
                Quad(8 + random.nextInt(5), min, 400 + random.nextInt(301), alternativeSample())
            }
            Variant.LIVE_QUIC -> {
                val min = 40 + random.nextInt(21)
                Quad(4 + random.nextInt(3), min, min + 40 + random.nextInt(21), liveQuicInitial(random))
            }
            Variant.NONE -> error("handled above")
        }
        return base.copy(
            jc = jc, jmin = jmin, jmax = jmax.coerceAtMost(MAX_JUNK_SIZE),
            s1 = 0, s2 = 0, s3 = 0, s4 = 0,
            h1 = "1", h2 = "2", h3 = "3", h4 = "4",
            i1 = i1, i2 = "", i3 = "", i4 = "", i5 = "",
            headerProtectionKey = "", contentPaddingAddition = ""
        )
    }

    /**
     * Same shape as the recorded samples (1250 bytes): long-header Initial, QUIC v1, an 8-byte
     * destination connection ID, no source ID or token, a 1232-byte payload. The engine fills
     * `<r N>` with new random bytes for every handshake, as a real client's would be.
     */
    internal fun liveQuicInitial(random: Random): String {
        val firstByte = listOf("c0", "c3", "c7", "ce")[random.nextInt(4)]
        return "<b 0x${firstByte}0000000108><r 8><b 0x000044d0><r 1232>"
    }

    /**
     * Picks a variant by Thompson sampling: each candidate's chance of working is drawn from a
     * Beta distribution of its decayed successes and failures, and the highest draw wins. A
     * variant that keeps working is chosen almost always; one that failed lately rarely, but
     * still now and then, so a lifted block is noticed. [favorite] (what the user's own settings
     * would use) starts with a head start on networks the app knows nothing about.
     */
    fun choose(
        stats: Map<Variant, ArmStats>,
        favorite: Variant,
        exclude: Set<Variant>,
        now: Long,
        random: Random = Random.Default
    ): Variant {
        val candidates = Variant.entries.filter { it !in exclude }.ifEmpty { Variant.entries }
        return candidates.maxBy { variant ->
            val arm = decay(stats[variant] ?: ArmStats(), now)
            val prior = if (variant == favorite) FAVORITE_PRIOR else 1.0
            sampleBeta(prior + arm.successes, 1.0 + arm.failures, random)
        }
    }

    fun recordSuccess(arm: ArmStats?, now: Long): ArmStats =
        decay(arm ?: ArmStats(), now).let { it.copy(successes = it.successes + 1, updatedAt = now) }

    fun recordFailure(arm: ArmStats?, now: Long): ArmStats =
        decay(arm ?: ArmStats(), now).let { it.copy(failures = it.failures + 1, updatedAt = now) }

    internal fun decay(arm: ArmStats, now: Long): ArmStats {
        if (arm.updatedAt <= 0L || now <= arm.updatedAt) return arm
        val factor = 0.5.pow((now - arm.updatedAt).toDouble() / HALF_LIFE_MS)
        return arm.copy(successes = arm.successes * factor, failures = arm.failures * factor, updatedAt = now)
    }

    private fun sampleBeta(alpha: Double, beta: Double, random: Random): Double {
        val x = sampleGamma(alpha, random)
        val y = sampleGamma(beta, random)
        return if (x + y == 0.0) 0.5 else x / (x + y)
    }

    /** Marsaglia–Tsang; shapes below 1 are boosted by one and scaled back. */
    private fun sampleGamma(shape: Double, random: Random): Double {
        if (shape < 1.0) {
            return sampleGamma(shape + 1.0, random) * random.nextDouble().coerceAtLeast(1e-12).pow(1.0 / shape)
        }
        val d = shape - 1.0 / 3.0
        val c = 1.0 / sqrt(9.0 * d)
        while (true) {
            var x: Double
            var v: Double
            do {
                x = gaussian(random)
                v = 1.0 + c * x
            } while (v <= 0.0)
            v *= v * v
            val u = random.nextDouble()
            if (u < 1.0 - 0.0331 * x * x * x * x) return d * v
            if (ln(u.coerceAtLeast(1e-300)) < 0.5 * x * x + d * (1.0 - v + ln(v))) return d * v
        }
    }

    private fun gaussian(random: Random): Double {
        val u1 = random.nextDouble().coerceAtLeast(1e-300)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }

    private data class Quad(val jc: Int, val jmin: Int, val jmax: Int, val i1: String)
}
