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

package ru.protonmod.next.ui.screens.countries

import android.app.Activity
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import ru.protonmod.next.R
import ru.protonmod.next.data.local.ServerLoadDisplayMode
import ru.protonmod.next.data.network.LogicalServer
import ru.protonmod.next.ui.components.ExpressiveCircularProgressIndicator
import ru.protonmod.next.ui.components.FlagIcon
import ru.protonmod.next.ui.components.LoadIndicator
import ru.protonmod.next.ui.icons.ProtonIcons
import ru.protonmod.next.ui.theme.ProtonNextTheme
import ru.protonmod.next.ui.utils.CountryUtils
import ru.protonmod.next.ui.utils.isTablet
import ru.protonmod.next.utils.ProtonLogger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountriesScreen(
    onNavigateToHome: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CountriesViewModel = hiltViewModel()
) {
    val colors = ProtonNextTheme.colors
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val connectedServer by viewModel.connectedServer.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isTablet = isTablet()

    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            ProtonLogger.d("CountriesScreen", "VPN permission granted")
            pendingAction?.invoke()
            pendingAction = null
        } else {
            pendingAction = null
        }
    }

    val errorAppOpsMsg = stringResource(R.string.error_system_appops)

    val checkVpnAndConnect: (() -> Unit) -> Unit = { connectAction ->
        try {
            val intent = VpnService.prepare(context)
            if (intent != null) {
                pendingAction = connectAction
                vpnPermissionLauncher.launch(intent)
            } else {
                connectAction()
            }
        } catch (_: SecurityException) {
            Toast.makeText(context, errorAppOpsMsg, Toast.LENGTH_LONG).show()
            connectAction()
        }
    }

    val loadDisplayMode = (uiState as? CountriesUiState.Success)?.loadDisplayMode ?: ServerLoadDisplayMode.ALL

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = colors.backgroundNorm,
        bottomBar = {}
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .statusBarsPadding()
        ) {
            CountriesSearchField(
                query = searchQuery,
                onQueryChange = viewModel::setSearchQuery,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
            )

            Box(modifier = Modifier.weight(1f)) {
                if (searchQuery.isBlank()) {
                    AnimatedContent(
                        targetState = uiState,
                        contentKey = { state -> state::class },
                        label = "countries_state_root",
                        modifier = Modifier.fillMaxSize()
                    ) { state ->
                        when (state) {
                            is CountriesUiState.Loading -> {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    ExpressiveCircularProgressIndicator(color = colors.brandNorm)
                                }
                            }
                            is CountriesUiState.Error -> {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(state.message, color = colors.notificationError)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Button(
                                            onClick = { viewModel.loadServers() },
                                            colors = ButtonDefaults.buttonColors(containerColor = colors.interactionNorm)
                                        ) {
                                            Text(stringResource(R.string.btn_retry), color = colors.textInverted)
                                        }
                                    }
                                }
                            }
                            is CountriesUiState.Success -> {
                                val countries = remember(state.countries) { state.countries.toImmutableList() }

                                CountriesListContent(
                                    countries = countries,
                                    connectedServer = connectedServer,
                                    onCountryClick = { country ->
                                        checkVpnAndConnect {
                                            viewModel.selectCountry(country.code)
                                            onNavigateToHome()
                                        }
                                    },
                                    onCountryMore = { country ->
                                        viewModel.expandCitiesForCountry(country.code)
                                    },
                                    isTablet = isTablet,
                                    loadDisplayMode = state.loadDisplayMode,
                                    connectionMode = state.connectionMode,
                                    onModeSelect = viewModel::setConnectionMode,
                                )
                            }
                        }
                    }
                } else {
                    SearchResultsList(
                        results = searchResults,
                        connectedServer = connectedServer,
                        loadDisplayMode = loadDisplayMode,
                        onCountryClick = { result ->
                            checkVpnAndConnect {
                                viewModel.selectCountry(result.code)
                                onNavigateToHome()
                            }
                        },
                        onCityClick = { result ->
                            checkVpnAndConnect {
                                viewModel.selectCity(result.countryCode, result.cityName)
                                onNavigateToHome()
                            }
                        },
                        onServerClick = { result ->
                            checkVpnAndConnect {
                                viewModel.selectServer(result.server)
                                onNavigateToHome()
                            }
                        },
                    )
                }
            }
        }

        val successState = uiState as? CountriesUiState.Success
        if (searchQuery.isBlank() && successState?.bottomSheetContent != null) {
            CountriesBottomSheet(
                onDismiss = { viewModel.backToCountries() },
                content = successState.bottomSheetContent,
                connectedServer = connectedServer,
                onCityClick = { city ->
                    checkVpnAndConnect {
                        viewModel.selectCity(city.name)
                        onNavigateToHome()
                    }
                },
                onCityMore = { city ->
                    viewModel.expandServersForCity(city.name)
                },
                onServerClick = { server ->
                    checkVpnAndConnect {
                        viewModel.selectServer(server)
                        onNavigateToHome()
                    }
                },
                onBack = { viewModel.backToCities() },
                loadDisplayMode = successState.loadDisplayMode
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountriesSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ProtonNextTheme.colors
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        placeholder = { Text(stringResource(R.string.countries_search_hint)) },
        leadingIcon = {
            Icon(
                imageVector = ProtonIcons.Magnifier,
                contentDescription = stringResource(R.string.desc_search),
                tint = colors.iconWeak
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = ProtonIcons.Cross,
                        contentDescription = stringResource(R.string.desc_clear_search),
                        tint = colors.iconWeak
                    )
                }
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.brandNorm,
            unfocusedBorderColor = colors.separatorNorm,
            focusedTextColor = colors.textNorm,
            unfocusedTextColor = colors.textNorm,
            cursorColor = colors.brandNorm,
            focusedContainerColor = colors.backgroundSecondary.copy(alpha = 0.4f),
            unfocusedContainerColor = colors.backgroundSecondary.copy(alpha = 0.4f),
            focusedPlaceholderColor = colors.textWeak,
            unfocusedPlaceholderColor = colors.textWeak,
        ),
    )
}

@Composable
fun CountriesListContent(
    countries: ImmutableList<CountryDisplayItem>,
    connectedServer: LogicalServer?,
    onCountryClick: (CountryDisplayItem) -> Unit,
    onCountryMore: (CountryDisplayItem) -> Unit,
    modifier: Modifier = Modifier,
    isTablet: Boolean = false,
    loadDisplayMode: ServerLoadDisplayMode = ServerLoadDisplayMode.ALL,
    connectionMode: CountryConnectionMode = CountryConnectionMode.STANDARD,
    onModeSelect: (CountryConnectionMode) -> Unit = {},
) {
    Box(modifier = modifier) {
        if (isTablet) {
            val windowInfo = LocalWindowInfo.current
            val density = LocalDensity.current
            val screenWidthDp = with(density) { windowInfo.containerSize.width.toDp() }.value
            val columns = (screenWidthDp / 300).toInt().coerceAtLeast(2)

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 140.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, contentType = "Header") {
                    ConnectionModeSelector(connectionMode, onModeSelect)
                }

                items(countries, key = { it.code }, contentType = { "Country" }) { country ->
                    CountryCard(
                        country = country,
                        isConnected = connectedServer?.exitCountry == country.code,
                        onClick = { onCountryClick(country) },
                        onMoreClick = { onCountryMore(country) },
                        displayMode = loadDisplayMode,
                        showTorBadge = connectionMode == CountryConnectionMode.TOR,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 140.dp),
            ) {
                item(contentType = "Header") {
                    ConnectionModeSelector(connectionMode, onModeSelect)
                }

                items(countries, key = { it.code }, contentType = { "Country" }) { country ->
                    CountryCard(
                        country = country,
                        isConnected = connectedServer?.exitCountry == country.code,
                        onClick = { onCountryClick(country) },
                        onMoreClick = { onCountryMore(country) },
                        displayMode = loadDisplayMode,
                        showTorBadge = connectionMode == CountryConnectionMode.TOR,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionModeSelector(
    selected: CountryConnectionMode,
    onSelect: (CountryConnectionMode) -> Unit,
) {
    val colors = ProtonNextTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CountryConnectionMode.entries.forEach { mode ->
            val title = when (mode) {
                CountryConnectionMode.STANDARD -> stringResource(R.string.connection_mode_standard)
                CountryConnectionMode.TOR -> stringResource(R.string.connection_mode_tor)
            }
            Button(
                onClick = { onSelect(mode) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected == mode) colors.brandNorm else colors.backgroundSecondary,
                    contentColor = if (selected == mode) colors.textInverted else colors.textNorm,
                ),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                if (mode == CountryConnectionMode.TOR) {
                    Icon(
                        ImageVector.vectorResource(R.drawable.ic_tor_project),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(title, maxLines = 1)
            }
        }
    }
}

/** A flag or placeholder with an optional Tor / connected badge, shared by the rows. */
@Composable
private fun CountryFlagBadge(
    flagResId: Int,
    isConnected: Boolean,
    showTorBadge: Boolean,
) {
    val colors = ProtonNextTheme.colors
    Box(contentAlignment = Alignment.BottomEnd) {
        if (flagResId != 0) {
            FlagIcon(countryFlag = flagResId, size = DpSize(36.dp, 24.dp))
        } else {
            Box(
                modifier = Modifier
                    .size(36.dp, 24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.backgroundSecondary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = ProtonIcons.Earth,
                    contentDescription = stringResource(R.string.desc_country),
                    tint = colors.iconNorm,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (showTorBadge) {
            Box(
                modifier = Modifier
                    .offset(x = 5.dp, y = 5.dp)
                    .size(16.dp)
                    .background(colors.backgroundNorm, CircleShape)
                    .padding(2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_tor_project),
                    contentDescription = stringResource(R.string.connection_mode_tor),
                    tint = colors.brandNorm,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else if (isConnected) {
            Box(
                modifier = Modifier
                    .offset(x = 4.dp, y = 4.dp)
                    .size(10.dp)
                    .background(colors.notificationSuccess, CircleShape)
                    .padding(2.dp)
                    .background(colors.backgroundNorm, CircleShape)
                    .padding(1.dp)
                    .background(colors.notificationSuccess, CircleShape)
            )
        }
    }
}

/** Shared row shell: leading content, a title/subtitle, the load percent and an optional trailing slot. */
@Composable
private fun ServerRow(
    title: String,
    isConnected: Boolean,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    load: Int,
    displayMode: ServerLoadDisplayMode,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = ProtonNextTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isConnected) colors.brandNorm.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = colors.textNorm,
                    maxLines = 1,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textWeak,
                        maxLines = 1,
                    )
                }
            }
            LoadIndicator(load = load, displayMode = displayMode)
            if (trailing != null) {
                Spacer(modifier = Modifier.width(8.dp))
                trailing()
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp),
            color = colors.separatorNorm.copy(alpha = 0.4f),
        )
    }
}

@Composable
private fun DrillIcon(onClick: () -> Unit) {
    val colors = ProtonNextTheme.colors
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(
            imageVector = ProtonIcons.ChevronRight,
            contentDescription = stringResource(R.string.desc_open_servers),
            tint = colors.iconWeak,
        )
    }
}

@Composable
fun CountryCard(
    country: CountryDisplayItem,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    isConnected: Boolean = false,
    displayMode: ServerLoadDisplayMode = ServerLoadDisplayMode.ALL,
    showTorBadge: Boolean = false,
) {
    val context = LocalContext.current
    val flagResId = remember(country.code) { CountryUtils.getFlagResource(context, country.code) }
    val localizedName = remember(country.code) { CountryUtils.getCountryName(context, country.code) }

    ServerRow(
        title = localizedName,
        isConnected = isConnected,
        onClick = onClick,
        leading = { CountryFlagBadge(flagResId, isConnected, showTorBadge) },
        load = country.averageLoad,
        displayMode = displayMode,
        modifier = modifier,
        trailing = { DrillIcon(onClick = onMoreClick) },
    )
}

@Composable
fun CityCard(
    city: CityDisplayItem,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    isConnected: Boolean = false,
    displayMode: ServerLoadDisplayMode = ServerLoadDisplayMode.ALL
) {
    val colors = ProtonNextTheme.colors
    ServerRow(
        title = city.localizedName,
        isConnected = isConnected,
        onClick = onClick,
        leading = {
            Box(
                modifier = Modifier
                    .size(36.dp, 24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.backgroundSecondary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = ProtonIcons.Buildings,
                    contentDescription = null,
                    tint = colors.iconNorm,
                    modifier = Modifier.size(20.dp)
                )
            }
        },
        load = city.averageLoad,
        displayMode = displayMode,
        modifier = modifier,
        trailing = { DrillIcon(onClick = onMoreClick) },
    )
}

@Composable
fun ServerItemCard(
    server: LogicalServer,
    isConnected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    displayMode: ServerLoadDisplayMode = ServerLoadDisplayMode.ALL
) {
    val colors = ProtonNextTheme.colors
    ServerRow(
        title = server.name,
        isConnected = isConnected,
        onClick = onClick,
        leading = {
            // Keep the title aligned with the flag column of country/city rows.
            Spacer(modifier = Modifier.size(36.dp, 24.dp))
        },
        load = server.averageLoad,
        displayMode = displayMode,
        modifier = modifier,
        trailing = if (isConnected) {
            {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(colors.notificationSuccess, CircleShape)
                )
            }
        } else null,
    )
}

@Composable
private fun SearchResultsList(
    results: List<SearchResult>,
    connectedServer: LogicalServer?,
    loadDisplayMode: ServerLoadDisplayMode,
    onCountryClick: (SearchResult.Country) -> Unit,
    onCityClick: (SearchResult.City) -> Unit,
    onServerClick: (SearchResult.Server) -> Unit,
) {
    val colors = ProtonNextTheme.colors
    val context = LocalContext.current

    if (results.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.search_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textWeak,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 140.dp),
    ) {
        items(
            results,
            key = { result ->
                when (result) {
                    is SearchResult.Country -> "c:${result.code}"
                    is SearchResult.City -> "t:${result.countryCode}:${result.cityName}"
                    is SearchResult.Server -> "s:${result.server.id}"
                }
            },
            contentType = { it::class },
        ) { result ->
            when (result) {
                is SearchResult.Country -> {
                    val flagResId = remember(result.code) { CountryUtils.getFlagResource(context, result.code) }
                    ServerRow(
                        title = result.localizedName,
                        subtitle = stringResource(R.string.desc_country),
                        isConnected = connectedServer?.exitCountry == result.code,
                        onClick = { onCountryClick(result) },
                        leading = { CountryFlagBadge(flagResId, isConnected = false, showTorBadge = false) },
                        load = result.averageLoad,
                        displayMode = loadDisplayMode,
                    )
                }
                is SearchResult.City -> {
                    val countryName = remember(result.countryCode) {
                        CountryUtils.getCountryName(context, result.countryCode)
                    }
                    ServerRow(
                        title = result.localizedName,
                        subtitle = countryName,
                        isConnected = connectedServer?.exitCountry == result.countryCode &&
                            connectedServer?.city == result.cityName,
                        onClick = { onCityClick(result) },
                        leading = {
                            Box(
                                modifier = Modifier
                                    .size(36.dp, 24.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colors.backgroundSecondary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = ProtonIcons.Buildings,
                                    contentDescription = null,
                                    tint = colors.iconNorm,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        load = result.averageLoad,
                        displayMode = loadDisplayMode,
                    )
                }
                is SearchResult.Server -> {
                    val flagResId = remember(result.server.exitCountry) {
                        CountryUtils.getFlagResource(context, result.server.exitCountry)
                    }
                    val countryName = remember(result.server.exitCountry) {
                        CountryUtils.getCountryName(context, result.server.exitCountry)
                    }
                    ServerRow(
                        title = result.server.name,
                        subtitle = countryName,
                        isConnected = connectedServer?.id == result.server.id,
                        onClick = { onServerClick(result) },
                        leading = { CountryFlagBadge(flagResId, isConnected = false, showTorBadge = false) },
                        load = result.server.averageLoad,
                        displayMode = loadDisplayMode,
                    )
                }
            }
        }
    }
}
