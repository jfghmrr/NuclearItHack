package ru.stenokot.app.pipeline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeout
import ru.stenokot.app.data.JobRecord
import ru.stenokot.app.data.JobStore
import ru.stenokot.app.data.SpeedBook
import ru.stenokot.core.contract.TranscriptValidator
import ru.stenokot.core.contract.ValidationResult
import ru.stenokot.core.engine.Asr
import ru.stenokot.core.engine.Diarizer
import ru.stenokot.core.engine.LanguageDetector
import ru.stenokot.core.engine.LlmRunner
import ru.stenokot.core.engine.Merger
import ru.stenokot.core.engine.PcmReader
import ru.stenokot.core.engine.Progress
import ru.stenokot.core.engine.Vad
import ru.stenokot.core.model.AsrSegment
import ru.stenokot.core.model.AsrToken
import ru.stenokot.core.model.AudioInfo
import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.Language
import ru.stenokot.core.model.LlmBudget
import ru.stenokot.core.model.LlmResult
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.model.SpeakerTurn
import ru.stenokot.core.model.SpeechSegment
import ru.stenokot.core.model.Utterance
import ru.stenokot.core.model.Word
import ru.stenokot.core.storage.DiarClusterRecord
import ru.stenokot.core.storage.DiarJournalLine
import ru.stenokot.core.storage.DiarTurnRecord
import ru.stenokot.core.storage.ModelHash
import java.io.File

/** Читатель без данных: фальшивым движкам звук не нужен. */
class FakePcm : PcmReader {
    var closed = false
    override val totalSamples: Long = 16_000L * 600
    override fun read(start: Long, count: Int): FloatArray = FloatArray(count)
    override fun close() {
        closed = true
    }
}

/** Движки со счётчиками и подменяемым поведением. */
class FakeEngines(private val segmentCount: Int = 5) : PipelineEngines {
    val events = ArrayList<String>()
    val transcribed = ArrayList<Int>()
    val diarizedWindows = ArrayList<Int>()
    val llmRuns = ArrayList<LlmBudget>()
    var openHeavy = 0
    var peakHeavy = 0
    var onTranscribe: (Int) -> Unit = {}
    var onWindow: (Int) -> Unit = {}
    var validation: ValidationResult = ValidationResult.Ok
    var language: Language = Language.RU
    var noSpeech = false
    var windows = 3
    var probeFailure: Exception? = null
    val restoredPerPass = ArrayList<Int>()
    var llmCancels = 0
    var llmBehavior: suspend (Int) -> LlmResult = { LlmResult(LlmStatus.APPLIED, null, null) }
    override var configHash: String = "cfg"

    private fun opened(name: String) {
        events += "open:$name"
        openHeavy += 1
        peakHeavy = maxOf(peakHeavy, openHeavy)
    }

    private fun closed(name: String) {
        events += "close:$name"
        openHeavy -= 1
    }

    override fun probe(source: File): SourceProbe {
        events += "probe"
        probeFailure?.let { throw it }
        return SourceProbe(60_000, "audio/mpeg")
    }

    override suspend fun decode(source: File, outWav: File, progress: Progress): AudioInfo {
        events += "decode:${source.readText()}"
        outWav.writeText("pcm")
        progress.report(1f, null)
        return AudioInfo(60_000, 44_100, 2, "audio/mpeg", "hash")
    }

    override fun openPcm(wav: File): PcmReader = FakePcm()

    override fun vad(): Vad = object : Vad {
        init {
            opened("vad")
        }

        override fun segments(pcm: PcmReader, progress: Progress): List<SpeechSegment> =
            if (noSpeech) emptyList() else List(segmentCount) { SpeechSegment(it, it * 16_000L, (it + 1) * 16_000L) }

        override fun close() = closed("vad")
    }

    override fun asr(language: Language): Asr = object : Asr {
        private val name = "asr-$language"

        init {
            opened(name)
        }

        override fun transcribe(pcm: PcmReader, chunk: SpeechSegment): AsrSegment {
            onTranscribe(chunk.index)
            transcribed += chunk.index
            val start = chunk.startSample / 16
            return AsrSegment(chunk, "слово${chunk.index}", listOf(AsrToken("слово${chunk.index}", start)))
        }

        override fun close() = closed(name)
    }

    override fun languageDetector(): LanguageDetector = LanguageDetector { language }

    override fun diarizer(request: DiarizationRequest): Diarizer = object : Diarizer {
        init {
            opened("diar")
            restoredPerPass += request.restored.size
        }

        override fun diarize(pcm: PcmReader, progress: Progress): List<SpeakerTurn> {
            for (index in request.restored.size until windows) {
                if (request.isCancelled()) error("Отменено")
                onWindow(index)
                diarizedWindows += index
                val start = index * 1_000L
                request.onWindow(
                    DiarJournalLine(
                        index = index,
                        startMs = start,
                        endMs = start + 1_000,
                        turns = listOf(DiarTurnRecord(0, start, start + 1_000)),
                        clusters = listOf(DiarClusterRecord(0, 1_000)),
                    ),
                )
            }
            return listOf(SpeakerTurn(0, windows * 1_000L, 0))
        }

        override fun close() = closed("diar")
    }

    override fun merger(audioDurationMs: Long): Merger = Merger { asr, turns ->
        events += "merge:${asr.size}:${turns.size}"
        asr.mapIndexed { index, segment ->
            val startMs = segment.segment.startSample / 16
            val word = Word(segment.text, startMs, startMs + 500, index % 2)
            Utterance(index, index % 2, startMs, startMs + 500, listOf(word), segment.text)
        }
    }

    override fun validator(): TranscriptValidator = TranscriptValidator { _, _ -> validation }

    override val llm: LlmRunner = object : LlmRunner {
        override suspend fun run(jobDir: File, budget: LlmBudget, progress: Progress): LlmResult {
            llmRuns += budget
            events += "llm"
            return llmBehavior(llmRuns.size)
        }

        override fun cancel() {
            llmCancels += 1
        }
    }
}

/** Показатели устройства, которые задаёт тест. */
class FakeDevice : DeviceMonitor {
    var memory = 2_000L * 1024 * 1024
    var thermal = 0
    var exitCode: ErrorCode? = null
    override fun availableMemoryBytes(): Long = memory
    override fun thermalStatus(): Int = thermal
    override fun peakRssKb(): Long? = 123_456
    override fun lastExitCode(): ErrorCode? = exitCode
}

/** Источник файла, который можно заставить отказать. */
class FakeSources : SourceGateway {
    var describeFailure: Exception? = null
    var free = 10_000_000_000L
    val copied = ArrayList<String>()

    override suspend fun describe(job: JobRecord): SourceInfo {
        describeFailure?.let { throw it }
        return SourceInfo(job.title, 1_000, "audio/mpeg")
    }

    override suspend fun copy(job: JobRecord, target: File, progress: (Long, Long) -> Unit) {
        copied += job.id
        target.writeText(job.id)
        progress(1, 1)
    }

    override fun freeBytes(): Long = free
}

/** Запоминает всё опубликованное. */
class FakeOutputs : OutputGateway {
    val transcripts = LinkedHashMap<String, ByteArray>()
    val meta = ArrayList<String>()
    val removed = ArrayList<String>()
    var failPublish = false

    override suspend fun publishTranscript(stem: String, bytes: ByteArray): PublishedFile {
        if (failPublish) error("Диск недоступен")
        transcripts[stem] = bytes
        return PublishedFile("Download/Stenokot/$stem.json", "content://$stem", fallback = false)
    }

    override suspend fun publishMeta(stem: String, kind: MetaKind, source: File) {
        meta += "$stem:${kind.suffix}"
    }

    override suspend fun removePublished(stem: String, uri: String?) {
        removed += stem
    }
}

/** Копирование модели под управлением теста. */
class FakeGate(initial: LlmModelState = LlmModelState.READY) : LlmModelGate {
    val current = MutableStateFlow(initial)
    val busyLog = ArrayList<Boolean>()
    override fun state(): LlmModelState = current.value
    override fun setBusy(busy: Boolean) {
        busyLog += busy
    }

    override suspend fun await(): LlmModelState = current.first { it != LlmModelState.PENDING }
}

/** Собранный конвейер на виртуальном времени. */
@OptIn(ExperimentalCoroutinesApi::class)
class Harness(
    private val test: TestScope,
    val root: File,
    val engines: FakeEngines = FakeEngines(),
    val device: FakeDevice = FakeDevice(),
    val sources: FakeSources = FakeSources(),
    val outputs: FakeOutputs = FakeOutputs(),
    val gate: FakeGate = FakeGate(),
    private val config: RunnerConfig = RunnerConfig(),
) {
    val statuses = ArrayList<String>()
    val store = JobStore { root }
    val lifetime = Job(test.backgroundScope.coroutineContext[Job])
    private val scope = CoroutineScope(test.backgroundScope.coroutineContext + lifetime)
    private val dispatcher = StandardTestDispatcher(test.testScheduler)
    private var seq = 0L
    private val recorder = MetricsRecorder(
        context = { RunContext("1", "abc", "emu", 36, 4_000_000_000, 3) },
        modelHashes = { listOf(ModelHash("asr-ru/encoder.int8.onnx", "aa")) },
    )
    val runner = PipelineRunner(
        store = store,
        engines = engines,
        device = device,
        sources = sources,
        outputs = outputs,
        modelGate = gate,
        metrics = recorder,
        speed = SpeedBook { File(root, "speed.json") },
        scope = scope,
        worker = dispatcher,
        io = dispatcher,
        clock = { test.testScheduler.currentTime },
        config = config,
        onStatus = { statuses += it },
    ).also { it.launch() }

    /** Импортирует файл [name] и возвращает номер задачи. */
    fun import(name: String): String {
        seq += 1
        val id = "job$seq"
        runner.enqueue(id, "content://$name", "$name.mp3", name, test.testScheduler.currentTime, seq)
        return id
    }

    /** Ждёт выполнения условия над задачами, виртуальное время идёт само. */
    suspend fun await(condition: (Map<String, JobRecord>) -> Boolean): Map<String, JobRecord> =
        withTimeout(WAIT_MS) { runner.jobs.first(condition) }

    /** Обрывает работу так, как это делает смерть процесса: файлы остаются как есть. */
    fun die() = lifetime.cancel()

    private companion object {
        const val WAIT_MS = 600_000L
    }
}
