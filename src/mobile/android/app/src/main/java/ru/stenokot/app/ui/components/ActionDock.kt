package ru.stenokot.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import ru.stenokot.app.ui.layout.CenteredContent
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private const val TINY_DOCK_SHARE = 0.45f

/**
 * Закреплённая панель действий: линия сверху отделяет её от прокручиваемого содержимого.
 * В узкой панели двухпанельного экрана [centered] отключается, иначе кнопки уйдут от края текста.
 * В крошечном окне с крупным шрифтом панель не выше доли экрана и прокручивается сама: иначе кнопки
 * вытеснили бы содержимое целиком.
 */
@Composable
fun ActionDock(modifier: Modifier = Modifier, centered: Boolean = true, content: @Composable () -> Unit) {
    val layout = LocalWindowLayout.current
    val line = StenoTheme.colors.outlineVariant
    val drawn = modifier.background(StenoTheme.colors.background).drawBehind {
        drawLine(line, Offset.Zero, Offset(size.width, 0f), StenoSize.hairline.toPx())
    }
    val rule = if (layout.tiny) {
        drawn.heightIn(max = layout.heightDp.dp * TINY_DOCK_SHARE).verticalScroll(rememberScrollState())
    } else {
        drawn
    }
    if (centered) {
        CenteredContent(rule) { content() }
    } else {
        Box(rule.fillMaxWidth()) { content() }
    }
}

/** Два действия: столбиком, а в низком окне рядом, чтобы панель не съедала экран. */
@Composable
fun ActionPair(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    if (LocalWindowLayout.current.compactHeight) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            first(Modifier.weight(1f))
            second(Modifier.weight(1f))
        }
    } else {
        first(Modifier)
        second(Modifier.padding(top = StenoSpace.xs))
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
