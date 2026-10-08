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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds

/**
 * The app's visual language: square corners, thin outlines, uppercase spaced labels, red
 * underlines and chevron stripes, after the red-and-black sports style the user picked.
 */
val ClubShape = RoundedCornerShape(2.dp)

/** Slow in, slow out: the easing of the sliding highlights (cubic-bezier(0.45, 0, 0.55, 1)). */
val ClubEasing = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)

enum class ClubButtonStyle {
    /** Red block, the main action. */
    FILLED,
    /** Outline only; a red underline runs across on press. */
    OUTLINED,
}

/**
 * Square button with an uppercase spaced label. Pressing it shrinks it a little, lights up
 * the outline in red and, for the outlined style, draws a red underline from left to right.
 */
@Composable
fun ClubButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ClubButtonStyle = ClubButtonStyle.FILLED,
    enabled: Boolean = true,
    height: Dp = 56.dp,
    leading: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = ProtonNextTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "clubButtonScale",
    )
    val container by animateColorAsState(
        targetValue = when (style) {
            ClubButtonStyle.FILLED -> if (pressed) colors.brandDarken20 else colors.brandNorm
            ClubButtonStyle.OUTLINED -> if (pressed) colors.brandNorm.copy(alpha = 0.12f) else Color.Transparent
        },
        animationSpec = tween(150),
        label = "clubButtonContainer",
    )
    val outline by animateColorAsState(
        targetValue = when {
            style == ClubButtonStyle.FILLED -> if (pressed) colors.brandLighten20 else colors.brandNorm
            pressed -> colors.brandNorm
            else -> colors.shade40
        },
        animationSpec = tween(150),
        label = "clubButtonOutline",
    )
    val underline by animateFloatAsState(
        targetValue = if (pressed && style == ClubButtonStyle.OUTLINED) 1f else 0f,
        animationSpec = tween(220),
        label = "clubButtonUnderline",
    )
    val content = when (style) {
        ClubButtonStyle.FILLED -> colors.onInteraction
        ClubButtonStyle.OUTLINED -> colors.textNorm
    }
    val underlineColor = colors.brandNorm

    Box(
        modifier = modifier
            .height(height)
            .scale(scale)
            .clip(ClubShape)
            .background(container)
            .border(1.dp, outline, ClubShape)
            .drawWithContent {
                drawContent()
                if (underline > 0f) {
                    val thickness = 3.dp.toPx()
                    drawRect(
                        color = underlineColor,
                        topLeft = Offset(0f, size.height - thickness),
                        size = androidx.compose.ui.geometry.Size(size.width * underline, thickness),
                    )
                }
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            leading?.invoke(this)
            RollingLabel(text = text, color = if (enabled) content else content.copy(alpha = 0.5f), rolled = pressed)
        }
    }
}

/** Uppercase, letter-spaced label used on buttons and tabs. */
@Composable
fun ClubLabel(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = color,
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.6.sp,
        ),
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

/**
 * A [ClubLabel] that rolls over when [rolled] turns true: the text slides up and out while an
 * identical copy slides in from below, and rolls back when it turns false again.
 */
@Composable
fun RollingLabel(text: String, color: Color, rolled: Boolean, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(
        targetValue = if (rolled) 1f else 0f,
        animationSpec = tween(350, easing = ClubEasing),
        label = "rollingLabel",
    )
    Box(modifier = modifier.clipToBounds()) {
        ClubLabel(
            text = text,
            color = color,
            modifier = Modifier.graphicsLayer { translationY = -size.height * progress },
        )
        ClubLabel(
            text = text,
            color = color,
            modifier = Modifier.graphicsLayer { translationY = size.height * (1f - progress) },
        )
    }
}

/**
 * A row of square outlined tabs. One red block marks the selection and slides to the tab
 * that gets picked; while a finger is down on another tab, a light block slides there first,
 * and that tab's label rolls over.
 */
@Composable
fun ClubSegmentedTabs(
    count: Int,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    tab: @Composable RowScope.(index: Int, selected: Boolean, contentColor: Color, rolled: Boolean) -> Unit,
) {
    val colors = ProtonNextTheme.colors
    BoxWithConstraints(modifier = modifier.height(height)) {
        val cell = maxWidth / count
        var pressedIndex by remember { mutableStateOf<Int?>(null) }
        val selectedOffset by animateDpAsState(
            targetValue = cell * selectedIndex.coerceAtLeast(0),
            animationSpec = tween(250, easing = ClubEasing),
            label = "clubTabSelected",
        )
        val hoverIndex = pressedIndex ?: selectedIndex.coerceAtLeast(0)
        val hoverOffset by animateDpAsState(
            targetValue = cell * hoverIndex,
            animationSpec = tween(250, easing = ClubEasing),
            label = "clubTabHover",
        )
        val hoverAlpha by animateFloatAsState(
            targetValue = if (pressedIndex != null && pressedIndex != selectedIndex) 1f else 0f,
            animationSpec = tween(200),
            label = "clubTabHoverAlpha",
        )

        // Outlines of every cell, the light hover block, then the red selection on top.
        Row(Modifier.fillMaxSize()) {
            repeat(count) { Box(Modifier.weight(1f).fillMaxHeight().border(1.dp, colors.shade40)) }
        }
        Box(
            Modifier
                .offset(x = hoverOffset)
                .width(cell)
                .fillMaxHeight()
                .background(colors.textNorm.copy(alpha = 0.10f * hoverAlpha))
                .border(1.dp, colors.textNorm.copy(alpha = hoverAlpha))
        )
        if (selectedIndex >= 0) {
            Box(
                Modifier
                    .offset(x = selectedOffset)
                    .width(cell)
                    .fillMaxHeight()
                    .background(colors.brandNorm)
            )
        }
        Row(Modifier.fillMaxSize()) {
            for (index in 0 until count) {
                val selected = index == selectedIndex
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                LaunchedEffect(pressed) {
                    if (pressed) pressedIndex = index else if (pressedIndex == index) pressedIndex = null
                }
                val contentColor by animateColorAsState(
                    targetValue = if (selected) colors.onInteraction else colors.textNorm,
                    animationSpec = tween(250, easing = ClubEasing),
                    label = "clubTabContent",
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(index) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    tab(index, selected, contentColor, pressed)
                }
            }
        }
    }
}

/** Short red bar under a selected item; grows in from nothing. */
@Composable
fun ClubUnderline(visible: Boolean, modifier: Modifier = Modifier, width: Dp = 24.dp) {
    val colors = ProtonNextTheme.colors
    val animatedWidth by animateDpAsState(
        targetValue = if (visible) width else 0.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "clubUnderline",
    )
    Box(
        modifier = modifier
            .width(animatedWidth)
            .height(3.dp)
            .background(colors.brandNorm)
    )
}

/**
 * Section title in capitals with a short red rule under it, like the page indicators on the
 * club site: one red segment on a thin grey line.
 */
@Composable
fun ClubSectionTitle(text: String, modifier: Modifier = Modifier) {
    val colors = ProtonNextTheme.colors
    androidx.compose.foundation.layout.Column(modifier = modifier) {
        Text(
            text = text.uppercase(),
            color = colors.textNorm,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            ),
        )
        Row(modifier = Modifier.padding(top = 6.dp)) {
            Box(Modifier.width(28.dp).height(2.dp).background(colors.brandNorm))
            Box(Modifier.width(56.dp).height(2.dp).background(colors.shade40))
        }
    }
}

/**
 * Diagonal chevron stripes fading out of a corner, drawn behind the content. Purely
 * decorative; keep [alpha] low so text on top stays readable.
 */
fun Modifier.chevronPattern(
    color: Color,
    alpha: Float = 0.18f,
    stripe: Dp = 14.dp,
    fromRight: Boolean = true,
): Modifier = drawBehind {
    val stripePx = stripe.toPx()
    val step = stripePx * 2.6f
    val brush = Brush.radialGradient(
        colors = listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.35f), Color.Transparent),
        center = Offset(if (fromRight) size.width else 0f, 0f),
        radius = maxOf(size.width, size.height) * 0.9f,
    )
    // Chevrons pointing up, their tips on a line running from the corner into the card.
    val tipX = if (fromRight) size.width * 0.78f else size.width * 0.22f
    val halfWidth = size.width * 0.55f
    val rise = halfWidth * 0.75f
    var y = -rise
    while (y < size.height + rise) {
        val path = Path().apply {
            moveTo(tipX - halfWidth, y + rise)
            lineTo(tipX, y)
            lineTo(tipX + halfWidth, y + rise)
        }
        drawPath(path, brush, style = Stroke(width = stripePx, join = StrokeJoin.Miter))
        y += step
    }
}

/**
 * Press feedback for list rows: a red bar slides in on the left edge and the row tints a
 * little while the finger is down.
 */
@Composable
fun Modifier.clubPressHighlight(interaction: MutableInteractionSource): Modifier {
    val colors = ProtonNextTheme.colors
    val pressed by interaction.collectIsPressedAsState()
    val progress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(160),
        label = "clubRowPress",
    )
    val bar = colors.brandNorm
    return drawBehind {
        if (progress > 0f) {
            drawRect(bar.copy(alpha = 0.10f * progress))
            val barWidth = 3.dp.toPx()
            val barHeight = size.height * progress
            drawRect(
                color = bar,
                topLeft = Offset(0f, (size.height - barHeight) / 2f),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
            )
        }
    }
}

/**
 * Header texture after the club's red banners: a red field darkening towards the edges, fine
 * grain, double chevron lines pointing right, fading into the content below. Drawn in code,
 * so it is our own artwork and scales to any width.
 */
fun Modifier.clubHeaderTexture(red: Color, deep: Color): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithCache {
        val w = size.width
        val h = size.height
        // Grain: fixed pseudo-random specks so the texture does not shimmer between frames.
        val random = java.util.Random(0x5EED)
        val specks = (w * h / (5.dp.toPx() * 5.dp.toPx())).toInt().coerceAtMost(9000)
        val darkSpecks = ArrayList<Offset>(specks / 2)
        val lightSpecks = ArrayList<Offset>(specks / 2)
        repeat(specks) { i ->
            val point = Offset(random.nextFloat() * w, random.nextFloat() * h)
            if (i % 2 == 0) darkSpecks += point else lightSpecks += point
        }
        val speck = 1.4.dp.toPx()
        val scanline = 1.dp.toPx()
        val scanStep = 3.dp.toPx()
        val line = 1.5.dp.toPx()
        val step = h * 0.55f
        val chevrons = buildList {
            var x = -h
            while (x < w + h) {
                add(Path().apply {
                    moveTo(x, -h * 0.1f)
                    lineTo(x + h * 0.45f, h * 0.5f)
                    lineTo(x, h * 1.1f)
                })
                x += step
            }
        }
        val base = Brush.verticalGradient(listOf(red, deep))
        val vignette = Brush.radialGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
            center = Offset(w * 0.5f, h * 0.35f),
            radius = maxOf(w, h) * 0.75f,
        )
        val fade = Brush.verticalGradient(
            0f to Color.Black,
            0.45f to Color.Black,
            0.75f to Color.Black.copy(alpha = 0.4f),
            1f to Color.Transparent,
        )
        onDrawBehind {
            drawRect(base)
            chevrons.forEach { path ->
                drawPath(path, Color.Black.copy(alpha = 0.35f), style = Stroke(width = line))
                translate(left = line * 3f) {
                    drawPath(path, red.copy(alpha = 0.55f), style = Stroke(width = line))
                }
            }
            drawPoints(darkSpecks, PointMode.Points, Color.Black.copy(alpha = 0.22f), strokeWidth = speck)
            drawPoints(lightSpecks, PointMode.Points, Color.White.copy(alpha = 0.07f), strokeWidth = speck)
            // Scanlines of an old tube monitor.
            var scan = 0f
            while (scan < h) {
                drawLine(Color.Black.copy(alpha = 0.10f), Offset(0f, scan), Offset(w, scan), strokeWidth = scanline)
                scan += scanStep
            }
            drawRect(vignette)
            drawRect(fade, blendMode = BlendMode.DstIn)
        }
    }
