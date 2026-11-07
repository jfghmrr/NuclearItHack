package ru.stenokot.app.ui.result

/** Ё и е не различаются: в расшифровке и в запросе человек пишет их по-разному. */
private fun foldChar(char: Char): Char = when (val lower = Character.toLowerCase(char)) {
    'ё' -> 'е'
    else -> lower
}

/**
 * Приводит текст к виду для поиска. Посимвольно, а не через `lowercase()`: длина обязана
 * совпасть с исходной, иначе найденные позиции разъедутся с текстом на экране.
 */
fun foldForSearch(text: String): String {
    val chars = CharArray(text.length) { foldChar(text[it]) }
    return String(chars)
}

/**
 * Находит все непересекающиеся вхождения [query] в [folded]. Оба значения уже приведены через
 * [foldForSearch]. Возвращает пары начало и конец подряд, пустой массив означает «нет вхождений».
 */
fun findMatches(folded: String, query: String): IntArray {
    if (query.isEmpty()) return EmptyMatches
    var found = 0
    var buffer = EmptyMatches
    var from = folded.indexOf(query)
    while (from >= 0) {
        if (found * 2 == buffer.size) buffer = buffer.copyOf((found * 2).coerceAtLeast(2) * 2)
        buffer[found * 2] = from
        buffer[found * 2 + 1] = from + query.length
        found += 1
        from = folded.indexOf(query, from + query.length)
    }
    return if (found == 0) EmptyMatches else buffer.copyOf(found * 2)
}

/** Запрос в рабочем виде: без крайних пробелов и уже приведённый. */
fun normalizeQuery(raw: String): String = foldForSearch(raw.trim())

private val EmptyMatches = IntArray(0)
