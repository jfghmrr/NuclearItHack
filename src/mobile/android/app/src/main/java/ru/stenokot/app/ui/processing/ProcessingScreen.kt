package ru.stenokot.app.ui.processing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ru.stenokot.app.R
import ru.stenokot.app.domain.ExportKind
import ru.stenokot.app.domain.JobDetailUi
import ru.stenokot.app.domain.ProcessingUi
import ru.stenokot.app.domain.StageStatus
import ru.stenokot.app.domain.StageUi
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.ui.AppActions
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.ActionSheet
import ru.stenokot.app.ui.components.ActionSheetRow
import ru.stenokot.app.ui.components.BannerTone
import ru.stenokot.app.ui.components.BottomActions
import ru.stenokot.app.ui.components.DangerAction
import ru.stenokot.app.ui.components.GhostAction
import ru.stenokot.app.ui.components.KeepScreenOn
import ru.stenokot.app.ui.components.MascotKind
import ru.stenokot.app.ui.components.MascotPlate
import ru.stenokot.app.ui.components.OutlinedAction
import ru.stenokot.app.ui.components.PrimaryAction
import ru.stenokot.app.ui.components.QuietAction
import ru.stenokot.app.ui.components.StageRail
import ru.stenokot.app.ui.components.StatusBanner
import ru.stenokot.app.ui.components.StenoDialog
import ru.stenokot.app.ui.components.StenoIcon
import ru.stenokot.app.ui.components.StenoProgressBar
import ru.stenokot.app.ui.components.StenoTopBar
import ru.stenokot.app.ui.components.spokenAs
import ru.stenokot.app.ui.format.formatPercent
import ru.stenokot.app.ui.format.formatPreflight
import ru.stenokot.app.ui.format.formatRemaining
import ru.stenokot.app.ui.format.formatStageCounter
import ru.stenokot.app.ui.format.formatTimecode
import ru.stenokot.app.ui.format.percentSpoken
import ru.stenokot.app.ui.format.percentValue
import ru.stenokot.app.ui.format.speakersLabel
import ru.stenokot.app.ui.format.stageLabel
import ru.stenokot.app.ui.format.userStageAction
import ru.stenokot.app.ui.format.userStageLabel
import ru.stenokot.app.ui.layout.CenteredContent
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.nav.Route
import ru.stenokot.app.ui.nav.ResultTab
import ru.stenokot.app.ui.nav.routeForJob
import ru.stenokot.app.ui.theme.EnterMotion
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.PauseReason
import ru.stenokot.core.model.Stage

private const val HINT_VISIBLE_MS = 4_000L
private const val META_DOT = " · "
private const val MIME_JSON = "application/json"
private val StageShape = RoundedCornerShape(StenoRadius.stage)
private val DoneTextWidth = 310.dp

/** Обработка закончена, остался только показ: стенограмма сохранена, протокол идёт сам. */
private fun JobState.hasResult(): Boolean = routeForJob("", this) is Route.Result

/** В записи нет речи: у такого итога свой экран в результате, а не «Всё по полочкам». */
private fun JobState.isSilent(): Boolean = this is JobState.Done && warnings.contains("AUDIO_EMPTY")

/**
 * SCR-03. Показывает ход обработки, а на готовой записи её итог. Сам уходит на ошибку, когда этого
 * требует состояние задачи. Результат открывается кнопкой: итог не прячется автоматическим переходом.
 */
@Composable
fun ProcessingRoute(jobId: String, controller: StenoController, actions: AppActions) {
    val job by remember(jobId) { controller.job(jobId) }.collectAsStateWithLifecycle()
    val detail = job
    val state = detail?.summary?.state
    LaunchedEffect(state) {
        if (state == null) return@LaunchedEffect
        val target = routeForJob(jobId, state)
        when {
            target is Route.Failure -> actions.navigator.replaceTop(target)
            state.isSilent() -> actions.navigator.replaceTop(target)
        }
    }
    if (detail != null && state != null && state.hasResult() && !state.isSilent()) {
        DoneScreen(detail, controller, actions)
        return
    }
    if (detail?.processing == null) {
        Column(Modifier.fillMaxSize()) {
            StenoTopBar(stringResource(R.string.scr03_title), onBack = actions.back)
        }
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
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var stopping by rememberSaveable { mutableStateOf(false) }
    ProcessingScreen(
        detail = detail,
        processing = detail.processing,
        showAlreadyRunning = hintTick > 0,
        onBack = actions.back,
        onImport = actions.importAudio,
        onStart = {
            if (state == JobState.Cancelled) controller.start(jobId) else hintTick += 1
        },
        onMenu = { menuOpen = true },
    )
    if (menuOpen) {
        ActionSheet(title = stringResource(R.string.scr03_title), onDismiss = { menuOpen = false }) {
            ActionSheetRow(
                title = stringResource(R.string.scr03_menu_diagnostics),
                subtitle = stringResource(R.string.scr03_menu_diagnostics_hint),
                icon = R.drawable.ic_chart,
                onClick = {
                    menuOpen = false
                    actions.navigator.push(Route.Verification)
                },
            )
            ActionSheetRow(
                title = stringResource(R.string.scr03_menu_stop),
                subtitle = stringResource(R.string.scr03_menu_stop_hint),
                icon = R.drawable.ic_stop_fill,
                enabled = state != JobState.Cancelled && !detail.processing.cancelling,
                tag = Tags.CANCEL,
                onClick = {
                    menuOpen = false
                    stopping = true
                },
            )
        }
    }
    if (stopping) {
        StenoDialog(
            title = stringResource(R.string.scr03_stop_title),
            onDismiss = { stopping = false },
            icon = R.drawable.ic_stop_fill,
            body = stringResource(R.string.scr03_stop_body),
        ) {
            PrimaryAction(
                text = stringResource(R.string.scr03_stop_dismiss),
                onClick = { stopping = false },
                tag = Tags.CANCEL_DISMISS,
            )
            DangerAction(
                text = stringResource(R.string.scr03_stop_confirm),
                onClick = {
                    stopping = false
                    controller.cancel(jobId)
                },
                tag = Tags.CANCEL_CONFIRM,
            )
        }
    }
}

@Composable
private fun ProcessingScreen(
    detail: JobDetailUi,
    processing: ProcessingUi,
    showAlreadyRunning: Boolean,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onStart: () -> Unit,
    onMenu: () -> Unit,
) {
    val layout = LocalWindowLayout.current
    val colors = StenoTheme.colors
    val state = detail.summary.state
    val cancelled = state == JobState.Cancelled
    val paused = state is JobState.Paused && state.reason == PauseReason.THERMAL
    val pipelineStage = when (state) {
        is JobState.Running -> state.stage
        is JobState.Paused -> state.stage
        else -> null
    }
    val active = processing.stages.firstOrNull { it.status == StageStatus.ACTIVE }
    val bar: @Composable () -> Unit = {
        StenoTopBar(
            title = stringResource(R.string.scr03_title),
            onBack = onBack,
            actions = {
                IconButton(onClick = onMenu, modifier = Modifier.testTag(Tags.PROCESSING_MENU)) {
                    StenoIcon(
                        icon = R.drawable.ic_more,
                        tint = colors.onSurface,
                        size = StenoSize.icon,
                        description = stringResource(R.string.scr03_menu),
                    )
                }
            },
        )
    }
    val hero: @Composable () -> Unit = { ProcessingHero(detail, processing, cancelled) }
    val work: @Composable () -> Unit = {
        if (cancelled) {
            CancelledSummary()
        } else {
            active?.let { StageCard(it, processing, pipelineStage) }
            ProcessingBanner(processing, paused, showAlreadyRunning)
        }
        StageRail(processing.stages, paused = paused)
        if (!cancelled) ProcessingNote(processing, paused, showAlreadyRunning)
    }
    val dock: @Composable () -> Unit = { ProcessingDock(cancelled, onBack, onStart, onImport) }
    if (layout.twoPane) {
        Column(Modifier.fillMaxSize()) {
            bar()
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = layout.gutter)
                        .padding(bottom = StenoSpace.l),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { hero() }
                Spacer(Modifier.width(StenoSize.hairline).fillMaxHeight().background(colors.outlineVariant))
                Column(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = layout.gutter)
                        .padding(bottom = StenoSpace.l),
                    verticalArrangement = Arrangement.spacedBy(StenoSpace.m),
                ) { work() }
            }
            dock()
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            bar()
            CenteredContent(Modifier.weight(1f)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = layout.gutter)
                        .padding(bottom = StenoSpace.m),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(StenoSpace.m),
                ) {
                    hero()
                    work()
                }
            }
            dock()
        }
    }
}

/** Кот, заголовок и сведения о файле. На коротком экране кот уступает место прогрессу. */
@Composable
private fun ProcessingHero(detail: JobDetailUi, processing: ProcessingUi, cancelled: Boolean) {
    val layout = LocalWindowLayout.current
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val preflight = processing.preflight
    val name = preflight?.fileName ?: detail.summary.title
    val duration = (preflight?.durationMs ?: detail.summary.durationMs)?.let(::formatTimecode)
    val line = listOfNotNull(name, duration).joinToString(META_DOT)
    Column(
        modifier = Modifier.fillMaxWidth().testTag(Tags.CARD_FILE),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!layout.short && !layout.largeText) {
            MascotPlate(MascotKind.WAITING, plate = StenoSize.mascotPlate * 0.8f, breathing = !cancelled)
        }
        Text(
            text = stringResource(R.string.scr03_headline),
            modifier = Modifier.padding(top = StenoSpace.s).semantics { heading() },
            style = type.headline.copy(fontSize = type.display.fontSize * 0.7f),
            color = colors.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = line,
            modifier = Modifier.padding(top = StenoSpace.s).testTag(Tags.FILE_NAME),
            style = type.bodySmall,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = preflight?.let(::formatPreflight) ?: stringResource(R.string.scr03_on_device),
            modifier = Modifier.then(if (preflight != null) Modifier.testTag(Tags.PREFLIGHT) else Modifier),
            style = type.bodySmall,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Карточка текущего этапа. Процент относится к этапу и появляется только там, где есть счётчик. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StageCard(stage: StageUi, processing: ProcessingUi, pipelineStage: Stage?) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val index = processing.stages.indexOf(stage) + 1
    val indexText = stringResource(R.string.scr03_stage_index, index, processing.stages.size)
    val label = userStageLabel(stage.stage)
    val fraction = stage.fraction
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, StageShape)
            .border(StenoSize.hairline, colors.outlineVariant, StageShape)
            .padding(horizontal = StenoSpace.l + StenoSpace.xs, vertical = StenoSpace.l),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = indexText.uppercase(),
                modifier = Modifier.weight(1f).testTag(Tags.STAGE_INDEX).spokenAs(indexText, indexText),
                style = type.eyebrow,
                color = colors.onSurfaceVariant,
            )
            Text(
                text = formatTimecode(processing.elapsedMs),
                modifier = Modifier
                    .testTag(Tags.ELAPSED)
                    .spokenAs(
                        stringResource(R.string.scr03_elapsed, formatTimecode(processing.elapsedMs)),
                        formatTimecode(processing.elapsedMs),
                    ),
                style = type.eyebrow,
                color = colors.onSurfaceVariant,
            )
        }
        Text(
            text = userStageAction(stage.stage),
            modifier = Modifier.padding(top = StenoSpace.s),
            style = type.titleMedium,
            color = colors.onSurface,
        )
        val detailLabel = pipelineStage?.let(::stageLabel)
        if (detailLabel != null && detailLabel != label) {
            Text(detailLabel, style = type.meta, color = colors.onSurfaceVariant)
        }
        if (fraction == null) {
            Text(
                text = stringResource(R.string.scr03_no_counter),
                modifier = Modifier.padding(top = StenoSpace.s),
                style = type.bodySmall,
                color = colors.onSurfaceVariant,
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = StenoSpace.m),
                horizontalArrangement = Arrangement.SpaceBetween,
                itemVerticalAlignment = Alignment.Bottom,
            ) {
                StagePercent(fraction)
                val done = stage.counterDone
                val total = stage.counterTotal
                if (done != null && total != null && pipelineStage != null) {
                    Text(
                        text = formatStageCounter(pipelineStage, done, total),
                        modifier = Modifier.padding(bottom = StenoSpace.xs).testTag(Tags.STAGE_COUNTER),
                        style = type.meta,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            StenoProgressBar(
                fraction = fraction,
                modifier = Modifier.padding(top = StenoSpace.s),
                description = stringResource(R.string.scr03_progress_spoken, label, percentSpoken(fraction)),
                tag = Tags.STAGE_PROGRESS,
            )
        }
        formatRemaining(processing.remainingMinMs, processing.remainingMaxMs)?.let {
            Text(
                text = it,
                modifier = Modifier.padding(top = StenoSpace.s).testTag(Tags.REMAINING),
                style = type.meta,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StagePercent(fraction: Float) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val value = percentValue(fraction).toString()
    val unit = stringResource(R.string.scr03_percent_unit)
    Row(
        modifier = Modifier.testTag(Tags.STAGE_PERCENT).spokenAs("${percentSpoken(fraction)} этапа", formatPercent(fraction)),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
    ) {
        Text(value, style = type.numeral, color = colors.primary, maxLines = 1)
        Text(
            text = unit,
            modifier = Modifier.padding(bottom = StenoSpace.xs / 2),
            style = type.numeralUnit,
            color = colors.primary,
            maxLines = 1,
        )
    }
}

/** Предупреждение важнее этапов и стоит над ними. Спокойная подсказка уходит под этапы. */
private fun ProcessingUi.warnsAboutMemory(showAlreadyRunning: Boolean): Boolean =
    lowMemory && !cancelling && !showAlreadyRunning

@Composable
private fun ProcessingBanner(processing: ProcessingUi, paused: Boolean, showAlreadyRunning: Boolean) {
    when {
        paused -> StatusBanner(
            tone = BannerTone.WARNING,
            icon = R.drawable.ic_device_thermostat,
            title = stringResource(R.string.scr03_paused_title),
            body = stringResource(R.string.scr03_paused_body),
            tag = Tags.STATUS_HINT,
            polite = true,
        )
        processing.warnsAboutMemory(showAlreadyRunning) -> StatusBanner(
            tone = BannerTone.WARNING,
            icon = R.drawable.ic_memory,
            body = stringResource(R.string.scr03_hint_low_memory),
            tag = Tags.STATUS_HINT,
            polite = true,
        )
    }
}

@Composable
private fun ProcessingNote(processing: ProcessingUi, paused: Boolean, showAlreadyRunning: Boolean) {
    if (paused || processing.warnsAboutMemory(showAlreadyRunning)) return
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
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(Tags.STATUS_HINT),
        style = StenoTheme.type.meta,
        color = StenoTheme.colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun CancelledSummary() {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    Row(
        modifier = Modifier.fillMaxWidth(),
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
                style = type.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/** Выход к записям и идемпотентный запуск. Запуск виден всегда: на него рассчитана автоматика. */
@Composable
private fun ProcessingDock(cancelled: Boolean, onBack: () -> Unit, onStart: () -> Unit, onImport: () -> Unit) {
    val start = stringResource(R.string.common_btn_start)
    BottomActions {
        if (cancelled) {
            PrimaryAction(start, onStart, icon = R.drawable.ic_play_arrow_fill, tag = Tags.START)
            OutlinedAction(
                text = stringResource(R.string.common_btn_import),
                onClick = onImport,
                modifier = Modifier.padding(top = StenoSpace.s),
                icon = R.drawable.ic_upload_file,
                tag = Tags.IMPORT,
            )
        } else {
            OutlinedAction(
                text = stringResource(R.string.scr03_to_library),
                onClick = onBack,
                icon = R.drawable.ic_arrow_back,
                tag = Tags.TO_LIBRARY,
            )
            QuietAction(
                text = start,
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                tag = Tags.START,
            )
        }
    }
}

/** Итог обработки: что готово, главный переход к тексту и экспорт на виду. */
@Composable
private fun DoneScreen(detail: JobDetailUi, controller: StenoController, actions: AppActions) {
    val layout = LocalWindowLayout.current
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val jobId = detail.summary.id
    val result = detail.result
    val forming = detail.summary.state !is JobState.Done
    val exportFailed = result?.output?.exportFailed == true
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_JSON)) { target ->
        if (target != null) controller.export(jobId, ExportKind.TRANSCRIPT, target)
    }
    val stem = (result?.title ?: detail.summary.title).substringBeforeLast('.')
    Column(Modifier.fillMaxSize()) {
        StenoTopBar(stringResource(R.string.scr03_done_title), onBack = actions.back)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = StenoSpace.xl, vertical = StenoSpace.l),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            MascotPlate(
                kind = MascotKind.DONE,
                plate = if (layout.short) StenoSize.mascotPlateSmall else StenoSize.mascotPlate,
                motion = EnterMotion.SUCCESS,
            )
            Text(
                text = stringResource(R.string.scr03_done_headline),
                modifier = Modifier.padding(top = StenoSpace.l).testTag(Tags.DONE_TITLE).semantics { heading() },
                style = type.headline,
                color = colors.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(if (forming) R.string.scr03_done_body_forming else R.string.scr03_done_body),
                modifier = Modifier.padding(top = StenoSpace.m).widthIn(max = DoneTextWidth),
                style = type.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            DoneFacts(detail)
            result?.output?.let { output ->
                if (exportFailed) {
                    StatusBanner(
                        tone = BannerTone.ERROR,
                        icon = R.drawable.ic_error,
                        modifier = Modifier.padding(top = StenoSpace.m),
                        body = stringResource(R.string.scr04_export_failed_title),
                        tag = Tags.EXPORT_ERROR,
                        polite = true,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.scr03_done_exported, output.path),
                        modifier = Modifier.padding(top = StenoSpace.m).testTag(Tags.OUTPUT_PATH),
                        style = type.meta,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        BottomActions {
            PrimaryAction(
                text = stringResource(R.string.scr03_done_open),
                onClick = { actions.navigator.replaceTop(Route.Result(jobId, ResultTab.TRANSCRIPT)) },
                icon = R.drawable.ic_description,
                tag = Tags.OPEN_TRANSCRIPT,
            )
            val exportText = stringResource(if (exportFailed) R.string.scr04_export_retry else R.string.common_btn_export)
            val onExport = { export.launch("$stem.json") }
            if (layout.largeText) {
                OutlinedAction(
                    text = exportText,
                    onClick = onExport,
                    modifier = Modifier.padding(top = StenoSpace.s),
                    icon = R.drawable.ic_download,
                    tag = Tags.EXPORT,
                )
                GhostAction(
                    text = stringResource(R.string.scr03_to_library),
                    onClick = actions.back,
                    modifier = Modifier.padding(top = StenoSpace.xs),
                    tag = Tags.TO_LIBRARY,
                )
            } else {
                Row(
                    modifier = Modifier.padding(top = StenoSpace.s),
                    horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedAction(
                        text = exportText,
                        onClick = onExport,
                        modifier = Modifier.weight(1f),
                        icon = R.drawable.ic_download,
                        tag = Tags.EXPORT,
                        dense = true,
                    )
                    GhostAction(
                        text = stringResource(R.string.scr03_to_library),
                        onClick = actions.back,
                        modifier = Modifier.weight(1f),
                        tag = Tags.TO_LIBRARY,
                    )
                }
            }
        }
    }
}

/** Длительность и число говорящих. Значок всегда рядом со значением, цвет ничего не решает один. */
@Composable
private fun DoneFacts(detail: JobDetailUi) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val duration = (detail.result?.durationMs ?: detail.summary.durationMs)?.let(::formatTimecode)
    val speakers = (detail.result?.speakers ?: detail.summary.speakers)?.let(::speakersLabel)
    Row(
        modifier = Modifier.padding(top = StenoSpace.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
    ) {
        duration?.let {
            StenoIcon(R.drawable.ic_schedule, colors.primary, size = StenoSize.iconMedium)
            Text(it, style = type.meta, color = colors.primary)
        }
        if (duration != null && speakers != null) Spacer(Modifier.width(StenoSpace.l))
        speakers?.let {
            StenoIcon(R.drawable.ic_users, colors.primary, size = StenoSize.iconMedium)
            Text(it, style = type.meta, color = colors.primary)
        }
    }
}
