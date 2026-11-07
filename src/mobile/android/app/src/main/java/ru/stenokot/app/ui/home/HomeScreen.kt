package ru.stenokot.app.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import ru.stenokot.app.R
import ru.stenokot.app.domain.AudioFileUi
import ru.stenokot.app.domain.HomeUi
import ru.stenokot.app.domain.JobSummaryUi
import ru.stenokot.app.domain.MicProblem
import ru.stenokot.app.domain.ModelReadiness
import ru.stenokot.app.domain.ModelsUi
import ru.stenokot.app.domain.RecordingUi
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.ui.AppActions
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.ActionDock
import ru.stenokot.app.ui.components.BannerTone
import ru.stenokot.app.ui.components.GroupLabel
import ru.stenokot.app.ui.components.MascotKind
import ru.stenokot.app.ui.components.MascotPlate
import ru.stenokot.app.ui.components.OutlinedAction
import ru.stenokot.app.ui.components.PrimaryAction
import ru.stenokot.app.ui.components.QuietAction
import ru.stenokot.app.ui.components.SearchBox
import ru.stenokot.app.ui.components.StatusBanner
import ru.stenokot.app.ui.components.StenoIcon
import ru.stenokot.app.ui.components.spokenAs
import ru.stenokot.app.ui.components.toneColors
import ru.stenokot.app.ui.format.formatDayLabel
import ru.stenokot.app.ui.format.formatFreeSpace
import ru.stenokot.app.ui.format.formatMib
import ru.stenokot.app.ui.format.formatMibValue
import ru.stenokot.app.ui.format.formatTimecode
import ru.stenokot.app.ui.layout.ContentMaxWidth
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.nav.Route
import ru.stenokot.app.ui.nav.routeForJob
import ru.stenokot.app.ui.theme.EnterMotion
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme
import ru.stenokot.core.model.PreflightInfo

private const val META_DOT = " · "
private const val SPOKEN_SEPARATOR = ", "
private const val IMPORT_WEIGHT = 1f
private const val RECORD_WEIGHT = 1.1f
private const val EMPTY_HEIGHT_SHARE = 0.68f
private val TightDock = 320.dp
private val EmptyTextWidth = 310.dp
private val BrandMarkSize = 25.sp
private val ModelsTop = 2.dp
private val ChipPadding = 20.dp
private val ChipPaddingNarrow = 16.dp
private const val NARROW_CHIPS_DP = 380

/** Спокойное сообщение о микрофоне над кнопками. */
private enum class MicNotice { UNAVAILABLE, EMPTY, NO_SPACE }

/** Всё, что нужно экрану для отрисовки. */
@Immutable
private data class HomeState(
    val home: HomeUi,
    val rows: List<LibraryRow>,
    val audio: List<AudioFileUi>,
    val preflight: PreflightInfo?,
    val micNotice: MicNotice?,
    val query: String,
    val filter: LibraryFilter,
    val today: LocalDate,
) {
    /** В библиотеке ничего нет: показываем приглашение вместо поиска и фильтров. */
    val isEmpty: Boolean
        get() = home.audio.isEmpty() && home.jobs.isEmpty()

    /** Поиск или фильтр скрыли всё, что есть. */
    val nothingFound: Boolean
        get() = !isEmpty && rows.isEmpty() && audio.isEmpty()
}

/** Сужение списка: состояние живёт на маршруте и переживает поворот. */
@Stable
private class LibraryControls(
    val onQuery: (String) -> Unit,
    val onFilter: (LibraryFilter) -> Unit,
    val onReset: () -> Unit,
)

private fun audioPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

/** Разрешение на микрофон уже выдано. */
internal fun micGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** Открывает системную карточку приложения: отказ от разрешения меняется только там. */
internal fun openAppSettings(context: Context) {
    val uri = Uri.fromParts("package", context.packageName, null)
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
}

private fun MicProblem.notice(): MicNotice = when (this) {
    MicProblem.UNAVAILABLE -> MicNotice.UNAVAILABLE
    MicProblem.EMPTY -> MicNotice.EMPTY
    MicProblem.NO_SPACE -> MicNotice.NO_SPACE
}

/** SCR-01. Библиотека записей. Импорт доступен всегда и не блокируется состоянием моделей. */
@Composable
fun HomeRoute(controller: StenoController, actions: AppActions) {
    val home by controller.home.collectAsStateWithLifecycle()
    val selectedId = remember(home.jobs) { homeSections(home.jobs).selected?.id }
    val preflight = selectedId?.let { id ->
        val detail by remember(id) { controller.job(id) }.collectAsStateWithLifecycle()
        detail?.processing?.preflight
    }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val recording by controller.recording.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) controller.refreshAudio()
    }
    val callbacks = remember(controller, actions, context) {
        HomeCallbacks(
            onImport = actions.importAudio,
            onImportAll = actions.importAny,
            onModels = { actions.navigator.push(Route.Verification) },
            onSettings = { actions.navigator.push(Route.Settings) },
            onAudio = { audio -> actions.importFiles(listOf(Uri.parse(audio.uri))) },
            onJob = { job -> actions.navigator.push(routeForJob(job.id, job.state)) },
            onStart = controller::start,
            onResume = controller::resume,
            onDelete = { job -> deletingId = job.id },
            onPermission = { permission.launch(audioPermission()) },
            onRecord = {
                controller.acknowledgeRecording()
                if (micGranted(context)) {
                    controller.startRecording()
                } else {
                    actions.navigator.push(Route.MicPermission)
                }
            },
            onMicNoticeDismiss = controller::acknowledgeRecording,
        )
    }
    val controls = remember {
        LibraryControls(
            onQuery = { query = it },
            onFilter = { filter = it },
            onReset = {
                query = ""
                filter = LibraryFilter.ALL
            },
        )
    }
    val rows = remember(home.jobs, filter, query) { libraryRows(home.jobs, filter, query) }
    val audio = remember(home.audio, filter, query) {
        if (filter == LibraryFilter.ALL) home.audio.filter { audioMatches(it.name, query) } else emptyList()
    }
    val notice = (recording as? RecordingUi.Failed)?.problem?.notice()
    val state = HomeState(home, rows, audio, preflight, notice, query, filter, LocalDate.now())
    if (LocalWindowLayout.current.twoPane) {
        HomeTwoPanes(state, callbacks, controls, listState)
    } else {
        HomeSinglePane(state, callbacks, controls, listState)
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
private fun HomeSinglePane(
    state: HomeState,
    callbacks: HomeCallbacks,
    controls: LibraryControls,
    listState: LazyListState,
) {
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag(Tags.LIST_AUDIO)) {
            val side = ((maxWidth - ContentMaxWidth) / 2).coerceAtLeast(0.dp)
            val viewport = maxHeight
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(Tags.LIST_HISTORY),
                state = listState,
                contentPadding = PaddingValues(start = side, end = side, bottom = StenoSpace.l),
            ) {
                item(key = "header", contentType = "header") { HomeHeader(state.home.models, callbacks) }
                if (!state.isEmpty) {
                    item(key = "narrowing", contentType = "narrowing") { LibraryNarrowing(state, controls) }
                }
                libraryContent(state, callbacks, controls, viewport)
            }
        }
        HomeDock(state, callbacks)
    }
}

@Composable
private fun HomeTwoPanes(
    state: HomeState,
    callbacks: HomeCallbacks,
    controls: LibraryControls,
    listState: LazyListState,
) {
    val colors = StenoTheme.colors
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(0.42f).fillMaxHeight()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                HomeHeader(state.home.models, callbacks)
            }
            HomeDock(state, callbacks)
        }
        Spacer(Modifier.width(StenoSize.hairline).fillMaxHeight().background(colors.outlineVariant))
        BoxWithConstraints(Modifier.weight(0.58f).fillMaxHeight().testTag(Tags.LIST_AUDIO)) {
            val viewport = maxHeight
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(Tags.LIST_HISTORY),
                state = listState,
                contentPadding = PaddingValues(top = StenoSpace.s, bottom = StenoSpace.l),
            ) {
                if (!state.isEmpty) {
                    item(key = "narrowing", contentType = "narrowing") { LibraryNarrowing(state, controls) }
                }
                libraryContent(state, callbacks, controls, viewport)
            }
        }
    }
}

/** Шапка: знак приложения, заголовок, обещание и настройки. Строка под заголовком честно говорит о моделях. */
@Composable
private fun HomeHeader(models: ModelsUi, callbacks: HomeCallbacks) {
    val layout = LocalWindowLayout.current
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val titleStyle = if (layout.short) type.headline else type.display
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = layout.gutter)
            .padding(top = if (layout.short) StenoSpace.s else StenoSpace.m),
    ) {
        Column {
            BrandLine()
            Text(
                text = stringResource(R.string.scr01_title),
                modifier = Modifier.semantics { heading() },
                style = titleStyle,
                color = colors.onBackground,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = titleStyle.fontSize),
            )
            ModelsLine(models, callbacks.onModels)
        }
        Box(Modifier.align(Alignment.TopEnd).size(StenoSize.touchTarget).background(colors.surfaceContainer, CircleShape)) {
            IconButton(onClick = callbacks.onSettings, modifier = Modifier.testTag(Tags.SETTINGS)) {
                StenoIcon(
                    icon = R.drawable.ic_settings,
                    tint = colors.onSurface,
                    size = StenoSize.icon,
                    description = stringResource(R.string.scr01_settings),
                )
            }
        }
    }
}

@Composable
private fun BrandLine() {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val name = stringResource(R.string.app_name)
    Row(
        modifier = Modifier.clearAndSetSemantics { contentDescription = name },
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
    ) {
        Text(
            text = stringResource(R.string.scr01_brand_mark),
            style = type.titleLarge.copy(fontSize = BrandMarkSize, lineHeight = BrandMarkSize * 1.2f),
            color = colors.primary,
        )
        Text(
            text = stringResource(R.string.scr01_brand_name),
            modifier = Modifier.padding(top = StenoSpace.xs),
            style = type.eyebrow,
            color = colors.primary,
        )
    }
}

/**
 * Обещание приватности и строка `txt_models`. Пока модели готовы, это тихая подпись под заголовком,
 * а автоматика и TalkBack всё равно получают готовность. Любая проблема показывается плашкой.
 */
@Composable
private fun ModelsLine(models: ModelsUi, onClick: () -> Unit) {
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
    val promise = stringResource(R.string.scr01_promise)
    val spoken = stringResource(
        R.string.scr01_models_spoken,
        when (models.readiness) {
            ModelReadiness.READY -> "$title. $free"
            ModelReadiness.COPYING -> stringResource(R.string.scr01_models_copying_hint)
            else -> title
        },
    )
    val shape = RoundedCornerShape(StenoRadius.field)
    Row(
        modifier = Modifier
            .padding(top = if (warning) StenoSpace.s else ModelsTop)
            .fillMaxWidth()
            .heightIn(min = StenoSize.touchTarget)
            .then(if (warning) Modifier.background(palette.container, shape) else Modifier)
            .clip(shape)
            .testTag(Tags.MODELS)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .spokenAs(spoken, title)
            .padding(horizontal = if (warning) StenoSpace.m else 0.dp, vertical = StenoSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
    ) {
        val copying = models.readiness == ModelReadiness.COPYING
        val icon = when {
            warning -> R.drawable.ic_warning
            copying -> R.drawable.ic_hourglass_top
            else -> R.drawable.ic_shield
        }
        StenoIcon(icon, if (warning) palette.icon else colors.onSurfaceVariant, size = StenoSize.iconSmall)
        Text(
            text = if (warning || copying) title else promise,
            modifier = Modifier.weight(1f),
            style = if (warning) type.bodySmall else type.meta,
            color = if (warning) palette.content else colors.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryNarrowing(state: HomeState, controls: LibraryControls) {
    val gutter = LocalWindowLayout.current.gutter
    Column(Modifier.fillMaxWidth().padding(horizontal = gutter)) {
        SearchBox(
            value = state.query,
            onValueChange = controls.onQuery,
            placeholder = stringResource(R.string.scr01_search),
            clearLabel = stringResource(R.string.scr01_search_clear),
            modifier = Modifier.padding(top = StenoSpace.xs),
            tag = Tags.LIBRARY_SEARCH,
            clearTag = Tags.LIBRARY_SEARCH_CLEAR,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = StenoSpace.m),
            horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
            verticalArrangement = Arrangement.spacedBy(StenoSpace.xs),
        ) {
            FilterChip(R.string.scr01_filter_all, LibraryFilter.ALL, state.filter, controls.onFilter)
            FilterChip(R.string.scr01_filter_done, LibraryFilter.DONE, state.filter, controls.onFilter)
            FilterChip(R.string.scr01_filter_in_work, LibraryFilter.IN_WORK, state.filter, controls.onFilter)
        }
    }
}

@Composable
private fun FilterChip(
    label: Int,
    filter: LibraryFilter,
    current: LibraryFilter,
    onSelect: (LibraryFilter) -> Unit,
) {
    val colors = StenoTheme.colors
    val selected = filter == current
    val narrow = LocalWindowLayout.current.widthDp < NARROW_CHIPS_DP
    val shape = RoundedCornerShape(StenoRadius.field)
    Box(
        modifier = Modifier
            .widthIn(min = StenoSize.touchTarget)
            .heightIn(min = StenoSize.touchTarget)
            .clip(shape)
            .background(if (selected) colors.primaryContainer else colors.background, shape)
            .testTag(Tags.chipFilter(filter.name.lowercase()))
            .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(filter) })
            .padding(horizontal = if (narrow) ChipPaddingNarrow else ChipPadding, vertical = StenoSpace.s),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(label),
            style = if (selected) StenoTheme.type.labelSmall else StenoTheme.type.bodySmall,
            color = if (selected) colors.primary else colors.onSurfaceVariant,
        )
    }
}

private fun LazyListScope.libraryContent(
    state: HomeState,
    callbacks: HomeCallbacks,
    controls: LibraryControls,
    viewport: Dp,
) {
    if (state.isEmpty) {
        item(key = "empty", contentType = "empty") { EmptyLibrary(state, callbacks, viewport) }
        return
    }
    if (state.nothingFound) {
        item(key = "nothing", contentType = "nothing") { NothingFound(controls.onReset) }
    }
    state.rows.forEach { row ->
        item(key = row.key, contentType = row::class) {
            when (row) {
                is LibraryRow.Day -> DayLabel(row.date, state.today)
                is LibraryRow.Record -> {
                    val firstQueued = row.kind == RecordKind.QUEUED && row.index == 0
                    Box(if (firstQueued) Modifier.testTag(Tags.LIST_QUEUE) else Modifier) {
                        RecordCard(row, state.today, state.preflight, callbacks)
                    }
                }
            }
        }
    }
    if (state.audio.isNotEmpty()) {
        item(key = "audio-header", contentType = "day") { SectionLabel(stringResource(R.string.scr01_section_audio)) }
        itemsIndexed(state.audio, key = { _, audio -> "audio-${audio.uri}" }, contentType = { _, _ -> "audio" }) { index, audio ->
            AudioRow(index, audio, callbacks.onAudio)
        }
    }
    item(key = "hints", contentType = "hints") { LibraryHints(state, callbacks) }
}

@Composable
private fun DayLabel(date: LocalDate, today: LocalDate) {
    SectionLabel(formatDayLabel(date, today))
}

@Composable
private fun SectionLabel(text: String) {
    GroupLabel(text, Modifier.padding(horizontal = LocalWindowLayout.current.gutter))
}

@Composable
private fun EmptyLibrary(state: HomeState, callbacks: HomeCallbacks, viewport: Dp) {
    val layout = LocalWindowLayout.current
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = viewport * EMPTY_HEIGHT_SHARE)
            .padding(horizontal = StenoSpace.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (!layout.tiny) {
            MascotPlate(
                kind = MascotKind.EMPTY,
                plate = if (layout.short) StenoSize.mascotPlateSmall else StenoSize.mascotPlate,
                motion = EnterMotion.EMPTY,
            )
        }
        Text(
            text = stringResource(R.string.scr01_empty_title),
            modifier = Modifier.padding(top = StenoSpace.l).semantics { heading() },
            style = type.headline,
            color = colors.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.scr01_empty_body),
            modifier = Modifier.padding(top = StenoSpace.m).widthIn(max = EmptyTextWidth),
            style = type.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Row(
            modifier = Modifier.padding(top = StenoSpace.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
        ) {
            StenoIcon(R.drawable.ic_shield, colors.primary, size = StenoSize.iconSmall)
            Text(stringResource(R.string.scr01_empty_badge), style = type.meta, color = colors.primary)
        }
        if (!state.home.audioPermissionGranted) PermissionRow(callbacks.onPermission)
        QuietAction(
            text = stringResource(R.string.scr01_btn_import_all),
            onClick = callbacks.onImportAll,
            tag = Tags.IMPORT_ALL,
        )
    }
}

@Composable
private fun NothingFound(onReset: () -> Unit) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = StenoSpace.xxl, vertical = StenoSpace.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(StenoSpace.xs),
    ) {
        Text(stringResource(R.string.scr01_nothing_found), style = type.titleMedium, color = colors.onSurface)
        Text(
            text = stringResource(R.string.scr01_nothing_found_hint),
            style = type.bodySmall,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        QuietAction(
            text = stringResource(R.string.scr01_reset),
            onClick = onReset,
            tag = Tags.LIBRARY_RESET,
        )
    }
}

/** Конец списка: форматы, выбор без фильтра и доступ к аудио на устройстве. */
@Composable
private fun LibraryHints(state: HomeState, callbacks: HomeCallbacks) {
    val colors = StenoTheme.colors
    Column(Modifier.padding(horizontal = LocalWindowLayout.current.gutter).padding(top = StenoSpace.m)) {
        Text(
            text = stringResource(R.string.scr01_formats),
            style = StenoTheme.type.meta,
            color = colors.onSurfaceVariant,
        )
        QuietAction(
            text = stringResource(R.string.scr01_btn_import_all),
            onClick = callbacks.onImportAll,
            modifier = Modifier.offset(x = -StenoSpace.s),
            tag = Tags.IMPORT_ALL,
        )
        if (!state.home.audioPermissionGranted) PermissionRow(callbacks.onPermission)
    }
}

@Composable
private fun PermissionRow(onClick: () -> Unit) {
    val colors = StenoTheme.colors
    Row(
        modifier = Modifier
            .heightIn(min = StenoSize.touchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(Tags.ROW_AUDIO_PERMISSION)
            .padding(vertical = StenoSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.xs),
    ) {
        Text(
            text = stringResource(R.string.scr01_row_permission),
            modifier = Modifier.weight(1f, fill = false),
            style = StenoTheme.type.labelLarge,
            color = colors.primary,
        )
        StenoIcon(R.drawable.ic_chevron_right, colors.primary, size = StenoSize.iconSmall)
    }
}

@Composable
private fun AudioRow(index: Int, audio: AudioFileUi, onAudio: (AudioFileUi) -> Unit) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val duration = audio.durationMs?.let(::formatTimecode)
    val size = formatMib(audio.sizeBytes)
    val meta = listOfNotNull(duration, size, audio.folder).joinToString(META_DOT)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = StenoSize.actionHeight)
            .testTag(Tags.rowAudio(index))
            .clickable(role = Role.Button) { onAudio(audio) }
            .spokenAs(
                stringResource(
                    R.string.scr01_spoken_audio,
                    listOfNotNull(audio.name, duration, size).joinToString(SPOKEN_SEPARATOR),
                ),
                audio.name,
            )
            .padding(horizontal = LocalWindowLayout.current.gutter, vertical = StenoSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.m),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = audio.name,
                style = type.labelMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Text(meta, style = type.meta, color = colors.onSurfaceVariant)
        }
        StenoIcon(R.drawable.ic_chevron_right, colors.onSurfaceVariant, size = StenoSize.iconSmall)
    }
}

/** Импорт и запись всегда под рукой. С открытой клавиатурой панель уступает место списку. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeDock(state: HomeState, callbacks: HomeCallbacks) {
    val layout = LocalWindowLayout.current
    if (WindowInsets.isImeVisible) return
    ActionDock {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = layout.gutter)
                .padding(top = StenoSpace.m, bottom = StenoSpace.s),
            verticalArrangement = Arrangement.spacedBy(StenoSpace.s),
        ) {
            state.micNotice?.let { MicNoticeBanner(it, callbacks) }
            ImportActions(callbacks)
        }
    }
}

/** Импорт и запись рядом. На самом узком окне значки уступают словам, крупному шрифту нужны две строки. */
@Composable
private fun ImportActions(callbacks: HomeCallbacks) {
    val importText = stringResource(R.string.common_btn_import)
    val recordText = stringResource(R.string.common_btn_record)
    val layout = LocalWindowLayout.current
    if (layout.largeText && !layout.compactHeight) {
        Column(verticalArrangement = Arrangement.spacedBy(StenoSpace.s)) {
            OutlinedAction(importText, callbacks.onImport, icon = R.drawable.ic_upload_file, tag = Tags.IMPORT)
            PrimaryAction(recordText, callbacks.onRecord, icon = R.drawable.ic_mic, tag = Tags.RECORD)
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val withIcons = maxWidth >= TightDock
        Row(horizontalArrangement = Arrangement.spacedBy(StenoSpace.s + StenoSpace.xs / 2)) {
            OutlinedAction(
                text = importText,
                onClick = callbacks.onImport,
                modifier = Modifier.weight(IMPORT_WEIGHT),
                icon = R.drawable.ic_upload_file.takeIf { withIcons },
                tag = Tags.IMPORT,
                dense = true,
            )
            PrimaryAction(
                text = recordText,
                onClick = callbacks.onRecord,
                modifier = Modifier.weight(RECORD_WEIGHT),
                icon = R.drawable.ic_mic.takeIf { withIcons },
                tag = Tags.RECORD,
                dense = true,
            )
        }
    }
}

@Composable
private fun MicNoticeBanner(notice: MicNotice, callbacks: HomeCallbacks) {
    val text = when (notice) {
        MicNotice.UNAVAILABLE -> R.string.rec_unavailable
        MicNotice.EMPTY -> R.string.rec_empty
        MicNotice.NO_SPACE -> R.string.rec_no_space
    }
    StatusBanner(
        tone = BannerTone.NEUTRAL,
        icon = R.drawable.ic_info,
        body = stringResource(text),
        tag = Tags.MIC_NOTICE,
        polite = true,
        action = {
            QuietAction(
                text = stringResource(R.string.rec_dismiss),
                onClick = callbacks.onMicNoticeDismiss,
                modifier = Modifier.offset(x = -StenoSpace.s),
                tag = Tags.MIC_DISMISS,
            )
        },
    )
}
