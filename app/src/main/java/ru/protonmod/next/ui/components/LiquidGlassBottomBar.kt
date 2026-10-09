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

package ru.protonmod.next.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import ru.protonmod.next.ui.icons.ProtonIcons
import ru.protonmod.next.ui.nav.MainTarget
import ru.protonmod.next.ui.theme.ProtonNextTheme
import ru.protonmod.next.ui.theme.liquidGlass
import ru.protonmod.next.ui.utils.isTablet
import ru.protonmod.next.ui.theme.PillShape
import ru.protonmod.next.ui.theme.AppEaseOut
import ru.protonmod.next.ui.theme.pressScale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color

@Composable
fun LiquidGlassBottomBar(
    selectedTarget: MainTarget?,
    navigateTo: (MainTarget) -> Unit,
    modifier: Modifier = Modifier,
    showCountries: Boolean = true,
    showGateways: Boolean = true,
    notificationDots: ImmutableSet<MainTarget> = persistentSetOf()
) {
    val isTablet = isTablet()

    val targets = mutableListOf(MainTarget.Home)
    if (showCountries) targets.add(MainTarget.Countries)
    targets.add(MainTarget.Profiles)
    targets.add(MainTarget.Settings)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = if (isTablet) 400.dp else 600.dp)
                .padding(horizontal = 24.dp)
                // Darker than the cards it floats over, with a soft shadow, like the ChatGPT
                // composer bar.
                .shadow(16.dp, PillShape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(PillShape)
                .background(if (ProtonNextTheme.colors.isDark) ProtonNextTheme.colors.shade15 else ProtonNextTheme.colors.shade0)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    // Swallows taps that land between the items so they do not reach
                    // the content scrolled underneath the bar.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                targets.forEach { target ->
                    NavigationItem(
                        target = target,
                        isSelected = target == selectedTarget,
                        hasNotification = notificationDots.contains(target),
                        onNavigate = { navigateTo(target) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * One bottom-bar item. Switching tabs happens many times a day, so the change is only a quick
 * color fade and a soft circle behind the selected icon; a press scales the item slightly.
 */
@Composable
private fun NavigationItem(
    target: MainTarget,
    isSelected: Boolean,
    hasNotification: Boolean,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ProtonNextTheme.colors
    val iconColor by animateColorAsState(
        targetValue = if (isSelected) colors.textNorm else colors.iconWeak,
        animationSpec = tween(150),
        label = "navIconColor"
    )
    val circleAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = tween(150, easing = AppEaseOut),
        label = "navCircle"
    )
    val interaction = remember { MutableInteractionSource() }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onNavigate
            )
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .pressScale(interaction)
                .size(48.dp)
                .background(colors.shade20.copy(alpha = circleAlpha), CircleShape)
        ) {
            Icon(
                imageVector = getProtonIconForTarget(target),
                contentDescription = target.name,
                tint = iconColor,
                modifier = Modifier.size(24.dp)
            )
            if (hasNotification) {
                Box(
                    modifier = Modifier
                        .padding(10.dp)
                        .size(8.dp)
                        .background(colors.notificationError, CircleShape)
                        .align(Alignment.TopEnd)
                )
            }
        }
    }
}

@Composable
private fun getProtonIconForTarget(target: MainTarget): ImageVector {
    return when (target) {
        MainTarget.Home -> ProtonIcons.House
        MainTarget.Profiles -> ProtonIcons.WindowTerminal
        MainTarget.Countries -> ProtonIcons.Globe
        MainTarget.Settings -> ProtonIcons.CogWheel
    }
}
