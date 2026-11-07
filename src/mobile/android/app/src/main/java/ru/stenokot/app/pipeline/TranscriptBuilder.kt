package ru.stenokot.app.pipeline

import kotlinx.serialization.encodeToString
import ru.stenokot.core.contract.ContractJson
import ru.stenokot.core.contract.TranscriptDocument
import ru.stenokot.core.contract.TranscriptItem
import ru.stenokot.core.model.Utterance

/** Собирает контрактные байты из реплик. Текст реплик переносится без единого изменения. */
object TranscriptBuilder {
    /** Строит документ. Номера говорящих идут сплошь по первому появлению. */
    fun document(utterances: List<Utterance>): TranscriptDocument {
        val numbers = LinkedHashMap<Int, Int>()
        val items = utterances.map { utterance ->
            val number = numbers.getOrPut(utterance.speaker) { numbers.size + 1 }
            TranscriptItem(
                speakerId = "speaker_$number",
                startMs = utterance.startMs,
                endMs = utterance.endMs,
                text = utterance.text,
            )
        }
        return TranscriptDocument(items)
    }

    /** Возвращает байты контрактного файла в UTF-8 без BOM. */
    fun bytes(utterances: List<Utterance>): ByteArray =
        ContractJson.encodeToString(document(utterances)).toByteArray(Charsets.UTF_8)
}
