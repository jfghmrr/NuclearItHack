package ru.stenokot.app.speech

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import ru.stenokot.app.BuildConfig
import ru.stenokot.app.audio.EngineException
import ru.stenokot.core.model.ErrorCode

/** Относительные пути файлов моделей. Раскладка одна для assets и для каталога на устройстве. */
object ModelFiles {
    const val ASR_RU_ENCODER = "asr-ru/encoder.int8.onnx"
    const val ASR_RU_DECODER = "asr-ru/decoder.onnx"
    const val ASR_RU_JOINER = "asr-ru/joiner.onnx"
    const val ASR_RU_TOKENS = "asr-ru/tokens.txt"
    const val ASR_EN_ENCODER = "asr-en/encoder.int8.onnx"
    const val ASR_EN_DECODER = "asr-en/decoder.int8.onnx"
    const val ASR_EN_JOINER = "asr-en/joiner.int8.onnx"
    const val ASR_EN_TOKENS = "asr-en/tokens.txt"
    const val VAD = "vad/silero_vad.onnx"
    const val DIAR_SEGMENTATION = "diar/segmentation.int8.onnx"
    const val DIAR_EMBEDDING = "diar/titanet_small.onnx"
    const val LLM = "llm/qwen3-0.6b-q4_k_m.gguf"
}

/**
 * Откуда брать модели: полная сборка читает их из assets, сборка dev из каталога на устройстве.
 * Нативный код завершает процесс на отсутствующем файле, поэтому [require] проверяет файлы заранее.
 */
class ModelSource private constructor(
    /** Менеджер assets для нативных конструкторов. Значение null означает чтение файлов по пути. */
    val assets: AssetManager?,
    private val root: File?,
) {
    /** Путь к модели в том виде, в каком его ждёт нативный конструктор. */
    fun path(relative: String): String =
        if (assets != null) "$ASSET_ROOT/$relative" else File(root, relative).path

    /** Размер файла, байты. Значение null: файла нет. */
    fun sizeOf(relative: String): Long? {
        if (assets != null) {
            return try {
                assets.openFd(path(relative)).use { it.length }
            } catch (_: IOException) {
                null
            }
        }
        return File(root, relative).takeIf { it.isFile }?.length()
    }

    /** Считает SHA-256 файла модели потоково. Нативный код падает на испорченной модели, сверять надо до загрузки. */
    fun sha256(relative: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val block = ByteArray(HASH_BLOCK)
        open(relative).use { input ->
            while (true) {
                val read = input.read(block)
                if (read < 0) break
                digest.update(block, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Проверяет, что все [relative] на месте и не пусты. Иначе бросает код загрузки модели. */
    fun require(vararg relative: String) {
        for (name in relative) {
            val size = sizeOf(name)
            if (size == null || size == 0L) {
                throw EngineException(ErrorCode.MODEL_LOAD_FAILED, "Нет файла модели: $name")
            }
        }
    }

    private fun open(relative: String): InputStream =
        if (assets != null) assets.open(path(relative)) else File(root, relative).inputStream()

    companion object {
        private const val ASSET_ROOT = "models"
        private const val HASH_BLOCK = 1 shl 16

        /** Выбирает источник по типу сборки. */
        fun from(context: Context): ModelSource =
            if (BuildConfig.MODELS_IN_ASSETS) {
                ModelSource(context.assets, null)
            } else {
                ModelSource(null, context.getExternalFilesDir(ASSET_ROOT))
            }

        /** Источник на каталоге [directory]. Нужен для проверок на устройстве. */
        fun fromDirectory(directory: File): ModelSource = ModelSource(null, directory)
    }
}

/** Создаёт нативный объект и переводит отказ конструктора в код загрузки модели. */
internal inline fun <T> loadModel(name: String, create: () -> T): T =
    try {
        create()
    } catch (error: IllegalArgumentException) {
        throw EngineException(ErrorCode.MODEL_LOAD_FAILED, "Модель не загрузилась: $name", error)
    } catch (error: UnsatisfiedLinkError) {
        throw EngineException(ErrorCode.MODEL_LOAD_FAILED, "Нет нативной библиотеки: $name", error)
    }
