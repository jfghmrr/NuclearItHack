package ru.stenokot.core.llm

import java.util.Collections
import ru.stenokot.core.model.Utterance

/** Алфавит текста окна. */
enum class Script { RU, EN, MIXED, UNKNOWN }

/** Вид пункта протокола: буква ответа модели и значение поля kind в protocol.json. */
enum class ProtocolKind(val letter: Char, val value: String) {
    DECISION('D', "decision"),
    ACTION('A', "action"),
    QUESTION('Q', "question");

    companion object {
        /** Находит вид по букве ответа. */
        fun of(letter: Char): ProtocolKind? = entries.firstOrNull { it.letter == letter }
    }
}

/** Итог разбора ответа по окну. */
enum class WindowOutcome { OK, SKIPPED_SCRIPT }

/** Принятый пункт: модель выбрала вид и номер, всё остальное берётся из реплики. */
data class ExtractedItem(val kind: ProtocolKind, val utterance: Utterance)

/** Разобранный ответ модели по одному окну. */
data class WindowExtract(val outcome: WindowOutcome, val topic: String?, val items: List<ExtractedItem>)

private const val MIN_SCRIPT_LETTERS = 20
private const val SCRIPT_SHARE = 0.7

private val WORD = Regex("[0-9]+|[а-яёa-z]+(?:['’-][а-яёa-z]+)*")
private val TOPIC_LINE = Regex("T: (.{3,120})")
private val ITEM_LINE = Regex("([DAQ]) ([0-9]{1,5})")

/** Слова текста в нижнем регистре, ё приведена к е. Используются только для сравнения. */
internal fun normalizedWords(text: String): List<String> =
    WORD.findAll(text.lowercase()).map { it.value.replace('ё', 'е') }.toList()

/** Определяет алфавит. Меньше двадцати букв значит, что опереться не на что. */
fun detectScript(text: String): Script {
    var cyrillic = 0
    var latin = 0
    for (c in text) {
        when (c) {
            in 'а'..'я', in 'А'..'Я', 'ё', 'Ё' -> cyrillic++
            in 'a'..'z', in 'A'..'Z' -> latin++
        }
    }
    val letters = cyrillic + latin
    return when {
        letters < MIN_SCRIPT_LETTERS -> Script.UNKNOWN
        cyrillic.toDouble() / letters >= SCRIPT_SHARE -> Script.RU
        latin.toDouble() / letters >= SCRIPT_SHARE -> Script.EN
        else -> Script.MIXED
    }
}

/**
 * Тема годится, только если она дословная цитата из окна. Отказ оставляет разделу интервал времени:
 * лучше честное время, чем придуманное название. Проверка не верит грамматике и работает и для
 * ответов из журнала.
 */
fun isTopicQuote(topic: String, windowText: String): Boolean {
    val quote = normalizedWords(topic)
    val words = topic.trim().split(' ').size
    if (quote.isEmpty() || words !in ProtocolGrammar.MIN_TOPIC_WORDS..ProtocolGrammar.MAX_TOPIC_WORDS) return false
    return Collections.indexOfSubList(normalizedWords(windowText), quote) >= 0
}

/**
 * Разбирает ответ модели. Модель выбирает только цитату для темы, вид пункта и номер реплики; своих
 * слов она в протокол добавить не может. Вид пункта должен подтверждаться текстом реплики, а одна
 * реплика даёт один пункт. Испорченный ответ даёт пустой результат, а не ошибку.
 */
fun parseWindowOutput(raw: String, window: LlmWindow): WindowExtract {
    val text = window.text
    if (detectScript(text) == Script.UNKNOWN) return WindowExtract(WindowOutcome.SKIPPED_SCRIPT, null, emptyList())
    val byId = window.utterances.associateBy { it.id }
    val candidates = ItemEvidence.candidates(window)
    var topic: String? = null
    val items = ArrayList<ExtractedItem>()
    val seen = HashSet<Int>()
    for (rawLine in raw.lineSequence()) {
        val line = rawLine.trim()
        val topicMatch = TOPIC_LINE.matchEntire(line)
        if (topicMatch != null && topic == null) {
            val candidate = topicMatch.groupValues[1].trim()
            if (isTopicQuote(candidate, text)) topic = candidate
            continue
        }
        val match = ITEM_LINE.matchEntire(line) ?: continue
        if (items.size >= ProtocolGrammar.MAX_ITEMS) continue
        val kind = ProtocolKind.of(match.groupValues[1][0]) ?: continue
        val utterance = byId[match.groupValues[2].toInt()] ?: continue
        if (utterance.id !in candidates.of(kind)) continue
        if (seen.add(utterance.id)) items += ExtractedItem(kind, utterance)
    }
    return WindowExtract(WindowOutcome.OK, topic, items)
}
