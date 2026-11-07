package ru.stenokot.app.pipeline

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import ru.stenokot.app.BuildConfig
import ru.stenokot.app.ControllerProvider
import ru.stenokot.app.audio.AndroidMic
import ru.stenokot.app.data.AndroidSources
import ru.stenokot.app.data.AudioDiscovery
import ru.stenokot.app.data.JobStore
import ru.stenokot.app.data.ResultPublisher
import ru.stenokot.app.data.SpeedBook
import ru.stenokot.app.service.AndroidDevice
import ru.stenokot.app.service.PipelineService
import ru.stenokot.app.service.RecordingService
import java.io.File

/**
 * Сборка объектов приложения. Конструкторы не трогают диск: всё, что читает файлы, вызывается через
 * [start] в фоне, поэтому запуск приложения не задерживается.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppGraph(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val io = Dispatchers.IO
    private val worker = Dispatchers.IO.limitedParallelism(1)
    private val jobsRoot by lazy { File(app.filesDir, JOBS_DIR) }
    private val recordingsRoot by lazy { File(app.filesDir, RECORDINGS_DIR) }
    private val device = AndroidDevice(app)
    private val ledger = ModelLedger(BuildConfig.MODELS_LOCK)
    private val modelSizes = { path: String -> models.sizeOf(path) }
    private val runContext by lazy {
        RunContext(
            appVersion = BuildConfig.VERSION_NAME,
            commit = BuildConfig.GIT_COMMIT,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            apiLevel = Build.VERSION.SDK_INT,
            totalMemBytes = device.totalMemoryBytes(),
            threads = maxOf(2, minOf(4, Runtime.getRuntime().availableProcessors() - 1)),
        )
    }
    private val store = JobStore { jobsRoot }
    private val speed = SpeedBook { File(app.filesDir, SPEED_FILE) }
    private val publisher = ResultPublisher(app)
    private val hashes = { ledger.modelHashes(modelSizes) }

    /** Готовность моделей. */
    val models = ModelPreparer(app, ledger, scope, io)

    /** Очередь и стадии. */
    val runner = PipelineRunner(
        store = store,
        engines = EngineFactory(app, ledger.version),
        device = device,
        sources = AndroidSources(app, { jobsRoot }, { recordingsRoot }),
        outputs = publisher,
        modelGate = models,
        metrics = MetricsRecorder({ runContext }, hashes),
        speed = speed,
        scope = scope,
        worker = worker,
        io = io,
        onStatus = { Log.i(STATUS_TAG, it) },
    )

    /** Запись с микрофона. Готовый файл идёт в [controller] как обычный импорт. */
    val recorder: MicRecorder = MicRecorder(
        root = { recordingsRoot },
        openMic = { rate -> ControllerProvider.override?.micSource() ?: AndroidMic.open(rate) },
        startService = { RecordingService.start(app) },
        import = { file -> controller.import(listOf(Uri.fromFile(file))) },
        findJob = { file ->
            val uri = Uri.fromFile(file).toString()
            runner.jobs.value.values.firstOrNull { it.sourceUri == uri }
        },
        onThreadStart = { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) },
        log = { Log.i(RECORDER_TAG, it) },
    )

    /** Контроллер для интерфейса. */
    val controller: RealStenoController = RealStenoController(
        context = app,
        runner = runner,
        recorder = recorder,
        models = models,
        store = store,
        publisher = publisher,
        discovery = AudioDiscovery(app),
        speed = speed,
        runContext = { runContext },
        modelHashes = hashes,
        startService = { PipelineService.start(app) },
        scope = scope,
        io = io,
    )

    /** Запускает фоновую работу. Сам вызов мгновенный. */
    fun start() {
        runner.launch()
        models.start()
        controller.start()
    }

    private companion object {
        const val JOBS_DIR = "jobs"
        const val RECORDINGS_DIR = "recordings"
        const val SPEED_FILE = "speed.json"
        const val STATUS_TAG = "PIPELINE"
        const val RECORDER_TAG = "Recorder"
    }
}
