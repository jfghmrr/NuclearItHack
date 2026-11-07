package ru.stenokot.app.ui.record

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.stenokot.app.R
import ru.stenokot.app.audio.MicRules
import ru.stenokot.app.domain.RecordingUi
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.BannerTone
import ru.stenokot.app.ui.components.OutlinedAction
import ru.stenokot.app.ui.components.PrimaryAction
import ru.stenokot.app.ui.components.StatusBanner
import ru.stenokot.app.ui.components.StenoTopBar
import ru.stenokot.app.ui.components.resourceTags
import ru.stenokot.app.ui.format.formatTimecode
import ru.stenokot.app.ui.layout.CenteredContent
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private const val METER_SEGMENTS = 32
private const val METER_GAP_SHARE = 0.3f
private const val STOP_WEIGHT = 3f
private const val CANCEL_WEIGHT = 2f

/**
 * Экран записи. Запись живёт в процессе, экран только показывает её, поэтому поворот ничего не теряет.
 * Назад не отменяет запись: приложение уходит в фон, а запись продолжается под уведомлением.
 */
@Composable
fun RecordRoute(controller: StenoController) {
    val state by controller.recording.collectAsStateWithLifecycle()
    val level = controller.micLevel.collectAsStateWithLifecycle()
    val active = state as? RecordingUi.Active
    val elapsedMs = active?.elapsedMs ?: 0L
    var confirming by rememberSaveable { mutableStateOf(false) }
    val activity = LocalActivity.current

    BackHandler { activity?.moveTaskToBack(true) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            StenoTopBar(stringResource(R.string.rec_title))
            CenteredContent {
                RecordingBody(elapsedMs, active?.micBusy == true, level)
            }
        }
        RecordDock(
            enabled = active != null,
            onStop = controller::stopRecording,
            onCancel = {
                if (MicRules.needsCancelConfirmation(elapsedMs)) confirming = true else controller.cancelRecording()
            },
        )
    }

    if (confirming && active != null) {
        CancelDialog(
            elapsed = formatTimecode(elapsedMs),
            onConfirm = {
                confirming = false
                controller.cancelRecording()
            },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun RecordingBody(elapsedMs: Long, micBusy: Boolean, level: State<Float>) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val layout = LocalWindowLayout.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = layout.gutter)
            .padding(top = if (layout.short) StenoSpace.s else StenoSpace.xxxl),
        verticalArrangement = Arrangement.spacedBy(StenoSpace.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StenoSpace.s)) {
            Box(Modifier.size(StenoSpace.m).background(if (micBusy) colors.outline else colors.error, CircleShape))
            Text(
                text = stringResource(if (micBusy) R.string.rec_waiting else R.string.rec_running),
                style = type.labelMedium,
                color = colors.onSurface,
            )
        }
        Text(
            text = formatTimecode(elapsedMs),
            modifier = Modifier.testTag(Tags.REC_ELAPSED),
            style = type.numeral,
            color = colors.onBackground,
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 28.sp, maxFontSize = type.numeral.fontSize),
        )
        LevelMeter(level)
        if (micBusy) {
            StatusBanner(
                tone = BannerTone.WARNING,
                icon = R.drawable.ic_pause_circle,
                title = stringResource(R.string.rec_busy_title),
                body = stringResource(R.string.rec_busy_body),
                tag = Tags.REC_BUSY,
                polite = true,
            )
        } else {
            Text(
                text = stringResource(R.string.rec_screen_off_hint),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = type.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/** Шкала из делений. Значение читается только при рисовании, поэтому экран не перестраивается. */
@Composable
private fun LevelMeter(level: State<Float>) {
    val colors = StenoTheme.colors
    val description = stringResource(R.string.rec_level)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(StenoSize.icon)
            .testTag(Tags.REC_LEVEL)
            .semantics { contentDescription = description },
    ) {
        val step = size.width / METER_SEGMENTS
        val width = step * (1f - METER_GAP_SHARE)
        val radius = CornerRadius(width / 2)
        val lit = (level.value * METER_SEGMENTS).toInt()
        for (index in 0 until METER_SEGMENTS) {
            drawRoundRect(
                color = if (index < lit) colors.primary else colors.surfaceVariant,
                topLeft = Offset(index * step, 0f),
                size = Size(width, size.height),
                cornerRadius = radius,
            )
        }
    }
}

@Composable
private fun RecordDock(enabled: Boolean, onStop: () -> Unit, onCancel: () -> Unit) {
    val layout = LocalWindowLayout.current
    val colors = StenoTheme.colors
    CenteredContent(
        Modifier
            .background(colors.surface)
            .drawBehind {
                drawLine(colors.outlineVariant, Offset.Zero, Offset(size.width, 0f), StenoSize.hairline.toPx())
            },
    ) {
        val padding = Modifier.fillMaxWidth().padding(horizontal = layout.gutter, vertical = StenoSpace.m)
        val spacing = Arrangement.spacedBy(StenoSpace.s)
        if (layout.compactHeight && !layout.largeText) {
            Row(padding, horizontalArrangement = spacing) {
                StopAction(enabled, onStop, Modifier.weight(STOP_WEIGHT))
                CancelAction(enabled, onCancel, Modifier.weight(CANCEL_WEIGHT))
            }
        } else {
            Column(padding, verticalArrangement = spacing) {
                StopAction(enabled, onStop)
                CancelAction(enabled, onCancel)
            }
        }
    }
}

@Composable
private fun StopAction(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PrimaryAction(
        text = stringResource(R.string.rec_stop),
        onClick = onClick,
        modifier = modifier,
        icon = R.drawable.ic_stop_fill,
        enabled = enabled,
        tag = Tags.REC_STOP,
    )
}

@Composable
private fun CancelAction(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedAction(
        text = stringResource(R.string.rec_cancel),
        onClick = onClick,
        modifier = modifier,
        icon = R.drawable.ic_close,
        enabled = enabled,
        tag = Tags.REC_CANCEL,
    )
}

@Composable
private fun CancelDialog(elapsed: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.resourceTags(),
        containerColor = colors.surfaceContainerHigh,
        title = {
            Text(
                text = stringResource(R.string.rec_cancel_title),
                modifier = Modifier.semantics { heading() },
                style = type.titleMedium,
                color = colors.onSurface,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.rec_cancel_body, elapsed),
                style = type.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(Tags.REC_CANCEL_CONFIRM)) {
                Text(stringResource(R.string.rec_cancel_confirm), color = colors.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(Tags.REC_CANCEL_DISMISS)) {
                Text(stringResource(R.string.rec_cancel_dismiss))
            }
        },
    )
}
