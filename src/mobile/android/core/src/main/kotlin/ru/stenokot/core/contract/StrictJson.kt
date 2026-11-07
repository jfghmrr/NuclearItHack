package ru.stenokot.core.contract

/** Узел разобранного документа. Числа хранятся исходной записью, ключи объектов в порядке файла. */
internal sealed interface JsonNode

/** Объект с повторами ключей, если они есть в файле. */
internal class JsonObjectNode(val members: List<Pair<String, JsonNode>>) : JsonNode

/** Массив. */
internal class JsonArrayNode(val items: List<JsonNode>) : JsonNode

/** Строка после раскрытия экранирования. */
internal class JsonStringNode(val value: String) : JsonNode

/** Число в том виде, в каком оно записано в файле. */
internal class JsonNumberNode(val literal: String) : JsonNode

/** Литерал true, false или null. */
internal class JsonLiteralNode(val literal: String) : JsonNode

/** Нарушение синтаксиса JSON с позицией в символах. */
internal class JsonSyntaxException(message: String, val offset: Int) : Exception(message)

/**
 * Строгий разбор JSON по RFC 8259. Общий разборщик здесь не подходит: он прячет повторы ключей
 * и запись чисел, а контракт проверяется именно по ним.
 */
internal class StrictJsonParser(private val source: String) {
    private var position = 0

    /** Разбирает весь документ. Содержимое после корня считается ошибкой. */
    fun parse(): JsonNode {
        skipSpace()
        val root = value(0)
        skipSpace()
        if (position != source.length) fail("лишние данные после корня")
        return root
    }

    private fun value(depth: Int): JsonNode {
        if (depth > MAX_DEPTH) fail("слишком большая вложенность")
        if (position >= source.length) fail("неожиданный конец файла")
        return when (source[position]) {
            '{' -> objectNode(depth)
            '[' -> arrayNode(depth)
            '"' -> JsonStringNode(string())
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> number()
        }
    }

    private fun objectNode(depth: Int): JsonNode {
        position++
        val members = ArrayList<Pair<String, JsonNode>>()
        skipSpace()
        if (peek() == '}') {
            position++
            return JsonObjectNode(members)
        }
        while (true) {
            skipSpace()
            if (peek() != '"') fail("ожидался ключ")
            val key = string()
            skipSpace()
            if (peek() != ':') fail("ожидалось двоеточие")
            position++
            skipSpace()
            members += key to value(depth + 1)
            skipSpace()
            when (peek()) {
                ',' -> position++
                '}' -> {
                    position++
                    return JsonObjectNode(members)
                }
                else -> fail("ожидалась запятая или конец объекта")
            }
        }
    }

    private fun arrayNode(depth: Int): JsonNode {
        position++
        val items = ArrayList<JsonNode>()
        skipSpace()
        if (peek() == ']') {
            position++
            return JsonArrayNode(items)
        }
        while (true) {
            skipSpace()
            items += value(depth + 1)
            skipSpace()
            when (peek()) {
                ',' -> position++
                ']' -> {
                    position++
                    return JsonArrayNode(items)
                }
                else -> fail("ожидалась запятая или конец массива")
            }
        }
    }

    private fun string(): String {
        position++
        val out = StringBuilder()
        while (true) {
            if (position >= source.length) fail("незакрытая строка")
            val c = source[position]
            when {
                c == '"' -> {
                    position++
                    return out.toString()
                }
                c == '\\' -> {
                    position++
                    escape(out)
                }
                c.code < 0x20 -> fail("управляющий символ внутри строки")
                else -> {
                    out.append(c)
                    position++
                }
            }
        }
    }

    private fun escape(out: StringBuilder) {
        val c = peek() ?: fail("незакрытое экранирование")
        position++
        when (c) {
            '"', '\\', '/' -> out.append(c)
            'b' -> out.append('\b')
            'f' -> out.append('\u000C')
            'n' -> out.append('\n')
            'r' -> out.append('\r')
            't' -> out.append('\t')
            'u' -> {
                if (position + 4 > source.length) fail("короткая запись \\u")
                val code = source.substring(position, position + 4).toIntOrNull(16)
                    ?.takeIf { source[position] != '+' && source[position] != '-' }
                    ?: fail("неверная запись \\u")
                position += 4
                out.append(code.toChar())
            }
            else -> fail("неизвестное экранирование")
        }
    }

    private fun number(): JsonNode {
        val start = position
        if (peek() == '-') position++
        if (peek() == '0') {
            position++
        } else {
            if (!digits()) fail("ожидалось значение")
        }
        if (peek() == '.') {
            position++
            if (!digits()) fail("пустая дробная часть")
        }
        if (peek() == 'e' || peek() == 'E') {
            position++
            if (peek() == '+' || peek() == '-') position++
            if (!digits()) fail("пустая экспонента")
        }
        return JsonNumberNode(source.substring(start, position))
    }

    private fun digits(): Boolean {
        val start = position
        while (position < source.length && source[position] in '0'..'9') position++
        return position > start
    }

    private fun literal(word: String): JsonNode {
        if (!source.startsWith(word, position)) fail("неизвестное значение")
        position += word.length
        return JsonLiteralNode(word)
    }

    private fun peek(): Char? = source.getOrNull(position)

    private fun skipSpace() {
        while (position < source.length) {
            when (source[position]) {
                ' ', '\t', '\n', '\r' -> position++
                else -> return
            }
        }
    }

    private fun fail(message: String): Nothing = throw JsonSyntaxException(message, position)

    private companion object {
        const val MAX_DEPTH = 16
    }
}
