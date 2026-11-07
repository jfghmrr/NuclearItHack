package ru.stenokot.app.ui.result

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.stenokot.app.R
import ru.stenokot.app.domain.ExportKind
import ru.stenokot.app.domain.JobDetailUi
import ru.stenokot.app.domain.ResultUi
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.ui.AppActions
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.OutlinedAction
import ru.stenokot.app.ui.components.PrimaryAction
import ru.stenokot.app.ui.components.StenoTopBar
import ru.stenokot.app.ui.format.formatTimecode
import ru.stenokot.app.ui.format.speakersLabel
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.nav.ResultTab
import ru.stenokot.app.ui.nav.Route
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.Language

private const val SEARCH_DEBOUNCE_MS = 150L
private const val WARNING_LANG_FAILED = "LANG_ID_FAILED"
private const val KEY_BANNER = "banner"
private const val KEY_HINT = "hint"
private const val KEY_OUTPUT = "output"
private const val TYPE_HEADER = 10
private const val MAX_PINNED_SPEAKERS = 6

/** Разобранная стенограмма и исходный вид списка. Всё считается вне основного потока. */
@Immutable
private class PreparedTranscript(
    val model: TranscriptModel,
    val initial: TranscriptView,
    val spans: List<RibbonSpan>,
)

private val SpeakerSetSaver = Saver<Set<Int>, IntArray>(
    save = { it.toIntArray() },
    restore = { it.toSet() },
)

/** Запрос и фильтр вместе: сужение считается одним шагом. */
private data class Narrowing(val query: String, val speakers: Set<Int>)

/** Вид списка: первый готов сразу, запрос считается в фоне с паузой на набор. */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
private fun rememberTranscriptView(
    prepared: PreparedTranscript,
    field: TextFieldState,
    speakers: () -> Set<Int>,
    style: MatchStyle,
): State<TranscriptView> {
    val view = remember(prepared, style) { mutableStateOf(prepared.initial) }
    LaunchedEffect(prepared, style) {
        var lastQuery = ""
        snapshotFlow { Narrowing(field.text.toString(), speakers()) }
            .distinctUntilChanged()
            .mapLatest { narrowing ->
                if (narrowing.query != lastQuery) delay(SEARCH_DEBOUNCE_MS)
                lastQuery = narrowing.query
                withContext(Dispatchers.Default) {
                    buildTranscriptView(prepared.model, narrowing.query, narrowing.speakers, style)
                }
            }
            .collect { view.value = it }
    }
    return view
}

/** Переходы по стенограмме: из протокола, с ленты и по адресу экрана. */
@Stable
private class TranscriptNavigator(
    private val scope: CoroutineScope,
    private val listState: LazyListState,
    private val rowsOffset: () -> Int,
    private val stripPx: () -> Int,
    private val view: () -> TranscriptView,
) {
    /** Показывает реплику момента [timeMs] в текущем виде списка, без анимации. */
    fun scrollToTime(timeMs: Long) {
        scope.launch { scrollNow(timeMs) }
    }

    /**
     * После перехода со вкладки протокола значки ещё не измерены, поэтому первый шаг идёт без
     * отступа, а после кадра реплика выставляется уже под настоящую высоту значков.
     */
    private suspend fun scrollNow(timeMs: Long) {
        val position = view().nearestPosition(timeMs)
        if (position < 0) return
        val index = rowsOffset() + position
        val offset = stripPx()
        listState.scrollToItem(index, -offset)
        withFrameNanos { }
        if (stripPx() != offset) listState.scrollToItem(rowsOffset() + position, -stripPx())
    }

    /** Снимает сужение, если оно скрывает нужную реплику, и переходит к ней. */
    fun reveal(timeMs: Long, clearNarrowing: () -> Unit) {
        scope.launch {
            if (view().narrowed) {
                clearNarrowing()
                snapshotFlow { view().narrowed }.first { !it }
            }
            scrollNow(timeMs)
        }
    }
}

/** SCR-04 и SCR-05. */
@Composable
fun ResultRoute(route: Route.Result, controller: StenoController, actions: AppActions) {
    val job by remember(route.jobId) { controller.job(route.jobId) }.collectAsStateWithLifecycle()
    val detail = job
    val result = detail?.result
    if (result == null) {
        StenoTopBar(detail?.summary?.title.orEmpty(), onBack = actions.back)
        return
    }
    ResultContent(route, detail, result, controller, actions)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultContent(
    route: Route.Result,
    detail: JobDetailUi,
    result: ResultUi,
    controller: StenoController,
    actions: AppActions,
) {
    val window = LocalWindowLayout.current
    val colors = StenoTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var tab by rememberSaveable(route.tab) { mutableStateOf(route.tab) }
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable(stateSaver = SpeakerSetSaver) { mutableStateOf(emptySet<Int>()) }
    val searchField = rememberTextFieldState()
    val transcriptList = rememberLazyListState()
    val protocolList = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val player = rememberResultPlayer(result.audioPath)

    val matchStyle = remember(colors) { MatchStyle(colors.tertiaryContainer, colors.onTertiaryContainer) }
    val state = detail.summary.state
    val noSpeech = result.utterances.isEmpty()
    val phase = protocolPhase(state, result.protocol, noSpeech)
    val document = result.protocol.document

    val prepared by produceState<PreparedTranscript?>(null, result.utterances, result.language, matchStyle) {
        value = withContext(Dispatchers.Default) {
            val model = buildTranscriptModel(result.utterances, result.language)
            PreparedTranscript(
                model = model,
                initial = buildTranscriptView(model, "", emptySet(), matchStyle),
                spans = model.rows.map { RibbonSpan(it.speaker, it.startMs, it.endMs) },
            )
        }
    }

    val copiedPath = stringResource(R.string.scr04_path_copied)
    val noFolder = stringResource(R.string.scr04_folder_unavailable)
    val noShare = stringResource(R.string.scr04_share_unavailable)
    val shareTitle = stringResource(R.string.scr04_action_share)
    val exportDone = stringResource(R.string.scr04_export_done)
    val copiedDiagnostics = stringResource(R.string.scr05_diagnostics_copied)
    val pathLabel = stringResource(R.string.scr04_panel_path)
    val output by rememberUpdatedState(result.output)

    val exportTo: (ExportKind, Uri?) -> Unit = { kind, target ->
        if (target != null) {
            controller.export(detail.summary.id, kind, target)
            scope.launch {
                if (awaitWritten(context, target) && !output.exportFailed) snackbar.showSnackbar(exportDone)
            }
        }
    }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_JSON_TYPE)) {
        exportTo(ExportKind.TRANSCRIPT, it)
    }
    val exportProtocol = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_JSON_TYPE)) {
        exportTo(ExportKind.PROTOCOL, it)
    }
    val stem = result.title.substringBeforeLast('.')
    val onExport = { exportJson.launch("$stem.json") }
    val onExportProtocol = { exportProtocol.launch("$stem.protocol.json") }
    val closeSearch: () -> Unit = {
        searchOpen = false
        searchField.clearText()
        keyboard?.hide()
    }
    val resetNarrowing: () -> Unit = {
        searchField.clearText()
        selected = emptySet()
    }
    val onCopyPath: () -> Unit = {
        copyText(context, pathLabel, result.output.path)
        scope.launch { snackbar.showSnackbar(copiedPath) }
    }
    val onShare: () -> Unit = {
        scope.launch { if (!shareOutput(context, result.output, shareTitle)) snackbar.showSnackbar(noShare) }
    }
    val onOpenFolder: () -> Unit = {
        if (!openFolder(context, result.output)) scope.launch { snackbar.showSnackbar(noFolder) }
    }
    val onCopyDiagnostics: () -> Unit = {
        copyText(context, pathLabel, controller.diagnosticsPackage(detail.summary.id))
        scope.launch { snackbar.showSnackbar(copiedDiagnostics) }
    }
    BackHandler(enabled = searchOpen, onBack = closeSearch)

    val layoutKind = window.resultLayout
    val sideBySide = layoutKind == ResultLayout.SIDE_BY_SIDE
    val rail = layoutKind == ResultLayout.RAIL
    val showProtocolTab = !sideBySide
    val dockedActions = !window.largeText
    val outputInList = window.short && !rail
    val hintText = languageHint(result.language, state)
    val imeOpen = WindowInsets.isImeVisible
    val meta = audioMeta(result, noSpeech)
    val failed = result.output.exportFailed

    val prep = prepared
    val onImport = actions.importAudio

    val transcriptHeader: LazyListScope.() -> Unit = {
        if (!sideBySide && !rail) {
            item(key = KEY_BANNER, contentType = TYPE_HEADER) {
                ProtocolBanner(
                    phase = phase,
                    protocol = result.protocol,
                    modifier = Modifier
                        .padding(horizontal = window.gutter)
                        .padding(top = StenoSpace.s),
                    onClick = { tab = ResultTab.PROTOCOL },
                )
            }
        }
        if (hintText != null) {
            item(key = KEY_HINT, contentType = TYPE_HEADER) { LanguageHint(hintText) }
        }
        if (outputInList) {
            item(key = KEY_OUTPUT, contentType = TYPE_HEADER) {
                OutputLine(result.output, onOpen = { panelOpen = true })
            }
        }
    }
    val headerItems = (if (!sideBySide && !rail) 1 else 0) +
        (if (hintText != null) 1 else 0) +
        (if (outputInList) 1 else 0)

    val view = prep?.let { rememberTranscriptView(it, searchField, { selected }, matchStyle) }
    val currentView = view?.value
    val activeIndex = remember(prep, player) {
        derivedStateOf { if (player.engaged && prep != null) prep.model.indexAt(player.positionMs) else -1 }
    }
    val active: () -> Int = remember(activeIndex) { { activeIndex.value } }
    val rowsOffset by rememberUpdatedState(headerItems + 1)
    val pinnedStrip = (!window.short || searchOpen) &&
        (prep?.model?.speakers?.size ?: 0) <= MAX_PINNED_SPEAKERS
    var stripHeight by remember { mutableIntStateOf(0) }
    val pinnedPx by rememberUpdatedState(if (pinnedStrip) stripHeight else 0)
    val navigator = remember(transcriptList, view) {
        TranscriptNavigator(
            scope = scope,
            listState = transcriptList,
            rowsOffset = { rowsOffset },
            stripPx = { pinnedPx },
            view = { view?.value ?: TranscriptView(emptyList(), "", emptySet(), LongArray(0)) },
        )
    }
    val onOpenTranscript: () -> Unit = { tab = ResultTab.TRANSCRIPT }
    val openAt: (Long) -> Unit = { timeMs ->
        tab = ResultTab.TRANSCRIPT
        player.seekTo(timeMs, play = true)
        navigator.reveal(timeMs, resetNarrowing)
    }

    LaunchedEffect(prep, route.focusUtterance) {
        val model = prep?.model ?: return@LaunchedEffect
        val index = route.focusUtterance?.let(model::indexOfId) ?: return@LaunchedEffect
        val row = model.rows[index]
        player.seekTo(row.startMs, play = false)
        navigator.scrollToTime(row.startMs)
    }
    LaunchedEffect(transcriptList) {
        snapshotFlow { transcriptList.isScrollInProgress }.collect { scrolling -> if (scrolling) keyboard?.hide() }
    }

    val transcriptPane: @Composable (Modifier) -> Unit = { modifier ->
        if (noSpeech) {
            NoSpeech(onImport, modifier)
        } else if (prep != null && currentView != null) {
            TranscriptPane(
                model = prep.model,
                view = currentView,
                search = TranscriptSearch(
                    field = searchField,
                    open = searchOpen,
                    onOpen = { searchOpen = true },
                    onClose = closeSearch,
                    onToggleSpeaker = { number ->
                        selected = if (number in selected) selected - number else selected + number
                    },
                    onReset = resetNarrowing,
                ),
                listState = transcriptList,
                active = active,
                onRow = { row -> player.seekTo(row.startMs, play = true) },
                header = transcriptHeader,
                pinned = pinnedStrip,
                onStripHeight = { stripHeight = it },
                modifier = modifier,
            )
        }
    }
    val protocolPane: @Composable (Modifier) -> Unit = { modifier ->
        ProtocolPane(
            phase = phase,
            protocol = result.protocol,
            showBanner = !rail,
            listState = protocolList,
            onOpenChapter = { section ->
                val time = prep?.model?.indexOfId(section.firstUtterance)?.let { prep.model.rows[it].startMs }
                openAt(time ?: section.startMs)
            },
            onOpenItem = { item -> openAt(item.startMs) },
            onOpenTranscript = onOpenTranscript.takeIf { showProtocolTab },
            onCopyDiagnostics = onCopyDiagnostics,
            modifier = modifier,
        )
    }

    val input = remember(prep, document, result.durationMs) {
        prep?.let { ready ->
            TimelineInput(
                spans = ready.spans,
                speakers = ready.model.speakers,
                moments = document?.let(::protocolItems).orEmpty().map { RibbonMoment(it.startMs, ribbonKind(it.kind)) },
                durationMs = result.durationMs,
            )
        }
    }
    val playerRow: @Composable () -> Unit = {
        if (input != null && !noSpeech) {
            PlayerRow(
                player = player,
                input = input,
                selectedSpeakers = selected,
                searchTimesMs = currentView?.matchedTimesMs ?: EmptyTimes,
                onSeeked = navigator::scrollToTime,
            )
        }
    }
    val buttons: @Composable (Boolean, Boolean) -> Unit = { showImport, showProtocolExport ->
        ActionButtons(
            showImport = showImport,
            showProtocolExport = showProtocolExport,
            protocolReady = phase.hasDocument,
            exportFailed = failed,
            onExport = onExport,
            onExportProtocol = onExportProtocol,
            onImport = onImport,
            modifier = Modifier.padding(horizontal = window.gutter),
        )
    }
    val pathOrFailure: @Composable ColumnScope.() -> Unit = {
        if (failed) {
            ExportFailure(Modifier.padding(horizontal = window.gutter, vertical = StenoSpace.s))
        } else if (!outputInList) {
            OutputLine(result.output, onOpen = { panelOpen = true })
        }
    }
    val snackbarHost: @Composable (Modifier) -> Unit = { modifier ->
        SnackbarHost(
            hostState = snackbar,
            modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    val topBar: @Composable () -> Unit = {
        ResultTopBar(
            title = result.title,
            meta = meta,
            onBack = actions.back,
            onOpenPanel = { panelOpen = true }.takeIf { !dockedActions },
        )
    }

    when (layoutKind) {
        ResultLayout.STACKED -> Centered {
            topBar()
            if (!(searchOpen && imeOpen)) ResultTabs(tab, phase) { tab = it }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                TabPanes(
                    tab = tab,
                    transcript = { transcriptPane(Modifier.fillMaxSize()) },
                    protocol = { protocolPane(Modifier.fillMaxSize()) },
                )
                snackbarHost(Modifier.align(Alignment.BottomCenter))
            }
            if (!imeOpen) {
                Dock {
                    if (!(failed && window.short)) playerRow()
                    if (dockedActions) {
                        Spacer(Modifier.height(StenoSpace.xs))
                        pathOrFailure()
                        buttons(tab == ResultTab.TRANSCRIPT, tab == ResultTab.PROTOCOL)
                        Spacer(Modifier.height(StenoSpace.s))
                    }
                }
            }
        }
        ResultLayout.RAIL -> Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                topBar()
                if (!(searchOpen && imeOpen)) ResultTabs(tab, phase) { tab = it }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    TabPanes(
                        tab = tab,
                        transcript = { transcriptPane(Modifier.fillMaxSize()) },
                        protocol = { protocolPane(Modifier.fillMaxSize()) },
                    )
                    snackbarHost(Modifier.align(Alignment.BottomCenter))
                }
            }
            SideRail {
                Spacer(Modifier.height(StenoSpace.s))
                if (!noSpeech) {
                    ProtocolBanner(
                        phase = phase,
                        protocol = result.protocol,
                        modifier = Modifier.padding(horizontal = window.gutter),
                        onClick = { tab = ResultTab.PROTOCOL }.takeIf { tab == ResultTab.TRANSCRIPT },
                    )
                }
                playerRow()
                pathOrFailure()
                buttons(true, false)
                if (tab == ResultTab.PROTOCOL) {
                    Spacer(Modifier.height(StenoSpace.s))
                    buttons(false, true)
                }
                Spacer(Modifier.height(StenoSpace.s))
            }
        }
        ResultLayout.SIDE_BY_SIDE -> Column(Modifier.fillMaxSize()) {
            topBar()
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    transcriptPane(Modifier.fillMaxSize())
                    snackbarHost(Modifier.align(Alignment.BottomCenter))
                }
                Spacer(Modifier.width(StenoSize.hairline).fillMaxHeight().background(colors.outlineVariant))
                Box(Modifier.weight(1f).fillMaxHeight()) { protocolPane(Modifier.fillMaxSize()) }
            }
            Dock {
                playerRow()
                Row(
                    Modifier.fillMaxWidth().padding(vertical = StenoSpace.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) { pathOrFailure() }
                    Box(Modifier.weight(1.2f)) { buttons(true, true) }
                }
            }
        }
    }

    if (panelOpen) {
        ExportSheet(
            output = result.output,
            onDismiss = { panelOpen = false },
            onShare = onShare,
            onCopyPath = onCopyPath,
            onOpenFolder = onOpenFolder,
            extra = if (dockedActions) {
                null
            } else {
                {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(StenoSpace.s)) {
                        PrimaryAction(
                            text = stringResource(if (failed) R.string.scr04_export_retry else R.string.common_btn_export),
                            onClick = onExport,
                            tag = Tags.EXPORT,
                        )
                        OutlinedAction(
                            text = stringResource(R.string.common_btn_export_protocol),
                            onClick = onExportProtocol,
                            enabled = phase.hasDocument,
                            tag = Tags.EXPORT_PROTOCOL,
                        )
                        OutlinedAction(
                            text = stringResource(R.string.common_btn_import),
                            onClick = onImport,
                            tag = Tags.IMPORT,
                        )
                    }
                }
            },
        )
    }
}

private const val MIME_JSON_TYPE = "application/json"
private val EmptyTimes = LongArray(0)

/** Сводка записи. Без речи говорящих нет, и строка не притворяется, что они есть. */
@Composable
private fun audioMeta(result: ResultUi, noSpeech: Boolean): String {
    val duration = formatTimecode(result.durationMs)
    val language = stringResource(if (result.language == Language.EN) R.string.lang_en else R.string.lang_ru)
    return if (noSpeech) {
        stringResource(R.string.scr04_audio_meta_empty, duration, language)
    } else {
        stringResource(R.string.scr04_audio_meta, duration, speakersLabel(result.speakers), language)
    }
}

/** Подсказка о языке. Для русской записи без сбоев её нет. */
@Composable
private fun languageHint(language: Language, state: JobState): String? = when {
    state is JobState.Done && WARNING_LANG_FAILED in state.warnings -> stringResource(R.string.scr04_lang_failed)
    language == Language.EN -> stringResource(R.string.scr04_lang_detected_en)
    else -> null
}
