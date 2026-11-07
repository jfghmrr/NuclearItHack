package ru.stenokot.core.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Корень контрактного файла. Дополнительные поля запрещены условием задачи. */
@Serializable
data class TranscriptDocument(val transcript: List<TranscriptItem>)

/** Одна реплика контрактного файла. */
@Serializable
data class TranscriptItem(
    @SerialName("speaker_id") val speakerId: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    val text: String,
)

/** Настройки сериализации контракта: компактная форма, кириллица без экранирования. */
val ContractJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
}

/** Строит идентификатор контракта из внутреннего номера говорящего, начинающегося с нуля. */
fun speakerId(speaker: Int): String = "speaker_${speaker + 1}"

/** Результат проверки реальных байтов файла. */
sealed interface ValidationResult {
    /** Файл полностью соответствует контракту. */
    data object Ok : ValidationResult

    /** Файл нельзя публиковать, список содержит причины. */
    data class Invalid(val errors: List<String>) : ValidationResult
}

/** Проверяет реальные байты JSON до публикации файла. */
fun interface TranscriptValidator {
    /** Проверяет [rawUtf8] с учётом длительности записи [audioDurationMs]. */
    fun validate(rawUtf8: ByteArray, audioDurationMs: Long): ValidationResult
}
