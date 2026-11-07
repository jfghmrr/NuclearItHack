package ru.stenokot.app.ui.result

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.format.formatTimecode
import ru.stenokot.app.ui.theme.SpeakerPalette
import ru.stenokot.app.ui.theme.StenoColors
import ru.stenokot.app.ui.theme.StenoTheme

private val MarksBand = 6.dp
private val MarkRadius = 2.5.dp
private val WaveTop = 7.dp
private val WaveHeight = 26.dp
private val LanesTop = 37.dp
private val LanesHeight = 13.dp
private val TimelineHeight = 52.dp
private val TrackThickness = 2.dp
private val PlayheadThickness = 1.5.dp
private val BarWidth = 2.dp
private val BarStride = 3.5.dp
private val MaxLaneThickness = 3.dp
private val LaneGapWide = 2.dp
private val LaneGapTight = 1.dp
private const val DIMMED_ALPHA = 0.25f
private const val MS_IN_SECOND = 1_000L

/** Что нарисовать на ленте. Меняется редко, позиция сюда не входит. */
@Immutable
class TimelineInput(
    val spans: List<RibbonSpan>,
    val speakers: List<Int>,
    val moments: List<RibbonMoment>,
    val durationMs: Long,
)

/**
 * Столбцы огибающей как пары концов для пакетной отрисовки, по одному столбцу на шаг. Самый тихий
 * столбец остаётся точкой: пауза в разговоре видна, а не выглядит обрывом.
 */
private fun wavePoints(levels: FloatArray, stride: Float, centerY: Float, height: Float, minimum: Float): FloatArray {
    val points = FloatArray(levels.size * 4)
    levels.forEachIndexed { bar, level ->
        val half = (level * height).coerceAtLeast(minimum) / 2f
        val x = stride * (bar + 0.5f)
        points[bar * 4] = x
        points[bar * 4 + 1] = centerY - half
        points[bar * 4 + 2] = x
        points[bar * 4 + 3] = centerY + half
    }
    return points
}

/** Заготовки одной дорожки: координаты отрезков уже разложены для пакетной отрисовки. */
private class LaneDraw(val points: FloatArray, val y: Float, val color: Color)

private fun laneDraws(
    model: RibbonModel,
    geometry: LaneLayout,
    palette: SpeakerPalette,
    selected: Set<Int>,
): List<LaneDraw> = model.lanes.mapIndexed { slot, lane ->
    val y = geometry.centerY(slot)
    val points = FloatArray(lane.runs.size * 2)
    var cursor = 0
    var run = 0
    while (run < lane.runs.size) {
        points[cursor] = lane.runs[run]
        points[cursor + 1] = y
        points[cursor + 2] = lane.runs[run + 1]
        points[cursor + 3] = y
        cursor += 4
        run += 2
    }
    val fill = palette.style(lane.speaker).line
    val visible = selected.isEmpty() || lane.speaker in selected
    LaneDraw(points, y, if (visible) fill else fill.copy(alpha = DIMMED_ALPHA))
}

private fun diamondPath(marks: List<RibbonMark>, kind: RibbonMarkKind, y: Float, radius: Float): Path {
    val path = Path()
    for (mark in marks) {
        if (mark.kind != kind) continue
        path.moveTo(mark.x, y - radius)
        path.lineTo(mark.x + radius, y)
        path.lineTo(mark.x, y + radius)
        path.lineTo(mark.x - radius, y)
        path.close()
    }
    return path
}

/** Статичный слой: дорожки, линейки и метки. Строится один раз на ленту и не зависит от позиции. */
private fun CacheDrawScope.ribbonLayer(
    model: RibbonModel?,
    ticks: FloatArray,
    selected: Set<Int>,
    colors: StenoColors,
    palette: SpeakerPalette,
): DrawResult {
    val lanes = model?.lanes.orEmpty()
    val geometry = laneLayout(
        speakers = lanes.size,
        top = LanesTop.toPx(),
        heightPx = LanesHeight.toPx(),
        gapWide = LaneGapWide.toPx(),
        gapTight = LaneGapTight.toPx(),
        minThicknessPx = 1f,
        maxThicknessPx = MaxLaneThickness.toPx(),
    )
    val draws = model?.let { laneDraws(it, geometry, palette, selected) }.orEmpty()
    val marks = model?.marks.orEmpty()
    val markY = (MarksBand / 2).toPx()
    val radius = MarkRadius.toPx()
    val decisions = diamondPath(marks, RibbonMarkKind.DECISION, markY, radius)
    val others = diamondPath(marks, RibbonMarkKind.OTHER, markY, radius)
    val questions = marks.filter { it.kind == RibbonMarkKind.QUESTION }
    val actions = marks.filter { it.kind == RibbonMarkKind.ACTION }
    val tickPoints = FloatArray(ticks.size * 4)
    ticks.forEachIndexed { index, x ->
        tickPoints[index * 4] = x
        tickPoints[index * 4 + 1] = 0f
        tickPoints[index * 4 + 2] = x
        tickPoints[index * 4 + 3] = MarksBand.toPx()
    }
    val paint = Paint().apply {
        strokeCap = StrokeCap.Round
        strokeWidth = geometry.thickness
    }
    val ink = colors.onSurfaceVariant
    return onDrawBehind {
        drawIntoCanvas { canvas ->
            draws.forEach { lane ->
                paint.color = lane.color
                if (lane.points.isNotEmpty()) canvas.drawRawPoints(PointMode.Lines, lane.points, paint)
            }
        }
        if (ticks.isNotEmpty()) {
            drawIntoCanvas { canvas ->
                paint.color = colors.primary
                paint.strokeWidth = 2f
                canvas.drawRawPoints(PointMode.Lines, tickPoints, paint)
                paint.strokeWidth = geometry.thickness
            }
        } else {
            drawPath(decisions, ink)
            drawPath(others, ink)
            actions.forEach {
                drawRect(ink, Offset(it.x - radius, markY - radius), Size(radius * 2, radius * 2))
            }
            questions.forEach {
                drawCircle(ink, radius - 1f, Offset(it.x, markY), style = Stroke(width = 2f))
            }
        }
    }
}

/**
 * Огибающая записи и говорящие под ней. Говорящий занимает свою горизонталь, как на нотном стане:
 * положение отличает его без цвета и совпадает с порядком в списке говорящих. Без [envelope] остаётся
 * тонкая дорожка: выдумывать форму звука нельзя. Ленту перестраивает только смена ширины или данных.
 * [position] читается в рисовании одного верхнего слоя, поэтому воспроизведение не трогает ни ленту,
 * ни список, а огибающая постоянного размера одинаково дешева и на трёхчасовой записи.
 */
@Composable
internal fun Timeline(
    input: TimelineInput,
    envelope: ByteArray?,
    selectedSpeakers: Set<Int>,
    searchTimesMs: LongArray,
    position: () -> Long,
    onPreview: (Long) -> Unit,
    onCommit: () -> Unit,
    onSeek: (Long) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    val colors = StenoTheme.colors
    val palette = StenoTheme.speakers
    var widthPx by remember { mutableIntStateOf(0) }
    val model by produceState<RibbonModel?>(null, input, widthPx) {
        if (widthPx > 0) {
            value = withContext(Dispatchers.Default) {
                buildRibbon(input.spans, input.moments, input.speakers, input.durationMs, widthPx)
            }
        }
    }
    val ticks by produceState(FloatArray(0), searchTimesMs, input.durationMs, widthPx) {
        value = withContext(Dispatchers.Default) { bucketTicks(searchTimesMs, input.durationMs, widthPx) }
    }
    val duration = input.durationMs
    val seconds by remember { derivedStateOf { position() / MS_IN_SECOND } }
    val preview by rememberUpdatedState(onPreview)
    val commit by rememberUpdatedState(onCommit)
    val seek by rememberUpdatedState(onSeek)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TimelineHeight)
            .onSizeChanged { widthPx = it.width }
            .systemGestureExclusion()
            .testTag(Tags.SLIDER_PLAYER)
            .semantics {
                contentDescription = description
                val current = seconds * MS_IN_SECOND
                stateDescription = formatTimecode(current)
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = current.toFloat().coerceIn(0f, duration.toFloat()),
                    range = 0f..duration.toFloat().coerceAtLeast(1f),
                )
                setProgress { target ->
                    seek(target.toLong())
                    true
                }
            }
            .pointerInput(duration) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val width = size.width.toFloat()
                    preview(timeAtX(down.position.x, width, duration))
                    horizontalDrag(down.id) { change ->
                        change.consume()
                        preview(timeAtX(change.position.x, width, duration))
                    }
                    commit()
                }
            },
    ) {
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache { ribbonLayer(model, ticks, selectedSpeakers, colors, palette) },
        )
        Spacer(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val stride = BarStride.toPx()
                    val centerY = (WaveTop + WaveHeight / 2).toPx()
                    val width = BarWidth.toPx()
                    val bars = (size.width / stride).toInt()
                    val points = envelope?.let {
                        wavePoints(envelopeBars(it, bars), stride, centerY, WaveHeight.toPx(), width)
                    }
                    val paint = Paint().apply {
                        strokeCap = StrokeCap.Round
                        strokeWidth = width
                    }
                    val track = TrackThickness.toPx()
                    val bottom = (LanesTop + LanesHeight).toPx()
                    onDrawBehind {
                        val fraction = if (duration > 0) (position().toFloat() / duration).coerceIn(0f, 1f) else 0f
                        val x = size.width * fraction
                        if (points != null && points.isNotEmpty()) {
                            clipRect(left = x) {
                                paint.color = colors.outlineVariant
                                drawIntoCanvas { it.drawRawPoints(PointMode.Lines, points, paint) }
                            }
                            clipRect(right = x) {
                                paint.color = colors.primary
                                drawIntoCanvas { it.drawRawPoints(PointMode.Lines, points, paint) }
                            }
                        } else {
                            drawLine(colors.outlineVariant, Offset(0f, centerY), Offset(size.width, centerY), track)
                            drawLine(colors.primary, Offset(0f, centerY), Offset(x, centerY), track)
                        }
                        drawLine(colors.onSurface, Offset(x, WaveTop.toPx()), Offset(x, bottom), PlayheadThickness.toPx())
                    }
                },
        )
    }
}
