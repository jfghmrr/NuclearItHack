package ru.stenokot.core.lang

import ru.stenokot.core.engine.LanguageDetector
import ru.stenokot.core.model.AsrSegment
import ru.stenokot.core.model.Language

/** Голоса служебных слов для metadata.json. */
data class LanguageTally(val ru: Int, val en: Int, val enChunks: Int, val chunks: Int, val language: Language)

/**
 * Определяет язык файла по служебным словам в пробных срезах русского маршрута. Доля латиницы
 * не используется: модель пишет английские термины латиницей и в русской речи. Английский
 * выбирается только при уверенном перевесе, иначе остаётся русский. Пороги не откалиброваны.
 */
class LanguageVote(
    private val minChunkVotes: Int = MIN_CHUNK_VOTES,
    private val dominance: Int = DOMINANCE,
) : LanguageDetector {

    override fun detect(samples: List<AsrSegment>): Language = tally(samples).language

    /** Считает голоса и принимает решение. */
    fun tally(samples: List<AsrSegment>): LanguageTally {
        var ru = 0
        var en = 0
        var enChunks = 0
        for (sample in samples) {
            val words = WORD.findAll(sample.text.lowercase()).map { it.value }.toList()
            val chunkRu = words.count { it in RUSSIAN }
            val chunkEn = words.count { it in ENGLISH }
            ru += chunkRu
            en += chunkEn
            if (chunkEn >= minChunkVotes && chunkEn >= dominance * chunkRu) enChunks++
        }
        val confident = samples.isNotEmpty() && enChunks * 3 >= samples.size * 2
        return LanguageTally(ru, en, enChunks, samples.size, if (confident) Language.EN else Language.RU)
    }

    private companion object {
        const val MIN_CHUNK_VOTES = 3
        const val DOMINANCE = 3

        val WORD = Regex("[а-яёa-z']+")

        val RUSSIAN = setOf(
            "и", "в", "во", "не", "на", "я", "что", "он", "с", "со", "как", "а", "то", "все", "всё", "она",
            "так", "его", "но", "да", "ты", "к", "у", "же", "вы", "за", "бы", "по", "только", "ее", "её",
            "мне", "было", "вот", "от", "меня", "еще", "ещё", "нет", "о", "из", "ему", "когда", "даже",
            "ну", "ли", "если", "уже", "или", "быть", "был", "до", "вас", "для", "мы", "это", "там", "они",
            "их", "чем", "тоже", "потому", "который", "которые", "надо", "очень",
        )

        val ENGLISH = setOf(
            "the", "of", "and", "to", "a", "in", "is", "it", "you", "that", "he", "was", "for", "on", "are",
            "with", "as", "they", "be", "at", "have", "this", "from", "or", "had", "by", "but", "what",
            "we", "when", "your", "can", "said", "there", "use", "an", "each", "which", "she", "do", "how",
            "their", "if", "will", "not", "so", "about", "would", "i'm", "don't", "it's", "i",
        )
    }
}
