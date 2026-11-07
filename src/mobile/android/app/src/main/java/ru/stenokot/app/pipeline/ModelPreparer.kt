package ru.stenokot.app.pipeline

import android.content.Context
import android.os.storage.StorageManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.stenokot.app.BuildConfig
import ru.stenokot.app.data.AtomicFiles
import ru.stenokot.app.domain.ModelItemUi
import ru.stenokot.app.domain.ModelReadiness
import ru.stenokot.app.domain.ModelsUi
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Готовит модели. ONNX читаются прямо из assets, поэтому импорт работает сразу после установки.
 * Копия нужна только GGUF, и делается она в простое: тяжёлая стадия приостанавливает её, а языковая
 * модель ждёт конца копии в состоянии WaitingModel. Экрана «модели не готовы» в приложении нет.
 */
class ModelPreparer(
    context: Context,
    private val ledger: ModelLedger,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val modelsInAssets: Boolean = BuildConfig.MODELS_IN_ASSETS,
) : LlmModelGate {
    private val app = context.applicationContext
    private val busy = MutableStateFlow(false)
    private val llm = MutableStateFlow(LlmModelState.PENDING)
    private val ui = MutableStateFlow(
        ModelsUi(ModelReadiness.COPYING, 0, 0, 0, emptyList()),
    )
    private val gate = ProgressGate(clock = clock)

    /** Готовность моделей для интерфейса. Обновляется не чаще раза в секунду. */
    val models: StateFlow<ModelsUi> = ui

    /** Запускает проверку и копию в фоне. */
    fun start() {
        scope.launch(io) { prepare() }
    }

    override fun state(): LlmModelState = llm.value

    override fun setBusy(busy: Boolean) {
        this.busy.value = busy
    }

    override suspend fun await(): LlmModelState = llm.first { it != LlmModelState.PENDING }

    /** Размер файла модели или null, если файла нет. */
    fun sizeOf(path: String): Long? =
        if (modelsInAssets) assetSize(path) else devFile(path).takeIf { it.isFile }?.length()

    private suspend fun prepare() {
        val items = buildItems()
        val onnxReady = ledger.paths.filter { it != ModelLedger.LLM_PATH && !it.startsWith("asr-en/") }
            .all { (sizeOf(it) ?: 0L) > 0L }
        publish(items, if (onnxReady) ModelReadiness.COPYING else ModelReadiness.ASR_UNAVAILABLE)
        val result = prepareLlm()
        val readiness = when {
            !onnxReady -> ModelReadiness.ASR_UNAVAILABLE
            result == ModelReadiness.READY -> ModelReadiness.READY
            else -> result
        }
        llm.value = if (result == ModelReadiness.READY) LlmModelState.READY else LlmModelState.UNAVAILABLE
        publish(buildItems(), readiness)
    }

    private suspend fun prepareLlm(): ModelReadiness {
        if (!modelsInAssets) {
            val ready = (sizeOf(ModelLedger.LLM_PATH) ?: 0L) > 0L
            return if (ready) ModelReadiness.READY else ModelReadiness.LLM_UNAVAILABLE
        }
        val total = assetSize(ModelLedger.LLM_PATH) ?: return ModelReadiness.LLM_UNAVAILABLE
        val versionDir = File(File(app.noBackupFilesDir, MODELS_DIR), ledger.version)
        val target = File(versionDir, ModelLedger.LLM_PATH)
        val marker = File(versionDir, READY_MARKER)
        removeOtherVersions(versionDir)
        if (marker.exists() && target.length() == total) {
            ui.update { it.copy(copiedBytes = total, totalBytes = total) }
            return ModelReadiness.READY
        }
        val tmp = File(target.parentFile, target.name + ".tmp")
        val needed = total - tmp.length().coerceAtMost(total)
        if (allocatableBytes(versionDir) < needed + COPY_RESERVE_BYTES) return ModelReadiness.STORAGE_LOW
        return copy(total, tmp, target, marker)
    }

    private suspend fun copy(total: Long, tmp: File, target: File, marker: File): ModelReadiness {
        val expected = ledger.sha256Of(ModelLedger.LLM_PATH)
        val copy = ResumableCopy(::openLlmAsset, total, tmp, target, expected)
        return try {
            val verified = copy.run(
                beforeBlock = { busy.first { !it } },
                onProgress = { copied -> reportCopy(copied, total) },
            )
            if (verified) {
                AtomicFiles.replaceText(marker, expected.orEmpty())
                ModelReadiness.READY
            } else {
                ModelReadiness.LLM_UNAVAILABLE
            }
        } catch (failure: IOException) {
            ModelReadiness.LLM_UNAVAILABLE
        }
    }

    private fun reportCopy(copied: Long, total: Long) {
        if (gate.allow(force = copied >= total)) {
            ui.update { it.copy(copiedBytes = copied, totalBytes = total, freeBytes = app.noBackupFilesDir.usableSpace) }
        }
    }

    private fun openLlmAsset(): InputStream =
        app.assets.openFd("$ASSET_ROOT/${ModelLedger.LLM_PATH}").createInputStream()

    private fun removeOtherVersions(current: File) {
        current.parentFile?.listFiles { file -> file.isDirectory && file != current }
            ?.forEach { it.deleteRecursively() }
    }

    private fun allocatableBytes(dir: File): Long {
        dir.mkdirs()
        val storage = app.getSystemService(StorageManager::class.java)
        return try {
            storage.getAllocatableBytes(storage.getUuidForPath(dir))
        } catch (failure: IOException) {
            dir.usableSpace
        }
    }

    private fun publish(items: List<ModelItemUi>, readiness: ModelReadiness) {
        ui.update { current ->
            current.copy(
                readiness = readiness,
                items = items,
                freeBytes = app.noBackupFilesDir.usableSpace,
            )
        }
    }

    private fun buildItems(): List<ModelItemUi> = ModelLedger.Cards.map { card ->
        val files = ledger.paths.filter { it.startsWith(card.prefix) }
        val sizes = files.map(::sizeOf)
        val llmCopyPending = card.prefix == "llm/" && llm.value != LlmModelState.READY && modelsInAssets
        ModelItemUi(
            name = card.name,
            role = card.role,
            sizeBytes = sizes.filterNotNull().sum(),
            sha256 = ledger.sha256Of(card.hashPath),
            license = card.license,
            ready = files.isNotEmpty() && sizes.none { it == null } && !llmCopyPending,
        )
    }

    private fun assetSize(path: String): Long? = try {
        app.assets.openFd("$ASSET_ROOT/$path").use { it.length }
    } catch (failure: IOException) {
        null
    }

    private fun devFile(path: String): File = File(app.getExternalFilesDir(MODELS_DIR), path)

    private companion object {
        const val ASSET_ROOT = "models"
        const val MODELS_DIR = "models"
        const val READY_MARKER = "llm.READY"
        const val COPY_RESERVE_BYTES = 32L * 1024 * 1024
    }
}
