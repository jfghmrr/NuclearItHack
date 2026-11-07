package ru.stenokot.core.contract

import ru.stenokot.core.model.Utterance

/** Переводит реплики в байты контрактного файла: UTF-8 без BOM, кириллица без экранирования. */
object TranscriptEncoder {
    /** Оставляет от реплик только поля контракта. Служебные данные наружу не попадают. */
    fun document(utterances: List<Utterance>): TranscriptDocument =
        TranscriptDocument(
            utterances.map { TranscriptItem(speakerId(it.speaker), it.startMs, it.endMs, it.text) },
        )

    /** Возвращает байты контрактного файла. */
    fun encode(utterances: List<Utterance>): ByteArray =
        ContractJson.encodeToString(TranscriptDocument.serializer(), document(utterances))
            .toByteArray(Charsets.UTF_8)
}
