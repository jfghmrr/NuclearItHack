package ru.stenokot.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

/** Пустое состояние: маскот, короткий текст и слот [content] с единственным действием. */
@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    mascot: MascotKind = MascotKind.EMPTY,
    mascotSize: Dp = StenoSize.mascot,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = StenoSpace.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(StenoSpace.l),
    ) {
        Mascot(mascot, size = mascotSize)
        Text(
            text = text,
            modifier = Modifier.widthIn(max = 320.dp),
            style = StenoTheme.type.bodyLarge,
            color = StenoTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        content?.invoke(this)
    }
}
