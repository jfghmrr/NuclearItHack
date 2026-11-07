package ru.stenokot.app.ui.format

import ru.stenokot.app.domain.UserStage
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.model.PauseReason
import ru.stenokot.core.model.Stage

/** Статус задачи в очереди и истории. Значения совпадают со списком статусов продукта. */
enum class JobBadge(val label: String) {
    READY("Готово"),
    PROCESSING("Обрабатывается"),
    WAITING("Ожидает"),
    PAUSED("Приостановлено"),
    ERROR("Ошибка"),
    SAVED("Результат сохранён"),
    CANCELLED("Отменено"),
}

/** Статус задачи для строки списка. Отмена не является ошибкой и не красится красным. */
fun jobBadge(state: JobState): JobBadge = when (state) {
    JobState.Queued -> JobBadge.WAITING
    is JobState.Running -> if (state.stage == Stage.LLM) JobBadge.SAVED else JobBadge.PROCESSING
    is JobState.Paused ->
        if (state.reason == PauseReason.QUEUED_BEHIND_OTHER_JOB) JobBadge.WAITING else JobBadge.PAUSED
    is JobState.Interrupted -> JobBadge.PAUSED
    is JobState.TranscriptReady, JobState.WaitingModel -> JobBadge.SAVED
    is JobState.Failed -> JobBadge.ERROR
    JobState.Cancelled -> JobBadge.CANCELLED
    is JobState.Done -> JobBadge.READY
}

/** Строка статуса протокола для истории или null, если протокола ещё нечем описать. */
fun protocolLabel(state: JobState): String? = when {
    state is JobState.Done && state.warnings.contains("AUDIO_EMPTY") -> "Речи не найдено"
    state is JobState.Done -> when (state.llm) {
        LlmStatus.APPLIED -> "Протокол готов"
        LlmStatus.PARTIAL -> "Протокол частичный"
        LlmStatus.DEFERRED, LlmStatus.CANCELLED_BY_NEW_JOB -> "Протокол отложен"
        LlmStatus.SKIPPED_BY_BUDGET, LlmStatus.SKIPPED_LOW_MEMORY -> "Протокол пропущен"
        LlmStatus.FAILED -> "Протокол не создан"
    }
    state is JobState.TranscriptReady || state == JobState.WaitingModel -> "Протокол формируется"
    state is JobState.Running && state.stage == Stage.LLM -> "Протокол формируется"
    else -> null
}

/** Название этапа, который видит пользователь. */
fun userStageLabel(stage: UserStage): String = when (stage) {
    UserStage.PREPARE_AUDIO -> "Подготовка аудио"
    UserStage.RECOGNIZE -> "Распознавание речи"
    UserStage.DIARIZE -> "Определение говорящих"
    UserStage.SAVE_TRANSCRIPT -> "Сохранение стенограммы"
    UserStage.BUILD_PROTOCOL -> "Формирование протокола"
}

/** Что приложение делает на этапе сейчас: подпись активного этапа в карточках. */
fun userStageAction(stage: UserStage): String = when (stage) {
    UserStage.PREPARE_AUDIO -> "Готовим аудио"
    UserStage.RECOGNIZE -> "Распознаём речь"
    UserStage.DIARIZE -> "Определяем говорящих"
    UserStage.SAVE_TRANSCRIPT -> "Сохраняем стенограмму"
    UserStage.BUILD_PROTOCOL -> "Формируем протокол"
}

/** Этап пользователя, в который входит стадия конвейера [stage]. */
fun userStageOf(stage: Stage): UserStage = UserStage.entries.first { stage in it.stages }

/** Счётчик этапа в виде «19 из 45 фрагментов». Единица определяется стадией конвейера. */
fun formatStageCounter(stage: Stage, done: Int, total: Int): String = when (stage) {
    Stage.IMPORT -> "${formatDecimal(done / 1024.0)} из ${formatDecimal(total / 1024.0)}$NBSP" + "МиБ"
    Stage.DECODE, Stage.VAD -> "${formatTimecode(done * 1000L)} из ${formatTimecode(total * 1000L)}"
    Stage.LANG_ID, Stage.ASR ->
        "$done из ${formatThousands(total.toLong())}$NBSP${genitiveAfterOf(total.toLong(), "фрагмента", "фрагментов")}"
    Stage.DIARIZE -> "$done из $total$NBSP${genitiveAfterOf(total.toLong(), "окна", "окон")}"
    Stage.MERGE, Stage.VALIDATE, Stage.LLM -> "$done из $total"
}
