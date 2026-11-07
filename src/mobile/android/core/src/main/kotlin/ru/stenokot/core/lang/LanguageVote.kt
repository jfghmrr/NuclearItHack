package ru.stenokot.core.lang

import ru.stenokot.core.engine.LanguageDetector
import ru.stenokot.core.model.AsrSegment
import ru.stenokot.core.model.Language

/** Голоса для metadata.json. [settled] значит, что пробных срезов хватило для решения. */
data class LanguageTally(
    val cyrillic: Int,
    val latin: Int,
    val chunks: Int,
    val settled: Boolean,
    val language: Language,
)

/**
 * Определяет язык файла по алфавиту пробных срезов русского маршрута. Модель пишет русскую речь
 * кириллицей, а английскую латиницей, причём словами без смысла, поэтому служебных английских слов
 * в таком тексте почти нет. Английские термины в русской речи дают лишь малую долю латиницы. Другие
 * языки на латинице уходят в английский маршрут: русский для них не лучше. Если слов мало или перевес
 * мал, конвейер читает ещё срезы, а итог решает большинство. При равенстве и без слов остаётся русский.
 */
class LanguageVote(
    private val minWords: Int = MIN_WORDS,
    private val dominance: Int = DOMINANCE,
) : LanguageDetector {

    override fun detect(samples: List<AsrSegment>): Language = tally(samples).language

    override fun settled(samples: List<AsrSegment>): Boolean = tally(samples).settled

    /** Считает слова каждого алфавита и принимает решение. */
    fun tally(samples: List<AsrSegment>): LanguageTally {
        var cyrillic = 0
        var latin = 0
        for (sample in samples) {
            for (word in WORD.findAll(sample.text.lowercase())) {
                if (word.value.any { it in 'а'..'я' || it == 'ё' }) cyrillic++ else latin++
            }
        }
        val leader = maxOf(cyrillic, latin)
        val other = minOf(cyrillic, latin)
        val settled = leader >= minWords && leader >= dominance * other
        val language = if (latin > cyrillic) Language.EN else Language.RU
        return LanguageTally(cyrillic, latin, samples.size, settled, language)
    }

    private companion object {
        const val MIN_WORDS = 8
        const val DOMINANCE = 4

        val WORD = Regex("[а-яёa-z]['а-яёa-z]*")
    }
}
