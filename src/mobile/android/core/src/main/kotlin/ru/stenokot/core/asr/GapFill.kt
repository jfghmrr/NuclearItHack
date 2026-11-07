package ru.stenokot.core.asr

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.round
import ru.stenokot.core.model.AsrSegment
import ru.stenokot.core.model.AsrToken
import ru.stenokot.core.model.SpeechSegment

/**
 * Параметры дораспознавания пауз внутри среза. Подобраны на отдельных данных: телефонные разговоры,
 * музыка и шум, которые не входят в оценочные наборы.
 *
 * @property minGapMs самая короткая пауза между словами, которую стоит проверять.
 * @property speechProbability вероятность, с которой окно входит в участок для распознавания.
 * @property strongProbability вероятность, с которой окно считается уверенной речью.
 * @property minSpeechMs сколько уверенной речи в паузе нужно, чтобы распознать её заново.
 * @property minLoudnessDb насколько медианное окно уверенной речи должно быть громче фона среза.
 * Под музыкой и сплошным шумом фон высокий, и пауза не трогается: там модель сочиняет слова.
 * @property paddingMs запас вокруг речи паузы. Участок не выходит за саму паузу.
 * @property headGuardMs новое слово не может начинаться раньше этого отступа от конца прежнего слова:
 * там распознаватель повторяет хвост уже записанного слова.
 * @property tailGuardMs то же перед началом следующего слова.
 */
data class GapFillConfig(
    val minGapMs: Long = 1_000,
    val speechProbability: Float = 0.5f,
    val strongProbability: Float = 0.8f,
    val minSpeechMs: Long = 640,
    val minLoudnessDb: Int = 15,
    val paddingMs: Long = 200,
    val headGuardMs: Long = 300,
    val tailGuardMs: Long = 400,
)

/**
 * Звук паузы окнами по [GAP_WINDOW_MS]: вероятность речи и энергия в децибелах. Энергий может быть
 * на одну меньше: неполное последнее окно в них не входит.
 */
class GapAudio(val probabilities: FloatArray, val energiesDb: DoubleArray)

/**
 * Пауза между распознанными словами одного среза.
 *
 * @property startMs конец предыдущего слова или начало среза.
 * @property endMs начало следующего слова или конец среза.
 * @property insertAt позиция в списке токенов среза, куда встают новые слова. Выбрана так, что знаки
 * препинания остаются при своих прежних словах.
 */
data class SpeechGap(val startMs: Long, val endMs: Long, val insertAt: Int)

/** Длительность окна определителя речи: 512 отсчётов при 16 кГц. */
const val GAP_WINDOW_MS = 32L

/**
 * Дораспознавание пауз. В длинном срезе с одним громким голосом модель пропускает тихую короткую
 * реплику второго человека, хотя тот же участок отдельно распознаётся. Паузы между словами, где
 * определитель речи слышит речь, распознаются заново отдельно, и найденные слова встают в срез
 * со своим временем. Прежние токены не меняются, не удаляются и не переставляются.
 */
object GapFill {
    /**
     * Дополняет срез [segment]. [floorDb] это фон среза, посчитанный по его отсчётам функцией
     * floorDb. [listen] отдаёт звук отрезка записи в миллисекундах, [decode] распознаёт отрезок и
     * возвращает токены с временем от начала записи. Каждая пауза распознаётся один раз, новые паузы
     * внутри вставок не ищутся.
     */
    fun fill(
        segment: AsrSegment,
        config: GapFillConfig,
        floorDb: Double,
        listen: (startMs: Long, endMs: Long) -> GapAudio,
        decode: (SpeechSegment) -> AsrSegment,
    ): AsrSegment {
        var result = segment
        for (gap in findGaps(segment, config).asReversed()) {
            val span = speechSpan(gap, listen(gap.startMs, gap.endMs), floorDb, config) ?: continue
            val decoded = decode(SpeechSegment(segment.segment.index, span.first * SAMPLES_PER_MS, span.last * SAMPLES_PER_MS))
            result = insert(result, gap, decoded, config)
        }
        return result
    }

    /** Энергия полных окон по 512 отсчётов в децибелах. Тишина ограничена снизу -100 дБ. */
    fun windowEnergiesDb(samples: FloatArray): DoubleArray {
        val count = samples.size / WINDOW_SAMPLES
        return DoubleArray(count) { index ->
            var sum = 0.0
            for (k in index * WINDOW_SAMPLES until (index + 1) * WINDOW_SAMPLES) sum += samples[k].toDouble() * samples[k]
            10 * log10(maxOf(sum / WINDOW_SAMPLES, MIN_POWER))
        }
    }

    /** Фон среза: десятый процентиль энергии окон с линейной интерполяцией. */
    fun floorDb(samples: FloatArray): Double {
        val energies = windowEnergiesDb(samples).sorted()
        if (energies.isEmpty()) return SILENCE_DB
        val position = FLOOR_QUANTILE * (energies.size - 1)
        val lower = floor(position).toInt()
        val upper = minOf(lower + 1, energies.size - 1)
        return energies[lower] + (energies[upper] - energies[lower]) * (position - lower)
    }

    /**
     * Паузы не короче [GapFillConfig.minGapMs] между словами с буквами, а также в начале и в конце
     * среза. Срез без слов пауз не имеет: его повторное распознавание дало бы то же самое. Пауза
     * пропускается, если вставка в неё изменила бы, к какому слову пристанут знаки препинания.
     */
    fun findGaps(segment: AsrSegment, config: GapFillConfig): List<SpeechGap> {
        val groups = groupsOf(segment.tokens)
        val words = groups.indices.filter { groups[it].hasLetters }
        if (words.isEmpty()) return emptyList()
        val chunkStartMs = samplesToMs(segment.segment.startSample)
        val chunkEndMs = samplesToMs(segment.segment.endSample)
        val out = ArrayList<SpeechGap>()

        val first = groups[words.first()]
        val leading = groups.subList(0, words.first())
        if (first.letterStartMs - chunkStartMs >= config.minGapMs &&
            leading.all { it.openingOnly || it.empty } && segment.tokens[groups.first().from].startsWord()
        ) {
            out += SpeechGap(chunkStartMs, first.letterStartMs, 0)
        }

        var previousEndMs = first.letterEndMs
        for (k in 1 until words.size) {
            val left = words[k - 1]
            val right = words[k]
            val next = groups[right]
            if (next.letterStartMs - previousEndMs >= config.minGapMs) {
                insertionPoint(groups, left, right)?.let { out += SpeechGap(previousEndMs, next.letterStartMs, it) }
            }
            previousEndMs = maxOf(previousEndMs, next.letterEndMs)
        }

        val trailing = groups.subList(words.last() + 1, groups.size)
        if (chunkEndMs - previousEndMs >= config.minGapMs && trailing.none { it.openingOnly }) {
            out += SpeechGap(previousEndMs, chunkEndMs, segment.tokens.size)
        }
        return out
    }

    /**
     * Отрезок для повторного распознавания: от первого до последнего окна речи с запасом
     * [GapFillConfig.paddingMs], не шире самой паузы. Null, если уверенной речи меньше
     * [GapFillConfig.minSpeechMs] или она тише [GapFillConfig.minLoudnessDb] над фоном [floorDb].
     */
    fun speechSpan(gap: SpeechGap, audio: GapAudio, floorDb: Double, config: GapFillConfig): LongRange? {
        val probabilities = audio.probabilities
        var first = -1
        var last = -1
        var strong = 0
        val loudness = ArrayList<Int>()
        for (index in probabilities.indices) {
            val probability = probabilities[index]
            if (probability >= config.strongProbability) {
                strong++
                if (index < audio.energiesDb.size) loudness += round(audio.energiesDb[index] - floorDb).toInt()
            }
            if (probability < config.speechProbability) continue
            if (first < 0) first = index
            last = index
        }
        if (first < 0 || strong * GAP_WINDOW_MS < config.minSpeechMs || loudness.isEmpty()) return null
        loudness.sort()
        if (loudness[loudness.size / 2] < config.minLoudnessDb) return null
        val startMs = maxOf(gap.startMs, gap.startMs + first * GAP_WINDOW_MS - config.paddingMs)
        val endMs = minOf(gap.endMs, gap.startMs + (last + 1) * GAP_WINDOW_MS + config.paddingMs)
        return if (endMs > startMs) startMs..endMs else null
    }

    /**
     * Вставляет в паузу [gap] слова из [decoded], которые начинаются внутри паузы с отступами
     * [GapFillConfig.headGuardMs] и [GapFillConfig.tailGuardMs]. Знаки препинания идут только вслед
     * за принятым словом. Текст среза пересобирается из токенов, если что-то вставлено.
     */
    fun insert(segment: AsrSegment, gap: SpeechGap, decoded: AsrSegment, config: GapFillConfig): AsrSegment {
        val accepted = ArrayList<AsrToken>()
        var open = false
        for (group in groupsOf(decoded.tokens)) {
            if (group.hasLetters) {
                open = group.letterStartMs >= gap.startMs + config.headGuardMs &&
                    group.letterStartMs < gap.endMs - config.tailGuardMs
            }
            if (open && !group.empty) accepted += decoded.tokens.subList(group.from, group.to)
        }
        if (accepted.isEmpty()) return segment
        if (!accepted.first().startsWord()) accepted[0] = accepted[0].copy(text = " " + accepted[0].text)
        val tokens = ArrayList<AsrToken>(segment.tokens.size + accepted.size)
        tokens += segment.tokens.subList(0, gap.insertAt)
        tokens += accepted
        tokens += segment.tokens.subList(gap.insertAt, segment.tokens.size)
        return segment.copy(text = tokens.joinToString("") { it.text }.trim(), tokens = tokens)
    }

    /**
     * Позиция вставки между словами [left] и [right]. Закрывающие и прочие знаки между ними пристают
     * к левому слову, открывающие к правому. Вставка возможна, только если сначала идут первые,
     * потом вторые.
     */
    private fun insertionPoint(groups: List<TokenGroup>, left: Int, right: Int): Int? {
        var cut = left + 1
        for (index in left + 1 until right) {
            if (!groups[index].openingOnly && !groups[index].empty) cut = index + 1
        }
        for (index in left + 1 until cut) {
            if (groups[index].openingOnly) return null
        }
        return groups[cut].from
    }

    /**
     * Делит токены на группы так же, как [rawWords] делит их на слова: группа начинается с токена
     * с пробелом или меткой слова. Служебные токены в группы не входят, но позиции считаются по
     * исходному списку.
     */
    private fun groupsOf(tokens: List<AsrToken>): List<TokenGroup> {
        val out = ArrayList<TokenGroup>()
        var current: TokenGroup? = null
        for ((index, token) in tokens.withIndex()) {
            val raw = token.text
            if (raw.isEmpty() || raw.trim() in SERVICE) {
                current?.to = index + 1
                continue
            }
            if (current == null || raw.startsWord()) {
                current = TokenGroup(index).also { out += it }
            }
            current.to = index + 1
            val body = raw.trim(' ', WORD_MARK)
            if (body.isEmpty()) continue
            current.text.append(body)
            if (body.any(Char::isLetterOrDigit)) {
                if (current.letterStartMs < 0) current.letterStartMs = token.startMs
                current.letterEndMs = token.startMs + FRAME_MS
            }
        }
        return out
    }

    private fun String.startsWord(): Boolean = isNotEmpty() && (this[0] == ' ' || this[0] == WORD_MARK)

    private fun AsrToken.startsWord(): Boolean = text.startsWord()

    /** Группа токенов одного слова: позиции в исходном списке и время по буквам, как в [rawWords]. */
    private class TokenGroup(val from: Int) {
        var to: Int = from + 1
        val text = StringBuilder()
        var letterStartMs = -1L
        var letterEndMs = -1L
        val hasLetters: Boolean get() = letterStartMs >= 0
        val empty: Boolean get() = text.isEmpty()
        val openingOnly: Boolean get() = text.isNotEmpty() && text.all { it in OPENING }
    }

    private const val SAMPLES_PER_MS = 16L
    private const val WINDOW_SAMPLES = 512
    private const val MIN_POWER = 1e-10
    private const val SILENCE_DB = -100.0
    private const val FLOOR_QUANTILE = 0.1
    private const val FRAME_MS = 40L
    private const val WORD_MARK = '▁'
    private val SERVICE = setOf("<unk>", "<blk>", "<s>", "</s>", "<pad>")
    private val OPENING = "«([{„“‘".toSet()
}
