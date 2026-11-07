package ru.stenokot.app.llm

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import ru.stenokot.core.model.ErrorCode

/** Причина смерти процесса :llm. [code] равен null, если система её не сообщила. */
internal data class LlmDeath(val code: ErrorCode?, val detail: String)

/** Различает нехватку памяти и падение в нативном коде по записям системы о завершении процессов. */
internal object LlmExitReason {
    private const val RECORDS_TO_READ = 8
    private const val SIGKILL = 9

    /** Читает последнюю запись о процессе :llm. Значение [pid], равное 0, означает любой номер процесса. */
    fun inspect(context: Context, pid: Int): LlmDeath {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return LlmDeath(null, "exit reason unavailable below API 30")
        }
        val manager = context.getSystemService(ActivityManager::class.java)
        val process = context.packageName + LLM_PROCESS_SUFFIX
        val exit = manager.getHistoricalProcessExitReasons(context.packageName, pid, RECORDS_TO_READ)
            .firstOrNull { it.processName == process }
            ?: return LlmDeath(null, "no exit record")
        val code = classify(exit.reason, exit.status)
        return LlmDeath(code, "${code?.name ?: "LLM_DIED"} reason=${exit.reason} status=${exit.status} " +
            "pss_kb=${exit.pss} rss_kb=${exit.rss}")
    }

    /**
     * Сопоставляет запись системы с кодом ошибки.
     * Отчёт REASON_LOW_MEMORY есть не на всех образах, поэтому SIGKILL без объяснения тоже
     * считается нехваткой памяти: так завершают процесс lmkd и ядро. Обычное падение SIGKILL не даёт.
     */
    fun classify(reason: Int, status: Int): ErrorCode? = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> ErrorCode.LLM_DIED_OOM
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_CRASH,
        -> ErrorCode.LLM_DIED_NATIVE
        ApplicationExitInfo.REASON_SIGNALED ->
            if (status == SIGKILL) ErrorCode.LLM_DIED_OOM else ErrorCode.LLM_DIED_NATIVE
        else -> null
    }
}
