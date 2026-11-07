package ru.stenokot.core.llm

import kotlin.math.abs
import kotlin.math.ceil
import ru.stenokot.core.contract.speakerId
import ru.stenokot.core.model.Language
import ru.stenokot.core.model.Utterance

/** Окно расшифровки для одного запроса к модели. Граница всегда идёт между репликами. */
data class LlmWindow(val index: Int, val utterances: List<Utterance>) {
    /** Начало первой реплики окна. */
    val startMs: Long
        get() = utterances.first().startMs

    /** Самый поздний конец реплики окна. */
    val endMs: Long
        get() = utterances.maxOf { it.endMs }

    /** Текст окна в одну строку, для проверки темы и алфавита. */
    val text: String
        get() = utterances.joinToString(" ") { it.text }
}

/** Разбиение расшифровки на окна и выбор окон, равномерный по времени записи. */
object LlmWindows {
    /** Записи длиннее этого значения получают окна меньшего размера. */
    const val SHORT_RECORDING_MS = 15 * 60_000L

    /** Размер окна в токенах для обычной записи. */
    const val WINDOW_TOKENS = 450

    /** Размер окна в токенах для длинной записи. */
    const val LONG_WINDOW_TOKENS = 300

    /** Меньшее окно уже не имеет смысла отправлять в модель. */
    const val MIN_WINDOW_TOKENS = 100

    private const val TOKENS_PER_WORD_RU = 2.32
    private const val TOKENS_PER_WORD_EN = 1.22
    private const val LINE_OVERHEAD_TOKENS = 9
    private const val SPARSE_MIN_DURATION_MS = 30_000L
    private const val SPARSE_WORDS_PER_MINUTE = 30.0

    /** Строка реплики в сообщении модели. */
    fun line(utterance: Utterance): String =
        "[${utterance.id}] ${speakerId(utterance.speaker)}: ${utterance.text}"

    /** Предел токенов окна для записи длительностью [audioMs]. */
    fun maxTokens(audioMs: Long): Int = if (audioMs <= SHORT_RECORDING_MS) WINDOW_TOKENS else LONG_WINDOW_TOKENS

    /** Оценка числа токенов реплики в окне. Настоящий токенизатор доступен только внутри модели. */
    fun estimateTokens(utterance: Utterance, language: Language): Int {
        val perWord = if (language == Language.EN) TOKENS_PER_WORD_EN else TOKENS_PER_WORD_RU
        return ceil(utterance.words.size.coerceAtLeast(1) * perWord).toInt() + LINE_OVERHEAD_TOKENS
    }

    /** Делит реплики на окна с перекрытием в одну реплику. Реплика не делится никогда. */
    fun split(utterances: List<Utterance>, maxTokens: Int, tokens: (Utterance) -> Int): List<LlmWindow> {
        val out = ArrayList<LlmWindow>()
        var from = 0
        while (from < utterances.size) {
            var used = 0
            var until = from
            while (until < utterances.size) {
                val next = tokens(utterances[until])
                if (until > from && used + next > maxTokens) break
                used += next
                until++
            }
            out += LlmWindow(out.size, utterances.subList(from, until).toList())
            if (until >= utterances.size) break
            from = maxOf(until - 1, from + 1)
        }
        return out
    }

    /** Окно с редкой речью: чаще всего музыка или шум, текст там ненадёжен. */
    fun isSparse(window: LlmWindow): Boolean {
        val durationMs = window.endMs - window.startMs
        if (durationMs < SPARSE_MIN_DURATION_MS) return false
        val words = window.utterances.sumOf { it.words.size }
        return words / (durationMs / 60_000.0) < SPARSE_WORDS_PER_MINUTE
    }

    /** Убирает окна с редкой речью, если после этого что-то остаётся. */
    fun withoutSparse(windows: List<LlmWindow>): List<LlmWindow> =
        windows.filterNot(::isSparse).ifEmpty { windows }

    /** Выбирает [count] окон равномерно по времени записи, а не по их номерам. */
    fun selectEvenly(windows: List<LlmWindow>, count: Int): List<LlmWindow> {
        if (count <= 0 || windows.isEmpty()) return emptyList()
        if (count >= windows.size) return windows
        val firstMs = windows.first().startMs
        val spanMs = (windows.maxOf { it.endMs } - firstMs).toDouble()
        val free = windows.toMutableList()
        val chosen = ArrayList<LlmWindow>(count)
        for (slot in 0 until count) {
            val target = firstMs + spanMs * (slot + 0.5) / count
            val best = free.minBy { abs((it.startMs + it.endMs) / 2.0 - target) }
            free.remove(best)
            chosen += best
        }
        return chosen.sortedBy { it.index }
    }

    /**
     * Порядок обработки, при котором любая начальная часть покрывает запись равномерно: сначала
     * середина, затем середины половин и так далее. Бюджет может закончиться на любом окне.
     */
    fun coverageOrder(windows: List<LlmWindow>): List<LlmWindow> {
        val out = ArrayList<LlmWindow>(windows.size)
        val ranges = ArrayList<IntRange>()
        ranges += windows.indices
        var head = 0
        while (head < ranges.size) {
            val range = ranges[head++]
            if (range.isEmpty()) continue
            val middle = (range.first + range.last) / 2
            out += windows[middle]
            ranges += range.first until middle
            ranges += (middle + 1)..range.last
        }
        return out
    }
}
