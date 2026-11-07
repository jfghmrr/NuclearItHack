package ru.stenokot.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.stenokot.app.R
import ru.stenokot.app.domain.StageStatus
import ru.stenokot.app.domain.StageUi
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.format.formatTimecode
import ru.stenokot.app.ui.format.userStageLabel
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.StenoColors
import ru.stenokot.app.ui.theme.StenoMotion
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private val NodeSize = 28.dp
private val NodeTop = 10.dp

/** Цвет линии рельса: пройденный участок чернилами акцента, остальной тонкой линией. */
private fun lineColor(done: Boolean, colors: StenoColors): Color =
    if (done) colors.primary else colors.outlineVariant

/** Рисует участки рельса выше и ниже узла строки. */
private fun Modifier.railLine(above: Color?, below: Color?): Modifier = drawBehind {
    val x = NodeSize.toPx() / 2
    val middle = (NodeTop + NodeSize / 2).toPx()
    val width = 2.dp.toPx()
    above?.let { drawLine(it, Offset(x, 0f), Offset(x, middle), width, StrokeCap.Butt) }
    below?.let { drawLine(it, Offset(x, middle), Offset(x, size.height), width, StrokeCap.Butt) }
}

@Composable
private fun RailNode(status: StageStatus, paused: Boolean) {
    val colors = StenoTheme.colors
    Box(
        modifier = Modifier
            .padding(top = NodeTop)
            .size(NodeSize)
            .background(
                color = when {
                    status == StageStatus.ACTIVE && paused -> colors.warning
                    status == StageStatus.ACTIVE -> colors.primary
                    else -> colors.surface
                },
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            StageStatus.DONE -> StenoIcon(R.drawable.ic_check_circle_fill, colors.success, size = StenoSize.icon)
            StageStatus.ACTIVE -> StenoIcon(
                icon = if (paused) R.drawable.ic_device_thermostat else R.drawable.ic_hourglass_top,
                tint = if (paused) colors.onWarning else colors.onPrimary,
                size = StenoSize.iconSmall + 2.dp,
            )
            StageStatus.PENDING -> StenoIcon(R.drawable.ic_radio_button_unchecked, colors.outline, size = StenoSize.icon)
            StageStatus.SKIPPED -> StenoIcon(R.drawable.ic_block, colors.onSurfaceVariant, size = StenoSize.iconMedium)
            StageStatus.FAILED -> StenoIcon(R.drawable.ic_error, colors.error, size = StenoSize.icon)
        }
    }
}

private fun spokenRow(label: String, stage: StageUi): String = when (stage.status) {
    StageStatus.DONE -> "$label, выполнено за ${formatTimecode(stage.tookMs ?: 0)}"
    StageStatus.ACTIVE -> "$label, идёт сейчас"
    StageStatus.SKIPPED -> "$label, пропущено"
    StageStatus.FAILED -> "$label, ошибка"
    StageStatus.PENDING -> "$label, ожидает"
}

/**
 * Рельс этапов: вертикальная линия с узлами. У готовых этапов справа реальное время, у текущего
 * строка раскрывается слотом [activeBody], ожидающие стоят тихо. Значения приходят от конвейера.
 * При смене этапа активная строка сама встаёт в видимую часть прокрутки.
 */
@Composable
fun StageRail(
    stages: List<StageUi>,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    activeBody: @Composable ColumnScope.(StageUi) -> Unit,
) {
    val colors = StenoTheme.colors
    Column(modifier.fillMaxWidth().testTag(Tags.LIST_STAGES)) {
        stages.forEachIndexed { index, stage ->
            key(stage.stage) {
                val above = if (index == 0) null else lineColor(stages[index - 1].status == StageStatus.DONE, colors)
                val below = if (index == stages.lastIndex) null else lineColor(stage.status == StageStatus.DONE, colors)
                RailRow(stage, above, below, paused, activeBody)
            }
        }
    }
}

@Composable
private fun RailRow(
    stage: StageUi,
    lineAbove: Color?,
    lineBelow: Color?,
    paused: Boolean,
    activeBody: @Composable ColumnScope.(StageUi) -> Unit,
) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val active = stage.status == StageStatus.ACTIVE
    val label = userStageLabel(stage.stage)
    val spoken = spokenRow(label, stage)
    val visibility = remember { BringIntoViewRequester() }
    LaunchedEffect(active) {
        if (active) visibility.bringIntoView()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = StenoSize.touchTarget)
            .bringIntoViewRequester(visibility)
            .animateContentSize(tween(StenoMotion.MEDIUM))
            .railLine(lineAbove, lineBelow)
            .testTag(Tags.rowStage(stage.stage.name))
            .then(if (active) Modifier else Modifier.spokenAs(spoken, label)),
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.m),
    ) {
        RailNode(stage.status, paused)
        Column(Modifier.weight(1f).padding(top = NodeTop, bottom = if (active) StenoSpace.l else StenoSpace.s)) {
            val title: @Composable (Modifier) -> Unit = { titleModifier ->
                Text(
                    text = label,
                    modifier = titleModifier.then(
                        if (active) {
                            Modifier
                                .testTag(Tags.STAGE)
                                .semantics {
                                    contentDescription = spoken
                                    liveRegion = LiveRegionMode.Polite
                                }
                        } else {
                            Modifier
                        },
                    ),
                    style = if (active) type.titleMedium else type.bodyLarge.copy(fontWeight = FontWeight.W500),
                    color = if (stage.status == StageStatus.PENDING) colors.onSurfaceVariant else colors.onSurface,
                )
            }
            val time: @Composable () -> Unit = {
                stage.tookMs?.let {
                    Text(formatTimecode(it), style = type.monoMedium, color = colors.onSurfaceVariant)
                }
            }
            if (LocalWindowLayout.current.largeText) {
                title(Modifier)
                time()
            } else {
                Row(
                    modifier = Modifier.heightIn(min = NodeSize),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
                ) {
                    title(Modifier.weight(1f))
                    time()
                }
            }
            if (active) activeBody(stage)
        }
    }
}
