package ru.stenokot.app.pipeline

import ru.stenokot.app.data.JobPhase
import ru.stenokot.app.data.JobRecord
import ru.stenokot.app.data.LlmRecord
import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.LlmBudget
import ru.stenokot.core.model.LlmResult
import ru.stenokot.core.model.LlmStatus
import kotlin.math.max
import kotlin.math.min

/** Бюджет и повтор языковой модели по разделу 8.7 спецификации. */
object LlmRules {
    /** Размер контекста: KV-кэш 117 МБ, больше на 4 ГБ не закладываем. */
    const val N_CTX: Int = 1024

    /** Ниже этого запаса модель не загружается. */
    const val MIN_MEMORY_BYTES: Long = 650L * 1024 * 1024

    private const val SHORT_RECORDING_MS = 900_000L
    private const val WINDOW_SHORT_TOKENS = 450
    private const val WINDOW_LONG_TOKENS = 300

    /**
     * `T_llm = max(15 с, min(0.10·T, 90 с, 0.95·T - t_core))`. Нижняя граница даёт короткой записи
     * попытку даже тогда, когда RTF_full из-за неё превысит цель: это показывается честно.
     */
    fun deadlineMs(durationMs: Long, coreMs: Long): Long {
        val tenth = durationMs / 10
        val headroom = (durationMs * 95 / 100) - coreMs
        return max(15_000L, min(min(tenth, 90_000L), headroom))
    }

    /** Строит бюджет запуска. Повтор получает тот же бюджет, потому что входные значения те же. */
    fun budget(durationMs: Long, coreMs: Long): LlmBudget = LlmBudget(
        nCtx = N_CTX,
        maxChunkTokens = if (durationMs <= SHORT_RECORDING_MS) WINDOW_SHORT_TOKENS else WINDOW_LONG_TOKENS,
        deadlineMs = deadlineMs(durationMs, coreMs),
    )

    /** Задача имеет право на один повтор при простое. */
    fun retryEligible(job: JobRecord): Boolean =
        job.phase == JobPhase.DONE &&
            !job.noSpeech &&
            !job.llm.retryUsed &&
            (job.llm.status == LlmStatus.DEFERRED || job.llm.status == LlmStatus.SKIPPED_BY_BUDGET)

    /**
     * Сводит результат запуска в запись задачи. Отмена новым импортом всегда превращается в
     * DEFERRED и повтор не расходует. Повтор, доработавший до конца или упёршийся в лимит,
     * расходуется, и статус становится окончательным.
     */
    fun settle(prior: LlmRecord, result: LlmResult, isRetry: Boolean): LlmRecord {
        val preempted = result.status == LlmStatus.DEFERRED ||
            result.status == LlmStatus.CANCELLED_BY_NEW_JOB
        val status = if (preempted) LlmStatus.DEFERRED else result.status
        return prior.copy(
            status = status,
            errorCode = errorCode(result),
            detail = result.detail,
            retryUsed = prior.retryUsed || (isRetry && !preempted),
            protocolPath = result.protocolPath ?: prior.protocolPath,
        )
    }

    /** Отмена пользователем окончательна: протокол он сам отказался ждать. */
    fun cancelledByUser(prior: LlmRecord, protocolPath: String?): LlmRecord = prior.copy(
        status = LlmStatus.CANCELLED_BY_NEW_JOB,
        errorCode = null,
        detail = null,
        retryUsed = true,
        protocolPath = protocolPath ?: prior.protocolPath,
    )

    private fun errorCode(result: LlmResult): ErrorCode? {
        val named = result.detail?.let { detail ->
            ErrorCode.entries.firstOrNull { detail.startsWith(it.name) }
        }
        return named ?: when (result.status) {
            LlmStatus.SKIPPED_LOW_MEMORY -> ErrorCode.LLM_LOW_MEMORY
            LlmStatus.FAILED -> ErrorCode.LLM_DIED_NATIVE
            else -> null
        }
    }
}
