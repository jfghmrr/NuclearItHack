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

private const val TABULAR = "tnum"

@OptIn(ExperimentalTextApi::class)
private fun noto(weight: Int) = Font(
    resId = R.font.noto_sans,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Noto Sans: единственный шрифт интерфейса, встроен в приложение. */
val NotoFamily = FontFamily(noto(400), noto(500), noto(600), noto(700))

private val centeredLine = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun style(
    weight: Int,
    size: Int,
    line: Int,
    tracking: TextUnit = 0.sp,
    features: String? = null,
) = TextStyle(
    fontFamily = NotoFamily,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking,
    fontFeatureSettings = features,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = centeredLine,
)

/**
 * Типографическая шкала макета. Размеры в sp, контейнеры вокруг текста только с минимальной высотой.
 * Таймкоды и замеры набраны тем же шрифтом с цифрами одной ширины.
 */
@Immutable
data class StenoType(
    val display: TextStyle = style(700, 34, 42),
    val headline: TextStyle = style(700, 26, 34),
    val titleLarge: TextStyle = style(700, 18, 24),
    val titleMedium: TextStyle = style(700, 16, 22),
    val cardTitle: TextStyle = style(700, 15, 21),
    val bodyLarge: TextStyle = style(400, 15, 25),
    val bodyMedium: TextStyle = style(400, 14, 23),
    val bodySmall: TextStyle = style(400, 12, 20),
    val meta: TextStyle = style(400, 11, 18),
    val labelLarge: TextStyle = style(700, 14, 20),
    val labelMedium: TextStyle = style(600, 14, 21),
    val labelSmall: TextStyle = style(700, 12, 16),
    val eyebrow: TextStyle = style(700, 10, 14, 0.04.em),
    val monoMedium: TextStyle = style(400, 13, 18, features = TABULAR),
    val monoSmall: TextStyle = style(400, 12, 16, features = TABULAR),
    val numeral: TextStyle = style(400, 34, 40, features = TABULAR),
    val metric: TextStyle = style(700, 26, 34, features = TABULAR),
    val numeralUnit: TextStyle = style(400, 15, 22),
    val timer: TextStyle = style(400, 67, 80, features = TABULAR),
)
