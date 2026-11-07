package ru.stenokot.app.ui.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import ru.stenokot.app.R
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.PrimaryAction
import ru.stenokot.app.ui.components.SheetHeader
import ru.stenokot.app.ui.components.SpeakerBadge
import ru.stenokot.app.ui.components.StenoIcon
import ru.stenokot.app.ui.components.StenoSheet
import ru.stenokot.app.ui.format.utterancesLabel
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

/** Строка выбора: значок слева, две строки текста и переключатель справа. */
@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    tag: String,
    onSelect: () -> Unit,
    leading: @Composable () -> Unit,
) {
    val colors = StenoTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = StenoSize.actionHeight)
            .testTag(tag)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = StenoSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.l),
    ) {
        leading()
        Column(Modifier.weight(1f)) {
            Text(title, style = StenoTheme.type.bodyLarge, color = colors.onSurface)
            Text(subtitle, style = StenoTheme.type.bodySmall, color = colors.onSurfaceVariant)
        }
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = colors.primary, unselectedColor = colors.outline),
        )
    }
}

/**
 * Выбор говорящего. Подписанный список не зависит от числа участников: он прокручивается, а кнопка
 * «Применить» остаётся на месте. Выбор вступает в силу только по ней. [current] равно null для всех.
 */
@Composable
internal fun SpeakerSheet(
    speakers: List<Int>,
    replies: Map<Int, Int>,
    total: Int,
    current: Int?,
    onApply: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = StenoTheme.colors
    val gutter = LocalWindowLayout.current.gutter
    var chosen by remember(current) { mutableStateOf(current) }
    val allCount = stringResource(R.string.scr04_speakers_all_count, utterancesLabel(total))
    StenoSheet(onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(Tags.SPEAKER_SHEET)
                .navigationBarsPadding()
                .padding(horizontal = gutter)
                .padding(bottom = StenoSpace.l),
        ) {
            SheetHeader(stringResource(R.string.scr04_speakers_title), onDismiss)
            Text(
                text = stringResource(R.string.scr04_speakers_body),
                modifier = Modifier.padding(bottom = StenoSpace.s),
                style = StenoTheme.type.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            LazyColumn(Modifier.weight(1f, fill = false).selectableGroup()) {
                item(key = "all") {
                    ChoiceRow(
                        title = stringResource(R.string.scr04_speakers_all),
                        subtitle = allCount,
                        selected = chosen == null,
                        tag = Tags.SPEAKER_ALL,
                        onSelect = { chosen = null },
                    ) {
                        Box(Modifier.size(StenoSize.touchTarget - StenoSpace.xs), Alignment.Center) {
                            StenoIcon(R.drawable.ic_users, colors.onSurface, size = StenoSize.icon)
                        }
                    }
                    HorizontalDivider(thickness = StenoSize.hairline, color = colors.outlineVariant)
                }
                items(speakers, key = { it }) { number ->
                    ChoiceRow(
                        title = stringResource(R.string.common_speaker, number),
                        subtitle = utterancesLabel(replies[number] ?: 0),
                        selected = chosen == number,
                        tag = Tags.chipSpeaker(number),
                        onSelect = { chosen = number },
                    ) {
                        Box(Modifier.size(StenoSize.touchTarget - StenoSpace.xs), Alignment.Center) {
                            SpeakerBadge(number)
                        }
                    }
                    HorizontalDivider(thickness = StenoSize.hairline, color = colors.outlineVariant)
                }
            }
            Spacer(Modifier.height(StenoSpace.l))
            PrimaryAction(
                text = stringResource(R.string.scr04_speakers_apply),
                onClick = { onApply(chosen) },
                tag = Tags.SPEAKER_APPLY,
            )
        }
    }
}
