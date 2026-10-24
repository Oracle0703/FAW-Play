package com.shilapi.xcertplay.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * Lavender design system colour roles (MD3 TonalSpot from seed #A898DC).
 * Light uses tone 40/100/90/10, dark uses tone 80/20/30/90. UI code references roles only.
 */

internal val LavenderLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF625596),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE7DEFF),
    onPrimaryContainer = Color(0xFF15134A),
    inversePrimary = Color(0xFFCDBDFF),
    secondary = Color(0xFF615B74),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE7DEFC),
    onSecondaryContainer = Color(0xFF1D182E),
    tertiary = Color(0xFF864D5E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9E2),
    onTertiaryContainer = Color(0xFF3A071B),
    error = Color(0xFFBA1929),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF3F0400),
    background = Color(0xFFFAF8FF),
    onBackground = Color(0xFF1C1B20),
    surface = Color(0xFFFAF8FF),
    onSurface = Color(0xFF1C1B20),
    surfaceVariant = Color(0xFFE4E1E9),
    onSurfaceVariant = Color(0xFF484552),
    surfaceTint = Color(0xFF625596),
    inverseSurface = Color(0xFF313035),
    inverseOnSurface = Color(0xFFF2F0F7),
    outline = Color(0xFF797584),
    outlineVariant = Color(0xFFC9C4D5),
    scrim = Color(0xFF000002),
    surfaceBright = Color(0xFFFAF8FF),
    surfaceDim = Color(0xFFDBD9E0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE4E1E9),
)

internal val LavenderDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFCDBDFF),
    onPrimary = Color(0xFF2F2863),
    primaryContainer = Color(0xFF483E7C),
    onPrimaryContainer = Color(0xFFE7DEFF),
    inversePrimary = Color(0xFF625596),
    secondary = Color(0xFFCAC2E0),
    onSecondary = Color(0xFF332D44),
    secondaryContainer = Color(0xFF49435C),
    onSecondaryContainer = Color(0xFFE7DEFC),
    tertiary = Color(0xFFF6B5C6),
    onTertiary = Color(0xFF521F30),
    tertiaryContainer = Color(0xFF6C3646),
    onTertiaryContainer = Color(0xFFFFD9E2),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF68000F),
    errorContainer = Color(0xFF93001A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF36343A),
    onSurfaceVariant = Color(0xFFC9C4D5),
    surfaceTint = Color(0xFFCDBDFF),
    inverseSurface = Color(0xFFE4E1E9),
    inverseOnSurface = Color(0xFF313035),
    outline = Color(0xFF938E9E),
    outlineVariant = Color(0xFF484552),
    scrim = Color(0xFF000002),
    surfaceBright = Color(0xFF3A383E),
    surfaceDim = Color(0xFF141218),
    surfaceContainerLowest = Color(0xFF0F0D14),
    surfaceContainerLow = Color(0xFF1C1B20),
    surfaceContainer = Color(0xFF201F24),
    surfaceContainerHigh = Color(0xFF2B292F),
    surfaceContainerHighest = Color(0xFF36343A),
)

/** Extended semantic roles that Material 3 does not carry: success and warning. */
@Immutable
data class LavenderExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

internal val LavenderExtendedLight = LavenderExtendedColors(
    success = Color(0xFF236A4B),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFA9F2CC),
    onSuccessContainer = Color(0xFF002113),
    warning = Color(0xFF88510F),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFDCBF),
    onWarningContainer = Color(0xFF2A1800),
)

internal val LavenderExtendedDark = LavenderExtendedColors(
    success = Color(0xFF8ED5B1),
    onSuccess = Color(0xFF003823),
    successContainer = Color(0xFF005235),
    onSuccessContainer = Color(0xFFA9F2CC),
    warning = Color(0xFFFEB874),
    onWarning = Color(0xFF492900),
    warningContainer = Color(0xFF6A3C00),
    onWarningContainer = Color(0xFFFFDCBF),
)

val LocalExtendedColors = staticCompositionLocalOf { LavenderExtendedLight }
