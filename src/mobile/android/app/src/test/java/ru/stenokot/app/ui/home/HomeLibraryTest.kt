package ru.stenokot.app.ui.home

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenokot.app.domain.JobSummaryUi
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.model.Stage

/** Порядок, фильтры и поиск библиотеки на главном экране. */
class HomeLibraryTest {
    private val zone = ZoneOffset.UTC
    private val day = 86_400_000L
    private val monday = LocalDate.of(2026, 11, 2).toEpochDay() * day

    private fun job(id: String, title: String, at: Long, state: JobState, position: Int? = null) =
        JobSummaryUi(id, title, at, 60_000, 2, state, position)

    private val done = job("done", "Обсуждение проекта", monday + 2 * day, JobState.Done(LlmStatus.APPLIED, emptyList()))
    private val old = job("old", "Идеи для приложения", monday, JobState.Done(LlmStatus.APPLIED, emptyList()))
    private val failed = job("failed", "Сломанный файл", monday + day, JobState.Cancelled)
    private val running = job("run", "Встреча с командой", monday + 2 * day, JobState.Running(Stage.ASR, 0.5f))
    private val first = job("q1", "Первая в очереди", monday + 2 * day, JobState.Queued, 1)
    private val second = job("q2", "Вторая в очереди", monday + 2 * day, JobState.Queued, 2)
    private val all = listOf(old, done, failed, second, running, first)

    private fun titles(rows: List<LibraryRow>) = rows.filterIsInstance<LibraryRow.Record>().map { it.job.id }

    @Test
    fun workGoesFirstInQueueOrderThenHistoryFromNewToOld() {
        val rows = libraryRows(all, LibraryFilter.ALL, "", zone)
        assertEquals(listOf("run", "q1", "q2", "done", "failed", "old"), titles(rows))
    }

    @Test
    fun dayLabelAppearsOnlyWhenTheDayChanges() {
        val rows = libraryRows(all, LibraryFilter.ALL, "", zone)
        val days = rows.filterIsInstance<LibraryRow.Day>().map { it.date }
        assertEquals(listOf(4L, 3L, 2L).map { LocalDate.of(2026, 11, it.toInt()) }, days)
        assertTrue(rows.first() is LibraryRow.Day)
    }

    @Test
    fun queueAndHistoryCountTheirOwnIndexes() {
        val records = libraryRows(all, LibraryFilter.ALL, "", zone).filterIsInstance<LibraryRow.Record>()
        assertEquals(listOf(0, 1), records.filter { it.kind == RecordKind.QUEUED }.map { it.index })
        assertEquals(listOf(0, 1, 2), records.filter { it.kind == RecordKind.HISTORY }.map { it.index })
    }

    @Test
    fun doneFilterKeepsOnlyRecordsWithTranscript() {
        val llm = job("llm", "Протокол идёт", monday, JobState.Running(Stage.LLM, 0.1f))
        val rows = libraryRows(all + llm, LibraryFilter.DONE, "", zone)
        assertEquals(setOf("done", "old", "llm"), titles(rows).toSet())
    }

    @Test
    fun inWorkFilterKeepsActiveAndQueue() {
        assertEquals(listOf("run", "q1", "q2"), titles(libraryRows(all, LibraryFilter.IN_WORK, "", zone)))
    }

    @Test
    fun searchIgnoresCaseAndSpacesAndUsesOnlyTheName() {
        assertEquals(listOf("done"), titles(libraryRows(all, LibraryFilter.ALL, "  ОБСУЖДЕНИЕ ", zone)))
        assertTrue(libraryRows(all, LibraryFilter.ALL, "нет такого", zone).isEmpty())
    }

    @Test
    fun keysAreUniqueEvenIfTheSameDayReturns() {
        val yesterday = job("old-run", "Вчерашняя", monday + day, JobState.Running(Stage.ASR, 0.1f))
        val rows = libraryRows(listOf(yesterday, done, old), LibraryFilter.ALL, "", zone)
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }

    @Test
    fun audioSearchMatchesName() {
        assertTrue(audioMatches("Interview.MP3", "inter"))
        assertTrue(audioMatches("Interview.MP3", ""))
        assertFalse(audioMatches("Interview.MP3", "zoom"))
    }
}
