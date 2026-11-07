package ru.stenokot.app.llm

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.stenokot.core.model.LlmBudget

/**
 * Проверка всего пути через службу. Команды приходят строкой action:
 * run запускает стадию над каталогом jobs/probe, cancel отменяет её и печатает задержку.
 */
class LlmRunnerProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        File(getExternalFilesDir(null), "jobs/probe").mkdirs()
        when (intent.getStringExtra("command")) {
            "run" -> run(intent)
            "cancel" -> cancel()
        }
    }

    private fun run(intent: Intent) {
        val jobDir = File(getExternalFilesDir(null), "jobs/probe")
        val budget = LlmBudget(nCtx = 1024, maxChunkTokens = 450, deadlineMs = intent.getLongExtra("budget", 90_000L))
        val started = SystemClock.elapsedRealtime()
        Log.i(TAG, "run pid=${android.os.Process.myPid()} dir=$jobDir")
        scope.launch {
            val result = runner(this@LlmRunnerProbeActivity).run(jobDir, budget) { fraction, note ->
                Log.i(TAG, "progress fraction=$fraction note=$note")
            }
            val now = SystemClock.elapsedRealtime()
            Log.i(TAG, "result status=${result.status} path=${result.protocolPath} detail=${result.detail} " +
                "total_ms=${now - started} since_cancel_ms=${if (cancelledAt > 0) now - cancelledAt else -1}")
        }
    }

    private fun cancel() {
        cancelledAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "cancel requested")
        runner(this).cancel()
    }

    private companion object {
        const val TAG = "StenokotProbe"
        val scope = CoroutineScope(Dispatchers.Default)
        var cancelledAt = 0L
        var instance: RemoteLlmRunner? = null

        fun runner(activity: Activity): RemoteLlmRunner =
            instance ?: RemoteLlmRunner(activity).also { instance = it }
    }
}
