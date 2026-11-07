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
                    c == '[' -> {
                        var end = i + 1
                        while (body[end] != ']' || body[end - 1] == '\\') end++
                        out.append(body, i, end + 1)
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
                        while (end < body.length && body[end].isLetter()) end++
                        out.append("(?:").append(expand(body.substring(i, end))).append(")")
                        i = end
                    }
                }
            }
            return out.toString()
        }

        return Regex(expand("root"))
    }

    private val grammar = ProtocolGrammar.build(listOf(12, 13, 14))
    private val regex = toRegex(grammar)

    private fun assertAccepted(output: String) = assertTrue(output, regex.matches(output))

    private fun assertRejected(output: String) = assertFalse(output, regex.matches(output))

    @Test
    fun exactText() {
        assertEquals(
            """
            root  ::= topic item{0,3}
            topic ::= "T: " word (" " word){0,5} "\n"
            item  ::= [DAQ] " " ref "\n"
            ref   ::= "12" | "13" | "14"
            word  ::= [^ \n\t\[\]]{1,24}
            """.trimIndent() + "\n",
            grammar,
        )
    }

    @Test
    fun acceptsValidAnswers() {
        assertAccepted("T: закупка корма\n")
        assertAccepted("T: закупка корма\nD 12\nA 13\n")
        assertAccepted("T: a b c d e f\nD 12\nA 13\nQ 14\n")
    }

    @Test
    fun rejectsInvalidAnswers() {
        assertRejected("D 12\n")
        assertRejected("T: тема\nD 99\n")
        assertRejected("T: тема\nX 12\n")
        assertRejected("T: тема\n" + "D 12\n".repeat(4))
        assertRejected("T: a b c d e f g\n")
        assertRejected("T: тема\nD 12: текст\n")
        assertRejected("T: [тема]\n")
        assertRejected("T: " + "ы".repeat(25) + "\n")
        assertRejected("T: тема")
    }

    @Test
    fun refContainsOnlyWindowNumbers() {
        val text = ProtocolGrammar.build(WINDOW_RU)
        assertTrue(text.contains("""ref   ::= "12" | "13" | "14""""))
        val other = toRegex(ProtocolGrammar.build(LlmWindow(0, listOf(utterance(7, 0, 0, 1, "да")))))
        assertTrue(other.matches("T: тема\nD 7\n"))
        assertFalse(other.matches("T: тема\nD 12\n"))
    }
}
