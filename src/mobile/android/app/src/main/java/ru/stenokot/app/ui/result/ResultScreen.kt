package ru.stenokot.app.ui.result

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.stenokot.app.R
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.OutlinedAction
import ru.stenokot.app.ui.components.CenteredState
import ru.stenokot.app.ui.components.MascotKind
import ru.stenokot.app.ui.components.MascotPlate
import ru.stenokot.app.ui.components.PrimaryAction
import ru.stenokot.app.ui.components.QuietAction
import ru.stenokot.app.ui.components.StateTextWidth
import ru.stenokot.app.ui.components.StenoIcon
import ru.stenokot.app.ui.layout.ContentMaxWidth
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.layout.WindowLayout
import ru.stenokot.app.ui.nav.ResultTab
import ru.stenokot.app.ui.theme.StenoMotion
import ru.stenokot.app.ui.theme.EnterMotion
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private val RailWidth = 320.dp
private val TabMinFont = 7.sp
private val TabIndicatorHeight = 3.dp
private val ReadyDot = 6.dp
private const val NARROW_TITLE_DP = 380

/** Раскладка экрана результата. */
internal enum class ResultLayout { STACKED, RAIL, SIDE_BY_SIDE }

/**
 * Выбор раскладки. На низком окне вертикаль важнее всего, поэтому плеер и файл уходят в боковую
 * колонку. Стенограмма рядом с протоколом только там, где обоим хватает и ширины, и высоты.
 */
internal val WindowLayout.resultLayout: ResultLayout
    get() = when {
        (compactHeight || short) && !compactWidth -> ResultLayout.RAIL
        width == WindowWidthSizeClass.Expanded -> ResultLayout.SIDE_BY_SIDE
        else -> ResultLayout.STACKED
    }

/** Заметная кнопка экспорта. Сразу открывает системное сохранение JSON, без промежуточных шагов. */
@Composable
internal fun ExportButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = StenoTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier.testTag(Tags.EXPORT),
        shape = RoundedCornerShape(StenoRadius.m),
        contentPadding = PaddingValues(horizontal = StenoSpace.m, vertical = StenoSpace.xs),
        elevation = null,
        colors = ButtonDefaults.buttonColors(containerColor = colors.primaryContainer, contentColor = colors.primary),
    ) {
        Text(stringResource(R.string.scr04_btn_export), style = StenoTheme.type.labelLarge, maxLines = 1, softWrap = false)
    }
}

/** Кнопка меню записи. */
@Composable
internal fun MenuButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.testTag(Tags.RESULT_MENU)) {
        Icon(
            painterResource(R.drawable.ic_more),
            stringResource(R.string.scr04_menu_open),
            tint = StenoTheme.colors.onSurface,
        )
    }
}

/** Имя файла. Усекается в середине, текст узла остаётся полным. */
@Composable
private fun ResultTitle(title: String) {
    val window = LocalWindowLayout.current
    Text(
        text = title,
        modifier = Modifier.testTag(Tags.FILE_NAME).semantics { heading() },
        style = if (window.widthDp < NARROW_TITLE_DP) StenoTheme.type.cardTitle else StenoTheme.type.titleLarge,
        color = StenoTheme.colors.onSurface,
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
    )
}

/** Сводка записи: длительность, число говорящих и язык. */
@Composable
internal fun ResultMeta(meta: String, modifier: Modifier = Modifier) {
    Text(
        text = meta,
        modifier = modifier.testTag(Tags.AUDIO_META),
        style = StenoTheme.type.bodySmall,
        color = StenoTheme.colors.onSurfaceVariant,
    )
}

/** Имя файла и сводка целиком: для начала списка, когда в верхней панели им не хватает ширины. */
@Composable
internal fun ResultHeading(title: String, meta: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = title,
            modifier = Modifier.testTag(Tags.FILE_NAME).semantics { heading() },
            style = StenoTheme.type.cardTitle,
            color = StenoTheme.colors.onSurface,
        )
        ResultMeta(meta)
    }
}

/**
 * Верхняя панель: файл и его сводка, заметный экспорт и меню записи. На крупном шрифте кнопка оставила
 * бы имени пару букв, поэтому оно уходит в начало списка: [heading] тогда равно null.
 */
@Composable
internal fun ResultTopBar(
    heading: Pair<String, String>?,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = StenoSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.xs),
    ) {
        BackButton(onBack)
        Column(Modifier.weight(1f).padding(vertical = StenoSpace.xs)) {
            heading?.let { (title, meta) ->
                ResultTitle(title)
                ResultMeta(meta)
            }
        }
        ExportButton(onExport)
        MenuButton(onMenu)
    }
}

/** Заголовок боковой колонки: файл и сводка без кнопки назад, она осталась у вкладок. */
@Composable
internal fun RailTitle(title: String, meta: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = LocalWindowLayout.current.gutter, vertical = StenoSpace.s)) {
        ResultTitle(title)
        ResultMeta(meta)
    }
}

/** Кнопка назад. */
@Composable
internal fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onBack, modifier = modifier.testTag(Tags.BACK)) {
        Icon(
            painterResource(R.drawable.ic_arrow_back),
            stringResource(R.string.common_back),
            tint = StenoTheme.colors.onSurface,
        )
    }
}

/**
 * Подпись вкладки. Ширина вкладки фиксирована, и на крупном шрифте слово разорвалось бы посередине,
 * поэтому размер сам уменьшается ровно настолько, чтобы слово осталось целым.
 */
@Composable
private fun TabLabel(text: String, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    val style = StenoTheme.type.labelLarge
    BasicText(
        text = text,
        modifier = modifier,
        style = style,
        maxLines = 1,
        color = { color },
        autoSize = TextAutoSize.StepBased(minFontSize = TabMinFont, maxFontSize = style.fontSize),
    )
}

/** Положение протокола на вкладке: готовый отмечен точкой, остальные значком состояния. */
@Composable
private fun ProtocolMark(phase: ProtocolPhase) {
    when (phase) {
        ProtocolPhase.NO_SPEECH -> Unit
        ProtocolPhase.APPLIED -> Spacer(Modifier.size(ReadyDot).background(StenoTheme.colors.primary, CircleShape))
        else -> Icon(painterResource(phase.icon), null, Modifier.size(StenoSize.iconSmall))
    }
}

/**
 * Вкладки результата. Значок на вкладке протокола держит статус, когда баннера в списке нет. Если
 * передан [status], он остаётся в дереве доступности под прежней меткой на линии под вкладками:
 * наложить узел на вкладку нельзя, он отнял бы у неё часть области нажатия.
 */
@Composable
internal fun ResultTabs(
    tab: ResultTab,
    phase: ProtocolPhase,
    modifier: Modifier = Modifier,
    status: String? = null,
    onTab: (ResultTab) -> Unit,
) {
    val colors = StenoTheme.colors
    Column(modifier.fillMaxWidth().background(colors.background)) {
        PrimaryTabRow(
            selectedTabIndex = tab.ordinal,
            modifier = Modifier.padding(horizontal = LocalWindowLayout.current.gutter),
            containerColor = colors.background,
            contentColor = colors.primary,
            indicator = {
                TabRowDefaults.PrimaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tab.ordinal, matchContentSize = false),
                    width = Dp.Unspecified,
                    height = TabIndicatorHeight,
                    color = colors.primary,
                    shape = RoundedCornerShape(topStart = TabIndicatorHeight, topEnd = TabIndicatorHeight),
                )
            },
            divider = { },
        ) {
            Tab(
                selected = tab == ResultTab.TRANSCRIPT,
                onClick = { onTab(ResultTab.TRANSCRIPT) },
                modifier = Modifier.testTag(Tags.TAB_TRANSCRIPT),
                selectedContentColor = colors.primary,
                unselectedContentColor = colors.onSurfaceVariant,
                text = { TabLabel(stringResource(R.string.common_tab_transcript)) },
            )
            Tab(
                selected = tab == ResultTab.PROTOCOL,
                onClick = { onTab(ResultTab.PROTOCOL) },
                modifier = Modifier.testTag(Tags.TAB_PROTOCOL),
                selectedContentColor = colors.primary,
                unselectedContentColor = colors.onSurfaceVariant,
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
                    ) {
                        TabLabel(stringResource(R.string.common_tab_protocol), Modifier.weight(1f, fill = false))
                        ProtocolMark(phase)
                    }
                },
            )
        }
        val divider = Modifier.fillMaxWidth().height(StenoSize.hairline).background(colors.outlineVariant)
        Spacer(
            if (status != null) {
                divider.testTag(Tags.BANNER_LLM).clearAndSetSemantics {
                    text = AnnotatedString(status)
                    liveRegion = LiveRegionMode.Polite
                }
            } else {
                divider
            },
        )
    }
}

/** Кнопки файла в боковой колонке: там есть место и для экспорта, и для импорта рядом. */
@Composable
internal fun ActionButtons(
    exportFailed: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrimaryAction(
            text = stringResource(if (exportFailed) R.string.scr04_export_retry else R.string.scr04_btn_export),
            onClick = onExport,
            modifier = Modifier.weight(1f),
            tag = Tags.EXPORT,
            dense = true,
        )
        OutlinedAction(
            text = stringResource(R.string.common_btn_import),
            onClick = onImport,
            modifier = Modifier.weight(1f),
            tag = Tags.IMPORT,
            dense = true,
        )
    }
}

/** Нет речи: честный результат, а не ошибка. JSON уже сохранён с пустым списком. */
@Composable
internal fun NoSpeech(onPickOther: () -> Unit, onLibrary: () -> Unit, modifier: Modifier = Modifier) {
    val colors = StenoTheme.colors
    val layout = LocalWindowLayout.current
    CenteredState(modifier.fillMaxSize()) {
        MascotPlate(
            MascotKind.NO_SPEECH,
            Modifier.padding(bottom = StenoSpace.s),
            plate = if (layout.hintsInContent) StenoSize.mascotPlateSmall else StenoSize.mascotPlate,
            motion = EnterMotion.EMPTY,
        )
        Text(
            text = stringResource(R.string.scr04_no_speech_title),
            modifier = Modifier.widthIn(max = StateTextWidth).semantics { heading() },
            style = if (layout.largeText) StenoTheme.type.titleLarge else StenoTheme.type.headline,
            color = colors.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.scr04_no_speech_body),
            modifier = Modifier.widthIn(max = StateTextWidth),
            style = StenoTheme.type.bodyLarge,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        PrimaryAction(
            text = stringResource(R.string.scr04_pick_other),
            onClick = onPickOther,
            modifier = Modifier.padding(top = StenoSpace.m).fillMaxWidth().widthIn(max = StateTextWidth),
            icon = R.drawable.ic_upload_file,
            tag = Tags.PICK_OTHER,
        )
        QuietAction(stringResource(R.string.scr03_to_library), onLibrary, tag = Tags.TO_LIBRARY)
    }
}

/** Нижняя часть экрана: карточка плеера сама отделяет себя от текста. */
@Composable
internal fun Dock(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(StenoTheme.colors.background)
            .padding(top = StenoSpace.xs),
        content = content,
    )
}

/** Узкая колонка по центру окна. */
@Composable
internal fun Centered(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = ContentMaxWidth).fillMaxSize(), content = content)
    }
}

/** Смена вкладки короткая и только по действию человека. */
@Composable
internal fun TabPanes(
    tab: ResultTab,
    transcript: @Composable () -> Unit,
    protocol: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Crossfade(tab, modifier, animationSpec = tween(StenoMotion.SHORT), label = "tab") { current ->
        when (current) {
            ResultTab.TRANSCRIPT -> transcript()
            ResultTab.PROTOCOL -> protocol()
        }
    }
}

/** Боковая колонка низкого окна: всё, что не текст, прокручивается отдельно. */
@Composable
internal fun SideRail(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = StenoTheme.colors
    Row(modifier.width(RailWidth + StenoSize.hairline).fillMaxHeight()) {
        Spacer(Modifier.width(StenoSize.hairline).fillMaxHeight().background(colors.outlineVariant))
        Column(
            modifier = Modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
            content = content,
        )
    }
}

/** Заметка о языке записи. Читается перед списком. */
@Composable
internal fun LanguageHint(text: String, modifier: Modifier = Modifier) {
    val colors = StenoTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .padding(horizontal = LocalWindowLayout.current.gutter)
            .testTag(Tags.LANG_HINT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
    ) {
        StenoIcon(R.drawable.ic_language, colors.onSurfaceVariant, size = StenoSize.iconSmall)
        Text(text, style = StenoTheme.type.bodySmall, color = colors.onSurfaceVariant)
    }
}
