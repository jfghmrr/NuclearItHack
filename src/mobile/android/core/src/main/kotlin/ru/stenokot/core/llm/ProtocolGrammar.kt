package ru.stenokot.core.llm

/**
 * Грамматика GBNF ответа модели. Правило ref строится из настоящих номеров реплик окна, поэтому
 * ссылка на несуществующую реплику невозможна на уровне выборки токенов.
 */
object ProtocolGrammar {
    /** Строит грамматику для окна. */
    fun build(window: LlmWindow): String = build(window.utterances.map { it.id })

    /** Строит грамматику для номеров реплик [refs]. */
    fun build(refs: List<Int>): String {
        require(refs.isNotEmpty()) { "окно без реплик" }
        val alternatives = refs.distinct().joinToString(" | ") { "\"$it\"" }
        return """
            root  ::= topic item{0,$MAX_ITEMS}
            topic ::= "T: " word (" " word){0,${MAX_TOPIC_WORDS - 1}} "\n"
            item  ::= [DAQ] " " ref "\n"
            ref   ::= $alternatives
            word  ::= [^ \n\t\[\]]{1,$MAX_WORD_LENGTH}
        """.trimIndent() + "\n"
    }

    /** Наибольшее число пунктов в ответе. */
    const val MAX_ITEMS = 3

    private const val MAX_TOPIC_WORDS = 6
    private const val MAX_WORD_LENGTH = 24
}
