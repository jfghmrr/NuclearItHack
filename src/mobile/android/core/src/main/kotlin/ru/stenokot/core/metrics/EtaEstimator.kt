package ru.stenokot.core.metrics

import kotlin.math.roundToLong
import ru.stenokot.core.model.Stage

/** На чём основана оценка: на замерах этого устройства или на общих прикидках. */
enum class EtaBasis { MEASURED, ROUGH }

/** Диапазон времени в миллисекундах. */
data class EtaRange(val minMs: Long, val maxMs: Long, val basis: EtaBasis)

/** Скорость стадий на этом устройстве: секунд работы на секунду записи. */
data class DeviceSpeed(val stageRtf: Map<Stage, Double>) {
    companion object {
        private const val MIN_AUDIO_MS = 10_000L

        /** Получает скорость из завершённого прогона. На слишком короткой записи замер не показателен. */
        fun fromRun(stageMs: Map<Stage, Long>, audioMs: Long): DeviceSpeed? {
            if (audioMs < MIN_AUDIO_MS) return null
            val rates = stageMs.filterKeys { it != Stage.LLM }.mapValues { it.value.toDouble() / audioMs }
            return if (Stage.ASR in rates) DeviceSpeed(rates) else null
        }
    }
}

/** Положение работающей задачи. Доля стадии задаётся только там, где она реально известна. */
data class EtaProgress(
    val audioMs: Long,
    val stage: Stage,
    val stageFraction: Float? = null,
    val stageElapsedMs: Long = 0,
    val finishedStageMs: Map<Stage, Long> = emptyMap(),
    val llmBudgetMs: Long? = null,
)

/**
 * Честная оценка времени. Опирается на скорость этого устройства и ход текущей задачи. Пока нет ни
 * того ни другого, оставшееся время неизвестно, и пользователь его не видит. Предварительная оценка
 * в этом случае даётся как широкий диапазон с пометкой приблизительной. Время протокола в оценку
 * файла не входит: стенограмма появляется раньше, а стадия языковой модели ограничена бюджетом.
 */
class EtaEstimator(private val speed: DeviceSpeed? = null) {

    /** Оценка до запуска. Значение null означает, что длительность записи неизвестна. */
    fun preflight(durationMs: Long?): EtaRange? {
        if (durationMs == null || durationMs <= 0) return null
        val known = speed?.stageRtf
        if (known != null && Stage.ASR in known) {
            val total = HEAVY_STAGES.sumOf { (known[it] ?: ROUGH_RTF.getValue(it)) * durationMs }
            return range(total, EtaBasis.MEASURED)
        }
        return EtaRange(
            ((ROUGH_CORE_RTF_MIN * durationMs) + ROUGH_START_MIN_MS).roundToLong(),
            ((ROUGH_CORE_RTF_MAX * durationMs) + ROUGH_START_MAX_MS).roundToLong(),
            EtaBasis.ROUGH,
        )
    }

    /** Оценка до конца файла по ходу задачи. Значение null означает, что подтвердить её нечем. */
    fun remaining(progress: EtaProgress): EtaRange? {
        if (progress.audioMs <= 0) return null
        if (progress.stage == Stage.LLM) return remainingLlm(progress)
        val known = speed?.stageRtf.orEmpty()
        val asrRate = observedAsrRate(progress) ?: known[Stage.ASR]
        val scale = asrRate?.let { it / ROUGH_RTF.getValue(Stage.ASR) }
        val audioMs = progress.audioMs.toDouble()
        var rough = false

        fun stageMs(stage: Stage): Double? {
            known[stage]?.let { return it * audioMs }
            val base = ROUGH_RTF[stage] ?: return 0.0
            val factor = scale ?: return null
            rough = true
            return base * factor * audioMs
        }

        val done = (progress.stageFraction ?: 0f).toDouble()
        var total = observedRemainingMs(progress) ?: ((stageMs(progress.stage) ?: return null) * (1.0 - done))
        for (stage in HEAVY_STAGES) {
            if (stage > progress.stage) total += stageMs(stage) ?: return null
        }
        return range(total, if (rough) EtaBasis.ROUGH else EtaBasis.MEASURED)
    }

    private fun remainingLlm(progress: EtaProgress): EtaRange? {
        val budget = progress.llmBudgetMs ?: return null
        return EtaRange(0, (budget - progress.stageElapsedMs).coerceAtLeast(0), EtaBasis.MEASURED)
    }

    private fun observedRemainingMs(progress: EtaProgress): Double? {
        val fraction = progress.stageFraction ?: return null
        if (fraction < MIN_FRACTION || fraction >= 1f || progress.stageElapsedMs < MIN_OBSERVED_MS) return null
        return progress.stageElapsedMs * (1.0 - fraction) / fraction
    }

    private fun observedAsrRate(progress: EtaProgress): Double? {
        progress.finishedStageMs[Stage.ASR]?.let { return it.toDouble() / progress.audioMs }
        if (progress.stage != Stage.ASR) return null
        val fraction = progress.stageFraction ?: return null
        if (fraction < MIN_FRACTION || progress.stageElapsedMs < MIN_OBSERVED_MS) return null
        return progress.stageElapsedMs / (fraction * progress.audioMs.toDouble())
    }

    private fun range(totalMs: Double, basis: EtaBasis): EtaRange {
        val low = if (basis == EtaBasis.MEASURED) MEASURED_LOW else ROUGH_LOW
        val high = if (basis == EtaBasis.MEASURED) MEASURED_HIGH else ROUGH_HIGH
        return EtaRange((totalMs * low).roundToLong(), (totalMs * high).roundToLong(), basis)
    }

    private companion object {
        const val MIN_FRACTION = 0.03f
        const val MIN_OBSERVED_MS = 3_000L
        const val MEASURED_LOW = 0.85
        const val MEASURED_HIGH = 1.25
        const val ROUGH_LOW = 0.7
        const val ROUGH_HIGH = 1.6
        const val ROUGH_CORE_RTF_MIN = 0.35
        const val ROUGH_CORE_RTF_MAX = 0.72
        const val ROUGH_START_MIN_MS = 5_000.0
        const val ROUGH_START_MAX_MS = 25_000.0

        val HEAVY_STAGES = listOf(Stage.DECODE, Stage.VAD, Stage.LANG_ID, Stage.ASR, Stage.DIARIZE)

        /** Прикидка по таблицам спецификации, ничего не измерено. Заменяется замерами устройства. */
        val ROUGH_RTF = mapOf(
            Stage.DECODE to 0.03,
            Stage.VAD to 0.03,
            Stage.LANG_ID to 0.02,
            Stage.ASR to 0.12,
            Stage.DIARIZE to 0.30,
        )
    }
}
