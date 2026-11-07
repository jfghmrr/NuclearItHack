package ru.stenokot.app.pipeline

import ru.stenokot.app.data.PreflightRecord
import ru.stenokot.core.metrics.DeviceSpeed
import ru.stenokot.core.metrics.EtaBasis
import ru.stenokot.core.metrics.EtaEstimator

/** Чистые правила предварительной проверки: сколько места нужно и сколько ждать. */
object PreflightRules {
    private const val PCM_BYTES_PER_SECOND = 32_000L
    private const val JOURNAL_BYTES_PER_SECOND = 14_000L
    private const val RESERVE_BYTES = 64L * 1024 * 1024

    /** Запас, который нужен задаче: копия файла, WAV, журналы и резерв. */
    fun requiredBytes(sizeBytes: Long, durationMs: Long?): Long {
        val seconds = (durationMs ?: 0L) / 1000
        return sizeBytes + seconds * (PCM_BYTES_PER_SECOND + JOURNAL_BYTES_PER_SECOND) + RESERVE_BYTES
    }

    /** Сколько нужно освободить. Ноль значит, что места хватает. */
    fun shortfallBytes(requiredBytes: Long, freeBytes: Long): Long =
        (requiredBytes - freeBytes).coerceAtLeast(0)

    /**
     * Собирает сведения для показа. Оценку считает ядро: по замерам устройства или широким
     * диапазоном с пометкой. Без длительности оценка скрыта, потому что считать не от чего.
     */
    fun record(
        fileName: String,
        sizeBytes: Long,
        durationMs: Long?,
        mime: String?,
        freeBytes: Long,
        speed: DeviceSpeed?,
    ): PreflightRecord {
        val estimate = EtaEstimator(speed).preflight(durationMs)
        return PreflightRecord(
            fileName = fileName,
            sizeBytes = sizeBytes,
            durationMs = durationMs,
            mime = mime,
            freeBytes = freeBytes,
            requiredBytes = requiredBytes(sizeBytes, durationMs),
            estimateMinMs = estimate?.minMs,
            estimateMaxMs = estimate?.maxMs,
            estimateMeasured = estimate?.basis == EtaBasis.MEASURED,
        )
    }

    /** Подробность отказа: оба числа для диагностики, сколько освободить, считает интерфейс. */
    fun storageDetail(record: PreflightRecord): String =
        "shortfall=${shortfallBytes(record.requiredBytes, record.freeBytes)} " +
            "free=${record.freeBytes} required=${record.requiredBytes}"
}
