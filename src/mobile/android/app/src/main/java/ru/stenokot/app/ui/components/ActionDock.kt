package ru.stenokot.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import ru.stenokot.app.ui.layout.CenteredContent
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoTheme

/**
 * Закреплённая панель действий: линия сверху отделяет её от прокручиваемого содержимого.
 * В узкой панели двухпанельного экрана [centered] отключается, иначе кнопки уйдут от края текста.
 */
@Composable
fun ActionDock(modifier: Modifier = Modifier, centered: Boolean = true, content: @Composable () -> Unit) {
    val line = StenoTheme.colors.outlineVariant
    val rule = modifier.drawBehind {
        drawLine(line, Offset.Zero, Offset(size.width, 0f), StenoSize.hairline.toPx())
    }
    if (centered) {
        CenteredContent(rule) { content() }
    } else {
        Box(rule.fillMaxWidth()) { content() }
    }
}
