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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import kotlinx.coroutines.flow.StateFlow
import ru.stenokot.app.R
import ru.stenokot.app.audio.MicRules
import ru.stenokot.app.domain.RecordingUi
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.BannerTone
import ru.stenokot.app.ui.components.BottomActions
import ru.stenokot.app.ui.components.DangerAction
import ru.stenokot.app.ui.components.GhostAction
import ru.stenokot.app.ui.components.PrimaryAction
import ru.stenokot.app.ui.components.StatusBanner
import ru.stenokot.app.ui.components.StenoDialog
import ru.stenokot.app.ui.components.StenoIcon
import ru.stenokot.app.ui.components.StenoTopBar
import ru.stenokot.app.ui.format.formatRecordDate
import ru.stenokot.app.ui.format.formatTimecode
import ru.stenokot.app.ui.layout.CenteredContent
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private const val LEVEL_BARS = 48
private const val BAR_SHARE = 0.5f
private const val MIN_BAR_SHARE = 0.08f
private val WaveHeight = 122.dp
private val WaveHeightShort = 64.dp
private val MicTile = 64.dp
private val MicTileRadius = 20.dp
private val RecordDot = 8.dp

/** Последние значения уровня для волны. Живёт вне состояния: запись идёт по кругу, рисование только читает. */
private class LevelHistory(size: Int) {
    private val values = FloatArray(size)
    private var head = 0

    /** Добавляет значение и вытесняет самое старое. */
    fun push(level: Float) {
        values[head] = level.coerceIn(0f, 1f)
        head = (head + 1) % values.size
    }

    /** Значение по порядку от старого к новому. */
    operator fun get(index: Int): Float = values[(head + index) % values.size]
}

/**
 * Экран записи. Запись живёт в процессе, экран только показывает её, поэтому поворот ничего не теряет.
 * Назад не отменяет запись: приложение уходит в фон, а запись продолжается под уведомлением.
 */
@Composable
fun RecordRoute(controller: StenoController) {
    val state by controller.recording.collectAsStateWithLifecycle()
    val active = state as? RecordingUi.Active
    val elapsedMs = active?.elapsedMs ?: 0L
    var confirming by rememberSaveable { mutableStateOf(false) }
    val activity = LocalActivity.current
    val onCancel = {
        if (MicRules.needsCancelConfirmation(elapsedMs)) confirming = true else controller.cancelRecording()
    }
    val leave: () -> Unit = { activity?.moveTaskToBack(true) }

    BackHandler(onBack = leave)

    Column(Modifier.fillMaxSize()) {
        StenoTopBar(
            title = stringResource(R.string.rec_title),
            onBack = leave,
            actions = {
                IconButton(
                    onClick = onCancel,
                    enabled = active != null,
                    modifier = Modifier.testTag(Tags.REC_DISCARD),
                ) {
                    StenoIcon(
                        icon = R.drawable.ic_close,
                        tint = StenoTheme.colors.onSurface,
                        size = StenoSize.icon,
                        description = stringResource(R.string.rec_discard),
                    )
                }
            },
        )
        CenteredContent(Modifier.weight(1f)) {
            RecordingBody(elapsedMs, active?.micBusy == true, controller.micLevel)
        }
        RecordDock(enabled = active != null, onStop = controller::stopRecording, onCancel = onCancel)
    }

    if (confirming && active != null) {
        DiscardDialog(
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
private fun RecordingBody(elapsedMs: Long, micBusy: Boolean, level: StateFlow<Float>) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val layout = LocalWindowLayout.current
    val roomy = !layout.short && !layout.largeText
    val startedAt = remember { System.currentTimeMillis() - elapsedMs }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = layout.gutter)
            .padding(bottom = StenoSpace.m),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (roomy) {
            Box(
                modifier = Modifier
                    .padding(top = StenoSpace.l)
                    .size(MicTile)
                    .background(colors.primaryContainer, RoundedCornerShape(MicTileRadius)),
                contentAlignment = Alignment.Center,
            ) {
                StenoIcon(R.drawable.ic_mic, colors.primary, size = StenoSize.iconLarge)
            }
        }
        Row(
            modifier = Modifier.padding(top = if (roomy) StenoSpace.xxl else StenoSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
        ) {
            Box(Modifier.size(RecordDot).background(if (micBusy) colors.outline else colors.recording, CircleShape))
            Text(
                text = stringResource(if (micBusy) R.string.rec_waiting else R.string.rec_running),
                style = type.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        Text(
            text = formatTimecode(elapsedMs),
            modifier = Modifier.padding(top = StenoSpace.s).testTag(Tags.REC_ELAPSED),
            style = type.timer,
            color = colors.onBackground,
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 28.sp, maxFontSize = type.timer.fontSize),
        )
        Text(
            text = formatRecordDate(startedAt, LocalDate.now()),
            style = type.bodySmall,
            color = colors.onSurfaceVariant,
        )
        LevelWave(level, Modifier.padding(top = if (roomy) StenoSpace.xxl else StenoSpace.m), if (roomy) WaveHeight else WaveHeightShort)
        Text(
            text = stringResource(R.string.rec_level_caption),
            modifier = Modifier.padding(top = StenoSpace.s),
            style = type.meta,
            color = colors.onSurfaceVariant,
        )
        if (micBusy) {
            StatusBanner(
                tone = BannerTone.WARNING,
                icon = R.drawable.ic_pause_circle,
                modifier = Modifier.padding(top = StenoSpace.l),
                title = stringResource(R.string.rec_busy_title),
                body = stringResource(R.string.rec_busy_body),
                tag = Tags.REC_BUSY,
                polite = true,
            )
        } else {
            StatusBanner(
                tone = BannerTone.WORKING,
                icon = R.drawable.ic_lock,
                modifier = Modifier.padding(top = StenoSpace.l),
                body = stringResource(R.string.rec_screen_off_hint),
                polite = true,
            )
        }
    }
}

/**
 * Волна из настоящих значений микрофона: новые столбики входят справа. Значения копятся вне
 * состояния Compose, счётчик читается только при рисовании, поэтому экран не перестраивается.
 */
@Composable
private fun LevelWave(level: StateFlow<Float>, modifier: Modifier, height: Dp) {
    val colors = StenoTheme.colors
    val description = stringResource(R.string.rec_level)
    val history = remember { LevelHistory(LEVEL_BARS) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(level) {
        level.collect {
            history.push(it)
            tick += 1
        }
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .testTag(Tags.REC_LEVEL)
            .semantics { contentDescription = description },
    ) {
        tick
        val step = size.width / LEVEL_BARS
        val width = step * BAR_SHARE
        val radius = CornerRadius(width / 2)
        for (index in 0 until LEVEL_BARS) {
            val share = history[index].coerceAtLeast(MIN_BAR_SHARE)
            val bar = size.height * share
            drawRoundRect(
                color = colors.primary,
                topLeft = Offset(index * step + (step - width) / 2, (size.height - bar) / 2),
                size = Size(width, bar),
                cornerRadius = radius,
            )
        }
    }
}

@Composable
private fun RecordDock(enabled: Boolean, onStop: () -> Unit, onCancel: () -> Unit) {
    val layout = LocalWindowLayout.current
    val wide = layout.compactHeight && !layout.largeText
    BottomActions {
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(StenoSpace.s)) {
                StopAction(enabled, onStop, Modifier.weight(3f))
                CancelAction(enabled, onCancel, Modifier.weight(2f))
            }
        } else {
            StopAction(enabled, onStop)
            CancelAction(enabled, onCancel, Modifier.padding(top = StenoSpace.xs))
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
    GhostAction(
        text = stringResource(R.string.rec_cancel),
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        tag = Tags.REC_CANCEL,
    )
}

@Composable
private fun DiscardDialog(elapsed: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    StenoDialog(
        title = stringResource(R.string.rec_cancel_title),
        onDismiss = onDismiss,
        icon = R.drawable.ic_delete,
        body = stringResource(R.string.rec_cancel_body, elapsed),
    ) {
        PrimaryAction(
            text = stringResource(R.string.rec_cancel_dismiss),
            onClick = onDismiss,
            tag = Tags.REC_CANCEL_DISMISS,
        )
        DangerAction(
            text = stringResource(R.string.rec_cancel_confirm),
            onClick = onConfirm,
            tag = Tags.REC_CANCEL_CONFIRM,
        )
    }
}
