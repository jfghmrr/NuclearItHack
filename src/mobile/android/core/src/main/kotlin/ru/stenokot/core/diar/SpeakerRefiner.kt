package ru.stenokot.core.diar

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import ru.stenokot.core.asr.endsSentence
import ru.stenokot.core.engine.Progress
import ru.stenokot.core.engine.TurnRefiner
import ru.stenokot.core.merge.DefaultMerger
import ru.stenokot.core.model.AsrSegment
import ru.stenokot.core.model.SpeakerTurn
import ru.stenokot.core.model.Word
import ru.stenokot.core.storage.DiarJournalLine

/**
 * Параметры уточнения говорящих по фразам. Значения выбраны на наборах настройки: косинус между
 * центроидами людей редко выше 0.6, половины речи одного человека дают от 0.7, в том числе в шуме.
 */
data class RefineParams(
    val minPrintMs: Long = 1_500,
    val maxPrintMs: Long = 6_000,
    val maxPhraseMs: Long = 10_000,
    val pauseMs: Long = 700,
    val linkCosine: Float = 0.55f,
    val sameVoiceCosine: Float = 0.65f,
    val passes: Int = 1,
    val margin: Float = 0.03f,
    val minClusterMs: Long = 10_000,
    val absorbCosine: Float = 0.5f,
    val weakCosine: Float = 0.3f,
    val backedPhrases: Int = 2,
    val adoptGapMs: Long = 5_000,
    val maxAnchors: Int = 1_500,
)

/** Отрезок записи в миллисекундах, конец не входит. */
data class TimeSpan(val startMs: Long, val endMs: Long) {
    val lengthMs: Long
        get() = endMs - startMs
}

/** Фраза: слова одного говорящего внутри предложения и чистая от наложений часть её звука. */
data class Phrase(val startMs: Long, val endMs: Long, val speaker: Int, val clean: List<TimeSpan>) {
    /** Сколько чистого звука есть у фразы. */
    val cleanMs: Long = clean.sumOf { it.lengthMs }
}

/** Отпечаток голоса отрезков записи. Null значит, что звука мало или модель не дала результат. */
fun interface VoicePrinter {
    /** Возвращает отпечаток склеенных отрезков [spans]. */
    fun print(spans: List<TimeSpan>): FloatArray?
}

/** Настоящее уточнение для конвейера: слова берутся теми же решениями, что и в слиянии. */
class PhraseTurnRefiner(
    private val audioDurationMs: Long,
    private val params: RefineParams = RefineParams(),
) : TurnRefiner {
    override fun refine(
        asr: List<AsrSegment>,
        turns: List<SpeakerTurn>,
        lines: List<DiarJournalLine>,
        printer: VoicePrinter,
        progress: Progress,
    ): List<SpeakerTurn> {
        val refiner = SpeakerRefiner(params)
        val overlaps = SpeakerRefiner.overlaps(lines)
        val phrases = refiner.phrases(DefaultMerger(audioDurationMs).assignWords(asr, turns), overlaps)
        return if (phrases.isEmpty()) turns else refiner.refine(phrases, turns, overlaps, printer, progress)
    }
}

/**
 * Решение уточнения: новый номер каждой фразы, номер для речи без фраз по старому говорящему, центроиды
 * групп и старые говорящие, у которых не нашлось ни одной фразы с отпечатком.
 */
class RefineDecision(
    val labels: IntArray,
    val fallback: Map<Int, Int>,
    val centroids: Map<Int, FloatArray> = emptyMap(),
    val unmatched: Set<Int> = emptySet(),
)

/**
 * Уточняет говорящих после диаризации. Кластеризация внутри нативного кода судит по отпечаткам коротких
 * кусков и на части записей заметно меняется от незаметной правки звука, а распознавание уже разделило
 * речь на фразы. Отпечаток целой фразы надёжнее, поэтому говорящие собираются заново: фразы группируются
 * по среднему сходству, близкие группы сводятся по центроидам, фраза без отпечатка берёт номер соседа
 * того же говорящего. Результат выражается только через интервалы говорящих, поэтому слова и текст
 * слияние получает те же. Звук читается по одной фразе, в памяти остаются только отпечатки.
 */
class SpeakerRefiner(private val params: RefineParams = RefineParams()) {

    /** Делит слова с номерами говорящих на фразы и вычитает из них участки наложения [overlaps]. */
    fun phrases(words: List<Word>, overlaps: List<TimeSpan>): List<Phrase> {
        val out = ArrayList<Phrase>()
        var from = 0
        var overlap = 0
        for (i in words.indices) {
            val last = i == words.lastIndex
            val cut = last || words[i + 1].speaker != words[i].speaker || endsSentence(words[i].text) ||
                words[i + 1].startMs - words[i].endMs >= params.pauseMs ||
                words[i + 1].endMs - words[from].startMs > params.maxPhraseMs
            if (!cut) continue
            val startMs = words[from].startMs
            val endMs = max(words[i].endMs, startMs + 1)
            overlap = firstAfter(overlaps, startMs, overlap)
            out += Phrase(startMs, endMs, words[from].speaker, cleanSpans(startMs, endMs, overlaps, overlap))
            from = i + 1
        }
        return out
    }

    private fun firstAfter(overlaps: List<TimeSpan>, startMs: Long, from: Int = 0): Int {
        var index = from
        while (index < overlaps.size && overlaps[index].endMs <= startMs) index++
        return index
    }

    private fun cleanSpans(startMs: Long, endMs: Long, overlaps: List<TimeSpan>, first: Int): List<TimeSpan> {
        val out = ArrayList<TimeSpan>(2)
        var from = startMs
        var index = first
        while (index < overlaps.size && overlaps[index].startMs < endMs) {
            val span = overlaps[index++]
            if (span.startMs > from) out += TimeSpan(from, span.startMs)
            from = max(from, span.endMs)
        }
        if (from < endMs) out += TimeSpan(from, endMs)
        return out
    }

    /**
     * Полный проход над фразами [phrases]. Отпечатки считаются по одному, [progress] получает долю
     * пройденных фраз. Возвращает новые интервалы говорящих.
     */
    fun refine(
        phrases: List<Phrase>,
        turns: List<SpeakerTurn>,
        overlaps: List<TimeSpan>,
        printer: VoicePrinter,
        progress: Progress = Progress { _, _ -> },
    ): List<SpeakerTurn> {
        val prints = arrayOfNulls<FloatArray>(phrases.size)
        val step = max(1, phrases.size / PROGRESS_STEPS)
        phrases.forEachIndexed { index, phrase ->
            if (phrase.cleanMs >= params.minPrintMs) {
                prints[index] = printer.print(limited(phrase.clean))?.let(::normalized)
            }
            if (index % step == 0) progress.report(index.toFloat() / phrases.size, null)
        }
        val decision = decide(phrases, prints) ?: return turns
        return relabel(turns, phrases, settle(decision, turns, overlaps, printer))
    }

    /**
     * Говорящий диаризации без фраз с отпечатком остаётся отдельным человеком, только если его голос по всей
     * его речи не похож ни на одну группу. Речи на отпечаток нет: новому человеку нечем подтвердиться.
     */
    private fun settle(
        decision: RefineDecision,
        turns: List<SpeakerTurn>,
        overlaps: List<TimeSpan>,
        printer: VoicePrinter,
    ): RefineDecision {
        if (decision.unmatched.isEmpty() || decision.centroids.isEmpty()) return decision
        val moved = HashMap<Int, Int>()
        for (speaker in decision.unmatched.sorted()) {
            val spans = turns.filter { it.speaker == speaker }.sortedBy { it.startMs }
                .flatMap { turn -> cleanSpans(turn.startMs, turn.endMs, overlaps, firstAfter(overlaps, turn.startMs)) }
            val print = if (spans.sumOf { it.lengthMs } >= params.minPrintMs) {
                printer.print(limited(spans))?.let(::normalized)
            } else {
                null
            }
            val fresh = decision.fallback.getValue(speaker)
            if (print == null) {
                moved[fresh] = NO_GROUP
                continue
            }
            val best = decision.centroids.toSortedMap().maxByOrNull { cosine(print, it.value) } ?: continue
            if (cosine(print, best.value) >= params.absorbCosine) moved[fresh] = best.key
        }
        if (moved.isEmpty()) return decision
        val nearest = nearestGroups(decision, turns, moved)
        val labels = IntArray(decision.labels.size) { nearest[decision.labels[it]] ?: decision.labels[it] }
        val fallback = decision.fallback.mapValues { nearest[it.value] ?: it.value }
        return RefineDecision(labels, fallback, decision.centroids, decision.unmatched)
    }

    /** Для говорящих без отпечатка берётся группа ближайшей по времени чужой речи. */
    private fun nearestGroups(
        decision: RefineDecision,
        turns: List<SpeakerTurn>,
        moved: Map<Int, Int>,
    ): Map<Int, Int> {
        val out = HashMap(moved)
        val freshOwner = decision.unmatched.associateBy { decision.fallback.getValue(it) }
        for ((fresh, target) in moved) {
            if (target != NO_GROUP) continue
            val own = turns.filter { it.speaker == freshOwner[fresh] }
            var bestGap = Long.MAX_VALUE
            var best = decision.centroids.keys.min()
            for (other in turns) {
                val group = decision.fallback[other.speaker] ?: continue
                if (group !in decision.centroids) continue
                for (turn in own) {
                    val gap = max(0L, max(turn.startMs - other.endMs, other.startMs - turn.endMs))
                    if (gap < bestGap) {
                        bestGap = gap
                        best = group
                    }
                }
            }
            out[fresh] = best
        }
        return out
    }

    /** Собирает говорящих заново. Null значит, что фраз с отпечатком мало и диаризации нечего противопоставить. */
    fun decide(phrases: List<Phrase>, prints: Array<FloatArray?>): RefineDecision? {
        val anchors = anchors(phrases, prints)
        if (anchors.size < MIN_ANCHORS) return null
        val groups = agglomerate(anchors.map { prints[it]!! })
        val labels = IntArray(phrases.size) { UNSET }
        anchors.forEachIndexed { position, index -> labels[index] = groups[position] }
        mergeClose(phrases, prints, labels)
        absorbSmall(phrases, prints, labels)
        repeat(params.passes) { pass(phrases, prints, labels, home(phrases, labels)) }
        mergeClose(phrases, prints, labels)
        absorbSmall(phrases, prints, labels)
        val home = home(phrases, labels)
        val nextFree = (labels.maxOrNull() ?: -1) + 1
        val fallback = HashMap(home)
        var fresh = nextFree
        val unmatched = LinkedHashSet<Int>()
        for (speaker in phrases.map { it.speaker }.distinct().sorted()) {
            if (speaker !in fallback) {
                fallback[speaker] = fresh++
                unmatched += speaker
            }
        }
        val centroids = centroids(phrases, prints, labels)
        adoptRest(phrases, labels, fallback)
        return RefineDecision(labels, fallback, centroids, unmatched)
    }

    /** Фразы для группировки. На очень длинной записи берётся равномерная выборка: память ограничена. */
    private fun anchors(phrases: List<Phrase>, prints: Array<FloatArray?>): List<Int> {
        val all = phrases.indices.filter { prints[it] != null }
        if (all.size <= params.maxAnchors) return all
        return List(params.maxAnchors) { all[(it.toLong() * all.size / params.maxAnchors).toInt()] }
    }

    /** Группы при среднем сходстве не ниже [RefineParams.linkCosine]. Цепочка ближайших соседей даёт квадратичное время. */
    private fun agglomerate(prints: List<FloatArray>): IntArray {
        val count = prints.size
        val similar = Array(count) { FloatArray(count) }
        for (row in 0 until count) {
            for (column in row + 1 until count) {
                val value = cosine(prints[row], prints[column])
                similar[row][column] = value
                similar[column][row] = value
            }
        }
        val size = IntArray(count) { 1 }
        val alive = BooleanArray(count) { true }
        val owner = IntArray(count) { it }
        val chain = IntArray(count)
        var top = 0
        var left = count
        while (left > 1) {
            if (top == 0) chain[top++] = alive.indexOf(true)
            val current = chain[top - 1]
            val previous = if (top >= 2) chain[top - 2] else NONE
            var best = previous
            var bestValue = if (previous == NONE) Float.NEGATIVE_INFINITY else similar[current][previous]
            for (other in 0 until count) {
                if (alive[other] && other != current && similar[current][other] > bestValue) {
                    bestValue = similar[current][other]
                    best = other
                }
            }
            if (best != previous) {
                chain[top++] = best
                continue
            }
            top -= 2
            left--
            alive[previous] = false
            val weightA = size[current].toFloat()
            val weightB = size[previous].toFloat()
            for (other in 0 until count) {
                if (alive[other] && other != current) {
                    val value = (similar[current][other] * weightA + similar[previous][other] * weightB) / (weightA + weightB)
                    similar[current][other] = value
                    similar[other][current] = value
                }
            }
            size[current] += size[previous]
            if (bestValue >= params.linkCosine) owner[previous] = current
        }
        val roots = IntArray(count) { root(owner, it) }
        val numbers = HashMap<Int, Int>()
        return IntArray(count) { numbers.getOrPut(roots[it]) { numbers.size } }
    }

    private fun root(owner: IntArray, index: Int): Int {
        var current = index
        while (owner[current] != current) current = owner[current]
        return current
    }

    /**
     * Сводит группы с близкими центроидами. Шум у отдельных фраз разный, а у центроидов усредняется,
     * поэтому косинус между центроидами разделяет людей лучше и почти не зависит от канала и шума.
     */
    private fun mergeClose(phrases: List<Phrase>, prints: Array<FloatArray?>, labels: IntArray) {
        while (true) {
            val ordered = centroids(phrases, prints, labels).toSortedMap().entries.toList()
            var keep = NONE
            var drop = NONE
            var best = params.sameVoiceCosine
            for (a in ordered.indices) {
                for (b in a + 1 until ordered.size) {
                    val value = cosine(ordered[a].value, ordered[b].value)
                    if (value >= best) {
                        best = value
                        keep = ordered[a].key
                        drop = ordered[b].key
                    }
                }
            }
            if (keep == NONE) return
            labels.indices.forEach { if (labels[it] == drop) labels[it] = keep }
        }
    }

    /**
     * Малая группа растворяется в ближайшей большой, если голос похож. Одна фраза с шумом или смехом
     * похожа на своего говорящего слабее, чем несколько фраз человека, поэтому порог зависит от того,
     * сколько у группы подтверждений: фраз, а для одной фразы ещё и отдельного говорящего диаризации.
     */
    private fun absorbSmall(phrases: List<Phrase>, prints: Array<FloatArray?>, labels: IntArray) {
        while (true) {
            val centroids = centroids(phrases, prints, labels).toSortedMap()
            if (centroids.size < 2) return
            val speech = HashMap<Int, Long>()
            val count = HashMap<Int, Int>()
            labels.indices.forEach { index ->
                val label = labels[index]
                if (label != UNSET && prints[index] != null) {
                    speech[label] = (speech[label] ?: 0L) + phrases[index].cleanMs
                    count[label] = (count[label] ?: 0) + 1
                }
            }
            val targets = centroids.keys.toList()
            val backed = home(phrases, labels).values.toSet()
            var victim = NONE
            var target = NONE
            var smallest = Long.MAX_VALUE
            for ((group, centroid) in centroids) {
                val total = speech[group] ?: 0L
                if (total >= params.minClusterMs || total >= smallest) continue
                val confirmed = (count[group] ?: 0) >= params.backedPhrases || group in backed
                val required = if (confirmed) params.absorbCosine else params.weakCosine
                var best = NONE
                var bestValue = required
                for (other in targets) {
                    if (other == group) continue
                    val value = cosine(centroid, centroids.getValue(other))
                    if (value >= bestValue) {
                        bestValue = value
                        best = other
                    }
                }
                if (best != NONE) {
                    victim = group
                    target = best
                    smallest = total
                }
            }
            if (victim == NONE) return
            labels.indices.forEach { if (labels[it] == victim) labels[it] = target }
        }
    }

    /** Один проход: фраза переходит к другому центроиду, только если он заметно ближе своего. */
    private fun pass(phrases: List<Phrase>, prints: Array<FloatArray?>, labels: IntArray, home: Map<Int, Int>) {
        val centroids = centroids(phrases, prints, labels)
        if (centroids.isEmpty()) return
        val ordered = centroids.toSortedMap()
        phrases.indices.forEach { index ->
            val print = prints[index] ?: return@forEach
            val current = labels[index].takeIf { it != UNSET } ?: home[phrases[index].speaker]
            var best = NONE
            var bestValue = Float.NEGATIVE_INFINITY
            for ((group, centroid) in ordered) {
                val value = cosine(print, centroid)
                if (value > bestValue) {
                    bestValue = value
                    best = group
                }
            }
            val own = current?.let(centroids::get)
            when {
                own == null -> if (bestValue >= params.absorbCosine) labels[index] = best
                best != current -> labels[index] = if (bestValue >= cosine(print, own) + params.margin) best else current
                else -> labels[index] = current
            }
        }
    }

    /** Группа, в которую попала большая часть речи прежнего говорящего. */
    private fun home(phrases: List<Phrase>, labels: IntArray): Map<Int, Int> {
        val votes = HashMap<Int, HashMap<Int, Long>>()
        phrases.indices.forEach { index ->
            if (labels[index] != UNSET) {
                val own = votes.getOrPut(phrases[index].speaker) { HashMap() }
                own[labels[index]] = (own[labels[index]] ?: 0L) + phrases[index].cleanMs
            }
        }
        return votes.mapValues { (_, own) ->
            own.entries.sortedWith(compareBy({ -it.value }, { it.key })).first().key
        }
    }

    /** Фраза без отпечатка берёт номер соседней фразы того же говорящего, а без соседа его общую группу. */
    private fun adoptRest(phrases: List<Phrase>, labels: IntArray, fallback: Map<Int, Int>) {
        val known = labels.copyOf()
        val lastBefore = HashMap<Int, Int>()
        val before = IntArray(phrases.size) { NONE }
        phrases.indices.forEach { index ->
            before[index] = lastBefore[phrases[index].speaker] ?: NONE
            if (known[index] != UNSET) lastBefore[phrases[index].speaker] = index
        }
        val firstAfter = HashMap<Int, Int>()
        for (index in phrases.indices.reversed()) {
            if (known[index] == UNSET) {
                val phrase = phrases[index]
                val previous = before[index].takeIf { it != NONE }
                val next = firstAfter[phrase.speaker]
                val previousGap = previous?.let { phrase.startMs - phrases[it].endMs } ?: Long.MAX_VALUE
                val nextGap = next?.let { phrases[it].startMs - phrase.endMs } ?: Long.MAX_VALUE
                val neighbour = if (previousGap <= nextGap) previous else next
                labels[index] = if (neighbour != null && min(previousGap, nextGap) <= params.adoptGapMs) {
                    known[neighbour]
                } else {
                    fallback.getValue(phrase.speaker)
                }
            } else {
                firstAfter[phrases[index].speaker] = index
            }
        }
    }

    /** Центроиды групп, вес фразы равен длине её отпечатка. */
    private fun centroids(
        phrases: List<Phrase>,
        prints: Array<FloatArray?>,
        labels: IntArray,
    ): Map<Int, FloatArray> {
        val sums = HashMap<Int, FloatArray>()
        phrases.indices.forEach { index ->
            val print = prints[index] ?: return@forEach
            if (labels[index] == UNSET) return@forEach
            val weight = min(phrases[index].cleanMs, params.maxPrintMs).toFloat()
            val sum = sums.getOrPut(labels[index]) { FloatArray(print.size) }
            for (i in print.indices) sum[i] += print[i] * weight
        }
        return sums.mapValues { normalized(it.value) }
    }

    /**
     * Переносит решение на интервалы. Интервал говорящего делится посередине между его фразами, и
     * каждый кусок получает номер своей фразы. Интервалы без фраз идут по [RefineDecision.fallback].
     */
    fun relabel(turns: List<SpeakerTurn>, phrases: List<Phrase>, decision: RefineDecision): List<SpeakerTurn> {
        val labels = decision.labels
        val byOwner = phrases.indices.groupBy { phrases[it].speaker }
        val spare = (labels.maxOrNull() ?: 0) + (decision.fallback.values.maxOrNull() ?: 0) + 1
        val free = turns.map { it.speaker }.distinct().sorted().filter { it !in decision.fallback }
            .withIndex().associate { (position, speaker) -> speaker to spare + position }
        val out = ArrayList<SpeakerTurn>(turns.size + phrases.size)
        val placed = HashSet<Int>()
        for ((speaker, own) in turns.groupBy { it.speaker }) {
            val indices = byOwner[speaker].orEmpty()
            val fallback = decision.fallback[speaker] ?: free.getValue(speaker)
            var first = 0
            for (turn in own.sortedBy { it.startMs }) {
                while (first < indices.size && phrases[indices[first]].endMs <= turn.startMs) first++
                val inside = ArrayList<Int>()
                var position = first
                while (position < indices.size && phrases[indices[position]].startMs < turn.endMs) {
                    inside += indices[position++]
                }
                placed += inside
                if (inside.isEmpty()) out += turn.copy(speaker = fallback) else split(turn, inside, phrases, labels, out)
            }
        }
        phrases.indices.forEach { index ->
            if (index !in placed) out += SpeakerTurn(phrases[index].startMs, phrases[index].endMs, labels[index])
        }
        return out.sortedWith(compareBy({ it.startMs }, { it.endMs }, { it.speaker }))
    }

    private fun split(
        turn: SpeakerTurn,
        inside: List<Int>,
        phrases: List<Phrase>,
        labels: IntArray,
        out: MutableList<SpeakerTurn>,
    ) {
        var from = turn.startMs
        inside.forEachIndexed { position, index ->
            val next = inside.getOrNull(position + 1)
            val to = if (next == null) turn.endMs else (phrases[index].endMs + phrases[next].startMs) / 2
            val end = to.coerceIn(from, turn.endMs)
            if (from < end) out += turn.copy(startMs = from, endMs = end, speaker = labels[index])
            from = end
        }
    }

    /** Ограничивает длину звука для отпечатка: длинная фраза не нужна целиком. */
    private fun limited(spans: List<TimeSpan>): List<TimeSpan> {
        var left = params.maxPrintMs
        val out = ArrayList<TimeSpan>(spans.size)
        for (span in spans.sortedByDescending { it.lengthMs }) {
            if (left <= 0) break
            val part = min(left, span.lengthMs)
            out += TimeSpan(span.startMs, span.startMs + part)
            left -= part
        }
        return out.sortedBy { it.startMs }
    }

    companion object {
        private const val PROGRESS_STEPS = 100
        private const val MIN_ANCHORS = 2
        private const val UNSET = -1
        private const val NONE = -1
        private const val NO_GROUP = -2

        /** Участки, где в одном окне диаризации одновременно звучат разные кластеры. Память пропорциональна числу интервалов. */
        fun overlaps(lines: List<DiarJournalLine>): List<TimeSpan> {
            val spans = ArrayList<TimeSpan>()
            for (line in lines) {
                val sorted = line.turns.sortedBy { it.startMs }
                sorted.forEachIndexed { position, turn ->
                    var index = position + 1
                    while (index < sorted.size && sorted[index].startMs < turn.endMs) {
                        val other = sorted[index++]
                        if (other.cluster == turn.cluster) continue
                        val from = maxOf(turn.startMs, other.startMs, line.startMs)
                        val to = minOf(turn.endMs, other.endMs, line.endMs)
                        if (from < to) spans += TimeSpan(from, to)
                    }
                }
            }
            val merged = ArrayList<TimeSpan>(spans.size)
            for (span in spans.sortedBy { it.startMs }) {
                val open = merged.lastOrNull()
                if (open != null && span.startMs <= open.endMs) {
                    merged[merged.lastIndex] = TimeSpan(open.startMs, max(open.endMs, span.endMs))
                } else {
                    merged += span
                }
            }
            return merged
        }

        private fun normalized(values: FloatArray): FloatArray {
            var sum = 0f
            for (value in values) sum += value * value
            val length = sqrt(sum)
            return if (length > 0f) FloatArray(values.size) { values[it] / length } else values.copyOf()
        }

        private fun cosine(a: FloatArray, b: FloatArray): Float {
            var sum = 0f
            for (i in 0 until min(a.size, b.size)) sum += a[i] * b[i]
            return sum
        }
    }
}
