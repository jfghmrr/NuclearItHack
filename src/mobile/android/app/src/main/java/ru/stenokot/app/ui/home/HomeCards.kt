package ru.stenokot.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import ru.stenokot.app.R
import ru.stenokot.app.domain.AudioFileUi
import ru.stenokot.app.domain.JobSummaryUi
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.FileCard
import ru.stenokot.app.ui.components.JobStatusLabel
import ru.stenokot.app.ui.components.PrimaryAction
import ru.stenokot.app.ui.components.QuietAction
import ru.stenokot.app.ui.components.StenoIcon
import ru.stenokot.app.ui.components.StenoProgressBar
import ru.stenokot.app.ui.components.spokenAs
import ru.stenokot.app.ui.components.tagged
import ru.stenokot.app.ui.format.JobBadge
import ru.stenokot.app.ui.format.formatMib
import ru.stenokot.app.ui.format.formatPercent
import ru.stenokot.app.ui.format.formatPreflight
import ru.stenokot.app.ui.format.formatRecordDate
import ru.stenokot.app.ui.format.formatTimecode
import ru.stenokot.app.ui.format.jobBadge
import ru.stenokot.app.ui.format.protocolLabel
import ru.stenokot.app.ui.format.speakersLabel
import ru.stenokot.app.ui.format.userStageAction
import ru.stenokot.app.ui.format.userStageLabel
import ru.stenokot.app.ui.format.userStageOf
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme
import ru.stenokot.app.ui.theme.animatedFraction
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.model.PreflightInfo
import ru.stenokot.core.model.Stage

private const val META_DOT = " · "
private val RecordShape = RoundedCornerShape(StenoRadius.record)
private val CardGap = 10.dp
private val TrailingEnd = 44.dp
private val HeadFooterGap = 20.dp

/** Действия главного экрана. Один объект на экран, чтобы строки списка не пересоздавали лямбды. */
@Stable
internal class HomeCallbacks(
    val onImport: () -> Unit,
    val onImportAll: () -> Unit,
    val onModels: () -> Unit,
    val onSettings: () -> Unit,
    val onAudio: (AudioFileUi) -> Unit,
    val onJob: (JobSummaryUi) -> Unit,
    val onStart: (String) -> Unit,
    val onResume: (String) -> Unit,
    val onDelete: (JobSummaryUi) -> Unit,
    val onPermission: () -> Unit,
    val onRecord: () -> Unit,
    val onMicNoticeDismiss: () -> Unit,
)

/** Как выглядит подложка карточки. */
private enum class CardTone { PLAIN, ACTIVE, WARNING }

/** Стадия и доля задачи для карточки. Доли нет там, где конвейер её не считает. */
private data class StageLine(val action: String, val stage: String, val fraction: Float?)

private fun stageLine(state: JobState): StageLine? = when (state) {
    is JobState.Running -> {
        val user = userStageOf(state.stage)
        val fraction = state.fraction.takeUnless { state.stage == Stage.MERGE || state.stage == Stage.VALIDATE }
        StageLine(userStageAction(user), userStageLabel(user), fraction)
    }
    is JobState.Paused -> {
        val user = userStageOf(state.stage)
        StageLine(userStageAction(user), userStageLabel(user), null)
    }
    else -> null
}

/** Строка данных записи: длительность и число говорящих, если они известны. */
private fun recordMeta(job: JobSummaryUi): String =
    listOfNotNull(job.durationMs?.let(::formatTimecode), job.speakers?.let(::speakersLabel)).joinToString(META_DOT)

/**
 * Рамка карточки. Нажимаемая часть и действия разнесены: TalkBack читает карточку одной фразой,
 * а кнопки внутри остаются отдельными целями. [trailing] лежит поверх угла, [actions] под содержимым.
 */
@Composable
private fun RecordFrame(
    tone: CardTone,
    tag: String?,
    description: String,
    title: String,
    onClick: () -> Unit,
    trailing: (@Composable BoxScope.() -> Unit)? = null,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = StenoTheme.colors
    val background = when (tone) {
        CardTone.PLAIN -> colors.surface
        CardTone.ACTIVE -> colors.primaryContainer
        CardTone.WARNING -> colors.warningContainer
    }
    Column(
        modifier = Modifier
            .padding(horizontal = LocalWindowLayout.current.gutter)
            .padding(bottom = CardGap)
            .fillMaxWidth()
            .clip(RecordShape)
            .background(background, RecordShape)
            .then(if (tone == CardTone.PLAIN) Modifier.border(StenoSize.hairline, colors.outlineVariant, RecordShape) else Modifier),
    ) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .tagged(tag)
                    .clickable(role = Role.Button, onClick = onClick)
                    .spokenAs(description, title)
                    .padding(StenoSpace.l),
                content = content,
            )
            trailing?.invoke(this)
        }
        actions?.invoke(this)
    }
}

@Composable
private fun RecordHead(job: JobSummaryUi, working: Boolean, endSpace: Boolean = false) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val large = LocalWindowLayout.current.largeText
    val meta = recordMeta(job)
    Row(
        modifier = Modifier.padding(end = if (endSpace) TrailingEnd else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.m),
    ) {
        Box(
            modifier = Modifier
                .size(StenoSize.tile)
                .background(colors.surfaceContainer, RoundedCornerShape(StenoRadius.tile)),
            contentAlignment = Alignment.Center,
        ) {
            StenoIcon(
                icon = if (working) R.drawable.ic_audio_file else R.drawable.ic_description,
                tint = colors.primary,
                size = StenoSize.icon,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StenoSpace.xs / 2)) {
            Text(
                text = job.title,
                style = type.cardTitle,
                color = colors.onSurface,
                maxLines = if (large) 2 else 1,
                overflow = if (large) TextOverflow.Ellipsis else TextOverflow.MiddleEllipsis,
            )
            if (meta.isNotEmpty()) Text(meta, style = type.meta, color = colors.onSurfaceVariant)
        }
    }
}

/** Низ карточки: дата слева, статус справа. Если места мало, статус переходит на новую строку. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordFooter(left: String, content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = HeadFooterGap),
        horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(left, style = StenoTheme.type.meta, color = StenoTheme.colors.onSurfaceVariant)
        content()
    }
}

/** Карточка записи в нужном состоянии. Всё видимое берётся из состояния задачи. */
@Composable
internal fun RecordCard(row: LibraryRow.Record, today: LocalDate, preflight: PreflightInfo?, callbacks: HomeCallbacks) {
    when (row.kind) {
        RecordKind.ACTIVE -> ActiveCard(row.job, callbacks.onJob)
        RecordKind.SELECTED -> SelectedCard(row.job, preflight, callbacks)
        RecordKind.QUEUED -> QueuedCard(row, today, callbacks.onJob)
        RecordKind.INTERRUPTED -> InterruptedCard(row.job, callbacks)
        RecordKind.HISTORY -> HistoryCard(row, today, callbacks)
    }
}

@Composable
private fun ActiveCard(job: JobSummaryUi, onOpen: (JobSummaryUi) -> Unit) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val line = stageLine(job.state)
    val percent = line?.fraction?.let { stringResource(R.string.scr01_stage_percent, formatPercent(it)) }
    val spoken = stringResource(R.string.scr01_spoken_job, job.title, line?.stage.orEmpty(), percent.orEmpty())
    RecordFrame(CardTone.ACTIVE, Tags.CARD_JOB, spoken, job.title, { onOpen(job) }) {
        RecordHead(job, working = true)
        Spacer(Modifier.height(StenoSpace.xl))
        line?.fraction?.let { fraction ->
            StenoProgressBar(fraction = fraction, modifier = Modifier.padding(bottom = StenoSpace.m))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StenoSpace.s)) {
            Text(
                text = line?.action ?: jobBadge(job.state).label,
                modifier = Modifier.weight(1f),
                style = type.meta,
                color = colors.primary,
            )
            line?.fraction?.let { StagePercentLabel(it) }
        }
    }
}

/** Процент этапа в карточке: цифра доходит до нового значения плавно, вслух читается вся карточка. */
@Composable
private fun StagePercentLabel(fraction: Float) {
    val shown by animatedFraction(fraction.coerceIn(0f, 1f))
    Text(
        text = stringResource(R.string.scr01_stage_percent, formatPercent(shown)),
        style = StenoTheme.type.meta.copy(fontWeight = FontWeight.W700),
        color = StenoTheme.colors.primary,
    )
}

@Composable
private fun SelectedCard(job: JobSummaryUi, preflight: PreflightInfo?, callbacks: HomeCallbacks) {
    val colors = StenoTheme.colors
    val gutter = LocalWindowLayout.current.gutter
    val duration = (preflight?.durationMs ?: job.durationMs)?.let(::formatTimecode)
    val size = preflight?.sizeBytes?.let(::formatMib)
    Column(
        modifier = Modifier.padding(horizontal = gutter).padding(bottom = CardGap),
        verticalArrangement = Arrangement.spacedBy(StenoSpace.s),
    ) {
        FileCard(
            name = preflight?.fileName ?: job.title,
            meta = listOfNotNull(duration, size).joinToString(META_DOT).ifEmpty { null },
            preflight = preflight?.let(::formatPreflight),
            description = stringResource(R.string.scr01_spoken_file, job.title, duration.orEmpty()) +
                preflight?.let { ". " + formatPreflight(it) }.orEmpty(),
        )
        PrimaryAction(
            text = stringResource(R.string.common_btn_start),
            onClick = { callbacks.onStart(job.id) },
            icon = R.drawable.ic_play_arrow_fill,
            tag = Tags.START,
        )
        Text(
            text = stringResource(R.string.scr01_autostart_hint),
            style = StenoTheme.type.meta,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun QueuedCard(row: LibraryRow.Record, today: LocalDate, onOpen: (JobSummaryUi) -> Unit) {
    val job = row.job
    val position = job.queuePosition ?: (row.index + 1)
    RecordFrame(
        tone = CardTone.PLAIN,
        tag = Tags.rowQueue(row.index),
        description = stringResource(R.string.scr01_spoken_queue, job.title, position),
        title = job.title,
        onClick = { onOpen(job) },
    ) {
        RecordHead(job, working = true)
        RecordFooter(formatRecordDate(job.createdAtMs, today)) {
            JobStatusLabel(jobBadge(job.state), extra = stringResource(R.string.scr01_queue_position, position))
        }
    }
}

@Composable
private fun InterruptedCard(job: JobSummaryUi, callbacks: HomeCallbacks) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val state = job.state as? JobState.Interrupted ?: return
    val title = stringResource(R.string.scr01_interrupted_title)
    val body = stringResource(R.string.scr01_interrupted_body, userStageLabel(userStageOf(state.resumeFrom)))
    RecordFrame(
        tone = CardTone.WARNING,
        tag = null,
        description = "${job.title}. $title. $body",
        title = job.title,
        onClick = { callbacks.onJob(job) },
        actions = {
            QuietAction(
                text = stringResource(R.string.common_btn_resume),
                onClick = { callbacks.onResume(job.id) },
                modifier = Modifier.padding(start = StenoSpace.s, bottom = StenoSpace.xs),
                tag = Tags.RESUME,
            )
        },
    ) {
        RecordHead(job, working = true)
        Spacer(Modifier.height(StenoSpace.m))
        Text(title, style = type.labelSmall, color = colors.onWarningContainer)
        Text(body, style = type.bodySmall, color = colors.onWarningContainer)
    }
}

@Composable
private fun HistoryCard(row: LibraryRow.Record, today: LocalDate, callbacks: HomeCallbacks) {
    val colors = StenoTheme.colors
    val job = row.job
    val date = formatRecordDate(job.createdAtMs, today)
    val badge = jobBadge(job.state)
    val protocol = protocolLabel(job.state)
    val quiet = job.state is JobState.Done && job.state.llm != LlmStatus.APPLIED
    val label = if (badge == JobBadge.READY) protocol ?: badge.label else badge.label
    val status = listOfNotNull(badge.label, protocol).joinToString(", ")
    RecordFrame(
        tone = CardTone.PLAIN,
        tag = Tags.rowHistory(row.index),
        description = stringResource(R.string.scr01_spoken_history, job.title, date, status),
        title = job.title,
        onClick = { callbacks.onJob(job) },
        trailing = {
            IconButton(
                onClick = { callbacks.onDelete(job) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = -StenoSpace.xs, y = StenoSpace.xs)
                    .testTag(Tags.rowDelete(row.index)),
            ) {
                StenoIcon(
                    icon = R.drawable.ic_delete,
                    tint = colors.outline,
                    size = StenoSize.iconMedium,
                    description = stringResource(R.string.scr01_delete),
                )
            }
        },
    ) {
        RecordHead(job, working = false, endSpace = true)
        RecordFooter(date) {
            JobStatusLabel(badge, label = label, tint = quietTint(quiet, colors.onSurfaceVariant))
        }
    }
}

private fun quietTint(quiet: Boolean, tint: Color): Color? = tint.takeIf { quiet }
