package ru.stenokot.app.pipeline

import ru.stenokot.app.data.JobRecord
import ru.stenokot.core.audio.AudioInput
import ru.stenokot.core.model.ErrorCode
import java.io.File

/**
 * Сбой стадии с кодом, который конвейер переводит в состояние задачи. Движки могут бросать
 * собственные исключения: их код сообщает [PipelineEngines.errorCode].
 */
class EngineFailure(
    val code: ErrorCode,
    detail: String? = null,
    cause: Throwable? = null,
) : Exception(detail, cause)

/** Показатели устройства. Вынесены из конвейера, чтобы его логику можно было проверять на JVM. */
interface DeviceMonitor {
    /** Доступная память по оценке системы. */
    fun availableMemoryBytes(): Long

    /** Термальный статус в шкале PowerManager: 0 нет, 3 SEVERE, 4 CRITICAL. */
    fun thermalStatus(): Int

    /** Пик памяти процесса VmHWM в килобайтах. */
    fun peakRssKb(): Long?

    /** Причина прошлой смерти процесса, если система её сообщила. */
    fun lastExitCode(): ErrorCode?

    /** Прошлый процесс закрыли принудительно: кнопкой в настройках или командой force-stop. */
    fun stoppedByUser(): Boolean = false
}

/** Сведения о файле, известные до его копирования. Размер равен нулю, если источник его не сообщил. */
data class SourceInfo(val name: String, val sizeBytes: Long, val mime: String?)

/**
 * Файл, который можно читать там, где он лежит. [lasting] означает, что доступ переживёт смерть
 * процесса: это путь или адрес с сохранённым правом, а не временное разрешение.
 */
class InPlaceSource(val input: AudioInput, val lasting: Boolean)

/** Доступ к импортируемому файлу. Сбой доступа сообщается через [EngineFailure]. */
interface SourceGateway {
    /** Узнаёт имя и размер файла и проверяет, что он читается. */
    suspend fun describe(job: JobRecord): SourceInfo

    /** Копирует файл в [target]. [progress] получает скопированные и общие байты. */
    suspend fun copy(job: JobRecord, target: File, progress: (copied: Long, total: Long) -> Unit)

    /** Возвращает файл для чтения на месте или null, если произвольный доступ к нему невозможен. */
    fun inPlace(job: JobRecord): InPlaceSource? = null

    /** Свободное место в хранилище задач. */
    fun freeBytes(): Long

    /** Задаче больше не нужен исходник. Запись с микрофона приложения удаляется, чужие файлы не трогаются. */
    fun release(job: JobRecord) = Unit
}

/**
 * Адрес опубликованного файла. [renamed] означает, что под нужным именем лежит чужой файл, чаще всего
 * от прошлой установки: заменить его без диалога нельзя, система выдала имя вида `name (1).json`.
 */
data class PublishedFile(
    val path: String,
    val uri: String?,
    val fallback: Boolean,
    val renamed: Boolean = false,
)

/** Вид служебного файла в `Download/Stenokot/meta/`. */
enum class MetaKind(val suffix: String) {
    METADATA("metadata"),
    PROTOCOL("protocol"),
}

/** Публикация результатов за пределы каталога задачи. */
interface OutputGateway {
    /** Публикует контрактный файл. Сюда попадают только байты, прошедшие проверку. */
    suspend fun publishTranscript(stem: String, bytes: ByteArray): PublishedFile

    /** Публикует служебный файл. Сбой не влияет на задачу. */
    suspend fun publishMeta(stem: String, kind: MetaKind, source: File)

    /** Удаляет опубликованные файлы задачи. */
    suspend fun removePublished(stem: String, uri: String?)
}

/** Состояние файла языковой модели. */
enum class LlmModelState { READY, PENDING, UNAVAILABLE }

/** Связь конвейера с копированием модели. */
interface LlmModelGate {
    /** Текущее состояние модели. */
    fun state(): LlmModelState

    /** Сообщает, что идёт тяжёлая стадия: копирование должно остановиться. */
    fun setBusy(busy: Boolean)

    /** Ждёт окончания копирования и возвращает итог. */
    suspend fun await(): LlmModelState
}
