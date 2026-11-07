package ru.stenokot.app.llm

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.stenokot.core.llm.LlmWindow
import ru.stenokot.core.llm.WindowResult
import ru.stenokot.core.llm.parseWindowOutput
import ru.stenokot.core.model.Language
import ru.stenokot.core.model.Utterance
import ru.stenokot.core.model.Word

/** Слово в merged.json. */
@Serializable
private data class MergedWord(val t: String, val s: Long, val e: Long, val k: Int)

/** Реплика в merged.json. */
@Serializable
private data class MergedUtterance(
    val id: Int,
    val speaker: Int,
    val startMs: Long,
    val endMs: Long,
    val words: List<MergedWord> = emptyList(),
    val text: String,
    val overlap: Boolean = false,
)

/** Содержимое merged.json: неизменяемая база, из которой протокол копирует реплики. */
@Serializable
private data class MergedFile(val utterances: List<MergedUtterance>)

/** Содержимое lang.json. Значение отсутствует, если определить язык не удалось. */
@Serializable
private data class LanguageFile(val language: Language? = null, val failed: Boolean = false)

/**
 * Строка журнала окон: сырой ответ модели. Разбор ответа не зависит от запуска, поэтому после смерти
 * процесса протокол восстанавливается без модели. [hash] отличает окно от другого текста с теми же номерами.
 */
@Serializable
private data class WindowLine(val first: Int, val last: Int, val hash: Int, val raw: String)

/** Реплики и язык задачи. */
internal data class LlmJob(val utterances: List<Utterance>, val language: Language?, val totalMs: Long)

/** Файлы задачи со стороны процесса :llm. Запись всегда атомарна: убить процесс можно в любой момент. */
internal class LlmJobFiles(private val dir: File) {
    private val merged = File(dir, "merged.json")
    private val languageFile = File(dir, "lang.json")
    private val windows = File(dir, "llm_windows.jsonl")
    val protocol = File(dir, "protocol.json")
    val stats = File(dir, "llm_stats.json")

    /** Читает реплики и язык. */
    fun readJob(): LlmJob {
        val utterances = FileJson.decodeFromString<MergedFile>(merged.readText()).utterances.map { line ->
            Utterance(
                id = line.id,
                speaker = line.speaker,
                startMs = line.startMs,
                endMs = line.endMs,
                words = line.words.map { Word(it.t, it.s, it.e, it.k) },
                text = line.text,
                overlap = line.overlap,
            )
        }
        val language = runCatching {
            FileJson.decodeFromString<LanguageFile>(languageFile.readText())
        }.getOrNull()?.takeUnless { it.failed }?.language
        return LlmJob(utterances, language, utterances.maxOfOrNull { it.endMs } ?: 0L)
    }

    /**
     * Восстанавливает уже обработанные окна. Неполная последняя строка и строки от другого текста
     * отбрасываются: окно будет посчитано заново.
     */
    fun restoreWindows(job: LlmJob): List<WindowResult> {
        if (!windows.isFile) return emptyList()
        val byId = job.utterances.withIndex().associate { (position, utterance) -> utterance.id to position }
        return windows.readLines().mapNotNull { text ->
            val line = runCatching { FileJson.decodeFromString<WindowLine>(text) }.getOrNull()
                ?: return@mapNotNull null
            val from = byId[line.first] ?: return@mapNotNull null
            val until = byId[line.last] ?: return@mapNotNull null
            if (until < from) return@mapNotNull null
            val window = LlmWindow(from, job.utterances.subList(from, until + 1).toList())
            if (window.text.hashCode() != line.hash) return@mapNotNull null
            WindowResult(window, parseWindowOutput(line.raw, window))
        }
    }

    /** Дописывает окно в журнал и сбрасывает его на диск. */
    fun appendWindow(window: LlmWindow, raw: String) {
        val line = WindowLine(window.utterances.first().id, window.utterances.last().id, window.text.hashCode(), raw)
        FileOutputStream(windows, true).use { output ->
            output.write((FileJson.encodeToString(line) + "\n").encodeToByteArray())
            output.fd.sync()
        }
    }

    /** Заменяет файл [target] так, что читатель видит либо старую, либо новую версию. */
    fun replace(target: File, text: String) {
        val temporary = File(dir, target.name + ".tmp")
        FileOutputStream(temporary).use { output ->
            output.write(text.encodeToByteArray())
            output.fd.sync()
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private companion object {
        val FileJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
