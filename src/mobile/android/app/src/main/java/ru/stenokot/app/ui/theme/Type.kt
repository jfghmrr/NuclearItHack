package ru.stenokot.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ru.stenokot.app.R

private const val TABULAR = "tnum"

/** Roboto: единственный шрифт интерфейса, встроен в приложение в трёх начертаниях. */
val RobotoFamily = FontFamily(
    Font(R.font.roboto_regular, FontWeight.Normal),
    Font(R.font.roboto_medium, FontWeight.Medium),
    Font(R.font.roboto_bold, FontWeight.Bold),
)

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
    fontFamily = RobotoFamily,
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
    val display: TextStyle = style(500, 32, 38),
    val headline: TextStyle = style(500, 28, 34),
    val sheetTitle: TextStyle = style(500, 24, 30),
    val titleLarge: TextStyle = style(500, 20, 26),
    val titleMedium: TextStyle = style(500, 16, 22),
    val cardTitle: TextStyle = style(500, 16, 22),
    val bodyLarge: TextStyle = style(400, 16, 25),
    val bodyMedium: TextStyle = style(400, 14, 21),
    val bodySmall: TextStyle = style(400, 12, 18),
    val meta: TextStyle = style(400, 12, 16),
    val labelLarge: TextStyle = style(500, 14, 20),
    val labelMedium: TextStyle = style(500, 14, 20),
    val labelSmall: TextStyle = style(500, 12, 16),
    val eyebrow: TextStyle = style(500, 12, 16, 0.08.em),
    val monoMedium: TextStyle = style(400, 13, 18, features = TABULAR),
    val monoSmall: TextStyle = style(400, 12, 16, features = TABULAR),
    val numeral: TextStyle = style(400, 34, 40, features = TABULAR),
    val metric: TextStyle = style(500, 26, 34, features = TABULAR),
    val numeralUnit: TextStyle = style(400, 15, 22),
    val timer: TextStyle = style(400, 64, 76, features = TABULAR),
)
