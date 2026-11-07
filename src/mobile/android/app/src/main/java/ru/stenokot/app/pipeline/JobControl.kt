package ru.stenokot.app.pipeline

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

    /** Задачу просили остановить. Нужно движкам, которые сами проверяют границы окон. */
    fun isStopRequested(): Boolean = stop.value != null

    /** Бросает [JobStopped], если задачу просили остановить. */
    fun check() {
        stop.value?.let { throw JobStopped(it) }
    }

    /**
     * Выполняет прерываемый участок, например декодирование. Нативные вызовы так не прервать,
     * поэтому для них граница наступает только после возврата из вызова.
     *
     * После просьбы об остановке любой сбой участка считается остановкой: движок может поймать
     * отмену и выдать её за свою ошибку, ведь CancellationException это IllegalStateException.
     * Проверка стоит снаружи области: упавший дочерний async подменил бы исключение изнутри неё.
     */
    suspend fun <T> stoppable(block: suspend () -> T): T = try {
        coroutineScope {
            val body = async { block() }
            val watcher = launch {
                stop.first { it != null }
                body.cancel()
            }
            try {
                body.await()
            } finally {
                watcher.cancel()
            }
        }
    } catch (failure: Exception) {
        check()
        throw failure
    }
}
