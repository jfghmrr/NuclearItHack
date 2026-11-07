package ru.stenokot.core.merge

import ru.stenokot.core.model.SpeakerTurn

/**
 * Интервалы говорящих в удобном для поиска виде. Интервалы одного говорящего объединены и не
 * пересекаются, интервалы разных могут пересекаться. Говорящие перенумерованы подряд с нуля.
 */
internal class TurnIndex(turns: List<SpeakerTurn>, durationMs: Long) {
    private val turns: List<SpeakerTurn> = normalize(turns, durationMs)
    private val maxEnd = LongArray(this.turns.size)
    private val maxEndTurn = IntArray(this.turns.size)
    private val bySpeaker: Map<Int, List<SpeakerTurn>> = this.turns.groupBy { it.speaker }
    private val sourceNumbers: List<Int> = turns.filter { it.speaker >= 0 }.map { it.speaker }.distinct().sorted()

    /** Верхняя граница номера говорящего. */
    val speakerCount: Int = (this.turns.maxOfOrNull { it.speaker } ?: -1) + 1

    init {
        var bestEnd = Long.MIN_VALUE
        var best = -1
        this.turns.forEachIndexed { index, turn ->
            if (turn.endMs > bestEnd) {
                bestEnd = turn.endMs
                best = index
            }
            maxEnd[index] = bestEnd
            maxEndTurn[index] = best
        }
    }

    /** Номер говорящего во входных интервалах. */
    fun sourceSpeaker(number: Int): Int = sourceNumbers.getOrElse(number) { number }

    /** Вызывает [visit] для интервалов, пересекающих полуоткрытый отрезок. */
    inline fun forEachOverlapping(fromMs: Long, toMs: Long, visit: (SpeakerTurn) -> Unit) {
        var index = firstEndingAfter(fromMs)
        while (index < size && turnAt(index).startMs < toMs) {
            val turn = turnAt(index)
            if (turn.endMs > fromMs) visit(turn)
            index++
        }
    }

    /** Ближайший к отрезку интервал, если он не дальше [maxGapMs]. */
    fun nearest(fromMs: Long, toMs: Long, maxGapMs: Long): SpeakerTurn? {
        val nextIndex = firstStartingAt(toMs)
        val next = turns.getOrNull(nextIndex)
        val previous = if (nextIndex > 0) turns[maxEndTurn[nextIndex - 1]] else null
        val nextGap = next?.let { it.startMs - toMs } ?: Long.MAX_VALUE
        val previousGap = previous?.let { fromMs - it.endMs } ?: Long.MAX_VALUE
        return when {
            minOf(nextGap, previousGap) > maxGapMs -> null
            previousGap <= nextGap -> previous
            else -> next
        }
    }

    /** Самый длинный интервал говорящего [speaker], пересекающий отрезок. */
    fun longestOverlapping(speaker: Int, fromMs: Long, toMs: Long): Long {
        var longest = 0L
        forEachOverlapping(fromMs, toMs) { turn ->
            if (turn.speaker == speaker) longest = maxOf(longest, turn.endMs - turn.startMs)
        }
        return longest
    }

    /** Конец интервала говорящего [speaker], содержащего момент [atMs]. */
    fun endOfTurnAt(speaker: Int, atMs: Long): Long? = ownTurnAt(speaker, atMs)?.endMs

    /** Начало интервала говорящего [speaker], содержащего момент [atMs]. */
    fun startOfTurnAt(speaker: Int, atMs: Long): Long? = ownTurnAt(speaker, atMs)?.startMs

    /** Говорит ли кто-нибудь в момент [atMs]. */
    fun isSpeechAt(atMs: Long): Boolean {
        val index = firstEndingAfter(atMs)
        return index < size && turns[index].startMs <= atMs
    }

    private fun ownTurnAt(speaker: Int, atMs: Long): SpeakerTurn? {
        val own = bySpeaker[speaker] ?: return null
        var low = 0
        var high = own.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val turn = own[middle]
            when {
                atMs < turn.startMs -> high = middle - 1
                atMs >= turn.endMs -> low = middle + 1
                else -> return turn
            }
        }
        return null
    }

    /** Суммарное время речи говорящего. */
    fun speechMs(speaker: Int): Long = bySpeaker[speaker]?.sumOf { it.endMs - it.startMs } ?: 0L

    /** Время, в которое говорит хотя бы один говорящий. */
    fun totalSpeechMs(): Long {
        var total = 0L
        var coveredTo = Long.MIN_VALUE
        for (turn in turns) {
            val from = maxOf(turn.startMs, coveredTo)
            if (turn.endMs > from) {
                total += turn.endMs - from
                coveredTo = turn.endMs
            }
        }
        return total
    }

    val size: Int
        get() = turns.size

    fun turnAt(index: Int): SpeakerTurn = turns[index]

    fun firstEndingAfter(ms: Long): Int {
        var low = 0
        var high = turns.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (maxEnd[middle] > ms) high = middle else low = middle + 1
        }
        return low
    }

    private fun firstStartingAt(ms: Long): Int {
        var low = 0
        var high = turns.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (turns[middle].startMs >= ms) high = middle else low = middle + 1
        }
        return low
    }

    private companion object {
        fun normalize(turns: List<SpeakerTurn>, durationMs: Long): List<SpeakerTurn> {
            val valid = turns.mapNotNull { turn ->
                val start = maxOf(0L, turn.startMs)
                val end = minOf(durationMs, turn.endMs)
                if (start < end && turn.speaker >= 0) turn.copy(startMs = start, endMs = end) else null
            }
            val numbers = valid.map { it.speaker }.distinct().sorted()
                .withIndex().associate { (number, speaker) -> speaker to number }
            val merged = ArrayList<SpeakerTurn>(valid.size)
            for ((speaker, own) in valid.groupBy { it.speaker }) {
                var current: SpeakerTurn? = null
                for (turn in own.sortedBy { it.startMs }) {
                    val open = current
                    current = if (open != null && turn.startMs <= open.endMs) {
                        open.copy(endMs = maxOf(open.endMs, turn.endMs))
                    } else {
                        open?.let { merged += it }
                        turn.copy(speaker = numbers.getValue(speaker))
                    }
                }
                current?.let { merged += it }
            }
            return merged.sortedWith(compareBy({ it.startMs }, { it.endMs }, { it.speaker }))
        }
    }
}
