package ru.stenokot.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.stenokot.app.R
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private val CardShape = RoundedCornerShape(StenoRadius.card)

/**
 * Карточка файла: имя с многоточием посередине, чтобы было видно расширение, данные записи и
 * строка предварительной проверки. Полное имя остаётся в тексте узла для TalkBack и автоматики.
 */
@Composable
fun FileCard(
    name: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    preflight: String? = null,
    description: String? = null,
) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(colors.surface, CardShape)
            .border(BorderStroke(StenoSize.hairline, colors.outlineVariant), CardShape)
            .testTag(Tags.CARD_FILE)
            .then(
                if (description != null) {
                    Modifier.spokenAs(description, name)
                } else {
                    Modifier.semantics(mergeDescendants = true) { }
                },
            )
            .padding(StenoSpace.l),
        verticalArrangement = Arrangement.spacedBy(StenoSpace.s, Alignment.CenterVertically),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StenoSpace.m),
        ) {
            StenoIcon(R.drawable.ic_audio_file, colors.onSurfaceVariant, size = StenoSize.icon)
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    modifier = Modifier.testTag(Tags.FILE_NAME),
                    style = type.labelLarge,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                meta?.let { Text(it, style = type.bodySmall, color = colors.onSurfaceVariant) }
            }
        }
        preflight?.let {
            Text(
                text = it,
                modifier = Modifier.testTag(Tags.PREFLIGHT),
                style = type.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}
