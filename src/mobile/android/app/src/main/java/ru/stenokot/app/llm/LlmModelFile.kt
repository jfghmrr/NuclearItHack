package ru.stenokot.app.llm

import android.content.Context
import java.io.File

/** Поиск файла весов. В сборке full его готовит установка моделей, в dev он лежит во внешнем каталоге. */
internal object LlmModelFile {
    private const val FILE_NAME = "qwen3-0.6b-q4_k_m.gguf"

    /** Возвращает файл весов или null, если модели на устройстве нет. */
    fun locate(context: Context): File? {
        val prepared = File(context.noBackupFilesDir, "models")
        val versions = prepared.listFiles { file -> file.isDirectory }.orEmpty().sortedByDescending { it.lastModified() }
        val candidates = versions.map { File(it, "llm/$FILE_NAME") } +
            File(prepared, "llm/$FILE_NAME") +
            listOfNotNull(context.getExternalFilesDir(null)?.let { File(it, "models/llm/$FILE_NAME") })
        return candidates.firstOrNull { it.isFile && it.length() > 0 }
    }
}
