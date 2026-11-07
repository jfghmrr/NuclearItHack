package ru.stenokot.app.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.stenokot.app.data.AtomicFiles
import ru.stenokot.app.data.JobPhase
import ru.stenokot.app.data.JobRecord
import ru.stenokot.app.data.JobReconciler
import ru.stenokot.app.data.JobStore
import ru.stenokot.app.data.LlmRecord
import ru.stenokot.app.data.SpeedBook
import ru.stenokot.core.engine.Progress
import ru.stenokot.core.llm.LlmBudgetPolicy
import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.LlmResult
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.model.Stage
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** Почему остановлена стадия языковой модели. */
private enum class LlmStop { NEW_JOB, USER }

/**
 * Очередь задач и последовательность стадий.
 *
 * Один рабочий поток берёт задачи по порядку поступления, поэтому две тяжёлые модели не встретятся
 * никогда. Копия импортированного файла делается отдельно и сразу: права на чужой адрес живут
 * недолго, а задача может ждать своей очереди часами. Языковая модель идёт после контрактного файла
 * и всегда уступает новому импорту.
 */
class PipelineRunner(
    private val store: JobStore,
    private val engines: PipelineEngines,
    private val device: DeviceMonitor,
    private val sources: SourceGateway,
    private val outputs: OutputGateway,
    private val modelGate: LlmModelGate,
    private val metrics: MetricsRecorder,
    private val speed: SpeedBook,
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val config: RunnerConfig = RunnerConfig(),
    onStatus: (String) -> Unit = {},
) {
    private val book = JobBook(store, onStatus)
    private val board = LiveBoard(clock)
    private val pipeline = JobPipeline(book, store, engines, device, outputs, metrics, board, clock, config)
    private val controls = ConcurrentHashMap<String, JobControl>()
    private val intake = Channel<String>(Channel.UNLIMITED)
    private val intakePending: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val ready = CompletableDeferred<Unit>()
    private val held = MutableStateFlow(true)
    private val llmStop = MutableStateFlow<LlmStop?>(null)

    @Volatile
    private var llmPhaseActive = false

    @Volatile
    private var llmRunning = false

    /** Все задачи. */
    val jobs: StateFlow<Map<String, JobRecord>> = book.flow

    /** Живой прогресс активной задачи. */
    val live: StateFlow<LiveProgress?> = board.flow

    /** Есть работа, ради которой служба должна оставаться запущенной. */
    val busy: Flow<Boolean> = combine(book.flow, held) { all, hold -> hasWork(all.values, hold) }
        .distinctUntilChanged()

    /** Загружает задачи с диска и запускает рабочие циклы. Вызывается один раз. */
    fun launch() {
        scope.launch(io) { load() }
        scope.launch(io) { intakeLoop() }
        scope.launch(worker) {
            ready.await()
            workerLoop()
        }
        scope.launch(io) { tickLoop() }
        scope.launch(io) { modelCopyGateLoop() }
    }

    /** Ждёт окончания загрузки задач. */
    suspend fun awaitReady() = ready.await()

    /** Принимает файл. Запись появляется сразу, диск и система затрагиваются в фоне. */
    fun enqueue(id: String, uri: String, title: String, stem: String, createdAtMs: Long, seq: Long): JobRecord {
        val record = JobRecord(
            id = id,
            seq = seq,
            title = title,
            stem = stem,
            sourceUri = uri,
            createdAtMs = createdAtMs,
        )
        book.add(record)
        held.value = false
        scope.launch(io) {
            ready.await()
            book.persist(id)
            sendToIntake(id)
        }
        announceWork()
        return record
    }

    /** Запускает задачу. Для задачи в работе вызов ничего не меняет. */
    fun start(id: String) {
        scope.launch(io) {
            ready.await()
            val job = book.get(id) ?: return@launch
            when (job.phase) {
                JobPhase.FAILED, JobPhase.CANCELLED, JobPhase.INTERRUPTED -> requeue(id)
                else -> Unit
            }
        }
    }

    /** Продолжает прерванную задачу с последнего чекпойнта. */
    fun resume(id: String) = start(id)

    /** Отменяет задачу на ближайшей безопасной границе. */
    fun cancel(id: String) {
        val job = book.get(id) ?: return
        when {
            job.phase == JobPhase.TRANSCRIPT_READY || job.phase == JobPhase.WAITING_MODEL ||
                (job.phase == JobPhase.RUNNING && job.stage == Stage.LLM) -> llmStop.value = LlmStop.USER
            job.phase == JobPhase.QUEUED -> {
                control(id).request(StopRequest.Cancel)
                scope.launch(io) { markCancelled(id) }
            }
            JobQueue.isActive(job) -> {
                control(id).request(StopRequest.Cancel)
                board.markCancelling(id)
            }
            else -> Unit
        }
    }

    /** Отменяет задачу, которая сейчас занимает конвейер. */
    fun cancelActive() {
        book.snapshot().firstOrNull(JobQueue::isActive)?.let { cancel(it.id) }
    }

    /** Удаляет задачу. Работающая задача сначала останавливается на безопасной границе. */
    fun delete(id: String, removeExported: Boolean) {
        scope.launch(io) {
            val job = book.get(id) ?: return@launch
            if (JobQueue.isActive(job) || job.awaitsWorker) {
                cancel(id)
                book.flow.first { all -> all[id]?.let { !JobQueue.isActive(it) && !it.awaitsWorker } ?: true }
            }
            book.remove(id)
            controls.remove(id)
            store.delete(id)
            if (removeExported) {
                runCatching { outputs.removePublished(job.stem, job.output?.uri) }
            }
        }
    }

    /**
     * Приложение вышло на передний план. Только здесь можно запускать службу после смерти процесса,
     * поэтому очередь отпускается и прерванные задачи продолжаются сами, не больше
     * [RunnerConfig.autoResumeLimit] раз. Дальше решает пользователь кнопкой «Продолжить»: иначе
     * файл, убивающий процесс, повторялся бы без конца.
     */
    fun onForeground() {
        scope.launch(io) {
            ready.await()
            held.value = false
            book.snapshot()
                .filter { it.phase == JobPhase.INTERRUPTED && it.autoResumes < config.autoResumeLimit }
                .sortedBy { it.seq }
                .forEach { job ->
                    book.mutate(job.id) { it.copy(autoResumes = it.autoResumes + 1) }
                    requeue(job.id)
                }
            book.snapshot()
                .filter { it.phase == JobPhase.QUEUED && Stage.IMPORT !in it.completedStages }
                .sortedBy { it.seq }
                .forEach { sendToIntake(it.id) }
            wake.trySend(Unit)
        }
    }

    /**
     * Система просит остановить службу. Задача сохраняет чекпойнт на ближайшей границе и
     * становится прерванной, следующие задачи ждут выхода приложения на передний план.
     */
    fun interruptForTimeout() {
        held.value = true
        val active = book.snapshot().firstOrNull(JobQueue::isActive) ?: return
        if (active.transcriptSaved) {
            llmStop.value = LlmStop.NEW_JOB
        } else {
            control(active.id).request(StopRequest.Interrupt(ErrorCode.FGS_TIMEOUT))
        }
    }

    /** Обновляет запись задачи после экспорта. */
    suspend fun updateJob(id: String, change: (JobRecord) -> JobRecord) {
        book.mutate(id, change)
    }

    private fun hasWork(all: Collection<JobRecord>, hold: Boolean): Boolean {
        if (hold) return all.any { JobQueue.isActive(it) && !it.awaitsWorker }
        return all.any { JobQueue.isActive(it) || it.awaitsWorker } || JobQueue.nextRetry(all) != null
    }

    private suspend fun load() {
        val exitCode = device.lastExitCode()
        val loaded = store.loadAll().map { record ->
            JobReconciler.reconcile(record, store.files(record.id), exitCode)
        }
        book.merge(loaded)
        loaded.forEach { book.persist(it.id) }
        ready.complete(Unit)
        wake.trySend(Unit)
    }

    private suspend fun tickLoop() {
        board.flow.map { it != null }.distinctUntilChanged().collectLatest { active ->
            while (active) {
                delay(TICK_MS)
                board.tick()
            }
        }
    }

    /**
     * Копия модели уступает всему, что идёт до контрактного файла, начиная с приёма файла: сотни
     * мегабайт записи на диск задерживают на секунды каждый fsync копии импорта и журналов.
     */
    private suspend fun modelCopyGateLoop() {
        book.flow.map { all -> all.values.any { !it.transcriptSaved && (it.awaitsWorker || JobQueue.isActive(it)) } }
            .distinctUntilChanged()
            .dropWhile { !it }
            .collect(modelGate::setBusy)
    }

    private suspend fun sendToIntake(id: String) {
        if (intakePending.add(id)) intake.send(id)
    }

    private suspend fun intakeLoop() {
        for (id in intake) {
            try {
                importOne(id)
            } finally {
                intakePending.remove(id)
            }
            wake.trySend(Unit)
        }
    }

    /**
     * Предварительная проверка. Сначала имя и размер, затем копия, затем пробное чтение копии: всё
     * это до загрузки моделей, поэтому плохой файл отсеивается здесь. Копия нужна раньше пробы ещё и
     * потому, что права на чужой адрес живут недолго.
     */
    private suspend fun importOne(id: String) {
        val control = control(id)
        val job = book.get(id) ?: return
        if (job.phase != JobPhase.QUEUED) return
        val startedAt = clock()
        val visible = book.snapshot().none { it.id != id && JobQueue.isActive(it) }
        if (visible) {
            book.mutate(id) { it.copy(phase = JobPhase.RUNNING, stage = Stage.IMPORT) }
            board.enter(id, Stage.IMPORT, 0)
        }
        try {
            control.check()
            val info = control.stoppable { sources.describe(job) }
            val named = book.mutate(id) { current ->
                current.copy(
                    title = info.name,
                    stem = StemRules.stem(info.name),
                    preflight = preflightRecord(info, info.sizeBytes, null),
                )
            } ?: return
            requireSpace(named)
            val source = copySource(id, named, control, visible)
            control.check()
            val probe = control.stoppable { engines.probe(source) }
            val checked = book.mutate(id) { current ->
                val record = preflightRecord(info, source.length(), probe)
                val lowMemory = device.availableMemoryBytes() < StageBudgets.memoryBytes(Stage.ASR)
                current.copy(preflight = record).let { if (lowMemory) it.warned(ErrorCode.LOW_MEMORY.name) else it }
            } ?: return
            requireSpace(checked)
            book.mutate(id) { current ->
                current.copy(
                    sourceFile = source.name,
                    completedStages = current.completedStages + Stage.IMPORT,
                    stageMs = current.stageMs + (Stage.IMPORT to clock() - startedAt),
                )
            }
        } catch (stop: JobStopped) {
            markCancelled(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            discardSource(id)
            val code = engines.errorCode(failure) ?: ErrorCode.IMPORT_READ_FAILED
            fail(id, Stage.IMPORT, code, failure.message ?: failure.toString())
        }
    }

    private fun preflightRecord(info: SourceInfo, sizeBytes: Long, probe: SourceProbe?) = PreflightRules.record(
        fileName = info.name,
        sizeBytes = sizeBytes,
        durationMs = probe?.durationMs,
        mime = probe?.mime ?: info.mime,
        freeBytes = sources.freeBytes(),
        speed = speed.current(),
    )

    private fun requireSpace(job: JobRecord) {
        val record = job.preflight ?: return
        if (PreflightRules.shortfallBytes(record.requiredBytes, record.freeBytes) > 0) {
            throw EngineFailure(ErrorCode.STORAGE_LOW, PreflightRules.storageDetail(record))
        }
    }

    /** Отвергнутая копия не должна занимать место, ради которого пользователя просят его освободить. */
    private fun discardSource(id: String) {
        val files = store.files(id)
        files.dir.listFiles { file -> file.name.startsWith("source") }?.forEach(File::delete)
    }

    private suspend fun copySource(id: String, job: JobRecord, control: JobControl, visible: Boolean): File {
        val files = store.files(id)
        val target = files.source(StemRules.extension(job.title))
        val tmp = File(files.dir, target.name + ".tmp")
        tmp.parentFile?.mkdirs()
        control.stoppable {
            sources.copy(job, tmp) { copied, total ->
                if (visible && total > 0) board.progress(copied.toFloat() / total)
            }
        }
        AtomicFiles.moveInto(tmp, target)
        return target
    }

    private suspend fun workerLoop() {
        while (true) {
            val all = book.snapshot()
            if (!held.value) {
                val next = JobQueue.next(all)
                if (next != null) {
                    if (Stage.IMPORT in next.completedStages) {
                        runJob(next.id)
                        continue
                    }
                } else {
                    val retry = JobQueue.nextRetry(all)
                    if (retry != null) {
                        val interrupted = withTimeoutOrNull(config.idleRetryMs) { wake.receive() }
                        if (interrupted == null) llmPhase(retry.id, isRetry = true)
                        continue
                    }
                }
            }
            wake.receive()
        }
    }

    private suspend fun runJob(id: String) {
        val control = control(id)
        val finished = try {
            pipeline.run(id, control)
            true
        } catch (stop: JobStopped) {
            stopped(id, stop.request)
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: OutOfMemoryError) {
            fail(id, currentStage(id), ErrorCode.LOW_MEMORY, failure.toString())
            false
        } catch (failure: Exception) {
            val stage = currentStage(id)
            val code = engines.errorCode(failure) ?: StageBudgets.failureCode(stage)
            fail(id, stage, code, failure.message ?: failure.toString())
            false
        } finally {
            controls.remove(id)
        }
        board.clear(id)
        if (finished) afterTranscript(id)
    }

    private fun currentStage(id: String): Stage = book.require(id).stage

    private suspend fun stopped(id: String, request: StopRequest) {
        when (request) {
            StopRequest.Cancel -> markCancelled(id)
            is StopRequest.Interrupt -> {
                book.mutate(id) { it.copy(phase = JobPhase.INTERRUPTED, errorCode = request.code, pauseReason = null) }
            }
        }
    }

    private suspend fun afterTranscript(id: String) {
        val job = book.require(id)
        recordSpeed(job)
        if (job.noSpeech) {
            finalize(id)
            return
        }
        book.mutate(id) { it.copy(phase = JobPhase.TRANSCRIPT_READY, stage = Stage.LLM) }
        writeMetadata(id)
        llmPhase(id, isRetry = false)
    }

    /**
     * Стадия языковой модели. Контрактный файл к этому моменту уже записан, поэтому исход здесь
     * определяет только статус протокола и никогда не делает задачу неудачной.
     */
    private suspend fun llmPhase(id: String, isRetry: Boolean) {
        llmPhaseActive = true
        llmStop.value = if (JobQueue.next(book.snapshot()) == null) null else LlmStop.NEW_JOB
        val startedAt = clock()
        val result = if (llmStop.value == null) attemptLlm(id, isRetry) else null
        val stop = llmStop.value
        llmPhaseActive = false
        val took = clock() - startedAt
        book.mutate(id) { job ->
            val settled = settle(job.llm, stop, result, isRetry)
            val stats = metrics.llmStats(store.files(id))
            job.copy(
                phase = JobPhase.DONE,
                stage = Stage.LLM,
                llm = settled.copy(
                    tookMs = job.llm.tookMs + took,
                    promptTokens = stats?.promptTokens ?: job.llm.promptTokens,
                    completionTokens = stats?.completionTokens ?: job.llm.completionTokens,
                    windowsDone = stats?.windowsDone ?: job.llm.windowsDone,
                    windowsTotal = stats?.windowsTotal ?: job.llm.windowsTotal,
                ),
            )
        }
        board.clear(id)
        finalize(id)
    }

    private fun settle(prior: LlmRecord, stop: LlmStop?, result: LlmResult?, isRetry: Boolean): LlmRecord =
        when (stop) {
            LlmStop.USER -> LlmRules.cancelledByUser(prior, result?.protocolPath)
            LlmStop.NEW_JOB -> LlmRules.settle(
                prior,
                LlmResult(LlmStatus.DEFERRED, result?.protocolPath, null),
                isRetry,
            )
            null -> LlmRules.settle(
                prior,
                result ?: LlmResult(LlmStatus.FAILED, null, null),
                isRetry,
            )
        }

    /**
     * Один запуск модели. Сторож следит за остановкой: если модель уже считает, отмена идёт через
     * [ru.stenokot.core.engine.LlmRunner.cancel] и на завершение даётся секунда. Дольше новый файл не
     * ждёт, поэтому вызов прерывается, а реализация запуска завершает процесс модели.
     */
    private suspend fun attemptLlm(id: String, isRetry: Boolean): LlmResult? = coroutineScope {
        val body = async { llmBody(id, isRetry) }
        val watcher = launch {
            llmStop.first { it != null }
            if (llmRunning) {
                engines.llm.cancel()
                withTimeoutOrNull(config.llmStopGraceMs) { body.join() }
            }
            body.cancel()
        }
        try {
            body.await()
        } catch (cancelled: CancellationException) {
            if (llmStop.value == null) throw cancelled
            null
        } finally {
            watcher.cancel()
        }
    }

    private suspend fun llmBody(id: String, isRetry: Boolean): LlmResult {
        if (!isRetry) delay(config.jsonPauseMs)
        if (device.availableMemoryBytes() < LlmRules.MIN_MEMORY_BYTES) {
            return LlmResult(LlmStatus.SKIPPED_LOW_MEMORY, null, ErrorCode.LLM_LOW_MEMORY.name)
        }
        if (modelGate.state() != LlmModelState.READY) {
            if (modelGate.state() == LlmModelState.PENDING) {
                book.mutate(id) { it.copy(phase = JobPhase.WAITING_MODEL, stage = Stage.LLM) }
            }
            if (modelGate.await() != LlmModelState.READY) {
                return LlmResult(LlmStatus.FAILED, null, ErrorCode.LLM_MODEL_UNAVAILABLE.name)
            }
        }
        val job = book.mutate(id) { it.copy(phase = JobPhase.RUNNING, stage = Stage.LLM) } ?: book.require(id)
        board.enter(id, Stage.LLM, job.coreMs)
        val budget = LlmBudgetPolicy.budget(job.durationMs ?: 0, job.coreMs)
        val progress = Progress { value, note -> board.report(value, note) }
        llmRunning = true
        return try {
            engines.llm.run(store.files(id).dir, budget, progress)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            LlmResult(LlmStatus.FAILED, null, (engines.errorCode(failure) ?: ErrorCode.LLM_DIED_NATIVE).name)
        } finally {
            llmRunning = false
        }
    }

    private suspend fun finalize(id: String) {
        book.mutate(id) { it.copy(phase = JobPhase.DONE, stage = Stage.LLM) }
        writeMetadata(id)
        publishProtocol(id)
    }

    private suspend fun writeMetadata(id: String) {
        val job = book.require(id)
        val files = store.files(id)
        runCatching {
            metrics.writeMetadata(job, files)
            outputs.publishMeta(job.stem, MetaKind.METADATA, files.metadata)
        }.onFailure { rethrowCancellation(it) }
    }

    private suspend fun publishProtocol(id: String) {
        val job = book.require(id)
        val files = store.files(id)
        if (!files.protocol.exists()) return
        runCatching { outputs.publishMeta(job.stem, MetaKind.PROTOCOL, files.protocol) }
            .onFailure { rethrowCancellation(it) }
    }

    private fun rethrowCancellation(failure: Throwable) {
        if (failure is CancellationException) throw failure
    }

    private fun recordSpeed(job: JobRecord) {
        val audioMs = job.durationMs ?: return
        if (!job.resumed) runCatching { speed.record(job.stageMs, audioMs) }
    }

    private suspend fun requeue(id: String) {
        controls.remove(id)
        val job = book.mutate(id) { current ->
            current.copy(
                phase = JobPhase.QUEUED,
                errorCode = null,
                errorDetail = null,
                pauseReason = null,
                resumed = Stage.DECODE in current.completedStages,
            )
        } ?: return
        if (Stage.IMPORT !in job.completedStages) sendToIntake(id)
        held.value = false
        announceWork()
    }

    private suspend fun markCancelled(id: String) {
        book.mutate(id) { it.copy(phase = JobPhase.CANCELLED, pauseReason = null) }
        board.clear(id)
    }

    private suspend fun fail(id: String, stage: Stage, code: ErrorCode, detail: String?) {
        book.mutate(id) { job ->
            job.copy(phase = JobPhase.FAILED, stage = stage, errorCode = code, errorDetail = detail, pauseReason = null)
        }
        board.clear(id)
    }

    /** Новая работа всегда важнее протокола: языковая модель сразу уступает ей место. */
    private fun announceWork() {
        if (llmPhaseActive) llmStop.compareAndSet(null, LlmStop.NEW_JOB)
        wake.trySend(Unit)
    }

    private fun control(id: String): JobControl = controls.getOrPut(id) { JobControl() }

    private companion object {
        const val TICK_MS = 1_000L
    }
}
