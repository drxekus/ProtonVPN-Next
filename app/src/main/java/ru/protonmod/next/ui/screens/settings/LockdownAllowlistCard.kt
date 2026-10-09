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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.protonmod.next.R
import ru.protonmod.next.ui.theme.ProtonNextTheme
import ru.protonmod.next.ui.theme.liquidGlass
import ru.protonmod.next.utils.system.LockdownAllowlist
import ru.protonmod.next.utils.system.LockdownAllowlist.Access
import javax.inject.Inject

@HiltViewModel
class LockdownAllowlistViewModel @Inject constructor(
    private val allowlist: LockdownAllowlist
) : ViewModel() {

    enum class Outcome { WRITTEN, CLEARED, FAILED }

    data class UiState(
        val access: Access = Access.UNAVAILABLE,
        /** The list Android holds now; null while unknown (no Shizuku access). */
        val stored: List<String>? = null,
        val busy: Boolean = false,
        val outcome: Outcome? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val access = allowlist.access()
            val stored = if (access == Access.READY) allowlist.read() else null
            _state.update { it.copy(access = access, stored = stored) }
        }
    }

    fun requestPermission() {
        allowlist.requestPermission { refresh() }
    }

    fun write(packages: Collection<String>) {
        _state.update { it.copy(busy = true, outcome = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = allowlist.write(packages)
            val outcome = when {
                !ok -> Outcome.FAILED
                packages.isEmpty() -> Outcome.CLEARED
                else -> Outcome.WRITTEN
            }
            _state.update { it.copy(busy = false, outcome = outcome) }
            refresh()
        }
    }
}

/**
 * Lets the excluded apps reach the network while Always-on VPN blocks connections without VPN,
 * by writing them into Android's lockdown exception list through Shizuku.
 */
@Composable
fun LockdownAllowlistCard(
    excludedApps: Set<String>,
    modifier: Modifier = Modifier,
    viewModel: LockdownAllowlistViewModel = hiltViewModel()
) {
    val colors = ProtonNextTheme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }

    val wanted = excludedApps.sorted()
    val inSync = state.stored?.sorted() == wanted
    val status = when {
        state.outcome == LockdownAllowlistViewModel.Outcome.FAILED -> stringResource(R.string.lockdown_allowlist_failed)
        state.outcome == LockdownAllowlistViewModel.Outcome.WRITTEN -> stringResource(R.string.lockdown_allowlist_written)
        state.outcome == LockdownAllowlistViewModel.Outcome.CLEARED -> stringResource(R.string.lockdown_allowlist_cleared)
        state.access == Access.UNAVAILABLE -> stringResource(R.string.lockdown_allowlist_no_shizuku)
        state.access == Access.NEEDS_PERMISSION -> stringResource(R.string.lockdown_allowlist_needs_permission)
        inSync -> stringResource(R.string.lockdown_allowlist_in_sync, wanted.size)
        else -> stringResource(R.string.lockdown_allowlist_out_of_sync, state.stored?.size ?: 0, wanted.size)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlass(shape = RoundedCornerShape(16.dp), alpha = 0.4f, shadowElevation = 0.dp)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.lockdown_allowlist_title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = colors.textNorm,
                modifier = Modifier.weight(1f)
            )
            SettingInfoButton(
                title = stringResource(R.string.lockdown_allowlist_title),
                info = stringResource(R.string.lockdown_allowlist_info)
            )
        }
        Text(
            text = stringResource(R.string.lockdown_allowlist_desc),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textWeak,
            modifier = Modifier.padding(end = 12.dp)
        )
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = colors.textNorm,
            modifier = Modifier.padding(end = 12.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val buttonColors = ButtonDefaults.buttonColors(containerColor = colors.brandNorm)
            when (state.access) {
                Access.READY -> {
                    Button(
                        onClick = { viewModel.write(wanted) },
                        enabled = !state.busy && !inSync && wanted.isNotEmpty(),
                        colors = buttonColors
                    ) { Text(stringResource(R.string.lockdown_allowlist_write)) }
                    if (!state.stored.isNullOrEmpty()) {
                        TextButton(onClick = { viewModel.write(emptyList()) }, enabled = !state.busy) {
                            Text(stringResource(R.string.lockdown_allowlist_clear), color = colors.textWeak)
                        }
                    }
                }
                Access.NEEDS_PERMISSION -> Button(onClick = viewModel::requestPermission, colors = buttonColors) {
                    Text(stringResource(R.string.lockdown_allowlist_grant))
                }
                Access.UNAVAILABLE -> TextButton(onClick = viewModel::refresh) {
                    Text(stringResource(R.string.lockdown_allowlist_retry), color = colors.brandNorm)
                }
            }
        }
    }
}
