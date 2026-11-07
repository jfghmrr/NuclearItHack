package ru.stenokot.llm

import ru.stenokot.core.llm.LlmSampler

/** Итог загрузки модели. Порядок значений совпадает с кодами в llama_bridge.cpp. */
enum class LoadStatus { LOADED, CANCELLED, MODEL_FAILED, CONTEXT_FAILED }

/** Итог генерации. Порядок значений совпадает с кодами в llama_bridge.cpp. */
enum class GenerationStatus {
    /** Модель сама завершила ответ по грамматике. */
    COMPLETED,

    /** Ответ оборван пределом числа токенов. */
    LIMIT_REACHED,
    CANCELLED,
    TIMED_OUT,
    PROMPT_TOO_LONG,
    BAD_GRAMMAR,
    FAILED,
}

/** Настройки контекста. Значения по умолчанию заданы решением по памяти для 4 ГБ. */
data class ContextConfig(
    val threads: Int,
    val contextTokens: Int = 1024,
    val batchTokens: Int = 256,
    val repackWeights: Boolean = false,
)

/** Ответ модели и счётчики запроса. */
data class GenerationResult(
    val status: GenerationStatus,
    val text: String,
    val promptTokens: Int,
    val decodedPromptTokens: Int,
    val generatedTokens: Int,
    val promptMs: Long,
    val generationMs: Long,
    val samplingMs: Long,
)

/** Точка входа в llama.cpp. Загружать только в процессе :llm: падение в нативном коде не должно задеть интерфейс. */
object LlamaBridge {
    private const val SLOT_COUNT = 7

    init {
        System.loadLibrary("stenokot_llm")
    }

    /** Выбирает лучший вариант CPU из каталога [nativeLibDir] и возвращает описание возможностей процессора. */
    fun initialize(nativeLibDir: String): String = nativeInit(nativeLibDir)

    /** Загружает модель через mmap и создаёт контекст. Долгий вызов, поток не должен быть главным. */
    fun load(modelPath: String, config: ContextConfig): LoadStatus =
        LoadStatus.entries[nativeLoad(
            modelPath,
            config.contextTokens,
            config.batchTokens,
            config.threads,
            config.repackWeights,
        )]

    /**
     * Выполняет один запрос. Общий с прошлым запросом префикс заново не считается,
     * поэтому системный промпт обрабатывается один раз.
     * Положительный [timeoutMs] ограничивает всю генерацию, включая обработку промпта.
     */
    fun generate(prompt: String, grammar: String?, sampler: LlmSampler, timeoutMs: Long): GenerationResult {
        val stats = LongArray(SLOT_COUNT)
        val answer = nativeGenerate(
            prompt.encodeToByteArray(),
            grammar?.encodeToByteArray(),
            sampler.maxOutputTokens,
            sampler.temperature,
            sampler.topK,
            sampler.topP,
            sampler.seed,
            timeoutMs,
            stats,
        )
        return GenerationResult(
            status = GenerationStatus.entries[stats[0].toInt()],
            text = answer?.decodeToString().orEmpty(),
            promptTokens = stats[1].toInt(),
            decodedPromptTokens = stats[2].toInt(),
            generatedTokens = stats[3].toInt(),
            promptMs = stats[4],
            generationMs = stats[5],
            samplingMs = stats[6],
        )
    }

    /** Просит прервать работу. Вызывается с любого потока и действует между операциями графа. */
    fun cancel() = nativeCancel()

    /** Снимает отмену перед новой задачей. */
    fun resetCancel() = nativeResetCancel()

    /** Освобождает контекст и модель. */
    fun unload() = nativeUnload()

    private external fun nativeInit(nativeLibDir: String): String

    private external fun nativeLoad(
        modelPath: String,
        nCtx: Int,
        nBatch: Int,
        nThreads: Int,
        repackWeights: Boolean,
    ): Int

    private external fun nativeGenerate(
        prompt: ByteArray,
        grammar: ByteArray?,
        nPredict: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        seed: Int,
        timeoutMs: Long,
        stats: LongArray,
    ): ByteArray?

    private external fun nativeCancel()

    private external fun nativeResetCancel()

    private external fun nativeUnload()
}
