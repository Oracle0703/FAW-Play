package com.shilapi.xcertplay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/** 4dp grid and layout tokens of the Lavender system. */
object Space {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp
    val s8 = 32.dp
    val s12 = 48.dp
    val marginCompact = 16.dp
    val measure = 600.dp
}

/** MD3 shape scale: the larger, the more important or interactive. */
object Radius {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 28.dp
}

private val LavenderShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.xs),
    small = RoundedCornerShape(Radius.sm),
    medium = RoundedCornerShape(Radius.md),
    large = RoundedCornerShape(Radius.lg),
    extraLarge = RoundedCornerShape(Radius.xl),
)

/** Phones use 375dp on the short edge as 1.0x; tablets and head units scale up proportionally. */
const val BASE_SHORTEST_WIDTH_DP = 375f
const val MIN_UI_SCALE = 0.85f
const val MAX_UI_SCALE = 2.2f

/**
 * Proportional UI scaling: the larger the screen, the larger text, spacing and padding.
 * The font scale is locked to 1 and follows the same factor, so head units with unusual
 * system font sizes cannot break the layout.
 */
@Composable
fun ProvideScaledDensity(content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val current = LocalDensity.current
    val shortestWidthDp = minOf(configuration.screenWidthDp, configuration.screenHeightDp)
    val scaleFactor = (shortestWidthDp / BASE_SHORTEST_WIDTH_DP).coerceIn(MIN_UI_SCALE, MAX_UI_SCALE)
    val scaled = remember(current.density, scaleFactor) {
        Density(density = current.density * scaleFactor, fontScale = 1f)
    }
    CompositionLocalProvider(LocalDensity provides scaled, content = content)
}

/**
 * Lavender theme. Light and dark follow the system automatically; there is no dynamic colour so
 * the brand seed stays consistent on every device.
 */
@Composable
fun XcertplayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    scaled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) LavenderDark else LavenderLight
    val extended = if (darkTheme) LavenderExtendedDark else LavenderExtendedLight
    CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialTheme(colorScheme = colors, typography = Typography, shapes = LavenderShapes) {
            if (scaled) ProvideScaledDensity(content) else content()
        }
    }
}

/** Shortcut for the success/warning roles. */
object Lavender {
    val extended: LavenderExtendedColors
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current
}
