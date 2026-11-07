package ru.stenokot.core.llm

import ru.stenokot.core.model.Utterance
import ru.stenokot.core.model.Word

/** Реплика для тестов: слова получаются разбиением текста по пробелам. */
internal fun utterance(id: Int, speaker: Int, startMs: Long, endMs: Long, text: String): Utterance =
    Utterance(
        id = id,
        speaker = speaker,
        startMs = startMs,
        endMs = endMs,
        words = text.split(" ").map { Word(it, startMs, endMs, speaker) },
        text = text,
    )

internal val WINDOW_RU = LlmWindow(
    0,
    listOf(
        utterance(12, 0, 0, 3_000, "Значит, закупаем корм у нового поставщика."),
        utterance(13, 1, 3_100, 7_000, "Барсик, поручаю тебе договориться с поставщиком."),
        utterance(14, 0, 7_100, 8_000, "Да."),
    ),
)

internal val WINDOW_EN = LlmWindow(
    1,
    listOf(
        utterance(21, 0, 0, 3_000, "So we buy the food from the new supplier."),
        utterance(22, 1, 3_100, 7_000, "Barsik, please call the supplier by November twentieth."),
        utterance(23, 0, 7_100, 9_000, "We won't install cameras, it is too expensive."),
        utterance(24, 2, 9_100, 9_500, "Okay."),
        utterance(25, 1, 9_600, 12_000, "It costs about twelve thousand rubles."),
    ),
)
