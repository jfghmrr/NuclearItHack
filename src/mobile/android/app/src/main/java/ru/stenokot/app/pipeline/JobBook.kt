package ru.stenokot.app.pipeline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.stenokot.app.data.JobRecord
import ru.stenokot.app.data.JobStore
import ru.stenokot.app.data.toState
import ru.stenokot.core.model.statusCode

/**
 * Состояние всех задач в памяти и его запись на диск.
 *
 * Изменение сначала становится видимым, затем записывается: так интерфейс не ждёт диск. Запись идёт
 * под замком и берёт свежее значение, поэтому позднее изменение не затирается ранним. Здесь же
 * замечается каждая смена машинного статуса, поэтому ни одна стадия не выпадает из журнала.
 */
class JobBook(private val store: JobStore, private val onStatus: (String) -> Unit = {}) {
    private val state = MutableStateFlow<Map<String, JobRecord>>(emptyMap())
    private val writeLock = Mutex()

    /** Все задачи. */
    val flow: StateFlow<Map<String, JobRecord>> = state

    /** Текущий снимок задач. */
    fun snapshot(): Collection<JobRecord> = state.value.values

    /** Возвращает задачу или null. */
    fun get(id: String): JobRecord? = state.value[id]

    /** Возвращает задачу, которая обязана существовать. */
    fun require(id: String): JobRecord = checkNotNull(state.value[id]) { "Нет задачи $id" }

    /** Добавляет задачу только в память. Запись на диск делает первый [mutate] или [persist]. */
    fun add(record: JobRecord) {
        state.update { it + (record.id to record) }
        onStatus(status(record))
    }

    /** Вливает задачи, прочитанные с диска. Значения из памяти новее и не заменяются. */
    fun merge(records: List<JobRecord>) = state.update { current ->
        records.associateBy { it.id } + current
    }

    /** Изменяет задачу и записывает её. Возвращает новое значение или null, если задачи нет. */
    suspend fun mutate(id: String, change: (JobRecord) -> JobRecord): JobRecord? {
        var before: JobRecord? = null
        var updated: JobRecord? = null
        state.update { current ->
            val job = current[id]
            before = job
            updated = job?.let(change)
            if (job == null) current else current + (id to checkNotNull(updated))
        }
        val next = updated ?: return null
        persist(id)
        val previous = before
        if (previous == null || status(previous) != status(next)) onStatus(status(next))
        return next
    }

    /** Записывает свежее значение задачи на диск. */
    suspend fun persist(id: String) {
        writeLock.withLock {
            state.value[id]?.let(store::save)
        }
    }

    /** Убирает задачу из памяти. Файлы удаляет вызывающий. */
    fun remove(id: String) = state.update { it - id }

    private fun status(record: JobRecord): String = statusCode(record.toState())
}
