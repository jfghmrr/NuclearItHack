package ru.stenokot.app.ui.failure

import androidx.compose.runtime.Immutable
import ru.stenokot.app.ui.components.MascotKind
import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.PreflightInfo
import ru.stenokot.core.model.Stage

/** Причина, которую видит пользователь на экране ошибки. Иллюстрации нет только у сбоев модели и проверки. */
enum class FailureKind(val mascot: MascotKind?) {
    MODEL(null),
    FORMAT(MascotKind.FILE_ERROR),
    CORRUPT(MascotKind.FILE_ERROR),
    READ(MascotKind.FILE_ERROR),
    NO_PERMISSION(MascotKind.FILE_ERROR),
    STORAGE(MascotKind.STORAGE),
    MEMORY(MascotKind.MEMORY),
    PROCESSING(null),
    INTERRUPTED(MascotKind.INTERRUPTED),
}

/** Что делает кнопка экрана ошибки. */
enum class FailureAction { RETRY, IMPORT, DIAGNOSTICS }

/** Главное действие экрана и необязательное запасное. */
@Immutable
data class FailureActions(val primary: FailureAction, val secondary: FailureAction?)

/** Кнопки для причины: главной всегда назначается то действие, что устраняет именно её. */
val FailureKind.actions: FailureActions
    get() = when (this) {
        FailureKind.MODEL -> FailureActions(FailureAction.DIAGNOSTICS, FailureAction.RETRY)
        FailureKind.FORMAT,
        FailureKind.CORRUPT,
        FailureKind.READ,
        FailureKind.NO_PERMISSION,
        -> FailureActions(FailureAction.IMPORT, null)
        FailureKind.STORAGE,
        FailureKind.MEMORY,
        FailureKind.PROCESSING,
        FailureKind.INTERRUPTED,
        -> FailureActions(FailureAction.RETRY, FailureAction.IMPORT)
    }

/** Причина для кода ошибки. Сбои без своего текста показываются как общая ошибка обработки. */
fun failureKind(code: ErrorCode): FailureKind = when (code) {
    ErrorCode.MODEL_LOAD_FAILED -> FailureKind.MODEL
    ErrorCode.DECODE_UNSUPPORTED_FORMAT -> FailureKind.FORMAT
    ErrorCode.DECODE_CORRUPT -> FailureKind.CORRUPT
    ErrorCode.IMPORT_READ_FAILED -> FailureKind.READ
    ErrorCode.IMPORT_NO_PERMISSION -> FailureKind.NO_PERMISSION
    ErrorCode.STORAGE_LOW -> FailureKind.STORAGE
    ErrorCode.LOW_MEMORY -> FailureKind.MEMORY
    ErrorCode.FGS_TIMEOUT -> FailureKind.INTERRUPTED
    else -> FailureKind.PROCESSING
}

/**
 * Причина для состояния задачи или null, если задача не остановлена. Прерванная задача всегда
 * продолжается с чекпойнта, поэтому она не становится общей ошибкой при любом коде.
 */
fun failureKind(state: JobState): FailureKind? = when (state) {
    is JobState.Failed -> failureKind(state.code)
    is JobState.Interrupted ->
        if (state.code == ErrorCode.LOW_MEMORY) FailureKind.MEMORY else FailureKind.INTERRUPTED
    else -> null
}

/** Стадия, на которой задача остановилась. */
fun failureStage(state: JobState): Stage? = when (state) {
    is JobState.Failed -> state.stage
    is JobState.Interrupted -> state.resumeFrom
    else -> null
}

/** Подробность сбоя из конвейера. */
fun failureNote(state: JobState): String? = (state as? JobState.Failed)?.detail

/** Сколько байт надо освободить или null, если предварительная проверка этого не посчитала. */
fun bytesToFree(preflight: PreflightInfo?): Long? {
    val info = preflight ?: return null
    return (info.requiredBytes - info.freeBytes).takeIf { it > 0 }
}
