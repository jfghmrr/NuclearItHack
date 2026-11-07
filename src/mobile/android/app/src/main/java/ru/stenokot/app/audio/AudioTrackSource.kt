package ru.stenokot.app.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.Closeable
import java.io.File
import java.io.IOException
import ru.stenokot.core.model.ErrorCode

/** Открытый файл с выбранной дорожкой. Общая часть быстрой проверки и полного декодирования. */
internal class AudioTrackSource private constructor(
    val extractor: MediaExtractor,
    val format: MediaFormat,
    val mime: String,
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
        unwrapped?.delete()
    }

    companion object {
        private const val HEAD_BYTES = 1L shl 20
        private val KNOWN_EXTENSIONS = setOf(
            "mp3", "wav", "wave", "m4a", "mp4", "aac", "ogg", "oga", "opus", "flac", "amr", "3gp", "mka", "mkv", "webm",
        )

        /**
         * Открывает [file]: берётся первая звуковая дорожка, обложка и видео пропускаются. Временный файл
         * для Ogg FLAC создаётся в [tempDir] и удаляется в [close]. При [headOnly] распаковывается только начало.
         */
        fun open(file: File, tempDir: File? = null, headOnly: Boolean = false): AudioTrackSource {
            if (!OggFlac.matches(file)) return openPlain(file, file, null)
            val target = File.createTempFile("ogg", ".flac", tempDir)
            try {
                OggFlac.unwrap(file, target, if (headOnly) HEAD_BYTES else Long.MAX_VALUE)
                return openPlain(file, target, target)
            } catch (error: IOException) {
                target.delete()
                throw EngineException(ErrorCode.DECODE_CORRUPT, "Не разобран Ogg FLAC: ${file.name}", error)
            } catch (error: EngineException) {
                target.delete()
                throw error
            }
        }

        private fun openPlain(original: File, readable: File, unwrapped: File?): AudioTrackSource {
            val extractor = MediaExtractor()
            val patched = WavHeaderPatch.detect(readable)?.let { PatchedWavSource(readable, it) }
            try {
                if (patched != null) extractor.setDataSource(patched) else extractor.setDataSource(readable.path)
            } catch (error: IOException) {
                release(extractor, patched)
                throw unreadable(original, error)
            } catch (error: IllegalArgumentException) {
                release(extractor, patched)
                throw unreadable(original, error)
            } catch (error: IllegalStateException) {
                release(extractor, patched)
                throw unreadable(original, error)
            }
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    extractor.selectTrack(index)
                    return AudioTrackSource(extractor, format, mime, unwrapped, patched)
                }
            }
            val tracks = extractor.trackCount
            release(extractor, patched)
            if (tracks == 0) throw unreadable(original, IOException("Контейнер без дорожек"))
            throw unsupported("В файле нет аудиодорожки", null)
        }

        private fun release(extractor: MediaExtractor, patched: PatchedWavSource?) {
            extractor.release()
            patched?.close()
        }

        /** Файл, который не открылся: заявленный как аудио считается повреждённым, остальной чужим. */
        private fun unreadable(file: File, cause: Exception): EngineException {
            val code = if (looksLikeAudio(file)) ErrorCode.DECODE_CORRUPT else ErrorCode.DECODE_UNSUPPORTED_FORMAT
            return EngineException(code, "Контейнер не открылся: ${file.name}", cause)
        }

        private fun looksLikeAudio(file: File): Boolean {
            if (file.extension.lowercase() in KNOWN_EXTENSIONS) return true
            val head = ByteArray(12)
            val got = file.inputStream().use { it.read(head) }
            if (got < 12) return false
            val start = String(head, 0, 4, Charsets.ISO_8859_1)
            val brand = String(head, 4, 4, Charsets.ISO_8859_1)
            return start == "RIFF" || start == "OggS" || start == "fLaC" || start.startsWith("ID3") || brand == "ftyp"
        }

        private fun unsupported(message: String, cause: Exception?) =
            EngineException(ErrorCode.DECODE_UNSUPPORTED_FORMAT, message, cause)
    }
}

private fun MediaFormat.intOrZero(key: String): Int = if (containsKey(key)) getInteger(key) else 0
