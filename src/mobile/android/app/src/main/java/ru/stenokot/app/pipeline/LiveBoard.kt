package ru.stenokot.app.pipeline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import ru.stenokot.core.model.Stage

/** Живой прогресс активной задачи. На диск не попадает. */
data class LiveProgress(
    val jobId: String,
    val stage: Stage,
    val fraction: Float?,
    val counterDone: Int?,
    val counterTotal: Int?,
    val stageStartedAtMs: Long,
    val nowMs: Long,
    val finishedStagesMs: Long,
    val lowMemory: Boolean,
    val cancelling: Boolean,
    val paused: Boolean,
) {
    /** Время с начала текущей стадии. */
    val stageElapsedMs: Long get() = nowMs - stageStartedAtMs

    /** Время всей обработки без ожидания в очереди. */
    val elapsedMs: Long get() = finishedStagesMs + stageElapsedMs
}

/**
 * Табло прогресса. Смена стадии показывается сразу, а значения внутри стадии проходят через
 * [ProgressGate]: интерфейс и уведомление не обновляются чаще раза в секунду.
 */
class LiveBoard(private val clock: () -> Long) {
    private val state = MutableStateFlow<LiveProgress?>(null)
    private val gate = ProgressGate(clock = clock)

    /** Текущее значение и его изменения. */
    val flow: StateFlow<LiveProgress?> = state

    /** Показывает начало стадии [stage] задачи [jobId]. */
    @Synchronized
    fun enter(jobId: String, stage: Stage, finishedStagesMs: Long) {
        val now = clock()
        gate.allow(force = true)
        val previous = state.value?.takeIf { it.jobId == jobId }
        state.value = LiveProgress(
            jobId = jobId,
            stage = stage,
            fraction = null,
            counterDone = null,
            counterTotal = null,
            stageStartedAtMs = now,
            nowMs = now,
            finishedStagesMs = finishedStagesMs,
            lowMemory = previous?.lowMemory ?: false,
            cancelling = previous?.cancelling ?: false,
            paused = false,
        )
    }

    /** Обновляет значения стадии с ограничением частоты. [force] показывает значение сразу. */
    @Synchronized
    fun progress(fraction: Float?, done: Int? = null, total: Int? = null, force: Boolean = false) {
        if (!gate.allow(force)) return
        val now = clock()
        state.update { current ->
            current?.copy(
                fraction = fraction?.coerceIn(0f, 1f),
                counterDone = done,
                counterTotal = total,
                nowMs = now,
            )
        }
    }

    /** Показывает значение движка. Заметка вида «3/8» даёт настоящий счётчик. */
    fun report(fraction: Float, note: String?) {
        val parts = note?.split('/')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        if (parts.size == 2) progress(fraction, parts[0], parts[1]) else progress(fraction)
    }

    /** Отмечает нехватку памяти. */
    fun markLowMemory() = state.update { it?.copy(lowMemory = true) }

    /** Отмечает, что отмена принята и ждёт безопасной границы. */
    fun markCancelling(jobId: String) = state.update { current ->
        if (current?.jobId == jobId) current.copy(cancelling = true) else current
    }

    /** Отмечает паузу из-за перегрева. */
    fun markPaused(paused: Boolean) = state.update { it?.copy(paused = paused) }

    /** Обновляет прошедшее время, пока стадия молчит. */
    fun tick() {
        val now = clock()
        state.update { it?.copy(nowMs = now) }
    }

    /** Убирает прогресс: активной задачи больше нет. */
    @Synchronized
    fun clear(jobId: String? = null) {
        state.update { current -> if (jobId == null || current?.jobId == jobId) null else current }
    }
}
