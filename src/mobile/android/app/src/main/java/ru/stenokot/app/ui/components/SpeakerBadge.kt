package ru.stenokot.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.stenokot.app.ui.theme.SpeakerShape
import ru.stenokot.app.ui.theme.StenoTheme

private const val SHAPE_BOX = 24f

/** Форма говорящего как [Shape]. Путь разбирается один раз на форму, строки его только читают. */
private class SpeakerOutline(shape: SpeakerShape) : Shape {
    private val source: Path = PathParser().parsePathString(shape.path).toPath()

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val scale = size.minDimension / SHAPE_BOX
        val scaled = Path().apply {
            addPath(source)
            transform(Matrix().apply { scale(scale, scale) })
        }
        return Outline.Generic(scaled)
    }
}

private val Outlines = SpeakerShape.entries.associateWith(::SpeakerOutline)

/**
 * Значок говорящего: форма, номер и цвет сразу, поэтому цвет никогда не работает один.
 * Размер задан в dp при обычном шрифте и растёт вместе со шрифтом системы, цифра всегда внутри.
 */
@Composable
fun SpeakerBadge(number: Int, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val style = StenoTheme.speakers.style(number)
    val density = LocalDensity.current
    val scale = density.fontScale.coerceIn(1f, 1.6f)
    val digitSize = (if (number > 9) 10 else 12).sp
    Box(
        modifier = modifier
            .size(size * scale)
            .background(style.fill, Outlines.getValue(style.shape)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            modifier = Modifier.offset(y = style.shape.digitOffsetDp.dp),
            style = StenoTheme.type.labelSmall.copy(
                fontSize = digitSize,
                lineHeight = digitSize,
                fontWeight = FontWeight.W700,
            ),
            color = style.onFill,
            maxLines = 1,
            softWrap = false,
        )
    }
}
