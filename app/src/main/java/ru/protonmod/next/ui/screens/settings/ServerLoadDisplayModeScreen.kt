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

package ru.protonmod.next.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.protonmod.next.R
import ru.protonmod.next.data.local.ServerLoadDisplayMode
import ru.protonmod.next.ui.components.NavigationHeader
import ru.protonmod.next.ui.components.ServerCard
import ru.protonmod.next.ui.icons.ProtonIcons
import ru.protonmod.next.ui.theme.ProtonNextTheme
import ru.protonmod.next.ui.utils.isTablet
import ru.protonmod.next.ui.theme.ClubShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerLoadDisplayModeScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = ProtonNextTheme.colors
    val isTablet = isTablet()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.backgroundNorm,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
                horizontalAlignment = if (isTablet) Alignment.CenterHorizontally else Alignment.Start
            ) {
                NavigationHeader(
                    title = stringResource(R.string.settings_load_display_mode),
                    onBack = onBack
                )

                val contentModifier = if (isTablet) Modifier.widthIn(max = 600.dp) else Modifier.fillMaxWidth()


                ServerLoadDisplayMode.entries.forEach { mode ->
                    LoadModePreviewCard(
                        mode = mode,
                        isSelected = uiState.serverLoadDisplayMode == mode,
                        onClick = { viewModel.setServerLoadDisplayMode(mode) },
                        modifier = contentModifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun LoadModePreviewCard(
    mode: ServerLoadDisplayMode,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ProtonNextTheme.colors
    val modeName = when (mode) {
        ServerLoadDisplayMode.ALL -> stringResource(R.string.load_mode_all)
        ServerLoadDisplayMode.LINE -> stringResource(R.string.load_mode_line)
        ServerLoadDisplayMode.PERCENT -> stringResource(R.string.load_mode_percent)
        ServerLoadDisplayMode.HIDDEN -> stringResource(R.string.load_mode_hidden)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = if (isSelected) 3.dp else 0.dp,
                    color = if (isSelected) colors.brandNorm else Color.Transparent,
                    shape = ClubShape
                )
        ) {
            val mockServer = remember {
                ru.protonmod.next.data.network.LogicalServer(
                    id = "preview",
                    name = "US-FREE #1",
                    city = "New York",
                    exitCountry = "US",
                    entryCountry = "US",
                    tier = 0,
                    features = 0,
                    averageLoad = 45,
                    servers = emptyList()
                )
            }
            ServerCard(
                server = mockServer,
                isConnected = false,
                isConnecting = false,
                displayMode = mode,
                onClick = null // Making it non-clickable inside the preview
            )

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .padding(12.dp)
                        .align(Alignment.TopEnd)
                        .background(colors.brandNorm, CircleShape)
                        .padding(4.dp)
                ) {
                    Icon(
                        imageVector = ProtonIcons.CheckmarkCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = modeName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) colors.brandNorm else colors.textNorm
        )
    }
}
