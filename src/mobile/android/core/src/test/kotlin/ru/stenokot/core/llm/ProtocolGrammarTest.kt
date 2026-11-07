package ru.stenokot.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolGrammarTest {
    /**
     * Переводит грамматику в регулярное выражение, чтобы проверять её смысл, а не запись.
     * Понимает только конструкции, которые использует построитель.
     */
    private fun toRegex(grammar: String): Regex {
        val rules = grammar.trim().lines().associate { line ->
            val parts = line.split("::=", limit = 2)
            parts[0].trim() to parts[1].trim()
        }

        fun expand(name: String): String {
            val body = rules.getValue(name)
            val out = StringBuilder()
            var i = 0
            while (i < body.length) {
                val c = body[i]
                when {
                    c == ' ' -> i++
                    c == '"' -> {
                        val end = body.indexOf('"', i + 1)
                        val literal = body.substring(i + 1, end).replace("\\n", "\n")
                        out.append(Regex.escape(literal))
                        i = end + 1
                    }
                    c == '{' -> {
                        val end = body.indexOf('}', i)
                        out.append(body, i, end + 1)
                        i = end + 1
                    }
                    c == '(' || c == ')' || c == '|' -> {
                        out.append(c)
                        i++
                    }
                    else -> {
                        var end = i
                        while (end < body.length && body[end].isLetterOrDigit()) end++
                        out.append("(?:").append(expand(body.substring(i, end))).append(")")
                        i = end
                    }
                }
            }
            return out.toString()
        }

        return Regex(expand("root"))
    }

    private val regex = toRegex(ProtocolGrammar.build(WINDOW_RU))

    private fun assertAccepted(output: String) = assertTrue(output, regex.matches(output))

    private fun assertRejected(output: String) = assertFalse(output, regex.matches(output))

    @Test
    fun acceptsQuotesAndConfirmedItems() {
        assertAccepted("T: закупаем корм\n")
        assertAccepted("T: закупаем корм у нового поставщика\n")
        assertAccepted("T: закупаем корм\nD 12\nA 13\n")
    }

    @Test
    fun rejectsTextThatIsNotQuote() {
        assertRejected("T: закупка корма\n")
        assertRejected("T: налоговая проверка\n")
        assertRejected("T: корм закупаем\n")
        assertRejected("T: закупаем\n")
        assertRejected("T: Значит закупаем\n")
    }

    @Test
    fun quoteEndsOnSignificantWord() {
        assertRejected("T: закупаем корм у\n")
        assertRejected("T: корм у\n")
    }

    @Test
    fun quoteDoesNotEndOnWeakWord() {
        val window = LlmWindow(0, listOf(utterance(1, 0, 0, 1, "описание вашей архитектуры и вашего что-то")))
        val matcher = toRegex(ProtocolGrammar.build(window))
        assertTrue(matcher.matches("T: описание вашей архитектуры\n"))
        assertFalse(matcher.matches("T: архитектуры и вашего\n"))
        assertFalse(matcher.matches("T: архитектуры и вашего что-то\n"))
    }

    @Test
    fun rejectsBrokenAnswers() {
        assertRejected("D 12\n")
        assertRejected("T: закупаем корм")
        assertRejected("T: закупаем корм\nX 12\n")
        assertRejected("T: закупаем корм\nD 12: текст\n")
        assertRejected("T: закупаем корм\n" + "D 12\n".repeat(4))
    }

    @Test
    fun itemsAreLimitedToConfirmedKinds() {
        assertRejected("T: закупаем корм\nD 99\n")
        assertRejected("T: закупаем корм\nD 13\n")
        assertRejected("T: закупаем корм\nA 12\n")
        assertRejected("T: закупаем корм\nQ 12\n")
        assertRejected("T: закупаем корм\nA 14\n")
    }

    @Test
    fun quoteIsLimitedToSixWords() {
        val long = LlmWindow(0, listOf(utterance(1, 0, 0, 1, "первый второй третий четвёртый пятый шестой седьмой восьмой")))
        val matcher = toRegex(ProtocolGrammar.build(long))
        assertTrue(matcher.matches("T: первый второй третий четвёртый пятый шестой\n"))
        assertFalse(matcher.matches("T: первый второй третий четвёртый пятый шестой седьмой\n"))
    }

    @Test
    fun quoteDoesNotCrossPunctuationOrFillers() {
        val window = LlmWindow(0, listOf(utterance(1, 0, 0, 1, "Новый склад, большой город э-э далёкий регион")))
        val matcher = toRegex(ProtocolGrammar.build(window))
        assertTrue(matcher.matches("T: Новый склад\n"))
        assertTrue(matcher.matches("T: большой город\n"))
        assertFalse(matcher.matches("T: склад большой\n"))
        assertFalse(matcher.matches("T: город далёкий\n"))
    }

    @Test
    fun windowWithoutConfirmedItemsAllowsOnlyTopic() {
        val window = LlmWindow(0, listOf(utterance(1, 0, 0, 1, "Обычный разговор про погоду в большом городе")))
        val text = ProtocolGrammar.build(window)
        assertFalse(text.contains("item"))
        assertFalse(toRegex(text).matches("T: Обычный разговор\nD 1\n"))
    }

    @Test
    fun windowWithoutQuotableWordsStaysValid() {
        val window = LlmWindow(0, listOf(utterance(1, 0, 0, 1, "Да. Нет. Да.")))
        assertTrue(toRegex(ProtocolGrammar.build(window)).matches("T: -\n"))
    }

    @Test
    fun everyAcceptedTopicPassesParser() {
        val output = "T: закупаем корм у нового поставщика\nD 12\n"
        assertEquals("закупаем корм у нового поставщика", parseWindowOutput(output, WINDOW_RU).topic)
    }
}
