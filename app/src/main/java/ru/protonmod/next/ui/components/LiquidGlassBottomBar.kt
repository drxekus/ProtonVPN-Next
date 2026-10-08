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
import ru.protonmod.next.ui.theme.ClubShape
import ru.protonmod.next.ui.theme.ClubEasing
import androidx.compose.foundation.layout.BoxWithConstraints

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

    val glassShape = ClubShape

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
                .liquidGlass(
                    shape = glassShape,
                    // Nearly opaque: the square bar showed the list scrolling under it.
                    alpha = 0.97f,
                    shadowElevation = 15.dp
                )
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(70.dp)
            ) {
                val colors = ProtonNextTheme.colors
                val cell = maxWidth / targets.size
                val selectedIndex = targets.indexOf(selectedTarget)
                var pressedIndex by remember { mutableStateOf<Int?>(null) }
                // One light block and one red bar slide between the items (cubic ease in and
                // out); a finger on another item pulls the block there before the switch.
                val blockIndex = pressedIndex ?: selectedIndex
                val blockOffset by animateDpAsState(
                    targetValue = cell * blockIndex.coerceAtLeast(0),
                    animationSpec = tween(250, easing = ClubEasing),
                    label = "navBlock"
                )
                val barOffset by animateDpAsState(
                    targetValue = cell * selectedIndex.coerceAtLeast(0),
                    animationSpec = tween(250, easing = ClubEasing),
                    label = "navBar"
                )
                if (blockIndex >= 0) {
                    Box(
                        modifier = Modifier
                            .offset(x = blockOffset)
                            .width(cell)
                            .fillMaxHeight()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .background(colors.textNorm.copy(alpha = 0.08f), ClubShape)
                    )
                }
                if (selectedIndex >= 0) {
                    Box(
                        modifier = Modifier
                            .offset(x = barOffset + (cell - 24.dp) / 2)
                            .align(Alignment.BottomStart)
                            .padding(bottom = 10.dp)
                            .width(24.dp)
                            .height(3.dp)
                            .background(colors.brandNorm)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxSize()
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
                    targets.forEachIndexed { index, target ->
                        NavigationItem(
                            target = target,
                            isSelected = target == selectedTarget,
                            hasNotification = notificationDots.contains(target),
                            onNavigate = { navigateTo(target) },
                            onPressedChange = { pressed ->
                                if (pressed) pressedIndex = index else if (pressedIndex == index) pressedIndex = null
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationItem(
    target: MainTarget,
    isSelected: Boolean,
    hasNotification: Boolean,
    onNavigate: () -> Unit,
    onPressedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ProtonNextTheme.colors
    val activeColor = colors.navigationActive
    val inactiveColor = colors.iconWeak

    val iconColor by animateColorAsState(
        targetValue = if (isSelected) activeColor else inactiveColor,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "iconColor"
    )

    val iconVector = getProtonIconForTarget(target)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(pressed) { onPressedChange(pressed) }
    val iconScale by animateFloatAsState(
        targetValue = if (pressed) 0.85f else if (isSelected) 1.1f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "navIconScale"
    )

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
        Box(modifier = Modifier.scale(iconScale)) {
            Icon(
                imageVector = iconVector,
                contentDescription = target.name,
                tint = iconColor,
                modifier = Modifier.size(24.dp)
            )

            if (hasNotification) {
                Box(
                    modifier = Modifier
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
