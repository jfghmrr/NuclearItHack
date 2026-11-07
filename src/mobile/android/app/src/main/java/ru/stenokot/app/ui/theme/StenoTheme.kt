package ru.stenokot.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalStenoColors = staticCompositionLocalOf { LightColors }
private val LocalSpeakers = staticCompositionLocalOf { LightSpeakers }
private val LocalStenoType = staticCompositionLocalOf { StenoType() }

private val Type = StenoType()

private val MaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(StenoRadius.s),
    small = RoundedCornerShape(StenoRadius.m),
    medium = RoundedCornerShape(StenoRadius.l),
    large = RoundedCornerShape(StenoRadius.xl),
    extraLarge = RoundedCornerShape(StenoRadius.xxl),
)

private val MaterialType = Typography(
    displaySmall = Type.display,
    headlineSmall = Type.headline,
    titleLarge = Type.titleLarge,
    titleMedium = Type.titleMedium,
    titleSmall = Type.labelMedium,
    bodyLarge = Type.bodyLarge,
    bodyMedium = Type.bodyMedium,
    bodySmall = Type.bodySmall,
    labelLarge = Type.labelLarge,
    labelMedium = Type.labelMedium,
    labelSmall = Type.labelSmall,
)

private fun StenoColors.toScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = surface,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = onSurface,
        surfaceBright = surfaceContainerHighest,
        surfaceDim = surface,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainerLowest = surfaceContainerLowest,
    )
}

/** Единая точка доступа к токенам темы. */
object StenoTheme {
    /** Цвета текущей темы. */
    val colors: StenoColors
        @Composable @ReadOnlyComposable get() = LocalStenoColors.current

    /** Палитра говорящих текущей темы. */
    val speakers: SpeakerPalette
        @Composable @ReadOnlyComposable get() = LocalSpeakers.current

    /** Типографическая шкала. */
    val type: StenoType
        @Composable @ReadOnlyComposable get() = LocalStenoType.current
}

/** Тема приложения. Светлая или тёмная по настройке системы, переключателя нет. */
@Composable
fun StenoTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    CompositionLocalProvider(
        LocalStenoColors provides colors,
        LocalSpeakers provides if (dark) DarkSpeakers else LightSpeakers,
        LocalStenoType provides Type,
    ) {
        MaterialTheme(
            colorScheme = colors.toScheme(),
            typography = MaterialType,
            shapes = MaterialShapes,
            content = content,
        )
    }
}
