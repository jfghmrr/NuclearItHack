package ru.stenokot.app.ui.result

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.stenokot.app.R
import ru.stenokot.app.domain.ProtocolUi
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.LedgerRow
import ru.stenokot.app.ui.components.PrimaryAction
import ru.stenokot.app.ui.components.QuietAction
import ru.stenokot.app.ui.components.SectionHeader
import ru.stenokot.app.ui.components.SpeakerBadge
import ru.stenokot.app.ui.components.StenoIcon
import ru.stenokot.app.ui.components.ledgerMarginWidth
import ru.stenokot.app.ui.format.formatTimeRange
import ru.stenokot.app.ui.format.formatTimecode
import ru.stenokot.app.ui.format.spokenTimecode
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme
import ru.stenokot.core.protocol.ProtocolDocument
import ru.stenokot.core.protocol.ProtocolItem
import ru.stenokot.core.protocol.ProtocolSection

private val RuleThickness = 3.dp
private val QuoteBadge = 20.dp
private val MessageWidth = 420.dp

/** Вид пункта протокола: подпись и значок. */
private enum class ItemKind(val title: Int, @param:DrawableRes val icon: Int, val mark: RibbonMarkKind) {
    DECISION(R.string.scr05_kind_decision, R.drawable.ic_gavel, RibbonMarkKind.DECISION),
    ACTION(R.string.scr05_kind_action, R.drawable.ic_assignment_ind, RibbonMarkKind.ACTION),
    QUESTION(R.string.scr05_kind_question, R.drawable.ic_help, RibbonMarkKind.QUESTION),
}

private fun itemKind(value: String): ItemKind = when (value) {
    "action" -> ItemKind.ACTION
    "question" -> ItemKind.QUESTION
    else -> ItemKind.DECISION
}

/** Значение поля kind как вид метки на ленте. */
internal fun ribbonKind(value: String): RibbonMarkKind = itemKind(value).mark

/** Номер говорящего из `speaker_3`. Неразобранное значение показывается как первый говорящий. */
internal fun speakerNumber(speakerId: String): Int =
    speakerId.substringAfterLast('_').toIntOrNull() ?: 1

private val IntervalOnly = Regex("""[\d:\s-]+""")

/** Тема главы. Без проверенной темы модель оставляет только интервал, он получает подпись. */
internal fun chapterTitle(section: ProtocolSection, fallback: (String) -> String): String {
    val title = section.title.trim()
    return if (title.isEmpty() || IntervalOnly.matches(title)) {
        fallback(formatTimeRange(section.startMs, section.endMs))
    } else {
        title.replaceFirstChar { it.titlecase() }
    }
}

/** Пункты всех разделов по времени. */
internal fun protocolItems(document: ProtocolDocument): List<ProtocolItem> =
    document.sections.flatMap { it.items }.sortedBy { it.startMs }

/** Что показывает панель, когда документа нет. */
@Immutable
private class ProtocolMessage(
    val text: String,
    @param:DrawableRes val mascot: Int?,
    @param:DrawableRes val icon: Int?,
)

@Composable
private fun protocolMessage(phase: ProtocolPhase, retryUsed: Boolean): ProtocolMessage {
    val retry = stringResource(R.string.scr05_retry_used)
    return when (phase) {
        ProtocolPhase.DEFERRED,
        ProtocolPhase.CANCELLED_BY_NEW_JOB,
        -> ProtocolMessage(
            if (retryUsed) retry else stringResource(R.string.scr05_deferred),
            R.drawable.mascot_waiting,
            null,
        )
        ProtocolPhase.SKIPPED_MEMORY ->
            ProtocolMessage(stringResource(R.string.scr05_skipped_memory), null, R.drawable.ic_memory)
        ProtocolPhase.SKIPPED_BUDGET -> ProtocolMessage(
            if (retryUsed) retry else stringResource(R.string.scr05_skipped_budget),
            null,
            R.drawable.ic_timer_off,
        )
        ProtocolPhase.FAILED -> ProtocolMessage(stringResource(R.string.scr05_failed), null, R.drawable.ic_warning)
        ProtocolPhase.NO_MODEL -> ProtocolMessage(stringResource(R.string.scr05_no_model), null, R.drawable.ic_warning)
        ProtocolPhase.NO_SPEECH ->
            ProtocolMessage(stringResource(R.string.scr05_no_speech), R.drawable.mascot_no_speech, null)
        else -> ProtocolMessage(stringResource(R.string.scr05_running), R.drawable.mascot_waiting, null)
    }
}

/** Технические подробности сбоя. Человеческое объяснение уже на экране, код только по запросу. */
@Composable
private fun FailureDetails(code: String, onCopyDiagnostics: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val colors = StenoTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        QuietAction(
            text = stringResource(if (open) R.string.scr05_details_hide else R.string.scr05_details),
            onClick = { open = !open },
            tag = Tags.DETAILS,
        )
        if (open) {
            Text(
                text = code,
                modifier = Modifier.testTag(Tags.DETAILS_CODE),
                style = StenoTheme.type.monoSmall,
                color = colors.onSurfaceVariant,
            )
            QuietAction(
                text = stringResource(R.string.scr05_copy_diagnostics),
                onClick = onCopyDiagnostics,
                tag = Tags.COPY_DIAGNOSTICS,
            )
        }
    }
}

/** Панель без документа: честное объяснение, почему протокола нет и что будет дальше. */
@Composable
private fun ProtocolEmpty(
    phase: ProtocolPhase,
    protocol: ProtocolUi,
    showBanner: Boolean,
    onOpenTranscript: (() -> Unit)?,
    onCopyDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = StenoTheme.colors
    val gutter = LocalWindowLayout.current.gutter
    val message = protocolMessage(phase, protocol.retryUsed)
    val failed = phase == ProtocolPhase.FAILED || phase == ProtocolPhase.NO_MODEL
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = gutter, vertical = StenoSpace.m),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(StenoSpace.l),
    ) {
        if (showBanner && phase != ProtocolPhase.NO_SPEECH) ProtocolBanner(phase, protocol)
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = StenoSpace.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(StenoSpace.l),
        ) {
            message.mascot?.let {
                Image(painterResource(it), contentDescription = null, Modifier.size(StenoSize.mascot))
            }
            message.icon?.let { StenoIcon(it, colors.onSurfaceVariant, size = StenoSize.iconLarge) }
            if (failed) {
                Text(
                    text = stringResource(R.string.scr05_failed_title),
                    modifier = Modifier.widthIn(max = MessageWidth).semantics { heading() },
                    style = StenoTheme.type.titleMedium,
                    color = colors.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = message.text,
                modifier = Modifier.widthIn(max = MessageWidth).testTag(Tags.PROTOCOL_STATE),
                style = StenoTheme.type.bodyLarge,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (failed) {
                if (onOpenTranscript != null) {
                    PrimaryAction(
                        text = stringResource(R.string.scr05_open_transcript),
                        onClick = onOpenTranscript,
                        modifier = Modifier.widthIn(max = MessageWidth),
                        tag = Tags.OPEN_TRANSCRIPT,
                    )
                }
                protocol.errorCode?.let { FailureDetails(it.name, onCopyDiagnostics) }
            }
        }
    }
}

/** Подпись слоя: это черновик поверх стенограммы, а не её правка. */
@Composable
private fun DraftNote(covered: String?) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    Column(verticalArrangement = Arrangement.spacedBy(StenoSpace.xs)) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) { }.testTag(Tags.LLM_DRAFT),
            horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
        ) {
            StenoIcon(R.drawable.ic_info, colors.onSurfaceVariant, Modifier.padding(top = 1.dp), StenoSize.iconSmall)
            Text(stringResource(R.string.scr05_draft), style = type.bodySmall, color = colors.onSurfaceVariant)
        }
        covered?.let {
            Text(
                text = stringResource(R.string.scr05_covered, it),
                modifier = Modifier.padding(start = StenoSize.iconSmall + StenoSpace.s).testTag(Tags.LLM_COVERED),
                style = type.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/** Пункт протокола: дословная цитата и ссылка на реплику. Текст скопирован кодом из стенограммы. */
@Composable
private fun ProtocolRow(index: Int, item: ProtocolItem, onOpen: (ProtocolItem) -> Unit) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val window = LocalWindowLayout.current
    val gutter = window.gutter
    val ledger = !window.largeText
    val marginWidth = ledgerMarginWidth()
    val kind = itemKind(item.kind)
    val number = speakerNumber(item.speakerId)
    val kindLabel = stringResource(kind.title)
    val speaker = stringResource(R.string.common_speaker, number)
    val time = formatTimecode(item.startMs)
    val spoken = stringResource(R.string.scr05_item_spoken, kindLabel, speaker, spokenTimecode(item.startMs), item.text)
    val clickLabel = stringResource(R.string.scr05_item_click)
    val ruleColor = StenoTheme.speakers.style(number).fill
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (ledger) {
                    val x = (gutter + marginWidth + StenoSpace.s).toPx()
                    val gap = StenoSpace.s.toPx()
                    val width = RuleThickness.toPx()
                    drawRect(ruleColor, Offset(x - width / 2f, gap), Size(width, size.height - gap * 2))
                }
            }
            .testTag(Tags.rowProtocol(index))
            .clickable(onClickLabel = clickLabel) { onOpen(item) }
            .clearAndSetSemantics {
                contentDescription = spoken
                text = AnnotatedString(item.text)
                role = Role.Button
                onClick(clickLabel) {
                    onOpen(item)
                    true
                }
            }
            .padding(horizontal = gutter, vertical = StenoSpace.m),
    ) {
        if (ledger) {
            Column(
                modifier = Modifier.width(marginWidth),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(StenoSpace.xs),
            ) {
                Text(time, Modifier.padding(top = StenoSpace.xs / 2), style = type.monoMedium, color = colors.onSurfaceVariant)
                SpeakerBadge(number, size = QuoteBadge)
            }
            Spacer(Modifier.width(StenoSpace.s + StenoSpace.m + StenoSpace.xs / 2))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StenoSpace.xs)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(StenoSpace.xs),
            ) {
                StenoIcon(kind.icon, colors.primary, size = StenoSize.iconSmall)
                Text(
                    text = kindLabel,
                    modifier = Modifier.weight(1f),
                    style = type.labelMedium,
                    color = colors.primary,
                )
                StenoIcon(R.drawable.ic_chevron_right, colors.onSurfaceVariant, size = StenoSize.iconSmall)
            }
            Text(item.text, style = type.bodyLarge, color = colors.onSurface)
            if (ledger) {
                Text(speaker, style = type.bodySmall, color = colors.onSurfaceVariant)
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
                ) {
                    SpeakerBadge(number, size = QuoteBadge)
                    Text(speaker, Modifier.weight(1f), style = type.bodySmall, color = colors.onSurfaceVariant)
                    Text(time, style = type.monoMedium, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

/** Оглавление и пункты протокола в одном ленивом списке. */
@Composable
private fun ProtocolList(
    phase: ProtocolPhase,
    protocol: ProtocolUi,
    document: ProtocolDocument,
    showBanner: Boolean,
    listState: LazyListState,
    onOpenChapter: (ProtocolSection) -> Unit,
    onOpenItem: (ProtocolItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gutter = LocalWindowLayout.current.gutter
    val items = remember(document) { protocolItems(document) }
    val covered = remember(document, phase) {
        if (phase != ProtocolPhase.PARTIAL) {
            null
        } else {
            coveredRanges(document.sections.map { CoveredRange(it.startMs, it.endMs) })
                .joinToString(", ") { formatTimeRange(it.startMs, it.endMs) }
        }
    }
    val fallbackTemplate = stringResource(R.string.scr05_chapter_fallback)
    val spokenChapter = stringResource(R.string.scr05_chapter_spoken)
    val clickLabel = stringResource(R.string.scr05_item_click)
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(Tags.LIST_PROTOCOL),
        state = listState,
        contentPadding = PaddingValues(bottom = StenoSpace.m),
    ) {
        item(key = "head", contentType = "head") {
            Column(
                modifier = Modifier.padding(horizontal = gutter).padding(top = StenoSpace.s),
                verticalArrangement = Arrangement.spacedBy(StenoSpace.s),
            ) {
                if (showBanner) ProtocolBanner(phase, protocol)
                DraftNote(covered)
            }
        }
        item(key = "toc", contentType = "section") {
            SectionHeader(
                title = stringResource(R.string.scr05_section_toc),
                modifier = Modifier.padding(horizontal = gutter).padding(top = StenoSpace.s),
                count = document.sections.size,
                tag = Tags.LIST_CHAPTERS,
            )
        }
        itemsIndexed(
            items = document.sections,
            key = { index, _ -> "chapter$index" },
            contentType = { _, _ -> "chapter" },
        ) { index, section ->
            val title = chapterTitle(section) { fallbackTemplate.format(it) }
            LedgerRow(
                margin = formatTimecode(section.startMs),
                title = title,
                subtitle = formatTimeRange(section.startMs, section.endMs),
                titleMaxLines = Int.MAX_VALUE,
                onClick = { onOpenChapter(section) },
                onClickLabel = clickLabel,
                description = spokenChapter.format(
                    title,
                    spokenTimecode(section.startMs),
                    spokenTimecode(section.endMs),
                ),
                tag = Tags.rowChapter(index),
            )
        }
        item(key = "protocol", contentType = "section") {
            SectionHeader(
                title = stringResource(R.string.scr05_section_protocol),
                modifier = Modifier.padding(horizontal = gutter).padding(top = StenoSpace.s),
                count = items.size.takeIf { it > 0 },
            )
        }
        if (items.isEmpty()) {
            item(key = "noItems", contentType = "note") {
                Text(
                    text = stringResource(R.string.scr05_no_items),
                    modifier = Modifier.padding(horizontal = gutter, vertical = StenoSpace.m),
                    style = StenoTheme.type.bodyMedium,
                    color = StenoTheme.colors.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(
            items = items,
            key = { index, _ -> "item$index" },
            contentType = { _, _ -> "item" },
        ) { index, item -> ProtocolRow(index, item, onOpenItem) }
    }
}

/** SCR-05: документ протокола или объяснение, почему его нет. */
@Composable
internal fun ProtocolPane(
    phase: ProtocolPhase,
    protocol: ProtocolUi,
    showBanner: Boolean,
    listState: LazyListState,
    onOpenChapter: (ProtocolSection) -> Unit,
    onOpenItem: (ProtocolItem) -> Unit,
    onOpenTranscript: (() -> Unit)?,
    onCopyDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val document = protocol.document
    if (phase.hasDocument && document != null) {
        ProtocolList(phase, protocol, document, showBanner, listState, onOpenChapter, onOpenItem, modifier)
    } else {
        ProtocolEmpty(phase, protocol, showBanner, onOpenTranscript, onCopyDiagnostics, modifier)
    }
}
