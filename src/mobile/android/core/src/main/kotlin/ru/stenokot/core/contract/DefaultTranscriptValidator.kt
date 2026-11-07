package ru.stenokot.core.contract

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.SerializationException

/**
 * Проверяет контрактный файл по реальным байтам, все девять пунктов контракта.
 * Сообщения идут только в диагностику, поэтому могут быть техническими.
 */
class DefaultTranscriptValidator : TranscriptValidator {
    override fun validate(rawUtf8: ByteArray, audioDurationMs: Long): ValidationResult {
        if (hasBom(rawUtf8)) return ValidationResult.Invalid(listOf("файл начинается с BOM"))
        val text = decodeUtf8(rawUtf8)
            ?: return ValidationResult.Invalid(listOf("файл не является корректным UTF-8"))
        val root = try {
            StrictJsonParser(text).parse()
        } catch (e: JsonSyntaxException) {
            return ValidationResult.Invalid(listOf("JSON в позиции ${e.offset}: ${e.message}"))
        }
        val errors = ArrayList<String>()
        val items = readDocument(root, errors) ?: return ValidationResult.Invalid(errors)
        checkOrderAndRange(items, audioDurationMs, errors)
        checkSpeakers(items, errors)
        if (errors.isEmpty()) checkRoundTrip(text, items, errors)
        return if (errors.isEmpty()) ValidationResult.Ok else ValidationResult.Invalid(errors)
    }

    private fun hasBom(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()

    private fun decodeUtf8(bytes: ByteArray): String? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun readDocument(root: JsonNode, errors: MutableList<String>): List<TranscriptItem>? {
        if (root !is JsonObjectNode) {
            errors += "корень не объект"
            return null
        }
        if (root.members.map { it.first } != listOf(KEY_ROOT)) {
            errors += "в корне должен быть ровно ключ $KEY_ROOT"
            return null
        }
        val array = root.members.single().second
        if (array !is JsonArrayNode) {
            errors += "$KEY_ROOT не массив"
            return null
        }
        val items = ArrayList<TranscriptItem>(array.items.size)
        array.items.forEachIndexed { index, node ->
            readItem(index, node, errors)?.let(items::add)
        }
        return if (items.size == array.items.size) items else null
    }

    private fun readItem(index: Int, node: JsonNode, errors: MutableList<String>): TranscriptItem? {
        if (node !is JsonObjectNode) {
            errors += "запись $index не объект"
            return null
        }
        val keys = node.members.map { it.first }
        if (keys.size != ITEM_KEYS.size || keys.toSet() != ITEM_KEYS) {
            errors += "запись $index: нужны ровно ключи $ITEM_KEYS"
            return null
        }
        val fields = node.members.toMap()
        val speaker = readString(fields.getValue(KEY_SPEAKER), "$index.$KEY_SPEAKER", errors)
        val start = readInteger(fields.getValue(KEY_START), "$index.$KEY_START", errors)
        val end = readInteger(fields.getValue(KEY_END), "$index.$KEY_END", errors)
        val text = readString(fields.getValue(KEY_TEXT), "$index.$KEY_TEXT", errors)
        if (speaker == null || start == null || end == null || text == null) return null
        checkText(index, text, errors)
        return TranscriptItem(speaker, start, end, text)
    }

    private fun readString(node: JsonNode, label: String, errors: MutableList<String>): String? {
        if (node is JsonStringNode) return node.value
        errors += "$label не строка"
        return null
    }

    private fun readInteger(node: JsonNode, label: String, errors: MutableList<String>): Long? {
        if (node !is JsonNumberNode || !INTEGER.matches(node.literal)) {
            errors += "$label не целое без дробной части и экспоненты"
            return null
        }
        val value = node.literal.toLongOrNull()
        if (value == null) errors += "$label вне диапазона"
        return value
    }

    private fun checkText(index: Int, text: String, errors: MutableList<String>) {
        if (text.isBlank()) errors += "запись $index: пустой текст"
        if (text.any { Character.getType(it) == Character.CONTROL.toInt() }) {
            errors += "запись $index: управляющий символ в тексте"
        }
        if (hasLoneSurrogate(text)) errors += "запись $index: одиночный суррогат в тексте"
    }

    private fun hasLoneSurrogate(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isHighSurrogate() && text.getOrNull(i + 1)?.isLowSurrogate() == true) {
                i += 2
                continue
            }
            if (c.isSurrogate()) return true
            i++
        }
        return false
    }

    private fun checkOrderAndRange(
        items: List<TranscriptItem>,
        audioDurationMs: Long,
        errors: MutableList<String>,
    ) {
        var previousStart = Long.MIN_VALUE
        items.forEachIndexed { index, item ->
            if (item.startMs < 0) errors += "запись $index: start_ms меньше нуля"
            if (item.startMs >= item.endMs) errors += "запись $index: start_ms не меньше end_ms"
            if (item.endMs > audioDurationMs + DURATION_TOLERANCE_MS) {
                errors += "запись $index: end_ms за концом записи"
            }
            if (item.startMs < previousStart) errors += "запись $index: нарушена сортировка"
            previousStart = item.startMs
        }
    }

    private fun checkSpeakers(items: List<TranscriptItem>, errors: MutableList<String>) {
        var seen = 0L
        items.forEachIndexed { index, item ->
            val match = SPEAKER.matchEntire(item.speakerId)
            val number = match?.groupValues?.get(1)?.toLongOrNull()
            when {
                number == null -> errors += "запись $index: неверный speaker_id"
                number > seen + 1 -> errors += "запись $index: пропуск в нумерации говорящих"
                number == seen + 1 -> seen = number
            }
        }
    }

    private fun checkRoundTrip(text: String, items: List<TranscriptItem>, errors: MutableList<String>) {
        val serializer = TranscriptDocument.serializer()
        try {
            val first = ContractJson.decodeFromString(serializer, text)
            val second = ContractJson.decodeFromString(
                serializer,
                ContractJson.encodeToString(serializer, first),
            )
            if (first.transcript != items || first != second) errors += "повторный разбор дал другой объект"
        } catch (e: SerializationException) {
            errors += "повторный разбор не удался: ${e.message}"
        }
    }

    private companion object {
        const val DURATION_TOLERANCE_MS = 50L
        const val KEY_ROOT = "transcript"
        const val KEY_SPEAKER = "speaker_id"
        const val KEY_START = "start_ms"
        const val KEY_END = "end_ms"
        const val KEY_TEXT = "text"
        val ITEM_KEYS = setOf(KEY_SPEAKER, KEY_START, KEY_END, KEY_TEXT)
        val INTEGER = Regex("0|-?[1-9][0-9]*")
        val SPEAKER = Regex("speaker_([1-9][0-9]*)")
    }
}
