package ru.stenokot.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import ru.stenokot.app.ui.layout.CenteredContent
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoTheme

/**
 * Закреплённая панель действий: линия сверху отделяет её от прокручиваемого содержимого.
 * В узкой панели двухпанельного экрана [centered] отключается, иначе кнопки уйдут от края текста.
 */
@Composable
fun ActionDock(modifier: Modifier = Modifier, centered: Boolean = true, content: @Composable () -> Unit) {
    val line = StenoTheme.colors.outlineVariant
    val rule = modifier.background(StenoTheme.colors.background).drawBehind {
        drawLine(line, Offset.Zero, Offset(size.width, 0f), StenoSize.hairline.toPx())
    }
    if (centered) {
        CenteredContent(rule) { content() }
    } else {
        Box(rule.fillMaxWidth()) { content() }
    }
}

/** Панель действий внизу экрана со стандартными отступами макета. */
@Composable
fun BottomActions(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    ActionDock(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LocalWindowLayout.current.gutter)
                .padding(top = StenoSpace.m, bottom = StenoSpace.s),
            content = content,
        )
    }
}
