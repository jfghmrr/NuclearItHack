package ru.stenokot.app.audio

import ru.stenokot.core.model.ErrorCode

/** Сбой стадии обработки. Конвейер переводит [code] в понятное сообщение, [message] идёт в диагностику. */
class EngineException(
    val code: ErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
