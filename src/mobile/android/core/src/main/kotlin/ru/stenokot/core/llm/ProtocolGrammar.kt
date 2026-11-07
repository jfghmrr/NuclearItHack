package ru.stenokot.core.llm

/**
 * Грамматика GBNF ответа модели. Тема может быть только цитатой из окна: от двух до шести слов подряд
 * внутри одной части предложения, с первым и последним значимым словом. Придумать слово или
 * повторить пример из запроса модель не может на уровне выборки токенов. Номера пунктов берутся из
 * [ItemEvidence]: ссылка на несуществующую реплику и вид без подтверждения в тексте тоже невозможны.
 */
object ProtocolGrammar {
    /** Наибольшее число пунктов в ответе. */
    const val MAX_ITEMS = 3

    /** Наибольшее число слов темы. */
    const val MAX_TOPIC_WORDS = 6

    /** Наименьшее число слов темы. */
    const val MIN_TOPIC_WORDS = 2

    private val CLAUSE_BREAK = Regex("[.,!?;:()«»\"…—–]+")
    private val QUOTE_WORD = Regex("[\\p{L}\\p{N}]+(?:['’-][\\p{L}\\p{N}]+)*")
    private const val MIN_SIGNIFICANT_LENGTH = 4
    private const val NO_TOPIC = "\"-\""

    /** Строит грамматику для окна. */
    fun build(window: LlmWindow): String {
        val rules = ArrayList<String>()
        val starts = ArrayList<String>()
        quoteRuns(window).forEachIndexed { run, words -> addQuotes(run, words, starts, rules) }
        val items = itemRule(ItemEvidence.candidates(window))
        val root = if (items == null) "root ::= topic" else "root ::= topic item{0,$MAX_ITEMS}\nitem ::= $items"
        val head = "$root\ntopic ::= \"T: \" phrase \"\\n\"\nphrase ::= ${starts.ifEmpty { listOf(NO_TOPIC) }.joinToString(" | ")}"
        return (listOf(head) + rules).joinToString("\n") + "\n"
    }

    /** Слова окна подряд: знак препинания и междометие разрывают цитату. */
    internal fun quoteRuns(window: LlmWindow): List<List<String>> =
        window.utterances.flatMap { utterance ->
            CLAUSE_BREAK.split(utterance.text).flatMap { clause ->
                val runs = ArrayList<List<String>>()
                var current = ArrayList<String>()
                for (match in QUOTE_WORD.findAll(clause)) {
                    if (ItemEvidence.isFiller(comparable(match.value))) {
                        runs += current
                        current = ArrayList()
                    } else {
                        current += match.value
                    }
                }
                runs += current
                runs.filter { it.size >= MIN_TOPIC_WORDS }
            }
        }

    private fun comparable(word: String): String = word.lowercase().replace('ё', 'е')

    private fun isSignificant(word: String): Boolean {
        val plain = comparable(word)
        return plain.length >= MIN_SIGNIFICANT_LENGTH && !ItemEvidence.isFunctionWord(plain)
    }

    /** Добавляет цепочки правил, по которым цитата идёт от начального слова к следующим. */
    private fun addQuotes(run: Int, words: List<String>, starts: MutableList<String>, rules: MutableList<String>) {
        val names = HashMap<Pair<Int, Int>, String?>()

        fun tail(index: Int, left: Int): String? = names.getOrPut(index to left) {
            val word = words[index]
            val next = if (left > 0 && index + 1 < words.size) tail(index + 1, left - 1) else null
            val endings = listOfNotNull(
                "\"$word\"".takeIf { isSignificant(word) },
                next?.let { "\"$word \" $it" },
            )
            if (endings.isEmpty()) return@getOrPut null
            val name = "q${run}x${index}x$left"
            rules += "$name ::= ${endings.joinToString(" | ")}"
            name
        }

        for (index in 0 until words.size - 1) {
            if (!isSignificant(words[index])) continue
            tail(index + 1, MAX_TOPIC_WORDS - MIN_TOPIC_WORDS)?.let { starts += "\"${words[index]} \" $it" }
        }
    }

    private fun itemRule(candidates: ItemCandidates): String? =
        ProtocolKind.entries
            .filter { candidates.of(it).isNotEmpty() }
            .joinToString(" | ") { kind ->
                val refs = candidates.of(kind).joinToString(" | ") { "\"$it\"" }
                "\"${kind.letter} \" ($refs) \"\\n\""
            }
            .ifEmpty { null }
}
