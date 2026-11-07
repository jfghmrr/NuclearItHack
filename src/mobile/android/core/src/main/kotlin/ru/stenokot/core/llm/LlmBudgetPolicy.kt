package ru.stenokot.core.llm

import kotlin.math.max
import kotlin.math.min
import ru.stenokot.core.model.LlmBudget
import ru.stenokot.core.model.LlmStatus

/**
 * Пределы стадии языковой модели. Замер скорости заканчивается на файле экспорта, поэтому окна идут
 * до конца, пока приложение живо и нет новой работы. Новый импорт останавливает модель сразу.
 * Пределы защищают только от очень длинных записей и зависшей генерации.
 */
object LlmBudgetPolicy {
    /** Больше окон запись не получает: длинная запись покрывается равномерной выборкой. */
    const val MAX_WINDOWS = 60

    /** Предел всей стадии от конца загрузки модели. */
    const val STAGE_LIMIT_MS = 45 * 60_000L

    /** Предел генерации одного окна. */
    const val WINDOW_LIMIT_MS = 180_000L

    /** Предел загрузки модели. На занятом устройстве чтение файла модели занимает минуты. */
    const val LOAD_LIMIT_MS = 300_000L

    /** Сколько основной процесс ждёт следующее окно, прежде чем считать модель зависшей. */
    const val WINDOW_WATCHDOG_MS = WINDOW_LIMIT_MS + 60_000L

    /** Собирает ограничения запуска. Поле deadlineMs здесь означает длительность, а не момент времени. */
    fun budget(sampler: LlmSampler = LlmSampler()): LlmBudget =
        LlmBudget(sampler.contextTokens, LlmWindows.WINDOW_TOKENS, STAGE_LIMIT_MS)

    /** Сколько можно генерировать текущее окно. Ноль значит, что стадия исчерпала предел. */
    fun windowLimitMs(elapsedMs: Long, budgetMs: Long): Long = min(WINDOW_LIMIT_MS, max(0L, budgetMs - elapsedMs))

    /**
     * Итог стадии по числу обработанных окон из всех окон записи. Ни одного окна значит, что предел
     * исчерпан первым окном. Выборка из длинной записи честно даёт частичный итог.
     */
    fun outcome(windowsDone: Int, windowsTotal: Int): LlmStatus =
        when {
            windowsDone <= 0 -> LlmStatus.SKIPPED_BY_BUDGET
            windowsDone >= windowsTotal -> LlmStatus.APPLIED
            else -> LlmStatus.PARTIAL
        }
}
