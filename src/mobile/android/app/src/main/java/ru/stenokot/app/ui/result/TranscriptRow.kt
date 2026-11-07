package ru.stenokot.app.ui.result

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.stenokot.app.R
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.SpeakerBadge
import ru.stenokot.app.ui.components.StenoIcon
import ru.stenokot.app.ui.format.spokenTimecode
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private val EdgeMarker = 3.dp
private val EdgeMarkerInset = 8.dp
private val HeaderBadge = 22.dp

/** Подписи строк. Собираются один раз на список, а не на каждую строку при прокрутке. */
@Stable
internal class RowStrings(context: Context) {
    private val speakerPrefix = context.getString(R.string.common_speaker, 0).dropLast(1)
    private val spokenTemplate = context.getString(R.string.scr04_row_spoken)
    private val overlapTemplate = context.getString(R.string.scr04_overlap_with)

    /** Подпись действия для TalkBack. */
    val clickLabel: String = context.getString(R.string.scr04_row_click)

    /** Отметка активной реплики для TalkBack. */
    val activeLabel: String = context.getString(R.string.scr04_row_active)

    private val overlap: String = context.getString(R.string.scr04_overlap)

    /** «Говорящий 3». */
    fun speaker(number: Int): String = speakerPrefix + number

    /** Подпись наложения: с номерами говорящих, если они известны. */
    fun overlap(row: TranscriptRowUi): String = row.overlapSpeakers?.let(overlapTemplate::format) ?: overlap

    /** Фраза TalkBack целиком. */
    fun spoken(row: TranscriptRowUi): String = buildString {
        append(
            spokenTemplate.format(
                speaker(row.speaker),
                spokenTimecode(row.startMs),
                spokenTimecode(row.endMs),
                row.text.text,
            ),
        )
        if (row.overlap) append(". ").append(overlap(row))
    }
}

/** Геометрия строки: где лежит карточка активной реплики и где начинается текст. */
@Stable
internal class RowLayout(val cardInset: Dp, val textInset: Dp)

/** Карточка звучащей реплики или текущего совпадения. Всё читается при рисовании, композиция не нужна. */
private fun Modifier.rowBackdrop(
    layout: RowLayout,
    index: Int,
    active: () -> Int,
    match: () -> Int,
    tint: Color,
    marker: Color,
): Modifier = drawBehind {
    if (active() != index && match() != index) return@drawBehind
    val inset = layout.cardInset.toPx()
    val radius = StenoRadius.card.toPx()
    val edge = EdgeMarker.toPx()
    val edgeInset = EdgeMarkerInset.toPx()
    drawRoundRect(
        color = tint,
        topLeft = Offset(inset, 0f),
        size = Size(size.width - inset * 2f, size.height),
        cornerRadius = CornerRadius(radius),
    )
    drawRoundRect(
        color = marker,
        topLeft = Offset(inset, edgeInset),
        size = Size(edge, size.height - edgeInset * 2f),
        cornerRadius = CornerRadius(edge / 2f),
    )
}

/** Метка одновременной речи: значок и слова, поэтому она читается и без цвета. */
@Composable
private fun OverlapMark(label: String, modifier: Modifier = Modifier) {
    val color = StenoTheme.colors.tertiary
    Row(
        modifier = modifier.padding(top = StenoSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.xs),
    ) {
        StenoIcon(R.drawable.ic_overlap, color, size = StenoSize.iconSmall)
        Text(label, style = StenoTheme.type.bodySmall, color = color)
    }
}

/** Шапка строки: говорящий и время. Продолжение той же реплики оставляет только время. */
@Composable
private fun RowHeader(item: VisibleRow, strings: RowStrings) {
    val row = item.row
    val type = StenoTheme.type
    val colors = StenoTheme.colors
    val timeBelow = item.startsGroup && LocalWindowLayout.current.largeText
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
    ) {
        if (item.startsGroup) {
            SpeakerBadge(row.speaker, size = HeaderBadge)
            Text(
                text = strings.speaker(row.speaker),
                modifier = Modifier.weight(1f),
                style = type.labelSmall,
                color = colors.onSurface,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (!timeBelow) Text(row.startLabel, style = type.monoSmall, color = colors.onSurfaceVariant)
    }
    if (timeBelow) Text(row.startLabel, style = type.monoSmall, color = colors.onSurfaceVariant)
}

/**
 * Реплика как абзац разговора. Текст показывается целиком. Строка не знает позицию плеера: подсветка
 * рисуется по [active] и [match], поэтому смена реплики не перекомпоновывает список.
 */
@Composable
internal fun TranscriptRow(
    item: VisibleRow,
    layout: RowLayout,
    strings: RowStrings,
    active: () -> Int,
    match: () -> Int,
    onClick: (TranscriptRowUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val row = item.row
    val colors = StenoTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = StenoSize.touchTarget)
            .rowBackdrop(layout, row.index, active, match, colors.primaryContainer, colors.primary)
            .testTag(Tags.rowUtt(row.index))
            .clickable(onClickLabel = strings.clickLabel) { onClick(row) }
            .clearAndSetSemantics {
                val spoken = strings.spoken(row)
                contentDescription = if (active() == row.index) "${strings.activeLabel}. $spoken" else spoken
                text = AnnotatedString(row.text.text)
                role = Role.Button
                onClick(strings.clickLabel) {
                    onClick(row)
                    true
                }
            }
            .padding(horizontal = layout.textInset)
            .padding(
                top = if (item.startsGroup) StenoSpace.m else StenoSpace.xs,
                bottom = if (item.endsGroup) StenoSpace.m else StenoSpace.xs,
            ),
    ) {
        RowHeader(item, strings)
        Spacer(Modifier.height(StenoSpace.xs))
        Text(item.text, style = StenoTheme.type.bodyLarge, color = colors.onSurface)
        if (row.overlap) OverlapMark(strings.overlap(row))
    }
}
