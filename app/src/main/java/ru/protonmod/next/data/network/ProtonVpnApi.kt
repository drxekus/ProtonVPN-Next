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

package ru.protonmod.next.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

@Serializable
data class CityTranslationsResponse(
    @SerialName("Language")
    val languageCode: String,
    @SerialName("Cities")
    val cities: Map<String, Map<String, String?>>, // CountryCode -> (CityEnglishName -> LocalizedName)
    @SerialName("States")
    val states: Map<String, Map<String, String?>>,
)

@Serializable
data class ConnectingDomainResponse(
    @SerialName("Code") val code: Int,
    @SerialName("Domain") val domain: String? = null
)

interface ProtonVpnApi {

    /**
     * Optimized server list retrieval.
     * @param lastModified RFC 1123 date string for If-Modified-Since header.
     * @param protocols Comma-separated list of protocols (e.g., "wireguard").
     */
    @GET("vpn/v2/logicals")
    suspend fun getLogicalServers(
        @Header("Authorization") authorization: String,
        @Header("x-pm-uid") sessionId: String,
        @Header("If-Modified-Since") lastModified: String? = null,
        @Header("x-pm-locale") locale: String? = null,
        @Query("WithEntriesForProtocols") protocols: String? = "wireguard",
        @Query("WithState") withState: Boolean = true,
        /** The user's network (last IPv4 octet zeroed), so scores are computed for them. */
        @Header("x-pm-netzone") netzone: String? = null
    ): Response<LogicalServersResponse>

    @GET("vpn/v1/loads")
    suspend fun getLoads(
        @Header("Authorization") authorization: String,
        @Header("x-pm-uid") sessionId: String,
        @Header("x-pm-netzone") netzone: String? = null
    ): Response<ResponseBody>

    @GET("vpn/v2")
    suspend fun getVpnInfo(
        @Header("Authorization") authorization: String,
        @Header("x-pm-uid") sessionId: String
    ): Response<ResponseBody>

    /**
     * Get user's current location and IP as seen by the API.
     */
    @GET("vpn/v1/location")
    suspend fun getUserLocation(
        @Header("Authorization") authorization: String,
        @Header("x-pm-uid") sessionId: String
    ): Response<ResponseBody>

    /**
     * Get localized names for cities and states.
     */
    @GET("vpn/v1/cities/names")
    suspend fun getServerCities(
        @Header("Authorization") authorization: String,
        @Header("x-pm-uid") sessionId: String,
        @Header("x-pm-locale") languageTag: String,
    ): CityTranslationsResponse

    @GET("vpn/v1/servers/{serverId}")
    suspend fun getServerDomain(
        @Header("Authorization") authorization: String,
        @Header("x-pm-uid") sessionId: String,
        @retrofit2.http.Path(value = "serverId", encoded = true) serverId: String
    ): ConnectingDomainResponse

    /**
     * Registers the WireGuard public key and obtains the internal VPN IP.
     * Based on Linux client: uses /vpn/v1/certificate
     */
    @POST("vpn/v1/certificate")
    suspend fun registerVpnKey(
        @Header("Authorization") authorization: String,
        @Header("x-pm-uid") sessionId: String,
        @Body request: CreateCertificateRequest
    ): CreateCertificateResponse
}
