package ru.stenokot.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.stenokot.core.model.AsrSegment
import ru.stenokot.core.model.AsrToken
import ru.stenokot.core.model.Language
import ru.stenokot.core.model.SpeakerTurn
import ru.stenokot.core.model.SpeechSegment
import ru.stenokot.core.model.Utterance
import ru.stenokot.core.model.Word
import java.io.File

/** Настройки служебных файлов задачи. Контрактный файл их не использует. */
val StoreJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** Срез записи в vad.json. */
@Serializable
data class SegmentLine(val i: Int, val s: Long, val e: Long)

/** Содержимое vad.json. */
@Serializable
data class VadDocument(val segments: List<SegmentLine>)

/** Содержимое lang.json. Текст пробных срезов не хранится: он уже есть в журнале ASR. */
@Serializable
data class LangDocument(val language: Language, val failed: Boolean, val probes: Int)

/** Токен журнала ASR. */
@Serializable
data class TokenLine(val t: String, val ms: Long)

/** Строка asr.jsonl: один завершённый срез. Пропущенный срез отмечен, чтобы не повторять сбой. */
@Serializable
data class AsrLine(
    val i: Int,
    val s: Long,
    val e: Long,
    val text: String = "",
    val tokens: List<TokenLine> = emptyList(),
    val skipped: Boolean = false,
)

/** Интервал говорящего в diar.jsonl. */
@Serializable
data class TurnLine(val s: Long, val e: Long, val k: Int, val c: Float? = null)

/** Строка diar.jsonl: завершённое окно. Строка с [FINAL_WINDOW] содержит итоговый список. */
@Serializable
data class DiarLine(
    val i: Int,
    val turns: List<TurnLine> = emptyList(),
    val state: String? = null,
    val skipped: Boolean = false,
)

/** Номер строки diar.jsonl с итогом всей стадии. */
const val FINAL_WINDOW: Int = -1

/** Слово в merged.json. */
@Serializable
data class WordLine(val t: String, val s: Long, val e: Long, val k: Int)

/** Реплика в merged.json. */
@Serializable
data class UtteranceLine(
    val id: Int,
    val speaker: Int,
    val startMs: Long,
    val endMs: Long,
    val words: List<WordLine>,
    val text: String,
    val overlap: Boolean = false,
)

/** Содержимое merged.json: неизменяемая база для контракта, интерфейса и протокола. */
@Serializable
data class MergedDocument(val utterances: List<UtteranceLine>)

/** Переводит срез записи в строку журнала. */
fun SpeechSegment.toLine(): SegmentLine = SegmentLine(index, startSample, endSample)

/** Восстанавливает срез записи из строки журнала. */
fun SegmentLine.toSegment(): SpeechSegment = SpeechSegment(i, s, e)

/** Переводит результат распознавания в строку журнала. */
fun AsrSegment.toLine(): AsrLine = AsrLine(
    i = segment.index,
    s = segment.startSample,
    e = segment.endSample,
    text = text,
    tokens = tokens.map { TokenLine(it.text, it.startMs) },
)

/** Восстанавливает результат распознавания из строки журнала. */
fun AsrLine.toSegment(): AsrSegment = AsrSegment(
    segment = SpeechSegment(i, s, e),
    text = text,
    tokens = tokens.map { AsrToken(it.t, it.ms) },
)

/** Переводит интервал говорящего в строку журнала. */
fun SpeakerTurn.toLine(): TurnLine = TurnLine(startMs, endMs, speaker, confidence)

/** Восстанавливает интервал говорящего из строки журнала. */
fun TurnLine.toTurn(): SpeakerTurn = SpeakerTurn(s, e, k, c)

/** Переводит реплику в форму merged.json. */
fun Utterance.toLine(): UtteranceLine = UtteranceLine(
    id = id,
    speaker = speaker,
    startMs = startMs,
    endMs = endMs,
    words = words.map { WordLine(it.text, it.startMs, it.endMs, it.speaker) },
    text = text,
    overlap = overlap,
)

/** Восстанавливает реплику из merged.json. */
fun UtteranceLine.toUtterance(): Utterance = Utterance(
    id = id,
    speaker = speaker,
    startMs = startMs,
    endMs = endMs,
    words = words.map { Word(it.t, it.s, it.e, it.k) },
    text = text,
    overlap = overlap,
)

/** Журнал asr.jsonl. */
class AsrJournal(file: File) {
    private val journal = LineJournal(file)

    /** Возвращает завершённые срезы и обрезает повреждённый хвост. */
    fun restore(): List<AsrLine> = journal.restore { line ->
        runCatching { StoreJson.decodeFromString<AsrLine>(line) }.getOrNull()
    }

    /** Дописывает завершённый срез. */
    fun append(line: AsrLine) = journal.append(StoreJson.encodeToString(line))

    /** Удаляет журнал. */
    fun clear() = journal.clear()
}

/** Журнал diar.jsonl. */
class DiarJournal(file: File) {
    private val journal = LineJournal(file)

    /** Возвращает завершённые окна и обрезает повреждённый хвост. */
    fun restore(): List<DiarLine> = journal.restore { line ->
        runCatching { StoreJson.decodeFromString<DiarLine>(line) }.getOrNull()
    }

    /** Дописывает завершённое окно. */
    fun append(line: DiarLine) = journal.append(StoreJson.encodeToString(line))

    /** Удаляет журнал. */
    fun clear() = journal.clear()
}

/**
 * Счётчик попыток среза. Живёт в файле, потому что падение нативного кода убивает процесс:
 * без файла тот же срез повторялся бы вечно.
 */
class AttemptLog(private val file: File, private val limit: Int = 2) {
    /**
     * Отмечает начало попытки для [index]. Возвращает false, если лимит исчерпан и срез
     * надо пропустить.
     */
    fun begin(index: Int): Boolean {
        val previous = read()
        val attempts = if (previous?.first == index) previous.second + 1 else 1
        if (attempts > limit) return false
        file.parentFile?.mkdirs()
        file.writeText("$index $attempts")
        return true
    }

    private fun read(): Pair<Int, Int>? {
        if (!file.exists()) return null
        val parts = runCatching { file.readText().trim().split(' ') }.getOrNull() ?: return null
        val index = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val attempts = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return index to attempts
    }
}
