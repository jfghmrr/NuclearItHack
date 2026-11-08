package ru.stenokot.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import ru.stenokot.app.ui.theme.StenoMotion
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private val RowHeight = 41.dp
private val CheckSize = 13.dp
private const val CHECK_FROM = 0.3f

/**
 * Кружок этапа: номер, галочка или значок. Цвет только дополняет форму и подпись. Заливка меняется
 * плавно, а галочка этапа, завершившегося на глазах, вырастает из точки. Уже готовый этап при
 * открытии экрана сразу стоит с галочкой.
 */
@Composable
private fun StepDot(number: Int, status: StageStatus, paused: Boolean) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val active = status == StageStatus.ACTIVE
    val target = when {
        active && paused -> colors.warning
        active -> colors.primary
        status == StageStatus.DONE || status == StageStatus.SKIPPED -> colors.primaryContainer
        status == StageStatus.FAILED -> colors.errorContainer
        else -> colors.background
    }
    val fill by animateColorAsState(target, tween(StenoMotion.MEDIUM), label = "stepFill")
    val check = remember { Animatable(if (status == StageStatus.DONE) 1f else 0f) }
    LaunchedEffect(status) {
        if (status == StageStatus.DONE) {
            check.animateTo(1f, tween(StenoMotion.CHECK, easing = FastOutSlowInEasing))
        } else {
            check.snapTo(0f)
        }
    }
    Box(
        modifier = Modifier
            .size(StenoSize.stepDot)
            .background(fill, CircleShape)
            .then(
                if (status == StageStatus.PENDING) {
                    Modifier.border(StenoSize.hairline, colors.outlineVariant, CircleShape)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            StageStatus.DONE -> StenoIcon(
                icon = R.drawable.ic_check,
                tint = colors.primary,
                modifier = Modifier.graphicsLayer {
                    val value = check.value
                    alpha = value
                    scaleX = CHECK_FROM + (1f - CHECK_FROM) * value
                    scaleY = CHECK_FROM + (1f - CHECK_FROM) * value
                },
                size = CheckSize,
            )
            StageStatus.SKIPPED -> StenoIcon(R.drawable.ic_block, colors.onSurfaceVariant, size = CheckSize)
            StageStatus.FAILED -> StenoIcon(R.drawable.ic_close, colors.error, size = CheckSize)
            StageStatus.ACTIVE, StageStatus.PENDING -> Text(
                text = number.toString(),
                style = type.eyebrow,
                color = when {
                    !active -> colors.onSurfaceVariant
                    paused -> colors.onWarning
                    else -> colors.onPrimary
                },
            )
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
 * Рельс этапов: пять строк с кружками. Текущая выделена начертанием и номером на акценте,
 * готовые отмечены галочкой, словом и настоящим временем этапа от конвейера.
 */
@Composable
fun StageRail(stages: List<StageUi>, modifier: Modifier = Modifier, paused: Boolean = false) {
    Column(modifier.fillMaxWidth().testTag(Tags.LIST_STAGES)) {
        stages.forEachIndexed { index, stage ->
            key(stage.stage) { RailRow(index + 1, stage, paused) }
        }
    }
}

@Composable
private fun RailRow(number: Int, stage: StageUi, paused: Boolean) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val active = stage.status == StageStatus.ACTIVE
    val label = userStageLabel(stage.stage)
    val spoken = spokenRow(label, stage)
    val tail = when (stage.status) {
        StageStatus.DONE -> stage.tookMs?.let { stringResource(R.string.scr03_stage_done_took, formatTimecode(it)) }
            ?: stringResource(R.string.scr03_stage_done)
        StageStatus.SKIPPED -> stringResource(R.string.scr03_stage_skipped)
        StageStatus.FAILED -> stringResource(R.string.scr03_stage_failed)
        else -> null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight)
            .testTag(Tags.rowStage(stage.stage.name))
            .then(if (active) Modifier else Modifier.spokenAs(spoken, label))
            .padding(horizontal = StenoSpace.m, vertical = StenoSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.m),
    ) {
        StepDot(number, stage.status, paused)
        Text(
            text = label,
            modifier = Modifier
                .weight(1f)
                .then(
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
            style = if (active) type.bodySmall.copy(fontWeight = FontWeight.W700) else type.bodySmall,
            color = if (active) colors.onSurface else colors.onSurfaceVariant,
        )
        tail?.let { Text(it, style = type.meta, color = colors.onSurfaceVariant) }
    }
}
