package ru.stenokot.core.llm

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenokot.core.model.Language
import ru.stenokot.core.model.Utterance
import ru.stenokot.core.model.Word

class WindowsTest {
    private val utterances = (1..40).map { utterance(it, it % 2, it * 10_000L, it * 10_000L + 9_000, "слово и ещё слово") }

    private fun evenWindows(count: Int) =
        List(count) { i -> LlmWindow(i, listOf(utterance(i + 1, 0, i * 60_000L, (i + 1) * 60_000L, "текст"))) }

    @Test
    fun windowsOverlapByOneUtterance() {
        val windows = LlmWindows.split(utterances, 100) { 30 }
        assertTrue(windows.size > 1)
        windows.zipWithNext().forEach { (a, b) -> assertEquals(a.utterances.last(), b.utterances.first()) }
        windows.forEach { assertTrue(it.utterances.size * 30 <= 100) }
        assertEquals(utterances, windows.flatMap { it.utterances }.distinct())
        assertEquals(windows.indices.toList(), windows.map { it.index })
    }

    @Test
    fun oversizedUtteranceStillProgresses() {
        val windows = LlmWindows.split(utterances.take(5), 10) { 100 }
        assertEquals(utterances.take(5), windows.flatMap { it.utterances }.distinct())
    }

    @Test
    fun emptyInputGivesNoWindows() {
        assertTrue(LlmWindows.split(emptyList(), 100) { 1 }.isEmpty())
    }

    @Test
    fun tokenEstimateFollowsLanguage() {
        val u = utterances[0]
        assertTrue(LlmWindows.estimateTokens(u, Language.RU) > LlmWindows.estimateTokens(u, Language.EN))
    }

    @Test
    fun selectionIsEvenAcrossTime() {
        val chosen = LlmWindows.selectEvenly(evenWindows(100), 4).map { it.index }
        assertEquals(listOf(12, 37, 62, 87), chosen)
    }

    @Test
    fun selectionCanBeLargerThanWindows() {
        assertEquals(3, LlmWindows.selectEvenly(evenWindows(3), 9).size)
        assertTrue(LlmWindows.selectEvenly(evenWindows(3), 0).isEmpty())
    }

    @Test
    fun selectionFollowsTimeNotNumbers() {
        val dense = List(50) { i -> LlmWindow(i, listOf(utterance(i + 1, 0, i * 1_000L, (i + 1) * 1_000L, "текст"))) }
        val sparse = List(5) { i -> LlmWindow(50 + i, listOf(utterance(60 + i, 0, 100_000L + i * 200_000, 300_000L + i * 200_000, "текст"))) }
        val chosen = LlmWindows.selectEvenly(dense + sparse, 4)
        assertEquals(0, chosen.count { it.index < 50 })
    }

    @Test
    fun coverageOrderIsEvenForAnyPrefix() {
        val windows = evenWindows(64)
        val order = LlmWindows.coverageOrder(windows)
        assertEquals(windows.toSet(), order.toSet())
        assertEquals(windows.size, order.size)
        for (size in listOf(1, 3, 7, 15, 31)) {
            val taken = order.take(size).map { it.index }.sorted()
            val gaps = (listOf(0) + taken + listOf(63)).zipWithNext { a, b -> b - a }
            assertTrue("$size $gaps", gaps.max() <= 2 * 64 / size + 1)
            val middle = taken[taken.size / 2]
            assertTrue(abs(middle - 32) <= 16)
        }
    }

    @Test
    fun sparseWindowsAreDropped() {
        val word = Word("да", 0, 100, 0)
        val rare = LlmWindow(0, listOf(Utterance(1, 0, 0, 120_000, listOf(word), "да")))
        val normal = LlmWindow(1, listOf(utterance(2, 0, 120_000, 130_000, "обычная речь идёт дальше")))
        assertTrue(LlmWindows.isSparse(rare))
        assertFalse(LlmWindows.isSparse(normal))
        assertEquals(listOf(normal), LlmWindows.withoutSparse(listOf(rare, normal)))
        assertEquals(listOf(rare), LlmWindows.withoutSparse(listOf(rare)))
    }

    @Test
    fun promptsCarryNumberedLines() {
        assertEquals(
            "[12] speaker_1: Значит, закупаем корм у нового поставщика.\n" +
                "[13] speaker_2: Барсик, поручаю тебе договориться с поставщиком.\n" +
                "[14] speaker_1: Да.",
            LlmPrompts.user(WINDOW_RU),
        )
    }

    @Test
    fun systemPromptsDifferByLanguage() {
        assertTrue(LlmPrompts.system(Language.RU).startsWith("Ты составляешь оглавление"))
        assertTrue(LlmPrompts.system(Language.EN).startsWith("You build a table of contents"))
        assertFalse(LlmPrompts.system(Language.RU).contains("  "))
    }

    /** Модель копировала тему примера в ответ, поэтому конкретного примера в запросе быть не должно. */
    @Test
    fun systemPromptsHoldNoConcreteExample() {
        for (language in Language.entries) {
            assertFalse(Regex("(?m)^(T: |D \\d|A \\d|Q \\d)").containsMatchIn(LlmPrompts.system(language).replace("T: фраза", "").replace("T: the phrase", "")))
        }
    }

    @Test
    fun chatMarkupDisablesThinking() {
        val full = LlmPrompts.systemPrefix(Language.RU) + LlmPrompts.windowSuffix(WINDOW_RU)
        assertTrue(full.startsWith("<|im_start|>system\n"))
        assertTrue(full.contains("<|im_end|>\n<|im_start|>user\n[12]"))
        assertTrue(full.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
    }

    @Test
    fun promptLanguageFallsBackToScript() {
        assertEquals(Language.EN, LlmPrompts.languageFor(null, WINDOW_EN))
        assertEquals(Language.RU, LlmPrompts.languageFor(null, WINDOW_RU))
        assertEquals(Language.RU, LlmPrompts.languageFor(Language.RU, WINDOW_EN))
    }
}
