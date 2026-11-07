package ru.stenokot.core.llm

import ru.stenokot.core.model.Language

/** Настройки выборки. Значения стартовые, модель работает без рассуждений. */
data class LlmSampler(
    val temperature: Float = 0.2f,
    val topK: Int = 20,
    val topP: Float = 0.9f,
    val minP: Float = 0f,
    val seed: Int = 42,
    val maxOutputTokens: Int = 64,
    val contextTokens: Int = 1024,
)

/**
 * Запросы к модели. Системная часть отделена от сообщения окна: она вычисляется один раз и остаётся
 * в кэше модели, а для следующего окна удаляется только хвост. Примера ответа в запросе нет: малая
 * модель копировала его тему и порядок строк в каждый ответ.
 */
object LlmPrompts {
    /** Версия запросов для служебных файлов. */
    const val VERSION = "p2"

    private const val ASSISTANT_PREFIX = "<|im_start|>assistant\n<think>\n\n</think>\n\n"

    private val SYSTEM_RU = """
        Ты составляешь оглавление записи разговора. Дан фрагмент расшифровки, реплики пронумерованы. Опирайся только на то, что сказано прямо.
        Формат ответа:
        T: фраза из текста, которая лучше всего называет предмет обсуждения, от двух до шести слов подряд.
        D номер: реплика, где принято решение.
        A номер: реплика, где кому-то поручено дело.
        Q номер: реплика с вопросом, на который никто не ответил.
        Строки D, A, Q пиши только если это сказано прямо, обычно таких реплик нет. Не пересказывай текст.
    """.trimIndent()

    private val SYSTEM_EN = """
        You build a table of contents for a recorded conversation. You get a numbered transcript fragment. Rely only on what is stated directly.
        Reply format:
        T: the phrase from the text that best names the subject, two to six consecutive words.
        D number: the line where a decision is made.
        A number: the line where someone is given a task.
        Q number: the line with a question nobody answered.
        Write D, A, Q lines only if it is stated directly, usually there are none. Do not retell the text.
    """.trimIndent()

    /** Системный запрос на языке записи. */
    fun system(language: Language): String =
        when (language) {
            Language.RU -> SYSTEM_RU
            Language.EN -> SYSTEM_EN
        }

    /** Сообщение с репликами окна, одинаковое для обоих языков. */
    fun user(window: LlmWindow): String = window.utterances.joinToString("\n", transform = LlmWindows::line)

    /** Системная часть в разметке диалога. */
    fun systemPrefix(language: Language): String = "<|im_start|>system\n${system(language)}<|im_end|>\n"

    /** Часть окна в разметке диалога, заканчивается пустым блоком рассуждений. */
    fun windowSuffix(window: LlmWindow): String =
        "<|im_start|>user\n${user(window)}<|im_end|>\n$ASSISTANT_PREFIX"

    /** Язык запроса: определённый конвейером, а если он неизвестен, по алфавиту окна. */
    fun languageFor(language: Language?, window: LlmWindow): Language =
        language ?: if (detectScript(window.text) == Script.EN) Language.EN else Language.RU
}
