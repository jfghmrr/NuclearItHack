package ru.stenokot.app.ui.home

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.stenokot.app.R
import ru.stenokot.app.domain.AudioFileUi
import ru.stenokot.app.domain.HomeUi
import ru.stenokot.app.domain.JobSummaryUi
import ru.stenokot.app.domain.ModelReadiness
import ru.stenokot.app.domain.ModelsUi
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.ui.AppActions
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.BannerTone
import ru.stenokot.app.ui.components.EmptyState
import ru.stenokot.app.ui.components.FileCard
import ru.stenokot.app.ui.components.JobStatusLabel
import ru.stenokot.app.ui.components.LedgerRow
import ru.stenokot.app.ui.components.OutlinedAction
import ru.stenokot.app.ui.components.PrimaryAction
import ru.stenokot.app.ui.components.QuietAction
import ru.stenokot.app.ui.components.SectionHeader
import ru.stenokot.app.ui.components.StatusBanner
import ru.stenokot.app.ui.components.StenoIcon
import ru.stenokot.app.ui.components.StenoProgressBar
import ru.stenokot.app.ui.components.resourceTags
import ru.stenokot.app.ui.components.spokenAs
import ru.stenokot.app.ui.components.toneColors
import ru.stenokot.app.ui.format.filesLabel
import ru.stenokot.app.ui.format.formatFreeSpace
import ru.stenokot.app.ui.format.formatHistoryDate
import ru.stenokot.app.ui.format.formatMib
import ru.stenokot.app.ui.format.formatMibValue
import ru.stenokot.app.ui.format.formatPercent
import ru.stenokot.app.ui.format.formatPreflight
import ru.stenokot.app.ui.format.formatTimecode
import ru.stenokot.app.ui.format.jobBadge
import ru.stenokot.app.ui.format.protocolLabel
import ru.stenokot.app.ui.format.speakersLabel
import ru.stenokot.app.ui.format.userStageLabel
import ru.stenokot.app.ui.format.userStageOf
import ru.stenokot.app.ui.layout.CenteredContent
import ru.stenokot.app.ui.layout.ContentMaxWidth
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.nav.Route
import ru.stenokot.app.ui.nav.routeForJob
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.PreflightInfo
import ru.stenokot.core.model.Stage

private const val META_DOT = " · "

/** Действия главного экрана. Один объект на экран, чтобы строки списка не пересоздавали лямбды. */
@Stable
private class HomeCallbacks(
    val onImport: () -> Unit,
    val onImportAll: () -> Unit,
    val onModels: () -> Unit,
    val onAudio: (AudioFileUi) -> Unit,
    val onJob: (JobSummaryUi) -> Unit,
    val onStart: (String) -> Unit,
    val onResume: (String) -> Unit,
    val onDelete: (JobSummaryUi) -> Unit,
    val onPermission: () -> Unit,
)

/** Всё, что нужно экрану для отрисовки. */
@Immutable
private data class HomeState(
    val home: HomeUi,
    val sections: HomeSections,
    val preflight: PreflightInfo?,
) {
    val isEmpty: Boolean
        get() = home.audio.isEmpty() && home.jobs.isEmpty()
}

private fun audioPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

/** SCR-01. Импорт доступен всегда и не блокируется состоянием моделей. */
@Composable
fun HomeRoute(controller: StenoController, actions: AppActions) {
    val home by controller.home.collectAsStateWithLifecycle()
    val sections = remember(home.jobs) { homeSections(home.jobs) }
    val selectedId = sections.selected?.id
    val preflight = selectedId?.let { id ->
        val detail by remember(id) { controller.job(id) }.collectAsStateWithLifecycle()
        detail?.processing?.preflight
    }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) controller.refreshAudio()
    }
    val callbacks = remember(controller, actions) {
        HomeCallbacks(
            onImport = actions.importAudio,
            onImportAll = actions.importAny,
            onModels = { actions.navigator.push(Route.Verification) },
            onAudio = { audio -> actions.importFiles(listOf(Uri.parse(audio.uri))) },
            onJob = { job -> actions.navigator.push(routeForJob(job.id, job.state)) },
            onStart = controller::start,
            onResume = controller::resume,
            onDelete = { job -> deletingId = job.id },
            onPermission = { permission.launch(audioPermission()) },
        )
    }
    val state = HomeState(home, sections, preflight)
    if (LocalWindowLayout.current.twoPane) {
        HomeTwoPanes(state, callbacks, listState)
    } else {
        HomeSinglePane(state, callbacks, listState)
    }
    home.jobs.firstOrNull { it.id == deletingId }?.let { job ->
        DeleteDialog(
            job = job,
            onConfirm = { removeExported ->
                controller.delete(job.id, removeExported)
                deletingId = null
            },
            onDismiss = { deletingId = null },
        )
    }
}

@Composable
private fun HomeSinglePane(state: HomeState, callbacks: HomeCallbacks, listState: LazyListState) {
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val side = ((maxWidth - ContentMaxWidth) / 2).coerceAtLeast(0.dp)
            val viewport = maxHeight
            val hintsAtEnd = LocalWindowLayout.current.hintsInContent
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(Tags.LIST_AUDIO),
                state = listState,
                contentPadding = PaddingValues(start = side, end = side, bottom = StenoSpace.l),
            ) {
                item(key = "header", contentType = "header") {
                    HomeHeader(state.home.models, callbacks)
                }
                homeContent(state, callbacks, viewport)
                if (hintsAtEnd) {
                    item(key = "hints", contentType = "hints") { ImportHints(callbacks) }
                }
            }
        }
        HomeDock(state, callbacks)
    }
}

@Composable
private fun HomeTwoPanes(state: HomeState, callbacks: HomeCallbacks, listState: LazyListState) {
    val colors = StenoTheme.colors
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(0.42f).fillMaxHeight()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                HomeHeader(state.home.models, callbacks)
                if (LocalWindowLayout.current.hintsInContent) ImportHints(callbacks)
            }
            HomeDock(state, callbacks)
        }
        Spacer(Modifier.width(StenoSize.hairline).fillMaxHeight().background(colors.outlineVariant))
        BoxWithConstraints(Modifier.weight(0.58f).fillMaxHeight()) {
            val viewport = maxHeight
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(Tags.LIST_AUDIO),
                state = listState,
                contentPadding = PaddingValues(top = StenoSpace.m, bottom = StenoSpace.l),
            ) {
                homeContent(state, callbacks, viewport)
            }
        }
    }
}

@Composable
private fun HomeHeader(models: ModelsUi, callbacks: HomeCallbacks) {
    val layout = LocalWindowLayout.current
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val titleStyle = if (layout.short) type.headline else type.display
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = layout.gutter)
            .padding(top = if (layout.short) StenoSpace.m else StenoSpace.xl),
    ) {
        Text(
            text = stringResource(R.string.scr01_title),
            modifier = Modifier.semantics { heading() },
            style = titleStyle,
            color = colors.onBackground,
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = titleStyle.fontSize),
        )
        Spacer(Modifier.height(if (layout.short) StenoSpace.xs else StenoSpace.s))
        Text(
            text = stringResource(R.string.scr01_promise),
            style = if (layout.short) type.bodyMedium else type.bodyLarge,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(if (layout.short) StenoSpace.xs else StenoSpace.l))
        ModelsRow(models, callbacks.onModels)
    }
}

@Composable
private fun ModelsRow(models: ModelsUi, onClick: () -> Unit) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val free = stringResource(R.string.scr01_models_free, formatFreeSpace(models.freeBytes))
    val warning = models.readiness != ModelReadiness.READY && models.readiness != ModelReadiness.COPYING
    val palette = toneColors(BannerTone.WARNING)
    val title = when (models.readiness) {
        ModelReadiness.READY -> stringResource(R.string.scr01_models_ready)
        ModelReadiness.COPYING -> stringResource(
            R.string.scr01_models_copying,
            formatMibValue(models.copiedBytes),
            formatMibValue(models.totalBytes),
        )
        ModelReadiness.LLM_UNAVAILABLE -> stringResource(R.string.scr01_models_llm_unavailable)
        ModelReadiness.ASR_UNAVAILABLE -> stringResource(R.string.scr01_models_asr_unavailable)
        ModelReadiness.STORAGE_LOW -> stringResource(
            R.string.scr01_models_storage_low,
            formatMibValue((models.totalBytes - models.freeBytes).coerceAtLeast(0)),
        )
    }
    val subtitle = when (models.readiness) {
        ModelReadiness.COPYING -> stringResource(R.string.scr01_models_copying_hint)
        ModelReadiness.READY -> free
        else -> null
    }
    val spoken = when (models.readiness) {
        ModelReadiness.COPYING -> stringResource(R.string.scr01_models_copying_hint)
        else -> title
    }
    val content = if (warning) palette.content else colors.onSurface
    val shape = RoundedCornerShape(StenoRadius.m)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = StenoSize.actionHeight)
            .then(if (warning) Modifier.background(palette.container, shape) else Modifier)
            .testTag(Tags.MODELS)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .spokenAs(stringResource(R.string.scr01_models_spoken, spoken), title)
            .padding(horizontal = if (warning) StenoSpace.m else 0.dp, vertical = StenoSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.m),
    ) {
        when (models.readiness) {
            ModelReadiness.READY -> StenoIcon(R.drawable.ic_check_circle_fill, colors.success, size = StenoSize.icon)
            ModelReadiness.COPYING -> StenoIcon(R.drawable.ic_hourglass_top, colors.primary, size = StenoSize.icon)
            else -> StenoIcon(R.drawable.ic_warning, palette.icon, size = StenoSize.icon)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = type.labelMedium, color = content)
            subtitle?.let { Text(it, style = type.bodySmall, color = colors.onSurfaceVariant) }
        }
        StenoIcon(R.drawable.ic_chevron_right, if (warning) palette.content else colors.onSurfaceVariant)
    }
}

@Composable
private fun ImportHints(callbacks: HomeCallbacks) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    Column(Modifier.padding(horizontal = LocalWindowLayout.current.gutter).padding(top = StenoSpace.s)) {
        Text(stringResource(R.string.scr01_formats), style = type.bodySmall, color = colors.onSurfaceVariant)
        QuietAction(
            text = stringResource(R.string.scr01_btn_import_all),
            onClick = callbacks.onImportAll,
            modifier = Modifier.offset(x = -StenoSpace.s),
            tag = Tags.IMPORT_ALL,
        )
    }
}

@Composable
private fun HomeDock(state: HomeState, callbacks: HomeCallbacks) {
    val layout = LocalWindowLayout.current
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val selected = state.sections.selected
    CenteredContent(
        Modifier
            .background(colors.surface)
            .drawBehind {
                drawLine(colors.outlineVariant, Offset.Zero, Offset(size.width, 0f), StenoSize.hairline.toPx())
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = layout.gutter)
                .padding(top = StenoSpace.m, bottom = if (layout.hintsInContent) StenoSpace.m else StenoSpace.xs),
            verticalArrangement = Arrangement.spacedBy(StenoSpace.s),
        ) {
            if (selected != null) {
                if (!layout.tiny) StartAction(selected.id, callbacks)
                OutlinedAction(
                    text = stringResource(R.string.common_btn_import),
                    onClick = callbacks.onImport,
                    icon = R.drawable.ic_upload_file,
                    tag = Tags.IMPORT,
                )
            } else {
                PrimaryAction(
                    text = stringResource(R.string.common_btn_import),
                    onClick = callbacks.onImport,
                    icon = R.drawable.ic_upload_file,
                    tag = Tags.IMPORT,
                )
            }
            if (!layout.hintsInContent) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.scr01_formats),
                        modifier = Modifier.weight(1f),
                        style = type.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    QuietAction(
                        text = stringResource(R.string.scr01_btn_import_all),
                        onClick = callbacks.onImportAll,
                        tag = Tags.IMPORT_ALL,
                    )
                }
            }
        }
    }
}

@Composable
private fun StartAction(jobId: String, callbacks: HomeCallbacks) {
    PrimaryAction(
        text = stringResource(R.string.common_btn_start),
        onClick = { callbacks.onStart(jobId) },
        icon = R.drawable.ic_play_arrow_fill,
        tag = Tags.START,
    )
    Text(
        text = stringResource(R.string.scr01_autostart_hint),
        style = StenoTheme.type.bodySmall,
        color = StenoTheme.colors.onSurfaceVariant,
    )
}

private fun LazyListScope.homeContent(state: HomeState, callbacks: HomeCallbacks, viewport: Dp) {
    val sections = state.sections
    state.sections.active?.let { job ->
        item(key = "job", contentType = "job") { JobCard(job, sections.queuedCount, callbacks.onJob) }
    }
    sections.selected?.let { job ->
        item(key = "selected", contentType = "selected") {
            SelectedFile(job, state.preflight)
            if (LocalWindowLayout.current.tiny) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = LocalWindowLayout.current.gutter)
                        .padding(top = StenoSpace.m),
                    verticalArrangement = Arrangement.spacedBy(StenoSpace.s),
                ) {
                    StartAction(job.id, callbacks)
                }
            }
        }
    }
    sections.interrupted?.let { job ->
        item(key = "interrupted", contentType = "interrupted") { InterruptedCard(job, callbacks) }
    }
    if (sections.queue.isNotEmpty()) {
        item(key = "queue-header", contentType = "section") {
            Section(stringResource(R.string.scr01_section_queue), Tags.LIST_QUEUE)
        }
        itemsIndexed(sections.queue, key = { _, job -> "queue-${job.id}" }, contentType = { _, _ -> "row" }) { index, job ->
            QueueRow(index, job)
        }
    }
    if (state.isEmpty) {
        item(key = "empty", contentType = "empty") {
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = viewport * 0.6f),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(stringResource(R.string.scr01_empty), mascotSize = emptyMascotSize()) {
                    if (!state.home.audioPermissionGranted) PermissionRow(callbacks.onPermission)
                }
            }
        }
        return
    }
    if (state.home.audio.isNotEmpty()) {
        item(key = "audio-header", contentType = "section") {
            Section(stringResource(R.string.scr01_section_audio), tag = null)
        }
        itemsIndexed(state.home.audio, key = { _, audio -> "audio-${audio.uri}" }, contentType = { _, _ -> "row" }) { index, audio ->
            AudioRow(index, audio, callbacks.onAudio)
        }
    }
    if (!state.home.audioPermissionGranted) {
        item(key = "permission", contentType = "permission") { PermissionRow(callbacks.onPermission) }
    }
    if (sections.history.isNotEmpty()) {
        item(key = "history-header", contentType = "section") {
            Section(stringResource(R.string.scr01_section_history), Tags.LIST_HISTORY)
        }
        itemsIndexed(sections.history, key = { _, job -> "history-${job.id}" }, contentType = { _, _ -> "row" }) { index, job ->
            HistoryRow(index, job, callbacks)
        }
    }
}

@Composable
private fun emptyMascotSize(): Dp = if (LocalWindowLayout.current.short) 88.dp else StenoSize.mascot

@Composable
private fun Section(title: String, tag: String?) {
    SectionHeader(
        title = title,
        modifier = Modifier.padding(horizontal = LocalWindowLayout.current.gutter).padding(top = StenoSpace.m),
        tag = tag,
    )
}

@Composable
private fun PermissionRow(onClick: () -> Unit) {
    val gutter = LocalWindowLayout.current.gutter
    val colors = StenoTheme.colors
    Row(
        modifier = Modifier
            .heightIn(min = StenoSize.touchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(Tags.ROW_AUDIO_PERMISSION)
            .padding(horizontal = gutter, vertical = StenoSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
    ) {
        Text(
            text = stringResource(R.string.scr01_row_permission),
            modifier = Modifier.weight(1f, fill = false),
            style = StenoTheme.type.labelMedium,
            color = colors.primary,
        )
        StenoIcon(R.drawable.ic_chevron_right, colors.primary)
    }
}

private fun jobStageLine(state: JobState): Pair<String, Float?>? = when (state) {
    is JobState.Running -> {
        val fraction = if (state.stage == Stage.MERGE || state.stage == Stage.VALIDATE) null else state.fraction
        userStageLabel(userStageOf(state.stage)) to fraction
    }
    is JobState.Paused -> userStageLabel(userStageOf(state.stage)) to null
    else -> null
}

@Composable
private fun JobCard(job: JobSummaryUi, queued: Int, onOpen: (JobSummaryUi) -> Unit) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val gutter = LocalWindowLayout.current.gutter
    val line = jobStageLine(job.state)
    val percent = line?.second?.let(::formatPercent)
    val stage = when {
        line == null -> ""
        percent != null -> stringResource(R.string.scr01_job_stage, line.first, percent)
        else -> line.first
    }
    val spoken = stringResource(R.string.scr01_spoken_job, job.title, line?.first.orEmpty(), percent.orEmpty())
    val shape = RoundedCornerShape(StenoRadius.xl)
    Row(
        modifier = Modifier
            .padding(horizontal = gutter)
            .padding(top = StenoSpace.m)
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(colors.primaryContainer, shape)
            .testTag(Tags.CARD_JOB)
            .clickable(role = Role.Button) { onOpen(job) }
            .spokenAs(spoken, job.title)
            .padding(StenoSpace.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StenoSpace.xs)) {
            Text(
                text = job.title,
                style = type.labelLarge,
                color = colors.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Text(stage, style = type.bodyMedium, color = colors.onPrimaryContainer)
            line?.second?.let {
                StenoProgressBar(
                    fraction = it,
                    modifier = Modifier.padding(vertical = StenoSpace.xs),
                    height = StenoSpace.xs,
                    track = colors.surface,
                )
            }
            JobStatusLabel(
                badge = jobBadge(job.state),
                tint = colors.onPrimaryContainer,
                extra = if (queued > 0) stringResource(R.string.scr01_queue, filesLabel(queued)) else null,
            )
        }
        StenoIcon(R.drawable.ic_chevron_right, colors.onPrimaryContainer)
    }
}

@Composable
private fun SelectedFile(job: JobSummaryUi, preflight: PreflightInfo?) {
    val gutter = LocalWindowLayout.current.gutter
    val duration = (preflight?.durationMs ?: job.durationMs)?.let(::formatTimecode)
    val size = preflight?.sizeBytes?.let(::formatMib)
    val meta = listOfNotNull(duration, size).joinToString(META_DOT).ifEmpty { null }
    FileCard(
        name = preflight?.fileName ?: job.title,
        meta = meta,
        preflight = preflight?.let(::formatPreflight),
        description = stringResource(R.string.scr01_spoken_file, job.title, duration.orEmpty()) +
            preflight?.let { ". " + formatPreflight(it) }.orEmpty(),
        modifier = Modifier.padding(horizontal = gutter).padding(top = StenoSpace.m),
    )
}

@Composable
private fun InterruptedCard(job: JobSummaryUi, callbacks: HomeCallbacks) {
    val gutter = LocalWindowLayout.current.gutter
    val state = job.state as? JobState.Interrupted ?: return
    StatusBanner(
        tone = BannerTone.WARNING,
        icon = R.drawable.ic_pause_circle,
        title = stringResource(R.string.scr01_interrupted_title),
        body = stringResource(
            R.string.scr01_interrupted_body,
            userStageLabel(userStageOf(state.resumeFrom)),
        ),
        modifier = Modifier.padding(horizontal = gutter).padding(top = StenoSpace.m),
        action = {
            QuietAction(
                text = stringResource(R.string.common_btn_resume),
                onClick = { callbacks.onResume(job.id) },
                modifier = Modifier.offset(x = -StenoSpace.s),
                tag = Tags.RESUME,
            )
        },
    )
}

@Composable
private fun QueueRow(index: Int, job: JobSummaryUi) {
    val position = job.queuePosition ?: (index + 1)
    LedgerRow(
        margin = position.toString(),
        title = job.title,
        marginFallback = null,
        description = stringResource(R.string.scr01_spoken_queue, job.title, position),
        tag = Tags.rowQueue(index),
        footer = { JobStatusLabel(jobBadge(job.state)) },
    )
}

@Composable
private fun AudioRow(index: Int, audio: AudioFileUi, onAudio: (AudioFileUi) -> Unit) {
    val duration = audio.durationMs?.let(::formatTimecode)
    val size = formatMib(audio.sizeBytes)
    LedgerRow(
        margin = duration,
        title = audio.name,
        subtitle = "$size$META_DOT${audio.folder}",
        marginFallback = listOfNotNull(duration, size, audio.folder).joinToString(META_DOT),
        description = stringResource(R.string.scr01_spoken_audio, audio.name, duration.orEmpty(), size),
        tag = Tags.rowAudio(index),
        onClick = { onAudio(audio) },
    )
}

@Composable
private fun HistoryRow(index: Int, job: JobSummaryUi, callbacks: HomeCallbacks) {
    val date = formatHistoryDate(job.createdAtMs)
    val duration = job.durationMs?.let(::formatTimecode)
    val speakers = job.speakers?.let(::speakersLabel)
    val badge = jobBadge(job.state)
    val protocol = protocolLabel(job.state)
    val status = listOfNotNull(badge.label, protocol).joinToString(", ")
    LedgerRow(
        margin = duration,
        title = job.title,
        subtitle = listOfNotNull(date, speakers).joinToString(META_DOT),
        marginFallback = listOfNotNull(date, duration, speakers).joinToString(META_DOT),
        description = stringResource(R.string.scr01_spoken_history, job.title, date, status),
        tag = Tags.rowHistory(index),
        onClick = { callbacks.onJob(job) },
        footer = { JobStatusLabel(badge, extra = protocol) },
        trailing = {
            IconButton(
                onClick = { callbacks.onDelete(job) },
                modifier = Modifier.testTag(Tags.rowDelete(index)),
            ) {
                StenoIcon(
                    icon = R.drawable.ic_delete,
                    tint = StenoTheme.colors.outline,
                    description = stringResource(R.string.scr01_delete),
                )
            }
        },
    )
}

/** Подтверждение удаления. Экспортированный файл уходит только по отдельной отметке. */
@Composable
private fun DeleteDialog(
    job: JobSummaryUi,
    onConfirm: (removeExported: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    var removeExported by rememberSaveable(job.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.resourceTags(),
        containerColor = colors.surfaceContainerLowest,
        title = { Text(stringResource(R.string.scr01_delete_title), style = type.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(StenoSpace.s)) {
                Text(job.title, style = type.labelMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(stringResource(R.string.scr01_delete_body), style = type.bodyMedium)
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
                    Text(stringResource(R.string.scr01_delete_exported), style = type.labelMedium)
                }
                if (removeExported) {
                    Text(
                        text = stringResource(R.string.scr01_delete_exported_warning),
                        style = type.bodyMedium.copy(fontWeight = FontWeight.W500),
                        color = colors.error,
                    )
                }
            }
        },
        confirmButton = {
            QuietAction(
                text = stringResource(R.string.scr01_delete_confirm),
                onClick = { onConfirm(removeExported) },
                tag = Tags.DELETE_CONFIRM,
            )
        },
        dismissButton = {
            QuietAction(
                text = stringResource(R.string.scr01_delete_dismiss),
                onClick = onDismiss,
                tag = Tags.DELETE_DISMISS,
            )
        },
    )
}
