package com.shilapi.xcertplay.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.shilapi.xcertplay.host.R

/** Plus Jakarta Sans for UI; CJK falls back to the system font (PingFang / Noto Sans SC). */
val LavenderSans = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
)

/** JetBrains Mono for logs and copyable technical values. */
val LavenderMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

private fun style(
    size: Int,
    line: Int,
    weight: FontWeight,
    tracking: TextUnit,
    family: FontFamily = LavenderSans,
) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking,
)

private val Regular = FontWeight.Normal
private val Semi = FontWeight.SemiBold

/** The 15 MD3 styles of the Lavender system. Only weights 400 and 600 are used. */
val Typography = Typography(
    displayLarge = style(57, 64, Regular, (-0.25).sp),
    displayMedium = style(45, 52, Regular, 0.sp),
    displaySmall = style(36, 44, Regular, 0.sp),
    headlineLarge = style(32, 40, Regular, 0.sp),
    headlineMedium = style(28, 36, Regular, 0.sp),
    headlineSmall = style(24, 32, Regular, 0.sp),
    titleLarge = style(22, 28, Regular, 0.sp),
    titleMedium = style(16, 24, Semi, 0.15.sp),
    titleSmall = style(14, 20, Semi, 0.1.sp),
    bodyLarge = style(16, 24, Regular, 0.3.sp),
    bodyMedium = style(14, 20, Regular, 0.2.sp),
    bodySmall = style(12, 16, Regular, 0.3.sp),
    labelLarge = style(14, 20, Semi, 0.1.sp),
    labelMedium = style(12, 16, Semi, 0.4.sp),
    labelSmall = style(11, 16, Semi, 0.4.sp),
)

/** `code-medium` and `code-large`: log lines and copyable fields. */
object LavenderCode {
    val medium = style(12, 18, Regular, 0.sp, LavenderMono)
    val large = style(14, 20, Regular, 0.sp, LavenderMono)
}
