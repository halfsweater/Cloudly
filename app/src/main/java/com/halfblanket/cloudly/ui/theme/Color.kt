package com.halfblanket.cloudly.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.halfblanket.cloudly.data.drive.FileCategory

// Dark (primary) palette: deep charcoal surfaces with a restrained electric-periwinkle accent.
internal val Night0 = Color(0xFF0B0C0F) // background
internal val Night1 = Color(0xFF121317) // surface
internal val Night2 = Color(0xFF181A1F) // container
internal val Night3 = Color(0xFF1F2128) // container high
internal val Night4 = Color(0xFF272A32) // container highest
internal val NightLine = Color(0xFF2C2F38)
internal val NightText = Color(0xFFECEDF1)
internal val NightMuted = Color(0xFF9097A3)
internal val Accent = Color(0xFF7B8CFF)
internal val AccentDim = Color(0xFF2A3160)
internal val AccentOn = Color(0xFF0A0D1F)

// Light palette.
internal val Day0 = Color(0xFFF5F6F8)
internal val Day1 = Color(0xFFFFFFFF)
internal val Day2 = Color(0xFFF0F1F4)
internal val Day3 = Color(0xFFE9EBEF)
internal val Day4 = Color(0xFFE1E4EA)
internal val DayLine = Color(0xFFD9DCE3)
internal val DayText = Color(0xFF15171C)
internal val DayMuted = Color(0xFF5E6573)
internal val AccentLight = Color(0xFF4457E0)
internal val AccentLightContainer = Color(0xFFDDE1FF)

internal val Danger = Color(0xFFFF6B6B)
internal val DangerLight = Color(0xFFD93A3A)
internal val Success = Color(0xFF4CC38A)
internal val Warning = Color(0xFFF5B946)

/** Distinct colours that mark which account a file or storage figure belongs to. */
val AccountPalette = listOf(
    Color(0xFF7B8CFF), // periwinkle
    Color(0xFF3DD6B5), // teal
    Color(0xFFF5B946), // amber
    Color(0xFFFF7A9C), // rose
    Color(0xFFB28CFF), // violet
    Color(0xFF8FD460), // lime
    Color(0xFF5CC8FF), // sky
    Color(0xFFFF9F5A), // tangerine
)

fun accountColor(index: Int): Color = AccountPalette[index.mod(AccountPalette.size)]

fun categoryColor(category: FileCategory): Color = when (category) {
    FileCategory.FOLDER -> Color(0xFF8C9BB5)
    FileCategory.IMAGE -> Color(0xFF4CC38A)
    FileCategory.VIDEO -> Color(0xFFFF6B6B)
    FileCategory.AUDIO -> Color(0xFFB28CFF)
    FileCategory.PDF -> Color(0xFFFF8A4C)
    FileCategory.DOCUMENT -> Color(0xFF5B9BFF)
    FileCategory.SPREADSHEET -> Color(0xFF35B97A)
    FileCategory.PRESENTATION -> Color(0xFFF5B946)
    FileCategory.ARCHIVE -> Color(0xFFB59A7A)
    FileCategory.OTHER -> Color(0xFF8A919E)
}

@Immutable
data class CloudlyExtraColors(
    val muted: Color,
    val line: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
    val isDark: Boolean,
)

val LocalExtraColors = staticCompositionLocalOf {
    CloudlyExtraColors(NightMuted, NightLine, Danger, Success, Warning, true)
}
