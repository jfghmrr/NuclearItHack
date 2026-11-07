package ru.stenokot.app.ui.result

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import ru.stenokot.app.ui.format.formatTimeRange
import ru.stenokot.app.ui.format.formatTimecode
import ru.stenokot.core.model.Language
import ru.stenokot.core.model.Utterance

/**
 * Реплика, готовая к показу. Посчитана вне композиции, строки только читают её. [overlapSpeakers]
 * называет всех, кто звучит одновременно с ней, а null означает, что собеседник по времени не найден.
 */
@Immutable
class TranscriptRowUi(
    val index: Int,
    val id: Int,
    val speaker: Int,
    val startMs: Long,
    val endMs: Long,
    val time: String,
    val startLabel: String,
    val text: AnnotatedString,
    val overlap: Boolean,
    val overlapSpeakers: String? = null,
)

/** Стенограмма, разобранная один раз на запись: строки, индексы и текст для поиска. */
@Immutable
class TranscriptModel(
    val rows: List<TranscriptRowUi>,
    val speakers: List<Int>,
    val repliesBySpeaker: Map<Int, Int>,
    private val folded: List<String>,
    private val starts: LongArray,
    private val indexById: Map<Int, Int>,
) {
    /** Приведённый текст строки [index] для поиска. */
    fun foldedText(index: Int): String = folded[index]

    /** Порядковый номер реплики по её идентификатору. */
    fun indexOfId(id: Int): Int? = indexById[id]

    /**
     * Реплика, звучащая в момент [positionMs]: последняя начавшаяся. В паузе между репликами
     * подсветка остаётся на предыдущей, иначе она мигала бы на каждом вдохе.
     */
    fun indexAt(positionMs: Long): Int {
        var low = 0
        var high = starts.size - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (starts[middle] <= positionMs) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return found
    }
}

/** Строка в текущем виде списка. Группа считается по видимым соседям, а не по всей записи. */
@Immutable
class VisibleRow(
    val row: TranscriptRowUi,
    val startsGroup: Boolean,
    val endsGroup: Boolean,
    val text: AnnotatedString,
)

/** Что сейчас показывает список. */
@Immutable
class TranscriptView(
    val rows: List<VisibleRow>,
    val query: String,
    val speakers: Set<Int>,
    val matchedTimesMs: LongArray,
) {
    /** Список сужен поиском или фильтром. */
    val narrowed: Boolean get() = query.isNotEmpty() || speakers.isNotEmpty()

    /** Позиция в списке для реплики, ближайшей к моменту [positionMs], или -1 для пустого списка. */
    fun nearestPosition(positionMs: Long): Int {
        if (rows.isEmpty()) return -1
        var low = 0
        var high = rows.size - 1
        var found = 0
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (rows[middle].row.startMs <= positionMs) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return found
    }
}

/** Оформление найденного фрагмента. Жирность делает его заметным и без цвета. */
@Immutable
data class MatchStyle(val background: Color, val foreground: Color) {
    /** Стиль для [AnnotatedString]. */
    val span: SpanStyle
        get() = SpanStyle(background = background, color = foreground, fontWeight = FontWeight.W600)
}

/** Внутренний номер говорящего начинается с нуля, в интерфейсе с единицы. */
private fun displayNumber(utterance: Utterance): Int = utterance.speaker + 1

/**
 * Разбирает реплики для показа. Текст не меняется ни на символ. Реплики записи на английском
 * помечаются языком, чтобы TalkBack читал их правильным голосом.
 */
fun buildTranscriptModel(utterances: List<Utterance>, language: Language): TranscriptModel {
    val locale = if (language == Language.EN) LocaleList("en") else null
    val reach = LongArray(utterances.size)
    utterances.forEachIndexed { index, item ->
        reach[index] = if (index == 0) item.endMs else maxOf(reach[index - 1], item.endMs)
    }
    val rows = utterances.mapIndexed { index, item ->
        TranscriptRowUi(
            index = index,
            id = item.id,
            speaker = displayNumber(item),
            startMs = item.startMs,
            endMs = item.endMs,
            time = formatTimeRange(item.startMs, item.endMs),
            startLabel = formatTimecode(item.startMs),
            text = plainText(item.text, locale),
            overlap = item.overlap,
            overlapSpeakers = if (item.overlap) overlapLabel(utterances, reach, index) else null,
        )
    }
    return TranscriptModel(
        rows = rows,
        speakers = rows.map { it.speaker }.distinct().sorted(),
        repliesBySpeaker = rows.groupingBy { it.speaker }.eachCount(),
        folded = utterances.map { foldForSearch(it.text) },
        starts = LongArray(rows.size) { rows[it].startMs },
        indexById = rows.associate { it.id to it.index },
    )
}

/**
 * Подпись наложения: «1 и 3», «1, 2 и 3». Считается по времени реплик соседей, поэтому в ней только те,
 * кто действительно звучит в эти секунды. [reach] хранит самый поздний конец среди реплик до индекса
 * включительно: он останавливает поиск назад, даже если перед ним стоит короткая реплика.
 */
private fun overlapLabel(utterances: List<Utterance>, reach: LongArray, index: Int): String? {
    val item = utterances[index]
    val speakers = sortedSetOf(displayNumber(item))
    var before = index - 1
    while (before >= 0 && reach[before] > item.startMs) {
        val other = utterances[before]
        if (other.endMs > item.startMs) speakers += displayNumber(other)
        before -= 1
    }
    var after = index + 1
    while (after < utterances.size && utterances[after].startMs < item.endMs) {
        speakers += displayNumber(utterances[after])
        after += 1
    }
    if (speakers.size < 2) return null
    val numbers = speakers.map(Int::toString)
    return numbers.dropLast(1).joinToString(", ") + " и " + numbers.last()
}

private fun plainText(text: String, locale: LocaleList?): AnnotatedString =
    if (locale == null) {
        AnnotatedString(text)
    } else {
        buildAnnotatedString { withStyle(SpanStyle(localeList = locale)) { append(text) } }
    }

/** Текст строки с подсветкой вхождений. */
private fun highlighted(row: TranscriptRowUi, matches: IntArray, style: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        append(row.text)
        var pair = 0
        while (pair < matches.size) {
            addStyle(style, matches[pair], matches[pair + 1])
            pair += 2
        }
    }

/**
 * Собирает вид списка. Сужение не меняет порядок реплик. Строка продолжает группу, только если
 * перед ней стоит её настоящая предшественница: иначе разрыв во времени выглядел бы монологом.
 */
fun buildTranscriptView(
    model: TranscriptModel,
    rawQuery: String,
    speakers: Set<Int>,
    style: MatchStyle,
): TranscriptView {
    val query = normalizeQuery(rawQuery)
    val span = style.span
    val picked = ArrayList<VisibleRow>(model.rows.size)
    val times = ArrayList<Long>()
    var previous: TranscriptRowUi? = null
    for (row in model.rows) {
        if (speakers.isNotEmpty() && row.speaker !in speakers) continue
        var text = row.text
        if (query.isNotEmpty()) {
            val matches = findMatches(model.foldedText(row.index), query)
            if (matches.isEmpty()) continue
            text = highlighted(row, matches, span)
            times += row.startMs
        }
        val before = previous
        val startsGroup = before == null || before.speaker != row.speaker || before.index != row.index - 1
        picked += VisibleRow(row, startsGroup, endsGroup = true, text)
        if (!startsGroup) {
            val last = picked.size - 2
            val prior = picked[last]
            picked[last] = VisibleRow(prior.row, prior.startsGroup, endsGroup = false, prior.text)
        }
        previous = row
    }
    return TranscriptView(picked, query, speakers, times.toLongArray())
}
