package ru.stenokot.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemEvidenceTest {
    private fun window(vararg lines: Pair<Int, String>) =
        LlmWindow(0, lines.mapIndexed { i, (speaker, text) -> utterance(i + 1, speaker, i * 1000L, i * 1000L + 900, text) })

    private fun candidates(vararg lines: Pair<Int, String>) = ItemEvidence.candidates(window(*lines))

    @Test
    fun plainReasoningIsNeitherDecisionNorActionNorQuestion() {
        val found = candidates(0 to "Программа автоматически разделяет текст на людей и ставит временные метки.")
        assertEquals(ItemCandidates(emptyList(), emptyList(), emptyList()), found)
    }

    @Test
    fun explicitDecisionIsFound() {
        assertEquals(listOf(1), candidates(0 to "Хорошо, принимаем дату открытия склада двадцатое декабря.").decisions)
        assertEquals(listOf(1), candidates(0 to "Мы решили перенести запуск склада на весну.").decisions)
    }

    @Test
    fun negatedOrConditionalDecisionIsNotDecision() {
        assertTrue(candidates(0 to "Мы так и не решили, кто отвечает за склад.").decisions.isEmpty())
        assertTrue(candidates(0 to "Посмотрим на цены, если договоримся с поставщиком.").decisions.isEmpty())
    }

    @Test
    fun narrativeAboutOthersIsNotDecision() {
        assertTrue(candidates(0 to "Потом они решили уехать из города навсегда.").decisions.isEmpty())
    }

    @Test
    fun questionSentenceIsNotDecisionEvidence() {
        assertTrue(candidates(0 to "Мы договорились про цену склада?").decisions.isEmpty())
    }

    @Test
    fun directTaskIsAction() {
        assertEquals(listOf(1), candidates(0 to "Марина, подготовь, пожалуйста, график приёмки оборудования.").actions)
        assertEquals(listOf(1), candidates(0 to "Вам необходимо разработать именно мобильное приложение.").actions)
        assertEquals(listOf(1), candidates(0 to "Нужно закрыть договор со складом до пятницы.").actions)
    }

    @Test
    fun generalNeedIsNotAction() {
        assertTrue(candidates(0 to "Оно должно работать на любом современном смартфоне.").actions.isEmpty())
        assertTrue(candidates(0 to "Вам надо понимать, что запись занимает много времени.").actions.isEmpty())
    }

    @Test
    fun fillersDoNotMakeAnItem() {
        val found = candidates(0 to "Э-э, ну, решили, вот.", 1 to "А-а, сделаю, угу.")
        assertTrue(found.decisions.isEmpty())
        assertTrue(found.actions.isEmpty())
    }

    @Test
    fun answeredQuestionIsNotOpen() {
        val found = candidates(0 to "Кто отвечает за найм кладовщиков?", 1 to "За найм отвечает отдел кадров в этом квартале.")
        assertTrue(found.questions.isEmpty())
    }

    @Test
    fun questionWithoutAnswerIsOpen() {
        assertEquals(listOf(1), candidates(0 to "Кто отвечает за найм кладовщиков?", 1 to "Пока не знаю, надо уточнить.").questions)
        assertEquals(listOf(1), candidates(0 to "Кто отвечает за найм кладовщиков?", 1 to "Хм.").questions)
    }

    @Test
    fun shortAnswerClosesQuestion() {
        assertTrue(candidates(0 to "А реалтайм нужен этому приложению или нет?", 1 to "Э-э, как опция нужна.").questions.isEmpty())
        assertTrue(candidates(0 to "Будет ли учитываться размер файла?", 1 to "Нет.").questions.isEmpty())
    }

    @Test
    fun idiomInsideLongAnswerDoesNotOpenQuestion() {
        val answer = "Эмулятор стандартный, четыре гигабайта памяти, у вас там не знаю что ещё добавить, но работать приложение должно на любом современном телефоне с андроидом."
        assertTrue(candidates(0 to "Известно ли заранее тестовое устройство?", 1 to answer).questions.isEmpty())
    }

    @Test
    fun rhetoricalQuestionIsNotOpen() {
        val found = candidates(0 to "Почему запись важна для компании? Потому что заметки часто теряются.", 1 to "Да.")
        assertTrue(found.questions.isEmpty())
    }

    @Test
    fun questionMarkedUnresolvedBySpeakerIsOpen() {
        val found = candidates(0 to "А кто будет отвечать за найм кладовщиков? Этот вопрос мы так и не закрыли.", 1 to "Да.")
        assertEquals(listOf(1), found.questions)
    }

    @Test
    fun lastLineWaitsForNextWindow() {
        assertTrue(candidates(0 to "Кто отвечает за найм кладовщиков?").questions.isEmpty())
    }

    @Test
    fun fillerRepetitionIsRecognised() {
        assertTrue(ItemEvidence.isFiller("и-и-и"))
        assertTrue(ItemEvidence.isFiller("э-э"))
        assertFalse(ItemEvidence.isFiller("из-за"))
    }
}
