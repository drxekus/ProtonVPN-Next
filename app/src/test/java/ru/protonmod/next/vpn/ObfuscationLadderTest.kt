package ru.protonmod.next.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.protonmod.next.data.local.SettingsManager
import ru.protonmod.next.vpn.ObfuscationLadder.ArmStats
import ru.protonmod.next.vpn.ObfuscationLadder.Variant
import kotlin.random.Random

class ObfuscationLadderTest {

    private val base = AmneziaVpnManager.ObfuscationParams(
        jc = 3, jmin = 1, jmax = 3, s1 = 0, s2 = 0, h1 = "1", h2 = "2", h3 = "3", h4 = "4",
        i1 = SettingsManager.DEFAULT_I1, rekeyAfterTime = "120"
    )

    @Test
    fun `every variant stays compatible with plain WireGuard servers`() {
        Variant.entries.filter { it != Variant.NONE }.forEach { variant ->
            val params = ObfuscationLadder.paramsFor(variant, base, installSeed = 42)
            assertEquals(listOf(0, 0, 0, 0), listOf(params.s1, params.s2, params.s3, params.s4))
            assertEquals(listOf("1", "2", "3", "4"), listOf(params.h1, params.h2, params.h3, params.h4))
            assertTrue("$variant junk must fit the MTU", params.jmax in params.jmin..1200)
            assertEquals("timers are kept", "120", params.rekeyAfterTime)
        }
    }

    @Test
    fun `no obfuscation sends no junk`() {
        val params = ObfuscationLadder.paramsFor(Variant.NONE, base, installSeed = 42)
        assertEquals(0, params.jc)
        assertEquals("", params.i1)
    }

    @Test
    fun `installs draw different junk sizes`() {
        val sizes = (1L..20L).map { seed ->
            ObfuscationLadder.paramsFor(Variant.STRONG, base, seed).let { it.jc to it.jmax }
        }.toSet()
        assertTrue("expected variety, got $sizes", sizes.size > 5)
    }

    @Test
    fun `live QUIC initial has the shape of the recorded samples`() {
        val spec = ObfuscationLadder.liveQuicInitial(Random(1))
        val bytes = Regex("<b 0x([0-9a-f]+)>").findAll(spec).sumOf { it.groupValues[1].length / 2 } +
            Regex("<r ([0-9]+)>").findAll(spec).sumOf { it.groupValues[1].toInt() }
        assertEquals(QuicInitialSamples.ALL.first().length.let { (it - "<b 0x>".length) / 2 }, bytes)
        assertEquals(SettingsManager.DEFAULT_I1, QuicInitialSamples.ALL.first())
    }

    @Test
    fun `a variant that keeps failing is rarely chosen and a working one usually is`() {
        val now = 1_000_000L
        val stats = mapOf(
            Variant.STANDARD to ArmStats(successes = 0.0, failures = 6.0, updatedAt = now),
            Variant.MEDIUM to ArmStats(successes = 5.0, failures = 0.0, updatedAt = now),
        )
        val random = Random(7)
        val picks = (1..500).map { ObfuscationLadder.choose(stats, Variant.STANDARD, emptySet(), now, random) }
        assertTrue(picks.count { it == Variant.MEDIUM } > 250)
        assertTrue(picks.count { it == Variant.STANDARD } < 25)
    }

    @Test
    fun `excluded variants are never chosen until all are excluded`() {
        val random = Random(3)
        repeat(200) {
            val pick = ObfuscationLadder.choose(emptyMap(), Variant.STANDARD, setOf(Variant.STANDARD, Variant.NONE), 0L, random)
            assertNotEquals(Variant.STANDARD, pick)
            assertNotEquals(Variant.NONE, pick)
        }
        assertTrue(ObfuscationLadder.choose(emptyMap(), Variant.NONE, Variant.entries.toSet(), 0L, random) in Variant.entries)
    }

    @Test
    fun `old results fade`() {
        val arm = ArmStats(successes = 8.0, failures = 4.0, updatedAt = 1L)
        val faded = ObfuscationLadder.decay(arm, 1L + ObfuscationLadder.HALF_LIFE_MS)
        assertEquals(4.0, faded.successes, 1e-9)
        assertEquals(2.0, faded.failures, 1e-9)
    }
}
