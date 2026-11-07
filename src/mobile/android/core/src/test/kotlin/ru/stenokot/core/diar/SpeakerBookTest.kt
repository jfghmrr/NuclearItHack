package ru.stenokot.core.diar

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenokot.core.model.SpeakerTurn
import ru.stenokot.core.storage.DiarClusterRecord
import ru.stenokot.core.storage.DiarJournalLine
import ru.stenokot.core.storage.DiarTurnRecord

class SpeakerBookTest {
    /** Отпечаток с заданным косинусом к базовому вектору. */
    private fun voice(cosine: Double, axis: Int = 1): List<Float> {
        val out = MutableList(8) { 0f }
        out[0] = cosine.toFloat()
        out[axis] = sin(kotlin.math.acos(cosine)).toFloat()
        return out
    }

    private fun axis(index: Int): List<Float> = List(8) { if (it == index) 1f else 0f }

    private fun line(
        index: Int,
        startMs: Long,
        endMs: Long,
        turns: List<DiarTurnRecord>,
        clusters: List<DiarClusterRecord>,
    ) = DiarJournalLine(index, startMs, endMs, turns, clusters)

    private fun turn(cluster: Int, from: Long, to: Long) = DiarTurnRecord(cluster, from, to)

    private fun cluster(id: Int, speech: Long, embedding: List<Float>?) = DiarClusterRecord(id, speech, embedding)

    @Test
    fun sameVoiceInNextWindowKeepsSpeaker() {
        val book = SpeakerBook()
        book.addWindow(
            line(0, 0, 300_000, listOf(turn(5, 0, 10_000), turn(9, 10_000, 20_000)),
                listOf(cluster(5, 10_000, axis(0)), cluster(9, 10_000, axis(1)))),
        )
        val mapping = book.addWindow(
            line(1, 290_000, 590_000, listOf(turn(0, 300_000, 310_000), turn(1, 310_000, 320_000)),
                listOf(cluster(0, 10_000, axis(1)), cluster(1, 10_000, axis(0)))),
        )
        assertEquals(mapOf(1 to 0, 0 to 1), mapping)
        assertEquals(2, book.speakerCount)
    }

    @Test
    fun cosineAtThresholdMatchesBelowDoesNot() {
        val book = SpeakerBook()
        book.addWindow(line(0, 0, 100_000, listOf(turn(0, 0, 10_000)), listOf(cluster(0, 10_000, axis(0)))))
        val high = book.addWindow(
            line(1, 100_000, 200_000, listOf(turn(0, 100_000, 110_000)), listOf(cluster(0, 10_000, voice(0.61)))),
        )
        assertEquals(0, high[0])
        val book2 = SpeakerBook()
        book2.addWindow(line(0, 0, 100_000, listOf(turn(0, 0, 10_000)), listOf(cluster(0, 10_000, axis(0)))))
        val low = book2.addWindow(
            line(1, 100_000, 200_000, listOf(turn(0, 100_000, 110_000)), listOf(cluster(0, 10_000, voice(0.59)))),
        )
        assertEquals(1, low[0])
    }

    @Test
    fun overclusteredWindowMergesIntoOneSpeaker() {
        val book = SpeakerBook()
        book.addWindow(
            line(0, 0, 100_000, listOf(turn(0, 0, 10_000), turn(1, 20_000, 30_000)),
                listOf(cluster(0, 10_000, axis(0)), cluster(1, 10_000, voice(0.9)))),
        )
        assertEquals(1, book.speakerCount)
    }

    @Test
    fun simultaneousClustersStaySeparate() {
        val book = SpeakerBook()
        book.addWindow(
            line(0, 0, 100_000, listOf(turn(0, 0, 10_000), turn(1, 5_000, 15_000)),
                listOf(cluster(0, 10_000, axis(0)), cluster(1, 10_000, voice(0.9)))),
        )
        assertEquals(2, book.speakerCount)
    }

    @Test
    fun overlapBetweenWindowsIsCutInTheMiddle() {
        val book = SpeakerBook()
        book.addWindow(line(0, 0, 300_000, listOf(turn(0, 280_000, 300_000)), listOf(cluster(0, 20_000, axis(0)))))
        book.addWindow(line(1, 290_000, 590_000, listOf(turn(0, 290_000, 320_000)), listOf(cluster(0, 30_000, axis(1)))))
        val out = book.finish()
        assertEquals(
            listOf(SpeakerTurn(280_000, 295_000, 0), SpeakerTurn(295_000, 320_000, 1)),
            out,
        )
    }

    @Test
    fun clusterWithoutEmbeddingTakesNearestSpeaker() {
        val book = SpeakerBook()
        val mapping = book.addWindow(
            line(0, 0, 100_000, listOf(turn(0, 0, 10_000), turn(1, 30_000, 40_000), turn(2, 11_000, 12_000)),
                listOf(cluster(0, 10_000, axis(0)), cluster(1, 10_000, axis(1)), cluster(2, 1_000, null))),
        )
        assertEquals(mapping[0], mapping[2])
    }

    @Test
    fun smallSpeakerIsAbsorbedByNearest() {
        val book = SpeakerBook()
        book.addWindow(
            line(0, 0, 300_000,
                listOf(turn(0, 0, 100_000), turn(1, 100_000, 200_000), turn(2, 200_000, 201_500)),
                listOf(cluster(0, 100_000, axis(0)), cluster(1, 100_000, axis(1)), cluster(2, 1_500, voice(0.5, 1).zip(axis(1)) { a, b -> a + b }))),
        )
        val out = book.finish()
        assertEquals(setOf(0, 1), out.map { it.speaker }.toSet())
        assertEquals(1, out.first { it.endMs >= 201_000 }.speaker)
    }

    @Test
    fun smallSpeakerThresholdScalesAndClamps() {
        val book = SpeakerBook()
        book.addWindow(
            line(0, 0, 600_000,
                listOf(turn(0, 0, 400_000), turn(1, 400_000, 405_000)),
                listOf(cluster(0, 400_000, axis(0)), cluster(1, 5_000, axis(1)))),
        )
        assertEquals(1, book.finish().map { it.speaker }.toSet().size)
        val short = SpeakerBook()
        short.addWindow(
            line(0, 0, 60_000,
                listOf(turn(0, 0, 40_000), turn(1, 40_000, 43_000)),
                listOf(cluster(0, 40_000, axis(0)), cluster(1, 3_000, axis(1)))),
        )
        assertEquals(2, short.finish().map { it.speaker }.toSet().size)
    }

    private fun manySpeakers(count: Int): DiarJournalLine {
        val turns = List(count) { turn(it, it * 20_000L, (it + 1) * 20_000L) }
        val clusters = List(count) { cluster(it, 20_000, axis(it)) }
        return line(0, 0, 300_000, turns, clusters)
    }

    @Test
    fun speakerCountIsLimited() {
        val book = SpeakerBook()
        book.addWindow(manySpeakers(8).let { it.copy(turns = it.turns, clusters = it.clusters) })
        book.addWindow(
            line(1, 300_000, 600_000,
                List(4) { turn(it, 300_000 + it * 20_000L, 300_000 + (it + 1) * 20_000L) },
                List(4) { cluster(it, 20_000, List(8) { d -> cos(it + d * 1.0).toFloat() }) }),
        )
        assertTrue(book.finish().map { it.speaker }.toSet().size <= 8)
    }

    @Test
    fun narrowbandLimitIsFour() {
        val book = SpeakerBook(narrowband = true)
        book.addWindow(manySpeakers(7))
        assertEquals(4, book.finish().map { it.speaker }.toSet().size)
    }

    @Test
    fun oneSpeakerAlwaysSurvives() {
        val book = SpeakerBook()
        book.addWindow(line(0, 0, 3_000, listOf(turn(0, 0, 1_000), turn(1, 1_000, 2_000)),
            listOf(cluster(0, 1_000, axis(0)), cluster(1, 1_000, axis(1)))))
        assertEquals(1, book.finish().map { it.speaker }.toSet().size)
    }

    @Test
    fun numbersFollowFirstAppearance() {
        val book = SpeakerBook()
        book.addWindow(
            line(0, 0, 300_000, listOf(turn(7, 50_000, 90_000), turn(3, 0, 40_000)),
                listOf(cluster(7, 40_000, axis(0)), cluster(3, 40_000, axis(1)))),
        )
        assertEquals(listOf(0, 1), book.finish().map { it.speaker })
    }

    @Test
    fun replayAfterRestartGivesSameResult() {
        val lines = List(5) { index ->
            val base = index * 290_000L
            line(index, base, base + 300_000,
                listOf(turn(0, base + 1_000, base + 100_000), turn(1, base + 100_000, base + 200_000)),
                listOf(cluster(0, 99_000, axis(index % 3)), cluster(1, 100_000, axis(3 + index % 2))))
        }
        val first = SpeakerBook().apply { lines.forEach { addWindow(it) } }.finish()
        val second = SpeakerBook().apply { lines.forEach { addWindow(it) } }.finish()
        assertEquals(first, second)
    }
}
