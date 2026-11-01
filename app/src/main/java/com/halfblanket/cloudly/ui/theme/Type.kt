package com.halfblanket.cloudly.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.halfblanket.cloudly.R

val Inter = FontFamily(
    Font(R.font.inter_400, FontWeight.Normal),
    Font(R.font.inter_500, FontWeight.Medium),
    Font(R.font.inter_600, FontWeight.SemiBold),
    Font(R.font.inter_700, FontWeight.Bold),
)

private fun style(size: Int, weight: FontWeight, line: Int, tracking: Double = 0.0) = TextStyle(
    fontFamily = Inter,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

val CloudlyTypography = Typography(
    displaySmall = style(34, FontWeight.SemiBold, 40, -0.8),
    headlineLarge = style(30, FontWeight.SemiBold, 36, -0.6),
    headlineMedium = style(26, FontWeight.SemiBold, 32, -0.5),
    headlineSmall = style(22, FontWeight.SemiBold, 28, -0.3),
    titleLarge = style(20, FontWeight.SemiBold, 26, -0.2),
    titleMedium = style(16, FontWeight.SemiBold, 22, -0.1),
    titleSmall = style(14, FontWeight.SemiBold, 20),
    bodyLarge = style(16, FontWeight.Normal, 24),
    bodyMedium = style(14, FontWeight.Normal, 20),
    bodySmall = style(12, FontWeight.Normal, 16, 0.1),
    labelLarge = style(14, FontWeight.Medium, 20),
    labelMedium = style(12, FontWeight.Medium, 16, 0.2),
    labelSmall = style(11, FontWeight.Medium, 14, 0.3),
)
