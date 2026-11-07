package ru.stenokot.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Содержимое protocol.json. Служебный файл, в контракт не входит. */
@Serializable
data class ProtocolDocument(
    val sections: List<ProtocolSection>,
    @SerialName("covered_ms") val coveredMs: Long,
    @SerialName("total_ms") val totalMs: Long,
)

/** Раздел оглавления: тема фрагмента и пункты протокола. */
@Serializable
data class ProtocolSection(
    val title: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    @SerialName("first_utterance") val firstUtterance: Int,
    val items: List<ProtocolItem>,
)

/** Пункт протокола. Текст скопирован из реплики кодом, а не создан моделью. */
@Serializable
data class ProtocolItem(
    val kind: String,
    val utterance: Int,
    @SerialName("speaker_id") val speakerId: String,
    @SerialName("start_ms") val startMs: Long,
    val text: String,
)
