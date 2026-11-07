package ru.stenokot.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.format.statusSpoken
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.layout.statusLineInsets
import ru.stenokot.app.ui.theme.StenoTheme

private const val STATUS_ALPHA = 0.7f

/**
 * Постоянная строка `txt_status`. Значение является машинным кодом и не переводится:
 * автоматика читает текст, TalkBack получает русское описание. Строка есть на каждом экране,
 * но держится тихо и не спорит с содержимым.
 */
@Composable
fun StatusLine(code: String, modifier: Modifier = Modifier) {
    val colors = StenoTheme.colors
    val spoken = statusSpoken(code)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background)
            .statusLineInsets()
            .heightIn(min = 16.dp)
            .padding(horizontal = LocalWindowLayout.current.gutter),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = code,
            modifier = Modifier
                .testTag(Tags.STATUS)
                .semantics { contentDescription = spoken },
            style = StenoTheme.type.eyebrow,
            color = colors.onSurfaceVariant.copy(alpha = STATUS_ALPHA),
        )
    }
}
