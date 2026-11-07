package ru.stenokot.app.ui.result

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
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
import ru.stenokot.app.ui.components.ActionSheet
import ru.stenokot.app.ui.components.ActionSheetRow
import ru.stenokot.app.ui.components.StenoTopBar
import ru.stenokot.app.ui.format.formatTimecode
import ru.stenokot.app.ui.format.speakersLabel
import ru.stenokot.app.ui.home.DeleteDialog
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
private const val KEY_HEADING = "heading"
private const val TYPE_HEADER = 10
private const val PINNED_STRIP_LIMIT = 2f

/**
 * Слой вкладок с репликами ниже шапки. Строки, ушедшие под вкладки при прокрутке, иначе забирают
 * у них область касания в дереве доступности: TalkBack на нижней половине вкладки читал реплику.
 */
private const val PANES_LAYER = -1f

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

/**
 * Время первой реплики, которую человек реально видит: под закреплёнными значками лежит ещё одна.
 * Пока список стоит в самом начале, запоминать нечего, значение null.
 */
private fun topRowTime(list: LazyListState, view: TranscriptView, rowsOffset: Int, stripPx: Int): Long? {
    if (list.firstVisibleItemIndex < rowsOffset) return null
    val item = list.layoutInfo.visibleItemsInfo.firstOrNull {
        it.index >= rowsOffset && it.offset + it.size > stripPx
    }
    return item?.let { view.rows.getOrNull(it.index - rowsOffset) }?.row?.startMs
}

/** Вид списка вместе с запросом, который его породил. */
private class NarrowedView(val narrowing: Narrowing, val view: TranscriptView)

/**
 * Вид списка: первый готов сразу, запрос считается в фоне с паузой на набор. После каждой смены
 * список ставится на место сам: якорь по ключу теряется, как только верхняя реплика исчезает из
 * вида, и тогда порядковый номер улетел бы к концу короткого списка. Позиция запрашивается через
 * `requestScrollToItem`: обычная прокрутка сработала бы по старым строкам ещё до применения новых.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
private fun rememberTranscriptView(
    prepared: PreparedTranscript,
    field: TextFieldState,
    speakers: () -> Set<Int>,
    style: MatchStyle,
    listState: LazyListState,
    rowsOffset: () -> Int,
    stripPx: () -> Int,
): State<TranscriptView> {
    val view = remember(prepared, style) { mutableStateOf(prepared.initial) }
    LaunchedEffect(prepared, style) {
        var lastQuery = ""
        var applied = Narrowing("", emptySet())
        var readingAtMs: Long? = null
        snapshotFlow { Narrowing(field.text.toString(), speakers()) }
            .distinctUntilChanged()
            .mapLatest { narrowing ->
                if (narrowing.query != lastQuery) delay(SEARCH_DEBOUNCE_MS)
                lastQuery = narrowing.query
                val next = withContext(Dispatchers.Default) {
                    buildTranscriptView(prepared.model, narrowing.query, narrowing.speakers, style)
                }
                NarrowedView(narrowing, next)
            }
            .collect { result ->
                val shownAtMs = topRowTime(listState, view.value, rowsOffset(), stripPx())
                val started = applied.query.isEmpty() && result.narrowing.query.isNotEmpty()
                val finished = applied.query.isNotEmpty() && result.narrowing.query.isEmpty()
                val typing = result.narrowing.query != applied.query
                if (started) readingAtMs = shownAtMs
                val target = when {
                    finished -> readingAtMs
                    typing -> null
                    else -> shownAtMs
                }
                applied = result.narrowing
                view.value = result.view
                when {
                    target != null -> {
                        val position = result.view.nearestPosition(target).coerceAtLeast(0)
                        listState.requestScrollToItem(rowsOffset() + position, -stripPx())
                    }
                    typing && !finished -> listState.requestScrollToItem(rowsOffset() - 1)
                }
            }
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
        val offset = stripPx()
        listState.requestScrollToItem(rowsOffset() + position, -offset)
        withFrameNanos { }
        if (stripPx() != offset) listState.requestScrollToItem(rowsOffset() + position, -stripPx())
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

/**
 * SCR-04 и SCR-05. Переход к реплике это разовая просьба: она выполняется и снимается с маршрута, иначе
 * повторилась бы при возвращении на экран. Вкладка задаётся явно, потому что после возврата сохранённая
 * вкладка восстанавливается как была, независимо от маршрута.
 */
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
    val jobId = detail.summary.id

    var tab by rememberSaveable(route.tab) { mutableStateOf(route.tab) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var exportOpen by rememberSaveable { mutableStateOf(false) }
    var speakersOpen by rememberSaveable { mutableStateOf(false) }
    var deleteOpen by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable(stateSaver = SpeakerSetSaver) { mutableStateOf(emptySet<Int>()) }
    var matchCursor by rememberSaveable { mutableIntStateOf(0) }
    val searchField = rememberTextFieldState()
    val transcriptList = rememberLazyListState()
    val protocolList = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val player = rememberResultPlayer(result.audioPath)
    val envelope by produceState<ByteArray?>(null, result.pcmPath) {
        value = result.pcmPath?.let { path -> withContext(Dispatchers.IO) { loadEnvelope(File(path)) } }
    }

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

    val noShare = stringResource(R.string.scr04_share_unavailable)
    val shareTitle = stringResource(R.string.scr04_action_share)
    val exportDone = stringResource(R.string.scr04_export_done)
    val copiedDiagnostics = stringResource(R.string.scr05_diagnostics_copied)
    val pathLabel = stringResource(R.string.scr04_panel_path)
    val output by rememberUpdatedState(result.output)

    val exportTo: (ExportKind, Uri?) -> Unit = { kind, target ->
        if (target != null) {
            controller.export(jobId, kind, target)
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
    val onSaveKind: (ExportKind) -> Unit = { kind ->
        if (kind == ExportKind.PROTOCOL) exportProtocol.launch("$stem.protocol.json") else onExport()
    }
    val closeSearch: () -> Unit = {
        searchOpen = false
        searchField.clearText()
        keyboard?.hide()
    }
    val resetNarrowing: () -> Unit = {
        searchField.clearText()
        selected = emptySet()
    }
    val onShare: () -> Unit = {
        scope.launch { if (!shareOutput(context, result.output, shareTitle)) snackbar.showSnackbar(noShare) }
    }
    val onCopyDiagnostics: () -> Unit = {
        copyText(context, pathLabel, controller.diagnosticsPackage(jobId))
        scope.launch { snackbar.showSnackbar(copiedDiagnostics) }
    }
    val onOpenFile: () -> Unit = { actions.navigator.push(Route.ResultFile(jobId)) }
    BackHandler(enabled = searchOpen, onBack = closeSearch)

    val layoutKind = window.resultLayout
    val sideBySide = layoutKind == ResultLayout.SIDE_BY_SIDE
    val rail = layoutKind == ResultLayout.RAIL
    val stacked = layoutKind == ResultLayout.STACKED
    val showProtocolTab = !sideBySide
    val searchTitle = searchOpen && stacked && !window.short
    val hintText = languageHint(result.language, state)
    val imeOpen = WindowInsets.isImeVisible
    val meta = audioMeta(result, noSpeech)
    val failed = result.output.exportFailed
    val bannerInList = stacked && phase != ProtocolPhase.APPLIED
    val headingInList = stacked && window.largeText
    val outputInList = !rail

    val prep = prepared
    val onImport = actions.importAudio

    val headerItems = if (searchOpen) {
        0
    } else {
        (if (headingInList) 1 else 0) + (if (bannerInList) 1 else 0) +
            (if (hintText != null) 1 else 0) + (if (outputInList) 1 else 0)
    }
    val transcriptHeader: LazyListScope.() -> Unit = {
        if (!searchOpen) {
            if (headingInList) {
                item(key = KEY_HEADING, contentType = TYPE_HEADER) {
                    ResultHeading(result.title, meta, Modifier.padding(horizontal = window.gutter + StenoSpace.s))
                }
            }
            if (bannerInList) {
                item(key = KEY_BANNER, contentType = TYPE_HEADER) {
                    ProtocolBanner(
                        phase = phase,
                        protocol = result.protocol,
                        modifier = Modifier
                            .padding(horizontal = window.gutter)
                            .padding(top = StenoSpace.xs),
                        onClick = { tab = ResultTab.PROTOCOL },
                    )
                }
            }
            if (hintText != null) {
                item(key = KEY_HINT, contentType = TYPE_HEADER) { LanguageHint(hintText) }
            }
            if (outputInList) {
                item(key = KEY_OUTPUT, contentType = TYPE_HEADER) { OutputLine(result.output, onOpen = onOpenFile) }
            }
        }
    }

    val rowsOffset by rememberUpdatedState(headerItems + 1)
    var stripHeight by remember { mutableIntStateOf(0) }
    var idleStripHeight by remember { mutableIntStateOf(0) }
    val pinLimitPx = with(LocalDensity.current) { (StenoSize.touchTarget * PINNED_STRIP_LIMIT).roundToPx() }
    val pinnedStrip = (!window.short && idleStripHeight <= pinLimitPx) || searchOpen
    val pinnedPx by rememberUpdatedState(if (pinnedStrip) stripHeight else 0)
    val view = prep?.let {
        rememberTranscriptView(
            prepared = it,
            field = searchField,
            speakers = { selected },
            style = matchStyle,
            listState = transcriptList,
            rowsOffset = { rowsOffset },
            stripPx = { pinnedPx },
        )
    }
    val currentView = view?.value
    val activeIndex = remember(prep, player) {
        derivedStateOf { if (player.engaged && prep != null) prep.model.indexAt(player.positionMs) else -1 }
    }
    val active: () -> Int = remember(activeIndex) { { activeIndex.value } }
    val cursorState = rememberUpdatedState(matchCursor)
    val viewState = rememberUpdatedState(currentView)
    val matchIndex = remember {
        derivedStateOf {
            viewState.value?.takeIf { it.query.isNotEmpty() }?.rows?.getOrNull(cursorState.value)?.row?.index ?: -1
        }
    }
    val match: () -> Int = remember(matchIndex) { { matchIndex.value } }
    val matchQuery = currentView?.query.orEmpty()
    LaunchedEffect(matchQuery) { matchCursor = 0 }
    LaunchedEffect(currentView) {
        val last = (currentView?.rows?.size ?: 0) - 1
        if (matchCursor > last) matchCursor = last.coerceAtLeast(0)
    }
    val stepMatch: (Int) -> Unit = { step ->
        val rows = currentView?.rows.orEmpty()
        if (rows.isNotEmpty()) {
            val next = (matchCursor + step).mod(rows.size)
            matchCursor = next
            transcriptList.requestScrollToItem(rowsOffset + next, -pinnedPx)
            player.seekTo(rows[next].row.startMs, play = player.isPlaying)
        }
    }
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
        tab = ResultTab.TRANSCRIPT
        player.seekTo(row.startMs, play = route.play)
        navigator.reveal(row.startMs, resetNarrowing)
        actions.navigator.replaceTop(route.copy(tab = ResultTab.TRANSCRIPT, focusUtterance = null, play = false))
    }
    LaunchedEffect(transcriptList) {
        snapshotFlow { transcriptList.isScrollInProgress }.collect { scrolling -> if (scrolling) keyboard?.hide() }
    }

    val transcriptPane: @Composable (Modifier) -> Unit = { modifier ->
        if (noSpeech) {
            NoSpeech(onImport, { actions.navigator.resetTo(Route.Home) }, modifier)
        } else if (prep != null && currentView != null) {
            TranscriptPane(
                model = prep.model,
                view = currentView,
                search = TranscriptSearch(
                    field = searchField,
                    open = searchOpen,
                    showBack = !searchTitle,
                    matchCursor = { matchCursor },
                    onOpen = { searchOpen = true },
                    onClose = closeSearch,
                    onStep = stepMatch,
                    onOpenSpeakers = { speakersOpen = true },
                    onSpeaker = { number -> selected = setOfNotNull(number) },
                    onReset = resetNarrowing,
                    onIdleHeight = { idleStripHeight = it },
                ),
                listState = transcriptList,
                active = active,
                match = match,
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
            showBanner = !rail && (sideBySide || phase != ProtocolPhase.APPLIED),
            listState = protocolList,
            onOpenChapter = { section ->
                val time = prep?.model?.indexOfId(section.firstUtterance)?.let { prep.model.rows[it].startMs }
                openAt(time ?: section.startMs)
            },
            onOpenItem = { index -> actions.navigator.push(Route.ProtocolItem(jobId, index)) },
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
                envelope = envelope,
                selectedSpeakers = selected,
                searchTimesMs = currentView?.matchedTimesMs ?: EmptyTimes,
                onSeeked = navigator::scrollToTime,
            )
        }
    }
    val footer: @Composable () -> Unit = {
        val tapHint = stringResource(R.string.scr04_player_tap_hint)
        PlayerFooter(
            hint = tapHint.takeIf { player.available && tab == ResultTab.TRANSCRIPT && !noSpeech && !window.short },
            onImport = onImport,
        )
    }
    val failure: @Composable () -> Unit = {
        if (failed) ExportFailure(Modifier.padding(horizontal = window.gutter, vertical = StenoSpace.s))
    }
    val snackbarHost: @Composable (Modifier) -> Unit = { modifier ->
        SnackbarHost(
            hostState = snackbar,
            modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    val tabStatus = bannerText(phase, result.protocol).takeIf { phase == ProtocolPhase.APPLIED }
    val topBar: @Composable () -> Unit = {
        ResultTopBar(
            heading = (result.title to meta).takeIf { !headingInList },
            onBack = actions.back,
            onExport = onExport,
            onMenu = { menuOpen = true },
        )
    }

    when (layoutKind) {
        ResultLayout.STACKED -> Centered {
            when {
                searchTitle -> StenoTopBar(
                    title = stringResource(R.string.scr04_search_title),
                    onBack = closeSearch,
                    backTag = Tags.SEARCH_CLOSE,
                )
                !searchOpen -> {
                    topBar()
                    ResultTabs(tab, phase, status = tabStatus) { tab = it }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().zIndex(PANES_LAYER)) {
                TabPanes(
                    tab = tab,
                    transcript = { transcriptPane(Modifier.fillMaxSize()) },
                    protocol = { protocolPane(Modifier.fillMaxSize()) },
                )
                snackbarHost(Modifier.align(Alignment.BottomCenter))
            }
            if (!imeOpen) {
                Dock {
                    failure()
                    if (!(failed && window.short)) playerRow()
                    if (!window.tiny) footer() else Spacer(Modifier.height(StenoSpace.s))
                }
            }
        }
        ResultLayout.RAIL -> Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (!(searchOpen && imeOpen)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BackButton(actions.back, Modifier.padding(start = StenoSpace.xs))
                        ResultTabs(tab, phase, Modifier.weight(1f), status = tabStatus) { tab = it }
                        MenuButton({ menuOpen = true })
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth().zIndex(PANES_LAYER)) {
                    TabPanes(
                        tab = tab,
                        transcript = { transcriptPane(Modifier.fillMaxSize()) },
                        protocol = { protocolPane(Modifier.fillMaxSize()) },
                    )
                    snackbarHost(Modifier.align(Alignment.BottomCenter))
                }
            }
            SideRail {
                RailTitle(result.title, meta)
                if (!noSpeech && phase != ProtocolPhase.APPLIED) {
                    ProtocolBanner(
                        phase = phase,
                        protocol = result.protocol,
                        modifier = Modifier.padding(horizontal = window.gutter),
                        onClick = { tab = ResultTab.PROTOCOL }.takeIf { tab == ResultTab.TRANSCRIPT },
                    )
                }
                Spacer(Modifier.height(StenoSpace.s))
                playerRow()
                failure()
                OutputLine(result.output, onOpen = onOpenFile)
                ActionButtons(failed, onExport, onImport, Modifier.padding(horizontal = window.gutter))
                Spacer(Modifier.height(StenoSpace.s))
            }
        }
        ResultLayout.SIDE_BY_SIDE -> Column(Modifier.fillMaxSize()) {
            topBar()
            Spacer(Modifier.fillMaxWidth().height(StenoSize.hairline).background(colors.outlineVariant))
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    transcriptPane(Modifier.fillMaxSize())
                    snackbarHost(Modifier.align(Alignment.BottomCenter))
                }
                Spacer(Modifier.width(StenoSize.hairline).fillMaxHeight().background(colors.outlineVariant))
                Box(Modifier.weight(1f).fillMaxHeight()) { protocolPane(Modifier.fillMaxSize()) }
            }
            Dock {
                failure()
                playerRow()
                footer()
            }
        }
    }

    if (menuOpen) {
        val closing: (() -> Unit) -> () -> Unit = { action ->
            {
                menuOpen = false
                action()
            }
        }
        ActionSheet(title = result.title, onDismiss = { menuOpen = false }) {
            ActionSheetRow(
                title = stringResource(R.string.scr04_menu_export),
                subtitle = stringResource(R.string.scr04_menu_export_hint),
                icon = R.drawable.ic_download,
                onClick = closing { exportOpen = true },
                tag = Tags.MENU_EXPORT,
            )
            ActionSheetRow(
                title = stringResource(R.string.scr04_panel_title),
                subtitle = stringResource(R.string.scr04_menu_file_hint),
                icon = R.drawable.ic_description,
                onClick = closing(onOpenFile),
                tag = Tags.EXPORT_PANEL_OPEN,
            )
            if (window.tiny) {
                ActionSheetRow(
                    title = stringResource(R.string.common_btn_import),
                    icon = R.drawable.ic_upload_file,
                    onClick = closing(onImport),
                    tag = Tags.IMPORT,
                )
            }
            ActionSheetRow(
                title = stringResource(R.string.scr04_menu_diagnostics),
                subtitle = stringResource(R.string.scr04_menu_diagnostics_hint),
                icon = R.drawable.ic_chart,
                onClick = closing { actions.navigator.push(Route.Diagnostics) },
                tag = Tags.DIAGNOSTICS_ROW,
            )
            ActionSheetRow(
                title = stringResource(R.string.scr04_menu_delete),
                subtitle = stringResource(R.string.scr04_menu_delete_hint),
                icon = R.drawable.ic_delete,
                onClick = closing { deleteOpen = true },
                tag = Tags.MENU_DELETE,
            )
        }
    }
    if (exportOpen) {
        ExportSheet(
            title = result.title,
            protocolReady = phase.hasDocument,
            exportFailed = failed,
            onDismiss = { exportOpen = false },
            onSave = onSaveKind,
            onShare = onShare,
        )
    }
    if (speakersOpen && prep != null) {
        SpeakerSheet(
            speakers = prep.model.speakers,
            replies = prep.model.repliesBySpeaker,
            total = prep.model.rows.size,
            current = selected.firstOrNull(),
            onApply = { number ->
                selected = setOfNotNull(number)
                speakersOpen = false
            },
            onDismiss = { speakersOpen = false },
        )
    }
    if (deleteOpen) {
        DeleteDialog(
            job = detail.summary,
            onConfirm = { removeExported ->
                deleteOpen = false
                controller.delete(jobId, removeExported)
                actions.back()
            },
            onDismiss = { deleteOpen = false },
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
