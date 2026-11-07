package ru.stenokot.app.ui.format

import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.model.PauseReason
import ru.stenokot.core.model.Stage

/** Машинный код без активной задачи. */
const val STATUS_IDLE = "IDLE"

/** Код задачи для строки `txt_status`. Значения являются договором с автоматикой и не переводятся. */
fun statusCode(state: JobState): String = when (state) {
    JobState.Queued -> "QUEUED"
    is JobState.Running -> "RUNNING:${state.stage.name}"
    is JobState.Paused ->
        if (state.reason == PauseReason.QUEUED_BEHIND_OTHER_JOB) "QUEUED" else "RUNNING:${state.stage.name}"
    is JobState.TranscriptReady -> "TRANSCRIPT_READY"
    JobState.WaitingModel -> "WAITING_MODEL"
    is JobState.Interrupted -> "FAILED:${state.code?.name ?: "INTERRUPTED"}"
    is JobState.Failed -> "FAILED:${state.code.name}"
    JobState.Cancelled -> "CANCELLED"
    is JobState.Done -> if (state.llm == LlmStatus.FAILED) "DONE_LLM_FAILED" else "DONE"
}

/** Название стадии конвейера для пользователя. */
fun stageLabel(stage: Stage): String = when (stage) {
    Stage.IMPORT -> "Копирование файла"
    Stage.DECODE -> "Декодирование аудио"
    Stage.VAD -> "Поиск речи"
    Stage.LANG_ID -> "Определение языка"
    Stage.ASR -> "Распознавание речи"
    Stage.DIARIZE -> "Определение говорящих"
    Stage.MERGE -> "Сборка реплик"
    Stage.VALIDATE -> "Проверка и сохранение JSON"
    Stage.LLM -> "Формирование протокола"
}

/** Озвучивание кода для TalkBack. Видимый текст строки остаётся кодом. */
fun statusSpoken(code: String): String = when {
    code == STATUS_IDLE -> "Состояние: ожидание файла"
    code == "QUEUED" -> "Состояние: файл в очереди"
    code.startsWith("RUNNING:") -> {
        val stage = Stage.entries.firstOrNull { it.name == code.substringAfter(':') }
        "Состояние: идёт этап ${stage?.let(::stageLabel) ?: code.substringAfter(':')}"
    }
    code == "TRANSCRIPT_READY" -> "Состояние: стенограмма сохранена"
    code == "WAITING_MODEL" -> "Состояние: ожидание языковой модели"
    code == "DONE" -> "Состояние: готово"
    code == "DONE_LLM_FAILED" -> "Состояние: стенограмма готова, протокол не создан"
    code.startsWith("FAILED:") -> "Состояние: ошибка"
    code == "CANCELLED" -> "Состояние: обработка отменена"
    else -> code
}
