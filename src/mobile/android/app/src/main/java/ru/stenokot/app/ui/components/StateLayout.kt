package ru.stenokot.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.StenoSpace

/** Ширина текста состояния: длинные строки по центру читаются хуже. */
val StateTextWidth = 420.dp

/**
 * Колонка экрана-состояния: содержимое по центру окна, а если не помещается, то прокрутка от верха.
 * Прокрутка стоит снаружи минимальной высоты, поэтому центровка не мешает крупному шрифту.
 */
@Composable
fun CenteredState(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val gutter = LocalWindowLayout.current.gutter
    BoxWithConstraints(modifier) {
        val viewport = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewport)
                .padding(horizontal = gutter, vertical = StenoSpace.l),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(StenoSpace.s, Alignment.CenterVertically),
            content = content,
        )
    }
}
