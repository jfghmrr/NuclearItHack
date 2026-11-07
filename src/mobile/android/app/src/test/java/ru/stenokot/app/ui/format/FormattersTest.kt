package ru.stenokot.app.ui.format

import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.stenokot.core.model.PreflightInfo

class FormattersTest {
    private fun nbsp(text: String) = text.replace(' ', NBSP)

    @Test
    fun spaceToFreeIsNeverUnderstated() {
        assertEquals(nbsp("883 МБ"), formatSpaceNeeded(882_900_000))
        assertEquals(nbsp("1 МБ"), formatSpaceNeeded(1))
        assertEquals(nbsp("1,3 ГБ"), formatSpaceNeeded(1_210_000_000))
        assertEquals(nbsp("2 ГБ"), formatSpaceNeeded(2_000_000_000))
        assertEquals(nbsp("0 МБ"), formatSpaceNeeded(-5))
    }

    @Test
    fun ratioKeepsTwoDigits() {
        assertEquals("0,42", formatRatio(0.42))
        assertEquals("1,05", formatRatio(1.05))
        assertEquals("0,50", formatRatio(0.5))
    }

    @Test
    fun memoryInGibibytes() {
        assertEquals(nbsp("1,2 ГиБ"), formatGib(1_229))
        assertEquals(nbsp("3 ГиБ"), formatGib(3_072))
    }

    @Test
    fun timecodes() {
        assertEquals("00:04", formatTimecode(4_120))
        assertEquals("12:48", formatTimecode(768_000))
        assertEquals("1:24:07", formatTimecode(5_047_000))
        assertEquals("2:48:36", formatTimecode(10_116_000))
        assertEquals("00:00", formatTimecode(-5))
        assertEquals("00:04 - 00:09", formatTimeRange(4_120, 9_480))
    }

    @Test
    fun percentNeverRoundsUp() {
        assertEquals(nbsp("42 %"), formatPercent(19f / 45f))
        assertEquals(nbsp("67 %"), formatPercent(515f / 768f))
        assertEquals(nbsp("33 %"), formatPercent(1f / 3f))
        assertEquals(nbsp("99 %"), formatPercent(0.999f))
        assertEquals(nbsp("100 %"), formatPercent(1f))
        assertEquals(nbsp("0 %"), formatPercent(-1f))
    }

    @Test
    fun numbers() {
        assertEquals(nbsp("2 418"), formatThousands(2_418))
        assertEquals("486", formatThousands(486))
        assertEquals(nbsp("1 234 567"), formatThousands(1_234_567))
        assertEquals("11,7", formatDecimal(11.7))
        assertEquals("378", formatDecimal(378.0))
        assertEquals("0,7", formatDecimal(0.72))
    }

    @Test
    fun sizes() {
        assertEquals(nbsp("11,7 МиБ"), formatMib((11.7 * 1_048_576).toLong()))
        assertEquals(nbsp("378 МиБ"), formatMib(378L * 1_048_576))
        assertEquals(nbsp("154,4 МиБ"), formatMib((154.4 * 1_048_576).toLong()))
        assertEquals(nbsp("1,8 ГБ"), formatFreeSpace(1_800_000_000))
        assertEquals(nbsp("842 МБ"), formatFreeSpace(842_000_000))
    }

    @Test
    fun historyDate() {
        val moment = ZonedDateTime.of(2026, 11, 5, 14, 20, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals("5 ноя, 14:20", formatHistoryDate(moment, ZoneOffset.UTC))
    }

    @Test
    fun durationWords() {
        assertEquals(nbsp("45 секунд"), formatDurationWords(45_000))
        assertEquals(nbsp("42 минуты"), formatDurationWords(42 * 60_000L))
        assertEquals(nbsp("13 минут"), formatDurationWords(768_000))
        assertEquals(nbsp("2 часа") + " " + nbsp("49 минут"), formatDurationWords(10_116_000))
        assertEquals(nbsp("1 час"), formatDurationWords(3_600_000))
    }

    @Test
    fun estimateRanges() {
        assertEquals("6–" + nbsp("10 минут"), formatEstimateRange(6 * 60_000L, 10 * 60_000L))
        assertEquals("меньше минуты", formatEstimateRange(10_000, 40_000))
        assertEquals(nbsp("5 минут"), formatEstimateRange(5 * 60_000L, 5 * 60_000L))
        assertEquals("3–" + nbsp("4 часа"), formatEstimateRange(3 * 3_600_000L, 3 * 3_600_000L + 60_000))
    }

    @Test
    fun preflightLineMatchesProductExample() {
        val info = PreflightInfo(
            fileName = "a.mp3",
            sizeBytes = 1,
            durationMs = 42 * 60_000L,
            mime = "audio/mpeg",
            freeBytes = 1_800_000_000,
            requiredBytes = 1,
            estimateMinMs = 6 * 60_000L,
            estimateMaxMs = 10 * 60_000L,
            estimateMeasured = true,
        )
        assertEquals(
            "Запись 42 минуты. Ожидаемое время обработки 6–10 минут. Свободно 1,8 ГБ",
            formatPreflight(info).replace(NBSP, ' '),
        )
    }

    @Test
    fun preflightLabelsRoughEstimateAndHidesUnknown() {
        val rough = PreflightInfo("a.mp3", 1, null, null, 1_800_000_000, 1, 60_000, 120_000)
        assertEquals(
            "Примерная оценка времени обработки: 1–2 минуты. Свободно 1,8 ГБ",
            formatPreflight(rough).replace(NBSP, ' '),
        )
        val unknown = rough.copy(estimateMinMs = null, estimateMaxMs = null)
        assertEquals("Свободно 1,8 ГБ", formatPreflight(unknown).replace(NBSP, ' '))
    }

    @Test
    fun remainingHiddenWithoutEstimate() {
        assertNull(formatRemaining(null, 60_000))
        assertEquals("Осталось примерно 3–5 минут", formatRemaining(180_000, 300_000)?.replace(NBSP, ' '))
    }
}
