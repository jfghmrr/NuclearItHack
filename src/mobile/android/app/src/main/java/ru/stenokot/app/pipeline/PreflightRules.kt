package ru.stenokot.app.pipeline

import ru.stenokot.app.data.PreflightRecord
import ru.stenokot.core.metrics.DeviceSpeed
import ru.stenokot.core.metrics.EtaBasis
import ru.stenokot.core.metrics.EtaEstimator

/** Чистые правила предварительной проверки: сколько места нужно и сколько ждать. */
object PreflightRules {
    private const val PCM_BYTES_PER_SECOND = 32_000L
    private const val JOURNAL_BYTES_PER_SECOND = 2_000L
    private const val RESERVE_BYTES = 64L * 1024 * 1024

    /**
     * Больше этого размера файл не копируется: он читается на месте, а права сохраняются. Запись до
     * четверти гигабайта это часы речи, её копия дешева и защищает от потери временного доступа.
     */
    const val COPY_LIMIT_BYTES = 256L * 1024 * 1024

    /**
     * Запас, который нужен задаче: [copyBytes] под ещё не сделанную копию файла, WAV, журналы и
     * резерв. Файл, читаемый на месте, и уже готовая копия новых байт не требуют.
     */
    fun requiredBytes(copyBytes: Long, durationMs: Long?): Long {
        val seconds = (durationMs ?: 0L) / 1000
        return copyBytes + seconds * (PCM_BYTES_PER_SECOND + JOURNAL_BYTES_PER_SECOND) + RESERVE_BYTES
    }

    /**
     * Читать ли файл на месте. Большой файл копируется только тогда, когда постоянного доступа нет, а
     * копия помещается: иначе временный доступ остаётся единственной возможностью обработать его.
     */
    fun readsInPlace(sizeBytes: Long, lasting: Boolean, freeBytes: Long): Boolean =
        sizeBytes > COPY_LIMIT_BYTES && (lasting || sizeBytes + RESERVE_BYTES > freeBytes)

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
        copyBytes: Long = sizeBytes,
    ): PreflightRecord {
        val estimate = EtaEstimator(speed).preflight(durationMs)
        return PreflightRecord(
            fileName = fileName,
            sizeBytes = sizeBytes,
            durationMs = durationMs,
            mime = mime,
            freeBytes = freeBytes,
            requiredBytes = requiredBytes(copyBytes, durationMs),
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
