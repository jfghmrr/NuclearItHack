package ru.stenokot.app.llm

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.stenokot.core.protocol.ProtocolItem
import ru.stenokot.core.protocol.ProtocolSection

@Serializable
internal data class LlmUtterance(val id: Int, val speaker: Int, val startMs: Long, val endMs: Long, val text: String)

@Serializable
internal data class MergedFile(val utterances: List<LlmUtterance>)

internal data class LlmJob(val utterances: List<LlmUtterance>, val totalMs: Long)

internal object LlmJobReader {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(jobDir: File): LlmJob {
        val merged = json.decodeFromString<MergedFile>(File(jobDir, "merged.json").readText())
        return LlmJob(merged.utterances, merged.utterances.maxOfOrNull { it.endMs } ?: 0L)
    }
}

internal data class LlmWindow(val index: Int, val utterances: List<LlmUtterance>) {
    val firstUtterance: Int get() = utterances.first().id
}

internal object LlmPlan {
    private const val CHARS_PER_TOKEN = 3
    private val system = """Ты готовишь оглавление записи разговора. Дан фрагмент расшифровки с номерами реплик. Опирайся только на то, что сказано прямо.
Формат ответа:
T: о чём фрагмент, до 6 слов из текста.
D номер: реплика с принятым решением.
A номер: реплика, где кому-то поручено дело.
Q номер: реплика с вопросом, оставшимся без ответа.
Строки D, A, Q необязательны, не больше трёх. Если таких реплик нет, напиши только T. Не пересказывай и не исправляй текст.
Пример.
[3] speaker_1: Значит, закупаем корм у нового поставщика.
[4] speaker_2: Хорошо, Барсик договорится до пятницы.
Ответ:
T: закупка корма
D 3
A 4"""

    fun windows(job: LlmJob, maxChunkTokens: Int): List<LlmWindow> {
        val out = ArrayList<LlmWindow>()
        var current = ArrayList<LlmUtterance>()
        var size = 0
        for (utterance in job.utterances) {
            val cost = utterance.text.length / CHARS_PER_TOKEN + 8
            if (size + cost > maxChunkTokens && current.isNotEmpty()) {
                out += LlmWindow(out.size, current)
                current = arrayListOf(current.last())
                size = current.first().text.length / CHARS_PER_TOKEN + 8
            }
            current += utterance
            size += cost
        }
        if (current.isNotEmpty()) out += LlmWindow(out.size, current)
        return out
    }

    fun prompt(window: LlmWindow): String {
        val body = window.utterances.joinToString("\n") { "[${it.id}] speaker_${it.speaker + 1}: ${it.text}" }
        return "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$body<|im_end|>\n" +
            "<|im_start|>assistant\n<think>\n\n</think>\n\n"
    }

    fun grammar(window: LlmWindow): String {
        val refs = window.utterances.joinToString(" | ") { "\"${it.id}\"" }
        return """root ::= topic item{0,3}
topic ::= "T: " word (" " word){0,5} "\n"
item ::= [DAQ] " " ref "\n"
ref ::= $refs
word ::= [^ \n\t\[\]]{1,24}
"""
    }

    fun section(window: LlmWindow, answer: String): ProtocolSection? {
        val lines = answer.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val title = lines.firstOrNull { it.startsWith("T: ") }?.removePrefix("T: ") ?: return null
        val kinds = mapOf('D' to "decision", 'A' to "action", 'Q' to "question")
        val items = lines.mapNotNull { line ->
            val kind = kinds[line.firstOrNull()] ?: return@mapNotNull null
            val id = line.substringAfter(' ', "").toIntOrNull() ?: return@mapNotNull null
            val utterance = window.utterances.firstOrNull { it.id == id } ?: return@mapNotNull null
            ProtocolItem(kind, id, "speaker_${utterance.speaker + 1}", utterance.startMs, utterance.text)
        }
        return ProtocolSection(title, window.utterances.first().startMs, window.utterances.last().endMs, window.utterances.first().id, items)
    }
}
