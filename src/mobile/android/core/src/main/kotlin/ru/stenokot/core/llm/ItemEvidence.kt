package ru.stenokot.core.llm

import ru.stenokot.core.model.Utterance

/** Реплики окна, которые по своему тексту могут стать пунктом протокола каждого вида. */
data class ItemCandidates(val decisions: List<Int>, val actions: List<Int>, val questions: List<Int>) {
    /** Номера реплик для вида [kind]. */
    fun of(kind: ProtocolKind): List<Int> =
        when (kind) {
            ProtocolKind.DECISION -> decisions
            ProtocolKind.ACTION -> actions
            ProtocolKind.QUESTION -> questions
        }
}

/**
 * Проверка пунктов по тексту реплики. Модель малого размера сама заполняет все виды пунктов, поэтому
 * вид реплики подтверждается её словами: решение и поручение прямым оборотом, открытый вопрос знаком
 * вопроса и отсутствием ответа. Лучше пропустить пункт, чем показать на защите ложный.
 */
object ItemEvidence {
    private const val MIN_CONTENT_WORDS = 3
    private const val MIN_CONTENT_LENGTH = 3
    private const val MAX_UNRESOLVED_REPLY_WORDS = 8

    private val SENTENCE_END = Regex("(?<=[.!?…])\\s+")
    private val DISABLING = Regex(" (не|если|чтобы|он|она|они)(?= )")

    private val INDEFINITE_ENDINGS = listOf("-то", "-нибудь", "-либо")

    private val DIRECT_ANSWERS = setOf("да", "нет", "yes", "no")

    private val FILLERS = setOf("э", "эм", "эээ", "м", "мм", "ммм", "хм", "гм", "ага", "угу", "ну", "вот", "uh", "um", "hmm")

    private val FUNCTION_WORDS = setOf(
        "это", "эта", "этот", "эти", "что", "как", "так", "там", "тут", "где", "когда", "тогда", "потом", "если",
        "чтобы", "потому", "поэтому", "уже", "еще", "тоже", "также", "очень", "просто", "можно", "нужно", "надо",
        "будет", "было", "были", "была", "быть", "есть", "вообще", "значит", "соответственно", "короче", "типа",
        "кстати", "конечно", "наверное", "может", "могут", "можем", "могу", "хочу", "знаю", "думаю", "кажется",
        "даже", "только", "лишь", "вроде", "именно", "сейчас", "теперь", "опять", "снова", "хорошо", "ладно",
        "спасибо", "привет", "чтоб", "ведь", "будто", "вдруг", "этого", "этой", "этом", "этим", "того", "тому", "вашего",
        "вашей", "вашу", "вашим", "нашего", "нашей", "нашу", "нашим", "моего", "моей", "твоего", "твоей", "своего",
        "своей", "согласен", "согласна", "который", "которая", "которое", "которые", "такой", "такая",
        "такое", "такие", "какой", "какая", "какое", "какие", "какими", "какой-то", "какая-то", "какие-то",
        "какими-то", "какую-то", "наш", "наша", "наше", "наши", "ваш", "ваша", "ваше", "ваши", "мой", "моя", "мои",
        "твой", "свой", "свою", "все", "всего", "всех", "вся", "весь", "для", "без", "про", "при", "над", "под",
        "через", "или", "они", "она", "оно", "его", "ему", "нас", "вас", "вам", "нам", "тебе", "меня", "мне",
        "the", "and", "but", "for", "that", "this", "with", "you", "are", "was", "were", "have", "has", "not", "yes",
        "okay", "well", "just", "really", "very", "from", "they", "them", "what", "about",
    )

    private val DECISION_CUES = listOf(
        " решили", " решено", " договорились", " договоримся", " утвердили", " утверждаем", " утвердим", " утвержден",
        " принимаем", " приняли", " принято", " согласовали", " согласован", " выбрали", " выбираем", " остановимся",
        " остановились", " отказываемся", " отказались", " переносим", " перенесли", " подпишем", " закупаем",
        " закрываем", " постановили", " окончательно", " берем", " we decided", " decided to", " we agreed",
        " agreed to", " approved", " go with",
    )

    private val ACTION_CUES = listOf(
        " поручаю", " поручено", " поручим", " поручили", " подготовь", " сделай", " проверь", " пришлите", " отправь",
        " займись", " займитесь", " свяжись", " свяжитесь", " согласуй", " запросите", " оформите", " закажи",
        " разошлите", " напиши", " возьми на себя", " возьму на себя", " беру на себя", " сделаю", " подготовлю",
        " пришлю", " отправлю", " займусь", " проверю", " ответственный", " ответственная", " please", " i will",
        " i'll", " assign", " responsible for", " take care of", " follow up",
    )

    private val NEED_CUES = listOf(" необходимо", " нужно", " надо", " должен", " должна", " должны", " обязан", " need to", " must", " have to")

    private val ADDRESSEE_CUES = listOf(" вам ", " тебе ", " вы ", " ты ", " вас ", " you ")

    private val DEADLINE_CUES = listOf(
        " до понедельника", " до вторника", " до среды", " до четверга", " до пятницы", " до субботы", " до конца",
        " к понедельнику", " ко вторнику", " к среде", " к четвергу", " к пятнице", " завтра", " by monday",
        " by tuesday", " by wednesday", " by thursday", " by friday", " tomorrow",
    )

    private val GENERAL_REMARK_CUES = listOf(" понимать", " сказать", " признать", " отметить", " заметить", " помнить", " учитывать")

    private val UNRESOLVED_CUES = listOf(
        " не знаю", " не закрыли", " не решили", " не решен", " не ясно", " неясно", " непонятно", " неизвестно",
        " уточн", " выясн", " вопрос открыт", " остается открыт", " подумаем", " посмотрим", " разберемся",
        " don't know", " not sure", " unclear", " find out",
    )

    /** Значимые слова текста: без междометий и служебных слов. */
    fun contentWords(text: String): List<String> =
        normalizedWords(text).filter { it.length >= MIN_CONTENT_LENGTH && !isFiller(it) && it !in FUNCTION_WORDS }

    /** Междометие: из словаря или повтор одного слога через дефис, как в «и-и-и». */
    fun isFiller(word: String): Boolean = word in FILLERS || ('-' in word && word.split('-').toSet().size == 1)

    /** Служебное слово, на которое тема не может опираться. Сюда входят и неопределённые слова вроде «что-то». */
    fun isFunctionWord(word: String): Boolean = word in FUNCTION_WORDS || INDEFINITE_ENDINGS.any(word::endsWith)

    /** Находит реплики окна с подтверждением в тексте. Слишком короткие реплики в пункты не попадают. */
    fun candidates(window: LlmWindow): ItemCandidates {
        val decisions = ArrayList<Int>()
        val actions = ArrayList<Int>()
        val questions = ArrayList<Int>()
        window.utterances.forEachIndexed { position, utterance ->
            if (contentWords(utterance.text).size >= MIN_CONTENT_WORDS) {
                val statements = statements(utterance.text)
                if (statements.any(::isDecision)) decisions += utterance.id
                if (statements.any(::isAction)) actions += utterance.id
                if (isOpenQuestion(utterance, window.utterances.getOrNull(position + 1))) questions += utterance.id
            }
        }
        return ItemCandidates(decisions, actions, questions)
    }

    private fun statements(text: String): List<String> =
        text.split(SENTENCE_END).filterNot { it.trimEnd().endsWith('?') }.map(::cueText)

    /**
     * Текст для поиска оборотов. Слово после отрицания и условия склеивается с ним: «не решили» и
     * «если договоримся» не должны считаться решением.
     */
    private fun cueText(text: String): String =
        " ${normalizedWords(text).joinToString(" ")} ".replace(DISABLING) { "${it.value}_" }.replace("_ ", "_")

    private fun isDecision(statement: String): Boolean = DECISION_CUES.any { it in statement }

    private fun isAction(statement: String): Boolean {
        if (ACTION_CUES.any { it in statement }) return true
        val directed = ADDRESSEE_CUES.any { it in statement } || DEADLINE_CUES.any { it in statement }
        return directed && NEED_CUES.any { it in statement } && GENERAL_REMARK_CUES.none { it in statement }
    }

    /**
     * Вопрос открыт, только если это видно из текста: говорящий сам оставил его нерешённым, собеседник
     * коротко ответил, что не знает, или вместо ответа прозвучало одно междометие. Короткий ответ
     * вроде «как опция нужна» закрывает вопрос. Последняя реплика окна проверяется в следующем окне:
     * окна перекрываются на одну реплику.
     */
    private fun isOpenQuestion(utterance: Utterance, next: Utterance?): Boolean {
        val mark = utterance.text.lastIndexOf('?')
        if (mark < 0) return false
        val tail = utterance.text.substring(mark + 1)
        if (contentWords(tail).isNotEmpty()) return isUnresolved(tail)
        if (next == null) return false
        val reply = contentWords(next.text)
        if (reply.size <= MAX_UNRESOLVED_REPLY_WORDS && isUnresolved(next.text)) return true
        return next.speaker != utterance.speaker && reply.isEmpty() && normalizedWords(next.text).none { it in DIRECT_ANSWERS }
    }

    private fun isUnresolved(text: String): Boolean {
        val plain = " ${normalizedWords(text).joinToString(" ")} "
        return UNRESOLVED_CUES.any { it in plain }
    }
}
