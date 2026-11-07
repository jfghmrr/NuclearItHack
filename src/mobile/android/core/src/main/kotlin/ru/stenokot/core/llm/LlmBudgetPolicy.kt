package ru.stenokot.core.llm

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import ru.stenokot.core.model.LlmBudget
import ru.stenokot.core.model.LlmStatus

/** Скорость модели на устройстве в токенах в секунду. */
data class LlmSpeed(val prefillTps: Double, val decodeTps: Double)

/** Расчёт покрытия записи при заданной скорости. */
data class WindowPlan(
    val budgetMs: Long,
    val windowTokens: Int,
    val windowsTotal: Int,
    val windowsFit: Int,
    val usedMs: Long,
    val coverage: Double,
)

/** Бюджет времени языковой модели. Она не должна заметно ухудшать общую скорость обработки. */
object LlmBudgetPolicy {
    /** Нижняя граница бюджета. */
    const val MIN_BUDGET_MS = 15_000L

    /** Верхняя граница бюджета. */
    const val MAX_BUDGET_MS = 90_000L

    /** Предел генерации для первого окна. Копия модели и её загрузка сюда не входят. */
    const val FIRST_WINDOW_LIMIT_MS = 45_000L

    /** Предел загрузки модели. На занятом устройстве чтение файла модели занимает минуты. */
    const val LOAD_LIMIT_MS = 300_000L

    private const val WATCHDOG_MARGIN_MS = 60_000L
    private const val AUDIO_SHARE = 0.10
    private const val TARGET_RTF = 0.95
    private const val LOAD_MS = 8_000L
    private const val SYSTEM_TOKENS = 250
    private const val OUTPUT_TOKENS = 20
    private const val SPEECH_TOKENS_PER_MINUTE = 350.0

    /** Вычисляет `T_llm = max(15 с, min(0.10 T, 90 с, 0.95 T - t_core))`. */
    fun budgetMs(audioMs: Long, coreMs: Long): Long {
        val audioShare = AUDIO_SHARE * audioMs
        val headroom = TARGET_RTF * audioMs - coreMs
        val limited = min(min(audioShare, MAX_BUDGET_MS.toDouble()), headroom)
        return max(MIN_BUDGET_MS.toDouble(), limited).roundToLong()
    }

    /** Собирает ограничения запуска. Поле deadlineMs здесь означает длительность, а не момент времени. */
    fun budget(audioMs: Long, coreMs: Long, sampler: LlmSampler = LlmSampler()): LlmBudget =
        LlmBudget(sampler.contextTokens, LlmWindows.maxTokens(audioMs), budgetMs(audioMs, coreMs))

    /** Сколько можно генерировать текущее окно. Первое окно получает попытку при любом остатке бюджета. */
    fun windowLimitMs(windowsDone: Int, elapsedMs: Long, budgetMs: Long): Long =
        if (windowsDone == 0) FIRST_WINDOW_LIMIT_MS else max(0L, budgetMs - elapsedMs)

    /**
     * Сколько основной процесс ждёт генерацию от конца загрузки модели. Первое окно получает
     * попытку при любом бюджете, поэтому короткий бюджет не должен обрывать его раньше времени.
     */
    fun generationDeadlineMs(budgetMs: Long): Long = max(budgetMs, FIRST_WINDOW_LIMIT_MS) + WATCHDOG_MARGIN_MS

    /** Успеет ли следующее окно, если оно займёт примерно [expectedWindowMs]. */
    fun mayStartWindow(windowsDone: Int, elapsedMs: Long, budgetMs: Long, expectedWindowMs: Long): Boolean =
        windowsDone == 0 || elapsedMs + expectedWindowMs <= budgetMs

    /**
     * Размер следующего окна под остаток бюджета. Значение null означает, что даже наименьшее окно
     * не успеет, и пора останавливаться.
     */
    fun nextWindowTokens(remainingMs: Long, speed: LlmSpeed, maxTokens: Int, outputTokens: Int = OUTPUT_TOKENS): Int? {
        val prefillSeconds = remainingMs / 1000.0 - outputTokens / speed.decodeTps
        val fit = floor(prefillSeconds * speed.prefillTps).toInt()
        return if (fit < LlmWindows.MIN_WINDOW_TOKENS) null else min(fit, maxTokens)
    }

    /** Итог стадии по числу обработанных окон. Ни одного окна значит, что бюджет исчерпан первым окном. */
    fun outcome(windowsDone: Int, windowsTotal: Int): LlmStatus =
        when {
            windowsDone <= 0 -> LlmStatus.SKIPPED_BY_BUDGET
            windowsDone >= windowsTotal -> LlmStatus.APPLIED
            else -> LlmStatus.PARTIAL
        }

    /** Считает, сколько окон поместится в бюджет при скорости [speed]. */
    fun plan(audioMs: Long, speed: LlmSpeed, coreMs: Long = 0, loadMs: Long = LOAD_MS): WindowPlan {
        val budget = budgetMs(audioMs, coreMs)
        val windowTokens = LlmWindows.maxTokens(audioMs)
        val tokens = audioMs / 60_000.0 * SPEECH_TOKENS_PER_MINUTE
        val total = max(1, roundHalfEven(tokens / windowTokens))
        val perWindow = windowTokens / speed.prefillTps + OUTPUT_TOKENS / speed.decodeTps
        val startup = loadMs / 1000.0 + SYSTEM_TOKENS / speed.prefillTps
        val left = budget / 1000.0 - startup
        val fit = max(0, min(total, floor(left / perWindow).toInt()))
        return WindowPlan(
            budgetMs = budget,
            windowTokens = windowTokens,
            windowsTotal = total,
            windowsFit = fit,
            usedMs = ((startup + fit * perWindow) * 1000).roundToLong(),
            coverage = fit * windowTokens / tokens,
        )
    }

    private fun roundHalfEven(value: Double): Int = Math.rint(value).roundToInt()
}
