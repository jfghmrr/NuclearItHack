package ru.stenokot.app.debug

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.stenokot.app.domain.ExportKind
import ru.stenokot.app.domain.HomeUi
import ru.stenokot.app.domain.JobDetailUi
import ru.stenokot.app.domain.ProcessingUi
import ru.stenokot.app.domain.RecordingUi
import ru.stenokot.app.domain.ModelCheckUi
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.domain.UserStage
import ru.stenokot.app.domain.VerificationUi
import ru.stenokot.app.ui.format.STATUS_IDLE
import ru.stenokot.app.ui.format.statusCode
import ru.stenokot.app.ui.nav.Route
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.model.Stage

private const val TICK_MS = 1_000L
private const val CANCEL_MS = 1_500L
private const val QUEUE_MS = 1_200L
private const val DEMO_JSON = "{\"transcript\":[]}"
private const val LEVEL_TICK_MS = 120L
private val DemoMicLevels = floatArrayOf(0.34f, 0.72f, 0.5f, 0.9f, 0.28f, 0.62f, 0.82f, 0.42f, 0.56f, 0.76f, 0.3f)
private const val DEMO_RECORDING_MS = 754_000L

/** Шаг имитации: стадия конвейера, её счётчик и этап пользователя. */
private data class LiveStep(val stage: Stage, val user: UserStage, val total: Int?)

private val LiveSteps = listOf(
    LiveStep(Stage.IMPORT, UserStage.PREPARE_AUDIO, 11_981),
    LiveStep(Stage.DECODE, UserStage.PREPARE_AUDIO, 768),
    LiveStep(Stage.VAD, UserStage.PREPARE_AUDIO, 768),
    LiveStep(Stage.LANG_ID, UserStage.PREPARE_AUDIO, 3),
    LiveStep(Stage.ASR, UserStage.RECOGNIZE, 45),
    LiveStep(Stage.DIARIZE, UserStage.DIARIZE, 3),
    LiveStep(Stage.MERGE, UserStage.SAVE_TRANSCRIPT, null),
    LiveStep(Stage.VALIDATE, UserStage.SAVE_TRANSCRIPT, null),
)

/**
 * Контроллер отладочной сборки. Показывает любое состояние брифа через [show] и умеет проиграть
 * обработку на демо-данных, чтобы проверить переходы между экранами.
 */
internal class FakeController(private val context: Context? = null) : StenoController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val homeState = MutableStateFlow(demoScene("S01-D")!!.home)
    private val verificationState = MutableStateFlow(DemoData.verificationEmpty)
    private val jobs = MutableStateFlow<Map<String, JobDetailUi>>(emptyMap())
    private val flows = ConcurrentHashMap<String, StateFlow<JobDetailUi?>>()
    private var running: Job? = null
    private var counter = 0

    override val home: StateFlow<HomeUi> = homeState
    override val verification: StateFlow<VerificationUi> = verificationState
    override val recording = MutableStateFlow<RecordingUi>(RecordingUi.Idle)
    override val micLevel = MutableStateFlow(DemoMicLevels[0])
    override val modelCheck = MutableStateFlow(ModelCheckUi())
    private var micFeed: Job? = null

    /** Демо-сверка: все модели совпадают. Настоящая живёт в конвейере и считает хэши файлов. */
    override fun checkModels() {
        val names = homeState.value.models.items.map { it.name }
        modelCheck.value = ModelCheckUi(verdicts = names.associateWith { true })
    }

    override fun startRecording() {
        recording.value = RecordingUi.Active(DEMO_RECORDING_MS, micBusy = false)
        feedMicLevel()
    }

    /** Шевелит волну только пока идёт запись: постоянное значение рисовало бы один столбик. */
    private fun feedMicLevel() {
        if (micFeed?.isActive == true) return
        micFeed = scope.launch {
            var index = 0
            while (recording.value is RecordingUi.Active) {
                micLevel.value = DemoMicLevels[index % DemoMicLevels.size]
                index += 1
                delay(LEVEL_TICK_MS)
            }
        }
    }

    override fun stopRecording() {
        recording.value = RecordingUi.Idle
    }

    override fun cancelRecording() {
        recording.value = RecordingUi.Idle
    }

    override fun acknowledgeRecording() {
        if (recording.value !is RecordingUi.Active) recording.value = RecordingUi.Idle
    }

    /** Открывает состояние [id]. Возвращает экран, на который надо перейти. */
    fun show(id: String): Route? {
        running?.cancel()
        if (id == "LIVE") {
            apply(demoScene("S01-A")!!)
            return Route.Home
        }
        val scene = demoScene(id) ?: return null
        apply(scene)
        return scene.route
    }

    private fun apply(scene: DemoScene) {
        jobs.value = scene.jobs.associateBy { it.summary.id }
        homeState.value = scene.home
        verificationState.value = scene.verification
        modelCheck.value = scene.modelCheck
        recording.value = scene.recording
        if (scene.recording is RecordingUi.Active) feedMicLevel()
    }

    override fun job(id: String): StateFlow<JobDetailUi?> = flows.getOrPut(id) {
        jobs.map { it[id] }.stateIn(scope, SharingStarted.Eagerly, jobs.value[id])
    }

    override fun import(uris: List<Uri>): String? {
        if (uris.isEmpty()) return null
        counter += 1
        val id = "live$counter"
        val name = uris.firstOrNull()?.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: DemoData.FILE_DS1
        val busy = running?.isActive == true
        val summary = DemoData.summary(
            id = id,
            title = name,
            createdAtMs = System.currentTimeMillis(),
            durationMs = DemoData.DURATION_DS1,
            speakers = null,
            state = JobState.Queued,
            queuePosition = homeState.value.jobs.count { it.state == JobState.Queued } + 1,
        )
        val work = ProcessingUi(
            preflight = DemoData.preflightDs1.copy(fileName = name),
            stages = railStages(StageSnapshot(null, null, null, null, emptyMap(), null)),
            elapsedMs = 0,
            remainingMinMs = null,
            remainingMaxMs = null,
            lowMemory = false,
            cancelling = false,
        )
        jobs.update { it + (id to JobDetailUi(summary, work, null, null)) }
        publish(id, JobState.Queued)
        if (!busy) start(id)
        return id
    }

    override fun start(id: String) {
        if (running?.isActive == true) return
        running = scope.launch {
            delay(QUEUE_MS)
            live(id)
        }
    }

    private suspend fun live(id: String) {
        val done = mutableMapOf<UserStage, Long>()
        var elapsed = 0L
        var stageStart = 0L
        var current: UserStage? = null
        LiveSteps.forEach { step ->
            if (current != step.user) {
                current?.let { done[it] = elapsed - stageStart }
                current = step.user
                stageStart = elapsed
            }
            val ticks = if (step.total == null) 2 else 4
            repeat(ticks) { tick ->
                val fraction = if (step.total == null) null else (tick + 1f) / ticks
                val snapshot = StageSnapshot(
                    active = step.user,
                    fraction = fraction,
                    done = step.total?.let { (it * (tick + 1)) / ticks },
                    total = step.total,
                    tookMs = done.toMap(),
                    activeMs = elapsed - stageStart,
                )
                update(id, JobState.Running(step.stage, fraction ?: 0f)) {
                    it.copy(stages = railStages(snapshot), elapsedMs = elapsed)
                }
                delay(TICK_MS)
                elapsed += TICK_MS
            }
        }
        publish(id, JobState.TranscriptReady("Download/Skvor/live.json"))
        delay(3 * TICK_MS)
        publish(id, JobState.Done(LlmStatus.APPLIED, emptyList()))
        startNextQueued()
    }

    private fun startNextQueued() {
        val next = homeState.value.jobs
            .filter { it.state == JobState.Queued }
            .minByOrNull { it.queuePosition ?: Int.MAX_VALUE }
        running = null
        next?.let { start(it.id) }
    }

    override fun cancel(id: String) {
        running?.cancel()
        jobs.update { map ->
            val detail = map[id] ?: return@update map
            map + (id to detail.copy(processing = detail.processing?.copy(cancelling = true)))
        }
        running = scope.launch {
            delay(CANCEL_MS)
            publish(id, JobState.Cancelled)
            update(id, JobState.Cancelled) { it.copy(cancelling = false) }
            startNextQueued()
        }
    }

    override fun resume(id: String) = start(id)

    override fun delete(id: String, removeExported: Boolean) {
        jobs.update { it - id }
        homeState.update { state -> state.copy(jobs = state.jobs.filter { it.id != id }) }
    }

    /**
     * Создаёт настоящую запись файла в Download. Нужна для проверки «Поделиться»: файл принадлежит
     * приложению, как и опубликованный конвейером результат.
     */
    fun publishDemoFile(name: String) {
        val resolver = context?.contentResolver ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Skvor/")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri, "wt")?.use { it.write(DEMO_JSON.toByteArray()) }
    }

    override fun export(id: String, kind: ExportKind, target: Uri) {
        val resolver = context?.contentResolver ?: return
        scope.launch(Dispatchers.IO) {
            val failed = runCatching {
                resolver.openOutputStream(target, "wt")?.use { it.write(DEMO_JSON.toByteArray()) }
                    ?: error("нет потока")
            }.isFailure
            jobs.update { map ->
                val detail = map[id] ?: return@update map
                val result = detail.result?.let { it.copy(output = it.output.copy(exportFailed = failed)) }
                map + (id to detail.copy(result = result))
            }
        }
    }

    override fun refreshAudio() {
        homeState.update { it.copy(audioPermissionGranted = true) }
    }

    override fun diagnosticsPackage(id: String?): String = "демо-данные, задача ${id.orEmpty()}"

    private fun update(id: String, state: JobState, work: (ProcessingUi) -> ProcessingUi) {
        jobs.update { map ->
            val detail = map[id] ?: return@update map
            map + (id to detail.copy(processing = detail.processing?.let(work)))
        }
        publish(id, state)
    }

    private fun publish(id: String, state: JobState) {
        jobs.update { map ->
            val detail = map[id] ?: return@update map
            map + (id to detail.copy(summary = detail.summary.copy(state = state)))
        }
        val summary = jobs.value[id]?.summary ?: return
        homeState.update { current ->
            val others = current.jobs.filter { it.id != id }
            current.copy(jobs = listOf(summary) + others, statusCode = statusCode(state))
        }
        if (state is JobState.Done || state == JobState.Cancelled) {
            homeState.update { it.copy(statusCode = STATUS_IDLE) }
        }
    }
}
