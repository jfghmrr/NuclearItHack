package ru.stenokot.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

/** Заголовок секции. Необязательный [count] показывает, сколько строк внутри. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    tag: String? = null,
) {
    val type = StenoTheme.type
    val colors = StenoTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .padding(top = StenoSpace.m, bottom = StenoSpace.xs)
            .semantics { heading() }
            .tagged(tag),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
    ) {
        Text(title, Modifier.weight(1f, fill = false), style = type.titleMedium, color = colors.onSurface)
        count?.let { Text(it.toString(), style = type.monoMedium, color = colors.onSurfaceVariant) }
    }
}
