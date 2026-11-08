package ru.stenokot.core.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.stenokot.core.model.Utterance

/**
 * Ручная правка одной реплики. Поле null означает, что эта часть реплики не менялась. [speaker] это
 * машинный номер говорящего с нуля, а не номер после перенумерации. [startMs] сверяется с машинным
 * результатом, чтобы правка не легла на чужую реплику после повторной обработки.
 */
@Serializable
data class UtteranceEdit(
    val index: Int,
    @SerialName("start_ms") val startMs: Long,
    val text: String? = null,
    val speaker: Int? = null,
)

/**
 * Содержимое edits.json. Машинный результат не меняется, правки лежат рядом. [utterances] это число
 * реплик машинного результата: при несовпадении правки не применяются вовсе.
 */
@Serializable
data class EditsDocument(val utterances: Int, val edits: List<UtteranceEdit> = emptyList())

/**
 * Правки поверх машинного результата. Реплики не добавляются и не удаляются, время не меняется.
 * Без правок байты файла те же, что выдаёт [TranscriptEncoder]. С правками говорящие заново
 * нумеруются по первому появлению, поэтому номера идут без пропусков, даже если правка опустошила
 * говорящего.
 */
object TranscriptEditor {
    private val Spaces = Regex("\\s+")

    /** Правки [edits] относятся именно к этому машинному результату [machine] и что-то меняют. */
    fun applies(machine: List<Utterance>, edits: EditsDocument?): Boolean =
        edits != null &&
            edits.edits.isNotEmpty() &&
            edits.utterances == machine.size &&
            edits.edits.all { it.index in machine.indices && machine[it.index].startMs == it.startMs }

    /** Реплики в том виде, как их видит пользователь. Без применимых правок возвращает [machine] как есть. */
    fun apply(machine: List<Utterance>, edits: EditsDocument?): List<Utterance> {
        if (edits == null || !applies(machine, edits)) return machine
        val byIndex = edits.edits.associateBy { it.index }
        val changed = machine.mapIndexed { index, utterance ->
            val edit = byIndex[index] ?: return@mapIndexed utterance
            utterance.copy(text = edit.text ?: utterance.text, speaker = edit.speaker ?: utterance.speaker)
        }
        return renumber(changed)
    }

    /** Нумерует говорящих по первому появлению, начиная с нуля. */
    fun renumber(utterances: List<Utterance>): List<Utterance> {
        val numbers = HashMap<Int, Int>()
        return utterances.map { it.copy(speaker = numbers.getOrPut(it.speaker) { numbers.size }) }
    }

    /** Байты контрактного файла. Без применимых правок это ровно машинный результат. */
    fun encode(machine: List<Utterance>, edits: EditsDocument?): ByteArray =
        TranscriptEncoder.encode(apply(machine, edits))

    /** Номера изменённых реплик. */
    fun editedIndices(machine: List<Utterance>, edits: EditsDocument?): Set<Int> =
        if (edits != null && applies(machine, edits)) edits.edits.map { it.index }.toSet() else emptySet()

    /** Текст одной строкой: переводы строк и повторные пробелы контракт не пропустил бы. */
    fun normalize(text: String): String = text.trim().replace(Spaces, " ")

    /** Правки после замены текста реплики [index]. Пустой текст запрещён, тогда возвращается null. */
    fun withText(machine: List<Utterance>, edits: EditsDocument?, index: Int, text: String): EditsDocument? {
        val clean = normalize(text)
        if (clean.isEmpty() || index !in machine.indices) return null
        val original = machine[index]
        return change(machine, edits, index) { it.copy(text = clean.takeIf { value -> value != original.text }) }
    }

    /**
     * Правки после смены говорящего реплики [index] на [shown], номер с нуля в том виде, как его видит
     * пользователь. Выбрать можно только говорящего, который уже есть в результате.
     */
    fun withSpeaker(machine: List<Utterance>, edits: EditsDocument?, index: Int, shown: Int): EditsDocument? {
        if (index !in machine.indices) return null
        val current = current(machine, edits)
        val visible = apply(machine, current)
        val position = visible.indexOfFirst { it.speaker == shown }
        if (position < 0) return null
        val raw = rawSpeaker(machine, current, position)
        val original = machine[index]
        return change(machine, current, index) { it.copy(speaker = raw.takeIf { value -> value != original.speaker }) }
    }

    /** Правки без правки реплики [index]: она возвращается к машинному виду. */
    fun reverted(machine: List<Utterance>, edits: EditsDocument?, index: Int): EditsDocument =
        EditsDocument(machine.size, current(machine, edits).edits.filterNot { it.index == index })

    private fun current(machine: List<Utterance>, edits: EditsDocument?): EditsDocument =
        if (edits != null && applies(machine, edits)) edits else EditsDocument(machine.size)

    private fun rawSpeaker(machine: List<Utterance>, edits: EditsDocument, index: Int): Int =
        edits.edits.firstOrNull { it.index == index }?.speaker ?: machine[index].speaker

    private fun change(
        machine: List<Utterance>,
        edits: EditsDocument?,
        index: Int,
        update: (UtteranceEdit) -> UtteranceEdit,
    ): EditsDocument {
        val base = current(machine, edits)
        val previous = base.edits.firstOrNull { it.index == index } ?: UtteranceEdit(index, machine[index].startMs)
        val next = update(previous)
        val others = base.edits.filterNot { it.index == index }
        val kept = if (next.text == null && next.speaker == null) others else others + next
        return EditsDocument(machine.size, kept.sortedBy { it.index })
    }
}
