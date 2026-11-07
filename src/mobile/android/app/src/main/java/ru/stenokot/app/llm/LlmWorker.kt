package ru.stenokot.app.llm

import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.LlmResult
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.protocol.ProtocolDocument
import ru.stenokot.core.protocol.ProtocolSection
import ru.stenokot.llm.ContextConfig
import ru.stenokot.llm.GenerationResult
import ru.stenokot.llm.GenerationStatus
import ru.stenokot.llm.LlamaBridge
import ru.stenokot.llm.LoadStatus
import ru.stenokot.llm.SamplingConfig

/** Счётчики прогона для metadata.json. Текста реплик здесь нет. */
@Serializable
internal data class LlmStats(
    val status: String,
    @SerialName("system_info") val systemInfo: String,
    val threads: Int,
    @SerialName("load_ms") val loadMs: Long,
    @SerialName("windows_total") val windowsTotal: Int,
    @SerialName("windows_done") val windowsDone: Int,
    @SerialName("prompt_tokens") val promptTokens: Long,
    @SerialName("decoded_prompt_tokens") val decodedPromptTokens: Long,
    @SerialName("generated_tokens") val generatedTokens: Long,
    @SerialName("prompt_ms") val promptMs: Long,
    @SerialName("generation_ms") val generationMs: Long,
    @SerialName("sampling_ms") val samplingMs: Long,
    @SerialName("vm_hwm_kb") val vmHwmKb: Long,
    @SerialName("rss_anon_kb") val rssAnonKb: Long,
)

/**
 * Стадия языковой модели внутри процесса :llm.
 * Частичный результат записывается после каждого окна, поэтому отмена и смерть процесса его не теряют.
 */
internal class LlmWorker(
    private val request: LlmRequest,
    private val nativeLibDir: String,
    private val onProgress: (Float, String?) -> Unit,
) {
    private val jobDir = File(request.jobDir)
    private val protocolFile = File(jobDir, "protocol.json")
    private val threads = LlmDevice.threadCount()
    private val sections = ArrayList<ProtocolSection>()
    private var systemInfo = ""
    private var loadMs = 0L
    private var windowsTotal = 0
    private var windowsDone = 0
    private var promptTokens = 0L
    private var decodedPromptTokens = 0L
    private var generatedTokens = 0L
    private var promptMs = 0L
    private var generationMs = 0L
    private var samplingMs = 0L

    /** Выполняет стадию. Исключения превращаются в статус: смерть из-за них не нужна никому. */
    fun run(): LlmResult {
        val result = runCatching(::process).getOrElse { error ->
            Log.e(TAG, "stage failed", error)
            LlmResult(failureStatus(), protocolPath(), error.javaClass.simpleName)
        }
        writeStats(result.status)
        return result
    }

    private fun process(): LlmResult {
        val job = LlmJobReader.read(jobDir)
        val windows = LlmPlan.windows(job, request.maxChunkTokens)
        windowsTotal = windows.size
        val pending = pendingWindows(windows)
        if (pending.isEmpty()) return LlmResult(LlmStatus.APPLIED, protocolPath(), null)

        systemInfo = LlamaBridge.initialize(nativeLibDir)
        val loadStart = SystemClock.elapsedRealtime()
        val config = ContextConfig(threads = threads, contextTokens = request.contextTokens)
        val loaded = LlamaBridge.load(request.modelPath, config)
        loadMs = SystemClock.elapsedRealtime() - loadStart
        Log.i(TAG, "load=$loaded ms=$loadMs threads=$threads")
        when (loaded) {
            LoadStatus.LOADED -> Unit
            LoadStatus.CANCELLED -> return LlmResult(LlmStatus.CANCELLED_BY_NEW_JOB, null, null)
            LoadStatus.MODEL_FAILED -> return failure(ErrorCode.LLM_MODEL_UNAVAILABLE.name)
            LoadStatus.CONTEXT_FAILED -> return failure(ErrorCode.LLM_LOW_MEMORY.name)
        }
        val deadline = SystemClock.elapsedRealtime() + request.budgetMs
        val outcome = generate(job, pending, deadline)
        LlamaBridge.unload()
        return outcome
    }

    /** Продолжение с чекпойнта: разделы прошлого запуска сохраняются, их окна не считаются заново. */
    private fun pendingWindows(windows: List<LlmWindow>): List<LlmWindow> {
        val restored = runCatching {
            FileJson.decodeFromString<ProtocolDocument>(protocolFile.readText()).sections
        }.getOrDefault(emptyList())
        val pending = windows.filterNot { window -> restored.any { it.firstUtterance == window.firstUtterance } }
        sections += restored
        windowsDone = windows.size - pending.size
        Log.i(TAG, "windows total=${windows.size} restored=$windowsDone")
        return pending
    }

    private fun generate(job: LlmJob, windows: List<LlmWindow>, deadline: Long): LlmResult {
        val sampling = SamplingConfig()
        for (window in windows) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return exhausted()
            val answer = LlamaBridge.generate(LlmPlan.prompt(window), LlmPlan.grammar(window), sampling, remaining)
            account(answer)
            Log.i(TAG, "window=${window.index} status=${answer.status} prompt=${answer.promptTokens} " +
                "decoded=${answer.decodedPromptTokens} generated=${answer.generatedTokens} " +
                "promptMs=${answer.promptMs} generationMs=${answer.generationMs} samplingMs=${answer.samplingMs}")
            when (answer.status) {
                GenerationStatus.COMPLETED, GenerationStatus.LIMIT_REACHED -> Unit
                GenerationStatus.CANCELLED -> return LlmResult(LlmStatus.CANCELLED_BY_NEW_JOB, protocolPath(), null)
                GenerationStatus.TIMED_OUT -> return exhausted()
                GenerationStatus.PROMPT_TOO_LONG -> continue
                GenerationStatus.BAD_GRAMMAR, GenerationStatus.FAILED -> return failure(answer.status.name)
            }
            LlmPlan.section(window, answer.text)?.let(sections::add)
            windowsDone += 1
            writeProtocol(job)
            onProgress(windowsDone.toFloat() / windowsTotal, null)
        }
        return LlmResult(LlmStatus.APPLIED, protocolPath(), null)
    }

    private fun exhausted(): LlmResult = LlmResult(
        if (windowsDone > 0) LlmStatus.PARTIAL else LlmStatus.SKIPPED_BY_BUDGET,
        protocolPath(),
        ErrorCode.LLM_TIMEOUT.name,
    )

    private fun failure(detail: String): LlmResult = LlmResult(failureStatus(), protocolPath(), detail)

    private fun failureStatus(): LlmStatus = if (windowsDone > 0) LlmStatus.PARTIAL else LlmStatus.FAILED

    private fun protocolPath(): String? = protocolFile.path.takeIf { protocolFile.isFile }

    private fun account(answer: GenerationResult) {
        promptTokens += answer.promptTokens
        decodedPromptTokens += answer.decodedPromptTokens
        generatedTokens += answer.generatedTokens
        promptMs += answer.promptMs
        generationMs += answer.generationMs
        samplingMs += answer.samplingMs
    }

    private fun writeProtocol(job: LlmJob) {
        val ordered = sections.sortedBy { it.firstUtterance }
        val document = ProtocolDocument(ordered, coveredMs(ordered), job.totalMs)
        replaceAtomically(protocolFile, FileJson.encodeToString(document))
    }

    private fun writeStats(status: LlmStatus) {
        val memory = ProcessMemory.read()
        val stats = LlmStats(
            status.name, systemInfo, threads, loadMs, windowsTotal, windowsDone, promptTokens,
            decodedPromptTokens, generatedTokens, promptMs, generationMs, samplingMs, memory.vmHwmKb, memory.rssAnonKb,
        )
        Log.i(TAG, "stats $stats")
        runCatching { replaceAtomically(File(jobDir, "llm_stats.json"), FileJson.encodeToString(stats)) }
    }

    private fun replaceAtomically(target: File, text: String) {
        val temporary = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(temporary).use { output ->
            output.write(text.encodeToByteArray())
            output.fd.sync()
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private companion object {
        const val TAG = "StenokotLlm"
        val FileJson = Json { encodeDefaults = true }
    }
}

/** Длина объединения разделов. Соседние окна делят реплику, поэтому простая сумма превысила бы запись. */
internal fun coveredMs(sections: List<ProtocolSection>): Long {
    var covered = 0L
    var reached = Long.MIN_VALUE
    for (section in sections.sortedBy { it.startMs }) {
        val start = maxOf(section.startMs, reached)
        if (section.endMs > start) covered += section.endMs - start
        reached = maxOf(reached, section.endMs)
    }
    return covered
}

/** Память текущего процесса по /proc/self/status. */
internal data class ProcessMemory(val vmHwmKb: Long, val rssAnonKb: Long) {
    companion object {
        private val Number = Regex("\\d+")

        fun read(): ProcessMemory {
            val lines = runCatching { File("/proc/self/status").readLines() }.getOrDefault(emptyList())
            fun field(name: String): Long =
                lines.firstOrNull { it.startsWith(name) }?.let { Number.find(it)?.value?.toLong() } ?: 0L
            return ProcessMemory(field("VmHWM"), field("RssAnon"))
        }
    }
}
