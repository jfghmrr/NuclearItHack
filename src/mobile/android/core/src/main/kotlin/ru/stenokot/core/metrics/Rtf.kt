package ru.stenokot.core.metrics

import ru.stenokot.core.model.Stage

/** Два значения скорости. Просто RTF не публикуется никогда. */
data class RtfValues(val core: Double?, val full: Double?)

/**
 * Считает скорость обработки. `RTF_core` включает всё до записи JSON, `RTF_full` добавляет языковую
 * модель. Отрисовка интерфейса не входит ни в одно значение.
 */
object RtfCalculator {
    /**
     * Считает оба значения. [overheadMs] покрывает запуск процесса, загрузку моделей и запись JSON,
     * если они не вошли во времена стадий. Без записи о стадии LLM значение `RTF_full` неизвестно.
     */
    fun compute(audioMs: Long, stageMs: Map<Stage, Long>, overheadMs: Long = 0): RtfValues {
        if (audioMs <= 0) return RtfValues(null, null)
        val coreMs = overheadMs + stageMs.filterKeys { it != Stage.LLM }.values.sum()
        val llmMs = stageMs[Stage.LLM]
        return RtfValues(
            core = coreMs.toDouble() / audioMs,
            full = llmMs?.let { (coreMs + it).toDouble() / audioMs },
        )
    }

    /** Скорость одной стадии. */
    fun stage(tookMs: Long, audioMs: Long): Double? = if (audioMs > 0) tookMs.toDouble() / audioMs else null
}
