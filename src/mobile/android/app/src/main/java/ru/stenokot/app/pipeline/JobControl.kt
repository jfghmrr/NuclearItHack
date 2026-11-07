package ru.stenokot.app.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.stenokot.core.model.ErrorCode

/** Причина, по которой задачу надо остановить на ближайшей безопасной границе. */
sealed interface StopRequest {
    /** Отмена пользователем. */
    data object Cancel : StopRequest

    /** Прерывание с возможностью продолжить, например по таймауту службы. */
    data class Interrupt(val code: ErrorCode) : StopRequest
}

/** Сигнал остановки на границе стадии, среза или окна. */
class JobStopped(val request: StopRequest) : Exception()

/** Управление одной задачей. Флаг читается только на безопасных границах. */
class JobControl {
    private val stop = MutableStateFlow<StopRequest?>(null)

    /** Просит остановить задачу. Первая причина остаётся в силе. */
    fun request(request: StopRequest) {
        stop.compareAndSet(null, request)
    }

    /** Бросает [JobStopped], если задачу просили остановить. */
    fun check() {
        stop.value?.let { throw JobStopped(it) }
    }

    /**
     * Выполняет прерываемый участок, например декодирование. Нативные вызовы так не прервать,
     * поэтому для них граница наступает только после возврата из вызова.
     */
    suspend fun <T> stoppable(block: suspend () -> T): T = coroutineScope {
        val body = async { block() }
        val watcher = launch {
            stop.first { it != null }
            body.cancel()
        }
        try {
            body.await()
        } catch (cancelled: CancellationException) {
            check()
            throw cancelled
        } finally {
            watcher.cancel()
        }
    }
}
