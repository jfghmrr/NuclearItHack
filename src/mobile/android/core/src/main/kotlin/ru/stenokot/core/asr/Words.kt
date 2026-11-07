package ru.stenokot.core.asr

import ru.stenokot.core.model.AsrSegment
import ru.stenokot.core.model.AsrToken
import ru.stenokot.core.model.Word

/** Номер говорящего у слова, которому говорящий ещё не назначен. */
const val UNASSIGNED_SPEAKER = -1

/** Длительность кадра модели: токены приходят с началом на этой сетке, конца у них нет. */
private const val FRAME_MS = 40L

/** Служебные токены словаря, в текст они не попадают. */
private val SERVICE_TOKENS = setOf("<unk>", "<blk>", "<s>", "</s>", "<pad>")

/** Метка начала слова в словаре. Распознаватель отдаёт вместо неё пробел, учитываются оба вида. */
private const val WORD_MARK = '▁'

private val OPENING_MARKS = "«([{„“‘".toSet()
private val CLOSING_MARKS = ".,?!:;…»)]}”’".toSet()
private val SENTENCE_END_MARKS = ".?!…".toSet()
private val TRAILING_QUOTES = "»)]}”’\"'".toSet()

/** Число букв и цифр слова. Длина знаков препинания нигде не учитывается. */
internal fun letterCount(text: String): Int = text.count(Char::isLetterOrDigit)

/** Слово состоит только из знаков. */
internal fun isMarkOnly(text: String): Boolean = text.none(Char::isLetterOrDigit)

/** Слово завершает предложение. Закрывающие кавычки и скобки после знака не мешают. */
internal fun endsSentence(text: String): Boolean =
    text.trimEnd { it in TRAILING_QUOTES }.lastOrNull() in SENTENCE_END_MARKS

/** Приводит слово к виду для сравнения. Применяется только к копии, сам текст не меняется. */
internal fun comparableWord(text: String): String =
    text.lowercase().replace('ё', 'е').filter(Char::isLetterOrDigit)

/**
 * Собирает слова из токенов и закрепляет отдельные знаки за соседними словами.
 * Закрывающие идут к предыдущему слову, открывающие к следующему, тире и символы вроде «₽»
 * к предыдущему через пробел, как их выдал распознаватель. Начало слова берётся по первому токену
 * с буквой, конец по последнему: знаки не сдвигают границы и не влияют на голосование.
 */
fun toWords(tokens: List<AsrToken>): List<Word> = attachPunctuation(rawWords(tokens))

/** Собирает слова среза. Если распознаватель не дал токенов, время слов делится по срезу поровну. */
fun toWords(segment: AsrSegment): List<Word> = attachPunctuation(segmentWords(segment))

/** Слова из токенов до закрепления знаков. Служебные токены отбрасываются. */
internal fun rawWords(tokens: List<AsrToken>): List<Word> {
    val out = ArrayList<Word>()
    val text = StringBuilder()
    var firstMs = -1L
    var letterStartMs = -1L
    var letterEndMs = -1L

    fun flush() {
        if (text.isNotEmpty()) {
            val startMs = if (letterStartMs >= 0) letterStartMs else firstMs
            val endMs = if (letterEndMs >= 0) letterEndMs else firstMs + FRAME_MS
            out += Word(text.toString(), startMs, endMs, UNASSIGNED_SPEAKER)
        }
        text.setLength(0)
        firstMs = -1L
        letterStartMs = -1L
        letterEndMs = -1L
    }

    for (token in tokens) {
        val raw = token.text
        if (raw.isEmpty() || raw.trim() in SERVICE_TOKENS) continue
        if (raw[0] == ' ' || raw[0] == WORD_MARK) flush()
        val body = raw.trim(' ', WORD_MARK)
        if (body.isEmpty()) continue
        if (firstMs < 0) firstMs = token.startMs
        if (body.any(Char::isLetterOrDigit)) {
            if (letterStartMs < 0) letterStartMs = token.startMs
            letterEndMs = token.startMs + FRAME_MS
        }
        text.append(body)
    }
    flush()
    return out
}

/** Слова среза до закрепления знаков. */
internal fun segmentWords(segment: AsrSegment): List<Word> {
    val words = rawWords(segment.tokens)
    if (words.isNotEmpty() || segment.text.isBlank()) return words
    val parts = segment.text.trim().split(WHITESPACE)
    val startMs = samplesToMs(segment.segment.startSample)
    val stepMs = maxOf(1L, (samplesToMs(segment.segment.endSample) - startMs) / parts.size)
    return parts.mapIndexed { index, part ->
        Word(part, startMs + index * stepMs, startMs + (index + 1) * stepMs, UNASSIGNED_SPEAKER)
    }
}

/**
 * Закрепляет слова только из знаков за соседними словами. После этого шага таких слов нет.
 * Если настоящих слов нет вовсе, результат пуст: реплика из одних знаков недопустима.
 */
internal fun attachPunctuation(words: List<Word>): List<Word> {
    val out = ArrayList<Word>(words.size)
    val prefix = StringBuilder()

    fun appendToLast(mark: String, separator: String) {
        val last = out.size - 1
        out[last] = out[last].copy(text = out[last].text + separator + mark)
    }

    for (word in words) {
        if (!isMarkOnly(word.text)) {
            out += if (prefix.isEmpty()) word else word.copy(text = prefix.toString() + word.text)
            prefix.setLength(0)
            continue
        }
        val opening = word.text.all { it in OPENING_MARKS }
        val closing = word.text.all { it in CLOSING_MARKS }
        when {
            opening -> prefix.append(word.text)
            out.isEmpty() -> prefix.append(word.text).append(' ')
            closing -> appendToLast(word.text, "")
            else -> appendToLast(word.text, " ")
        }
    }
    if (prefix.isNotEmpty() && out.isNotEmpty()) appendToLast(prefix.toString().trimEnd(), " ")
    return out
}

/** Перевод отсчётов 16 кГц в миллисекунды шкалы записи. */
internal fun samplesToMs(samples: Long): Long = samples / 16

private val WHITESPACE = Regex("\\s+")
