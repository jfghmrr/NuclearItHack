package ru.stenokot.app.speech

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlin.math.ceil
import kotlin.math.min
import ru.stenokot.core.engine.PcmReader
import ru.stenokot.core.engine.Progress
import ru.stenokot.core.engine.Vad
import ru.stenokot.core.model.SpeechSegment
import ru.stenokot.core.vad.ChunkPlanner
import ru.stenokot.core.vad.ChunkPlannerConfig
import com.k2fsa.sherpa.onnx.Vad as NativeVad

/**
 * Silero VAD. Работает через дорожку вероятностей окнами по 512 отсчётов: внутренний буфер нативного
 * детектора удваивается на длинных спанах, а дорожка на час это около 112 тысяч чисел.
 */
class SileroVadEngine(models: ModelSource) : Vad {
    private val vad: NativeVad

    init {
        models.require(ModelFiles.VAD)
        vad = loadModel(ModelFiles.VAD) {
            NativeVad(
                assetManager = models.assets,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = models.path(ModelFiles.VAD),
                        threshold = VadTuning.THRESHOLD,
                        minSilenceDuration = VadTuning.MIN_SILENCE_S,
                        minSpeechDuration = VadTuning.MIN_SPEECH_S,
                        windowSize = VadTuning.WINDOW,
                        maxSpeechDuration = VadTuning.MAX_SPEECH_S,
                    ),
                    sampleRate = VadTuning.SAMPLE_RATE,
                    numThreads = 1,
                ),
            )
        }
    }

    override fun segments(pcm: PcmReader, progress: Progress): List<SpeechSegment> {
        val probabilities = probabilityTrack(pcm, progress)
        return ChunkPlanner(ChunkPlannerConfig()).plan(probabilities, pcm.totalSamples)
    }

    /** Вероятность речи на каждое окно записи. Состояние сети сбрасывается один раз, в начале. */
    fun probabilityTrack(pcm: PcmReader, progress: Progress): FloatArray {
        val window = FloatArray(VadTuning.WINDOW)
        val count = ceil(pcm.totalSamples.toDouble() / VadTuning.WINDOW).toInt()
        val track = FloatArray(count)
        vad.reset()
        var filled = 0
        var lastReport = 0L
        while (filled < count) {
            val position = filled.toLong() * VadTuning.WINDOW
            val block = pcm.read(position, VadTuning.WINDOW * READ_WINDOWS)
            if (block.isEmpty()) break
            var offset = 0
            while (offset < block.size && filled < count) {
                val available = min(VadTuning.WINDOW, block.size - offset)
                System.arraycopy(block, offset, window, 0, available)
                if (available < VadTuning.WINDOW) window.fill(0f, available, VadTuning.WINDOW)
                track[filled] = vad.compute(window)
                filled++
                offset += available
            }
            val now = System.currentTimeMillis()
            if (now - lastReport >= REPORT_EVERY_MS) {
                lastReport = now
                progress.report(filled.toFloat() / count, null)
            }
        }
        progress.report(1f, null)
        return track
    }

    /**
     * Вероятность речи окнами по 512 отсчётов для короткого отрезка [samples]. Состояние сети
     * сбрасывается перед отрезком, неполное последнее окно дополняется нулями, как в дорожке записи.
     */
    fun probabilities(samples: FloatArray): FloatArray {
        val window = FloatArray(VadTuning.WINDOW)
        val count = ceil(samples.size.toDouble() / VadTuning.WINDOW).toInt()
        val out = FloatArray(count)
        vad.reset()
        for (index in 0 until count) {
            val from = index * VadTuning.WINDOW
            val available = min(VadTuning.WINDOW, samples.size - from)
            System.arraycopy(samples, from, window, 0, available)
            if (available < VadTuning.WINDOW) window.fill(0f, available, VadTuning.WINDOW)
            out[index] = vad.compute(window)
        }
        return out
    }

    override fun close() {
        vad.release()
    }

    private companion object {
        const val READ_WINDOWS = 256
        const val REPORT_EVERY_MS = 500L
    }
}

/** Параметры детектора. Значения пока стартовые, калибруются на записях. */
internal object VadTuning {
    const val SAMPLE_RATE = 16000
    const val WINDOW = 512
    const val THRESHOLD = 0.35f
    const val MIN_SILENCE_S = 0.30f
    const val MIN_SPEECH_S = 0.10f
    const val MAX_SPEECH_S = 20f
}
