package ru.stenokot.core.engine

import ru.stenokot.core.model.ErrorCode

/** Сбой движка с кодом, который конвейер переводит в состояние задачи. */
class EngineFailure(
    val code: ErrorCode,
    detail: String? = null,
    cause: Throwable? = null,
) : Exception(detail, cause)
