package ru.stenokot.core.diar

import ru.stenokot.core.model.SpeakerTurn
import ru.stenokot.core.model.Word

/** Параметры отделения тихих ответов. */
data class QuietParams(
    val dropDb: Float = 10f,
    val pauseMs: Long = 300,
    val unitPauseMs: Long = 700,
    val matchDb: Float = 4f,
    val referenceShare: Float = 0.75f,
)

/** Уровень речи на отрезке записи, дБ. Null значит, что звука слишком мало для оценки. */
fun interface LevelMeter {
    /** Возвращает уровень отрезка [span]. */
    fun levelDb(span: TimeSpan): Float?
}

/**
 * Отделяет тихие короткие ответы от громкого собеседника. На одном микрофоне дальний человек заметно
 * тише, а распознаватель ставит тире на смене говорящего. Отрезок после тире, отделённый паузами и
 * намного тише обычной речи своего говорящего, уходит другому человеку: самому близкому по уровню из
 * более тихих говорящих. Если в записи говорящий один, ответы получает один общий новый говорящий, а
 * если тихого собеседника среди нескольких нет, отрезок остаётся на месте: новый человек из одного
 * уровня громкости ненадёжен. Решение выражается через интервалы говорящих, слова и текст не меняются.
 * Модель не нужна, только уровень звука.
 */
class QuietReplies(private val params: QuietParams = QuietParams()) {

    /** Возвращает интервалы, в которых тихие ответы отданы другому говорящему. */
    fun apply(words: List<Word>, turns: List<SpeakerTurn>, meter: LevelMeter): List<SpeakerTurn> {
        if (words.isEmpty()) return turns
        val units = units(words)
        val levels = units.map { meter.levelDb(TimeSpan(words[it.first].startMs, words[it.last].endMs)) }
        val byOwner = units.indices.filter { levels[it] != null }.groupBy { words[units[it].first].speaker }
        val medians = byOwner.mapValues { (_, own) -> quantile(own.map { levels[it]!! }, 0.5f) }
        val references = byOwner.mapValues { (_, own) -> quantile(own.map { levels[it]!! }, params.referenceShare) }
        val quietSpeaker = (turns.maxOfOrNull { it.speaker } ?: -1) + 1
        val moves = ArrayList<Pair<IntRange, Int>>()
        units.forEachIndexed { index, unit ->
            val level = levels[index] ?: return@forEachIndexed
            val owner = words[unit.first].speaker
            val ownLevel = references[owner] ?: return@forEachIndexed
            if (!isReply(words, unit) || level > ownLevel - params.dropDb) return@forEachIndexed
            val others = medians.filterKeys { it != owner }
            val target = if (others.isEmpty()) {
                quietSpeaker
            } else {
                others.entries.filter { it.value <= ownLevel - params.matchDb }
                    .minByOrNull { kotlin.math.abs(it.value - level) }?.key ?: return@forEachIndexed
            }
            moves += unit to target
        }
        if (moves.isEmpty()) return turns
        var out = turns
        for ((unit, target) in moves) {
            out = moveSpan(out, TimeSpan(words[unit.first].startMs, words[unit.last].endMs), words[unit.first].speaker, target)
        }
        return out.sortedWith(compareBy({ it.startMs }, { it.endMs }, { it.speaker }))
    }

    /** Отрезки: слова до тире, до смены говорящего или до длинной паузы. */
    private fun units(words: List<Word>): List<IntRange> {
        val out = ArrayList<IntRange>()
        var from = 0
        for (i in words.indices) {
            val last = i == words.lastIndex
            if (last || endsWithDash(words[i]) || words[i + 1].speaker != words[i].speaker ||
                words[i + 1].startMs - words[i].endMs >= params.unitPauseMs
            ) {
                out += from..i
                from = i + 1
            }
        }
        return out
    }

    private fun isReply(words: List<Word>, unit: IntRange): Boolean {
        if (unit.first == 0 || !endsWithDash(words[unit.first - 1])) return false
        val before = words[unit.first].startMs - words[unit.first - 1].endMs
        val after = if (unit.last + 1 < words.size) words[unit.last + 1].startMs - words[unit.last].endMs else Long.MAX_VALUE
        return before >= params.pauseMs && after >= params.pauseMs
    }

    private fun endsWithDash(word: Word): Boolean = word.text.trimEnd().endsWith(DASH)

    /** Речь говорящего [from] внутри [span] переходит к [to]; если её не было, отрезок отдаётся [to] целиком. */
    private fun moveSpan(turns: List<SpeakerTurn>, span: TimeSpan, from: Int, to: Int): List<SpeakerTurn> {
        val out = ArrayList<SpeakerTurn>(turns.size + 2)
        for (turn in turns) {
            if (turn.speaker != from || turn.endMs <= span.startMs || turn.startMs >= span.endMs) {
                out += turn
                continue
            }
            if (turn.startMs < span.startMs) out += turn.copy(endMs = span.startMs)
            if (turn.endMs > span.endMs) out += turn.copy(startMs = span.endMs)
        }
        out += SpeakerTurn(span.startMs, span.endMs, to)
        return out
    }

    private companion object {
        const val DASH = '—'

        fun quantile(values: List<Float>, share: Float): Float {
            val sorted = values.sorted()
            return sorted[((sorted.size - 1) * share).toInt()]
        }
    }
}
