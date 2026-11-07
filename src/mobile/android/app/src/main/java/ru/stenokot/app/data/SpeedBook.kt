package ru.stenokot.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/** Диапазон скорости обработки. [measured] отличает замер устройства от примерной оценки. */
data class SpeedRange(val minRtf: Double, val maxRtf: Double, val measured: Boolean)

/** Содержимое speed.json. */
@Serializable
private data class SpeedDocument(val rtfCore: List<Double> = emptyList(), val rtfDiarize: List<Double> = emptyList())

/**
 * Замеры скорости этого устройства. Оценка времени опирается только на них, поэтому после первой
 * завершённой записи она становится честной, а не взятой из таблицы.
 */
class SpeedBook(private val file: () -> File) {
    private var document: SpeedDocument? = null

    /** Возвращает диапазон `RTF_core` с запасом или null, если замеров нет. */
    @Synchronized
    fun coreRange(): SpeedRange? = range(load().rtfCore)

    /** Возвращает диапазон скорости диаризации или null, если замеров нет. */
    @Synchronized
    fun diarizeRange(): SpeedRange? = range(load().rtfDiarize)

    /** Добавляет замер завершённой записи. */
    @Synchronized
    fun record(rtfCore: Double, rtfDiarize: Double?) {
        val current = load()
        val updated = SpeedDocument(
            rtfCore = (current.rtfCore + rtfCore).takeLast(KEEP),
            rtfDiarize = (current.rtfDiarize + listOfNotNull(rtfDiarize)).takeLast(KEEP),
        )
        document = updated
        AtomicFiles.replaceText(file(), StoreJson.encodeToString(updated))
    }

    private fun range(values: List<Double>): SpeedRange? {
        if (values.isEmpty()) return null
        return SpeedRange(values.min() * LOWER_MARGIN, values.max() * UPPER_MARGIN, measured = true)
    }

    private fun load(): SpeedDocument = document ?: read().also { document = it }

    private fun read(): SpeedDocument {
        val target = file()
        if (!target.exists()) return SpeedDocument()
        return runCatching { StoreJson.decodeFromString<SpeedDocument>(target.readText()) }
            .getOrDefault(SpeedDocument())
    }

    private companion object {
        const val KEEP = 5
        const val LOWER_MARGIN = 0.9
        const val UPPER_MARGIN = 1.2
    }
}
