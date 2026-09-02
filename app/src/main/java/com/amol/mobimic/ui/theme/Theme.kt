package com.amol.mobimic.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * Colours that carry meaning but have no Material slot to live in.
 *
 * Material's scheme covers surfaces and accents; it has nothing for "the signal is
 * live" or "this row is a warning". Putting them in their own local keeps screens
 * from hardcoding hex values, which is how a palette quietly drifts apart.
 */
data class SemanticColors(
    val live: Color,
    val good: Color,
    val warn: Color,
    val bad: Color,
    val separator: Color,
    val labelSecondary: Color,
    val labelTertiary: Color,
    val fill: Color,
    val canvas: Color,
    val groupedSurface: Color,
)

val LocalSemanticColors = staticCompositionLocalOf {
    SemanticColors(
        live = LiveRedDark,
        good = SignalGreenDark,
        warn = WarnAmberDark,
        bad = LiveRedDark,
        separator = DarkSeparator,
        labelSecondary = DarkLabelSecondary,
        labelTertiary = DarkLabelTertiary,
        fill = DarkFill,
        canvas = DarkCanvas,
        groupedSurface = DarkSurface,
    )
}

/** Shorthand for the semantic palette. */
val semantic: SemanticColors
    @Composable get() = LocalSemanticColors.current

private val DarkScheme = darkColorScheme(
    primary = AccentBlueDark,
    onPrimary = Color.White,
    secondary = AccentBlueDark,
    background = DarkCanvas,
    onBackground = DarkLabel,
    surface = DarkSurface,
    onSurface = DarkLabel,
    surfaceVariant = DarkSurfaceRaised,
    onSurfaceVariant = DarkLabelSecondary,
    outline = DarkSeparator,
    error = LiveRedDark,
)

private val LightScheme = lightColorScheme(
    primary = AccentBlueLight,
    onPrimary = Color.White,
    secondary = AccentBlueLight,
    background = LightCanvas,
    onBackground = LightLabel,
    surface = LightSurface,
    onSurface = LightLabel,
    surfaceVariant = LightSurfaceRaised,
    onSurfaceVariant = LightLabelSecondary,
    outline = LightSeparator,
    error = LiveRedLight,
)

/**
 * Generous, even radii. Cards at 18dp read as physical objects rather than boxes,
 * and the control radius matches so nothing looks borrowed from another screen.
 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun MobiMicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Dynamic colour is deliberately off. The meters depend on green/amber/red
    // meaning specific things, and a wallpaper-derived scheme would recolour them
    // into nonsense.
    val colorScheme = if (darkTheme) DarkScheme else LightScheme

    val semanticColors = if (darkTheme) {
        SemanticColors(
            live = LiveRedDark,
            good = SignalGreenDark,
            warn = WarnAmberDark,
            bad = LiveRedDark,
            separator = DarkSeparator,
            labelSecondary = DarkLabelSecondary,
            labelTertiary = DarkLabelTertiary,
            fill = DarkFill,
            canvas = DarkCanvas,
            groupedSurface = DarkSurface,
        )
    } else {
        SemanticColors(
            live = LiveRedLight,
            good = SignalGreenLight,
            warn = WarnAmberLight,
            bad = LiveRedLight,
            separator = LightSeparator,
            labelSecondary = LightLabelSecondary,
            labelTertiary = LightLabelTertiary,
            fill = LightFill,
            canvas = LightCanvas,
            groupedSurface = LightSurface,
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalSemanticColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = AppShapes,
            content = content,
        )
    }
}
