package ru.stenokot.app.llm

import android.os.Bundle
import ru.stenokot.core.model.LlmResult
import ru.stenokot.core.model.LlmStatus

/** Имя процесса службы. Совпадает с android:process в манифесте. */
internal const val LLM_PROCESS_SUFFIX = ":llm"

/** Коды сообщений между основным процессом и :llm. Транскрипт и протокол передаются только файлами задачи. */
internal object LlmMessage {
    const val START = 1
    const val CANCEL = 2
    const val STARTED = 3
    const val PROGRESS = 4
    const val FINISHED = 5
}

/** Запуск стадии в процессе :llm. Предел стадии [budgetMs] считается от конца загрузки модели. */
internal data class LlmRequest(
    val jobDir: String,
    val modelPath: String,
    val contextTokens: Int,
    val maxChunkTokens: Int,
    val budgetMs: Long,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_JOB_DIR, jobDir)
        putString(KEY_MODEL_PATH, modelPath)
        putInt(KEY_CONTEXT, contextTokens)
        putInt(KEY_CHUNK, maxChunkTokens)
        putLong(KEY_BUDGET, budgetMs)
    }

    companion object {
        private const val KEY_JOB_DIR = "job_dir"
        private const val KEY_MODEL_PATH = "model_path"
        private const val KEY_CONTEXT = "context"
        private const val KEY_CHUNK = "chunk"
        private const val KEY_BUDGET = "budget"

        fun from(bundle: Bundle): LlmRequest = LlmRequest(
            jobDir = requireNotNull(bundle.getString(KEY_JOB_DIR)),
            modelPath = requireNotNull(bundle.getString(KEY_MODEL_PATH)),
            contextTokens = bundle.getInt(KEY_CONTEXT),
            maxChunkTokens = bundle.getInt(KEY_CHUNK),
            budgetMs = bundle.getLong(KEY_BUDGET),
        )
    }
}

/** Прогресс стадии. Содержит только числа и служебные пометки, текста реплик здесь нет. */
internal object LlmProgressBundle {
    private const val KEY_FRACTION = "fraction"
    private const val KEY_NOTE = "note"

    fun of(fraction: Float, note: String?): Bundle = Bundle().apply {
        putFloat(KEY_FRACTION, fraction)
        putString(KEY_NOTE, note)
    }

    fun fraction(bundle: Bundle): Float = bundle.getFloat(KEY_FRACTION)

    fun note(bundle: Bundle): String? = bundle.getString(KEY_NOTE)
}

/** Итог стадии. Сам протокол уже лежит в каталоге задачи. */
internal object LlmOutcomeBundle {
    private const val KEY_STATUS = "status"
    private const val KEY_PATH = "path"
    private const val KEY_DETAIL = "detail"

    fun of(result: LlmResult): Bundle = Bundle().apply {
        putString(KEY_STATUS, result.status.name)
        putString(KEY_PATH, result.protocolPath)
        putString(KEY_DETAIL, result.detail)
    }

    fun result(bundle: Bundle): LlmResult = LlmResult(
        status = LlmStatus.valueOf(requireNotNull(bundle.getString(KEY_STATUS))),
        protocolPath = bundle.getString(KEY_PATH),
        detail = bundle.getString(KEY_DETAIL),
    )
}
