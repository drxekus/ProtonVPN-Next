/*
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

package ru.protonmod.next.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The app's look after the ChatGPT Android app: black background, graphite surfaces without
 * outlines, pill-shaped controls, one blue accent, grey section titles in normal case, and quick,
 * quiet motion (a slight press scale, strong ease-out, nothing over 300 ms).
 */

/** Cards and grouped lists. */
val AppShape = RoundedCornerShape(24.dp)

/** Buttons, tabs, search fields, the bottom bar. */
val PillShape = RoundedCornerShape(percent = 50)

/** Strong ease-out for answers to the user: presses, things appearing. */
val AppEaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

/** Strong ease-in-out for things moving across the screen, like the selected tab. */
val AppEaseInOut = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)

private const val PRESS_MS = 160

enum class AppButtonStyle {
    /** Blue pill, the main action. */
    PRIMARY,
    /** Graphite pill for everything else. */
    SECONDARY,
}

/** Scales a pressable element to 0.97 while it is held, so the press is felt at once. */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(PRESS_MS, easing = AppEaseOut),
        label = "pressScale",
    )
    return scale(scale)
}

/** Pill button: blue for the main action, graphite otherwise. */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: AppButtonStyle = AppButtonStyle.PRIMARY,
    enabled: Boolean = true,
    height: Dp = 56.dp,
    leading: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = ProtonNextTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val container = when (style) {
        AppButtonStyle.PRIMARY -> colors.brandNorm
        // One step lighter than the card it usually sits on.
        AppButtonStyle.SECONDARY -> colors.shade40
    }
    val content = when (style) {
        AppButtonStyle.PRIMARY -> colors.onInteraction
        AppButtonStyle.SECONDARY -> colors.textNorm
    }
    Row(
        modifier = modifier
            .height(height)
            .pressScale(interaction)
            .clip(PillShape)
            .background(if (enabled) container else container.copy(alpha = 0.5f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        leading?.invoke(this)
        AppLabel(text = text, color = if (enabled) content else content.copy(alpha = 0.6f))
    }
}

/** Label of buttons and tabs. */
@Composable
fun AppLabel(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

/**
 * Pill-shaped segmented switch: a lighter pill marks the selection and glides to the tab that
 * gets picked.
 */
@Composable
fun AppSegmentedTabs(
    count: Int,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    tab: @Composable RowScope.(index: Int, selected: Boolean, contentColor: Color) -> Unit,
) {
    val colors = ProtonNextTheme.colors
    val selectedColor = if (colors.isDark) colors.shade10 else colors.shade0
    BoxWithConstraints(
        modifier = modifier
            .height(height)
            .clip(PillShape)
            .background(colors.shade20)
            .padding(4.dp)
    ) {
        val cell = maxWidth / count
        val offset by animateDpAsState(
            targetValue = cell * selectedIndex.coerceAtLeast(0),
            animationSpec = tween(220, easing = AppEaseInOut),
            label = "segmentedSelection",
        )
        if (selectedIndex >= 0) {
            Box(
                Modifier
                    .offset(x = offset)
                    .width(cell)
                    .fillMaxHeight()
                    .background(selectedColor, PillShape)
            )
        }
        Row(Modifier.fillMaxSize()) {
            for (index in 0 until count) {
                val selected = index == selectedIndex
                val contentColor by animateColorAsState(
                    targetValue = if (selected) colors.textNorm else colors.textWeak,
                    animationSpec = tween(150),
                    label = "segmentedContent",
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(index) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    tab(index, selected, contentColor)
                }
            }
        }
    }
}

/** Section title: grey, normal case, like "Account" in the ChatGPT settings. */
@Composable
fun AppSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = ProtonNextTheme.colors.textWeak,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Normal),
        modifier = modifier,
    )
}

/** Press feedback for list rows: a faint lighter wash while the finger is down. */
@Composable
fun Modifier.pressHighlight(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(120, easing = AppEaseOut),
        label = "pressHighlight",
    )
    val wash = ProtonNextTheme.colors.textNorm
    return drawBehind { if (alpha > 0f) drawRect(wash.copy(alpha = 0.07f * alpha)) }
}
