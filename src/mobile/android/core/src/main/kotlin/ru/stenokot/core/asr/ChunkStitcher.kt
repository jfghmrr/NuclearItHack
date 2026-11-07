package ru.stenokot.core.asr

import kotlin.math.abs
import ru.stenokot.core.model.AsrSegment
import ru.stenokot.core.model.Word

/** Наибольшая разница старта, при которой два одинаковых слова в нахлёсте считаются дублем. */
private const val DUPLICATE_MS = 120L

/**
 * Склеивает слова срезов в одну хронологическую последовательность.
 * Нахлёст определяется по границам срезов и режется посередине. Дубль убирается только внутри
 * нахлёста, вне его повторы слов настоящие и остаются всегда.
 * Слова только из знаков ещё не закреплены: срез может оказаться целиком из них.
 */
fun stitchChunks(asr: List<AsrSegment>): List<Word> {
    val out = ArrayList<Word>()
    var previousEndMs: Long? = null
    for (chunk in asr.sortedBy { it.segment.startSample }) {
        var words = segmentWords(chunk)
        val startMs = samplesToMs(chunk.segment.startSample)
        val overlapEndMs = previousEndMs
        if (overlapEndMs != null && overlapEndMs > startMs) {
            val middleMs = (startMs + overlapEndMs) / 2
            while (out.isNotEmpty() && out[out.size - 1].startMs >= middleMs) out.removeAt(out.size - 1)
            words = withoutDuplicates(out, words.filter { it.startMs >= middleMs }, startMs, overlapEndMs)
        }
        out += words
        previousEndMs = samplesToMs(chunk.segment.endSample)
    }
    return out
}

private fun withoutDuplicates(
    left: List<Word>,
    right: List<Word>,
    overlapStartMs: Long,
    overlapEndMs: Long,
): List<Word> {
    val candidates = left.takeLastWhile { it.startMs >= overlapStartMs }.toMutableList()
    return right.filterNot { word ->
        val key = comparableWord(word.text)
        val index = if (word.startMs < overlapEndMs && key.isNotEmpty()) {
            candidates.indexOfFirst {
                abs(it.startMs - word.startMs) <= DUPLICATE_MS && comparableWord(it.text) == key
            }
        } else {
            -1
        }
        if (index >= 0) candidates.removeAt(index)
        index >= 0
    }
}
