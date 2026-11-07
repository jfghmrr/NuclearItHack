package ru.stenokot.core.diar

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

    private fun SpeakerBook.run(vararg lines: DiarJournalLine): List<SpeakerTurn> =
        finish(lines.map { addWindow(it) })

    @Test
    fun sameVoiceInNextWindowKeepsSpeaker() {
        val book = SpeakerBook()
        book.addWindow(
            line(0, 0, 300_000, listOf(turn(5, 0, 10_000), turn(9, 10_000, 20_000)),
                listOf(cluster(5, 10_000, axis(0)), cluster(9, 10_000, axis(1)))),
        )
        val mapping = book.link(
            line(1, 300_000, 590_000, listOf(turn(0, 300_000, 310_000), turn(1, 310_000, 320_000)),
                listOf(cluster(0, 10_000, axis(1)), cluster(1, 10_000, axis(0)))),
        )
        assertEquals(mapOf(1 to 0, 0 to 1), mapping)
        assertEquals(2, book.speakerCount)
    }

    @Test
    fun cosineAtThresholdMatchesBelowDoesNot() {
        val first = line(0, 0, 100_000, listOf(turn(0, 0, 10_000)), listOf(cluster(0, 10_000, axis(0))))
        val book = SpeakerBook()
        book.link(first)
        val high = book.link(
            line(1, 100_000, 200_000, listOf(turn(0, 100_000, 110_000)), listOf(cluster(0, 10_000, voice(0.61)))),
        )
        assertEquals(0, high[0])
        val other = SpeakerBook()
        other.link(first)
        val low = other.link(
            line(1, 100_000, 200_000, listOf(turn(0, 100_000, 110_000)), listOf(cluster(0, 10_000, voice(0.59)))),
        )
        assertEquals(1, low[0])
    }

    @Test
    fun overclusteredWindowMergesIntoOneSpeaker() {
        val book = SpeakerBook()
        book.link(
            line(0, 0, 100_000, listOf(turn(0, 0, 10_000), turn(1, 20_000, 30_000)),
                listOf(cluster(0, 10_000, axis(0)), cluster(1, 10_000, voice(0.9)))),
        )
        assertEquals(1, book.speakerCount)
    }

    @Test
    fun simultaneousClustersStaySeparate() {
        val book = SpeakerBook()
        book.link(
            line(0, 0, 100_000, listOf(turn(0, 0, 10_000), turn(1, 5_000, 15_000)),
                listOf(cluster(0, 10_000, axis(0)), cluster(1, 10_000, voice(0.9)))),
        )
        assertEquals(2, book.speakerCount)
    }

    @Test
    fun turnsAreCroppedToWindowResponsibility() {
        val book = SpeakerBook()
        val first = book.addWindow(
            line(0, 0, 295_000, listOf(turn(0, 280_000, 300_000)), listOf(cluster(0, 20_000, axis(0)))),
        )
        val second = book.addWindow(
            line(1, 295_000, 590_000, listOf(turn(0, 290_000, 320_000)), listOf(cluster(0, 30_000, axis(1)))),
        )
        assertEquals(
            listOf(SpeakerTurn(280_000, 295_000, 0), SpeakerTurn(295_000, 320_000, 1)),
            book.finish(listOf(first, second)),
        )
    }

    @Test
    fun confidenceIsCarriedThrough() {
        val turns = SpeakerBook().addWindow(
            line(0, 0, 100_000, listOf(DiarTurnRecord(0, 0, 10_000, 0.7f)), listOf(cluster(0, 10_000, axis(0)))),
        )
        assertEquals(0.7f, turns.single().confidence)
    }

    @Test
    fun clusterWithoutEmbeddingTakesNearestSpeaker() {
        val mapping = SpeakerBook().link(
            line(0, 0, 100_000, listOf(turn(0, 0, 10_000), turn(1, 30_000, 40_000), turn(2, 11_000, 12_000)),
                listOf(cluster(0, 10_000, axis(0)), cluster(1, 10_000, axis(1)), cluster(2, 1_000, null))),
        )
        assertEquals(mapping[0], mapping[2])
    }

    @Test
    fun windowWithoutAnyEmbeddingKeepsLastSpeaker() {
        val book = SpeakerBook()
        book.addWindow(line(0, 0, 100_000, listOf(turn(0, 0, 10_000)), listOf(cluster(0, 10_000, axis(0)))))
        val mapping = book.link(line(1, 100_000, 200_000, listOf(turn(4, 100_000, 101_000)), listOf(cluster(4, 1_000, null))))
        assertEquals(0, mapping[4])
    }

    @Test
    fun smallSpeakerIsAbsorbedByNearest() {
        val near = axis(1).zip(voice(0.5)) { a, b -> a + b }
        val out = SpeakerBook().run(
            line(0, 0, 300_000,
                listOf(turn(0, 0, 100_000), turn(1, 100_000, 200_000), turn(2, 200_000, 201_500)),
                listOf(cluster(0, 100_000, axis(0)), cluster(1, 100_000, axis(1)), cluster(2, 1_500, near))),
        )
        assertEquals(setOf(0, 1), out.map { it.speaker }.toSet())
        assertEquals(1, out.first { it.endMs >= 201_000 }.speaker)
    }

    @Test
    fun smallSpeakerThresholdScalesAndClamps() {
        val large = SpeakerBook().run(
            line(0, 0, 600_000,
                listOf(turn(0, 0, 400_000), turn(1, 400_000, 405_000)),
                listOf(cluster(0, 400_000, axis(0)), cluster(1, 5_000, axis(1)))),
        )
        assertEquals(1, large.map { it.speaker }.toSet().size)
        val short = SpeakerBook().run(
            line(0, 0, 60_000,
                listOf(turn(0, 0, 40_000), turn(1, 40_000, 43_000)),
                listOf(cluster(0, 40_000, axis(0)), cluster(1, 3_000, axis(1)))),
        )
        assertEquals(2, short.map { it.speaker }.toSet().size)
    }

    @Test
    fun speakerCountIsNotLimited() {
        val count = 20
        val turns = List(count) { turn(it, it * 20_000L, (it + 1) * 20_000L) }
        val clusters = List(count) { index ->
            cluster(index, 20_000, List(count) { if (it == index) 1f else 0f })
        }
        val out = SpeakerBook().run(line(0, 0, 400_000, turns, clusters))
        assertEquals(count, out.map { it.speaker }.toSet().size)
    }

    @Test
    fun oneSpeakerAlwaysSurvives() {
        val out = SpeakerBook().run(
            line(0, 0, 3_000, listOf(turn(0, 0, 1_000), turn(1, 1_000, 2_000)),
                listOf(cluster(0, 1_000, axis(0)), cluster(1, 1_000, axis(1)))),
        )
        assertEquals(1, out.map { it.speaker }.toSet().size)
    }

    @Test
    fun numbersFollowFirstAppearance() {
        val out = SpeakerBook().run(
            line(0, 0, 300_000, listOf(turn(7, 50_000, 90_000), turn(3, 0, 40_000)),
                listOf(cluster(7, 40_000, axis(0)), cluster(3, 40_000, axis(1)))),
        )
        assertEquals(listOf(0, 1), out.map { it.speaker })
    }

    @Test
    fun adjacentTurnsOfOneSpeakerAreMerged() {
        val out = SpeakerBook().run(
            line(0, 0, 100_000, listOf(turn(0, 0, 50_000)), listOf(cluster(0, 50_000, axis(0)))),
            line(1, 100_000, 200_000, listOf(turn(0, 50_000, 120_000)), listOf(cluster(0, 20_000, axis(0)))),
        )
        assertEquals(1, out.map { it.speaker }.toSet().size)
    }

    @Test
    fun restoredBookContinuesExactlyLikeUninterrupted() {
        val lines = List(6) { index ->
            val base = index * 290_000L
            line(index, base, base + 290_000,
                listOf(turn(0, base + 1_000, base + 100_000), turn(1, base + 100_000, base + 200_000)),
                listOf(cluster(0, 99_000, axis(index % 3)), cluster(1, 100_000, axis(3 + index % 2))))
        }
        val whole = SpeakerBook()
        val straight = whole.finish(lines.map { whole.addWindow(it) })
        var book = SpeakerBook()
        val windows = lines.map { line ->
            val turns = book.addWindow(line)
            book = SpeakerBook.restore(book.state())
            turns
        }
        assertEquals(straight, book.finish(windows))
    }

    @Test
    fun soleClusterWithoutPrintMatchesItsPrint() {
        val turns = listOf(turn(3, 0, 4_000), turn(3, 6_000, 9_000))
        fun outcome(embedding: List<Float>?) = SpeakerBook(SpeakerBookConfig.EXPORT).run(
            line(0, 0, 10_000, turns, listOf(cluster(3, 7_000, embedding))),
        )
        assertEquals(outcome(axis(0)), outcome(null))
    }

    @Test
    fun finishToleratesUnknownSpeakers() {
        val out = SpeakerBook().finish(listOf(listOf(SpeakerTurn(0, 10_000, 3), SpeakerTurn(10_000, 20_000, 5))))
        assertEquals(listOf(0, 1), out.map { it.speaker })
    }
}
