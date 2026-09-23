package com.mohdshayan.slowglass.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mohdshayan.slowglass.R

/*
 * Michroma (Vernon Adams, SIL OFL 1.1) only where a camera would engrave: the exposure clock, the
 * duration chip and the mode names. Hanken Grotesk (Hanken Design Co., SIL OFL 1.1) for the rest.
 * Both are bundled under res/font; licences in docs/.
 */
val Engraved = FontFamily(Font(R.font.michroma_regular, FontWeight.Normal))

private val Hanken = FontFamily(
    Font(R.font.hanken_grotesk_regular, FontWeight.Normal),
    Font(R.font.hanken_grotesk_medium, FontWeight.Medium),
    Font(R.font.hanken_grotesk_semibold, FontWeight.SemiBold),
)

/** The exposure clock above the shutter. Each digit sits in a fixed-width slot. */
val ClockStyle = TextStyle(fontFamily = Engraved, fontSize = 40.sp, lineHeight = 48.sp)
val DurationChipStyle = TextStyle(fontFamily = Engraved, fontSize = 16.sp, lineHeight = 20.sp)
val ModeNameStyle = TextStyle(fontFamily = Engraved, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.4.sp)

val AppTypography = Typography(
    headlineSmall = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)
