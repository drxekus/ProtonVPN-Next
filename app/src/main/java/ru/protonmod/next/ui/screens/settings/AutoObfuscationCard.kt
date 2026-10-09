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

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.protonmod.next.R
import ru.protonmod.next.data.local.SettingsManager
import ru.protonmod.next.ui.theme.liquidGlass
import ru.protonmod.next.vpn.ObfuscationAdvisor
import ru.protonmod.next.vpn.ObfuscationLadder
import javax.inject.Inject

@HiltViewModel
class AutoObfuscationViewModel @Inject constructor(
    private val settingsManager: SettingsManager,
    private val advisor: ObfuscationAdvisor
) : ViewModel() {
    val enabled: StateFlow<Boolean> = settingsManager.autoObfuscationEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _current = MutableStateFlow<ObfuscationLadder.Variant?>(null)
    /** What last worked on the network the phone is on now. */
    val current: StateFlow<ObfuscationLadder.Variant?> = _current.asStateFlow()

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) { _current.value = advisor.currentNetworkVariant() }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsManager.setAutoObfuscationEnabled(enabled) }
    }
}

@Composable
fun AutoObfuscationCard(
    modifier: Modifier = Modifier,
    viewModel: AutoObfuscationViewModel = hiltViewModel()
) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val current by viewModel.current.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }

    val subtitle = when {
        !enabled -> stringResource(R.string.auto_obfuscation_off)
        current != null -> stringResource(R.string.auto_obfuscation_current, stringResource(current!!.labelRes()))
        else -> stringResource(R.string.auto_obfuscation_on)
    }
    SettingToggleRow(
        title = stringResource(R.string.auto_obfuscation_title),
        subtitle = subtitle,
        checked = enabled,
        onCheckedChange = viewModel::setEnabled,
        info = stringResource(R.string.auto_obfuscation_info),
        modifier = modifier
            .fillMaxWidth()
            .liquidGlass(shape = RoundedCornerShape(16.dp), alpha = 0.4f, shadowElevation = 0.dp)
    )
}

private fun ObfuscationLadder.Variant.labelRes(): Int = when (this) {
    ObfuscationLadder.Variant.NONE -> R.string.obf_variant_none
    ObfuscationLadder.Variant.STANDARD -> R.string.obf_variant_standard
    ObfuscationLadder.Variant.MEDIUM -> R.string.obf_variant_medium
    ObfuscationLadder.Variant.STRONG -> R.string.obf_variant_strong
    ObfuscationLadder.Variant.LIVE_QUIC -> R.string.obf_variant_live_quic
}
