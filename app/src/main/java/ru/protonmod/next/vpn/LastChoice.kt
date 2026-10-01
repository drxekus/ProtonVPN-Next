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

import ru.protonmod.next.data.network.LogicalServer

/**
 * What the user last asked to connect to: "fastest", a country, a city or one server.
 *
 * "Last used" quick connect, the tile and auto-connect go back to this choice. They used to take
 * the most recent server instead, which also held servers the app had picked by itself, so one
 * automatic pick (South Sudan, under the old load-only ranking) stuck for good.
 */
sealed interface LastChoice {
    data object Fastest : LastChoice
    data class Country(val code: String) : LastChoice
    data class City(val country: String, val city: String) : LastChoice
    data class Server(val id: String) : LastChoice

    fun encode(): String = when (this) {
        Fastest -> FASTEST
        is Country -> "$COUNTRY:$code"
        is City -> "$CITY:$country:$city"
        is Server -> "$SERVER:$id"
    }

    /**
     * The server to connect to now and how far a failover may move from it (null keeps the
     * chosen server, which itself falls back to its country after a few silent attempts).
     */
    fun resolve(servers: Collection<LogicalServer>): Pair<LogicalServer, ServerScope?>? = when (this) {
        Fastest -> ServerSelector.fastest(servers)?.let { it to ServerScope.AnyServer }
        is Country -> ServerSelector.fastest(servers.filter { it.exitCountry == code })
            ?.let { it to ServerScope.Country(code) }
            ?: Fastest.resolve(servers)
        is City -> ServerSelector.fastest(servers.filter { it.exitCountry == country && it.city == city })
            ?.let { it to ServerScope.City(country, city) }
            ?: Country(country).resolve(servers)
        is Server -> {
            val server = servers.find { it.id == id }
            when {
                server != null && ServerSelector.isUsable(server) -> server to null
                server != null -> Country(server.exitCountry).resolve(servers)
                else -> Fastest.resolve(servers)
            }
        }
    }

    companion object {
        private const val FASTEST = "fastest"
        private const val COUNTRY = "country"
        private const val CITY = "city"
        private const val SERVER = "server"

        fun decode(value: String?): LastChoice? {
            if (value.isNullOrBlank()) return null
            val parts = value.split(':', limit = 3)
            return when (parts[0]) {
                FASTEST -> Fastest
                COUNTRY -> parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(::Country)
                CITY -> if (parts.size == 3) City(parts[1], parts[2]) else null
                SERVER -> parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(::Server)
                else -> null
            }
        }

        /** A profile's target as a choice: exact server, else city, else country, else fastest. */
        fun ofTarget(serverId: String?, country: String?, city: String?): LastChoice = when {
            serverId != null -> Server(serverId)
            country != null && city != null -> City(country, city)
            country != null -> Country(country)
            else -> Fastest
        }
    }
}
