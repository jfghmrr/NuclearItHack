package ru.stenokot.app.llm

import android.os.SystemClock
import android.util.Log
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.stenokot.core.llm.LlmBudgetPolicy
import ru.stenokot.core.llm.LlmPrompts
import ru.stenokot.core.llm.LlmSampler
import ru.stenokot.core.llm.LlmSpeed
import ru.stenokot.core.llm.LlmWindow
import ru.stenokot.core.llm.LlmWindows
import ru.stenokot.core.llm.ProtocolAssembler
import ru.stenokot.core.llm.ProtocolGrammar
import ru.stenokot.core.llm.WindowResult
import ru.stenokot.core.llm.parseWindowOutput
import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.Language
import ru.stenokot.core.model.LlmResult
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.model.Utterance
import ru.stenokot.llm.ContextConfig
import ru.stenokot.llm.GenerationResult
import ru.stenokot.llm.GenerationStatus
import ru.stenokot.llm.LlamaBridge
import ru.stenokot.llm.LoadStatus

/** Счётчики прогона для metadata.json. Текста реплик здесь нет. */
@Serializable
internal data class LlmRunStats(
    val status: String,
    @SerialName("system_info") val systemInfo: String,
    val threads: Int,
    @SerialName("load_ms") val loadMs: Long,
    @SerialName("windows_total") val windowsTotal: Int,
    @SerialName("windows_done") val windowsDone: Int,
    @SerialName("prompt_tokens") val promptTokens: Long,
    @SerialName("decoded_prompt_tokens") val decodedPromptTokens: Long,
    @SerialName("completion_tokens") val completionTokens: Long,
    @SerialName("prompt_ms") val promptMs: Long,
    @SerialName("generation_ms") val generationMs: Long,
    @SerialName("sampling_ms") val samplingMs: Long,
    @SerialName("vm_hwm_kb") val vmHwmKb: Long,
    @SerialName("rss_anon_kb") val rssAnonKb: Long,
)

/**
 * Стадия языковой модели внутри процесса :llm.
 * Ответ по окну сразу попадает в журнал, а протокол заменяется целиком, поэтому отмена и смерть
 * процесса теряют не больше одного окна.
 */
internal class LlmWorker(
    private val request: LlmRequest,
    private val nativeLibDir: String,
    private val onProgress: (Float, String?) -> Unit,
) {
    private val files = LlmJobFiles(File(request.jobDir))
    private val sampler = LlmSampler(contextTokens = request.contextTokens)
    private val threads = LlmDevice.threadCount()
    private val results = ArrayList<WindowResult>()
    private var systemInfo = ""
    private var loadMs = 0L
    private var windowsTotal = 0
    private var promptTokens = 0L
    private var decodedPromptTokens = 0L
    private var completionTokens = 0L
    private var promptMs = 0L
    private var generationMs = 0L
    private var samplingMs = 0L
    private var speed: LlmSpeed? = null
    private var shortened = false

    /** Выполняет стадию. Исключение превращается в статус: контрактный файл от него не зависит. */
    fun run(): LlmResult {
        val result = runCatching(::process).getOrElse { error ->
            Log.e(TAG, "stage failed", error)
            LlmResult(failureStatus(), protocolPath(), error.javaClass.simpleName)
        }
        writeStats(result.status)
        return result
    }

    private fun process(): LlmResult {
        val job = files.readJob()
        val windows = planWindows(job)
        windowsTotal = windows.size
        results += files.restoreWindows(job)
        val finished = results.mapTo(HashSet()) { it.window.utterances.first().id }
        val pending = windows.filterNot { it.utterances.first().id in finished }
        Log.i(TAG, "windows total=$windowsTotal restored=${results.size}")
        if (pending.isEmpty()) return outcome(null)

        val failure = loadModel()
        if (failure != null) return failure
        val outcome = generate(job, LlmWindows.coverageOrder(pending))
        LlamaBridge.unload()
        return outcome
    }

    private fun planWindows(job: LlmJob): List<LlmWindow> {
        val language = job.language ?: Language.RU
        val tokens = { utterance: Utterance -> LlmWindows.estimateTokens(utterance, language) }
        return LlmWindows.withoutSparse(LlmWindows.split(job.utterances, request.maxChunkTokens, tokens))
    }

    private fun loadModel(): LlmResult? {
        systemInfo = LlamaBridge.initialize(nativeLibDir)
        val started = SystemClock.elapsedRealtime()
        val config = ContextConfig(threads = threads, contextTokens = request.contextTokens)
        val status = LlamaBridge.load(request.modelPath, config)
        loadMs = SystemClock.elapsedRealtime() - started
        Log.i(TAG, "load=$status ms=$loadMs threads=$threads")
        return when (status) {
            LoadStatus.LOADED -> null
            LoadStatus.CANCELLED -> LlmResult(LlmStatus.CANCELLED_BY_NEW_JOB, protocolPath(), null)
            LoadStatus.MODEL_FAILED -> LlmResult(failureStatus(), protocolPath(), ErrorCode.LLM_MODEL_UNAVAILABLE.name)
            LoadStatus.CONTEXT_FAILED -> LlmResult(failureStatus(), protocolPath(), ErrorCode.LLM_LOW_MEMORY.name)
        }
    }

    private fun generate(job: LlmJob, windows: List<LlmWindow>): LlmResult {
        val started = SystemClock.elapsedRealtime()
        val tokens = { utterance: Utterance -> LlmWindows.estimateTokens(utterance, job.language ?: Language.RU) }
        for (planned in windows) {
            val elapsed = SystemClock.elapsedRealtime() - started
            val window = fitToBudget(planned, elapsed, tokens) ?: return outcome(ErrorCode.LLM_TIMEOUT.name)
            val limit = LlmBudgetPolicy.windowLimitMs(results.size, elapsed, request.budgetMs)
            if (limit <= 0) return outcome(ErrorCode.LLM_TIMEOUT.name)

            val language = LlmPrompts.languageFor(job.language, window)
            val prompt = LlmPrompts.systemPrefix(language) + LlmPrompts.windowSuffix(window)
            val answer = LlamaBridge.generate(prompt, ProtocolGrammar.build(window), sampler, limit)
            account(answer)
            when (answer.status) {
                GenerationStatus.COMPLETED, GenerationStatus.LIMIT_REACHED -> accept(job, window, answer.text)
                GenerationStatus.CANCELLED -> return LlmResult(LlmStatus.CANCELLED_BY_NEW_JOB, protocolPath(), null)
                GenerationStatus.TIMED_OUT -> return outcome(ErrorCode.LLM_TIMEOUT.name)
                GenerationStatus.PROMPT_TOO_LONG -> shortened = true
                GenerationStatus.BAD_GRAMMAR, GenerationStatus.FAILED ->
                    return LlmResult(failureStatus(), protocolPath(), answer.status.name)
            }
        }
        return outcome(null)
    }

    /** Сокращает окно под остаток бюджета по замеренной скорости. Значение null означает конец стадии. */
    private fun fitToBudget(window: LlmWindow, elapsed: Long, tokens: (Utterance) -> Int): LlmWindow? {
        val measured = speed ?: return window
        val remaining = request.budgetMs - elapsed
        val fit = LlmBudgetPolicy.nextWindowTokens(remaining, measured, request.maxChunkTokens) ?: return null
        val cut = LlmWindows.split(window.utterances, fit, tokens).first().copy(index = window.index)
        if (cut.utterances.size < window.utterances.size) shortened = true
        return cut
    }

    private fun accept(job: LlmJob, window: LlmWindow, raw: String) {
        files.appendWindow(window, raw)
        results += WindowResult(window, parseWindowOutput(raw, window))
        files.replace(files.protocol, FileJson.encodeToString(ProtocolAssembler.assemble(results, job.totalMs)))
        onProgress(results.size.toFloat() / windowsTotal, null)
    }

    private fun outcome(detail: String?): LlmResult {
        val status = LlmBudgetPolicy.outcome(results.size, windowsTotal)
        val honest = if (status == LlmStatus.APPLIED && shortened) LlmStatus.PARTIAL else status
        return LlmResult(if (windowsTotal == 0) LlmStatus.APPLIED else honest, protocolPath(), detail)
    }

    private fun failureStatus(): LlmStatus = if (results.isNotEmpty()) LlmStatus.PARTIAL else LlmStatus.FAILED

    private fun protocolPath(): String? = files.protocol.path.takeIf { files.protocol.isFile }

    private fun account(answer: GenerationResult) {
        promptTokens += answer.promptTokens
        decodedPromptTokens += answer.decodedPromptTokens
        completionTokens += answer.generatedTokens
        promptMs += answer.promptMs
        generationMs += answer.generationMs
        samplingMs += answer.samplingMs
        if (decodedPromptTokens > 0 && promptMs > 0 && completionTokens > 0 && generationMs + samplingMs > 0) {
            speed = LlmSpeed(
                prefillTps = decodedPromptTokens * MS_PER_SECOND / promptMs,
                decodeTps = completionTokens * MS_PER_SECOND / (generationMs + samplingMs),
            )
        }
        Log.i(TAG, "window status=${answer.status} prompt=${answer.promptTokens} " +
            "decoded=${answer.decodedPromptTokens} generated=${answer.generatedTokens} " +
            "promptMs=${answer.promptMs} generationMs=${answer.generationMs} samplingMs=${answer.samplingMs}")
    }

    private fun writeStats(status: LlmStatus) {
        val memory = ProcessMemory.read()
        val stats = LlmRunStats(
            status.name, systemInfo, threads, loadMs, windowsTotal, results.size, promptTokens,
            decodedPromptTokens, completionTokens, promptMs, generationMs, samplingMs,
            memory.vmHwmKb, memory.rssAnonKb,
        )
        Log.i(TAG, "stats $stats")
        runCatching { files.replace(files.stats, FileJson.encodeToString(stats)) }
    }

    private companion object {
        const val TAG = "StenokotLlm"
        const val MS_PER_SECOND = 1000.0
        val FileJson = Json { encodeDefaults = true }
    }
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
