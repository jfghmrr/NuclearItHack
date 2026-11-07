package ru.stenokot.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.max

/**
 * Микрофон через AudioRecord. Сначала нужный распознаванию вход на 16 кГц, затем запасные варианты:
 * обязательной для всех устройств является только частота 44,1 кГц, остальное конвейер пересчитает сам.
 */
internal class AndroidMic private constructor(private val record: AudioRecord) : MicSource {
    override val sampleRate: Int = record.sampleRate

    override fun read(target: ShortArray): Int = record.read(target, 0, target.size, AudioRecord.READ_BLOCKING)

    override fun silenced(): Boolean = record.activeRecordingConfiguration?.isClientSilenced == true

    override fun interrupt() {
        runCatching { record.stop() }
    }

    override fun close() {
        runCatching { record.stop() }
        record.release()
    }

    companion object {
        private const val PREFERRED_RATE = 16_000
        private const val BUFFER_SECONDS = 2
        private val FallbackRates = listOf(44_100, 48_000)
        private val Inputs = listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)

        /** Открывает и запускает микрофон. При заданной [sampleRate] другие частоты не пробуются. */
        fun open(sampleRate: Int?): MicSource {
            val rates = sampleRate?.let(::listOf) ?: (listOf(PREFERRED_RATE) + FallbackRates)
            for (rate in rates) {
                for (input in Inputs) {
                    start(input, rate)?.let { return AndroidMic(it) }
                }
            }
            throw MicUnavailable("Ни один вход микрофона не открылся")
        }

        @SuppressLint("MissingPermission")
        private fun start(input: Int, rate: Int): AudioRecord? {
            val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) return null
            val format = AudioFormat.Builder()
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
            val record = try {
                AudioRecord.Builder()
                    .setAudioSource(input)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(max(minimum, rate * 2 * BUFFER_SECONDS))
                    .build()
            } catch (failure: UnsupportedOperationException) {
                return null
            } catch (failure: SecurityException) {
                throw MicUnavailable("Нет разрешения на микрофон", failure)
            }
            try {
                record.startRecording()
            } catch (failure: IllegalStateException) {
                record.release()
                return null
            }
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) return record
            record.release()
            return null
        }
    }
}
