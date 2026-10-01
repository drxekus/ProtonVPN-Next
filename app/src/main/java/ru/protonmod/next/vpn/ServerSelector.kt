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
import ru.protonmod.next.data.network.PhysicalServer

/**
 * The single place that decides which servers can be connected to and which one is "fastest".
 *
 * Proton's load endpoint only reports servers that are serving traffic, so a server that is
 * disabled or under maintenance keeps a load of 0. Ranking by raw load therefore used to pick
 * exactly those servers as the "fastest": the engine started, nothing answered, and the UI still
 * claimed a connection. Every server choice now goes through the rules below.
 */
/**
 * Which other logical servers a connection may move to when its server does not answer.
 * An explicitly chosen server has no scope: only its other physical servers and ports are tried.
 */
sealed interface ServerScope {
    fun accepts(server: LogicalServer): Boolean

    data object AnyServer : ServerScope {
        override fun accepts(server: LogicalServer) = true
    }

    data class Country(val code: String) : ServerScope {
        override fun accepts(server: LogicalServer) = server.exitCountry == code
    }

    data class City(val country: String, val city: String) : ServerScope {
        override fun accepts(server: LogicalServer) = server.exitCountry == country && server.city == city
    }
}

object ServerSelector {
    /** Proton logical-server feature bits that make a server unsuitable for a generic pick. */
    private const val FEATURE_SECURE_CORE = 1
    private const val FEATURE_TOR = 2

    /** A physical server can take a WireGuard connection right now. */
    fun isOnline(server: PhysicalServer): Boolean =
        server.status == 1 && !server.wgPublicKey.isNullOrBlank()

    /** Proton's Smart Routing: the exit country is not where the server physically is. */
    fun isVirtualLocation(server: LogicalServer): Boolean =
        server.hostCountry != null && server.hostCountry != server.exitCountry

    /** A logical server has at least one physical server that can take a connection. */
    fun isUsable(server: LogicalServer): Boolean = server.servers.any(::isOnline)

    /** Online physical servers of [server], least loaded first. */
    fun onlinePhysicalServers(server: LogicalServer): List<PhysicalServer> =
        server.servers.filter(::isOnline).sortedBy { it.load }

    /** The physical server to connect to, or null when the whole logical server is offline. */
    fun pickPhysical(server: LogicalServer): PhysicalServer? = onlinePhysicalServers(server).firstOrNull()

    /**
     * Orders usable servers from best to worst. A load of 0 means Proton did not report one,
     * which almost always means the server is not serving, so those go last instead of first.
     * Among the rest, Proton's score decides (lower is better): it weighs load against the
     * distance from the user, so a half-empty server on another continent no longer wins over
     * a nearby one. Servers without a score follow, by load.
     */
    fun rank(servers: Collection<LogicalServer>): List<LogicalServer> =
        servers.filter(::isUsable)
            .sortedWith(
                compareBy<LogicalServer>(
                    { it.averageLoad <= 0 },
                    { it.score <= 0.0 },
                    { if (it.score > 0.0) it.score else 0.0 },
                    { it.averageLoad }
                )
            )

    /**
     * The best server for a generic "fastest" connection. Secure Core and Tor servers are only
     * used when the user asks for them explicitly, as in the official client. Smart Routing
     * locations come after real ones: they are often lightly loaded and scored as near (they are
     * hosted elsewhere), but their exit IP is in a far country, which is not what "fastest" means
     * to a user. Within a country choice every server is in that country, so nothing changes.
     */
    fun fastest(servers: Collection<LogicalServer>, exclude: Set<String> = emptySet()): LogicalServer? {
        val candidates = servers.filter { it.id !in exclude }
        val regular = candidates.filter { it.features and (FEATURE_SECURE_CORE or FEATURE_TOR) == 0 }
        return rank(regular.filterNot(::isVirtualLocation)).firstOrNull()
            ?: rank(regular).firstOrNull()
            ?: rank(candidates).firstOrNull()
    }

    /** The top of the generic ranking, for logs: why "fastest" picked what it picked. */
    fun describeTop(servers: Collection<LogicalServer>, limit: Int = 5): String {
        val regular = servers.filter { it.features and (FEATURE_SECURE_CORE or FEATURE_TOR) == 0 }
        val ranked = rank(regular.filterNot(::isVirtualLocation)) + rank(regular.filter(::isVirtualLocation))
        return ranked.take(limit).joinToString { server ->
            val host = server.hostCountry?.takeIf { isVirtualLocation(server) }?.let { "@$it" }.orEmpty()
            "${server.name}$host score=${"%.2f".format(server.score)} load=${server.averageLoad}"
        } + " (scored: ${servers.count { it.score > 0.0 }}/${servers.size}, virtual: ${servers.count(::isVirtualLocation)})"
    }

    /** Resolves a profile target: exact server, then city, then country, then anything. */
    fun forTarget(
        servers: Collection<LogicalServer>,
        serverId: String?,
        country: String?,
        city: String?,
    ): LogicalServer? {
        if (serverId != null) servers.find { it.id == serverId }?.let { return it }
        if (country != null && city != null) {
            fastest(servers.filter { it.exitCountry == country && it.city == city })?.let { return it }
        }
        if (country != null) fastest(servers.filter { it.exitCountry == country })?.let { return it }
        return fastest(servers)
    }

    /** How far a profile connection may move when its server does not answer. */
    fun scopeForTarget(serverId: String?, country: String?, city: String?): ServerScope? = when {
        serverId != null -> null
        country != null && city != null -> ServerScope.City(country, city)
        country != null -> ServerScope.Country(country)
        else -> ServerScope.AnyServer
    }
}
