package ru.stenokot.core.llm

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

/** Причина отказа в теме. Тогда раздел получает интервал времени вместо названия. */
enum class TopicRejection { SUPPORT, DIGITS }

/** Итог разбора ответа по окну. */
enum class WindowOutcome { OK, SKIPPED_SCRIPT }

/** Принятый пункт: модель выбрала вид и номер, всё остальное берётся из реплики. */
data class ExtractedItem(val kind: ProtocolKind, val utterance: Utterance)

/** Разобранный ответ модели по одному окну. */
data class WindowExtract(val outcome: WindowOutcome, val topic: String?, val items: List<ExtractedItem>)

private const val MIN_SCRIPT_LETTERS = 20
private const val SCRIPT_SHARE = 0.7
private const val MIN_TOPIC_WORD_LENGTH = 4
private const val MIN_TOPIC_SUPPORT = 0.6
private const val MIN_ITEM_WORDS = 3

private val WORD = Regex("[0-9]+|[а-яёa-z]+(?:['’-][а-яёa-z]+)*")
private val TOPIC_LINE = Regex("T: (.{3,80})")
private val ITEM_LINE = Regex("([DAQ]) ([0-9]{1,5})")

/** Слова текста в нижнем регистре, ё приведена к е. Используются только для сравнения. */
internal fun normalizedWords(text: String): List<String> =
    WORD.findAll(text.lowercase()).map { it.value.replace('ё', 'е') }.toList()

/** Основа слова: от четырёх до пяти букв, без последней буквы. */
internal fun stem(word: String): String = word.take(maxOf(4, minOf(5, word.length - 1)))

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
 * Проверяет тему по основам слов окна. Проверка не зависит от языка: тема годится, если достаточно
 * её слов встречается в окне, а все числа в цифрах взяты из окна.
 */
fun checkTopic(topic: String, windowText: String, minSupport: Double = MIN_TOPIC_SUPPORT): TopicRejection? {
    val window = normalizedWords(windowText)
    val topicWords = normalizedWords(topic)
    val long = topicWords.filter { it.length >= MIN_TOPIC_WORD_LENGTH }
    val stems = window.mapTo(HashSet(), ::stem)
    if (long.isNotEmpty() && long.count { stem(it) in stems }.toDouble() / long.size < minSupport) {
        return TopicRejection.SUPPORT
    }
    val windowDigits = window.filterTo(HashSet()) { it.all(Char::isDigit) }
    if (topicWords.any { it.all(Char::isDigit) && it !in windowDigits }) return TopicRejection.DIGITS
    return null
}

/**
 * Разбирает ответ модели. Модель выбирает только вид пункта и номер реплики; ни одно её слово,
 * кроме темы, в протокол не попадает. Испорченный ответ даёт пустой результат, а не ошибку.
 */
fun parseWindowOutput(raw: String, window: LlmWindow): WindowExtract {
    val text = window.text
    if (detectScript(text) == Script.UNKNOWN) return WindowExtract(WindowOutcome.SKIPPED_SCRIPT, null, emptyList())
    val byId = window.utterances.associateBy { it.id }
    var topic: String? = null
    val items = ArrayList<ExtractedItem>()
    val seen = HashSet<Pair<ProtocolKind, Int>>()
    for (rawLine in raw.lineSequence()) {
        val line = rawLine.trim()
        val topicMatch = TOPIC_LINE.matchEntire(line)
        if (topicMatch != null && topic == null) {
            val candidate = topicMatch.groupValues[1].trim()
            if (checkTopic(candidate, text) == null) topic = candidate
            continue
        }
        val match = ITEM_LINE.matchEntire(line) ?: continue
        if (items.size >= ProtocolGrammar.MAX_ITEMS) continue
        val kind = ProtocolKind.of(match.groupValues[1][0]) ?: continue
        val utterance = byId[match.groupValues[2].toInt()] ?: continue
        if (normalizedWords(utterance.text).size < MIN_ITEM_WORDS) continue
        if (seen.add(kind to utterance.id)) items += ExtractedItem(kind, utterance)
    }
    return WindowExtract(WindowOutcome.OK, topic, items)
}
