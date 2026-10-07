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

package ru.protonmod.next.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import ru.protonmod.next.R

object ProtonPalette {
    val Haiti = Color(0xFF1B1340)
    val Valhalla = Color(0xFF271B54)
    val Jacarta = Color(0xFF2E2260)
    val Chambray = Color(0xFF372580)
    val SanMarino = Color(0xFF4D34B3)
    val CornflowerBlue = Color(0xFF6D4AFF)
    val Portage = Color(0xFF8A6EFF)
    val Perano = Color(0xFFC4B7FF)

    val BalticSea = Color(0xFF1C1B24)
    val Bastille = Color(0xFF292733)
    val BlackCurrant = Color(0xFF3B3747)
    val GunPowder = Color(0xFF4A4658)
    val Smoky = Color(0xFF5B576B)
    val Dolphin = Color(0xFF6D697D)
    val CadetBlue = Color(0xFFA7A4B5)
    val Cinder = Color(0xFF0C0C14)
    val ShipGray = Color(0xFF35333D)
    val DoveGray = Color(0xFF706D6B)
    val Dawn = Color(0xFF999693)
    val CottonSeed = Color(0xFFC2BFBC)
    val Cloud = Color(0xFFD1CFCD)
    val Ebb = Color(0xFFEAE7E4)
    val Pampas = Color(0xFFF1EEEB)
    val Carrara = Color(0xFFF5F4F2)
    val White = Color(0xFFFFFFFF)

    val Woodsmoke = Color(0xFF17181C)

    val Pomegranate = Color(0xFFCC2D4F)
    val Mauvelous = Color(0xFFF08FA4)
    val Sunglow = Color(0xFFE65200)
    val TexasRose = Color(0xFFFFB84D)
    val Apple = Color(0xFF007B58)
    val PuertoRico = Color(0xFF4AB89A)

    val Gold = Color(0xFFD4AF37)
    val GoldDarken20 = Color(0xFFAA8C2C)
    val GoldDarken40 = Color(0xFF7F6921)
    val GoldLighten20 = Color(0xFFDDBF5F)
    val GoldLighten40 = Color(0xFFE6CF87)

    // Surfshark
    val SurfsharkBlue = Color(0xFF00D1FF)
    val SurfsharkDeep = Color(0xFF001F2D)

    // Nord
    val NordBlue = Color(0xFF4687FF)
    val NordDeep = Color(0xFF0B122B)

    // IPVanish
    val IPVanishGreen = Color(0xFF8CC63F)
    val IPVanishDeep = Color(0xFF121212)

    // PureVPN
    val PureVPNCyan = Color(0xFF00EBC7)
    val PureVPNDeep = Color(0xFF1A0033)

    // Mullvad
    val MullvadOrange = Color(0xFFFFBD44)
    val MullvadDeep = Color(0xFF222222)

    // Windscribe
    val WindscribeBlue = Color(0xFF009FE3)
    val WindscribeDeep = Color(0xFF001A33)

    // Nothing
    val NothingRed = Color(0xFFFF5252)
}

@Stable
@Suppress("LongParameterList")
class ProtonColors(
    isDark: Boolean,

    val shade100: Color,
    val shade80: Color,
    val shade60: Color,
    val shade50: Color,
    val shade40: Color,
    val shade20: Color,
    val shade15: Color,
    val shade10: Color,
    val shade0: Color,

    val brandDarken40: Color = ProtonPalette.Chambray,
    val brandDarken20: Color = ProtonPalette.SanMarino,
    val brandNorm: Color = ProtonPalette.CornflowerBlue,
    val brandLighten20: Color = ProtonPalette.Portage,
    val brandLighten40: Color = ProtonPalette.Perano,

    val textNorm: Color = shade100,
    val textAccent: Color = brandNorm,
    val textWeak: Color = shade80,
    val textHint: Color = shade60,
    val textDisabled: Color = shade50,
    val textInverted: Color = shade0,

    val iconNorm: Color = shade100,
    val iconAccent: Color = brandNorm,
    val iconWeak: Color = shade80,
    val iconHint: Color = shade60,
    val iconDisabled: Color = shade50,
    val iconInverted: Color = shade0,

    val interactionStrongNorm: Color = shade100,
    val interactionStrongPressed: Color = shade80,

    val interactionWeakNorm: Color = shade20,
    val interactionWeakPressed: Color = shade40,
    val interactionWeakDisabled: Color = shade10,

    val backgroundNorm: Color = shade0,
    val backgroundSecondary: Color = shade10,
    val backgroundDeep: Color = shade15,

    val separatorNorm: Color = shade20,

    val blenderNorm: Color,

    val notificationNorm: Color = shade100,
    val notificationError: Color = ProtonPalette.Pomegranate,
    val notificationWarning: Color = ProtonPalette.Sunglow,
    val notificationSuccess: Color = ProtonPalette.Apple,

    val interactionNorm: Color = brandNorm,
    val interactionPressed: Color = brandDarken20,
    val interactionDisabled: Color = brandLighten40,

    val floatyBackground: Color = ProtonPalette.ShipGray,
    val floatyPressed: Color = ProtonPalette.Cinder,
    val floatyText: Color = Color.White,

    val shadowNorm: Color,
    val shadowRaised: Color,
    val shadowLifted: Color,

    val onInteraction: Color = Color.White,
    val navigationActive: Color = brandNorm,

    val sidebarColors: ProtonColors? = null,
) {
    var isDark: Boolean by mutableStateOf(isDark, structuralEqualityPolicy())
        internal set

    fun copy(
        isDark: Boolean = this.isDark,

        shade100: Color = this.shade100,
        shade80: Color = this.shade80,
        shade60: Color = this.shade60,
        shade50: Color = this.shade50,
        shade40: Color = this.shade40,
        shade20: Color = this.shade20,
        shade15: Color = this.shade15,
        shade10: Color = this.shade10,
        shade0: Color = this.shade0,

        textNorm: Color = this.textNorm,
        textAccent: Color = this.textAccent,
        textWeak: Color = this.textWeak,
        textHint: Color = this.textHint,
        textDisabled: Color = this.textDisabled,
        textInverted: Color = this.textInverted,

        iconNorm: Color = this.iconNorm,
        iconAccent: Color = this.iconAccent,
        iconWeak: Color = this.iconWeak,
        iconHint: Color = this.iconHint,
        iconDisabled: Color = this.iconDisabled,
        iconInverted: Color = this.iconInverted,

        interactionStrongNorm: Color = this.interactionStrongNorm,
        interactionStrongPressed: Color = this.interactionStrongPressed,

        interactionWeakNorm: Color = this.interactionWeakNorm,
        interactionWeakPressed: Color = this.interactionWeakPressed,
        interactionWeakDisabled: Color = this.interactionWeakDisabled,

        backgroundNorm: Color = this.backgroundNorm,
        backgroundSecondary: Color = this.backgroundSecondary,
        backgroundDeep: Color = this.backgroundDeep,

        separatorNorm: Color = this.separatorNorm,

        blenderNorm: Color = this.blenderNorm,

        brandDarken40: Color = this.brandDarken40,
        brandDarken20: Color = this.brandDarken20,
        brandNorm: Color = this.brandNorm,
        brandLighten20: Color = this.brandLighten20,
        brandLighten40: Color = this.brandLighten40,

        notificationNorm: Color = this.notificationNorm,
        notificationError: Color = this.notificationError,
        notificationWarning: Color = this.notificationWarning,
        notificationSuccess: Color = this.notificationSuccess,

        interactionNorm: Color = this.interactionNorm,
        interactionPressed: Color = this.interactionPressed,
        interactionDisabled: Color = this.interactionDisabled,

        floatyBackground: Color = this.floatyBackground,
        floatyPressed: Color = this.floatyPressed,
        floatyText: Color = this.floatyText,

        shadowNorm: Color = this.shadowNorm,
        shadowRaised: Color = this.shadowRaised,
        shadowLifted: Color = this.shadowLifted,

        onInteraction: Color = this.onInteraction,
        navigationActive: Color = this.navigationActive,

        sidebarColors: ProtonColors? = this.sidebarColors,
    ) = ProtonColors(
        isDark = isDark,

        shade100 = shade100,
        shade80 = shade80,
        shade60 = shade60,
        shade50 = shade50,
        shade40 = shade40,
        shade20 = shade20,
        shade15 = shade15,
        shade10 = shade10,
        shade0 = shade0,

        textNorm = textNorm,
        textAccent = textAccent,
        textWeak = textWeak,
        textHint = textHint,
        textDisabled = textDisabled,
        textInverted = textInverted,

        iconNorm = iconNorm,
        iconAccent = iconAccent,
        iconWeak = iconWeak,
        iconHint = iconHint,
        iconDisabled = iconDisabled,
        iconInverted = iconInverted,

        interactionStrongNorm = interactionStrongNorm,
        interactionStrongPressed = interactionStrongPressed,

        interactionWeakNorm = interactionWeakNorm,
        interactionWeakPressed = interactionWeakPressed,
        interactionWeakDisabled = interactionWeakDisabled,

        backgroundNorm = backgroundNorm,
        backgroundSecondary = backgroundSecondary,
        backgroundDeep = backgroundDeep,

        separatorNorm = separatorNorm,

        blenderNorm = blenderNorm,

        brandDarken40 = brandDarken40,
        brandDarken20 = brandDarken20,
        brandNorm = brandNorm,
        brandLighten20 = brandLighten20,
        brandLighten40 = brandLighten40,

        notificationNorm = notificationNorm,
        notificationError = notificationError,
        notificationWarning = notificationWarning,
        notificationSuccess = notificationSuccess,

        interactionNorm = interactionNorm,
        interactionPressed = interactionPressed,
        interactionDisabled = interactionDisabled,

        floatyBackground = floatyBackground,
        floatyPressed = floatyPressed,
        floatyText = floatyText,

        shadowNorm = shadowNorm,
        shadowRaised = shadowRaised,
        shadowLifted = shadowLifted,

        onInteraction = onInteraction,
        navigationActive = navigationActive,

        sidebarColors = sidebarColors,
    )

    companion object {

        val Light = baseLight().copy(sidebarColors = sidebarLight())
        val Dark = baseDark().copy(sidebarColors = sidebarDark())


        private fun baseLight(
            brandDarken40: Color = ProtonPalette.Chambray,
            brandDarken20: Color = ProtonPalette.SanMarino,
            brandNorm: Color = ProtonPalette.CornflowerBlue,
            brandLighten20: Color = ProtonPalette.Portage,
            brandLighten40: Color = ProtonPalette.Perano,
        ) = ProtonColors(
            isDark = false,
            brandDarken40 = brandDarken40,
            brandDarken20 = brandDarken20,
            brandNorm = brandNorm,
            brandLighten20 = brandLighten20,
            brandLighten40 = brandLighten40,
            notificationError = ProtonPalette.Pomegranate,
            notificationWarning = ProtonPalette.Sunglow,
            notificationSuccess = ProtonPalette.Apple,
            shade100 = ProtonPalette.Cinder,
            shade80 = ProtonPalette.DoveGray,
            shade60 = ProtonPalette.Dawn,
            shade50 = ProtonPalette.CottonSeed,
            shade40 = ProtonPalette.Cloud,
            shade20 = ProtonPalette.Ebb,
            shade15 = ProtonPalette.Pampas,
            shade10 = Color(0xFFF0F0F0), // Explicit secondary
            shade0 = Color.White,
            shadowNorm = Color.Black.copy(alpha = 0.1f),
            shadowRaised = Color.Black.copy(alpha = 0.1f),
            shadowLifted = Color.Black.copy(alpha = 0.1f),
            blenderNorm = ProtonPalette.Woodsmoke.copy(alpha = 0.48f),
            textAccent = brandNorm,
            iconAccent = brandNorm,
        ).let {
            it.copy(
                interactionWeakNorm = it.shade15,
                interactionWeakPressed = it.shade20,
                interactionWeakDisabled = it.shade10,
                backgroundSecondary = it.shade15.copy(alpha = 0.4f), // More transparent for better glass effect
                onInteraction = Color.White,
            )
        }

        private fun baseDark(
            brandDarken40: Color = ProtonPalette.Chambray,
            brandDarken20: Color = ProtonPalette.SanMarino,
            brandNorm: Color = ProtonPalette.CornflowerBlue,
            brandLighten20: Color = ProtonPalette.Portage,
            brandLighten40: Color = ProtonPalette.Perano,
        ) = ProtonColors(
            isDark = true,
            brandDarken40 = brandDarken40,
            brandDarken20 = brandDarken20,
            brandNorm = brandNorm,
            brandLighten20 = brandLighten20,
            brandLighten40 = brandLighten40,
            notificationError = ProtonPalette.Mauvelous,
            notificationWarning = ProtonPalette.TexasRose,
            notificationSuccess = ProtonPalette.PuertoRico,
            shade100 = Color.White,
            shade80 = ProtonPalette.CadetBlue,
            shade60 = ProtonPalette.Dolphin,
            shade50 = ProtonPalette.Smoky,
            shade40 = ProtonPalette.GunPowder,
            shade20 = ProtonPalette.BlackCurrant,
            shade15 = ProtonPalette.Bastille,
            shade10 = ProtonPalette.BalticSea,
            shade0 = ProtonPalette.Cinder,
            shadowNorm = Color.Black.copy(alpha = 0.8f),
            shadowRaised = Color.Black.copy(alpha = 0.8f),
            shadowLifted = Color.Black.copy(alpha = 0.86f),
            blenderNorm = Color.Black.copy(alpha = 0.52f),
            textAccent = brandLighten20,
            iconAccent = brandLighten20,
        ).let {
            it.copy(
                interactionWeakNorm = it.shade20,
                interactionWeakPressed = it.shade40,
                interactionWeakDisabled = it.shade15,
                interactionDisabled = it.brandDarken40,
                backgroundNorm = it.shade10,
                backgroundSecondary = it.shade20.copy(alpha = 0.4f), // More transparent for better glass effect
                backgroundDeep = it.shade0,
                onInteraction = Color.White,
            )
        }

        private fun sidebarLight(
            brandDarken40: Color = ProtonPalette.Chambray,
            brandDarken20: Color = ProtonPalette.SanMarino,
            brandNorm: Color = ProtonPalette.CornflowerBlue,
            brandLighten20: Color = ProtonPalette.Portage,
            brandLighten40: Color = ProtonPalette.Perano,
        ) = baseLight(
            brandDarken40 = brandDarken40,
            brandDarken20 = brandDarken20,
            brandNorm = brandNorm,
            brandLighten20 = brandLighten20,
            brandLighten40 = brandLighten40,
        ).copy(
            backgroundNorm = ProtonPalette.Haiti,
            interactionWeakNorm = ProtonPalette.Jacarta,
            interactionWeakPressed = ProtonPalette.Valhalla,
            separatorNorm = ProtonPalette.Jacarta,
            textNorm = ProtonPalette.White,
            textWeak = ProtonPalette.CadetBlue,
            iconNorm = ProtonPalette.White,
            iconWeak = ProtonPalette.CadetBlue,
            interactionPressed = ProtonPalette.SanMarino,
        )

        private fun sidebarDark(
            brandDarken40: Color = ProtonPalette.Chambray,
            brandDarken20: Color = ProtonPalette.SanMarino,
            brandNorm: Color = ProtonPalette.CornflowerBlue,
            brandLighten20: Color = ProtonPalette.Portage,
            brandLighten40: Color = ProtonPalette.Perano,
        ) = baseDark(
            brandDarken40 = brandDarken40,
            brandDarken20 = brandDarken20,
            brandNorm = brandNorm,
            brandLighten20 = brandLighten20,
            brandLighten40 = brandLighten40,
        ).copy(
            backgroundNorm = ProtonPalette.Cinder,
            interactionWeakNorm = ProtonPalette.BlackCurrant,
            interactionWeakPressed = ProtonPalette.GunPowder,
            separatorNorm = ProtonPalette.BlackCurrant,
            textNorm = ProtonPalette.White,
            textWeak = ProtonPalette.CadetBlue,
            iconNorm = ProtonPalette.White,
            iconWeak = ProtonPalette.CadetBlue,
            interactionPressed = ProtonPalette.SanMarino,
        )

        fun fromMaterial3(scheme: ColorScheme, isDark: Boolean): ProtonColors {
            val base = if (isDark) baseDark(
                brandNorm = scheme.primary,
                brandDarken20 = scheme.primary.copy(alpha = 0.8f),
                brandDarken40 = scheme.primary.copy(alpha = 0.6f)
            ) else baseLight(
                brandNorm = scheme.primary,
                brandDarken20 = scheme.primary.copy(alpha = 0.8f),
                brandDarken40 = scheme.primary.copy(alpha = 0.6f)
            )

            return base.copy(
                brandNorm = scheme.primary,
                brandDarken20 = scheme.primaryContainer,
                brandDarken40 = scheme.onPrimaryContainer,
                backgroundNorm = scheme.background,
                backgroundSecondary = scheme.surfaceVariant.copy(alpha = 0.4f),
                backgroundDeep = scheme.surfaceContainer,
                textNorm = scheme.onBackground,
                textWeak = scheme.onSurfaceVariant,
                iconNorm = scheme.onBackground,
                iconWeak = scheme.onSurfaceVariant,
                interactionNorm = scheme.primary,
                interactionPressed = scheme.primary.copy(alpha = 0.8f)
            )
        }
    }
}

fun ProtonColors.textNorm(enabled: Boolean = true) = if (enabled) textNorm else textDisabled
fun ProtonColors.textWeak(enabled: Boolean = true) = if (enabled) textWeak else textDisabled
fun ProtonColors.interactionNorm(enabled: Boolean = true) = if (enabled) interactionNorm else interactionDisabled

internal fun ProtonColors.toMaterial3ThemeColors() = androidx.compose.material3.ColorScheme(
    primary = brandNorm,
    onPrimary = onInteraction,
    primaryContainer = backgroundNorm,
    onPrimaryContainer = textNorm,
    inversePrimary = onInteraction,
    secondary = brandNorm,
    onSecondary = onInteraction,
    secondaryContainer = backgroundSecondary,
    onSecondaryContainer = textNorm,
    tertiary = brandDarken20,
    onTertiary = onInteraction,
    tertiaryContainer = backgroundNorm,
    onTertiaryContainer = textNorm,
    background = backgroundNorm,
    onBackground = textNorm,
    surface = backgroundNorm,
    onSurface = textNorm,
    surfaceVariant = backgroundNorm,
    onSurfaceVariant = textNorm,
    inverseSurface = backgroundNorm,
    inverseOnSurface = textNorm,
    error = notificationError,
    onError = textInverted,
    errorContainer = backgroundNorm,
    onErrorContainer = textNorm,
    outline = brandNorm,
    surfaceTint = Color.Unspecified,
    outlineVariant = brandNorm,
    scrim = blenderNorm,
    surfaceBright = backgroundNorm,
    surfaceDim = backgroundNorm,
    surfaceContainer = backgroundSecondary,
    surfaceContainerHigh = backgroundSecondary,
    surfaceContainerHighest = backgroundSecondary,
    surfaceContainerLow = backgroundNorm,
    surfaceContainerLowest = backgroundNorm,
    primaryFixed = brandNorm,
    onPrimaryFixed = onInteraction,
    primaryFixedDim = brandDarken20,
    onPrimaryFixedVariant = onInteraction,
    secondaryFixed = brandNorm,
    onSecondaryFixed = onInteraction,
    secondaryFixedDim = brandDarken20,
    onSecondaryFixedVariant = onInteraction,
    tertiaryFixed = brandDarken20,
    onTertiaryFixed = onInteraction,
    tertiaryFixedDim = brandDarken40,
    onTertiaryFixedVariant = onInteraction
)

val LocalColors = staticCompositionLocalOf { ProtonColors.Light }

@Composable
fun ProtonNextTheme(
    modifier: Modifier = Modifier,
    appTheme: AppTheme = AppTheme.DARK,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val isSystemDark = isSystemInDarkTheme()
    val protonColors = when (appTheme) {
        AppTheme.LIGHT -> ProtonColors.Light
        AppTheme.DARK -> ProtonColors.Dark
    }

    val typography = MaterialTheme.typography

    val isDark = protonColors.isDark

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !isDark
        }
    }

    Surface(
        modifier = modifier,
        color = protonColors.backgroundNorm
    ) {
        CompositionLocalProvider(LocalColors provides protonColors) {
            MaterialTheme(
                colorScheme = protonColors.toMaterial3ThemeColors(),
                typography = typography,
                content = content
            )
        }
    }
}

object ProtonNextTheme {
    val colors: ProtonColors
        @Composable
        @ReadOnlyComposable
        get() = LocalColors.current
}
