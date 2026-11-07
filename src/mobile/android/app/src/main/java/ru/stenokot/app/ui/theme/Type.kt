package ru.stenokot.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ru.stenokot.app.R

@OptIn(ExperimentalTextApi::class)
private fun golos(weight: Int) = Font(
    resId = R.font.golos_text,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

@OptIn(ExperimentalTextApi::class)
private fun mono(weight: Int) = Font(
    resId = R.font.jetbrains_mono,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Golos Text: основной шрифт интерфейса, встроен в приложение. */
val GolosFamily = FontFamily(golos(400), golos(500), golos(600), golos(700))

/** JetBrains Mono: таймкоды и замеры, цифры всегда одной ширины. */
val MonoFamily = FontFamily(mono(300), mono(400), mono(500))

private val centeredLine = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun style(
    family: FontFamily,
    weight: Int,
    size: Int,
    line: Int,
    tracking: TextUnit = 0.sp,
) = TextStyle(
    fontFamily = family,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = centeredLine,
)

/** Типографическая шкала. Размеры в sp, контейнеры вокруг текста только с минимальной высотой. */
@Immutable
data class StenoType(
    val display: TextStyle = style(GolosFamily, 600, 40, 46, (-0.02).em),
    val headline: TextStyle = style(GolosFamily, 600, 28, 34, (-0.01).em),
    val titleLarge: TextStyle = style(GolosFamily, 600, 22, 28),
    val titleMedium: TextStyle = style(GolosFamily, 600, 18, 24),
    val bodyLarge: TextStyle = style(GolosFamily, 400, 16, 24),
    val bodyMedium: TextStyle = style(GolosFamily, 400, 14, 20),
    val bodySmall: TextStyle = style(GolosFamily, 400, 12, 16),
    val labelLarge: TextStyle = style(GolosFamily, 600, 16, 24),
    val labelMedium: TextStyle = style(GolosFamily, 500, 14, 20),
    val labelSmall: TextStyle = style(GolosFamily, 500, 12, 16),
    val monoMedium: TextStyle = style(MonoFamily, 500, 13, 18),
    val monoSmall: TextStyle = style(MonoFamily, 400, 12, 16),
    val numeral: TextStyle = style(MonoFamily, 300, 56, 60, (-0.04).em),
    val numeralUnit: TextStyle = style(MonoFamily, 400, 24, 60),
)
