package ru.stenokot.app.pipeline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.stenokot.app.audio.MicCapture
import ru.stenokot.app.audio.MicEnd
import ru.stenokot.app.audio.MicProgress
import ru.stenokot.app.audio.MicResult
import ru.stenokot.app.audio.MicRules
import ru.stenokot.app.audio.MicSource
import ru.stenokot.app.audio.MicUnavailable
import ru.stenokot.app.audio.repairMicWav
import ru.stenokot.app.data.JobRecord
import ru.stenokot.app.domain.MicProblem
import ru.stenokot.app.domain.RecordingUi
import java.io.File
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * Жизнь одной записи: запуск через службу, поток записи, передача файла в конвейер. Объект живёт в
 * процессе, а не в активности, поэтому поворот экрана и выход из приложения запись не трогают.
 *
 * Пока идёт запись, файл называется `*.wav.part`. Такой файл на старте процесса означает, что процесс
 * умер посреди записи: он чинится и идёт в обработку. Файл `*.wav` живёт, пока конвейер не сделал копию.
 */
class MicRecorder internal constructor(
    private val root: () -> File,
    private val openMic: (sampleRate: Int?) -> MicSource,
    private val startService: () -> Boolean,
    private val import: (File) -> String?,
    private val findJob: (File) -> JobRecord?,
    private val onThreadStart: () -> Unit = {},
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val current = MutableStateFlow<RecordingUi>(RecordingUi.Idle)
    private val meter = MutableStateFlow(0f)

    @Volatile
    private var capture: MicCapture? = null

    @Volatile
    private var cancelled = false

    @Volatile
    private var activePart: File? = null

    /** Состояние записи. */
    val state: StateFlow<RecordingUi> = current.asStateFlow()

    /** Уровень сигнала от 0 до 1. */
    val level: StateFlow<Float> = meter.asStateFlow()

    /** Запускает службу. Запись начнётся в [begin], когда служба выйдет на передний план. */
    fun requestStart() {
        val idle = current.value
        if (idle is RecordingUi.Starting || idle is RecordingUi.Active) return
        if (!current.compareAndSet(idle, RecordingUi.Starting)) return
        if (!startService()) fail()
    }

    /** Служба на переднем плане, система разрешает микрофон. Повторный вызов ничего не меняет. */
    fun begin() {
        if (current.value != RecordingUi.Starting || capture != null) return
        cancelled = false
        val started = clock()
        val part = File(root().apply { mkdirs() }, MicRules.fileName(started, zone()) + PART_SUFFIX)
        val free = { part.parentFile?.usableSpace ?: 0L }
        val session = MicCapture(openMic, free, ::publish)
        capture = session
        activePart = part
        thread(name = THREAD_NAME) {
            onThreadStart()
            finish(part, record(session, part))
        }
    }

    /** Служба не вышла на передний план. */
    fun fail() {
        current.value = RecordingUi.Failed(MicProblem.UNAVAILABLE)
    }

    /** Заканчивает запись, файл уйдёт в обработку. */
    fun stop() {
        capture?.stop()
    }

    /** Заканчивает запись, файл будет удалён. */
    fun cancel() {
        cancelled = true
        capture?.stop()
    }

    /** Итог показан пользователю. Идущую запись вызов не трогает. */
    fun acknowledge() {
        val shown = current.value
        if (shown is RecordingUi.Saved || shown is RecordingUi.Failed) {
            current.compareAndSet(shown, RecordingUi.Idle)
        }
    }

    /**
     * Подбирает то, что оставил умерший процесс. Вызывается один раз после загрузки задач: оборванная
     * запись чинится, а файл без задачи идёт в обработку. Файл, копия которого уже у задачи, удаляется.
     */
    fun recover() {
        val dir = root()
        dir.listFiles().orEmpty()
            .filter { it.name.endsWith(PART_SUFFIX) && it != activePart }
            .forEach(::repair)
        dir.listFiles().orEmpty()
            .filter { it.name.endsWith(WAV_SUFFIX) }
            .forEach { file ->
                val job = findJob(file)
                when {
                    job == null -> import(file)
                    job.sourceFile != null -> file.delete()
                }
            }
    }

    private fun repair(part: File) {
        val samples = runCatching { repairMicWav(part) }.getOrDefault(0L)
        log("Оборванная запись: $samples отсчётов")
        if (samples > 0) part.renameTo(File(part.parentFile, part.name.removeSuffix(PART_SUFFIX))) else part.delete()
    }

    private fun record(session: MicCapture, part: File): MicResult? = try {
        session.run(part)
    } catch (failure: MicUnavailable) {
        log("Микрофон не открылся: ${failure.message}")
        null
    }

    private fun publish(progress: MicProgress) {
        meter.value = progress.level
        val shown = RecordingUi.Active(wholeSeconds(progress.elapsedMs), progress.busy)
        if (current.value != shown) current.value = shown
    }

    private fun finish(part: File, result: MicResult?) {
        log("Запись окончена: ${result?.end}, ${result?.samples} отсчётов")
        meter.value = 0f
        val outcome = when {
            result == null -> {
                part.delete()
                RecordingUi.Failed(MicProblem.UNAVAILABLE)
            }
            cancelled -> {
                part.delete()
                RecordingUi.Idle
            }
            result.samples == 0L -> {
                part.delete()
                RecordingUi.Failed(emptyReason(result.end))
            }
            else -> handOver(part)
        }
        capture = null
        activePart = null
        current.value = outcome
    }

    private fun emptyReason(end: MicEnd): MicProblem = when (end) {
        MicEnd.NO_SPACE, MicEnd.WRITE_FAILED -> MicProblem.NO_SPACE
        MicEnd.STOPPED, MicEnd.MIC_LOST -> MicProblem.EMPTY
    }

    private fun handOver(part: File): RecordingUi {
        val target = File(part.parentFile, part.name.removeSuffix(PART_SUFFIX))
        if (!part.renameTo(target)) return RecordingUi.Failed(MicProblem.EMPTY)
        val jobId = import(target) ?: return RecordingUi.Failed(MicProblem.EMPTY)
        return RecordingUi.Saved(jobId)
    }

    private fun wholeSeconds(ms: Long): Long = ms / MS_IN_SECOND * MS_IN_SECOND

    private companion object {
        const val THREAD_NAME = "mic-capture"
        const val PART_SUFFIX = ".part"
        const val WAV_SUFFIX = ".wav"
        const val MS_IN_SECOND = 1000L
    }
}
