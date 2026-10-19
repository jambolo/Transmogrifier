package io.github.jambolo.transmogrifier.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.jambolo.transmogrifier.R

private val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_bold, FontWeight.Bold)
)

private val Bricolage = FontFamily(
    Font(R.font.bricolage_bold, FontWeight.Bold),
    Font(R.font.bricolage_extra_bold, FontWeight.ExtraBold)
)

private fun display(size: Int, height: Int, weight: FontWeight = FontWeight.Bold) = TextStyle(
    fontFamily = Bricolage,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = height.sp,
    letterSpacing = (-0.5).sp
)

private fun body(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = Manrope,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = height.sp,
    letterSpacing = 0.sp
)

val Typography = Typography(
    displayLarge = display(64, 68, FontWeight.ExtraBold),
    displayMedium = display(48, 52, FontWeight.ExtraBold),
    displaySmall = display(40, 44, FontWeight.ExtraBold),
    headlineLarge = display(32, 38),
    headlineMedium = display(28, 34),
    headlineSmall = display(24, 30),
    titleLarge = display(22, 28),
    titleMedium = body(16, 24, FontWeight.Bold),
    titleSmall = body(14, 20, FontWeight.Bold),
    bodyLarge = body(16, 24),
    bodyMedium = body(14, 22),
    bodySmall = body(12, 18),
    labelLarge = body(14, 20, FontWeight.Bold),
    labelMedium = body(12, 18, FontWeight.Bold),
    labelSmall = body(11, 16, FontWeight.Medium)
)
