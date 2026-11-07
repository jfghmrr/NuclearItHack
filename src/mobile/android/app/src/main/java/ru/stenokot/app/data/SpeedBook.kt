package ru.stenokot.app.data

import kotlinx.serialization.Serializable
import ru.stenokot.core.metrics.DeviceSpeed
import ru.stenokot.core.model.Stage
import ru.stenokot.core.storage.ServiceJson
import java.io.File

/** Содержимое speed.json. */
@Serializable
private data class SpeedDocument(val stageRtf: Map<Stage, Double> = emptyMap())

/**
 * Скорость этого устройства по последней подходящей записи. Оценка времени опирается на неё, поэтому
 * после первой завершённой записи она становится замером, а не прикидкой.
 */
class SpeedBook(private val file: () -> File) {
    private var loaded = false
    private var speed: DeviceSpeed? = null

    /** Возвращает скорость устройства или null, если замеров ещё нет. */
    @Synchronized
    fun current(): DeviceSpeed? {
        if (!loaded) {
            speed = read()
            loaded = true
        }
        return speed
    }

    /** Запоминает замер. Слишком короткая запись замером не считается и не записывается. */
    @Synchronized
    fun record(stageMs: Map<Stage, Long>, audioMs: Long) {
        val measured = DeviceSpeed.fromRun(stageMs, audioMs) ?: return
        speed = measured
        loaded = true
        val text = ServiceJson.encodeToString(SpeedDocument.serializer(), SpeedDocument(measured.stageRtf))
        AtomicFiles.replaceText(file(), text)
    }

    private fun read(): DeviceSpeed? {
        val target = file()
        if (!target.exists()) return null
        val document = runCatching {
            ServiceJson.decodeFromString(SpeedDocument.serializer(), target.readText())
        }.getOrNull() ?: return null
        return DeviceSpeed(document.stageRtf).takeIf { document.stageRtf.isNotEmpty() }
    }
}
