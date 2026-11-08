package ru.stenokot.app.ui.theme

import android.content.res.Configuration
import android.view.ContextThemeWrapper
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import ru.stenokot.app.R

private val LocalStenoColors = staticCompositionLocalOf { LightColors }
private val LocalSpeakers = staticCompositionLocalOf { LightSpeakers }
private val LocalStenoType = staticCompositionLocalOf { StenoType() }

private val Type = StenoType()

private val MaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(StenoRadius.s),
    small = RoundedCornerShape(StenoRadius.m),
    medium = RoundedCornerShape(StenoRadius.field),
    large = RoundedCornerShape(StenoRadius.card),
    extraLarge = RoundedCornerShape(StenoRadius.sheet),
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
        scrim = scrim,
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

/**
 * Подменяет ночной режим ресурсов, когда в настройках выбрана тема не как у системы: иначе маскот
 * и системные значения остались бы от другой темы.
 */
@Composable
private fun ForcedNight(dark: Boolean, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val configuration = LocalConfiguration.current
    val themed = remember(base, configuration, dark) {
        val night = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        val forced = Configuration(configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        }
        ContextThemeWrapper(base, R.style.Theme_Skvor).apply { applyOverrideConfiguration(forced) }
    }
    CompositionLocalProvider(
        LocalContext provides themed,
        LocalConfiguration provides themed.resources.configuration,
        LocalResources provides themed.resources,
        content = content,
    )
}

/** Цвета, шрифты и формы дизайна для уже выбранной темы. */
@Composable
private fun StenoTokens(dark: Boolean, content: @Composable () -> Unit) {
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

/** Тема приложения: как в системе либо светлая или тёмная по выбору в настройках. */
@Composable
fun StenoTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    when (mode) {
        ThemeMode.SYSTEM -> StenoTokens(systemDark, content)
        ThemeMode.LIGHT -> ForcedNight(dark = false) { StenoTokens(dark = false, content) }
        ThemeMode.DARK -> ForcedNight(dark = true) { StenoTokens(dark = true, content) }
    }
}
