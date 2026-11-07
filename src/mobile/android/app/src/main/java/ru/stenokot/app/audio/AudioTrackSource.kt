package ru.stenokot.app.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.Closeable
import java.io.File
import java.io.IOException
import ru.stenokot.core.audio.AudioInput
import ru.stenokot.core.audio.FileAudioInput
import ru.stenokot.core.audio.SeekableInput
import ru.stenokot.core.model.ErrorCode

/** Открытый файл с выбранной дорожкой. Общая часть быстрой проверки и полного декодирования. */
internal class AudioTrackSource private constructor(
    val extractor: MediaExtractor,
    val format: MediaFormat,
    val mime: String,
    private val input: SeekableInput,
    private val unwrapped: File?,
    private val patched: PatchedWavSource?,
) : Closeable {
    /** Частота дискретизации из контейнера. Реальная приходит от кодека. */
    val sampleRate: Int = format.intOrZero(MediaFormat.KEY_SAMPLE_RATE)

    /** Число каналов из контейнера. */
    val channels: Int = format.intOrZero(MediaFormat.KEY_CHANNEL_COUNT)

    /** Длительность из контейнера, микросекунды. Значение null: контейнер её не знает. */
    val headerDurationUs: Long? =
        if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION).takeIf { it > 0 }
        } else {
            null
        }

    /** Создаёт и настраивает декодер дорожки. */
    fun createCodec(): MediaCodec {
        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (error: IOException) {
            throw unsupported("Нет декодера для $mime", error)
        } catch (error: IllegalArgumentException) {
            throw unsupported("Нет декодера для $mime", error)
        }
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            return codec
        } catch (error: IllegalStateException) {
            codec.release()
            throw unsupported("Декодер отверг формат $mime", error)
        } catch (error: IllegalArgumentException) {
            codec.release()
            throw unsupported("Декодер отверг формат $mime", error)
        }
    }

    /** Имя разборщика контейнера на этом образе. */
    val container: String?
        get() = extractor.metrics?.getString(MediaExtractor.MetricsConstants.FORMAT)

    /** Дорожка открыта из распакованного Ogg FLAC. */
    val isUnwrapped: Boolean
        get() = unwrapped != null

    override fun close() {
        extractor.release()
        patched?.close()
        input.close()
        unwrapped?.delete()
    }

    companion object {
        private const val HEAD_BYTES = 1L shl 20
        private val KNOWN_EXTENSIONS = setOf(
            "mp3", "wav", "wave", "m4a", "mp4", "aac", "ogg", "oga", "opus", "flac", "amr", "3gp", "mka", "mkv", "webm",
        )

        /**
         * Открывает [source]: берётся первая звуковая дорожка, обложка и видео пропускаются. Временный файл
         * для Ogg FLAC создаётся в [tempDir] и удаляется в [close]. При [headOnly] распаковывается только начало.
         * Пути у чужого файла может не быть, поэтому извлекатель всегда получает дескриптор.
         */
        fun open(source: AudioInput, tempDir: File? = null, headOnly: Boolean = false): AudioTrackSource {
            val input = source.open()
            var readable = input
            var temp: File? = null
            try {
                if (OggFlac.matches(input)) {
                    temp = unwrap(source.name, input, tempDir, headOnly)
                    readable = FileAudioInput(temp).open()
                    input.close()
                }
                return openPlain(source.name, readable, temp)
            } catch (error: Exception) {
                readable.close()
                input.close()
                temp?.delete()
                throw error
            }
        }

        private fun unwrap(name: String, input: SeekableInput, tempDir: File?, headOnly: Boolean): File {
            val target = File.createTempFile("ogg", ".flac", tempDir)
            try {
                OggFlac.unwrap(input, target, if (headOnly) HEAD_BYTES else Long.MAX_VALUE)
                return target
            } catch (error: IOException) {
                target.delete()
                throw EngineException(ErrorCode.DECODE_CORRUPT, "Не разобран Ogg FLAC: $name", error)
            } catch (error: EngineException) {
                target.delete()
                throw error
            }
        }

        private fun openPlain(name: String, readable: SeekableInput, unwrapped: File?): AudioTrackSource {
            val extractor = MediaExtractor()
            val patched = WavHeaderPatch.detect(readable)?.let { PatchedWavSource(readable, it) }
            try {
                if (patched != null) {
                    extractor.setDataSource(patched)
                } else {
                    extractor.setDataSource(readable.descriptor, 0, readable.length())
                }
            } catch (error: IOException) {
                release(extractor, patched)
                throw unreadable(name, readable, error)
            } catch (error: IllegalArgumentException) {
                release(extractor, patched)
                throw unreadable(name, readable, error)
            } catch (error: IllegalStateException) {
                release(extractor, patched)
                throw unreadable(name, readable, error)
            }
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    extractor.selectTrack(index)
                    return AudioTrackSource(extractor, format, mime, readable, unwrapped, patched)
                }
            }
            val tracks = extractor.trackCount
            release(extractor, patched)
            if (tracks == 0) throw unreadable(name, readable, IOException("Контейнер без дорожек"))
            throw unsupported("В файле нет аудиодорожки", null)
        }

        private fun release(extractor: MediaExtractor, patched: PatchedWavSource?) {
            extractor.release()
            patched?.close()
        }

        /** Файл, который не открылся: заявленный как аудио считается повреждённым, остальной чужим. */
        private fun unreadable(name: String, input: SeekableInput, cause: Exception): EngineException {
            val code = if (looksLikeAudio(name, input)) ErrorCode.DECODE_CORRUPT else ErrorCode.DECODE_UNSUPPORTED_FORMAT
            return EngineException(code, "Контейнер не открылся: $name", cause)
        }

        private fun looksLikeAudio(name: String, input: SeekableInput): Boolean {
            if (name.substringAfterLast('.', "").lowercase() in KNOWN_EXTENSIONS) return true
            val head = ByteArray(12)
            input.seek(0)
            if (input.read(head) < head.size) return false
            val start = String(head, 0, 4, Charsets.ISO_8859_1)
            val brand = String(head, 4, 4, Charsets.ISO_8859_1)
            return start == "RIFF" || start == "OggS" || start == "fLaC" || start.startsWith("ID3") || brand == "ftyp"
        }

        private fun unsupported(message: String, cause: Exception?) =
            EngineException(ErrorCode.DECODE_UNSUPPORTED_FORMAT, message, cause)
    }
}

private fun MediaFormat.intOrZero(key: String): Int = if (containsKey(key)) getInteger(key) else 0
