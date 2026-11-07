package ru.stenokot.app.ui.result

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
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
import ru.stenokot.app.ui.components.BannerTone
import ru.stenokot.app.ui.components.PrimaryAction
import ru.stenokot.app.ui.components.QuietAction
import ru.stenokot.app.ui.components.SectionHeader
import ru.stenokot.app.ui.components.StatusBanner
import ru.stenokot.app.ui.components.StatusChip
import ru.stenokot.app.ui.components.StenoIcon
import ru.stenokot.app.ui.components.ledgerMarginWidth
import ru.stenokot.app.ui.components.spokenAs
import ru.stenokot.app.ui.format.formatTimeRange
import ru.stenokot.app.ui.format.formatTimecode
import ru.stenokot.app.ui.format.spokenTimecode
import ru.stenokot.app.ui.format.topicsLabel
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme
import ru.stenokot.core.protocol.ProtocolDocument
import ru.stenokot.core.protocol.ProtocolItem
import ru.stenokot.core.protocol.ProtocolSection

private val MessageWidth = 420.dp

/** Вид пункта протокола: подпись и значок. */
internal enum class ItemKind(
    val title: Int,
    val group: Int,
    @param:DrawableRes val icon: Int,
    val mark: RibbonMarkKind,
) {
    DECISION(R.string.scr05_kind_decision, R.string.scr05_group_decisions, R.drawable.ic_gavel, RibbonMarkKind.DECISION),
    ACTION(R.string.scr05_kind_action, R.string.scr05_group_actions, R.drawable.ic_assignment_ind, RibbonMarkKind.ACTION),
    QUESTION(R.string.scr05_kind_question, R.string.scr05_group_questions, R.drawable.ic_help, RibbonMarkKind.QUESTION),
}

internal fun itemKind(value: String): ItemKind = when (value) {
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

/** Предупреждение слоя: это черновик поверх стенограммы, а не её правка. */
@Composable
private fun DraftNote(covered: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(StenoSpace.xs)) {
        StatusBanner(
            tone = BannerTone.WARNING,
            icon = R.drawable.ic_info,
            body = stringResource(R.string.scr05_draft),
            tag = Tags.LLM_DRAFT,
        )
        covered?.let {
            Text(
                text = stringResource(R.string.scr05_covered, it),
                modifier = Modifier.padding(horizontal = StenoSpace.m).testTag(Tags.LLM_COVERED),
                style = StenoTheme.type.bodySmall,
                color = StenoTheme.colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * Тема разговора: время на поле и заголовок. Переход ведёт в стенограмму к началу темы. На крупном
 * шрифте поле исчезает, и время уходит под заголовок.
 */
@Composable
private fun ChapterRow(
    index: Int,
    title: String,
    section: ProtocolSection,
    clickLabel: String,
    spoken: String,
    onOpen: () -> Unit,
) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val window = LocalWindowLayout.current
    val range = formatTimeRange(section.startMs, section.endMs)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = StenoSize.actionHeight)
            .testTag(Tags.rowChapter(index))
            .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onOpen)
            .spokenAs(spoken, title)
            .padding(horizontal = window.gutter, vertical = StenoSpace.m),
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.m),
    ) {
        if (!window.largeText) {
            Text(
                text = formatTimecode(section.startMs),
                modifier = Modifier.width(ledgerMarginWidth()).padding(top = StenoSpace.xs / 2),
                style = type.monoSmall,
                color = colors.primary,
                maxLines = 1,
                softWrap = false,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StenoSpace.xs / 2)) {
            Text(title, style = type.cardTitle, color = colors.onSurface)
            Text(range, style = type.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

/** Карточка пункта: метка проверки, дословный текст и ссылка на источник. Открывает пункт целиком. */
@Composable
private fun ItemCard(index: Int, item: ProtocolItem, onOpen: (Int) -> Unit) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val gutter = LocalWindowLayout.current.gutter
    val shape = RoundedCornerShape(StenoRadius.card)
    val kind = itemKind(item.kind)
    val speaker = stringResource(R.string.common_speaker, speakerNumber(item.speakerId))
    val spoken = stringResource(
        R.string.scr05_item_spoken,
        stringResource(kind.title),
        speaker,
        spokenTimecode(item.startMs),
        item.text,
    )
    val clickLabel = stringResource(R.string.scr05_item_click)
    Column(
        modifier = Modifier
            .padding(horizontal = gutter, vertical = StenoSpace.xs)
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(StenoSize.hairline, colors.outlineVariant, shape)
            .clip(shape)
            .testTag(Tags.rowProtocol(index))
            .clickable(onClickLabel = clickLabel) { onOpen(index) }
            .clearAndSetSemantics {
                contentDescription = spoken
                text = AnnotatedString(item.text)
                role = Role.Button
                onClick(clickLabel) {
                    onOpen(index)
                    true
                }
            }
            .padding(StenoSpace.l),
        verticalArrangement = Arrangement.spacedBy(StenoSpace.s),
    ) {
        StatusChip(stringResource(R.string.scr05_item_check), kind.icon, BannerTone.WARNING)
        Text(item.text, style = type.cardTitle, color = colors.onSurface)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
        ) {
            StenoIcon(R.drawable.ic_link, colors.primary, size = StenoSize.iconSmall)
            Text(
                text = stringResource(R.string.scr05_item_source, formatTimecode(item.startMs)),
                modifier = Modifier.weight(1f),
                style = type.bodySmall,
                color = colors.primary,
            )
            StenoIcon(R.drawable.ic_chevron_right, colors.onSurfaceVariant, size = StenoSize.iconSmall)
        }
    }
}

/** Темы и пункты протокола в одном ленивом списке. Пункты разбиты по видам, номер сквозной. */
@Composable
private fun ProtocolList(
    phase: ProtocolPhase,
    protocol: ProtocolUi,
    document: ProtocolDocument,
    showBanner: Boolean,
    listState: LazyListState,
    onOpenChapter: (ProtocolSection) -> Unit,
    onOpenItem: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gutter = LocalWindowLayout.current.gutter
    val groups = remember(document) {
        val numbered = protocolItems(document).withIndex().toList()
        ItemKind.entries.map { kind -> kind to numbered.filter { itemKind(it.value.kind) == kind } }
            .filter { (_, items) -> items.isNotEmpty() }
    }
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
                modifier = Modifier.padding(horizontal = gutter).padding(top = StenoSpace.m),
                verticalArrangement = Arrangement.spacedBy(StenoSpace.s),
            ) {
                if (showBanner) ProtocolBanner(phase, protocol)
                DraftNote(covered)
            }
        }
        item(key = "toc", contentType = "section") {
            SectionHeader(
                title = stringResource(R.string.scr05_section_toc),
                modifier = Modifier.padding(horizontal = gutter).padding(top = StenoSpace.l),
                tag = Tags.LIST_CHAPTERS,
                trailing = topicsLabel(document.sections.size),
            )
        }
        itemsIndexed(
            items = document.sections,
            key = { index, _ -> "chapter$index" },
            contentType = { _, _ -> "chapter" },
        ) { index, section ->
            val title = chapterTitle(section) { fallbackTemplate.format(it) }
            ChapterRow(
                index = index,
                title = title,
                section = section,
                clickLabel = clickLabel,
                spoken = spokenChapter.format(title, spokenTimecode(section.startMs), spokenTimecode(section.endMs)),
                onOpen = { onOpenChapter(section) },
            )
            if (index < document.sections.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = gutter),
                    thickness = StenoSize.hairline,
                    color = StenoTheme.colors.outlineVariant,
                )
            }
        }
        if (groups.isEmpty()) {
            item(key = "noItems", contentType = "note") {
                Text(
                    text = stringResource(R.string.scr05_no_items),
                    modifier = Modifier.padding(horizontal = gutter, vertical = StenoSpace.l),
                    style = StenoTheme.type.bodyMedium,
                    color = StenoTheme.colors.onSurfaceVariant,
                )
            }
        }
        groups.forEach { (kind, numbered) ->
            item(key = "group${kind.name}", contentType = "section") {
                SectionHeader(
                    title = stringResource(kind.group, numbered.size),
                    modifier = Modifier.padding(horizontal = gutter).padding(top = StenoSpace.l),
                )
            }
            items(
                items = numbered,
                key = { "item${it.index}" },
                contentType = { "item" },
            ) { ItemCard(it.index, it.value, onOpenItem) }
        }
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
    onOpenItem: (Int) -> Unit,
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
