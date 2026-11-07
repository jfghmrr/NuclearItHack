package ru.stenokot.app.ui.result

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.stenokot.app.R
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.QuietAction
import ru.stenokot.app.ui.components.SpeakerBadge
import ru.stenokot.app.ui.components.ledgerMarginWidth
import ru.stenokot.app.ui.format.formatThousands
import ru.stenokot.app.ui.format.speakersLabel
import ru.stenokot.app.ui.format.utterancesLabel
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.StenoMotion
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

/** Сколько значков ещё переносится по строкам. Больше листается в одну строку. */
private const val MAX_WRAPPED_SPEAKERS = 8

private const val MIN_LEDGER_WIDTH_DP = 340
private const val DIMMED_ALPHA = 0.45f
private const val KEY_STRIP = "strip"
private const val KEY_EMPTY = "empty"
private const val TYPE_ROW_FIRST = 0
private const val TYPE_ROW_NEXT = 1
private const val TYPE_STRIP = 2
private const val TYPE_OTHER = 3

/** Строка разрешает поле с временем, только если тексту остаётся достаточно ширины. */
@Composable
private fun rememberRowLayout(): RowLayout {
    val window = LocalWindowLayout.current
    val width = LocalConfiguration.current.screenWidthDp
    val margin = ledgerMarginWidth()
    return remember(window, width, margin) {
        RowLayout(!window.largeText && width >= MIN_LEDGER_WIDTH_DP, window.gutter, margin)
    }
}

/** Значок-переключатель фильтра. Выбранный обведён: выбор виден и без цвета. */
@Composable
private fun SpeakerChip(number: Int, selected: Boolean, dimmed: Boolean, label: String, onToggle: () -> Unit) {
    val colors = StenoTheme.colors
    Box(
        modifier = Modifier
            .size(StenoSize.touchTarget)
            .testTag(Tags.chipSpeaker(number))
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val ring = RoundedCornerShape(StenoRadius.s)
        Box(
            modifier = Modifier
                .size(StenoSize.icon + StenoSpace.s + StenoSpace.xs / 2)
                .then(if (selected) Modifier.border(2.dp, colors.onSurface, ring) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            SpeakerBadge(number, Modifier.then(if (dimmed) Modifier.alpha(DIMMED_ALPHA) else Modifier))
        }
    }
}

/**
 * Значки говорящих, счётчик и вход в поиск. Легенда заодно служит фильтром. Несколько значков
 * переносятся на вторую строку, чтобы участник не прятался за краем. Говорящих может быть сколько
 * угодно, и когда их много, значки листаются в одну строку: иначе фильтр занял бы весь экран.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeakerStrip(
    speakers: List<Int>,
    selected: Set<Int>,
    count: String,
    onToggle: (Int) -> Unit,
    onSearch: () -> Unit,
) {
    val colors = StenoTheme.colors
    val gutter = LocalWindowLayout.current.gutter
    val spoken = stringResource(R.string.scr04_speakers_spoken, speakersLabel(speakers.size))
    val filterTemplate = stringResource(R.string.scr04_filter_spoken)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(start = gutter - StenoSpace.s, end = StenoSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val chips: @Composable () -> Unit = {
            speakers.forEach { number ->
                SpeakerChip(
                    number = number,
                    selected = number in selected,
                    dimmed = selected.isNotEmpty() && number !in selected,
                    label = filterTemplate.format(number),
                    onToggle = { onToggle(number) },
                )
            }
        }
        val chipsModifier = Modifier
            .weight(1f)
            .testTag(Tags.LIST_SPEAKERS)
            .semantics { contentDescription = spoken }
        if (speakers.size <= MAX_WRAPPED_SPEAKERS) {
            FlowRow(chipsModifier) { chips() }
        } else {
            Row(chipsModifier.horizontalScroll(rememberScrollState())) { chips() }
        }
        Text(
            text = count,
            modifier = Modifier
                .padding(start = StenoSpace.s)
                .testTag(Tags.UTT_COUNT),
            style = StenoTheme.type.bodySmall,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
        IconButton(onClick = onSearch, modifier = Modifier.testTag(Tags.SEARCH_OPEN)) {
            Icon(
                painterResource(R.drawable.ic_search),
                stringResource(R.string.scr04_search_open),
                tint = colors.onSurface,
            )
        }
    }
}

/** Поле поиска. Открывается с фокусом и сразу показывает клавиатуру. */
@Composable
private fun SearchField(state: TextFieldState, found: String, onClose: () -> Unit) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = StenoSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.testTag(Tags.SEARCH_CLOSE)) {
            Icon(
                painterResource(R.drawable.ic_arrow_back),
                stringResource(R.string.scr04_search_close),
                tint = colors.onSurface,
            )
        }
        BasicTextField(
            state = state,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                .testTag(Tags.SEARCH_FIELD),
            textStyle = type.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            lineLimits = TextFieldLineLimits.SingleLine,
            decorator = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (state.text.isEmpty()) {
                        Text(
                            text = stringResource(R.string.scr04_search_hint),
                            style = type.bodyLarge,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    field()
                }
            },
        )
        if (state.text.isNotEmpty()) {
            Text(
                text = found,
                modifier = Modifier.padding(start = StenoSpace.s).testTag(Tags.SEARCH_COUNT),
                style = type.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
            IconButton(onClick = { state.clearText() }, modifier = Modifier.testTag(Tags.SEARCH_CLEAR)) {
                Icon(
                    painterResource(R.drawable.ic_close),
                    stringResource(R.string.scr04_search_clear),
                    tint = colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** Управление списком: значки с поиском или поле поиска. Переключение короткое и только по нажатию. */
@Composable
private fun TranscriptControls(
    model: TranscriptModel,
    view: TranscriptView,
    search: TranscriptSearch,
) {
    val total = model.rows.size
    val shown = view.rows.size
    val count = if (view.narrowed) {
        stringResource(R.string.scr04_utt_filtered, formatThousands(shown.toLong()), utterancesLabel(total))
    } else {
        utterancesLabel(total)
    }
    val found = stringResource(R.string.scr04_search_found, formatThousands(shown.toLong()))
    AnimatedContent(
        targetState = search.open,
        transitionSpec = { fadeIn(tween(StenoMotion.SHORT)) togetherWith fadeOut(tween(StenoMotion.SHORT)) },
        label = "controls",
    ) { open ->
        if (open) {
            SearchField(search.field, found, search.onClose)
        } else {
            SpeakerStrip(model.speakers, view.speakers, count, search.onToggleSpeaker, search.onOpen)
        }
    }
}

/** Поиск и фильтр снаружи списка: они переживают поворот вместе с экраном. */
internal class TranscriptSearch(
    val field: TextFieldState,
    val open: Boolean,
    val onOpen: () -> Unit,
    val onClose: () -> Unit,
    val onToggleSpeaker: (Int) -> Unit,
    val onReset: () -> Unit,
)

/** Пустой результат поиска: что случилось и как вернуть список. */
@Composable
private fun NothingFound(onReset: () -> Unit) {
    val gutter = LocalWindowLayout.current.gutter
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = StenoSpace.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(StenoSpace.s),
    ) {
        Text(
            text = stringResource(R.string.scr04_search_none),
            style = StenoTheme.type.bodyLarge,
            color = StenoTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        QuietAction(stringResource(R.string.scr04_search_reset), onReset, tag = Tags.SEARCH_RESET)
    }
}

/**
 * Лента реплик. [header] это строки над значками: они уезжают вместе со списком. При [pinned]
 * значки закреплены, чтобы фильтр оставался под рукой.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TranscriptPane(
    model: TranscriptModel,
    view: TranscriptView,
    search: TranscriptSearch,
    listState: LazyListState,
    active: () -> Int,
    onRow: (TranscriptRowUi) -> Unit,
    header: LazyListScope.() -> Unit,
    pinned: Boolean,
    onStripHeight: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val strings = remember(context) { RowStrings(context) }
    val layout = rememberRowLayout()
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(Tags.LIST_TRANSCRIPT),
        state = listState,
        contentPadding = PaddingValues(bottom = StenoSpace.s),
    ) {
        header()
        val controls: @Composable () -> Unit = {
            Box(Modifier.onSizeChanged { onStripHeight(it.height) }) {
                TranscriptControls(model, view, search)
            }
        }
        if (pinned) {
            stickyHeader(key = KEY_STRIP, contentType = TYPE_STRIP) { controls() }
        } else {
            item(key = KEY_STRIP, contentType = TYPE_STRIP) { controls() }
        }
        if (view.rows.isEmpty() && view.narrowed) {
            item(key = KEY_EMPTY, contentType = TYPE_OTHER) { NothingFound(search.onReset) }
        }
        items(
            items = view.rows,
            key = { it.row.id },
            contentType = { if (it.startsGroup) TYPE_ROW_FIRST else TYPE_ROW_NEXT },
        ) { item ->
            TranscriptRow(item, layout, strings, active, onRow)
        }
    }
}
