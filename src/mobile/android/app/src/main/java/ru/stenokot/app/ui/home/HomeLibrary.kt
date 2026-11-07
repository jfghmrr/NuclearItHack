package ru.stenokot.app.ui.home

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import ru.stenokot.app.domain.JobSummaryUi
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.Stage

/** Фильтр библиотеки. */
enum class LibraryFilter { ALL, DONE, IN_WORK }

/** Положение записи в библиотеке. От него зависят вид карточки и её номер в автоматике. */
enum class RecordKind { ACTIVE, SELECTED, QUEUED, INTERRUPTED, HISTORY }

/** Строка библиотеки: подпись дня или карточка записи. */
@Immutable
sealed interface LibraryRow {
    /** Ключ строки в списке. */
    val key: String

    /** Подпись дня. Одинаковые дни подряд объединяются. */
    data class Day(val date: LocalDate, val position: Int) : LibraryRow {
        override val key: String get() = "day-$date-$position"
    }

    /** Карточка записи. [index] считается отдельно для очереди и для истории. */
    data class Record(val job: JobSummaryUi, val kind: RecordKind, val index: Int) : LibraryRow {
        override val key: String get() = "record-${job.id}"
    }
}

/** Стенограмма уже есть: идёт только языковая модель или всё закончено. */
private fun JobState.hasTranscript(): Boolean = when (this) {
    is JobState.Done, is JobState.TranscriptReady, JobState.WaitingModel -> true
    is JobState.Running -> stage == Stage.LLM
    else -> false
}

private fun RecordKind.matches(filter: LibraryFilter, state: JobState): Boolean = when (filter) {
    LibraryFilter.ALL -> true
    LibraryFilter.DONE -> this == RecordKind.HISTORY && state.hasTranscript()
    LibraryFilter.IN_WORK -> this != RecordKind.HISTORY
}

private fun JobSummaryUi.matches(query: String): Boolean =
    query.isBlank() || title.contains(query.trim(), ignoreCase = true)

private fun ordered(jobs: List<JobSummaryUi>): List<Pair<JobSummaryUi, RecordKind>> {
    val sections = homeSections(jobs)
    return buildList {
        sections.active?.let { add(it to RecordKind.ACTIVE) }
        sections.selected?.let { add(it to RecordKind.SELECTED) }
        sections.queue.forEach { add(it to RecordKind.QUEUED) }
        sections.interrupted?.let { add(it to RecordKind.INTERRUPTED) }
        sections.history.forEach { add(it to RecordKind.HISTORY) }
    }
}

/**
 * Строит библиотеку: сначала то, что в работе, в порядке очереди, потом история от новых к старым.
 * Подпись дня ставится при смене дня, поэтому текущая работа всегда наверху, но дата у неё верная.
 */
fun libraryRows(
    jobs: List<JobSummaryUi>,
    filter: LibraryFilter,
    query: String,
    zone: ZoneId = ZoneId.systemDefault(),
): List<LibraryRow> {
    val rows = mutableListOf<LibraryRow>()
    var day: LocalDate? = null
    var queueIndex = 0
    var historyIndex = 0
    ordered(jobs).forEach { (job, kind) ->
        if (!kind.matches(filter, job.state) || !job.matches(query)) return@forEach
        val date = Instant.ofEpochMilli(job.createdAtMs).atZone(zone).toLocalDate()
        if (date != day) {
            rows += LibraryRow.Day(date, rows.size)
            day = date
        }
        val index = when (kind) {
            RecordKind.QUEUED -> queueIndex++
            RecordKind.HISTORY -> historyIndex++
            else -> 0
        }
        rows += LibraryRow.Record(job, kind, index)
    }
    return rows
}

/** Найденный файл подходит под строку поиска. */
fun audioMatches(name: String, query: String): Boolean =
    query.isBlank() || name.contains(query.trim(), ignoreCase = true)
