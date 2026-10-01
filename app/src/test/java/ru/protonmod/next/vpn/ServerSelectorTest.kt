package ru.protonmod.next.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.protonmod.next.data.network.LogicalServer
import ru.protonmod.next.data.network.PhysicalServer

class ServerSelectorTest {

    private fun physical(id: String, status: Int = 1, key: String? = "pk", load: Int = 10) =
        PhysicalServer(id = id, domain = "$id.example", status = status, wgPublicKey = key, load = load)

    private fun logical(
        id: String,
        load: Int,
        servers: List<PhysicalServer> = listOf(physical("$id-p")),
        country: String = "DE",
        city: String = "Berlin",
        features: Int = 0,
        score: Double = 0.0,
    ) = LogicalServer(
        id = id, name = id, tier = 0, features = features, entryCountry = country,
        exitCountry = country, city = city, servers = servers, averageLoad = load, score = score
    )

    @Test
    fun `a server under maintenance is never the fastest even with load 0`() {
        val maintenance = logical("maintenance", load = 0, servers = listOf(physical("m", status = 0)))
        val busy = logical("busy", load = 70)

        assertEquals("busy", ServerSelector.fastest(listOf(maintenance, busy))?.id)
    }

    @Test
    fun `an unreported load ranks after every reported one`() {
        val unreported = logical("unreported", load = 0)
        val loaded = logical("loaded", load = 90)

        assertEquals("loaded", ServerSelector.fastest(listOf(unreported, loaded))?.id)
    }

    @Test
    fun `a nearby server beats an idle one far away`() {
        val farAndIdle = logical("mz", load = 5, country = "MZ", score = 9.4)
        val nearAndBusier = logical("fi", load = 45, country = "FI", score = 1.2)

        assertEquals("fi", ServerSelector.fastest(listOf(farAndIdle, nearAndBusier))?.id)
    }

    @Test
    fun `servers without a score follow scored ones by load`() {
        val unscoredIdle = logical("unscored", load = 3)
        val scored = logical("scored", load = 60, score = 4.0)
        val unscoredBusy = logical("busy", load = 80)

        assertEquals(
            listOf("scored", "unscored", "busy"),
            ServerSelector.rank(listOf(unscoredBusy, unscoredIdle, scored)).map { it.id }
        )
    }

    @Test
    fun `physical servers without a WireGuard key cannot be picked`() {
        val server = logical(
            "s", load = 10,
            servers = listOf(physical("nokey", key = null, load = 1), physical("ok", load = 50))
        )

        assertEquals("ok", ServerSelector.pickPhysical(server)?.id)
        assertFalse(ServerSelector.isUsable(logical("x", 10, listOf(physical("y", key = "")))))
    }

    @Test
    fun `secure core and tor are used only when nothing else is available`() {
        val secureCore = logical("sc", load = 5, features = 1)
        val tor = logical("tor", load = 5, features = 2)
        val regular = logical("regular", load = 60)

        assertEquals("regular", ServerSelector.fastest(listOf(secureCore, tor, regular))?.id)
        assertEquals("sc", ServerSelector.fastest(listOf(secureCore))?.id)
    }

    @Test
    fun `excluded servers are skipped`() {
        val a = logical("a", load = 10)
        val b = logical("b", load = 20)

        assertEquals("b", ServerSelector.fastest(listOf(a, b), exclude = setOf("a"))?.id)
        assertNull(ServerSelector.fastest(listOf(a), exclude = setOf("a")))
    }

    @Test
    fun `profile targets resolve from exact server to city to country to anything`() {
        val berlin = logical("berlin", load = 30, city = "Berlin")
        val munich = logical("munich", load = 10, city = "Munich")
        val paris = logical("paris", load = 5, country = "FR", city = "Paris")
        val all = listOf(berlin, munich, paris)

        assertEquals("berlin", ServerSelector.forTarget(all, "berlin", null, null)?.id)
        assertEquals("berlin", ServerSelector.forTarget(all, null, "DE", "Berlin")?.id)
        assertEquals("munich", ServerSelector.forTarget(all, null, "DE", null)?.id)
        assertEquals("paris", ServerSelector.forTarget(all, null, null, null)?.id)
    }

    @Test
    fun `failover scope follows the profile target`() {
        assertNull(ServerSelector.scopeForTarget("id", "DE", "Berlin"))
        assertEquals(ServerScope.City("DE", "Berlin"), ServerSelector.scopeForTarget(null, "DE", "Berlin"))
        assertEquals(ServerScope.Country("DE"), ServerSelector.scopeForTarget(null, "DE", null))
        assertEquals(ServerScope.AnyServer, ServerSelector.scopeForTarget(null, null, null))
        assertTrue(ServerScope.Country("DE").accepts(logical("x", 1, country = "DE")))
        assertFalse(ServerScope.City("DE", "Berlin").accepts(logical("x", 1, city = "Munich")))
    }
}
