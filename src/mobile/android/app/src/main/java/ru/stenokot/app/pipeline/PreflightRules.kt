package ru.stenokot.app.pipeline

import ru.stenokot.app.data.PreflightRecord
import ru.stenokot.app.data.SpeedRange
import ru.stenokot.core.model.ErrorCode

/** Чистые правила предварительной проверки: сколько места нужно и сколько ждать. */
object PreflightRules {
    private const val PCM_BYTES_PER_SECOND = 32_000L
    private const val JOURNAL_BYTES_PER_SECOND = 14_000L
    private const val RESERVE_BYTES = 64L * 1024 * 1024

    /**
     * Примерный диапазон `RTF_core` для устройства без своих замеров. Он взят из расчётной оценки
     * спецификации, поэтому в интерфейсе подписывается как примерный.
     */
    val RoughSpeed = SpeedRange(minRtf = 0.35, maxRtf = 0.75, measured = false)

    /** Запас, который нужен задаче: копия файла, WAV, журналы и резерв. */
    fun requiredBytes(sizeBytes: Long, durationMs: Long?): Long {
        val seconds = (durationMs ?: 0L) / 1000
        return sizeBytes + seconds * (PCM_BYTES_PER_SECOND + JOURNAL_BYTES_PER_SECOND) + RESERVE_BYTES
    }

    /** Сколько нужно освободить. Ноль значит, что места хватает. */
    fun shortfallBytes(requiredBytes: Long, freeBytes: Long): Long =
        (requiredBytes - freeBytes).coerceAtLeast(0)

    /**
     * Собирает сведения для показа. Без длительности оценка скрыта: неизвестно, от чего считать.
     * [speed] равен null, если у устройства нет замеров: тогда берётся примерный диапазон.
     */
    fun record(
        fileName: String,
        sizeBytes: Long,
        durationMs: Long?,
        mime: String?,
        freeBytes: Long,
        speed: SpeedRange?,
    ): PreflightRecord {
        val range = speed ?: RoughSpeed
        return PreflightRecord(
            fileName = fileName,
            sizeBytes = sizeBytes,
            durationMs = durationMs,
            mime = mime,
            freeBytes = freeBytes,
            requiredBytes = requiredBytes(sizeBytes, durationMs),
            estimateMinMs = durationMs?.let { (it * range.minRtf).toLong() },
            estimateMaxMs = durationMs?.let { (it * range.maxRtf).toLong() },
            estimateMeasured = durationMs != null && range.measured,
        )
    }

    /** Возвращает отказ, если места не хватает. Подробность содержит оба числа для диагностики. */
    fun storageRejection(record: PreflightRecord): PreflightOutcome.Rejected? {
        val shortfall = shortfallBytes(record.requiredBytes, record.freeBytes)
        if (shortfall == 0L) return null
        return PreflightOutcome.Rejected(
            code = ErrorCode.STORAGE_LOW,
            detail = "shortfall=$shortfall free=${record.freeBytes} required=${record.requiredBytes}",
            record = record,
        )
    }
}
