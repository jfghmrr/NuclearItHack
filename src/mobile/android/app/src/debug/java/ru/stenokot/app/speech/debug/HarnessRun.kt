package ru.stenokot.app.speech.debug

import android.content.Context
import android.content.Intent
import android.os.Debug
import android.util.Log
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import ru.stenokot.app.audio.EngineException
import ru.stenokot.app.audio.MediaDecoder
import ru.stenokot.app.audio.Preflight
import ru.stenokot.app.audio.WavPcmReader
import ru.stenokot.app.speech.DiarizationJournal
import ru.stenokot.app.speech.EngineThreads
import ru.stenokot.app.speech.GigaAsr
import ru.stenokot.app.speech.ModelSource
import ru.stenokot.app.speech.ParakeetAsr
import ru.stenokot.app.speech.SileroVadEngine
import ru.stenokot.app.speech.WindowedDiarizer
import ru.stenokot.core.asr.toWords
import ru.stenokot.core.engine.Asr
import ru.stenokot.core.engine.Progress
import ru.stenokot.core.model.AsrSegment
import ru.stenokot.core.model.SpeakerTurn
import ru.stenokot.core.merge.DefaultMerger
import ru.stenokot.core.model.SpeechSegment
import ru.stenokot.core.storage.DiarJournalLine

/** Параметры запуска из adb. */
internal data class HarnessRequest(
    val file: String,
    val name: String,
    val stages: Set<String>,
    val language: String,
    val threads: Int?,
    val diarThreads: Int?,
    val threshold: Float?,
    val embeddingSeconds: Int,
    val checkResume: Boolean,
    val maxChunks: Int,
) {
    companion object {
        fun from(intent: Intent?): HarnessRequest {
            val file = intent?.getStringExtra("file").orEmpty()
            return HarnessRequest(
                file = file,
                name = intent?.getStringExtra("name") ?: File(file).nameWithoutExtension,
                stages = (intent?.getStringExtra("stages") ?: "decode,vad,asr,diar").split(",").toSet(),
                language = intent?.getStringExtra("lang") ?: "ru",
                threads = intent?.getStringExtra("threads")?.toIntOrNull(),
                diarThreads = intent?.getStringExtra("diarThreads")?.toIntOrNull(),
                threshold = intent?.getStringExtra("threshold")?.toFloatOrNull(),
                embeddingSeconds = intent?.getStringExtra("embedSeconds")?.toIntOrNull() ?: 30,
                checkResume = intent?.getStringExtra("resume") != null,
                maxChunks = intent?.getStringExtra("maxChunks")?.toIntOrNull() ?: Int.MAX_VALUE,
            )
        }
    }
}

/** Выполняет стадии по очереди и собирает отчёт: время, память, тексты. */
internal class HarnessRun(private val context: Context, private val request: HarnessRequest) {
    private val report = JSONObject()
    private val stageList = JSONArray()
    private val sampler = MemorySampler()
    private val quiet = Progress { _, _ -> }

    fun execute(): JSONObject {
        report.put("externalDir", context.getExternalFilesDir(null)?.path)
        if (request.file.isEmpty()) return report
        val source = File(request.file)
        val workDir = File(context.filesDir, "harness/${request.name}").apply { mkdirs() }
        val wav = File(workDir, "pcm16k.wav")
        report.put("file", source.name)
        report.put("sizeBytes", source.length())
        report.put("cores", Runtime.getRuntime().availableProcessors())
        report.put("asrThreads", request.threads ?: EngineThreads.asr())
        report.put("diarThreads", request.diarThreads ?: EngineThreads.diarization())
        report.put("stages", stageList)
        report.put("startKb", MemoryStats.read().toJson())
        sampler.start()
        try {
            runStages(source, wav)
        } catch (error: EngineException) {
            report.put("errorCode", error.code.name)
            report.put("errorMessage", error.message)
        } finally {
            sampler.stop()
        }
        report.put("endKb", MemoryStats.read().toJson())
        return report
    }

    private fun runStages(source: File, wav: File) {
        val probe = stage("preflight", 0.0) { Preflight.probe(source) }
        report.put(
            "preflight",
            JSONObject()
                .put("mime", probe.mime)
                .put("codec", probe.codecName)
                .put("container", probe.container)
                .put("sampleRate", probe.sampleRate)
                .put("channels", probe.channels)
                .put("headerDurationMs", probe.headerDurationMs)
                .put("headerReliable", probe.headerReliable),
        )
        val inspected = Preflight.inspect(source, source.name, context.filesDir)
        report.put(
            "preflightInfo",
            JSONObject()
                .put("fileName", inspected.fileName)
                .put("sizeBytes", inspected.sizeBytes)
                .put("durationMs", inspected.durationMs)
                .put("freeBytes", inspected.freeBytes)
                .put("requiredBytes", inspected.requiredBytes),
        )
        if ("decode" !in request.stages) return
        val info = stage("decode", 0.0) {
            runBlocking { MediaDecoder().decode(source, wav, quiet) }
        }
        val seconds = info.durationMs / 1000.0
        report.put(
            "audio",
            JSONObject()
                .put("durationMs", info.durationMs)
                .put("sourceSampleRate", info.sourceSampleRate)
                .put("sourceChannels", info.sourceChannels)
                .put("mime", info.mime)
                .put("sha256", info.sourceSha256)
                .put("wavBytes", wav.length()),
        )
        fixFirstStageRtf(seconds)
        val models = ModelSource.from(context)
        WavPcmReader.open(wav).use { pcm ->
            report.put("gain", pcm.gain.toDouble())
            if ("vad" !in request.stages) return
            val segments = stage("vad", seconds) {
                SileroVadEngine(models).use { it.segments(pcm, quiet) }
            }
            report.put("chunks", segments.size)
            report.put("chunkedSeconds", segments.sumOf { (it.endSample - it.startSample) / 16000.0 })
            report.put("maxChunkSeconds", (segments.maxOfOrNull { it.endSample - it.startSample } ?: 0L) / 16000.0)
            var results = emptyList<AsrSegment>()
            if ("asr" in request.stages && segments.isNotEmpty()) {
                results = runAsr(models, pcm, segments, seconds)
                describeAsr(results)
            }
            if ("diar" in request.stages) {
                val lines = ArrayList<DiarJournalLine>()
                val turns = stage("diar", seconds) {
                    diarizer(models, DiarizationJournal(onWindow = { lines += it })).use { it.diarize(pcm, quiet) }
                }
                describeTurns(turns)
                if (request.checkResume && lines.size > 1) {
                    val journal = DiarizationJournal(restored = lines.take(lines.size - 1))
                    val resumed = stage("diar_resume", seconds) { diarizer(models, journal).use { it.diarize(pcm, quiet) } }
                    report.put("resumeWindows", lines.size)
                    report.put("resumeIdentical", resumed == turns)
                }
                if (results.isNotEmpty()) describeMerge(results, turns, info.durationMs)
            }
        }
    }

    private fun diarizer(models: ModelSource, journal: DiarizationJournal) = WindowedDiarizer(
        models = models,
        threads = request.diarThreads ?: EngineThreads.diarization(),
        journal = journal,
        thresholdOverride = request.threshold,
        embeddingSeconds = request.embeddingSeconds,
    )

    private fun runAsr(
        models: ModelSource,
        pcm: WavPcmReader,
        segments: List<SpeechSegment>,
        seconds: Double,
    ): List<AsrSegment> {
        val threads = request.threads ?: EngineThreads.asr()
        val engine = stage("asr_load", seconds) {
            if (request.language == "en") ParakeetAsr(models, threads) else GigaAsr(models, threads)
        }
        val results = engine.useEngine {
            stage("asr", seconds) {
                segments.take(request.maxChunks).map { engine.transcribe(pcm, it) }
            }
        }
        stageList.getJSONObject(stageList.length() - 1).put("afterReleaseKb", MemoryStats.read().toJson())
        return results
    }

    private inline fun <T> Asr.useEngine(block: () -> T): T = use { block() }

    private fun describeAsr(results: List<AsrSegment>) {
        val tokens = results.flatMap { it.tokens }
        val words = results.flatMap { toWords(it) }
        val firstWords = JSONArray()
        words.take(WORD_SAMPLE).forEach { firstWords.put(JSONObject().put("t", it.text).put("ms", it.startMs)) }
        report.put(
            "asrResult",
            JSONObject()
                .put("chunks", results.size)
                .put("tokens", tokens.size)
                .put("words", words.size)
                .put("timestampsOrdered", results.all { chunk -> chunk.tokens.zipWithNext().all { (a, b) -> a.startMs <= b.startMs } })
                .put("timestampsInsideChunks", results.all { chunk -> chunk.tokens.all { it.startMs in chunkRangeMs(chunk) } })
                .put("lastTokenMs", tokens.lastOrNull()?.startMs ?: 0L)
                .put("textSample", results.joinToString(" ") { it.text.trim() }.trim().take(TEXT_SAMPLE))
                .put("firstWords", firstWords),
        )
    }

    private fun chunkRangeMs(chunk: AsrSegment): LongRange =
        (chunk.segment.startSample / 16)..(chunk.segment.endSample / 16)

    private fun describeTurns(turns: List<SpeakerTurn>) {
        val speakers = JSONArray()
        turns.groupBy { it.speaker }.toSortedMap().forEach { (speaker, own) ->
            speakers.put(
                JSONObject()
                    .put("speaker", speaker)
                    .put("turns", own.size)
                    .put("seconds", own.sumOf { it.endMs - it.startMs } / 1000.0),
            )
        }
        val intervals = JSONArray()
        turns.forEach { intervals.put(JSONArray().put(it.startMs).put(it.endMs).put(it.speaker)) }
        report.put(
            "diarResult",
            JSONObject()
                .put("speakers", speakers.length())
                .put("turns", turns.size)
                .put("perSpeaker", speakers)
                .put("intervals", intervals),
        )
    }

    private fun describeMerge(asr: List<AsrSegment>, turns: List<SpeakerTurn>, durationMs: Long) {
        val utterances = stage("merge", durationMs / 1000.0) { DefaultMerger(durationMs).merge(asr, turns) }
        val workDir = File(context.filesDir, "harness/${request.name}")
        File(workDir, "transcript.txt").writeText(
            utterances.joinToString("\n") { "S${it.speaker} ${it.startMs}-${it.endMs}: ${it.text}" },
        )
        val sample = JSONArray()
        utterances.take(UTTERANCE_SAMPLE).forEach {
            sample.put(
                JSONObject()
                    .put("speaker", it.speaker)
                    .put("startMs", it.startMs)
                    .put("endMs", it.endMs)
                    .put("text", it.text.take(160)),
            )
        }
        report.put(
            "mergeResult",
            JSONObject()
                .put("utterances", utterances.size)
                .put("speakers", utterances.map { it.speaker }.distinct().size)
                .put("sample", sample),
        )
    }

    private fun fixFirstStageRtf(seconds: Double) {
        for (index in 0 until stageList.length()) {
            val entry = stageList.getJSONObject(index)
            if (seconds > 0) entry.put("rtf", entry.getLong("ms") / 1000.0 / seconds)
        }
    }

    private fun <T> stage(name: String, audioSeconds: Double, block: () -> T): T {
        Log.i(TAG, "stage $name start")
        MemoryStats.resetPeak()
        sampler.resetPeak()
        val started = System.nanoTime()
        val result = block()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val memory = MemoryStats.read()
        val entry = JSONObject()
            .put("name", name)
            .put("ms", elapsedMs)
            .put("peakRssKb", sampler.peakKb())
            .put("afterKb", memory.toJson())
            .put("pssKb", Debug.getPss())
        if (audioSeconds > 0) entry.put("rtf", (elapsedMs / 1000.0 / audioSeconds * 1000).roundToInt() / 1000.0)
        stageList.put(entry)
        Log.i(TAG, "stage $name done ${elapsedMs} ms, peak ${sampler.peakKb()} kB")
        return result
    }

    private companion object {
        const val TAG = "StenoHarness"
        const val TEXT_SAMPLE = 600
        const val WORD_SAMPLE = 12
        const val UTTERANCE_SAMPLE = 8
    }
}

/** Значения памяти процесса из /proc/self/status, килобайты. */
private data class MemoryStats(val rss: Long, val hwm: Long, val anon: Long) {
    fun toJson(): JSONObject = JSONObject().put("vmRss", rss).put("vmHwm", hwm).put("rssAnon", anon)

    companion object {
        fun read(): MemoryStats {
            var rss = 0L
            var hwm = 0L
            var anon = 0L
            File("/proc/self/status").forEachLine { line ->
                when {
                    line.startsWith("VmRSS:") -> rss = parse(line)
                    line.startsWith("VmHWM:") -> hwm = parse(line)
                    line.startsWith("RssAnon:") -> anon = parse(line)
                }
            }
            return MemoryStats(rss, hwm, anon)
        }

        /** Сброс пика ядра. Не на всех образах доступен, поэтому есть ещё и замер опросом. */
        fun resetPeak() {
            runCatching { File("/proc/self/clear_refs").writeText("5") }
        }

        private fun parse(line: String): Long = line.filter { it.isDigit() }.toLong()
    }
}

/** Опрашивает VmRSS, чтобы пик стадии был виден и там, где сброс VmHWM запрещён. */
private class MemorySampler {
    @Volatile private var running = false
    @Volatile private var peak = 0L

    fun start() {
        running = true
        thread(name = "memory-sampler", isDaemon = true) {
            while (running) {
                val current = MemoryStats.read().rss
                if (current > peak) peak = current
                Thread.sleep(PERIOD_MS)
            }
        }
    }

    fun stop() {
        running = false
    }

    fun resetPeak() {
        peak = 0
    }

    fun peakKb(): Long = peak

    private companion object {
        const val PERIOD_MS = 100L
    }
}
