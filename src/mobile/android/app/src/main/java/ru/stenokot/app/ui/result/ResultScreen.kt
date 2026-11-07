package ru.stenokot.app.ui.result

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.stenokot.app.R
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.OutlinedAction
import ru.stenokot.app.ui.components.PrimaryAction
import ru.stenokot.app.ui.components.StenoIcon
import ru.stenokot.app.ui.layout.ContentMaxWidth
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.layout.WindowLayout
import ru.stenokot.app.ui.nav.ResultTab
import ru.stenokot.app.ui.theme.StenoMotion
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private val RailWidth = 320.dp
private const val IMPORT_WEIGHT = 1f
private const val EXPORT_WEIGHT = 1.25f

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

/** Верхняя панель: файл и его сводка. Имя усекается в середине, текст узла остаётся полным. */
@Composable
internal fun ResultTopBar(
    title: String,
    meta: String,
    onBack: () -> Unit,
    onOpenPanel: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = StenoTheme.colors
    val gutter = LocalWindowLayout.current.gutter
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = StenoSpace.xs, end = if (onOpenPanel != null) StenoSpace.xs else gutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.xs),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag(Tags.BACK)) {
            Icon(
                painterResource(R.drawable.ic_arrow_back),
                stringResource(R.string.common_back),
                tint = colors.onSurface,
            )
        }
        Column(Modifier.weight(1f).padding(vertical = StenoSpace.xs)) {
            Text(
                text = title,
                modifier = Modifier.testTag(Tags.FILE_NAME).semantics { heading() },
                style = StenoTheme.type.labelLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Text(
                text = meta,
                modifier = Modifier.testTag(Tags.AUDIO_META),
                style = StenoTheme.type.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        if (onOpenPanel != null) {
            IconButton(onClick = onOpenPanel, modifier = Modifier.testTag(Tags.EXPORT_PANEL_OPEN)) {
                Icon(
                    painterResource(R.drawable.ic_description),
                    stringResource(R.string.scr04_panel_title),
                    tint = colors.onSurface,
                )
            }
        }
    }
}

/** Вкладки результата. Значок на вкладке протокола держит статус, когда баннер уже уехал. */
@Composable
internal fun ResultTabs(tab: ResultTab, phase: ProtocolPhase, onTab: (ResultTab) -> Unit) {
    val colors = StenoTheme.colors
    PrimaryTabRow(
        selectedTabIndex = tab.ordinal,
        containerColor = colors.surface,
        contentColor = colors.primary,
        divider = { HorizontalDivider(thickness = StenoSize.hairline, color = colors.outlineVariant) },
    ) {
        Tab(
            selected = tab == ResultTab.TRANSCRIPT,
            onClick = { onTab(ResultTab.TRANSCRIPT) },
            modifier = Modifier.testTag(Tags.TAB_TRANSCRIPT),
            selectedContentColor = colors.primary,
            unselectedContentColor = colors.onSurfaceVariant,
            text = { Text(stringResource(R.string.common_tab_transcript), style = StenoTheme.type.labelMedium) },
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
                    horizontalArrangement = Arrangement.spacedBy(StenoSpace.xs),
                ) {
                    Text(stringResource(R.string.common_tab_protocol), style = StenoTheme.type.labelMedium)
                    if (phase != ProtocolPhase.NO_SPEECH) {
                        Icon(painterResource(phase.icon), null, Modifier.size(StenoSize.iconSmall))
                    }
                }
            },
        )
    }
}

/** Кнопки файла. Экспорт работает с момента сохранения JSON и не зависит от протокола. */
@Composable
internal fun ActionButtons(
    showImport: Boolean,
    showProtocolExport: Boolean,
    protocolReady: Boolean,
    exportFailed: Boolean,
    onExport: () -> Unit,
    onExportProtocol: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrimaryAction(
            text = stringResource(if (exportFailed) R.string.scr04_export_retry else R.string.common_btn_export),
            onClick = onExport,
            modifier = Modifier.weight(EXPORT_WEIGHT),
            icon = R.drawable.ic_download.takeIf { !showProtocolExport || !showImport },
            tag = Tags.EXPORT,
            dense = true,
        )
        if (showProtocolExport) {
            OutlinedAction(
                text = stringResource(R.string.common_btn_export_protocol),
                onClick = onExportProtocol,
                modifier = Modifier.weight(EXPORT_WEIGHT),
                enabled = protocolReady,
                tag = Tags.EXPORT_PROTOCOL,
                dense = true,
            )
        }
        if (showImport) {
            OutlinedAction(
                text = stringResource(R.string.common_btn_import),
                onClick = onImport,
                modifier = Modifier.weight(IMPORT_WEIGHT),
                tag = Tags.IMPORT,
                dense = true,
            )
        }
    }
}

/** Нет речи: честный результат, а не ошибка. JSON уже сохранён с пустым списком. */
@Composable
internal fun NoSpeech(onPickOther: () -> Unit, modifier: Modifier = Modifier) {
    val colors = StenoTheme.colors
    val gutter = LocalWindowLayout.current.gutter
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = gutter, vertical = StenoSpace.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(StenoSpace.m, Alignment.CenterVertically),
    ) {
        Image(painterResource(R.drawable.mascot_no_speech), null, Modifier.size(StenoSize.mascot))
        Text(
            text = stringResource(R.string.scr04_no_speech_title),
            modifier = Modifier.semantics { heading() },
            style = StenoTheme.type.titleLarge,
            color = colors.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.scr04_no_speech_body),
            modifier = Modifier.widthIn(max = 420.dp),
            style = StenoTheme.type.bodyLarge,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        PrimaryAction(
            text = stringResource(R.string.scr04_pick_other),
            onClick = onPickOther,
            modifier = Modifier.widthIn(max = 420.dp),
            tag = Tags.PICK_OTHER,
        )
    }
}

/** Док под тонкой линией: так же отделяются нижние панели остальных экранов. */
@Composable
internal fun Dock(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = StenoTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface)
            .drawBehind {
                drawLine(colors.outlineVariant, Offset.Zero, Offset(size.width, 0f), StenoSize.hairline.toPx())
            },
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
