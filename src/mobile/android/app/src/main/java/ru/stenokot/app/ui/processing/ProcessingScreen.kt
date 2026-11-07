package ru.stenokot.app.ui.processing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ru.stenokot.app.R
import ru.stenokot.app.domain.JobDetailUi
import ru.stenokot.app.domain.ProcessingUi
import ru.stenokot.app.domain.StageUi
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.ui.AppActions
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.BannerTone
import ru.stenokot.app.ui.components.FileCard
import ru.stenokot.app.ui.components.KeepScreenOn
import ru.stenokot.app.ui.components.OutlinedAction
import ru.stenokot.app.ui.components.PrimaryAction
import ru.stenokot.app.ui.components.StageRail
import ru.stenokot.app.ui.components.StatusBanner
import ru.stenokot.app.ui.components.StenoIcon
import ru.stenokot.app.ui.components.StenoProgressBar
import ru.stenokot.app.ui.components.StenoTopBar
import ru.stenokot.app.ui.components.TonalAction
import ru.stenokot.app.ui.format.formatMib
import ru.stenokot.app.ui.format.formatPercent
import ru.stenokot.app.ui.format.formatPreflight
import ru.stenokot.app.ui.format.formatRemaining
import ru.stenokot.app.ui.format.formatStageCounter
import ru.stenokot.app.ui.format.formatTimecode
import ru.stenokot.app.ui.format.percentSpoken
import ru.stenokot.app.ui.format.stageLabel
import ru.stenokot.app.ui.format.userStageLabel
import ru.stenokot.app.ui.layout.CenteredContent
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.nav.Route
import ru.stenokot.app.ui.nav.routeForJob
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme
import ru.stenokot.app.ui.theme.StenoType
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.PauseReason
import ru.stenokot.core.model.Stage

private const val HINT_VISIBLE_MS = 4_000L
private const val META_DOT = " · "

/** SCR-03. Сам уходит на результат или на ошибку, когда этого требует состояние задачи. */
@Composable
fun ProcessingRoute(jobId: String, controller: StenoController, actions: AppActions) {
    val job by remember(jobId) { controller.job(jobId) }.collectAsStateWithLifecycle()
    val detail = job
    val state = detail?.summary?.state
    LaunchedEffect(state) {
        if (state == null) return@LaunchedEffect
        val target = routeForJob(jobId, state)
        if (target !is Route.Processing) actions.navigator.replaceTop(target)
    }
    if (detail?.processing == null) {
        StenoTopBar(stringResource(R.string.scr03_title), onBack = actions.back)
        return
    }
    KeepScreenOn()
    var hintTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(hintTick) {
        if (hintTick > 0) {
            delay(HINT_VISIBLE_MS)
            hintTick = 0
        }
    }
    ProcessingScreen(
        detail = detail,
        processing = detail.processing,
        showAlreadyRunning = hintTick > 0,
        onBack = actions.back,
        onImport = actions.importAudio,
        onStart = {
            if (state == JobState.Cancelled) controller.start(jobId) else hintTick += 1
        },
        onCancel = { controller.cancel(jobId) },
    )
}

@Composable
private fun ProcessingScreen(
    detail: JobDetailUi,
    processing: ProcessingUi,
    showAlreadyRunning: Boolean,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val layout = LocalWindowLayout.current
    val colors = StenoTheme.colors
    val cancelled = detail.summary.state == JobState.Cancelled
    val state = detail.summary.state
    val paused = state is JobState.Paused && state.reason == PauseReason.THERMAL
    val pipelineStage = when (state) {
        is JobState.Running -> state.stage
        is JobState.Paused -> state.stage
        else -> null
    }
    val rail: @Composable () -> Unit = {
        StageRail(processing.stages, paused = paused) { stage ->
            ActiveStage(stage, processing.stages, pipelineStage)
        }
    }
    val file: @Composable () -> Unit = {
        ProcessingFile(detail, processing, withPreflight = !layout.short)
    }
    val status: @Composable () -> Unit = {
        if (cancelled) {
            CancelledSummary()
        } else {
            ProcessingStatus(processing, paused, showAlreadyRunning)
        }
        if (layout.short) PreflightNote(processing)
    }
    val dock: @Composable () -> Unit = {
        ProcessingDock(cancelled, processing.cancelling, onStart, onCancel, onImport)
    }
    val dockUnderPanes = layout.compactHeight
    if (layout.twoPane) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.weight(0.42f).fillMaxHeight()) {
                    StenoTopBar(stringResource(R.string.scr03_title), onBack = onBack)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = layout.gutter)
                            .padding(bottom = StenoSpace.l),
                        verticalArrangement = Arrangement.spacedBy(StenoSpace.m),
                    ) {
                        file()
                        status()
                        if (layout.tiny) dock()
                    }
                    if (!dockUnderPanes && !layout.tiny) dock()
                }
                Spacer(Modifier.width(StenoSize.hairline).fillMaxHeight().background(colors.outlineVariant))
                Column(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = layout.gutter, vertical = StenoSpace.s),
                ) {
                    rail()
                }
            }
            if (dockUnderPanes && !layout.tiny) dock()
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            StenoTopBar(stringResource(R.string.scr03_title), onBack = onBack)
            CenteredContent(Modifier.weight(1f)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = layout.gutter)
                        .padding(bottom = StenoSpace.l),
                    verticalArrangement = Arrangement.spacedBy(if (layout.short) StenoSpace.s else StenoSpace.l),
                ) {
                    file()
                    rail()
                    status()
                    if (layout.tiny) dock()
                }
            }
            if (!layout.tiny) dock()
        }
    }
}

@Composable
private fun ProcessingFile(detail: JobDetailUi, processing: ProcessingUi, withPreflight: Boolean) {
    val preflight = processing.preflight
    val duration = (preflight?.durationMs ?: detail.summary.durationMs)?.let(::formatTimecode)
    val size = preflight?.sizeBytes?.let(::formatMib)
    FileCard(
        name = preflight?.fileName ?: detail.summary.title,
        meta = listOfNotNull(duration, size).joinToString(META_DOT).ifEmpty { null },
        preflight = if (withPreflight) preflight?.let(::formatPreflight) else null,
    )
}

/** Строка проверки файла под рельсом: на коротком экране она не должна отодвигать прогресс. */
@Composable
private fun PreflightNote(processing: ProcessingUi) {
    val line = processing.preflight?.let(::formatPreflight) ?: return
    Text(
        text = line,
        modifier = Modifier.testTag(Tags.PREFLIGHT),
        style = StenoTheme.type.bodySmall,
        color = StenoTheme.colors.onSurfaceVariant,
    )
}

/** Число крупно, а знак процента мельче: так значение читается сразу и не разъезжается в строке. */
private fun percentAnnotated(percent: String, type: StenoType): AnnotatedString = buildAnnotatedString {
    val digits = percent.takeWhile(Char::isDigit)
    append(digits)
    withStyle(type.numeralUnit.toSpanStyle()) { append(percent.drop(digits.length)) }
}

/** Строки этапа без счётчика: здесь процент был бы выдумкой. */
private fun hasNoCounter(stage: StageUi): Boolean = stage.fraction == null

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveStage(stage: StageUi, stages: List<StageUi>, pipelineStage: Stage?) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val label = userStageLabel(stage.stage)
    Column(Modifier.padding(top = StenoSpace.xs), verticalArrangement = Arrangement.spacedBy(StenoSpace.s)) {
        Text(
            text = stringResource(R.string.scr03_stage_index, stages.indexOf(stage) + 1, stages.size),
            modifier = Modifier.testTag(Tags.STAGE_INDEX),
            style = type.bodySmall,
            color = colors.onSurfaceVariant,
        )
        val detailLabel = pipelineStage?.let(::stageLabel)
        if (detailLabel != null && detailLabel != label) {
            Text(detailLabel, style = type.bodyMedium, color = colors.onSurfaceVariant)
        }
        if (hasNoCounter(stage)) {
            Text(
                text = stringResource(R.string.scr03_no_counter),
                style = type.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        } else {
            val fraction = stage.fraction ?: 0f
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                itemVerticalAlignment = Alignment.Bottom,
            ) {
                val percent = formatPercent(fraction)
                Text(
                    text = remember(percent, type) { percentAnnotated(percent, type) },
                    modifier = Modifier.testTag(Tags.STAGE_PERCENT),
                    style = type.numeral,
                    color = colors.onSurface,
                    maxLines = 1,
                )
                val done = stage.counterDone
                val total = stage.counterTotal
                if (done != null && total != null && pipelineStage != null) {
                    Text(
                        text = formatStageCounter(pipelineStage, done, total),
                        modifier = Modifier
                            .padding(bottom = StenoSpace.s)
                            .testTag(Tags.STAGE_COUNTER),
                        style = type.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            StenoProgressBar(
                fraction = fraction,
                description = stringResource(R.string.scr03_progress_spoken, label, percentSpoken(fraction)),
                tag = Tags.STAGE_PROGRESS,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProcessingStatus(processing: ProcessingUi, paused: Boolean, showAlreadyRunning: Boolean) {
    val type = StenoTheme.type
    val colors = StenoTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(StenoSpace.s)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.scr03_elapsed, formatTimecode(processing.elapsedMs)),
                modifier = Modifier.testTag(Tags.ELAPSED),
                style = type.monoMedium,
                color = colors.onSurface,
            )
            formatRemaining(processing.remainingMinMs, processing.remainingMaxMs)?.let {
                Text(
                    text = it,
                    modifier = Modifier.testTag(Tags.REMAINING),
                    style = type.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        ProcessingHint(processing, paused, showAlreadyRunning)
    }
}

@Composable
private fun ProcessingHint(processing: ProcessingUi, paused: Boolean, showAlreadyRunning: Boolean) {
    val type = StenoTheme.type
    val colors = StenoTheme.colors
    when {
        paused -> StatusBanner(
            tone = BannerTone.WARNING,
            icon = R.drawable.ic_device_thermostat,
            title = stringResource(R.string.scr03_paused_title),
            body = stringResource(R.string.scr03_paused_body),
            tag = Tags.STATUS_HINT,
            polite = true,
        )
        processing.lowMemory && !processing.cancelling && !showAlreadyRunning -> StatusBanner(
            tone = BannerTone.WARNING,
            icon = R.drawable.ic_memory,
            body = stringResource(R.string.scr03_hint_low_memory),
            tag = Tags.STATUS_HINT,
            polite = true,
        )
        else -> {
            val hint = when {
                processing.cancelling -> R.string.scr03_cancelling
                showAlreadyRunning -> R.string.scr03_hint_already_running
                processing.powerSave -> R.string.scr03_hint_power_save
                processing.resumed -> R.string.scr03_hint_resumed
                else -> R.string.scr03_hint_keep_screen
            }
            Text(
                text = stringResource(hint),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag(Tags.STATUS_HINT),
                style = type.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CancelledSummary() {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    Row(
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.m),
        verticalAlignment = Alignment.Top,
    ) {
        StenoIcon(R.drawable.ic_cancel, colors.onSurfaceVariant, size = StenoSize.iconLarge)
        Column(verticalArrangement = Arrangement.spacedBy(StenoSpace.xs)) {
            Text(
                text = stringResource(R.string.scr03_cancelled_title),
                modifier = Modifier.semantics { heading() },
                style = type.titleMedium,
                color = colors.onSurface,
            )
            Text(
                text = stringResource(R.string.scr03_cancelled_body),
                style = type.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProcessingDock(
    cancelled: Boolean,
    cancelling: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onImport: () -> Unit,
) {
    val layout = LocalWindowLayout.current
    val colors = StenoTheme.colors
    val start = stringResource(R.string.common_btn_start)
    val sideBySide = !layout.twoPane || layout.compactHeight
    val inRow = sideBySide && !layout.compactWidth && !layout.largeText
    val first: @Composable (Modifier) -> Unit = { modifier ->
        if (cancelled) {
            PrimaryAction(start, onStart, modifier, icon = R.drawable.ic_play_arrow_fill, tag = Tags.START)
        } else {
            TonalAction(start, onStart, modifier, icon = R.drawable.ic_play_arrow_fill, tag = Tags.START)
        }
    }
    val second: @Composable (Modifier) -> Unit = { modifier ->
        if (cancelled) {
            OutlinedAction(
                text = stringResource(R.string.common_btn_import),
                onClick = onImport,
                modifier = modifier,
                icon = R.drawable.ic_upload_file,
                tag = Tags.IMPORT,
            )
        } else {
            OutlinedAction(
                text = stringResource(R.string.common_btn_cancel),
                onClick = onCancel,
                modifier = modifier,
                enabled = !cancelling,
                tag = Tags.CANCEL,
            )
        }
    }
    val actions: @Composable (Modifier) -> Unit = { modifier ->
        if (inRow || layout.width == WindowWidthSizeClass.Expanded) {
            Row(modifier, horizontalArrangement = Arrangement.spacedBy(StenoSpace.s)) {
                first(Modifier.weight(1.55f))
                second(Modifier.weight(1f))
            }
        } else {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(StenoSpace.s)) {
                first(Modifier)
                second(Modifier)
            }
        }
    }
    if (layout.tiny) {
        actions(Modifier)
        return
    }
    CenteredContent(
        Modifier.drawBehind {
            drawLine(colors.outlineVariant, Offset.Zero, Offset(size.width, 0f), StenoSize.hairline.toPx())
        },
    ) {
        actions(Modifier.padding(horizontal = layout.gutter, vertical = StenoSpace.m))
    }
}
