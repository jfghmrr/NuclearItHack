package ru.stenokot.core.diar

import kotlin.math.roundToLong
import kotlin.math.sqrt
import ru.stenokot.core.model.SpeakerTurn
import ru.stenokot.core.storage.DiarClusterRecord
import ru.stenokot.core.storage.DiarJournalLine

/** Параметры сшивки. Значения стартовые и калибруются на собственных данных. */
data class SpeakerBookConfig(
    val stitchCosine: Float = 0.6f,
    val minSpeakerShare: Double = 0.02,
    val minSpeakerMinMs: Long = 2_000,
    val minSpeakerMaxMs: Long = 6_000,
    val maxSpeakers: Int = 8,
    val maxSpeakersNarrowband: Int = 4,
    val conflictOverlapMs: Long = 500,
)

/**
 * Сводит кластеры отдельных окон диаризации к общим говорящим записи. Окна добавляются строго
 * по порядку; те же строки журнала восстанавливают книгу после перезапуска, поэтому результат не
 * зависит от того, прерывалась ли обработка. Нахлёст окон режется посередине.
 */
class SpeakerBook(
    private val narrowband: Boolean = false,
    private val config: SpeakerBookConfig = SpeakerBookConfig(),
) {
    private class Entry(var centroid: FloatArray?, var weightMs: Long)

    private val entries = ArrayList<Entry>()
    private val turns = ArrayList<SpeakerTurn>()
    private var previousEndMs: Long? = null

    /** Число говорящих до поглощения мелких. */
    val speakerCount: Int
        get() = entries.size

    /** Добавляет окно и возвращает соответствие внутреннего кластера окна общему говорящему. */
    fun addWindow(line: DiarJournalLine): Map<Int, Int> {
        val mapping = mapClusters(line)
        val boundaryMs = (previousEndMs ?: line.startMs).let { previous ->
            if (previous > line.startMs) (previous + line.startMs) / 2 else line.startMs
        }
        cropTail(boundaryMs)
        for (turn in line.turns) {
            val speaker = mapping[turn.cluster] ?: continue
            val startMs = maxOf(turn.startMs, boundaryMs)
            if (startMs < turn.endMs) turns += SpeakerTurn(startMs, turn.endMs, speaker)
        }
        previousEndMs = line.endMs
        return mapping
    }

    /** Поглощает мелких говорящих, ограничивает их число и возвращает интервалы всей записи. */
    fun finish(): List<SpeakerTurn> {
        val owner = IntArray(entries.size) { it }
        absorb(owner)
        val numbers = HashMap<Int, Int>()
        val mapped = turns
            .sortedWith(compareBy({ it.startMs }, { it.endMs }, { it.speaker }))
            .map { turn ->
                val root = rootOf(owner, turn.speaker)
                turn.copy(speaker = numbers.getOrPut(root) { numbers.size })
            }
        return mergeAdjacent(mapped)
    }

    private fun mapClusters(line: DiarJournalLine): Map<Int, Int> {
        val mapping = HashMap<Int, Int>()
        val known = line.clusters.filter { it.embedding != null }.sortedByDescending { it.speechMs }
        for (cluster in known) {
            val embedding = normalized(cluster.embedding!!)
            val taken = conflictingSpeakers(line, cluster, mapping)
            val match = bestMatch(embedding, taken)
            val speaker = match ?: newEntry()
            mapping[cluster.cluster] = speaker
            update(entries[speaker], embedding, cluster.speechMs)
        }
        for (cluster in line.clusters.filter { it.embedding == null }) {
            mapping[cluster.cluster] = nearestInTime(line, cluster.cluster, mapping)
        }
        return mapping
    }

    /** Говорящие, которых нельзя присвоить: они уже звучат одновременно с этим кластером. */
    private fun conflictingSpeakers(
        line: DiarJournalLine,
        cluster: DiarClusterRecord,
        mapping: Map<Int, Int>,
    ): Set<Int> {
        val own = line.turns.filter { it.cluster == cluster.cluster }
        val taken = HashSet<Int>()
        for ((other, speaker) in mapping) {
            var overlapMs = 0L
            for (a in own) {
                for (b in line.turns) {
                    if (b.cluster != other) continue
                    overlapMs += maxOf(0L, minOf(a.endMs, b.endMs) - maxOf(a.startMs, b.startMs))
                }
            }
            if (overlapMs >= config.conflictOverlapMs) taken += speaker
        }
        return taken
    }

    private fun bestMatch(embedding: FloatArray, taken: Set<Int>): Int? {
        var best: Int? = null
        var bestCosine = config.stitchCosine
        entries.forEachIndexed { index, entry ->
            val centroid = entry.centroid
            if (centroid != null && index !in taken) {
                val value = cosine(embedding, centroid)
                if (value >= bestCosine && (best == null || value > bestCosine)) {
                    bestCosine = value
                    best = index
                }
            }
        }
        return best
    }

    private fun newEntry(): Int {
        entries += Entry(null, 0)
        return entries.size - 1
    }

    private fun update(entry: Entry, embedding: FloatArray, weightMs: Long) {
        val centroid = entry.centroid
        val weight = maxOf(weightMs, 1L)
        if (centroid == null) {
            entry.centroid = embedding.copyOf()
        } else {
            val total = (entry.weightMs + weight).toFloat()
            val merged = FloatArray(centroid.size) {
                (centroid[it] * entry.weightMs + embedding[it] * weight) / total
            }
            entry.centroid = normalized(merged)
        }
        entry.weightMs += weight
    }

    /** Кластер без отпечатка голоса получает говорящего ближайшей по времени речи окна. */
    private fun nearestInTime(line: DiarJournalLine, cluster: Int, mapping: Map<Int, Int>): Int {
        val own = line.turns.filter { it.cluster == cluster }
        var best: Int? = null
        var bestGap = Long.MAX_VALUE
        for (other in line.turns) {
            val speaker = mapping[other.cluster] ?: continue
            for (turn in own) {
                val gap = maxOf(0L, maxOf(turn.startMs - other.endMs, other.startMs - turn.endMs))
                if (gap < bestGap) {
                    bestGap = gap
                    best = speaker
                }
            }
        }
        return best ?: turns.lastOrNull()?.speaker ?: newEntry()
    }

    private fun cropTail(boundaryMs: Long) {
        var i = turns.size - 1
        while (i >= 0) {
            val turn = turns[i]
            when {
                turn.endMs <= boundaryMs -> Unit
                turn.startMs >= boundaryMs -> turns.removeAt(i)
                else -> turns[i] = turn.copy(endMs = boundaryMs)
            }
            i--
        }
    }

    private fun absorb(owner: IntArray) {
        val speech = LongArray(entries.size)
        for (turn in turns) speech[turn.speaker] += turn.endMs - turn.startMs
        val minMs = (unionMs() * config.minSpeakerShare).roundToLong()
            .coerceIn(config.minSpeakerMinMs, config.minSpeakerMaxMs)
        val limit = if (narrowband) config.maxSpeakersNarrowband else config.maxSpeakers
        while (true) {
            val alive = entries.indices.filter { owner[it] == it && speech[it] > 0 }
            if (alive.size < 2) return
            val smallest = alive.minWith(compareBy({ speech[it] }, { it }))
            if (speech[smallest] >= minMs && alive.size <= limit) return
            val target = nearestSpeaker(smallest, alive)
            owner[smallest] = target
            speech[target] += speech[smallest]
            speech[smallest] = 0
            mergeCentroid(entries[target], entries[smallest])
        }
    }

    private fun nearestSpeaker(speaker: Int, alive: List<Int>): Int {
        val centroid = entries[speaker].centroid
        val others = alive.filter { it != speaker }
        if (centroid != null) {
            val best = others.filter { entries[it].centroid != null }
                .maxByOrNull { cosine(centroid, entries[it].centroid!!) }
            if (best != null) return best
        }
        return nearestByTime(speaker, others)
    }

    private fun nearestByTime(speaker: Int, others: List<Int>): Int {
        val own = turns.filter { it.speaker == speaker }
        var best = others.first()
        var bestGap = Long.MAX_VALUE
        for (other in turns) {
            if (other.speaker !in others) continue
            for (turn in own) {
                val gap = maxOf(0L, maxOf(turn.startMs - other.endMs, other.startMs - turn.endMs))
                if (gap < bestGap) {
                    bestGap = gap
                    best = other.speaker
                }
            }
        }
        return best
    }

    private fun mergeCentroid(target: Entry, source: Entry) {
        val centroid = source.centroid ?: return
        update(target, centroid, source.weightMs)
    }

    private fun rootOf(owner: IntArray, speaker: Int): Int {
        var current = speaker
        while (owner[current] != current) current = owner[current]
        return current
    }

    private fun unionMs(): Long {
        var total = 0L
        var coveredTo = Long.MIN_VALUE
        for (turn in turns.sortedBy { it.startMs }) {
            val from = maxOf(turn.startMs, coveredTo)
            if (turn.endMs > from) {
                total += turn.endMs - from
                coveredTo = turn.endMs
            }
        }
        return total
    }

    private fun mergeAdjacent(sorted: List<SpeakerTurn>): List<SpeakerTurn> {
        val open = HashMap<Int, Int>()
        val out = ArrayList<SpeakerTurn>(sorted.size)
        for (turn in sorted) {
            val index = open[turn.speaker]
            if (index != null && turn.startMs <= out[index].endMs) {
                out[index] = out[index].copy(endMs = maxOf(out[index].endMs, turn.endMs))
            } else {
                open[turn.speaker] = out.size
                out += turn
            }
        }
        return out
    }

    private fun normalized(values: List<Float>): FloatArray = normalized(values.toFloatArray())

    private fun normalized(values: FloatArray): FloatArray {
        var sum = 0f
        for (value in values) sum += value * value
        val length = sqrt(sum)
        return if (length > 0f) FloatArray(values.size) { values[it] / length } else values.copyOf()
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in 0 until minOf(a.size, b.size)) sum += a[i] * b[i]
        return sum
    }
}
