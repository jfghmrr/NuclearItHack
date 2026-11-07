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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.stenokot.app.R
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.SpeakerBadge
import ru.stenokot.app.ui.components.StenoIcon
import ru.stenokot.app.ui.format.spokenTimecode
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private val RuleThickness = 3.dp
private val EdgeMarker = 4.dp
private val RuleGap = 6.dp
private val MarginBadge = 20.dp
private val HeaderBadge = 24.dp

/** Подписи строк. Собираются один раз на список, а не на каждую строку при прокрутке. */
@Stable
internal class RowStrings(context: Context) {
    private val speakerPrefix = context.getString(R.string.common_speaker, 0).dropLast(1)
    private val spokenTemplate = context.getString(R.string.scr04_row_spoken)

    /** Подпись действия для TalkBack. */
    val clickLabel: String = context.getString(R.string.scr04_row_click)

    /** Отметка активной реплики для TalkBack. */
    val activeLabel: String = context.getString(R.string.scr04_row_active)

    /** Подпись наложения речи. */
    val overlap: String = context.getString(R.string.scr04_overlap)

    /** «Говорящий 3». */
    fun speaker(number: Int): String = speakerPrefix + number

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
        if (row.overlap) append(". ").append(overlap)
    }
}

/** Геометрия строки: поле с временем или заголовок над текстом. Считается один раз на список. */
@Stable
internal class RowLayout(val ledger: Boolean, val gutter: Dp, val marginWidth: Dp) {
    /** Положение цветной линейки говорящего. */
    val ruleX: Dp = if (ledger) gutter + marginWidth + StenoSpace.s else StenoSpace.s
}

/** Заливка активной строки, поле говорящего и краевая метка. Цвет и позиция читаются при рисовании. */
private fun Modifier.rowBackdrop(
    layout: RowLayout,
    index: Int,
    active: () -> Int,
    speaker: Color,
    tint: Color,
    marker: Color,
    startsGroup: Boolean,
    endsGroup: Boolean,
): Modifier = drawBehind {
    val ruleX = layout.ruleX.toPx()
    val rule = RuleThickness.toPx()
    val isActive = active() == index
    if (isActive) {
        drawRect(tint, Offset(0f, 0f), size)
        drawRect(marker, Offset.Zero, Size(EdgeMarker.toPx(), size.height))
    }
    val top = if (startsGroup) RuleGap.toPx() else 0f
    val bottom = size.height - if (endsGroup) RuleGap.toPx() else 0f
    drawRect(speaker, Offset(ruleX - rule / 2f, top), Size(rule, bottom - top))
}

/** Метка наложения речи: значок и слово, поэтому она читается и без цвета. */
@Composable
private fun OverlapMark(label: String, modifier: Modifier = Modifier) {
    val colors = StenoTheme.colors
    Row(
        modifier = modifier.padding(top = StenoSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.xs),
    ) {
        StenoIcon(R.drawable.ic_overlap, colors.onSurfaceVariant, size = StenoSize.iconSmall)
        Text(label, style = StenoTheme.type.bodySmall, color = colors.onSurfaceVariant)
    }
}

/** Вид строки: время на поле, значок под ним, текст на странице. */
@Composable
private fun LedgerContent(item: VisibleRow, layout: RowLayout, strings: RowStrings) {
    val row = item.row
    val type = StenoTheme.type
    val colors = StenoTheme.colors
    Row(Modifier.fillMaxWidth().padding(start = layout.gutter, end = layout.gutter)) {
        Column(
            modifier = Modifier.width(layout.marginWidth),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(StenoSpace.xs),
        ) {
            Text(
                text = row.startLabel,
                modifier = Modifier.padding(top = StenoSpace.xs / 2),
                style = type.monoMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.End,
            )
            SpeakerBadge(row.speaker, size = MarginBadge)
        }
        Spacer(Modifier.width(StenoSpace.s + StenoSpace.m + StenoSpace.xs / 2))
        Column(Modifier.weight(1f)) {
            if (item.startsGroup) {
                Text(
                    text = strings.speaker(row.speaker),
                    style = type.labelMedium,
                    color = colors.onSurface,
                )
            }
            Text(item.text, style = type.bodyLarge, color = colors.onSurface)
            if (row.overlap) OverlapMark(strings.overlap)
        }
    }
}

/** Вид строки без поля: для узкого экрана и крупного шрифта, текст занимает всю ширину. */
@Composable
private fun HeaderContent(item: VisibleRow, layout: RowLayout, strings: RowStrings) {
    val row = item.row
    val type = StenoTheme.type
    val colors = StenoTheme.colors
    val timeBelow = item.startsGroup && LocalWindowLayout.current.largeText
    Column(Modifier.fillMaxWidth().padding(start = layout.gutter, end = layout.gutter)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = HeaderBadge),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
        ) {
            SpeakerBadge(row.speaker, size = if (item.startsGroup) HeaderBadge else MarginBadge)
            if (item.startsGroup) {
                Text(
                    text = strings.speaker(row.speaker),
                    modifier = Modifier.weight(1f),
                    style = type.labelMedium,
                    color = colors.onSurface,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (!timeBelow) Text(row.time, style = type.monoMedium, color = colors.onSurfaceVariant)
        }
        if (timeBelow) Text(row.time, style = type.monoMedium, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(StenoSpace.xs))
        Text(item.text, style = type.bodyLarge, color = colors.onSurface)
        if (row.overlap) OverlapMark(strings.overlap)
    }
}

/**
 * Реплика как строка документа. Текст показывается целиком. Строка не знает позицию плеера:
 * подсветка рисуется по [active], поэтому смена реплики не перекомпоновывает список.
 */
@Composable
internal fun TranscriptRow(
    item: VisibleRow,
    layout: RowLayout,
    strings: RowStrings,
    active: () -> Int,
    onClick: (TranscriptRowUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val row = item.row
    val colors = StenoTheme.colors
    val speakerColor = StenoTheme.speakers.style(row.speaker).fill
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = StenoSize.touchTarget)
            .rowBackdrop(
                layout = layout,
                index = row.index,
                active = active,
                speaker = speakerColor,
                tint = colors.primaryContainer,
                marker = colors.primary,
                startsGroup = item.startsGroup,
                endsGroup = item.endsGroup,
            )
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
            .padding(
                top = if (item.startsGroup) StenoSpace.m else StenoSpace.xs,
                bottom = if (item.endsGroup) StenoSpace.m else StenoSpace.xs,
            ),
    ) {
        if (layout.ledger) LedgerContent(item, layout, strings) else HeaderContent(item, layout, strings)
    }
}
