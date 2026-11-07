package ru.stenokot.app.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import ru.stenokot.app.data.AsrJournal
import ru.stenokot.app.data.AtomicFiles
import ru.stenokot.app.data.AttemptLog
import ru.stenokot.app.data.CoreStages
import ru.stenokot.app.data.DiarJournal
import ru.stenokot.app.data.JobFiles
import ru.stenokot.app.data.JobPhase
import ru.stenokot.app.data.JobRecord
import ru.stenokot.app.data.JobStore
import ru.stenokot.app.data.LangDocument
import ru.stenokot.app.data.MergedDocument
import ru.stenokot.app.data.OutputRecord
import ru.stenokot.app.data.TurnsDocument
import ru.stenokot.app.data.VadDocument
import ru.stenokot.app.data.toLine
import ru.stenokot.app.data.toRecord
import ru.stenokot.app.data.toSegment
import ru.stenokot.app.data.toTurn
import ru.stenokot.app.data.toUtterance
import ru.stenokot.core.contract.TranscriptEncoder
import ru.stenokot.core.contract.ValidationResult
import ru.stenokot.core.engine.Asr
import ru.stenokot.core.engine.PcmReader
import ru.stenokot.core.engine.Progress
import ru.stenokot.core.lang.LanguageVote
import ru.stenokot.core.metrics.RtfCalculator
import ru.stenokot.core.model.AsrSegment
import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.Language
import ru.stenokot.core.model.PauseReason
import ru.stenokot.core.model.SpeakerTurn
import ru.stenokot.core.model.SpeechSegment
import ru.stenokot.core.model.Stage
import ru.stenokot.core.storage.AsrJournalLine
import ru.stenokot.core.storage.DiarJournalLine
import ru.stenokot.core.storage.LanguageVotes
import ru.stenokot.core.storage.ServiceJson
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

/** Настройки конвейера. Значения по умолчанию заданы спецификацией, тесты их сокращают. */
data class RunnerConfig(
    val jsonPauseMs: Long = 3_000,
    val idleRetryMs: Long = 30_000,
    val llmStopGraceMs: Long = 1_000,
    val thermalPollMs: Long = 2_000,
    val severeThermalPauseMs: Long = 200,
    val autoResumeLimit: Int = 1,
)

/**
 * Стадии одной задачи от декодирования до контрактного файла. Выполняются строго по очереди:
 * модель стадии закрывается до начала следующей, вход и выход каждой стадии лежат в файлах.
 * Поэтому продолжение после смерти процесса начинается с первой незавершённой стадии.
 */
class JobPipeline(
    private val book: JobBook,
    private val store: JobStore,
    private val engines: PipelineEngines,
    private val device: DeviceMonitor,
    private val outputs: OutputGateway,
    private val metrics: MetricsRecorder,
    private val board: LiveBoard,
    private val clock: () -> Long,
    private val config: RunnerConfig,
) {
    /** Проводит задачу [id] до записанного контрактного файла. */
    suspend fun run(id: String, control: JobControl) {
        invalidateStaleCheckpoints(id)
        Run(id, store.files(id), control).use { run ->
            for (stage in CoreStages) {
                if (stage in book.require(id).completedStages) continue
                run.boundary(stage)
                measured(run, stage) { execute(run, stage) }
            }
        }
        book.mutate(id) { job -> job.copy(coreMs = job.stageMs.filterKeys { it in CoreStages }.values.sum()) }
    }

    /** То же правило, что и в JobFile ядра: смена настроек или моделей отменяет всё после декодирования. */
    private suspend fun invalidateStaleCheckpoints(id: String) {
        val job = book.require(id)
        val stale = job.configHash != null && job.configHash != engines.configHash
        if (stale && !job.transcriptSaved) {
            val files = store.files(id)
            AsrJournal(files.asr).clear()
            DiarJournal(files.diar).clear()
            listOf(files.vad, files.lang, files.turns, files.merged, files.asrAttempt, files.diarAttempt)
                .forEach(File::delete)
            val kept = job.completedStages.filter { it <= Stage.DECODE }.toSet()
            book.mutate(id) { it.copy(completedStages = kept, noSpeech = false) }
        }
        book.mutate(id) { it.copy(configHash = engines.configHash) }
    }

    private suspend fun measured(run: Run, stage: Stage, body: suspend () -> Unit) {
        val id = run.id
        val available = device.availableMemoryBytes()
        val thermalStart = device.thermalStatus()
        val startedAt = clock()
        val finishedMs = book.require(id).stageMs.values.sum()
        val lowMemory = available < StageBudgets.memoryBytes(stage)
        book.mutate(id) { job ->
            val moved = job.copy(phase = JobPhase.RUNNING, stage = stage, pauseReason = null, errorCode = null, errorDetail = null)
            if (lowMemory) moved.warned(ErrorCode.LOW_MEMORY.name) else moved
        }
        board.enter(id, stage, finishedMs)
        if (lowMemory) board.markLowMemory()
        body()
        val took = clock() - startedAt
        val peak = device.peakRssKb()
        val job = book.require(id)
        metrics.stage(
            run.files,
            StageMetrics(
                stage = stage,
                startedAtMs = startedAt,
                tookMs = took,
                rtf = job.durationMs?.let { RtfCalculator.stage(took, it) },
                availMemMb = available / BYTES_IN_MB,
                thermalStart = thermalStart,
                thermalEnd = device.thermalStatus(),
                vmHwmKb = peak,
            ),
        )
        book.mutate(id) { current ->
            current.copy(
                completedStages = current.completedStages + stage,
                stageMs = current.stageMs + (stage to (current.stageMs[stage] ?: 0L) + took),
                peakRssKb = listOfNotNull(current.peakRssKb, peak).maxOrNull(),
            )
        }
    }

    private suspend fun execute(run: Run, stage: Stage) = when (stage) {
        Stage.DECODE -> decode(run)
        Stage.VAD -> vad(run)
        Stage.LANG_ID -> languageId(run)
        Stage.ASR -> recognize(run)
        Stage.DIARIZE -> diarize(run)
        Stage.MERGE -> merge(run)
        Stage.VALIDATE -> validateAndSave(run)
        Stage.IMPORT, Stage.LLM -> error("Стадия $stage выполняется вне этого цикла")
    }

    private suspend fun decode(run: Run) {
        val job = book.require(run.id)
        val sourceName = job.sourceFile
            ?: throw EngineFailure(ErrorCode.IMPORT_READ_FAILED, "Нет копии исходного файла")
        val tmp = File(run.files.dir, run.files.pcm.name + ".tmp")
        tmp.delete()
        val info = run.control.stoppable {
            engines.decode(File(run.files.dir, sourceName), tmp, stageProgress())
        }
        AtomicFiles.moveInto(tmp, run.files.pcm)
        book.mutate(run.id) { it.copy(audio = info.toRecord()) }
    }

    private suspend fun vad(run: Run) {
        val segments = loading { engines.vad() }.use { it.segments(run.pcm, stageProgress()) }
        val document = VadDocument(segments.map { it.toLine() })
        AtomicFiles.replaceText(run.files.vad, ServiceJson.encodeToString(VadDocument.serializer(), document))
        if (segments.isEmpty()) {
            book.mutate(run.id) { job ->
                job.copy(
                    noSpeech = true,
                    completedStages = job.completedStages + setOf(Stage.LANG_ID, Stage.ASR, Stage.DIARIZE),
                ).warned(ErrorCode.AUDIO_EMPTY.name)
            }
        }
    }

    /**
     * Язык определяется первыми словами на русском пути: модель уже загружена, поэтому в типичном
     * русском случае нет лишней загрузки. Каждый пробный срез сразу идёт в журнал ASR, как и любой
     * другой, поэтому прерывание здесь ничего не теряет. Английский итог очищает журнал. Сбой стадии
     * не смертелен: запись идёт русским маршрутом с предупреждением.
     */
    private suspend fun languageId(run: Run) {
        val segments = readSegments(run.files)
        val probes = LanguageProbes.pick(segments)
        val asr = run.asr(Language.RU)
        val journal = AsrJournal(run.files.asr)
        val known = journal.restore().associateBy { it.index }
        val results = ArrayList<AsrSegment>(probes.size)
        var language = Language.RU
        var votes: LanguageVotes? = null
        var failed = false
        try {
            probes.forEachIndexed { index, probe ->
                run.boundary(Stage.LANG_ID)
                val restored = known[probe.index]?.takeUnless { it.skipped }?.toSegment()
                results += restored ?: asr.transcribe(run.pcm, probe).also { journal.append(AsrJournalLine.of(it)) }
                board.progress((index + 1f) / probes.size, index + 1, probes.size)
            }
            val detector = engines.languageDetector()
            language = detector.detect(results)
            votes = (detector as? LanguageVote)?.tally(results)?.let { LanguageVotes(it.ru, it.en, it.chunks) }
        } catch (stop: JobStopped) {
            throw stop
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failed = true
            language = Language.RU
        }
        val document = LangDocument(language, failed, results.size)
        if (language != Language.RU) journal.clear()
        AtomicFiles.replaceText(run.files.lang, ServiceJson.encodeToString(LangDocument.serializer(), document))
        book.mutate(run.id) { job ->
            val updated = job.copy(language = language, langFailed = failed, langVotes = votes)
            if (failed) updated.warned(ErrorCode.LANG_ID_FAILED.name) else updated
        }
    }

    private suspend fun recognize(run: Run) {
        val language = book.require(run.id).language ?: Language.RU
        val segments = readSegments(run.files)
        val journal = AsrJournal(run.files.asr)
        val done = journal.restore().map { it.index }.toMutableSet()
        val attempts = AttemptLog(run.files.asrAttempt)
        val asr = run.asr(language)
        val total = segments.size
        var finished = segments.count { it.index in done }
        board.progress(fraction(finished, total), finished, total, force = true)
        for (segment in segments) {
            if (segment.index in done) continue
            run.boundary(Stage.ASR)
            recognizeOne(run, asr, segment, journal, attempts)
            done += segment.index
            finished += 1
            board.progress(fraction(finished, total), finished, total)
        }
        run.closeAsr()
    }

    /** Срез получает две попытки. Падение процесса считается попыткой благодаря [AttemptLog]. */
    private suspend fun recognizeOne(
        run: Run,
        asr: Asr,
        segment: SpeechSegment,
        journal: AsrJournal,
        attempts: AttemptLog,
    ) {
        while (attempts.begin(segment.index)) {
            val result = try {
                asr.transcribe(run.pcm, segment)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                continue
            }
            journal.append(AsrJournalLine.of(result))
            return
        }
        journal.append(AsrJournalLine(segment.index, segment.startSample, segment.endSample, "", skipped = true))
        book.mutate(run.id) { it.copy(skippedSegments = it.skippedSegments + segment.index) }
    }

    /**
     * Окна ведёт сам диаризатор, конвейер пишет чекпойнт в его обратном вызове. Падение считается
     * попыткой окна: на третий раз окно записывается пустым и пропускается, иначе запись, убивающая
     * процесс, не дала бы закончить файл. Сбой с кодом, например нет модели, окном не лечится.
     */
    private suspend fun diarize(run: Run) {
        val journal = DiarJournal(run.files.diar)
        val done = journal.restore().toMutableList()
        val attempts = AttemptLog(run.files.diarAttempt)
        var turns: List<SpeakerTurn>? = null
        while (turns == null) {
            val index = done.size
            if (!attempts.begin(index)) {
                val empty = DiarJournalLine(index, 0, 0, emptyList(), emptyList())
                journal.append(empty)
                done += empty
                book.mutate(run.id) { it.copy(skippedWindows = it.skippedWindows + index) }
                continue
            }
            run.boundary(Stage.DIARIZE)
            turns = diarizeFrom(run, journal, done)
        }
        val finished = turns.ifEmpty { wholeRecordingTurn(run) }
        val document = TurnsDocument(finished.map { it.toLine() })
        AtomicFiles.replaceText(run.files.turns, ServiceJson.encodeToString(TurnsDocument.serializer(), document))
    }

    /** Один проход диаризатора. Возвращает null, если проход оборвался и окно надо повторить. */
    private fun diarizeFrom(run: Run, journal: DiarJournal, done: MutableList<DiarJournalLine>): List<SpeakerTurn>? {
        val request = DiarizationRequest(
            restored = done.toList(),
            onWindow = { line ->
                journal.append(line)
                done += line
            },
            isCancelled = run.control::isStopRequested,
            sourceSampleRate = book.require(run.id).audio?.sourceSampleRate,
        )
        val diarizer = loading { engines.diarizer(request) }
        return try {
            diarizer.use { it.diarize(run.pcm, stageProgress()) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            run.control.check()
            if (engines.errorCode(failure) != null) throw failure
            null
        }
    }

    /**
     * Запасной интервал: если говорящих нет совсем, текст остаётся, а реплики получают одного
     * говорящего. Пропущенные окна уже отмечены в metadata.json.
     */
    private fun wholeRecordingTurn(run: Run): List<SpeakerTurn> =
        listOf(SpeakerTurn(0, book.require(run.id).durationMs ?: 0, 0))

    private suspend fun merge(run: Run) {
        val job = book.require(run.id)
        val utterances = if (job.noSpeech) {
            emptyList()
        } else {
            val asr = AsrJournal(run.files.asr).restore()
                .filterNot { it.skipped }
                .sortedBy { it.index }
                .map { it.toSegment() }
            val turns = ServiceJson.decodeFromString(TurnsDocument.serializer(), run.files.turns.readText())
            engines.merger(job.durationMs ?: 0).merge(asr, turns.turns.map { it.toTurn() })
        }
        val document = MergedDocument(utterances.map { it.toLine() })
        AtomicFiles.replaceText(run.files.merged, ServiceJson.encodeToString(MergedDocument.serializer(), document))
        book.mutate(run.id) { current ->
            val updated = current.copy(
                speakers = utterances.map { it.speaker }.distinct().size,
                utterances = utterances.size,
            )
            if (utterances.isEmpty()) {
                updated.copy(noSpeech = true).warned(ErrorCode.AUDIO_EMPTY.name)
            } else {
                updated
            }
        }
    }

    /** Проверяются именно те байты, что будут записаны. При провале файл не появляется нигде. */
    private suspend fun validateAndSave(run: Run) {
        val job = book.require(run.id)
        val utterances = store.readUtterances(run.id).map { it.toUtterance() }
        val bytes = TranscriptEncoder.encode(utterances)
        val verdict = engines.validator().validate(bytes, job.durationMs ?: 0)
        if (verdict is ValidationResult.Invalid) {
            throw EngineFailure(ErrorCode.CONTRACT_INVALID, verdict.errors.take(5).joinToString("; "))
        }
        AtomicFiles.replace(run.files.transcript, bytes)
        val published = publish(job, bytes, run.files)
        book.mutate(run.id) { current ->
            val updated = current.copy(output = published.first)
            if (published.second) updated.warned(OUTPUT_RENAMED_WARNING) else updated
        }
    }

    /** Возвращает запись о файле и признак того, что нужное имя занято чужим файлом. */
    private suspend fun publish(job: JobRecord, bytes: ByteArray, files: JobFiles): Pair<OutputRecord, Boolean> {
        val sha = sha256(bytes)
        val createdAt = clock()
        return try {
            val published = outputs.publishTranscript(job.stem, bytes)
            OutputRecord(
                path = published.path,
                uri = published.uri,
                sizeBytes = bytes.size.toLong(),
                createdAtMs = createdAt,
                validated = true,
                sha256 = sha,
                fallback = published.fallback,
            ) to published.renamed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            OutputRecord(
                path = files.transcript.path,
                sizeBytes = bytes.size.toLong(),
                createdAtMs = createdAt,
                validated = true,
                sha256 = sha,
                exportFailed = true,
            ) to false
        }
    }

    private fun stageProgress() = Progress { value, note -> board.report(value, note) }

    private fun readSegments(files: JobFiles): List<SpeechSegment> =
        ServiceJson.decodeFromString(VadDocument.serializer(), files.vad.readText()).segments.map { it.toSegment() }

    private fun fraction(done: Int, total: Int): Float =
        if (total <= 0) 1f else done.toFloat() / total

    private inline fun <T> loading(create: () -> T): T = try {
        create()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        if (engines.errorCode(failure) != null) throw failure
        throw EngineFailure(ErrorCode.MODEL_LOAD_FAILED, failure.message, failure)
    }

    /** Ресурсы текущего прогона. Закрываются при любом исходе. */
    private inner class Run(val id: String, val files: JobFiles, val control: JobControl) : Closeable {
        private var reader: PcmReader? = null
        private var asrEngine: Asr? = null
        private var asrLanguage: Language? = null

        val pcm: PcmReader
            get() = reader ?: engines.openPcm(files.pcm).also { reader = it }

        /** Возвращает распознаватель языка [language], заменяя загруженный при необходимости. */
        fun asr(language: Language): Asr {
            if (asrLanguage == language) return checkNotNull(asrEngine)
            closeAsr()
            return loading { engines.asr(language) }.also {
                asrEngine = it
                asrLanguage = language
            }
        }

        /** Освобождает память модели распознавания до следующей стадии. */
        fun closeAsr() {
            asrEngine?.close()
            asrEngine = null
            asrLanguage = null
        }

        /** Безопасная граница: здесь срабатывают отмена, прерывание и пауза из-за перегрева. */
        suspend fun boundary(stage: Stage) {
            control.check()
            val status = device.thermalStatus()
            if (status >= THERMAL_CRITICAL) {
                waitForCooling(stage)
            } else if (status == THERMAL_SEVERE) {
                delay(config.severeThermalPauseMs)
            }
            control.check()
        }

        private suspend fun waitForCooling(stage: Stage) {
            book.mutate(id) { job ->
                job.copy(phase = JobPhase.PAUSED, stage = stage, pauseReason = PauseReason.THERMAL)
                    .warned(ErrorCode.THERMAL_CRITICAL.name)
            }
            board.markPaused(true)
            while (device.thermalStatus() >= THERMAL_CRITICAL) {
                delay(config.thermalPollMs)
                control.check()
            }
            board.markPaused(false)
            book.mutate(id) { it.copy(phase = JobPhase.RUNNING, pauseReason = null) }
        }

        override fun close() {
            closeAsr()
            reader?.close()
            reader = null
        }
    }

    private companion object {
        const val THERMAL_SEVERE = 3
        const val THERMAL_CRITICAL = 4
        const val BYTES_IN_MB = 1024L * 1024
    }
}

/** Предупреждение: контрактный файл пришлось записать под другим именем. */
const val OUTPUT_RENAMED_WARNING: String = "OUTPUT_RENAMED"

/** Считает SHA-256 и возвращает шестнадцатеричную строку. */
fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
