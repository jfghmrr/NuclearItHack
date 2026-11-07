package ru.stenokot.app.pipeline

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.stenokot.app.BuildConfig
import ru.stenokot.app.data.AudioDiscovery
import ru.stenokot.app.data.JobPhase
import ru.stenokot.app.data.JobRecord
import ru.stenokot.app.data.JobStore
import ru.stenokot.app.data.ResultPublisher
import ru.stenokot.app.data.SpeedBook
import ru.stenokot.app.data.toUtterance
import ru.stenokot.app.domain.AudioFileUi
import ru.stenokot.app.domain.ExportKind
import ru.stenokot.app.domain.HomeUi
import ru.stenokot.app.domain.JobDetailUi
import ru.stenokot.app.domain.ModelsUi
import ru.stenokot.app.domain.RecordingUi
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.domain.VerificationUi
import ru.stenokot.core.model.Utterance
import ru.stenokot.core.protocol.ProtocolDocument
import ru.stenokot.core.storage.ModelHash
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Связь интерфейса с конвейером. Все потоки значений идут отсюда, вызовы возвращаются сразу. Прогресс
 * уже ограничен табло, поэтому интерфейс обновляется не чаще раза в секунду.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealStenoController(
    context: Context,
    private val runner: PipelineRunner,
    private val recorder: MicRecorder,
    private val models: ModelPreparer,
    private val store: JobStore,
    private val publisher: ResultPublisher,
    private val discovery: AudioDiscovery,
    private val speed: SpeedBook,
    private val runContext: () -> RunContext,
    private val modelHashes: () -> List<ModelHash>,
    private val startService: () -> Unit,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) : StenoController {
    private val app = context.applicationContext
    private val audio = MutableStateFlow<List<AudioFileUi>>(emptyList())
    private val audioGranted = MutableStateFlow(false)
    private val session: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val jobFlows = ConcurrentHashMap<String, StateFlow<JobDetailUi?>>()
    private val lastSeq = AtomicLong(0)
    private val network = MutableStateFlow(NetworkAudit(internetAbsent = false, networkStateAbsent = false))

    @Volatile
    private var utteranceCache: Pair<String, List<Utterance>>? = null

    @Volatile
    private var protocolCache: Triple<String, Long, ProtocolDocument?>? = null

    override val home: StateFlow<HomeUi> =
        combine(models.models, audio, audioGranted, runner.jobs, runner.live) { modelsUi, files, granted, jobs, live ->
            val all = jobs.values
            HomeUi(
                models = modelsUi,
                audioPermissionGranted = granted,
                audio = files,
                jobs = all.sortedByDescending { it.seq }.map { JobUiMapper.summary(it, live, all) },
                statusCode = JobUiMapper.homeStatus(all, live, session),
            )
        }.stateIn(scope, SharingStarted.Eagerly, emptyHome())

    override val verification: StateFlow<VerificationUi> =
        combine(models.models, runner.jobs, network) { modelsUi, jobs, audit ->
            verificationUi(modelsUi, jobs.values, audit)
        }.stateIn(scope, SharingStarted.Eagerly, verificationUi(models.models.value, emptyList(), network.value))

    override val recording: StateFlow<RecordingUi> get() = recorder.state

    override val micLevel: StateFlow<Float> get() = recorder.level

    /** Первичные проверки, которым нужен диск или обращение к системе. */
    fun start() {
        scope.launch(io) { network.value = auditNetwork() }
        scope.launch(io) {
            runner.awaitReady()
            recorder.recover()
        }
        refreshAudio()
    }

    override fun startRecording() = recorder.requestStart()

    override fun stopRecording() = recorder.stop()

    override fun cancelRecording() = recorder.cancel()

    override fun acknowledgeRecording() = recorder.acknowledge()

    /** Приложение на экране: можно запускать службу и продолжать прерванное. */
    fun onForeground() {
        runner.onForeground()
        refreshAudio()
        scope.launch(io) {
            runner.awaitReady()
            if (hasPendingWork()) startService()
        }
    }

    override fun job(id: String): StateFlow<JobDetailUi?> = jobFlows.getOrPut(id) {
        detailFlow(id).stateIn(scope, SharingStarted.WhileSubscribed(FLOW_STOP_MS), firstDetail(id))
    }

    override fun import(uris: List<Uri>): String? {
        val ids = uris.map { uri ->
            val id = UUID.randomUUID().toString()
            val title = StemRules.titleFromUri(uri.lastPathSegment)
            val now = clock()
            val seq = lastSeq.updateAndGet { maxOf(it + 1, now) }
            session += id
            runner.enqueue(id, uri.toString(), title, StemRules.stem(title), now, seq)
            id
        }
        if (ids.isNotEmpty()) startService()
        return ids.firstOrNull()
    }

    override fun start(id: String) {
        session += id
        runner.start(id)
        startService()
    }

    override fun cancel(id: String) = runner.cancel(id)

    override fun resume(id: String) {
        session += id
        runner.resume(id)
        startService()
    }

    override fun delete(id: String, removeExported: Boolean) {
        session -= id
        jobFlows.remove(id)
        runner.delete(id, removeExported)
    }

    override fun export(id: String, kind: ExportKind, target: Uri) {
        scope.launch(io) {
            val files = store.files(id)
            val source = when (kind) {
                ExportKind.TRANSCRIPT -> files.transcript
                ExportKind.METADATA -> files.metadata
                ExportKind.PROTOCOL -> files.protocol
            }
            val failed = runCatching { publisher.export(source, target) }.isFailure
            runner.updateJob(id) { job ->
                job.copy(output = job.output?.copy(exportFailed = failed))
            }
        }
    }

    override fun refreshAudio() {
        scope.launch(io) {
            audioGranted.value = discovery.permissionGranted()
            audio.value = runCatching { discovery.scan() }.getOrDefault(emptyList())
        }
    }

    override fun diagnosticsPackage(id: String?): String {
        val run = runContext()
        val jobs = runner.jobs.value.values
        val chosen = if (id != null) listOfNotNull(jobs.firstOrNull { it.id == id }) else jobs.sortedBy { it.seq }
        val audit = network.value
        return Diagnostics.encode(
            DiagnosticPackage(
                appVersion = run.appVersion,
                commit = run.commit,
                device = run.device,
                apiLevel = run.apiLevel,
                totalMemBytes = run.totalMemBytes,
                internetAbsent = audit.internetAbsent,
                networkStateAbsent = audit.networkStateAbsent,
                models = modelHashes(),
                jobs = chosen.map(Diagnostics::job),
            ),
        )
    }

    private fun hasPendingWork(): Boolean = runner.jobs.value.values.any {
        JobQueue.isActive(it) || it.awaitsWorker || LlmRules.retryEligible(it)
    }

    private fun detailFlow(id: String): Flow<JobDetailUi?> {
        val job = runner.jobs.map { it[id] }.distinctUntilChanged()
        val live = runner.live.map { value -> value?.takeIf { it.jobId == id } }.distinctUntilChanged()
        return combine(job, live, models.models) { record, progress, modelsUi -> Triple(record, progress, modelsUi) }
            .mapLatest { (record, progress, modelsUi) ->
                record?.let {
                    detail(it, progress, readUtterances(it), readProtocol(it), modelsUi)
                }
            }
            .flowOn(io)
    }

    /** Первое значение для экрана. Вызывается на главном потоке, поэтому обходится без диска. */
    private fun firstDetail(id: String): JobDetailUi? {
        val job = runner.jobs.value[id] ?: return null
        val all = runner.jobs.value.values
        return JobDetailUi(
            summary = JobUiMapper.summary(job, null, all),
            processing = if (job.transcriptSaved) null else JobUiMapper.processing(job, null, null),
            result = null,
            diagnostics = null,
        )
    }

    private fun detail(
        job: JobRecord,
        live: LiveProgress?,
        utterances: List<Utterance>,
        document: ProtocolDocument?,
        modelsUi: ModelsUi?,
    ): JobDetailUi {
        val all = runner.jobs.value.values
        val showsProcessing = !job.transcriptSaved
        return JobDetailUi(
            summary = JobUiMapper.summary(job, live, all),
            processing = if (showsProcessing) JobUiMapper.processing(job, live, speed.current(), powerSave()) else null,
            result = if (job.transcriptSaved) {
                JobUiMapper.result(job, live, utterances, document, modelsUi, store.files(job.id).pcm.path)
            } else {
                null
            },
            diagnostics = if (job.phase == JobPhase.FAILED || job.phase == JobPhase.INTERRUPTED) {
                Diagnostics.encode(emptyPackage(job))
            } else {
                null
            },
        )
    }

    private fun powerSave(): Boolean = app.getSystemService(PowerManager::class.java).isPowerSaveMode

    private fun emptyPackage(job: JobRecord): DiagnosticPackage {
        val run = runContext()
        val audit = network.value
        return DiagnosticPackage(
            appVersion = run.appVersion,
            commit = run.commit,
            device = run.device,
            apiLevel = run.apiLevel,
            totalMemBytes = run.totalMemBytes,
            internetAbsent = audit.internetAbsent,
            networkStateAbsent = audit.networkStateAbsent,
            models = emptyList(),
            jobs = listOf(Diagnostics.job(job)),
        )
    }

    /** Реплики неизменны после записи, поэтому держим в памяти только последнюю открытую задачу. */
    private fun readUtterances(job: JobRecord): List<Utterance> {
        if (!job.transcriptSaved) return emptyList()
        utteranceCache?.takeIf { it.first == job.id }?.let { return it.second }
        val utterances = store.readUtterances(job.id).map { it.toUtterance() }
        utteranceCache = job.id to utterances
        return utterances
    }

    /** Протокол дописывается по окнам, поэтому читается заново только при смене времени файла. */
    private fun readProtocol(job: JobRecord): ProtocolDocument? {
        val file = store.files(job.id).protocol
        val modified = file.lastModified()
        protocolCache?.takeIf { it.first == job.id && it.second == modified }?.let { return it.third }
        val document = store.readProtocol(job.id)
        protocolCache = Triple(job.id, modified, document)
        return document
    }

    private fun verificationUi(
        modelsUi: ModelsUi,
        jobs: Collection<JobRecord>,
        audit: NetworkAudit,
    ): VerificationUi {
        val last = jobs.filter { it.transcriptSaved }.maxByOrNull { it.seq }
        val rtf = last?.rtf()
        return VerificationUi(
            internetPermissionAbsent = audit.internetAbsent,
            networkStatePermissionAbsent = audit.networkStateAbsent,
            appVersion = BuildConfig.VERSION_NAME,
            models = modelsUi.items,
            lastJobTitle = last?.title,
            audioDurationMs = last?.durationMs,
            processingMs = last?.let { it.coreMs + it.llm.tookMs },
            rtfCore = rtf?.core,
            rtfFull = rtf?.full,
            peakRssMb = last?.peakRssKb?.div(KB_IN_MB),
            stageMetrics = last?.let(JobUiMapper::stageMetrics).orEmpty(),
            jsonValid = last?.output?.validated,
            jsonSha256 = last?.output?.sha256,
            jsonPath = last?.output?.path,
            llmTokens = last?.llm?.let { llm ->
                listOfNotNull(llm.promptTokens, llm.completionTokens).takeIf { it.isNotEmpty() }?.sum()
            },
            lastJobId = last?.id,
            threads = runContext().threads.takeIf { last != null },
            llmStatus = last?.llm?.status,
        )
    }

    /** Проверка по настоящему пакету, а не по манифесту из репозитория: это видит и жюри. */
    private fun auditNetwork(): NetworkAudit {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().toSet()
        return NetworkAudit(
            internetAbsent = Manifest.permission.INTERNET !in requested,
            networkStateAbsent = Manifest.permission.ACCESS_NETWORK_STATE !in requested,
        )
    }

    private fun emptyHome(): HomeUi = HomeUi(
        models = models.models.value,
        audioPermissionGranted = false,
        audio = emptyList(),
        jobs = emptyList(),
        statusCode = JobUiMapper.homeStatus(emptyList(), null, emptySet()),
    )

    private data class NetworkAudit(val internetAbsent: Boolean, val networkStateAbsent: Boolean)

    private companion object {
        const val FLOW_STOP_MS = 5_000L
        const val KB_IN_MB = 1024L
    }
}
