package ru.protonmod.next.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.protonmod.next.data.network.LogicalServer
import ru.protonmod.next.data.network.PhysicalServer

class LastChoiceTest {

    private fun server(
        id: String,
        country: String,
        city: String = "City",
        load: Int = 30,
        score: Double = 0.0,
        hostCountry: String? = null,
        online: Boolean = true
    ) = LogicalServer(
        id = id, name = id, tier = 0, features = 0, entryCountry = country, exitCountry = country,
        city = city, averageLoad = load, score = score, hostCountry = hostCountry,
        servers = listOf(PhysicalServer(id = "$id-p", domain = "$id.example", status = if (online) 1 else 0, wgPublicKey = "pk"))
    )

    @Test
    fun `choices survive encoding`() {
        listOf(
            LastChoice.Fastest,
            LastChoice.Country("DE"),
            LastChoice.City("US", "New York: Manhattan"),
            LastChoice.Server("abc_123=="),
        ).forEach { assertEquals(it, LastChoice.decode(it.encode())) }
        assertNull(LastChoice.decode(null))
        assertNull(LastChoice.decode("garbage"))
    }

    @Test
    fun `fastest prefers a real location over a lighter Smart Routing one`() {
        val virtual = server("ss-7", "SS", load = 5, score = 1.0, hostCountry = "DE")
        val real = server("fi-1", "FI", load = 40, score = 1.5)

        assertEquals("fi-1", LastChoice.Fastest.resolve(listOf(virtual, real))?.first?.id)
        assertEquals("ss-7", LastChoice.Country("SS").resolve(listOf(virtual, real))?.first?.id)
    }

    @Test
    fun `an offline chosen server falls back to its country`() {
        val chosen = server("de-1", "DE", online = false)
        val sameCountry = server("de-2", "DE")
        val elsewhere = server("fi-1", "FI", load = 1)

        val (target, scope) = LastChoice.Server("de-1").resolve(listOf(chosen, sameCountry, elsewhere))!!
        assertEquals("de-2", target.id)
        assertEquals(ServerScope.Country("DE"), scope)
    }

    @Test
    fun `a city without servers falls back to its country`() {
        val berlin = server("de-1", "DE", city = "Berlin")
        val (target, scope) = LastChoice.City("DE", "Hamburg").resolve(listOf(berlin))!!
        assertEquals("de-1", target.id)
        assertEquals(ServerScope.Country("DE"), scope)
    }

    @Test
    fun `profiles map to the narrowest target they name`() {
        assertEquals(LastChoice.Server("x"), LastChoice.ofTarget("x", "DE", "Berlin"))
        assertEquals(LastChoice.City("DE", "Berlin"), LastChoice.ofTarget(null, "DE", "Berlin"))
        assertEquals(LastChoice.Country("DE"), LastChoice.ofTarget(null, "DE", null))
        assertEquals(LastChoice.Fastest, LastChoice.ofTarget(null, null, null))
    }
}
