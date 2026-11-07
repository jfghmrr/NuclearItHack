package ru.stenokot.app.llm

import android.app.Activity
import android.os.Bundle
import android.util.Log
import java.io.File
import kotlin.concurrent.thread
import ru.stenokot.core.llm.LlmSampler
import ru.stenokot.llm.ContextConfig
import ru.stenokot.llm.LlamaBridge
import ru.stenokot.llm.LoadStatus

/** Прямой запуск модели без службы и грамматики: печатает возможности процессора, токены и пик памяти. */
class LlmRawProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val threads = intent.getIntExtra("threads", 3)
        val repack = intent.getBooleanExtra("repack", false)
        thread(name = "llm-raw-probe") { probe(threads, repack) }
    }

    private fun probe(threads: Int, repack: Boolean) {
        val directory = File(getExternalFilesDir(null), "models/llm").apply { mkdirs() }
        val model = File(directory, "qwen3-0.6b-q4_k_m.gguf")
        Log.i(TAG, "model=${model.path} size=${model.length()} threads=$threads repack=$repack")
        val info = LlamaBridge.initialize(applicationInfo.nativeLibraryDir)
        Log.i(TAG, "system_info=$info")
        val loadStart = System.nanoTime()
        val status = LlamaBridge.load(model.path, ContextConfig(threads = threads, repackWeights = repack))
        Log.i(TAG, "load=$status ms=${(System.nanoTime() - loadStart) / 1_000_000} ${memory()}")
        if (status != LoadStatus.LOADED) return

        val question = intent.getStringExtra("question") ?: "Назови три столицы Европы."
        val body = "Расскажи подробно, почему важно вести протокол совещаний. ".repeat(intent.getIntExtra("pad", 0))
        val prompt = "<|im_start|>user\n$body$question<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
        val tokens = intent.getIntExtra("tokens", 48)
        val result = LlamaBridge.generate(prompt, null, LlmSampler(maxOutputTokens = tokens), 0)
        Log.i(TAG, "status=${result.status} prompt=${result.promptTokens} generated=${result.generatedTokens}")
        Log.i(TAG, "promptMs=${result.promptMs} generationMs=${result.generationMs} samplingMs=${result.samplingMs}")
        Log.i(TAG, "text=${result.text}")
        Log.i(TAG, "done ${memory()}")
        LlamaBridge.unload()
    }

    private fun memory(): String = File("/proc/self/status").readLines()
        .filter { it.startsWith("VmHWM") || it.startsWith("VmRSS") || it.startsWith("RssAnon") || it.startsWith("RssFile") }
        .joinToString(" ") { it.replace(Regex("\\s+"), " ") }

    private companion object {
        const val TAG = "StenokotProbe"
    }
}
