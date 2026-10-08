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

package ru.protonmod.next.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.protonmod.next.R
import ru.protonmod.next.netshield.NetShieldLevel
import ru.protonmod.next.netshield.NetShieldStats
import ru.protonmod.next.ui.theme.ProtonNextTheme
import ru.protonmod.next.ui.theme.liquidGlass
import java.util.Locale
import ru.protonmod.next.ui.theme.ClubShape

@Composable
fun NetShieldStatsCard(
    stats: NetShieldStats,
    level: NetShieldLevel,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = ProtonNextTheme.colors
    val shape = RoundedCornerShape(if (compact) 18.dp else 24.dp)
    val accentColors = netShieldAccentColors(level)
    val levelLabel = when (level) {
        NetShieldLevel.DISABLED -> R.string.netshield_level_off
        NetShieldLevel.MALWARE -> R.string.netshield_level_malware
        NetShieldLevel.ADS_TRACKERS -> R.string.netshield_level_extended
        NetShieldLevel.ADS_TRACKERS_ADULT -> R.string.netshield_level_adult
    }

    Box(
        modifier = modifier
            .then(if (compact) Modifier.widthIn(max = 360.dp) else Modifier.fillMaxWidth())
            .liquidGlass(
                shape = shape,
                alpha = if (compact) 0.58f else 0.48f,
                borderAlpha = 0.14f,
            )
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            accentColors.first.copy(alpha = if (compact) 0.18f else 0.14f),
                            accentColors.second.copy(alpha = if (compact) 0.09f else 0.07f),
                            Color.Transparent,
                        )
                    )
                )
        )

        Column(
            modifier = Modifier.padding(if (compact) 14.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(if (compact) 34.dp else 40.dp)
                        .clip(CircleShape)
                        .background(accentColors.first.copy(alpha = 0.16f))
                        .border(1.dp, accentColors.first.copy(alpha = 0.20f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_proton_netshield),
                        contentDescription = null,
                        tint = accentColors.first,
                        modifier = Modifier.size(if (compact) 19.dp else 22.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.netshield_title).uppercase(),
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                        color = colors.textNorm,
                    )
                    Text(
                        text = stringResource(levelLabel),
                        fontSize = if (compact) 10.sp else 11.sp,
                        lineHeight = if (compact) 12.sp else 14.sp,
                        color = colors.textWeak,
                        maxLines = if (compact) 1 else 2,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(ClubShape)
                        .background(accentColors.first.copy(alpha = 0.12f))
                        .border(1.dp, accentColors.first.copy(alpha = 0.18f), ClubShape)
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(accentColors.first)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
            ) {
                if (level == NetShieldLevel.MALWARE) {
                    Stat(
                        value = stats.malwareBlocked.toString(),
                        label = stringResource(R.string.netshield_malware_blocked),
                        accent = accentColors.first,
                        compact = compact,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Stat(
                        value = stats.adsBlocked.toString(),
                        label = stringResource(R.string.netshield_ads_blocked),
                        accent = accentColors.first,
                        compact = compact,
                        modifier = Modifier.weight(1f),
                    )
                    Stat(
                        value = stats.trackersBlocked.toString(),
                        label = stringResource(R.string.netshield_trackers_blocked),
                        accent = accentColors.second,
                        compact = compact,
                        modifier = Modifier.weight(1f),
                    )
                    Stat(
                        value = formatBytes(stats.savedBytes),
                        label = stringResource(R.string.netshield_data_saved),
                        accent = colors.notificationSuccess,
                        compact = compact,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun Stat(
    value: String,
    label: String,
    accent: Color,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = ProtonNextTheme.colors
    val shape = RoundedCornerShape(if (compact) 11.dp else 14.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.backgroundNorm.copy(alpha = 0.24f))
            .border(1.dp, colors.textNorm.copy(alpha = 0.06f), shape)
            .padding(horizontal = if (compact) 5.dp else 8.dp, vertical = if (compact) 9.dp else 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            fontSize = if (compact) 15.sp else 18.sp,
            lineHeight = if (compact) 18.sp else 22.sp,
            fontWeight = FontWeight.ExtraBold,
            color = accent,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            textAlign = TextAlign.Center,
            fontSize = if (compact) 9.sp else 10.sp,
            lineHeight = if (compact) 11.sp else 12.sp,
            fontWeight = FontWeight.Medium,
            color = colors.textWeak,
            maxLines = 2,
        )
    }
}

@Composable
private fun netShieldAccentColors(level: NetShieldLevel): Pair<Color, Color> {
    val colors = ProtonNextTheme.colors
    return when (level) {
        NetShieldLevel.DISABLED -> colors.textWeak to colors.textHint
        NetShieldLevel.MALWARE -> colors.notificationSuccess to colors.brandNorm
        NetShieldLevel.ADS_TRACKERS -> colors.brandNorm to colors.brandLighten20
        NetShieldLevel.ADS_TRACKERS_ADULT -> colors.notificationWarning to colors.notificationError
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1_000.0)
    else -> "$bytes B"
}
