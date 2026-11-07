package ru.stenokot.app.data

import kotlinx.serialization.Serializable
import ru.stenokot.core.model.Language
import ru.stenokot.core.model.SpeakerTurn
import ru.stenokot.core.model.SpeechSegment
import ru.stenokot.core.model.Utterance
import ru.stenokot.core.model.Word
import ru.stenokot.core.storage.AsrJournalLine
import ru.stenokot.core.storage.DiarJournalLine
import ru.stenokot.core.storage.ServiceJson
import ru.stenokot.core.storage.encodeJournalLine
import java.io.File

/** Срез записи в vad.json. */
@Serializable
data class SegmentLine(val i: Int, val s: Long, val e: Long)

/** Содержимое vad.json. */
@Serializable
data class VadDocument(val segments: List<SegmentLine>)

/** Содержимое lang.json. Его читает и процесс языковой модели. */
@Serializable
data class LangDocument(val language: Language, val failed: Boolean, val probes: Int)

/** Интервал говорящего в turns.json. */
@Serializable
data class TurnLine(val s: Long, val e: Long, val k: Int, val c: Float? = null)

/** Содержимое turns.json: итог диаризации после склейки окон. */
@Serializable
data class TurnsDocument(val turns: List<TurnLine>)

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

/** Переводит срез записи в строку файла. */
fun SpeechSegment.toLine(): SegmentLine = SegmentLine(index, startSample, endSample)

/** Восстанавливает срез записи из строки файла. */
fun SegmentLine.toSegment(): SpeechSegment = SpeechSegment(i, s, e)

/** Переводит интервал говорящего в строку файла. */
fun SpeakerTurn.toLine(): TurnLine = TurnLine(startMs, endMs, speaker, confidence)

/** Восстанавливает интервал говорящего из строки файла. */
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

/** Журнал asr.jsonl в формате ядра. */
class AsrJournal(file: File) {
    private val journal = LineJournal(file)

    /** Возвращает завершённые срезы и обрезает повреждённый хвост. */
    fun restore(): List<AsrJournalLine> = journal.restore { line ->
        runCatching { ServiceJson.decodeFromString(AsrJournalLine.serializer(), line) }.getOrNull()
    }

    /** Дописывает завершённый срез. */
    fun append(line: AsrJournalLine) =
        journal.append(encodeJournalLine(AsrJournalLine.serializer(), line).trimEnd('\n'))

    /** Удаляет журнал. */
    fun clear() = journal.clear()
}

/** Журнал diar.jsonl в формате ядра. Из него книга говорящих собирается заново. */
class DiarJournal(file: File) {
    private val journal = LineJournal(file)

    /** Возвращает завершённые окна и обрезает повреждённый хвост. */
    fun restore(): List<DiarJournalLine> = journal.restore { line ->
        runCatching { ServiceJson.decodeFromString(DiarJournalLine.serializer(), line) }.getOrNull()
    }

    /** Дописывает завершённое окно. */
    fun append(line: DiarJournalLine) =
        journal.append(encodeJournalLine(DiarJournalLine.serializer(), line).trimEnd('\n'))

    /** Удаляет журнал. */
    fun clear() = journal.clear()
}

/**
 * Счётчик попыток среза или окна. Живёт в файле, потому что падение нативного кода убивает процесс:
 * без файла тот же срез повторялся бы вечно.
 */
class AttemptLog(private val file: File, private val limit: Int = 2) {
    /** Отмечает попытку для [index]. Возвращает false, если лимит исчерпан и пора пропускать. */
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
