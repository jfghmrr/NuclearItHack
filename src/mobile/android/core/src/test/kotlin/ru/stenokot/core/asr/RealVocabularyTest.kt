package ru.stenokot.core.asr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.stenokot.core.model.AsrToken

/** Проверка на настоящем словаре. Без каталога моделей на машине тест пропускается. */
class RealVocabularyTest {
    private val modelsDir = System.getenv("STENOKOT_MODELS_DIR")

    private fun vocabulary(model: String): List<String> {
        assumeTrue(modelsDir != null)
        val file = File(modelsDir, "$model/tokens.txt")
        assumeTrue(file.exists())
        return file.readLines().map { it.substringBeforeLast(' ') }
    }

    private fun check(model: String) {
        val tokens = vocabulary(model)
        assertTrue(tokens.size > 1000)
        for (token in tokens) {
            for (form in listOf(token, token.replace('▁', ' '))) {
                val input = listOf(AsrToken(" начало", 0), AsrToken(form, 500), AsrToken(" конец", 1_000))
                val words = toWords(input)
                assertTrue(form, words.none { isMarkOnly(it.text) })
                val service = form.startsWith("<")
                val expected = "начало" + if (service) "" else form.replace(" ", "").replace("▁", "")
                assertEquals(form, expected + "конец", words.joinToString("") { it.text }.replace(" ", ""))
                assertEquals(form, "начало", words.first().text.take(6))
            }
        }
    }

    @Test
    fun everyRussianTokenIsHandled() = check("asr-ru")

    @Test
    fun everyEnglishTokenIsHandled() = check("asr-en")
}
