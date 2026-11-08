package ru.stenokot.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoTheme
import ru.stenokot.app.ui.theme.animatedFraction

/**
 * Определённая полоса прогресса, неопределённого режима нет: полоса показывает только реальную долю.
 * Заливка доезжает до нового значения плавно и читается только при отрисовке, без перекомпоновки.
 */
@Composable
fun StenoProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    description: String? = null,
    tag: String? = null,
    height: Dp = StenoSize.progressHeight,
    track: Color = StenoTheme.colors.outlineVariant,
    indicator: Color = StenoTheme.colors.primary,
) {
    val value = fraction.coerceIn(0f, 1f)
    val shown = animatedFraction(value)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .progressSemantics(value)
            .semantics { description?.let { contentDescription = it } }
            .tagged(tag),
    ) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        val fill = shown.value
        if (fill > 0f) {
            val width = (size.width * fill).coerceAtLeast(size.height)
            drawRoundRect(indicator, size = Size(width, size.height), cornerRadius = radius)
        }
    }
}
