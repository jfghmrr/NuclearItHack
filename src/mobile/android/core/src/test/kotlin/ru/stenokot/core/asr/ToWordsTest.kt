package ru.stenokot.core.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenokot.core.model.AsrSegment
import ru.stenokot.core.model.AsrToken
import ru.stenokot.core.model.SpeechSegment

class ToWordsTest {
    private fun tokens(vararg pairs: Pair<String, Long>) = pairs.map { AsrToken(it.first, it.second) }

    @Test
    fun jniFormatWithLeadingSpace() {
        val words = toWords(tokens(" при" to 0, "вет" to 40, "." to 80, " как" to 400, " дела" to 800, "?" to 1000))
        assertEquals(listOf("привет.", "как", "дела?"), words.map { it.text })
    }

    @Test
    fun vocabularyFormatWithWordMark() {
        val words = toWords(tokens("▁при" to 0, "вет" to 40, "." to 80, "▁как" to 400))
        assertEquals(listOf("привет.", "как"), words.map { it.text })
    }

    @Test
    fun bareWordMarkTokenSplitsWords() {
        val words = toWords(tokens("▁" to 0, "е" to 40, "ще" to 80, "▁" to 200, "раз" to 240))
        assertEquals(listOf("еще", "раз"), words.map { it.text })
    }

    @Test
    fun unkIsFilteredAndDoesNotSplit() {
        val words = toWords(tokens(" при" to 0, "<unk>" to 40, "вет" to 80, "<unk>" to 120, " мир" to 400))
        assertEquals(listOf("привет", "мир"), words.map { it.text })
    }

    @Test
    fun blankTokenIsFiltered() {
        assertEquals(emptyList<String>(), toWords(tokens("<blk>" to 0, "<unk>" to 40)).map { it.text })
    }

    @Test
    fun wordTimesUseLetterTokensOnly() {
        val word = toWords(tokens(" «" to 0, "сло" to 80, "во" to 120, "»." to 800)).single()
        assertEquals("«слово».", word.text)
        assertEquals(80L, word.startMs)
        assertEquals(160L, word.endMs)
        assertEquals(UNASSIGNED_SPEAKER, word.speaker)
    }

    @Test
    fun closingAttachesToPrevious() {
        val words = toWords(tokens(" ну" to 0, " ..." to 400, " да" to 800))
        assertEquals(listOf("ну...", "да"), words.map { it.text })
    }

    @Test
    fun openingAttachesToNext() {
        val words = toWords(tokens(" он" to 0, " «" to 400, " сказал" to 500, "»" to 700))
        assertEquals(listOf("он", "«сказал»"), words.map { it.text })
    }

    @Test
    fun dashAttachesToPreviousThroughSpace() {
        val words = toWords(tokens(" это" to 0, " —" to 400, " важно" to 800))
        assertEquals(listOf("это —", "важно"), words.map { it.text })
    }

    @Test
    fun dashAtStartAttachesToNext() {
        val words = toWords(tokens(" —" to 0, " да" to 400))
        assertEquals(listOf("— да"), words.map { it.text })
    }

    @Test
    fun currencyKeepsAsrSpacing() {
        val words = toWords(tokens(" 500" to 0, " ₽" to 400, " за" to 800, " 50" to 1200, "%" to 1400))
        assertEquals(listOf("500 ₽", "за", "50%"), words.map { it.text })
    }

    @Test
    fun hyphenInsideWordStays() {
        val words = toWords(tokens(" кто" to 0, "-" to 40, "то" to 80, " из" to 400))
        assertEquals(listOf("кто-то", "из"), words.map { it.text })
    }

    @Test
    fun onlyMarksGiveNoWords() {
        assertTrue(toWords(tokens(" ." to 0, " ," to 40)).isEmpty())
    }

    @Test
    fun trailingOpeningGoesToPrevious() {
        val words = toWords(tokens(" слово" to 0, " «" to 400))
        assertEquals(listOf("слово «"), words.map { it.text })
    }

    @Test
    fun textIsCoveredUpToSpacesProperty() {
        val random = kotlin.random.Random(7)
        val pieces = listOf(" а", " бв", "г", ",", ".", " —", " «", "»", " ...", " ₽", "<unk>", " 12", "-", "%", " ")
        repeat(500) {
            val tokens = List(random.nextInt(1, 40)) { i -> AsrToken(pieces.random(random), i * 40L) }
            val words = toWords(tokens)
            val source = tokens.filter { it.text != "<unk>" }.joinToString("") { it.text }.replace(" ", "")
            val output = words.joinToString("") { it.text }.replace(" ", "")
            assertTrue(words.none { isMarkOnly(it.text) })
            if (source.any(Char::isLetterOrDigit)) {
                assertEquals(source.sortedLetters(), output.sortedLetters())
                assertEquals(source.filter(Char::isLetterOrDigit), output.filter(Char::isLetterOrDigit))
            } else {
                assertTrue(words.isEmpty())
            }
        }
    }

    private fun String.sortedLetters() = toList().sorted()

    @Test
    fun sentenceEndDetection() {
        assertTrue(endsSentence("слово."))
        assertTrue(endsSentence("слово?»"))
        assertTrue(endsSentence("слово!\""))
        assertFalse(endsSentence("слово,"))
        assertFalse(endsSentence("слово —"))
    }

    @Test
    fun missingTokensFallBackToSegmentText() {
        val segment = AsrSegment(SpeechSegment(0, 16_000, 32_000), "один два", emptyList())
        val words = toWords(segment)
        assertEquals(listOf("один", "два"), words.map { it.text })
        assertEquals(listOf(1_000L, 1_500L), words.map { it.startMs })
    }

    private fun chunk(index: Int, startMs: Long, endMs: Long, vararg pairs: Pair<String, Long>) =
        AsrSegment(
            SpeechSegment(index, startMs * 16, endMs * 16),
            "",
            pairs.map { AsrToken(it.first, it.second) },
        )

    @Test
    fun repeatsOutsideOverlapSurvive() {
        val a = chunk(0, 0, 3_000, " да" to 100, " да" to 500, " да" to 900)
        val b = chunk(1, 3_000, 6_000, " да" to 3_100, " да" to 3_200, " да" to 3_250)
        assertEquals(6, stitchChunks(listOf(a, b)).size)
    }

    @Test
    fun overlapCutsAtMiddleAndRemovesDuplicate() {
        val a = chunk(0, 0, 20_000, " один" to 18_100, " два" to 18_900, " три" to 19_500)
        val b = chunk(1, 18_000, 30_000, " два" to 18_950, " три" to 19_480, " четыре" to 20_500)
        val words = stitchChunks(listOf(a, b))
        assertEquals(listOf("один", "два", "три", "четыре"), words.map { it.text })
        assertEquals(19_480L, words[2].startMs)
    }

    @Test
    fun duplicateAtMiddleIsRemovedOnce() {
        val a = chunk(0, 0, 20_000, " так" to 18_900, " вот" to 18_960)
        val b = chunk(1, 18_000, 30_000, " вот" to 19_040, " вот" to 19_400)
        val words = stitchChunks(listOf(a, b))
        assertEquals(listOf("так", "вот", "вот"), words.map { it.text })
        assertEquals(listOf(18_900L, 18_960L, 19_400L), words.map { it.startMs })
    }

    @Test
    fun repeatsInsideOverlapWithFarStartSurvive() {
        val a = chunk(0, 0, 20_000, " да" to 18_500)
        val b = chunk(1, 18_000, 30_000, " да" to 19_200, " да" to 19_600)
        assertEquals(3, stitchChunks(listOf(a, b)).size)
    }

    @Test
    fun differentWordNearMiddleIsKept() {
        val a = chunk(0, 0, 20_000, " кот" to 18_950)
        val b = chunk(1, 18_000, 30_000, " код" to 19_000)
        assertEquals(listOf("кот", "код"), stitchChunks(listOf(a, b)).map { it.text })
    }
}
