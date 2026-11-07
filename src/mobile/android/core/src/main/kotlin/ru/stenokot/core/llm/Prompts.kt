package ru.stenokot.core.llm

import ru.stenokot.core.model.Language

/** Настройки выборки. Значения стартовые, модель работает без рассуждений. */
data class LlmSampler(
    val temperature: Float = 0.2f,
    val topK: Int = 20,
    val topP: Float = 0.9f,
    val minP: Float = 0f,
    val seed: Int = 42,
    val maxOutputTokens: Int = 48,
    val contextTokens: Int = 1024,
)

/**
 * Запросы к модели. Системная часть отделена от сообщения окна: она вычисляется один раз и остаётся
 * в кэше модели, а для следующего окна удаляется только хвост.
 */
object LlmPrompts {
    /** Версия запросов для служебных файлов. */
    const val VERSION = "p1"

    private const val ASSISTANT_PREFIX = "<|im_start|>assistant\n<think>\n\n</think>\n\n"

    private val SYSTEM_RU = """
        Ты готовишь оглавление записи разговора. Дан фрагмент расшифровки с номерами реплик. Опирайся только на то, что сказано прямо.
        Формат ответа:
        T: о чём фрагмент, до 6 слов из текста.
        D номер: реплика с принятым решением.
        A номер: реплика, где кому-то поручено дело.
        Q номер: реплика с вопросом, оставшимся без ответа.
        Строки D, A, Q необязательны, не больше трёх. Если таких реплик нет, напиши только T. Не пересказывай и не исправляй текст.
        Пример.
        [3] speaker_1: Значит, закупаем корм у нового поставщика.
        [4] speaker_2: Хорошо, Барсик договорится до пятницы.
        Ответ:
        T: закупка корма
        D 3
        A 4
    """.trimIndent()

    private val SYSTEM_EN = """
        You build a table of contents for a recorded conversation. You get a numbered transcript fragment. Rely only on what is stated directly.
        Reply format:
        T: what the fragment is about, up to 6 words from the text.
        D number: the line with a decision.
        A number: the line where someone is given a task.
        Q number: the line with a question left unanswered.
        The D, A, Q lines are optional, at most three. If there are none, write only T. Do not retell or correct the text.
        Example.
        [3] speaker_1: So we buy the food from the new supplier.
        [4] speaker_2: Fine, Barsik will arrange it by Friday.
        Reply:
        T: buying food
        D 3
        A 4
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
