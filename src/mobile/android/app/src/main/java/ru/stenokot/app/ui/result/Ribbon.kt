package ru.stenokot.app.ui.result

import androidx.compose.runtime.Immutable
import kotlin.math.ceil
import kotlin.math.floor

/** Вид метки протокола на ленте. Цвет один, различие задано формой. */
enum class RibbonMarkKind { DECISION, ACTION, QUESTION, OTHER }

/** Метка протокола: координата в пикселях и вид. */
@Immutable
data class RibbonMark(val x: Float, val kind: RibbonMarkKind)

/**
 * Дорожка одного говорящего. [runs] хранит пары начало и конец в пикселях подряд: соседние
 * реплики уже слиты, поэтому размер ограничен шириной ленты, а не длиной записи.
 */
@Immutable
class RibbonLane(val speaker: Int, val runs: FloatArray)

/** Готовая лента для заданной ширины. Рисование только читает эти массивы. */
@Immutable
class RibbonModel(val widthPx: Int, val lanes: List<RibbonLane>, val marks: List<RibbonMark>)

/** Интервал речи на входе. Отдельный тип, чтобы лента не зависела от строк интерфейса. */
class RibbonSpan(val speaker: Int, val startMs: Long, val endMs: Long)

/** Метка на входе: момент времени и вид. */
class RibbonMoment(val atMs: Long, val kind: RibbonMarkKind)

private const val MARK_GAP_PX = 3f

/**
 * Раскладывает реплики по дорожкам говорящих и сливает их до целых пикселей. Трёхчасовая запись
 * даёт столько же интервалов, сколько короткая, поэтому рисование не зависит от числа реплик.
 * Самая короткая реплика занимает один пиксель: иначе говорящий пропал бы с ленты.
 */
fun buildRibbon(
    spans: List<RibbonSpan>,
    moments: List<RibbonMoment>,
    speakers: List<Int>,
    durationMs: Long,
    widthPx: Int,
): RibbonModel {
    if (widthPx <= 0 || durationMs <= 0) return RibbonModel(widthPx.coerceAtLeast(0), emptyList(), emptyList())
    val scale = widthPx.toDouble() / durationMs
    val slots = speakers.withIndex().associate { (slot, speaker) -> speaker to slot }
    val buffers = Array(speakers.size) { FloatArray(16) }
    val sizes = IntArray(speakers.size)
    for (span in spans) {
        val slot = slots[span.speaker] ?: continue
        val from = floor(span.startMs * scale).toFloat().coerceIn(0f, widthPx.toFloat())
        val to = ceil(span.endMs * scale).toFloat().coerceIn(from, widthPx.toFloat())
        appendRun(buffers, sizes, slot, from, if (to > from) to else (from + 1f).coerceAtMost(widthPx.toFloat()))
    }
    val lanes = speakers.mapIndexed { slot, speaker -> RibbonLane(speaker, buffers[slot].copyOf(sizes[slot])) }
    return RibbonModel(widthPx, lanes, bucketMoments(moments, scale, widthPx))
}

/** Дописывает интервал в дорожку, сливая его с предыдущим при касании. */
private fun appendRun(buffers: Array<FloatArray>, sizes: IntArray, slot: Int, from: Float, to: Float) {
    val size = sizes[slot]
    var runs = buffers[slot]
    if (size > 0 && from <= runs[size - 1]) {
        if (to > runs[size - 1]) runs[size - 1] = to
        return
    }
    if (size == runs.size) {
        runs = runs.copyOf(size * 2)
        buffers[slot] = runs
    }
    runs[size] = from
    runs[size + 1] = to
    sizes[slot] = size + 2
}

/** Метки ближе нескольких пикселей слипаются в одну: на ленте их всё равно не различить. */
private fun bucketMoments(moments: List<RibbonMoment>, scale: Double, widthPx: Int): List<RibbonMark> {
    val out = ArrayList<RibbonMark>(moments.size)
    var last = Float.NEGATIVE_INFINITY
    for (moment in moments.sortedBy { it.atMs }) {
        val x = floor(moment.atMs * scale).toFloat().coerceIn(0f, widthPx.toFloat())
        if (x - last < MARK_GAP_PX) continue
        out += RibbonMark(x, moment.kind)
        last = x
    }
    return out
}

/**
 * Сливает моменты в занятые пиксели. Используется для вхождений поиска: их может быть тысячи,
 * а на ленте отличаются только столбцы. Возвращает отсортированные координаты без повторов.
 */
fun bucketTicks(timesMs: LongArray, durationMs: Long, widthPx: Int): FloatArray {
    if (widthPx <= 0 || durationMs <= 0 || timesMs.isEmpty()) return FloatArray(0)
    val taken = java.util.BitSet(widthPx + 1)
    val scale = widthPx.toDouble() / durationMs
    for (time in timesMs) taken.set(floor(time * scale).toInt().coerceIn(0, widthPx))
    val out = FloatArray(taken.cardinality())
    var slot = 0
    var bit = taken.nextSetBit(0)
    while (bit >= 0) {
        out[slot++] = bit.toFloat()
        bit = taken.nextSetBit(bit + 1)
    }
    return out
}

/** Время для точки [x] ленты шириной [widthPx]. */
fun timeAtX(x: Float, widthPx: Float, durationMs: Long): Long {
    if (widthPx <= 0f) return 0
    return ((x / widthPx).coerceIn(0f, 1f) * durationMs).toLong()
}
