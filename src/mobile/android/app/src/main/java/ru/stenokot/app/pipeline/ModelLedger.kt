package ru.stenokot.app.pipeline

import ru.stenokot.core.storage.ModelHash

/** Модель, как её показывает экран проверки. */
data class ModelCard(
    val name: String,
    val role: String,
    val license: String,
    val prefix: String,
    val hashPath: String,
)

/**
 * Перечень моделей из `config/models.lock`. Хэши вшиты в сборку, поэтому экран проверки показывает
 * их без чтения сотен мегабайт, а копия GGUF сверяется с тем, что зафиксировано в репозитории.
 */
class ModelLedger(lock: String) {
    private val hashes: Map<String, String> = lock.split('|', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"), limit = 2)
            if (parts.size == 2) parts[1] to parts[0] else null
        }
        .toMap()

    /** Версия набора: копия модели живёт в каталоге с этим именем. */
    val version: String = sha256(lock.toByteArray(Charsets.UTF_8)).take(VERSION_LENGTH)

    /** Все пути файлов моделей. */
    val paths: Set<String> get() = hashes.keys

    /** Возвращает хэш файла [path] или null, если файла нет в перечне. */
    fun sha256Of(path: String): String? = hashes[path]

    /** Перечень для metadata.json. [sizeOf] даёт размер файла, если он известен. */
    fun modelHashes(sizeOf: (String) -> Long?): List<ModelHash> =
        hashes.map { (path, sha) -> ModelHash(path, sha, sizeOf(path)) }.sortedBy { it.name }

    companion object {
        private const val VERSION_LENGTH = 12

        /** Файл языковой модели относительно корня моделей. */
        const val LLM_PATH = "llm/qwen3-0.6b-q4_k_m.gguf"

        /** Модели в порядке показа. */
        val Cards = listOf(
            ModelCard("GigaAM v3 e2e RNNT int8", "Русская речь", "MIT", "asr-ru/", "asr-ru/encoder.int8.onnx"),
            ModelCard("Parakeet TDT 0.6B v2 int8", "Английская речь", "CC-BY-4.0", "asr-en/", "asr-en/encoder.int8.onnx"),
            ModelCard("Silero VAD", "Поиск речи", "MIT", "vad/", "vad/silero_vad.onnx"),
            ModelCard(
                "pyannote-segmentation-3-0 int8",
                "Границы говорящих",
                "MIT",
                "diar/segmentation",
                "diar/segmentation.int8.onnx",
            ),
            ModelCard("TitaNet-small fp32", "Голоса говорящих", "Apache-2.0", "diar/titanet", "diar/titanet_small.onnx"),
            ModelCard("Qwen3-0.6B Q4_K_M", "Оглавление и протокол", "Apache-2.0", "llm/", LLM_PATH),
        )
    }
}
