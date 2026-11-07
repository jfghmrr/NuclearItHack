package ru.stenokot.app.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import java.io.IOException
import java.nio.ByteBuffer
import ru.stenokot.core.audio.AudioInput
import ru.stenokot.core.model.ErrorCode

/** Что известно о файле до загрузки моделей. */
data class AudioProbe(
    /** Тип кодека дорожки, например audio/mpeg. */
    val mime: String,
    /** Декодер, принявший дорожку. */
    val codecName: String,
    /** Разборщик контейнера на этом образе. */
    val container: String?,
    /** Частота из контейнера, Гц. */
    val sampleRate: Int,
    /** Число каналов из контейнера. */
    val channels: Int,
    /** Длительность из заголовка, мс. Итоговая длительность считается только по отсчётам. */
    val headerDurationMs: Long?,
    /** Заголовку можно верить. У MP3 без служебного кадра длительность лишь оценка по битрейту. */
    val headerReliable: Boolean,
    /** Размер файла, байты. */
    val sizeBytes: Long,
)

/**
 * Быстрая проверка до тяжёлых стадий: формат, кодек, дорожка, длительность, читаемость.
 * Неподдерживаемый и повреждённый файл отсекается здесь, модели ещё не загружены.
 */
object Preflight {
    private const val TRIAL_PACKETS = 256
    private const val TRIAL_TIMEOUT_MS = 5_000L
    private const val DEQUEUE_US = 5_000L
    private const val TAIL_POSITION = 0.9
    private const val PROBE_BUFFER = 1 shl 18
    private const val LARGE_WAV_MIME = "audio/wav"
    private const val LARGE_WAV_CODEC = "pcm"

    /** Проверяет [source]. При отказе бросает [EngineException] с кодом ошибки декодирования. */
    fun probe(source: AudioInput): AudioProbe {
        val size = fileSize(source)
        if (size == 0L) throw EngineException(ErrorCode.DECODE_CORRUPT, "Пустой файл: ${source.name}")
        source.open().use(LargeWav::detect)?.let { return probeLargeWav(it, size) }
        AudioTrackSource.open(source, headOnly = true).use { track ->
            if (track.sampleRate <= 0 || track.channels <= 0) {
                throw EngineException(ErrorCode.DECODE_CORRUPT, "Дорожка без параметров звука: ${track.mime}")
            }
            val isMp3 = track.mime == "audio/mpeg"
            val tag = if (isMp3) source.open().use(Mp3Tag::read) else null
            if (tag != null && tag.isTruncated(size)) {
                throw EngineException(ErrorCode.DECODE_CORRUPT, "Файл обрезан: заявлено ${tag.bytes} байт, есть $size")
            }
            val codecName = trialDecode(track)
            val reliable = !track.isUnwrapped && (!isMp3 || tag?.frames != null)
            val headerUs = when {
                track.isUnwrapped -> source.open().use(OggFlac::durationUs)
                else -> tag?.durationMs?.times(1000) ?: track.headerDurationUs
            }
            if (reliable && headerUs != null) requireTail(track, headerUs)
            return AudioProbe(
                mime = track.mime,
                codecName = codecName,
                container = track.container,
                sampleRate = track.sampleRate,
                channels = track.channels,
                headerDurationMs = headerUs?.div(1000),
                headerReliable = reliable,
                sizeBytes = size,
            )
        }
    }

    private fun probeLargeWav(wav: LargeWav, size: Long) = AudioProbe(
        mime = LARGE_WAV_MIME,
        codecName = LARGE_WAV_CODEC,
        container = null,
        sampleRate = wav.sampleRate,
        channels = wav.channels,
        headerDurationMs = wav.durationMs,
        headerReliable = true,
        sizeBytes = size,
    )

    private fun fileSize(source: AudioInput): Long = try {
        source.open().use { it.length() }
    } catch (error: IOException) {
        throw EngineException(ErrorCode.IMPORT_READ_FAILED, "Файл недоступен: ${source.name}", error)
    }

    /** Декодирует начало дорожки: контейнер мог открыться, а данные оказаться мусором. */
    private fun trialDecode(track: AudioTrackSource): String {
        val codec = track.createCodec()
        val info = MediaCodec.BufferInfo()
        val deadline = System.currentTimeMillis() + TRIAL_TIMEOUT_MS
        var packets = 0
        var inputDone = false
        try {
            while (System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val slot = codec.dequeueInputBuffer(DEQUEUE_US)
                    if (slot >= 0) {
                        val target = codec.getInputBuffer(slot) ?: throw IllegalStateException("Нет входного буфера")
                        val read = track.extractor.readSampleData(target, 0)
                        if (read < 0 || packets >= TRIAL_PACKETS) {
                            codec.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(slot, 0, read, track.extractor.sampleTime.coerceAtLeast(0), 0)
                            track.extractor.advance()
                            packets++
                        }
                    }
                }
                val output = codec.dequeueOutputBuffer(info, DEQUEUE_US)
                if (output >= 0) {
                    val produced = info.size > 0
                    val finished = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(output, false)
                    if (produced) return codec.name
                    if (finished) break
                }
            }
        } catch (error: IllegalStateException) {
            throw EngineException(ErrorCode.DECODE_CORRUPT, "Декодер отказал на начале файла: ${track.mime}", error)
        } catch (error: IllegalArgumentException) {
            throw EngineException(ErrorCode.DECODE_CORRUPT, "Не прочитан пакет данных: ${track.mime}", error)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        throw EngineException(ErrorCode.DECODE_CORRUPT, "Декодер не выдал ни одного отсчёта: ${track.mime}")
    }

    /** Обращается к хвосту записи: обрезанный файл открывается нормально, но данных там нет. */
    private fun requireTail(track: AudioTrackSource, durationUs: Long) {
        val probeBuffer = ByteBuffer.allocate(PROBE_BUFFER)
        val extractor = track.extractor
        val readable = try {
            extractor.seekTo((durationUs * TAIL_POSITION).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            extractor.readSampleData(probeBuffer, 0) >= 0
        } catch (_: IllegalArgumentException) {
            true
        }
        if (!readable) {
            throw EngineException(ErrorCode.DECODE_CORRUPT, "Файл обрезан: нет данных у конца записи")
        }
    }
}
