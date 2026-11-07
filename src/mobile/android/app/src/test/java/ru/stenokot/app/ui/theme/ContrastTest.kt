package ru.stenokot.app.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Считает контраст по формуле WCAG 2.x для всех пар цветов, которые встречаются на экранах. */
class ContrastTest {
    private fun channel(value: Float): Double {
        val c = value.toDouble()
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun check(name: String, a: Color, b: Color, minimum: Double) {
        val value = ratio(a, b)
        assertTrue("$name: %.2f меньше %.1f".format(value, minimum), value >= minimum)
    }

    private fun textPairs(c: StenoColors) = mapOf(
        "onSurface/surface" to (c.onSurface to c.surface),
        "onSurface/lowest" to (c.onSurface to c.surfaceContainerLowest),
        "onSurface/high" to (c.onSurface to c.surfaceContainerHigh),
        "variant/surface" to (c.onSurfaceVariant to c.surface),
        "variant/lowest" to (c.onSurfaceVariant to c.surfaceContainerLowest),
        "variant/low" to (c.onSurfaceVariant to c.surfaceContainerLow),
        "variant/container" to (c.onSurfaceVariant to c.surfaceContainer),
        "variant/high" to (c.onSurfaceVariant to c.surfaceContainerHigh),
        "variant/highest" to (c.onSurfaceVariant to c.surfaceContainerHighest),
        "onPrimary/primary" to (c.onPrimary to c.primary),
        "primary/surface" to (c.primary to c.surface),
        "primary/lowest" to (c.primary to c.surfaceContainerLowest),
        "onPrimaryContainer" to (c.onPrimaryContainer to c.primaryContainer),
        "variant/primaryContainer" to (c.onSurfaceVariant to c.primaryContainer),
        "onSurface/primaryContainer" to (c.onSurface to c.primaryContainer),
        "primary/low" to (c.primary to c.surfaceContainerLow),
        "onSecondary" to (c.onSecondary to c.secondary),
        "onSecondaryContainer" to (c.onSecondaryContainer to c.secondaryContainer),
        "tertiary/surface" to (c.tertiary to c.surface),
        "onTertiary" to (c.onTertiary to c.tertiary),
        "onTertiaryContainer" to (c.onTertiaryContainer to c.tertiaryContainer),
        "onError" to (c.onError to c.error),
        "error/surface" to (c.error to c.surface),
        "error/lowest" to (c.error to c.surfaceContainerLowest),
        "onErrorContainer" to (c.onErrorContainer to c.errorContainer),
        "onSuccess" to (c.onSuccess to c.success),
        "success/surface" to (c.success to c.surface),
        "onSuccessContainer" to (c.onSuccessContainer to c.successContainer),
        "onWarning" to (c.onWarning to c.warning),
        "warning/surface" to (c.warning to c.surface),
        "onWarningContainer" to (c.onWarningContainer to c.warningContainer),
        "inverse" to (c.inverseOnSurface to c.inverseSurface),
        "inversePrimary" to (c.inversePrimary to c.inverseSurface),
    )

    private fun graphicPairs(c: StenoColors) = mapOf(
        "outline/surface" to (c.outline to c.surface),
        "progress/track" to (c.primary to c.surfaceVariant),
        "progress/primaryContainer" to (c.primary to c.surface),
        "primary/primaryContainer" to (c.primary to c.primaryContainer),
        "success/successContainer" to (c.success to c.successContainer),
        "warning/warningContainer" to (c.warning to c.warningContainer),
        "error/errorContainer" to (c.error to c.errorContainer),
        "playhead/surface" to (c.onSurface to c.surface),
        "activeEdge/primaryContainer" to (c.primary to c.primaryContainer),
    )

    @Test
    fun textMeetsAaInBothThemes() {
        listOf(LightColors, DarkColors).forEach { colors ->
            textPairs(colors).forEach { (name, pair) -> check(name, pair.first, pair.second, 4.5) }
        }
    }

    @Test
    fun graphicsMeetThreeToOneInBothThemes() {
        listOf(LightColors, DarkColors).forEach { colors ->
            graphicPairs(colors).forEach { (name, pair) -> check(name, pair.first, pair.second, 3.0) }
        }
    }

    @Test
    fun speakerDigitsReadOnEveryShape() {
        listOf(LightSpeakers, DarkSpeakers).forEach { palette ->
            palette.all.forEachIndexed { index, style ->
                check("цифра ${index + 1}", style.onFill, style.fill, 4.5)
            }
        }
    }

    @Test
    fun speakerShapesStandOutFromBackgrounds() {
        val themes = listOf(LightColors to LightSpeakers, DarkColors to DarkSpeakers)
        themes.forEach { (colors, palette) ->
            palette.all.forEachIndexed { index, style ->
                check("форма ${index + 1}/фон", style.fill, colors.surface, 3.0)
                check("форма ${index + 1}/активная строка", style.fill, colors.primaryContainer, 3.0)
            }
        }
    }

    @Test
    fun firstEightSpeakersKeepTheirOwnShapes() {
        listOf(LightSpeakers, DarkSpeakers).forEach { palette ->
            assertEquals(SpeakerShape.entries, (1..8).map { palette.style(it).shape })
            assertEquals(palette.all, (1..8).map(palette::style))
        }
    }

    @Test
    fun speakerPastPaletteKeepsColourButNotLookOfEarlierSpeaker() {
        listOf(LightSpeakers, DarkSpeakers).forEach { palette ->
            assertEquals(palette.style(1).fill, palette.style(9).fill)
            assertNotEquals(palette.style(1).shape, palette.style(9).shape)
            assertEquals(64, (1..64).map(palette::style).toSet().size)
        }
    }

    @Test
    fun speakerNumberPastAllPairsStillResolves() {
        assertEquals(LightSpeakers.style(1), LightSpeakers.style(65))
        assertEquals(LightSpeakers.style(64), LightSpeakers.style(128))
    }
}
