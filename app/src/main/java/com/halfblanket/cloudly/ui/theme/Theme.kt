package com.halfblanket.cloudly.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp
import com.halfblanket.cloudly.data.prefs.ThemeMode

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = AccentOn,
    primaryContainer = AccentDim,
    onPrimaryContainer = NightText,
    secondary = Accent,
    onSecondary = AccentOn,
    secondaryContainer = Night4,
    onSecondaryContainer = NightText,
    tertiary = Success,
    background = Night0,
    onBackground = NightText,
    surface = Night0,
    onSurface = NightText,
    surfaceVariant = Night3,
    onSurfaceVariant = NightMuted,
    surfaceContainerLowest = Night0,
    surfaceContainerLow = Night1,
    surfaceContainer = Night2,
    surfaceContainerHigh = Night3,
    surfaceContainerHighest = Night4,
    surfaceBright = Night4,
    surfaceDim = Night0,
    outline = NightLine,
    outlineVariant = NightLine,
    error = Danger,
    onError = Night0,
    inverseSurface = NightText,
    inverseOnSurface = Night1,
    scrim = Night0,
)

private val LightScheme = lightColorScheme(
    primary = AccentLight,
    onPrimary = Day1,
    primaryContainer = AccentLightContainer,
    onPrimaryContainer = DayText,
    secondary = AccentLight,
    onSecondary = Day1,
    secondaryContainer = Day4,
    onSecondaryContainer = DayText,
    tertiary = Success,
    background = Day0,
    onBackground = DayText,
    surface = Day0,
    onSurface = DayText,
    surfaceVariant = Day3,
    onSurfaceVariant = DayMuted,
    surfaceContainerLowest = Day1,
    surfaceContainerLow = Day1,
    surfaceContainer = Day1,
    surfaceContainerHigh = Day2,
    surfaceContainerHighest = Day3,
    surfaceBright = Day1,
    surfaceDim = Day3,
    outline = DayLine,
    outlineVariant = DayLine,
    error = DangerLight,
    onError = Day1,
)

val CloudlyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CloudlyTheme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val extra = if (dark) {
        CloudlyExtraColors(NightMuted, NightLine, Danger, Success, Warning, true)
    } else {
        CloudlyExtraColors(DayMuted, DayLine, DangerLight, Success, Warning, false)
    }
    CompositionLocalProvider(LocalExtraColors provides extra) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = CloudlyTypography,
            shapes = CloudlyShapes,
            content = content,
        )
    }
}

object CloudlyTheme {
    val extra: CloudlyExtraColors
        @Composable @ReadOnlyComposable get() = LocalExtraColors.current
}
