package ru.stenokot.app.speech

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlin.math.min
import kotlin.math.roundToLong
import ru.stenokot.core.asr.GapAudio
import ru.stenokot.core.asr.GapFill
import ru.stenokot.core.asr.GapFillConfig
import ru.stenokot.core.engine.Asr
import ru.stenokot.core.engine.PcmReader
import ru.stenokot.core.model.AsrSegment
import ru.stenokot.core.model.AsrToken
import ru.stenokot.core.model.SpeechSegment

/**
 * Распознавание русской речи: GigaAM v3 e2e RNNT. Конфигурация своя, потому что этой версии нет в
 * getOfflineModelConfig. Без modelType nemo_transducer загрузка падает на метаданных vocab_size.
 * Паузы внутри среза, где слышна речь, распознаются заново: так находится тихая реплика второго
 * человека, которую модель пропускает рядом с громким голосом.
 */
class GigaAsr(models: ModelSource, threads: Int = EngineThreads.asr()) : Asr by TransducerEngine(
    models = models,
    encoder = ModelFiles.ASR_RU_ENCODER,
    decoder = ModelFiles.ASR_RU_DECODER,
    joiner = ModelFiles.ASR_RU_JOINER,
    tokens = ModelFiles.ASR_RU_TOKENS,
    featureDim = 64,
    threads = threads,
    gaps = GapFillConfig(),
)

/**
 * Распознавание английской речи: Parakeet TDT 0.6B v2. Размерность признаков в экспорте не записана:
 * у этой модели 128, у прежней 110M 80. Выбор по размеру оставляет рабочими сборки dev, на устройстве
 * которых лежит прежняя модель.
 */
class ParakeetAsr(models: ModelSource, threads: Int = EngineThreads.asr()) : Asr by TransducerEngine(
    models = models,
    encoder = ModelFiles.ASR_EN_ENCODER,
    decoder = ModelFiles.ASR_EN_DECODER,
    joiner = ModelFiles.ASR_EN_JOINER,
    tokens = ModelFiles.ASR_EN_TOKENS,
    featureDim = if ((models.sizeOf(ModelFiles.ASR_EN_ENCODER) ?: 0L) > SMALL_ENCODER_BYTES) 128 else 80,
    threads = threads,
)

private const val SMALL_ENCODER_BYTES = 200L * 1024 * 1024

/**
 * Общая часть обеих моделей: срез записи, поток, декодирование, абсолютное время токенов. Если задан
 * [gaps], рядом загружается свой определитель речи для пауз внутри среза. Дополненный срез
 * возвращается целиком, поэтому журнал и склейка видят новые слова как обычные.
 */
internal class TransducerEngine(
    models: ModelSource,
    encoder: String,
    decoder: String,
    joiner: String,
    tokens: String,
    featureDim: Int,
    threads: Int,
    private val gaps: GapFillConfig? = null,
) : Asr {
    private val recognizer: OfflineRecognizer
    private val speech: SileroVadEngine?

    init {
        models.require(encoder, decoder, joiner, tokens)
        recognizer = loadModel(encoder) {
            OfflineRecognizer(
                assetManager = models.assets,
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = featureDim),
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = models.path(encoder),
                            decoder = models.path(decoder),
                            joiner = models.path(joiner),
                        ),
                        tokens = models.path(tokens),
                        modelType = "nemo_transducer",
                        numThreads = threads,
                        provider = "cpu",
                    ),
                    decodingMethod = "greedy_search",
                ),
            )
        }
        speech = try {
            gaps?.let { SileroVadEngine(models) }
        } catch (failure: Throwable) {
            recognizer.release()
            throw failure
        }
    }

    override fun transcribe(pcm: PcmReader, chunk: SpeechSegment): AsrSegment {
        val samples = read(pcm, chunk) ?: return AsrSegment(chunk, "", emptyList())
        val segment = decode(chunk, samples)
        val config = gaps ?: return segment
        val vad = speech ?: return segment
        return GapFill.fill(
            segment = segment,
            config = config,
            floorDb = GapFill.floorDb(samples),
            listen = { startMs, endMs ->
                val part = pcm.read(startMs * SAMPLES_PER_MS, ((endMs - startMs) * SAMPLES_PER_MS).toInt())
                GapAudio(vad.probabilities(part), GapFill.windowEnergiesDb(part))
            },
            decode = { range -> read(pcm, range)?.let { decode(range, it) } ?: AsrSegment(range, "", emptyList()) },
        )
    }

    private fun read(pcm: PcmReader, chunk: SpeechSegment): FloatArray? {
        val length = chunk.endSample - chunk.startSample
        if (length <= 0) return null
        return pcm.read(chunk.startSample, length.toInt()).takeIf { it.isNotEmpty() }
    }

    private fun decode(chunk: SpeechSegment, samples: FloatArray): AsrSegment {
        val input = if (samples.size < MIN_SAMPLES) samples.copyOf(MIN_SAMPLES) else samples
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(input, SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val chunkStartSeconds = chunk.startSample.toDouble() / SAMPLE_RATE
            val count = min(result.tokens.size, result.timestamps.size)
            val tokens = ArrayList<AsrToken>(count)
            for (index in 0 until count) {
                val absoluteSeconds = chunkStartSeconds + result.timestamps[index]
                tokens += AsrToken(result.tokens[index], (absoluteSeconds * 1000).roundToLong())
            }
            return AsrSegment(chunk, result.text, tokens)
        } finally {
            stream.release()
        }
    }

    override fun close() {
        speech?.close()
        recognizer.release()
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val SAMPLES_PER_MS = 16L
        const val MIN_SAMPLES = SAMPLE_RATE / 10
    }
}
