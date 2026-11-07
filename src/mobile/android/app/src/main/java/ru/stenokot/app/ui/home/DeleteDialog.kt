package ru.stenokot.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import ru.stenokot.app.R
import ru.stenokot.app.domain.JobSummaryUi
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.BannerTone
import ru.stenokot.app.ui.components.DangerAction
import ru.stenokot.app.ui.components.PrimaryAction
import ru.stenokot.app.ui.components.StatusBanner
import ru.stenokot.app.ui.components.StenoDialog
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.Stage

/** Контрактный файл уже лежит в папке Download: он появляется до старта языковой модели. */
fun hasExportedJson(state: JobState): Boolean = when (state) {
    is JobState.TranscriptReady, JobState.WaitingModel, is JobState.Done -> true
    is JobState.Running -> state.stage == Stage.LLM
    is JobState.Paused -> state.stage == Stage.LLM
    is JobState.Interrupted -> state.resumeFrom == Stage.LLM
    else -> false
}

/**
 * Подтверждение удаления, в автоматическом проходе оно не встречается. Опубликованный файл JSON
 * уходит только по отдельной отметке, и тогда предупреждение и кнопка говорят об этом прямо.
 * Диалог того же вида, что остановка обработки и удаление записи с микрофона.
 */
@Composable
fun DeleteDialog(
    job: JobSummaryUi,
    onConfirm: (removeExported: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val exported = hasExportedJson(job.state)
    var removeExported by rememberSaveable(job.id) { mutableStateOf(false) }
    StenoDialog(
        title = stringResource(R.string.scr01_delete_title),
        onDismiss = onDismiss,
        icon = R.drawable.ic_delete,
        content = {
            Column(
                modifier = Modifier.padding(top = StenoSpace.m),
                verticalArrangement = Arrangement.spacedBy(StenoSpace.s),
            ) {
                Text(
                    text = job.title,
                    style = type.labelMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                Text(
                    text = stringResource(if (exported) R.string.scr01_delete_body else R.string.scr01_delete_body_no_json),
                    style = type.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                if (exported) {
                    if (!removeExported) {
                        Text(
                            text = stringResource(R.string.scr01_delete_keeps_json),
                            style = type.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = StenoSize.touchTarget)
                            .clickable(role = Role.Checkbox) { removeExported = !removeExported }
                            .testTag(Tags.DELETE_EXPORTED),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = removeExported, onCheckedChange = null)
                        Spacer(Modifier.width(StenoSpace.m))
                        Text(stringResource(R.string.scr01_delete_exported), style = type.labelMedium, color = colors.onSurface)
                    }
                    if (removeExported) {
                        StatusBanner(
                            tone = BannerTone.ERROR,
                            icon = R.drawable.ic_warning,
                            body = stringResource(R.string.scr01_delete_exported_warning),
                            polite = true,
                        )
                    }
                }
            }
        },
    ) {
        PrimaryAction(
            text = stringResource(R.string.scr01_delete_dismiss),
            onClick = onDismiss,
            tag = Tags.DELETE_DISMISS,
        )
        DangerAction(
            text = stringResource(
                if (removeExported) R.string.scr01_delete_confirm_exported else R.string.scr01_delete_confirm,
            ),
            onClick = { onConfirm(removeExported) },
            tag = Tags.DELETE_CONFIRM,
        )
    }
}
