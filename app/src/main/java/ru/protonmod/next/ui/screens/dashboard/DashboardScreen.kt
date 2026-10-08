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

import android.app.Activity
import android.net.VpnService
import android.text.BidiFormatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import ru.protonmod.next.R
import ru.protonmod.next.data.network.LogicalServer
import ru.protonmod.next.ui.components.ExpressiveCircularProgressIndicator
import ru.protonmod.next.ui.components.FlagIcon
import ru.protonmod.next.ui.components.ServerCard
import ru.protonmod.next.ui.components.SmoothOutlinedTextField
import ru.protonmod.next.ui.icons.ProtonIcons
import ru.protonmod.next.ui.theme.ProtonColors
import ru.protonmod.next.ui.theme.ProtonNextTheme
import ru.protonmod.next.ui.theme.liquidGlass
import ru.protonmod.next.ui.utils.CountryUtils
import ru.protonmod.next.ui.utils.isTablet
import ru.protonmod.next.utils.ProtonLogger
import ru.protonmod.next.utils.system.SystemUtils
import ru.protonmod.next.vpn.AmneziaVpnManager
import ru.protonmod.next.ui.theme.ClubShape
import ru.protonmod.next.ui.theme.ClubButton
import ru.protonmod.next.ui.theme.ClubButtonStyle
import ru.protonmod.next.ui.theme.chevronPattern
import ru.protonmod.next.ui.theme.clubHeaderTexture
import androidx.compose.foundation.border
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.launch
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset

// --- Extensions for UI Effects matching Original Proton ---

@Composable
fun Modifier.vpnStatusOverlayBackground(
    isConnected: Boolean,
    isConnecting: Boolean,
    colors: ProtonColors
): Modifier {
    // The header is the club-style red texture in every state; the state itself is shown on
    // the dark plate of [VpnStatusTop], where green and red stay readable. A connected tunnel
    // only adds a faint green wash on top, enough to notice at a glance.
    val connectedTint by animateFloatAsState(
        targetValue = if (isConnected) 1f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "headerConnectedTint"
    )
    val green = colors.notificationSuccess
    return this
        .clubHeaderTexture(red = colors.brandNorm, deep = colors.brandDarken40)
        .drawBehind {
            if (connectedTint > 0f) {
                drawRect(
                    Brush.verticalGradient(
                        0f to green.copy(alpha = 0.22f * connectedTint),
                        0.6f to green.copy(alpha = 0.10f * connectedTint),
                        1f to Color.Transparent,
                    )
                )
            }
        }
}

/** The deeper green of the radar, darker than the "connected" text. */
private val RadarGreen = Color(0xFF00A651)
/** Bright phosphor green of the sweep's leading edge and the radar captions. */
private val RadarPhosphor = Color(0xFF39FF7A)
/** Alarm red of the "link lost" alert. */
private val AlertRed = Color(0xFFFF2A2A)

/** Fixed radar contacts as (angle in degrees, distance as a share of the reach). */
private val RadarBlips = listOf(
    25f to 0.32f, 70f to 0.55f, 118f to 0.22f, 160f to 0.7f, 205f to 0.45f,
    250f to 0.62f, 300f to 0.28f, 335f to 0.8f,
)

private const val RADAR_MS = 3000
private const val CAPTION_MS = 4200

@Composable
fun VpnStatusTop(
    isConnected: Boolean,
    isConnecting: Boolean,
    vpnState: AmneziaVpnManager.VpnState,
    modifier: Modifier = Modifier,
    isRecovering: Boolean = false
) {
    val colors = ProtonNextTheme.colors
    val showsConnected = vpnState == AmneziaVpnManager.VpnState.CONNECTED && !isRecovering
    val radar = remember { Animatable(1f) }
    val caption = remember { Animatable(1f) }
    val flicker = remember { Animatable(1f) }
    var wasConnected by rememberSaveable { mutableStateOf(showsConnected) }
    // Red Alert style "unit ready": when the tunnel comes up a radar grid lights up around the
    // status plate, a phosphor beam sweeps twice and pings contacts, rings spread out, the label
    // flickers like an old tube and a caption is typed out underneath.
    LaunchedEffect(showsConnected) {
        if (showsConnected && !wasConnected) {
            launch {
                radar.snapTo(0f)
                radar.animateTo(1f, tween(durationMillis = RADAR_MS, easing = LinearEasing))
            }
            launch {
                caption.snapTo(0f)
                caption.animateTo(1f, tween(durationMillis = CAPTION_MS, easing = LinearEasing))
            }
            flicker.snapTo(0f)
            flicker.animateTo(1f, keyframes {
                durationMillis = 420
                0.9f at 50
                0.15f at 110
                1f at 170
                0.35f at 240
                1f at 320
            })
        }
        wasConnected = showsConnected
    }
    // While the link is being restored the plate sounds a red alert: a pulsing glow and a
    // blinking caption, until a handshake answers again.
    val alarm = rememberInfiniteTransition(label = "redAlert")
    val alarmPulse by alarm.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "redAlertPulse"
    )

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .drawBehind {
                    if (isRecovering) drawRedAlert(alarmPulse)
                    val p = radar.value
                    if (p < 1f) drawRadar(p)
                }
                .background(colors.shade0.copy(alpha = 0.72f), ClubShape)
                .border(1.dp, if (isRecovering) AlertRed.copy(alpha = 0.4f + 0.6f * alarmPulse) else colors.shade40, ClubShape)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                // A tunnel that lost its path is not "Connected": show the progress spinner.
                targetState = if (isRecovering && vpnState == AmneziaVpnManager.VpnState.CONNECTED) {
                    AmneziaVpnManager.VpnState.VERIFYING
                } else {
                    vpnState
                },
                label = "VpnStatusTopTransition"
            ) { state ->
                when (state) {
                    AmneziaVpnManager.VpnState.CONNECTED -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.graphicsLayer { alpha = flicker.value }
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_proton_lock_filled),
                                tint = colors.notificationSuccess,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.status_connected),
                                style = MaterialTheme.typography.titleLarge,
                                color = colors.notificationSuccess,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    AmneziaVpnManager.VpnState.CONNECTING, AmneziaVpnManager.VpnState.VERIFYING -> {
                        ExpressiveCircularProgressIndicator(
                            color = if (isRecovering) AlertRed else colors.textNorm,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    else -> {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_proton_lock_open_filled_2),
                            contentDescription = null,
                            tint = colors.notificationError,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }

        // Terminal captions under the plate, in the style of a command console.
        val captionText = when {
            isRecovering -> stringResource(R.string.radar_link_lost)
            caption.value < 1f -> stringResource(R.string.radar_link_established)
            else -> null
        }
        if (captionText != null) {
            val shown = if (isRecovering) {
                captionText
            } else {
                // Typed out in the first 30 %, held, then faded in the last 20 %.
                val typed = (caption.value / 0.3f).coerceAtMost(1f)
                captionText.take((captionText.length * typed).toInt())
            }
            val captionAlpha = when {
                isRecovering -> if (alarmPulse > 0.5f) 1f else 0.25f
                caption.value > 0.8f -> (1f - caption.value) / 0.2f
                else -> 1f
            }
            val cursor = if (!isRecovering && caption.value < 0.8f && (caption.value * 20).toInt() % 2 == 0) "_" else " "
            Text(
                text = "> " + shown.uppercase() + if (isRecovering) "" else cursor,
                color = (if (isRecovering) AlertRed else RadarPhosphor).copy(alpha = captionAlpha),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 2.sp,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .background(colors.shade0.copy(alpha = 0.55f * captionAlpha))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

/** Radar effect around the plate for progress [p] from 0 to 1. */
private fun DrawScope.drawRadar(p: Float) {
    val c = center
    val reach = 460.dp.toPx()
    // The grid lights up, holds and dims away.
    val grid = when {
        p < 0.1f -> p / 0.1f
        p > 0.75f -> (1f - p) / 0.25f
        else -> 1f
    }
    val thin = 1.dp.toPx()
    val ringStep = 56.dp.toPx()
    var r = ringStep
    while (r <= reach) {
        drawCircle(RadarGreen.copy(alpha = 0.30f * grid), radius = r, center = c, style = Stroke(width = thin))
        r += ringStep
    }
    // Crosshair and the diagonals.
    for (angle in listOf(0f, 45f, 90f, 135f)) {
        val rad = Math.toRadians(angle.toDouble())
        val dx = (kotlin.math.cos(rad) * reach).toFloat()
        val dy = (kotlin.math.sin(rad) * reach).toFloat()
        val alpha = if (angle % 90f == 0f) 0.35f else 0.18f
        drawLine(RadarGreen.copy(alpha = alpha * grid), Offset(c.x - dx, c.y - dy), Offset(c.x + dx, c.y + dy), strokeWidth = thin)
    }
    // Bearing ticks on the outer ring, a long one every 30 degrees.
    val outer = reach - ringStep / 2f
    for (deg in 0 until 360 step 10) {
        val rad = Math.toRadians(deg.toDouble())
        val len = if (deg % 30 == 0) 14.dp.toPx() else 6.dp.toPx()
        val cos = kotlin.math.cos(rad).toFloat()
        val sin = kotlin.math.sin(rad).toFloat()
        drawLine(
            RadarGreen.copy(alpha = 0.5f * grid),
            Offset(c.x + cos * outer, c.y + sin * outer),
            Offset(c.x + cos * (outer - len), c.y + sin * (outer - len)),
            strokeWidth = thin * 1.5f,
        )
    }

    // Sweep: two turns. The gradient's bright edge sits at 90 degrees before rotation.
    val turn = 720f * p
    val beam = 1f - (p - 0.8f).coerceAtLeast(0f) / 0.2f
    rotate(degrees = turn, pivot = c) {
        drawCircle(
            brush = Brush.sweepGradient(
                0f to Color.Transparent,
                0.12f to RadarGreen.copy(alpha = 0.10f * beam),
                0.24f to RadarGreen.copy(alpha = 0.55f * beam),
                0.25f to RadarPhosphor.copy(alpha = 0.85f * beam),
                0.2501f to Color.Transparent,
                1f to Color.Transparent,
                center = c,
            ),
            radius = reach,
            center = c,
        )
    }
    val edge = Math.toRadians((turn + 90f).toDouble())
    drawLine(
        RadarPhosphor.copy(alpha = beam),
        c,
        Offset(c.x + (kotlin.math.cos(edge) * reach).toFloat(), c.y + (kotlin.math.sin(edge) * reach).toFloat()),
        strokeWidth = 2.5.dp.toPx(),
    )

    // Contacts flare up when the beam passes over them and fade until the next pass.
    val edgeTotal = turn + 90f
    for ((angle, share) in RadarBlips) {
        if (edgeTotal < angle) continue
        val since = (edgeTotal - angle) % 360f
        val glow = kotlin.math.exp(-since / 110f) * beam
        if (glow < 0.02f) continue
        val rad = Math.toRadians(angle.toDouble())
        val pos = Offset(
            c.x + (kotlin.math.cos(rad) * reach * share).toFloat(),
            c.y + (kotlin.math.sin(rad) * reach * share).toFloat(),
        )
        drawCircle(RadarPhosphor.copy(alpha = 0.35f * glow), radius = 9.dp.toPx(), center = pos)
        drawCircle(RadarPhosphor.copy(alpha = glow), radius = 3.5.dp.toPx(), center = pos)
    }

    // Three rings spreading out, one after another.
    for (k in 0 until 3) {
        val local = ((p - k * 0.14f) / 0.6f).coerceIn(0f, 1f)
        if (local <= 0f || local >= 1f) continue
        drawCircle(
            color = RadarGreen.copy(alpha = 0.8f * (1f - local)),
            radius = size.minDimension / 2f + reach * local,
            center = c,
            style = Stroke(width = 2.dp.toPx() + 6.dp.toPx() * (1f - local)),
        )
    }
}

/** Pulsing red alarm glow around the plate while the link is being restored. */
private fun DrawScope.drawRedAlert(pulse: Float) {
    val c = center
    val radius = size.maxDimension * (0.9f + 0.35f * pulse)
    drawCircle(
        brush = Brush.radialGradient(
            0f to AlertRed.copy(alpha = 0.45f * pulse),
            0.6f to AlertRed.copy(alpha = 0.15f * pulse),
            1f to Color.Transparent,
            center = c,
            radius = radius,
        ),
        radius = radius,
        center = c,
    )
    // Hazard brackets at the plate corners.
    val arm = 14.dp.toPx()
    val gap = 6.dp.toPx()
    val w = 3.dp.toPx()
    val color = AlertRed.copy(alpha = 0.5f + 0.5f * pulse)
    val l = -gap
    val t = -gap
    val r = size.width + gap
    val b = size.height + gap
    drawLine(color, Offset(l, t), Offset(l + arm, t), w); drawLine(color, Offset(l, t), Offset(l, t + arm), w)
    drawLine(color, Offset(r, t), Offset(r - arm, t), w); drawLine(color, Offset(r, t), Offset(r, t + arm), w)
    drawLine(color, Offset(l, b), Offset(l + arm, b), w); drawLine(color, Offset(l, b), Offset(l, b - arm), w)
    drawLine(color, Offset(r, b), Offset(r - arm, b), w); drawLine(color, Offset(r, b), Offset(r, b - arm), w)
}

// --- Masked Location Text Components ---

private fun annotatedCountryHighlight(
    text: String,
    highlight: String,
    colors: ProtonColors,
    displayText: String = text,
) = buildAnnotatedString {
    append(displayText)
    val startIndex = text.indexOf(highlight)
    if (startIndex >= 0) {
        val styleStart = startIndex.coerceAtMost(displayText.length)
        val styleEnd = (startIndex + highlight.length).coerceAtMost(displayText.length)
        if (styleStart < styleEnd) {
            addStyle(
                style = SpanStyle(color = colors.textNorm, fontWeight = FontWeight.SemiBold),
                start = styleStart,
                end = styleEnd
            )
        }
    }
}

/**
 * Text that can beautifully obscure its contents with a character-by-character animation.
 * Replaces chars with '*' while keeping spaces and dots intact.
 */
@Composable
private fun ObscurableText(
    targetText: String,
    highlightText: String,
    isObscured: Boolean,
    modifier: Modifier = Modifier,
    duration: Int = 30, // Animation speed per character
    targetCharacter: Char = '*',
    preserveCharacters: CharArray = charArrayOf('.', ' ', '-', ':')
) {
    var displayText by remember {
        mutableStateOf(
            if (isObscured) {
                val chars = targetText.toCharArray()
                for (i in chars.indices) {
                    if (!preserveCharacters.contains(chars[i])) chars[i] = targetCharacter
                }
                String(chars)
            } else {
                targetText
            }
        )
    }

    var fixedWidth by remember { mutableStateOf<Int?>(null) }
    // Track the previous target string to rebuild the base perfectly when the IP changes
    var previousTargetText by remember { mutableStateOf(targetText) }

    Box(modifier = modifier) {
        val indicesToAnimate = remember(isObscured, targetText) {
            targetText.indices
                .filter { !preserveCharacters.contains(targetText[it]) }
                .shuffled()
        }

        LaunchedEffect(isObscured, targetText) {
            val targetChars = targetText.toCharArray()
            var currentChars = displayText.toCharArray()

            // Check if the underlying string itself has changed (e.g., completely new IP loaded)
            val baseChanged = previousTargetText != targetText || currentChars.size != targetChars.size

            if (baseChanged) {
                fixedWidth = null
                previousTargetText = targetText

                val baseChars = targetChars.clone()
                if (isObscured) {
                    for (i in baseChars.indices) {
                        if (!preserveCharacters.contains(baseChars[i])) baseChars[i] = targetCharacter
                    }
                }
                // Update displayText immediately for base change to align characters
                displayText = String(baseChars)
                currentChars = baseChars
            }

            // Always run the animation loop to ensure state matches targetText/isObscured
            for (i in indicesToAnimate) {
                if (isObscured && currentChars[i] == targetCharacter) continue
                if (!isObscured && currentChars[i] == targetChars[i]) continue

                delay(duration.toLong())
                val newChar = if (isObscured) targetCharacter else targetChars[i]
                currentChars[i] = newChar
                displayText = String(currentChars)
            }
        }

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            val colors = ProtonNextTheme.colors
            Layout(
                content = {
                    Text(
                        text = annotatedCountryHighlight(
                            text = targetText,
                            highlight = highlightText,
                            colors = colors,
                            displayText = displayText
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ProtonNextTheme.colors.textWeak,
                        modifier = Modifier.onGloballyPositioned {
                            // Prevent layout jumping while animating asterisks
                            if (fixedWidth == null || fixedWidth!! < it.size.width) {
                                fixedWidth = it.size.width
                            }
                        },
                    )
                },
                measurePolicy = { measurables, constraints ->
                    val placeable = measurables.first().measure(constraints)
                    val width = fixedWidth ?: placeable.width
                    val offsetX = (width - placeable.width) / 2
                    layout(width, placeable.height) {
                        placeable.placeRelative(offsetX, 0)
                    }
                }
            )
        }
    }
}

@Composable
private fun LocationTextElement(
    locationText: LocationText,
    isObscured: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ProtonNextTheme.colors
    Box(modifier = modifier) {
        Surface(
            color = colors.backgroundSecondary.copy(alpha = 0.86F),
            border = BorderStroke(
                1.dp,
                Brush.verticalGradient(listOf(colors.shade100.copy(alpha = 0.08f), colors.shade100.copy(alpha = 0.02f)))
            ),
            shape = ClubShape,
            modifier = Modifier
                .clip(ClubShape)
                .clickable(onClick = onClick) // Makes the entire IP block clickable to toggle privacy mode
        ) {
            val unknown = stringResource(R.string.unknown)
            
            // Data is now sanitized at the Mapper level, so we only need simple fallbacks
            val safeIp = locationText.ip.ifBlank { unknown }
            
            val safeCountry = if (locationText.ip.isBlank()) {
                unknown
            } else {
                locationText.country.ifBlank { stringResource(R.string.status_not_connected) }
            }

            val country = BidiFormatter.getInstance().unicodeWrap(safeCountry)
            val fullText = stringResource(R.string.location_format, country, safeIp)

            ObscurableText(
                targetText = fullText,
                highlightText = country,
                isObscured = isObscured,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

// --- Main Screen ---

@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val colors = ProtonNextTheme.colors
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val statsUiState by viewModel.statsUiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingServer by remember { mutableStateOf<LogicalServer?>(null) }
    var isQuickConnectPending by remember { mutableStateOf(false) }
    val isTablet = isTablet()

    var showQuickConnectConfig by remember { mutableStateOf(false) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            ProtonLogger.d("DashboardScreen", "VPN permission granted")
            if (isQuickConnectPending) {
                viewModel.quickConnect()
                isQuickConnectPending = false
            } else {
                pendingServer?.let {
                    viewModel.toggleConnection(it)
                    pendingServer = null
                }
            }
        } else {
            pendingServer = null
            isQuickConnectPending = false
        }
    }

    var showPauseDialog by remember { mutableStateOf(false) }

    val errorAppOpsMsg = stringResource(R.string.error_system_appops)
    val errorVpnDialogNotFound = stringResource(R.string.error_vpn_permission_dialog_not_found)

    val checkVpnAndConnect: (LogicalServer) -> Unit = { server ->
        try {
            val intent = VpnService.prepare(context)
            if (intent != null) {
                pendingServer = server
                vpnPermissionLauncher.launch(intent)
            } else {
                viewModel.toggleConnection(server)
            }
        } catch (_: SecurityException) {
            android.widget.Toast.makeText(context, errorAppOpsMsg, android.widget.Toast.LENGTH_LONG).show()
            viewModel.toggleConnection(server)
        } catch (_: android.content.ActivityNotFoundException) {
            pendingServer = null
            android.widget.Toast.makeText(context, errorVpnDialogNotFound, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    val checkVpnAndQuickConnect: () -> Unit = {
        try {
            val intent = VpnService.prepare(context)
            if (intent != null) {
                isQuickConnectPending = true
                vpnPermissionLauncher.launch(intent)
            } else {
                viewModel.quickConnect()
            }
        } catch (_: SecurityException) {
            android.widget.Toast.makeText(context, errorAppOpsMsg, android.widget.Toast.LENGTH_LONG).show()
            viewModel.quickConnect()
        } catch (_: android.content.ActivityNotFoundException) {
            isQuickConnectPending = false
            android.widget.Toast.makeText(context, errorVpnDialogNotFound, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = colors.backgroundNorm,
        bottomBar = {}
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val successState = uiState as? DashboardUiState.Success
            val isRecovering = successState?.isRecovering == true
            val isConnected = successState?.isConnected == true && !isRecovering
            val isConnecting = successState?.isConnecting == true || isRecovering

            // Background gradient decoration (immersive)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.backgroundNorm)
            )

            if (isTablet) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .vpnStatusOverlayBackground(isConnected, isConnecting, colors)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.6f)
                ) {
                    HomeMap(
                        allServers = (successState?.servers ?: emptyList()).toImmutableList(),
                        connectedServer = successState?.connectedServer,
                        isConnected = isConnected,
                        isConnecting = isConnecting,
                        modifier = Modifier.fillMaxSize(),
                        userCountryCode = successState?.originalLocationText?.countryCode,
                        isInteractive = false
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .align(Alignment.BottomCenter)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, colors.backgroundNorm)))
                    )
                }
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Ends above the map's focus point, so the connected country stays clear.
                        .height(190.dp)
                        .align(Alignment.TopCenter)
                        .vpnStatusOverlayBackground(isConnected, isConnecting, colors)
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(top = 16.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    VpnStatusTop(
                        isConnected = isConnected,
                        isConnecting = isConnecting,
                        vpnState = successState?.vpnState ?: AmneziaVpnManager.VpnState.DISCONNECTED,
                        isRecovering = isRecovering
                    )
                }
            }

            val baseState = when (uiState) {
                is DashboardUiState.Loading -> 0
                is DashboardUiState.Error -> 1
                is DashboardUiState.Success -> 2
            }

            Box(modifier = Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = baseState,
                    label = "dashboard_state",
                    modifier = Modifier.fillMaxSize()
                ) { target ->
                    when (target) {
                        0 -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                ExpressiveCircularProgressIndicator(color = colors.brandNorm)
                            }
                        }
                        1 -> {
                            val errorState = uiState as? DashboardUiState.Error
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(errorState?.message.orEmpty(), color = colors.notificationError)
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
                        2 -> {
                            val state = uiState as? DashboardUiState.Success
                            if (state != null) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    DashboardContent(
                                        state = state,
                                        isTablet = isTablet,
                                        onServerClick = { server -> checkVpnAndConnect(server) },
                                        onQuickConnect = { checkVpnAndQuickConnect() },
                                        onDisconnect = { viewModel.disconnect() },
                                        onPause = { showPauseDialog = true },
                                        onResume = { viewModel.resumeVpn() },
                                        onRefreshCert = { viewModel.refreshCertificate() },
                                        onToggleIpVisibility = { viewModel.toggleIpVisibility() },
                                        onChangeQuickConnect = { showQuickConnectConfig = true },
                                        stats = statsUiState,
                                        onToggleStats = { viewModel.toggleTrafficStats() }
                                    )
                                }

                                if (showPauseDialog) {
                                    PauseDialog(
                                        onDismiss = { showPauseDialog = false },
                                        onPause = { durationMs ->
                                            viewModel.pauseVpn(durationMs)
                                            showPauseDialog = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Move Quick Connect Config outside of AnimatedContent to avoid gesture conflicts
            // and ensure it's not clipped by screen transitions
            if (showQuickConnectConfig && successState != null) {
                QuickConnectBottomSheet(
                    onDismiss = { showQuickConnectConfig = false },
                    currentStrategy = successState.quickConnectStrategy,
                    currentTargetId = successState.quickConnectTargetId,
                    profiles = successState.profiles.toImmutableList(),
                    recentServers = successState.recentConnections.toImmutableList(),
                    onStrategySelect = { strategy, targetId ->
                        viewModel.setQuickConnectStrategy(strategy, targetId)
                    }
                )
            }
        }
    }
}

@Composable
fun DashboardContent(
    state: DashboardUiState.Success,
    onServerClick: (LogicalServer) -> Unit,
    onQuickConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRefreshCert: () -> Unit,
    onToggleIpVisibility: () -> Unit,
    onChangeQuickConnect: () -> Unit,
    modifier: Modifier = Modifier,
    stats: TrafficStatsUiState = TrafficStatsUiState(),
    onToggleStats: () -> Unit = {},
    isTablet: Boolean = false
) {
    Box(modifier = modifier) {
        if (isTablet) {
            TabletDashboardLayout(
                state = state,
                stats = stats,
                onServerClick = onServerClick,
                onQuickConnect = onQuickConnect,
                onDisconnect = onDisconnect,
                onPause = onPause,
                onResume = onResume,
                onRefreshCert = onRefreshCert,
                onToggleIpVisibility = onToggleIpVisibility,
                onChangeQuickConnect = onChangeQuickConnect,
                onToggleStats = onToggleStats
            )
        } else {
            PhoneDashboardLayout(
                state = state,
                stats = stats,
                onServerClick = onServerClick,
                onQuickConnect = onQuickConnect,
                onDisconnect = onDisconnect,
                onPause = onPause,
                onResume = onResume,
                onRefreshCert = onRefreshCert,
                onToggleIpVisibility = onToggleIpVisibility,
                onChangeQuickConnect = onChangeQuickConnect,
                onToggleStats = onToggleStats
            )
        }
    }
}

@Composable
fun CertificateBanner(
    state: AmneziaVpnManager.CertificateState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state == AmneziaVpnManager.CertificateState.Valid) return

    val colors = ProtonNextTheme.colors
    val (backgroundColor, contentColor, icon, message) = when (state) {
        is AmneziaVpnManager.CertificateState.ExpiringSoon -> Quadruple(
            colors.notificationWarning.copy(alpha = 0.1f),
            colors.notificationWarning,
            ProtonIcons.ExclamationTriangleFilled,
            stringResource(R.string.cert_msg_expiring_soon, state.hoursRemaining)
        )
        is AmneziaVpnManager.CertificateState.Expired -> Quadruple(
            colors.notificationError.copy(alpha = 0.1f),
            colors.notificationError,
            ProtonIcons.ExclamationCircle,
            stringResource(R.string.cert_msg_expired)
        )
        is AmneziaVpnManager.CertificateState.Refreshing -> Quadruple(
            colors.backgroundSecondary,
            colors.textNorm,
            ProtonIcons.ArrowsRotate,
            stringResource(R.string.cert_msg_refreshing)
        )
        is AmneziaVpnManager.CertificateState.RefreshFailed -> {
            val msg = if (state.isFullyExpired) {
                stringResource(R.string.cert_msg_refresh_failed, state.error)
            } else {
                stringResource(R.string.cert_msg_auto_refresh_failed)
            }
            Quadruple(
                colors.notificationError.copy(alpha = 0.1f),
                colors.notificationError,
                ProtonIcons.ExclamationCircle,
                msg
            )
        }
        is AmneziaVpnManager.CertificateState.Error -> Quadruple(
            colors.notificationError.copy(alpha = 0.1f),
            colors.notificationError,
            ProtonIcons.ExclamationCircle,
            state.message
        )
    }

    val isRefreshing = state is AmneziaVpnManager.CertificateState.Refreshing
    val infiniteTransition = rememberInfiniteTransition(label = "refresh_anim")
    val rotation by if (isRefreshing) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "rotation"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = ClubShape,
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer { rotationZ = rotation }
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    fontWeight = FontWeight.Medium
                )
            }
            if (state is AmneziaVpnManager.CertificateState.Expired || state is AmneziaVpnManager.CertificateState.RefreshFailed) {
                TextButton(onClick = onRefresh) {
                    Text(stringResource(R.string.cert_btn_refresh_now), color = contentColor)
                }
            } else if (isRefreshing) {
                Text(
                    text = stringResource(R.string.cert_msg_refreshing),
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.7f),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

private data class Quadruple<out A, out B, out C, out D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

@Composable
private fun StatCard(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    val colors = ProtonNextTheme.colors
    Box(
        modifier = modifier
            .liquidGlass(
                shape = ClubShape,
                alpha = 0.4f,
                shadowElevation = 0.dp
            )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.brandNorm,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textWeak
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.textNorm
            )
        }
    }
}

@Composable
fun ConnectionStatusCard(
    isConnected: Boolean,
    isConnecting: Boolean,
    originalLocationText: LocationText?,
    vpnLocationText: LocationText?,
    isIpHidden: Boolean,
    quickConnectStrategy: String,
    quickConnectTargetId: String?,
    profiles: ImmutableList<ru.protonmod.next.data.local.VpnProfileEntity>,
    onToggleIpVisibility: () -> Unit,
    onToggleConnection: () -> Unit,
    onPause: () -> Unit,
    onChangeQuickConnect: () -> Unit,
    modifier: Modifier = Modifier,
    vpnState: AmneziaVpnManager.VpnState = AmneziaVpnManager.VpnState.DISCONNECTED,
    connectedServer: LogicalServer? = null,
    allServers: ImmutableList<LogicalServer> = kotlinx.collections.immutable.persistentListOf(),
    isRecovering: Boolean = false
) {
    val colors = ProtonNextTheme.colors
    val context = LocalContext.current

    val contentColor = colors.textNorm

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .liquidGlass(
                shape = ClubShape,
                alpha = if (isConnected) 0.2f else 0.4f,
                shadowElevation = 0.dp
            )
            .chevronPattern(colors.brandNorm, alpha = if (isConnected) 0.10f else 0.16f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        isRecovering && vpnState != AmneziaVpnManager.VpnState.DISCONNECTED &&
                            vpnState != AmneziaVpnManager.VpnState.DISCONNECTING -> stringResource(R.string.status_recovering)
                        else -> when (vpnState) {
                        AmneziaVpnManager.VpnState.CONNECTED -> stringResource(R.string.status_connected)
                        AmneziaVpnManager.VpnState.CONNECTING -> stringResource(R.string.status_connecting)
                        AmneziaVpnManager.VpnState.VERIFYING -> stringResource(R.string.status_verifying)
                        AmneziaVpnManager.VpnState.DISCONNECTING -> stringResource(R.string.status_disconnecting)
                        else -> stringResource(R.string.status_not_connected)
                        }
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isConnected && !isRecovering) colors.notificationSuccess else contentColor.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Bold
                )

                // Manage the intermediate state when the VPN is UP, but the real IP hasn't been fetched yet
                val isFetchingVpnIp = isConnected && vpnLocationText == null

                val currentLocation = when {
                    isConnected && vpnLocationText != null -> vpnLocationText
                    isConnected && vpnLocationText == null -> {
                        // Provide a dummy IP string while waiting for the real one.
                        val rawCountry = connectedServer?.exitCountry?.let { CountryUtils.getCountryName(context, it) }
                        val safeCountry = rawCountry?.ifBlank { null } ?: stringResource(R.string.unknown)
                        LocationText(country = safeCountry, countryCode = connectedServer?.exitCountry, ip = stringResource(R.string.unknown))
                    }
                    else -> originalLocationText ?: LocationText(country = stringResource(R.string.status_connecting), ip = stringResource(R.string.unknown))
                }

                Spacer(modifier = Modifier.width(12.dp))
                LocationTextElement(
                    locationText = currentLocation,
                    // Force obscuring when connecting, hiding IP manually, waiting for VPN IP, or waiting for Original IP
                    isObscured = isIpHidden || isConnecting || isFetchingVpnIp || originalLocationText == null,
                    onClick = onToggleIpVisibility
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ClubShape)
                    .clickable(enabled = !isConnecting) { onChangeQuickConnect() }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isConnected || isConnecting) {
                    val countryCode = connectedServer?.exitCountry
                    val flagResId = CountryUtils.getFlagResource(context, countryCode)
                    if (flagResId != 0) {
                        FlagIcon(
                            countryFlag = flagResId,
                            size = DpSize(48.dp, 32.dp)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(48.dp, 32.dp)
                                .clip(ClubShape)
                                .background(colors.backgroundNorm),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = ProtonIcons.Earth,
                                contentDescription = stringResource(R.string.desc_country),
                                tint = colors.iconNorm,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                } else {
                    val targetServer = if (quickConnectStrategy == "server") {
                        allServers.find { it.id == quickConnectTargetId }
                    } else null

                    val flagRes = when {
                        targetServer != null -> CountryUtils.getFlagResource(context, targetServer.exitCountry)
                        quickConnectStrategy == "fastest" || quickConnectStrategy == "recent" -> R.drawable.flag_fastest
                        else -> 0
                    }

                    if (flagRes != 0) {
                        FlagIcon(
                            countryFlag = flagRes,
                            size = DpSize(48.dp, 32.dp)
                        )
                    } else {
                        val iconVector = when (quickConnectStrategy) {
                            "profile" -> ProtonIcons.Star
                            else -> ProtonIcons.Bolt
                        }
                        Box(
                            modifier = Modifier
                                .size(48.dp, 32.dp)
                                .clip(ClubShape)
                                .background(colors.backgroundNorm),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = iconVector,
                                contentDescription = null,
                                tint = colors.brandNorm,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    val rawCountry = connectedServer?.let { CountryUtils.getCountryName(context, it.exitCountry) }
                    val safeCountryName = rawCountry?.ifBlank { null } ?: stringResource(R.string.status_vpn)
                    val safeCityName = connectedServer?.localizedCity ?: connectedServer?.city ?: ""

                    val targetServer = if (quickConnectStrategy == "server") {
                        allServers.find { it.id == quickConnectTargetId }
                    } else null

                    val locationTitleText = if (isConnected || isConnecting) {
                        if (safeCityName.isNotEmpty()) {
                            stringResource(R.string.location_city_format, safeCountryName, safeCityName)
                        } else {
                            safeCountryName
                        }
                    } else {
                        when (quickConnectStrategy) {
                            "fastest" -> stringResource(R.string.qc_strategy_fastest)
                            "recent" -> stringResource(R.string.qc_strategy_recent)
                            "profile" -> profiles.find { it.id == quickConnectTargetId }?.name ?: stringResource(R.string.label_fastest_server)
                            "server" -> targetServer?.let {
                                val cName = CountryUtils.getCountryName(context, it.exitCountry)
                                val cityName = it.localizedCity ?: it.city
                                if (cityName.isNotBlank()) "$cName, $cityName" else cName
                            } ?: stringResource(R.string.label_fastest_server)
                            else -> stringResource(R.string.label_fastest_server)
                        }
                    }

                    Text(
                        text = locationTitleText,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isConnected || isConnecting) {
                            connectedServer?.name ?: ""
                        } else {
                            if (quickConnectStrategy == "server") targetServer?.name ?: ""
                            else stringResource(R.string.label_select_location)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textWeak
                    )
                }

                Icon(
                    imageVector = ProtonIcons.ChevronRight,
                    contentDescription = stringResource(R.string.desc_change_server),
                    tint = colors.iconWeak.copy(alpha = 0.5f)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isConnected) {
                    ClubButton(
                        text = stringResource(R.string.btn_pause),
                        onClick = onPause,
                        style = ClubButtonStyle.OUTLINED,
                        modifier = Modifier.weight(1f),
                        height = 58.dp,
                    )
                }

                val canDisconnect = isConnected || isConnecting
                ClubButton(
                    text = if (canDisconnect) stringResource(R.string.btn_disconnect) else stringResource(R.string.btn_quick_connect),
                    onClick = onToggleConnection,
                    style = if (canDisconnect) ClubButtonStyle.OUTLINED else ClubButtonStyle.FILLED,
                    modifier = Modifier.weight(if (canDisconnect) 2f else 1f),
                    height = 58.dp,
                    leading = if (isConnecting) {
                        {
                            ExpressiveCircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = colors.textNorm
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                    } else null,
                )
            }
        }
    }
}

@Composable
fun ConnectionWarningBanner(
    warning: AmneziaVpnManager.ConnectionWarning,
    modifier: Modifier = Modifier
) {
    val colors = ProtonNextTheme.colors
    val (title, description) = when (warning) {
        AmneziaVpnManager.ConnectionWarning.Ipv6OnlyEndpoint ->
            stringResource(R.string.ipv6_blocked_title) to stringResource(R.string.ipv6_blocked_desc)
        AmneziaVpnManager.ConnectionWarning.InvalidProxyConfiguration ->
            stringResource(R.string.proxy_config_invalid_title) to stringResource(R.string.proxy_config_invalid_desc)
        AmneziaVpnManager.ConnectionWarning.ServerNotResponding ->
            stringResource(R.string.server_not_responding_title) to stringResource(R.string.server_not_responding_desc)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = ClubShape,
        color = colors.notificationWarning.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = ProtonIcons.ExclamationTriangleFilled,
                contentDescription = null,
                tint = colors.notificationWarning,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.notificationWarning,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.notificationWarning
                )
            }
        }
    }
}

@Composable
fun BatteryOptimizationBanner(
    modifier: Modifier = Modifier
) {
    val colors = ProtonNextTheme.colors
    val context = LocalContext.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = ClubShape,
        color = colors.notificationWarning.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = ProtonIcons.ExclamationTriangleFilled,
                contentDescription = null,
                tint = colors.notificationWarning,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.battery_optimization_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.notificationWarning,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.battery_optimization_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.notificationWarning
                )
            }
            TextButton(
                onClick = {
                    SystemUtils.openBatteryOptimizationSettings(context)
                }
            ) {
                Text(stringResource(R.string.btn_fix), color = colors.notificationWarning)
            }
        }
    }
}

@Composable
fun PauseBanner(
    endTime: Long,
    onResume: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ProtonNextTheme.colors
    var timeLeft by remember(endTime) { 
        mutableLongStateOf((endTime - System.currentTimeMillis()).coerceAtLeast(0) / 1000)
    }

    Box(modifier = modifier) {
        LaunchedEffect(endTime) {
            while (timeLeft > 0) {
                delay(1000)
                timeLeft = (endTime - System.currentTimeMillis()).coerceAtLeast(0) / 1000
            }
        }

        val minutes = timeLeft / 60
        val seconds = timeLeft % 60
        val timeStr = String.format("%02d:%02d", minutes, seconds)

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = ClubShape,
            color = colors.brandNorm.copy(alpha = 0.1f)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = ProtonIcons.Bolt, // Using Speed icon for Pause indicator
                    contentDescription = null,
                    tint = colors.brandNorm,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.pause_active_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.brandNorm,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.pause_active_desc, timeStr),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.brandNorm
                    )
                }
                TextButton(onClick = onResume) {
                    Text(stringResource(R.string.btn_resume), color = colors.brandNorm)
                }
            }
        }
    }
}

@Composable
fun PauseDialog(
    onDismiss: () -> Unit,
    onPause: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ProtonNextTheme.colors
    var showCustom by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pause_dialog_title), color = colors.textNorm) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!showCustom) {
                    Text(stringResource(R.string.pause_dialog_desc), color = colors.textWeak)
                    Spacer(modifier = Modifier.height(8.dp))
                    listOf(5, 15, 60).forEach { minutes ->
                        Button(
                            onClick = { onPause(minutes * 60 * 1000L) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.backgroundSecondary),
                            shape = ClubShape
                        ) {
                            Text(stringResource(R.string.pause_option, minutes), color = colors.textNorm)
                        }
                    }
                    OutlinedButton(
                        onClick = { showCustom = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = ClubShape,
                        border = BorderStroke(1.dp, colors.shade20)
                    ) {
                        Icon(ProtonIcons.ClockRotateLeft, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.pause_custom), color = colors.textNorm)
                    }
                } else {
                    CustomPauseContent(onPause = onPause)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel), color = colors.brandNorm)
            }
        },
        containerColor = colors.backgroundNorm,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomPauseContent(
    onPause: (Long) -> Unit
) {
    val colors = ProtonNextTheme.colors
    var timeInput by remember { mutableStateOf("") }
    var selectedUnit by remember { mutableIntStateOf(1) } // 0: Sec, 1: Min, 2: Hour
    var expanded by remember { mutableStateOf(false) }

    val units = listOf(
        stringResource(R.string.pause_unit_seconds),
        stringResource(R.string.pause_unit_minutes),
        stringResource(R.string.pause_unit_hours)
    )

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SmoothOutlinedTextField(
            value = timeInput,
            onValueChange = { if (it.all { char -> char.isDigit() }) timeInput = it },
            label = { Text(stringResource(R.string.pause_custom_amount)) },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.brandNorm,
                unfocusedBorderColor = colors.shade20
            )
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = units[selectedUnit],
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                shape = ClubShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.brandNorm,
                    unfocusedBorderColor = colors.shade20
                )
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(colors.backgroundSecondary)
            ) {
                units.forEachIndexed { index, unit ->
                    DropdownMenuItem(
                        text = { Text(unit, color = colors.textNorm) },
                        onClick = {
                            selectedUnit = index
                            expanded = false
                        }
                    )
                }
            }
        }

        Button(
            onClick = {
                val value = timeInput.toLongOrNull() ?: 0L
                val multiplier = when (selectedUnit) {
                    0 -> 1000L
                    1 -> 60 * 1000L
                    2 -> 60 * 60 * 1000L
                    else -> 1000L
                }
                if (value > 0) onPause(value * multiplier)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = colors.brandNorm),
            shape = ClubShape,
            enabled = timeInput.isNotBlank()
        ) {
            Text(stringResource(R.string.btn_start_pause))
        }
    }
}

