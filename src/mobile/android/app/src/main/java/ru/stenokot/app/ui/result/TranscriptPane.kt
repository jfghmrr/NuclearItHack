package ru.stenokot.app.ui.result

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import ru.stenokot.app.R
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.QuietAction
import ru.stenokot.app.ui.components.SpeakerBadge
import ru.stenokot.app.ui.components.StenoIcon
import ru.stenokot.app.ui.format.formatThousands
import ru.stenokot.app.ui.format.fragmentsOfLabel
import ru.stenokot.app.ui.format.speakersLabel
import ru.stenokot.app.ui.format.utterancesLabel
import ru.stenokot.app.ui.format.utterancesOfLabel
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.StenoMotion
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private const val NARROW_WIDTH_DP = 360
private const val KEY_STRIP = "strip"
private const val KEY_EMPTY = "empty"
private const val TYPE_ROW_FIRST = 0
private const val TYPE_ROW_NEXT = 1
private const val TYPE_STRIP = 2
private const val TYPE_OTHER = 3

/** Поля строк: на узком экране меньше, чтобы тексту осталась ширина. */
@Composable
private fun rememberRowLayout(): RowLayout {
    val window = LocalWindowLayout.current
    return remember(window) {
        RowLayout(cardInset = window.gutter - StenoSpace.s, textInset = window.gutter + StenoSpace.s)
    }
}

/**
 * Выбор говорящего на подложке. Пока фильтра нет, показывает всех, а с фильтром значок выбранного
 * участника и тоновую заливку: фильтр виден и без подсчёта строк.
 */
@Composable
private fun SpeakerPill(
    speakers: Int,
    selected: Int?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = StenoTheme.colors
    val active = selected != null
    val shape = RoundedCornerShape(StenoRadius.field)
    val label = if (selected != null) {
        stringResource(R.string.common_speaker, selected)
    } else {
        stringResource(R.string.scr04_speakers_pill, speakers)
    }
    val spoken = if (active) {
        stringResource(R.string.scr04_speaker_active, label)
    } else {
        stringResource(R.string.scr04_speakers_spoken, speakersLabel(speakers))
    }
    Row(
        modifier = modifier
            .heightIn(min = StenoSize.touchTarget)
            .background(if (active) colors.primaryContainer else colors.surfaceContainer, shape)
            .clip(shape)
            .testTag(Tags.LIST_SPEAKERS)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = spoken }
            .padding(horizontal = StenoSpace.m, vertical = StenoSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
    ) {
        if (selected != null) {
            SpeakerBadge(selected)
        } else {
            StenoIcon(R.drawable.ic_users, colors.onSurface)
        }
        Text(
            text = label,
            modifier = Modifier.weight(1f, fill = false),
            style = StenoTheme.type.bodyMedium,
            color = if (active) colors.primary else colors.onSurface,
        )
        StenoIcon(R.drawable.ic_expand_more, if (active) colors.primary else colors.onSurface)
    }
}

/**
 * Управление списком: выбор говорящего, счётчик и вход в поиск. Счётчик стоит рядом, пока хватает
 * ширины, а при фильтре уходит под строку: там он говорит, сколько реплик осталось из всех. Высота
 * снимается здесь, а не снаружи: контейнер перехода проходит через промежуточные размеры поля поиска,
 * и по ним строка ошибочно открепилась бы.
 */
@Composable
private fun SpeakerStrip(
    speakers: List<Int>,
    selected: Set<Int>,
    count: String,
    search: TranscriptSearch,
) {
    val colors = StenoTheme.colors
    val window = LocalWindowLayout.current
    val gutter = window.gutter
    val current = selected.firstOrNull()
    val countBelow = current != null || window.largeText || window.widthDp < NARROW_WIDTH_DP
    val countText: @Composable (Modifier) -> Unit = { modifier ->
        Text(
            text = count,
            modifier = modifier.testTag(Tags.UTT_COUNT),
            style = StenoTheme.type.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.background)
            .onSizeChanged { search.onIdleHeight(it.height) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = gutter, end = StenoSpace.s, top = StenoSpace.m, bottom = StenoSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StenoSpace.xs),
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                SpeakerPill(speakers.size, current, search.onOpenSpeakers, Modifier.weight(1f, fill = false))
                if (current != null) {
                    IconButton(onClick = { search.onSpeaker(null) }, modifier = Modifier.testTag(Tags.SPEAKER_CLEAR)) {
                        Icon(
                            painterResource(R.drawable.ic_close),
                            stringResource(R.string.scr04_speaker_clear),
                            tint = colors.onSurface,
                        )
                    }
                }
            }
            if (!countBelow) countText(Modifier.padding(horizontal = StenoSpace.xs))
            IconButton(onClick = search.onOpen, modifier = Modifier.testTag(Tags.SEARCH_OPEN)) {
                Icon(
                    painterResource(R.drawable.ic_search),
                    stringResource(R.string.scr04_search_open),
                    tint = colors.onSurface,
                )
            }
        }
        if (countBelow) countText(Modifier.padding(start = gutter + StenoSpace.s, bottom = StenoSpace.xs))
    }
}

/** Поле поиска. Открывается с фокусом и сразу показывает клавиатуру. */
@Composable
private fun SearchField(state: TextFieldState, showBack: Boolean, onClose: () -> Unit) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val gutter = LocalWindowLayout.current.gutter
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (showBack) StenoSpace.xs else gutter, end = gutter, top = StenoSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            IconButton(onClick = onClose, modifier = Modifier.testTag(Tags.SEARCH_CLOSE)) {
                Icon(
                    painterResource(R.drawable.ic_arrow_back),
                    stringResource(R.string.scr04_search_close),
                    tint = colors.onSurface,
                )
            }
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = StenoSize.touchTarget)
                .background(colors.surfaceContainer, RoundedCornerShape(StenoRadius.field))
                .padding(start = StenoSpace.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
        ) {
            StenoIcon(R.drawable.ic_search, colors.onSurfaceVariant)
            BasicTextField(
                state = state,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = StenoSpace.s)
                    .focusRequester(focus)
                    .testTag(Tags.SEARCH_FIELD),
                textStyle = type.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                onKeyboardAction = { keyboard?.hide() },
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
}

/** Счётчик совпадений и шаги между ними. Смена номера озвучивается, не перебивая чтение. */
@Composable
private fun MatchBar(position: String, onStep: (Int) -> Unit) {
    val colors = StenoTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = LocalWindowLayout.current.gutter, end = StenoSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = position,
            modifier = Modifier
                .weight(1f)
                .testTag(Tags.SEARCH_COUNT)
                .semantics { liveRegion = LiveRegionMode.Polite },
            style = StenoTheme.type.bodySmall,
            color = colors.onSurfaceVariant,
        )
        IconButton(onClick = { onStep(-1) }, modifier = Modifier.testTag(Tags.SEARCH_PREV)) {
            Icon(
                painterResource(R.drawable.ic_expand_less),
                stringResource(R.string.scr04_search_prev),
                tint = colors.onSurface,
            )
        }
        IconButton(onClick = { onStep(1) }, modifier = Modifier.testTag(Tags.SEARCH_NEXT)) {
            Icon(
                painterResource(R.drawable.ic_expand_more),
                stringResource(R.string.scr04_search_next),
                tint = colors.onSurface,
            )
        }
    }
}

/** Управление списком: говорящие с поиском или поле поиска. Переключение короткое и только по нажатию. */
@Composable
private fun TranscriptControls(
    model: TranscriptModel,
    view: TranscriptView,
    search: TranscriptSearch,
) {
    val total = model.rows.size
    val shown = view.rows.size
    val count = if (view.narrowed) {
        stringResource(R.string.scr04_utt_filtered, formatThousands(shown.toLong()), utterancesOfLabel(total))
    } else {
        utterancesLabel(total)
    }
    AnimatedContent(
        targetState = search.open,
        transitionSpec = { fadeIn(tween(StenoMotion.SHORT)) togetherWith fadeOut(tween(StenoMotion.SHORT)) },
        label = "controls",
    ) { open ->
        if (open) {
            Column(Modifier.fillMaxWidth().background(StenoTheme.colors.background)) {
                SearchField(search.field, search.showBack, search.onClose)
                if (view.query.isNotEmpty() && shown > 0) {
                    val position = stringResource(
                        R.string.scr04_search_position,
                        formatThousands((search.matchCursor() + 1).toLong()),
                        fragmentsOfLabel(shown),
                    )
                    MatchBar(position, search.onStep)
                }
            }
        } else {
            SpeakerStrip(model.speakers, view.speakers, count, search)
        }
    }
}

/** Поиск и фильтр снаружи списка: они переживают поворот вместе с экраном. */
internal class TranscriptSearch(
    val field: TextFieldState,
    val open: Boolean,
    val showBack: Boolean,
    val matchCursor: () -> Int,
    val onOpen: () -> Unit,
    val onClose: () -> Unit,
    val onStep: (Int) -> Unit,
    val onOpenSpeakers: () -> Unit,
    val onSpeaker: (Int?) -> Unit,
    val onReset: () -> Unit,
    val onIdleHeight: (Int) -> Unit,
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
 * Лента реплик. [header] это служебные строки под управлением: они уезжают вместе со списком. При
 * [pinned] управление закреплено, чтобы фильтр оставался под рукой.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TranscriptPane(
    model: TranscriptModel,
    view: TranscriptView,
    search: TranscriptSearch,
    listState: LazyListState,
    active: () -> Int,
    match: () -> Int,
    onRow: (TranscriptRowUi) -> Unit,
    onEdit: (TranscriptRowUi) -> Unit,
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
        header()
        if (view.rows.isEmpty() && view.narrowed) {
            item(key = KEY_EMPTY, contentType = TYPE_OTHER) { NothingFound(search.onReset) }
        }
        items(
            items = view.rows,
            key = { it.row.id },
            contentType = { if (it.startsGroup) TYPE_ROW_FIRST else TYPE_ROW_NEXT },
        ) { item ->
            TranscriptRow(item, layout, strings, active, match, onRow, onEdit)
        }
    }
}
