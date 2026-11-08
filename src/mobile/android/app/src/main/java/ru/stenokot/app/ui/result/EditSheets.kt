package ru.stenokot.app.ui.result

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ru.stenokot.app.R
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.ActionSheet
import ru.stenokot.app.ui.components.ActionSheetRow
import ru.stenokot.app.ui.components.GhostAction
import ru.stenokot.app.ui.components.PrimaryAction
import ru.stenokot.app.ui.components.SheetHeader
import ru.stenokot.app.ui.components.SpeakerBadge
import ru.stenokot.app.ui.components.StenoDialog
import ru.stenokot.app.ui.components.StenoSheet
import ru.stenokot.app.ui.format.utterancesLabel
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private const val FIELD_MIN_LINES = 3
private const val FIELD_MAX_LINES = 8

/** Шаг ручной правки реплики. */
internal enum class EditStep { ACTIONS, TEXT, SPEAKER }

/**
 * Действия над одной репликой. «Вернуть исходный» видно только у исправленной реплики. Сменить
 * говорящего можно, только если в записи их больше одного.
 */
@Composable
internal fun UtteranceActionsSheet(
    row: TranscriptRowUi,
    canChangeSpeaker: Boolean,
    onText: () -> Unit,
    onSpeaker: () -> Unit,
    onRevert: () -> Unit,
    onDismiss: () -> Unit,
) {
    ActionSheet(title = stringResource(R.string.scr04_edit_sheet_title), onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().testTag(Tags.EDIT_SHEET)) {
            Text(
                text = stringResource(R.string.common_speaker, row.speaker) + " · " + row.startLabel,
                modifier = Modifier.padding(bottom = StenoSpace.s),
                style = StenoTheme.type.meta,
                color = StenoTheme.colors.onSurfaceVariant,
            )
            ActionSheetRow(
                title = stringResource(R.string.scr04_edit_text),
                subtitle = stringResource(R.string.scr04_edit_text_hint),
                icon = R.drawable.ic_description,
                onClick = onText,
                tag = Tags.EDIT_TEXT,
            )
            if (canChangeSpeaker) {
                ActionSheetRow(
                    title = stringResource(R.string.scr04_edit_speaker),
                    subtitle = stringResource(R.string.scr04_edit_speaker_hint),
                    icon = R.drawable.ic_users,
                    onClick = onSpeaker,
                    tag = Tags.EDIT_SPEAKER,
                )
            }
            if (row.edited) {
                ActionSheetRow(
                    title = stringResource(R.string.scr04_edit_revert),
                    subtitle = stringResource(R.string.scr04_edit_revert_hint),
                    icon = R.drawable.ic_replay,
                    onClick = onRevert,
                    tag = Tags.EDIT_REVERT,
                )
            }
        }
    }
}

/** Правка текста. Пустой текст сохранить нельзя: контракт его не пропустит. */
@Composable
internal fun EditTextDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    var value by rememberSaveable(initial) { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val blank = value.isBlank()
    val label = stringResource(R.string.scr04_edit_field)
    LaunchedEffect(Unit) { focus.requestFocus() }
    StenoDialog(
        title = stringResource(R.string.scr04_edit_text),
        onDismiss = onDismiss,
        content = {
            Box(
                modifier = Modifier
                    .padding(top = StenoSpace.m)
                    .fillMaxWidth()
                    .heightIn(min = StenoSize.touchTarget)
                    .background(colors.surfaceContainer, RoundedCornerShape(StenoRadius.field))
                    .padding(StenoSpace.m),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .semantics { contentDescription = label }
                        .testTag(Tags.EDIT_FIELD),
                    textStyle = type.bodyLarge.copy(color = colors.onSurface),
                    minLines = FIELD_MIN_LINES,
                    maxLines = FIELD_MAX_LINES,
                    cursorBrush = SolidColor(colors.primary),
                )
            }
            if (blank) {
                Text(
                    text = stringResource(R.string.scr04_edit_empty),
                    modifier = Modifier.padding(top = StenoSpace.s),
                    style = type.bodySmall,
                    color = colors.error,
                )
            }
        },
        actions = {
            PrimaryAction(
                text = stringResource(R.string.scr04_edit_save),
                onClick = { onSave(value) },
                enabled = !blank && value != initial,
                tag = Tags.EDIT_SAVE,
            )
            GhostAction(stringResource(R.string.common_btn_cancel), onDismiss, tag = Tags.EDIT_CANCEL)
        },
    )
}

/**
 * Выбор говорящего для реплики из тех, кто уже есть в записи. Выбор вступает в силу только по кнопке,
 * как в фильтре говорящих.
 */
@Composable
internal fun EditSpeakerSheet(
    speakers: List<Int>,
    replies: Map<Int, Int>,
    current: Int,
    onApply: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = StenoTheme.colors
    var chosen by remember(current) { mutableStateOf(current) }
    StenoSheet(onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(Tags.EDIT_SPEAKER_SHEET)
                .navigationBarsPadding()
                .padding(horizontal = LocalWindowLayout.current.gutter)
                .padding(bottom = StenoSpace.l),
        ) {
            SheetHeader(stringResource(R.string.scr04_edit_speaker), onDismiss)
            Text(
                text = stringResource(R.string.scr04_edit_speaker_body),
                modifier = Modifier.padding(bottom = StenoSpace.s),
                style = StenoTheme.type.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            LazyColumn(Modifier.weight(1f, fill = false).selectableGroup()) {
                items(speakers, key = { it }) { number ->
                    ChoiceRow(
                        title = stringResource(R.string.common_speaker, number),
                        subtitle = utterancesLabel(replies[number] ?: 0),
                        selected = chosen == number,
                        tag = Tags.editSpeaker(number),
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
                text = stringResource(R.string.scr04_edit_save),
                onClick = { onApply(chosen) },
                enabled = chosen != current,
                tag = Tags.EDIT_SPEAKER_APPLY,
            )
        }
    }
}
