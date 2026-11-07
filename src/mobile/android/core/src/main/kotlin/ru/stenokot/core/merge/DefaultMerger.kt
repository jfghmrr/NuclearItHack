package ru.stenokot.core.merge

import kotlin.math.abs
import kotlin.math.roundToLong
import ru.stenokot.core.asr.attachPunctuation
import ru.stenokot.core.asr.comparableWord
import ru.stenokot.core.asr.endsSentence
import ru.stenokot.core.asr.letterCount
import ru.stenokot.core.asr.samplesToMs
import ru.stenokot.core.asr.stitchChunks
import ru.stenokot.core.engine.Merger
import ru.stenokot.core.model.AsrSegment
import ru.stenokot.core.model.SpeakerTurn
import ru.stenokot.core.model.Utterance
import ru.stenokot.core.model.Word

/** Параметры слияния. Значения стартовые и калибруются на собственных данных. */
data class MergeParams(
    val voteMsPerLetter: Long = 70,
    val minVoteMs: Long = 120,
    val maxVoteMs: Long = 600,
    val nearestTurnMs: Long = 1_000,
    val overlapShare: Double = 0.3,
    val islandWords: Int = 2,
    val islandMs: Long = 500,
    val backchannelSegmentMs: Long = 200,
    val snapWords: Int = 2,
    val snapMargin: Double = 0.6,
    val minSpeakerShare: Double = 0.02,
    val minSpeakerMinMs: Long = 2_000,
    val minSpeakerMaxMs: Long = 6_000,
    val pauseSplitMs: Long = 1_500,
    val maxUtteranceMs: Long = 30_000,
)

/**
 * Собирает реплики из слов и интервалов говорящих. Работает только со списком слов: разрез идёт
 * между словами, текст слова не меняется, поэтому знак препинания не может потеряться или уйти к
 * другому говорящему отдельно от слова.
 */
class DefaultMerger(
    private val audioDurationMs: Long,
    private val params: MergeParams = MergeParams(),
) : Merger {

    override fun merge(asr: List<AsrSegment>, turns: List<SpeakerTurn>): List<Utterance> {
        if (audioDurationMs <= 0) return emptyList()
        val words = chronologicalWords(asr)
        if (words.isEmpty()) return emptyList()
        return Pass(words, TurnIndex(turns, audioDurationMs), chunkEnds(asr, words)).run()
    }

    private fun chronologicalWords(asr: List<AsrSegment>): List<Word> =
        attachPunctuation(stitchChunks(asr))
            .sortedBy { it.startMs }
            .map { word ->
                val startMs = word.startMs.coerceIn(0L, audioDurationMs - 1)
                word.copy(startMs = startMs, endMs = word.endMs.coerceIn(startMs + 1, audioDurationMs))
            }

    /** Конец среза для каждого слова: речь не считается продолженной за его пределы. */
    private fun chunkEnds(asr: List<AsrSegment>, words: List<Word>): LongArray {
        val chunks = asr.map { it.segment }.sortedBy { it.startSample }
        val ends = LongArray(words.size)
        var chunk = 0
        words.forEachIndexed { index, word ->
            while (chunk + 1 < chunks.size && samplesToMs(chunks[chunk + 1].startSample) <= word.startMs) chunk++
            ends[index] = samplesToMs(chunks[chunk].endSample)
        }
        return ends
    }

    /** Один проход слияния. Хранит состояние в массивах, индекс соответствует слову. */
    private inner class Pass(
        private val words: List<Word>,
        private val index: TurnIndex,
        private val chunkEnd: LongArray,
    ) {
        private val count = words.size
        private val voteEnd = LongArray(count)
        private val wordEnd = LongArray(count)
        private val speaker = IntArray(count)
        private val margin = DoubleArray(count)
        private val overlap = BooleanArray(count)

        fun run(): List<Utterance> {
            computeVoteIntervals()
            vote()
            fillUnassigned()
            absorbIslands()
            snapBoundaries()
            absorbSmallSpeakers()
            return buildUtterances()
        }

        private fun computeVoteIntervals() {
            for (i in 0 until count) {
                val word = words[i]
                val allowed = (letterCount(word.text) * params.voteMsPerLetter)
                    .coerceIn(params.minVoteMs, params.maxVoteMs)
                val next = if (i + 1 < count) words[i + 1].startMs else Long.MAX_VALUE
                voteEnd[i] = maxOf(word.startMs + 1, minOf(next, word.startMs + allowed))
                wordEnd[i] = minOf(audioDurationMs, maxOf(word.endMs, voteEnd[i]))
            }
        }

        /** Голосование интервалом от начала слова: конец слова у модели ненадёжен. */
        private fun vote() {
            val votes = LongArray(index.speakerCount)
            val shortest = LongArray(index.speakerCount)
            val coversMiddle = BooleanArray(index.speakerCount)
            for (i in 0 until count) {
                val fromMs = words[i].startMs
                val toMs = voteEnd[i]
                val middleMs = (fromMs + toMs) / 2
                votes.fill(0)
                shortest.fill(Long.MAX_VALUE)
                coversMiddle.fill(false)
                index.forEachOverlapping(fromMs, toMs) { turn ->
                    votes[turn.speaker] += minOf(toMs, turn.endMs) - maxOf(fromMs, turn.startMs)
                    shortest[turn.speaker] = minOf(shortest[turn.speaker], turn.endMs - turn.startMs)
                    if (turn.startMs <= middleMs && middleMs < turn.endMs) coversMiddle[turn.speaker] = true
                }
                var first = -1
                var second = -1
                for (s in votes.indices) {
                    if (votes[s] == 0L) continue
                    if (first < 0 || votes[s] > votes[first]) {
                        second = first
                        first = s
                    } else if (second < 0 || votes[s] > votes[second]) {
                        second = s
                    }
                }
                if (first < 0) {
                    speaker[i] = index.nearest(fromMs, toMs, params.nearestTurnMs)?.speaker ?: NO_SPEAKER
                    continue
                }
                val lengthMs = (toMs - fromMs).toDouble()
                val secondVotes = if (second >= 0) votes[second] else 0L
                val contested = second >= 0 &&
                    (votes[first] == secondVotes || (coversMiddle[first] && coversMiddle[second]))
                val preferSecond = contested && shortest[second] < shortest[first]
                speaker[i] = if (preferSecond) second else first
                margin[i] = abs(votes[first] - secondVotes) / lengthMs
                overlap[i] = secondVotes >= params.overlapShare * lengthMs
            }
        }

        private fun fillUnassigned() {
            var previous = NO_SPEAKER
            for (i in 0 until count) {
                if (speaker[i] == NO_SPEAKER) speaker[i] = previous else previous = speaker[i]
            }
            var next = previous
            for (i in count - 1 downTo 0) {
                if (speaker[i] == NO_SPEAKER) speaker[i] = maxOf(next, 0) else next = speaker[i]
            }
        }

        /** Короткая серия внутри чужой реплики поглощается, кроме настоящих коротких ответов. */
        private fun absorbIslands() {
            var from = 0
            while (from < count) {
                var last = from
                while (last + 1 < count && speaker[last + 1] == speaker[from]) last++
                if (from > 0 && last + 1 < count && speaker[from - 1] == speaker[last + 1]) {
                    val short = last - from + 1 <= params.islandWords ||
                        voteEnd[last] - words[from].startMs <= params.islandMs
                    if (short && !isRealBackchannel(from, last)) {
                        speaker.fill(speaker[from - 1], from, last + 1)
                    }
                }
                from = last + 1
            }
        }

        private fun isRealBackchannel(from: Int, last: Int): Boolean {
            for (i in from..last) {
                if (comparableWord(words[i].text) !in BACKCHANNEL) return false
            }
            val longest = index.longestOverlapping(speaker[from], words[from].startMs, voteEnd[last])
            return longest >= params.backchannelSegmentMs
        }

        /**
         * Подтягивает границу смены говорящего к концу предложения. Слово с уверенным голосованием
         * не двигается: это настоящее перебивание. Сначала проверяется граница раньше текущей,
         * потому что диаризация обычно отстаёт от смены голоса.
         */
        private fun snapBoundaries() {
            var i = 0
            while (i + 1 < count) {
                if (speaker[i] == speaker[i + 1] || endsSentence(words[i].text)) {
                    i++
                    continue
                }
                for (distance in 1..params.snapWords) {
                    if (moveBackward(i, distance)) break
                    if (moveForward(i, distance)) {
                        i += distance
                        break
                    }
                }
                i++
            }
        }

        private fun moveBackward(boundary: Int, distance: Int): Boolean {
            val target = boundary - distance
            if (target < 0 || !endsSentence(words[target].text)) return false
            val moving = (target + 1)..boundary
            if (moving.any { speaker[it] != speaker[boundary] || margin[it] >= params.snapMargin }) return false
            speaker.fill(speaker[boundary + 1], target + 1, boundary + 1)
            return true
        }

        private fun moveForward(boundary: Int, distance: Int): Boolean {
            val target = boundary + distance
            if (target >= count || !endsSentence(words[target].text)) return false
            val moving = (boundary + 1)..target
            val right = speaker[boundary + 1]
            if (moving.any { speaker[it] != right || margin[it] >= params.snapMargin }) return false
            speaker.fill(speaker[boundary], boundary + 1, target + 1)
            return true
        }

        /** Мелкие кластеры, оставшиеся после диаризации, отходят ближайшему по времени слову. */
        private fun absorbSmallSpeakers() {
            val present = speaker.distinct()
            if (present.size < 2) return
            val minMs = (index.totalSpeechMs() * params.minSpeakerShare).roundToLong()
                .coerceIn(params.minSpeakerMinMs, params.minSpeakerMaxMs)
            val keeper = present.maxBy { index.speechMs(it) }
            val small = present.filter { it != keeper && index.speechMs(it) < minMs }.toSet()
            if (small.isEmpty()) return
            val previousKept = IntArray(count)
            var kept = -1
            for (i in 0 until count) {
                if (speaker[i] !in small) kept = i
                previousKept[i] = kept
            }
            kept = -1
            val owner = speaker.copyOf()
            for (i in count - 1 downTo 0) {
                if (speaker[i] !in small) {
                    kept = i
                    continue
                }
                val before = previousKept[i]
                owner[i] = when {
                    before < 0 && kept < 0 -> keeper
                    before < 0 -> speaker[kept]
                    kept < 0 -> speaker[before]
                    words[i].startMs - words[before].startMs <= words[kept].startMs - words[i].startMs ->
                        speaker[before]
                    else -> speaker[kept]
                }
            }
            owner.copyInto(speaker)
        }

        private fun buildUtterances(): List<Utterance> {
            val ranges = ArrayList<IntRange>()
            var from = 0
            for (i in 1..count) {
                val boundary = i == count || speaker[i] != speaker[i - 1] ||
                    words[i].startMs - wordEnd[i - 1] >= params.pauseSplitMs
                if (boundary) {
                    splitLong(from, i, ranges)
                    from = i
                }
            }
            val numbers = HashMap<Int, Int>()
            val out = ArrayList<Utterance>(ranges.size)
            ranges.forEachIndexed { position, range ->
                val number = numbers.getOrPut(speaker[range.first]) { numbers.size }
                val nextStart = ranges.getOrNull(position + 1)?.let { words[it.first].startMs }
                val own = range.map { words[it].copy(endMs = wordEnd[it], speaker = number) }
                out += Utterance(
                    id = position + 1,
                    speaker = number,
                    startMs = own.first().startMs,
                    endMs = utteranceEnd(range.last, nextStart),
                    words = own,
                    text = own.joinToString(" ") { it.text },
                    overlap = range.any { overlap[it] },
                )
            }
            return out
        }

        /** Делит серию на части не длиннее предела: по концу предложения, иначе по самой длинной паузе. */
        private fun splitLong(from: Int, until: Int, ranges: MutableList<IntRange>) {
            var start = from
            while (wordEnd[until - 1] - words[start].startMs > params.maxUtteranceMs) {
                var limit = start + 1
                while (limit < until && wordEnd[limit] - words[start].startMs <= params.maxUtteranceMs) limit++
                if (limit >= until) break
                val cut = sentenceCut(start, limit) ?: pauseCut(start, limit)
                ranges += start until cut
                start = cut
            }
            ranges += start until until
        }

        private fun sentenceCut(start: Int, limit: Int): Int? {
            val halfMs = params.maxUtteranceMs / 2
            for (i in limit - 1 downTo start) {
                if (wordEnd[i] - words[start].startMs < halfMs) return null
                if (endsSentence(words[i].text)) return i + 1
            }
            return null
        }

        private fun pauseCut(start: Int, limit: Int): Int {
            var best = limit
            var bestGap = -1L
            for (i in start + 1 until limit) {
                if (wordEnd[i - 1] - words[start].startMs < params.maxUtteranceMs / 2) continue
                val gap = words[i].startMs - wordEnd[i - 1]
                if (gap >= bestGap) {
                    bestGap = gap
                    best = i
                }
            }
            return best
        }

        /** Конец реплики учитывает конец сегмента говорящего и среза, но не заходит на следующую реплику. */
        private fun utteranceEnd(last: Int, nextStartMs: Long?): Long {
            val spokenEnd = index.endOfTurnAt(speaker[last], words[last].startMs)
                ?: return wordEnd[last]
            val limit = minOf(spokenEnd, chunkEnd[last], nextStartMs ?: Long.MAX_VALUE)
            return minOf(audioDurationMs, maxOf(wordEnd[last], limit))
        }
    }

    private companion object {
        const val NO_SPEAKER = -1

        /** Короткие ответы, которые остаются отдельной репликой. Сравнение идёт по приведённой форме. */
        val BACKCHANNEL = setOf(
            "да", "ага", "угу", "нет", "ну", "мм", "хм", "ясно", "ладно", "понятно", "хорошо", "точно",
            "yes", "yeah", "yep", "no", "ok", "okay", "right", "sure", "mhm", "hmm", "uhhuh",
        )
    }
}
