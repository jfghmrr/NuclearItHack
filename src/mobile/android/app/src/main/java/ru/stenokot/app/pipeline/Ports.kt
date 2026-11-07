package ru.stenokot.app.pipeline

import ru.stenokot.app.data.JobRecord
import ru.stenokot.app.data.PreflightRecord
import ru.stenokot.core.model.ErrorCode
import java.io.File

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
}

/** Итог предварительной проверки. */
sealed interface PreflightOutcome {
    /** Файл годится: [record] показывается пользователю, [warnings] попадают в задачу. */
    data class Passed(val record: PreflightRecord, val warnings: List<String>) : PreflightOutcome

    /** Файл не годится. [record] есть, если сведения успели собрать. */
    data class Rejected(
        val code: ErrorCode,
        val detail: String?,
        val record: PreflightRecord?,
    ) : PreflightOutcome
}

/** Доступ к импортируемому файлу. */
interface SourceGateway {
    /** Проверяет файл до загрузки моделей. */
    suspend fun preflight(job: JobRecord): PreflightOutcome

    /** Копирует файл в [target]. [progress] получает скопированные и общие байты. */
    suspend fun copy(job: JobRecord, target: File, progress: (copied: Long, total: Long) -> Unit)
}

/** Адрес опубликованного файла. */
data class PublishedFile(val path: String, val uri: String?, val fallback: Boolean)

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
