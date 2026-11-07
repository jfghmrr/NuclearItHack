package ru.stenokot.core.diar

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.stenokot.core.storage.DiarClusterRecord
import ru.stenokot.core.storage.DiarJournalLine
import ru.stenokot.core.storage.DiarTurnRecord

/** Малый говорящий в настройке экспорта: другой голос остаётся, осколок кластера поглощается. */
class DistinctSpeakerTest {
    private fun axis(index: Int): List<Float> = List(8) { if (it == index) 1f else 0f }

    private fun blend(first: Int, second: Int, cosine: Float): List<Float> {
        val rest = kotlin.math.sqrt(1f - cosine * cosine)
        return List(8) { if (it == first) cosine else if (it == second) rest else 0f }
    }

    private fun window(small: List<Float>?) = DiarJournalLine(
        0,
        0,
        300_000,
        listOf(
            DiarTurnRecord(0, 0, 100_000),
            DiarTurnRecord(1, 100_000, 200_000),
            DiarTurnRecord(2, 200_000, 201_500),
        ),
        listOf(
            DiarClusterRecord(0, 100_000, axis(0)),
            DiarClusterRecord(1, 100_000, axis(1)),
            DiarClusterRecord(2, 1_500, small),
        ),
    )

    private fun speakers(line: DiarJournalLine, config: SpeakerBookConfig): Int {
        val book = SpeakerBook(config)
        return book.finish(listOf(book.addWindow(line))).map { it.speaker }.toSet().size
    }

    @Test
    fun distinctShortVoiceStays() {
        assertEquals(3, speakers(window(axis(2)), SpeakerBookConfig.EXPORT))
    }

    @Test
    fun defaultConfigAbsorbsShortVoice() {
        assertEquals(2, speakers(window(axis(2)), SpeakerBookConfig()))
    }

    @Test
    fun closeShortVoiceIsAbsorbed() {
        assertEquals(2, speakers(window(blend(1, 2, 0.5f)), SpeakerBookConfig.EXPORT))
    }

    @Test
    fun shortVoiceWithoutPrintIsAbsorbed() {
        assertEquals(2, speakers(window(null), SpeakerBookConfig.EXPORT))
    }

    @Test
    fun severalDistinctShortVoicesStay() {
        val line = DiarJournalLine(
            0,
            0,
            300_000,
            listOf(DiarTurnRecord(0, 0, 200_000), DiarTurnRecord(1, 200_000, 201_000), DiarTurnRecord(2, 202_000, 203_000)),
            listOf(
                DiarClusterRecord(0, 200_000, axis(0)),
                DiarClusterRecord(1, 1_000, axis(1)),
                DiarClusterRecord(2, 1_000, axis(2)),
            ),
        )
        assertEquals(3, speakers(line, SpeakerBookConfig.EXPORT))
    }
}
