package ru.stenokot.app.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.StatFs
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.stenokot.core.audio.SampleSink
import ru.stenokot.core.audio.StreamingResampler
import ru.stenokot.core.engine.AudioDecoder
import ru.stenokot.core.engine.Progress
import ru.stenokot.core.model.AudioInfo
import ru.stenokot.core.model.ErrorCode

/**
 * Потоковое декодирование в pcm16k.wav с постоянной памятью: MediaExtractor, MediaCodec, сведение в моно,
 * ресемплинг. Длительность берётся из числа записанных отсчётов, заголовок файла в ней не участвует.
 *
 * Нулём шкалы считается первый отсчёт кодека: задержку кодера он уже срезал, а метка первого буфера при этом
 * остаётся сдвинутой и короткий буфер не согласован со следующим. Поэтому начало отсчёта меток берётся
 * по второму буферу, дальше метки только сверяются со шкалой.
 */
class MediaDecoder : AudioDecoder {

    override suspend fun decode(source: File, outWav: File, progress: Progress): AudioInfo =
        withContext(Dispatchers.IO) {
            val probe = Preflight.probe(source)
            val partial = File(outWav.path + ".part")
            try {
                val outcome = AudioTrackSource.open(source, outWav.parentFile).use { track ->
                    PcmWavWriter(partial, TARGET_RATE).use { writer ->
                        val sink = PcmSink(writer)
                        pumpWithRestarts(track, sink, source.length(), progress)
                        sink.finish()
                        writer.finish()
                        sink
                    }
                }
                requireComplete(probe, outcome.durationMs)
                if (!partial.renameTo(outWav)) throw IOException("Не удалось сохранить ${outWav.name}")
                progress.report(1f, null)
                AudioInfo(
                    durationMs = outcome.durationMs,
                    sourceSampleRate = outcome.sourceRate.takeIf { it > 0 } ?: probe.sampleRate,
                    sourceChannels = outcome.sourceChannels.takeIf { it > 0 } ?: probe.channels,
                    mime = probe.mime,
                    sourceSha256 = sha256(source),
                )
            } catch (error: IOException) {
                throw writeFailure(outWav, error)
            } finally {
                partial.delete()
            }
        }

    /** Запись оборвалась: при заполненном диске это нехватка места, иначе сбой чтения файла задачи. */
    private fun writeFailure(outWav: File, error: IOException): EngineException {
        val free = outWav.parentFile?.let { StatFs(it.path).availableBytes } ?: 0L
        val code = if (free < MIN_FREE_BYTES) ErrorCode.STORAGE_LOW else ErrorCode.IMPORT_READ_FAILED
        return EngineException(code, "Запись ${outWav.name} прервана", error)
    }

    /** Отказ кодека на повреждённом фрагменте не обрывает запись: новый кодек, разрыв закроется тишиной. */
    private suspend fun pumpWithRestarts(track: AudioTrackSource, sink: PcmSink, size: Long, progress: Progress) {
        var restarts = 0
        val pacer = ProgressPacer(size, progress)
        while (true) {
            val codec = track.createCodec()
            try {
                pump(track, codec, sink, pacer)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalStateException) {
                restarts = nextRestart(track, sink, restarts, error)
            } catch (error: IllegalArgumentException) {
                restarts = nextRestart(track, sink, restarts, error)
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
        }
    }

    /** Пропускает пакет, на котором споткнулся кодек. Отказ в самом начале или слишком частый означает порчу. */
    private fun nextRestart(track: AudioTrackSource, sink: PcmSink, restarts: Int, error: RuntimeException): Int {
        if (sink.framesAccepted == 0L || restarts >= MAX_RESTARTS) {
            throw EngineException(ErrorCode.DECODE_CORRUPT, "Декодер остановился: ${track.mime}", error)
        }
        track.extractor.advance()
        return restarts + 1
    }

    private suspend fun pump(track: AudioTrackSource, codec: MediaCodec, sink: PcmSink, pacer: ProgressPacer) {
        val info = MediaCodec.BufferInfo()
        val extractor = track.extractor
        var inputDone = false
        var lastProgress = System.currentTimeMillis()
        while (true) {
            coroutineContext.ensureActive()
            var moved = false
            if (!inputDone) {
                val slot = codec.dequeueInputBuffer(DEQUEUE_US)
                if (slot >= 0) {
                    moved = true
                    val buffer = codec.getInputBuffer(slot)
                        ?: throw IllegalStateException("Нет входного буфера")
                    val read = extractor.readSampleData(buffer, 0)
                    if (read < 0) {
                        codec.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val time = extractor.sampleTime
                        codec.queueInputBuffer(slot, 0, read, time, 0)
                        pacer.fed(read)
                        extractor.advance()
                    }
                }
            }
            val output = codec.dequeueOutputBuffer(info, DEQUEUE_US)
            when {
                output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    moved = true
                    sink.onFormat(codec.outputFormat)
                }
                output >= 0 -> {
                    moved = true
                    val finished = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0) {
                        val data = codec.getOutputBuffer(output)
                            ?: throw IllegalStateException("Нет выходного буфера")
                        data.position(info.offset)
                        data.limit(info.offset + info.size)
                        sink.accept(data.slice().order(ByteOrder.nativeOrder()), info.presentationTimeUs)
                    }
                    codec.releaseOutputBuffer(output, false)
                    if (finished) return
                }
            }
            val now = System.currentTimeMillis()
            if (moved) {
                lastProgress = now
            } else if (now - lastProgress > STALL_LIMIT_MS) {
                val code = if (sink.framesAccepted == 0L) ErrorCode.DECODE_UNSUPPORTED_FORMAT else ErrorCode.DECODE_CORRUPT
                throw EngineException(code, "Декодер завис: ${track.mime}")
            }
        }
    }

    /** Надёжный заголовок обещал заметно больше данных, чем удалось декодировать: файл обрезан. */
    private fun requireComplete(probe: AudioProbe, decodedMs: Long) {
        if (decodedMs <= 0) throw EngineException(ErrorCode.DECODE_CORRUPT, "Не декодировано ни одного отсчёта")
        val header = probe.headerDurationMs ?: return
        if (probe.headerReliable && decodedMs < header * MIN_COMPLETE_RATIO && header - decodedMs > MIN_LOSS_MS) {
            throw EngineException(ErrorCode.DECODE_CORRUPT, "Декодировано $decodedMs мс из $header мс")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val block = ByteArray(HASH_BLOCK)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(block)
                if (read < 0) break
                digest.update(block, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Доля выполнения по прочитанным байтам: заголовок VBR врёт, размер файла точен. */
    private class ProgressPacer(private val size: Long, private val progress: Progress) {
        private var bytes = 0L
        private var last = 0L

        fun fed(count: Int) {
            bytes += count
            val now = System.currentTimeMillis()
            if (now - last >= REPORT_EVERY_MS) {
                last = now
                progress.report(min(bytes.toFloat() / size, MAX_REPORTED), null)
            }
        }
    }

    /** Приёмник буферов кодека: сводит в моно, держит шкалу времени, пишет 16 кГц. */
    private class PcmSink(private val writer: PcmWavWriter) {
        private var rate = 0
        private var channels = 0
        private var encoding = AudioFormat.ENCODING_PCM_16BIT
        private var resampler: StreamingResampler? = null
        private val output = SampleSink { samples, count -> writer.write(samples, 0, count) }
        private var mono = FloatArray(4096)
        private var baseUs = 0.0
        private var framesAtRate = 0L
        private var lastPtsUs = 0L
        private var buffers = 0L
        private var originUs = 0.0

        /** Частота, на которой реально работал кодек. */
        var sourceRate = 0
            private set

        /** Число каналов на выходе кодека. */
        var sourceChannels = 0
            private set

        /** Сколько кадров принято от кодека всего. */
        var framesAccepted = 0L
            private set

        /** Длительность записанного файла по числу отсчётов. */
        val durationMs: Long
            get() = (writer.samples * 1000.0 / TARGET_RATE).roundToLong()

        /** Реальные частота и каналы приходят только здесь, дорожке верить нельзя. */
        fun onFormat(format: MediaFormat) {
            val newRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val newChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            if (newRate != rate) {
                resampler?.flush(output)
                resampler = null
                if (rate > 0) baseUs += framesAtRate * 1e6 / rate
                framesAtRate = 0
                rate = newRate
                sourceRate = newRate
            }
            channels = newChannels
            sourceChannels = newChannels
        }

        fun accept(data: ByteBuffer, ptsUs: Long) {
            if (rate == 0 || channels == 0) throw IllegalStateException("Буфер раньше формата")
            val bytesPerFrame = bytesPerSample(encoding) * channels
            val frames = data.remaining() / bytesPerFrame
            if (frames == 0) return
            var skip = 0
            when {
                buffers == 0L -> if (ptsUs < 0) skip = min(frames.toLong(), framesFor(-ptsUs)).toInt()
                buffers == 1L -> originUs = ptsUs - positionUs()
                ptsUs > lastPtsUs -> {
                    val drift = ptsUs - originUs - positionUs()
                    if (drift > TOLERANCE_US) {
                        closeGap(drift)
                    } else if (drift < -TOLERANCE_US) {
                        skip = min(frames.toLong(), framesFor(-drift.toLong())).toInt()
                    }
                }
            }
            buffers++
            lastPtsUs = ptsUs
            framesAccepted += frames
            if (mono.size < frames) mono = FloatArray(frames)
            toMono(data, frames)
            push(mono, skip, frames - skip)
        }

        /** Сбрасывает хвост ресемплера. */
        fun finish() {
            resampler?.flush(output)
            resampler = null
        }

        private fun positionUs(): Double = baseUs + framesAtRate * 1e6 / rate

        private fun framesFor(us: Long): Long = us * rate / 1_000_000

        /** Закрывает тишиной разрыв [gapUs]. Огромный разрыв это сбой меток, шкала сдвигается без записи. */
        private fun closeGap(gapUs: Double) {
            if (gapUs > MAX_GAP_US) {
                originUs += gapUs
                return
            }
            var left = framesFor(gapUs.toLong())
            if (mono.size < SILENCE_BLOCK) mono = FloatArray(SILENCE_BLOCK)
            while (left > 0) {
                val part = min(left, SILENCE_BLOCK.toLong()).toInt()
                mono.fill(0f, 0, part)
                push(mono, 0, part)
                left -= part
            }
        }

        private fun push(samples: FloatArray, offset: Int, count: Int) {
            if (count <= 0) return
            val active = resampler ?: StreamingResampler(rate, 1, TARGET_RATE).also { resampler = it }
            framesAtRate += count
            active.process(samples, offset, count, output)
        }

        private fun toMono(data: ByteBuffer, frames: Int) {
            val scale = 1f / channels
            for (frame in 0 until frames) {
                var sum = 0f
                for (channel in 0 until channels) sum += readSample(data, frame * channels + channel)
                mono[frame] = sum * scale
            }
        }

        private fun readSample(data: ByteBuffer, index: Int): Float = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> data.getShort(index * 2) / 32768f
            AudioFormat.ENCODING_PCM_FLOAT -> data.getFloat(index * 4)
            AudioFormat.ENCODING_PCM_8BIT -> ((data.get(index).toInt() and 0xFF) - 128) / 128f
            AudioFormat.ENCODING_PCM_32BIT -> data.getInt(index * 4) / 2147483648f
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                val at = index * 3
                val packed = (data.get(at).toInt() and 0xFF) or
                    ((data.get(at + 1).toInt() and 0xFF) shl 8) or
                    (data.get(at + 2).toInt() shl 16)
                packed / 8388608f
            }
            else -> throw EngineException(ErrorCode.DECODE_UNSUPPORTED_FORMAT, "Кодировка PCM $encoding")
        }

        private fun bytesPerSample(encoding: Int): Int = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
            else -> 2
        }
    }

    private companion object {
        const val TARGET_RATE = 16_000
        const val DEQUEUE_US = 5_000L
        const val STALL_LIMIT_MS = 20_000L
        const val MAX_RESTARTS = 16
        const val TOLERANCE_US = 20_000.0
        const val MAX_GAP_US = 60_000_000.0
        const val SILENCE_BLOCK = 4096
        const val MIN_COMPLETE_RATIO = 0.9
        const val MIN_LOSS_MS = 2_000L
        const val HASH_BLOCK = 1 shl 16
        const val MIN_FREE_BYTES = 1L shl 20
        const val REPORT_EVERY_MS = 500L
        const val MAX_REPORTED = 0.99f
    }
}
