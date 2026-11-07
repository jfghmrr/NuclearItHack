package ru.stenokot.app.llm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import ru.stenokot.core.engine.LlmRunner
import ru.stenokot.core.engine.Progress
import ru.stenokot.core.llm.LlmBudgetPolicy
import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.LlmBudget
import ru.stenokot.core.model.LlmResult
import ru.stenokot.core.model.LlmStatus

/**
 * Запускает модель в процессе :llm и ведёт её до конца.
 * Отмена укладывается в секунду: сначала флаг в обратном вызове ggml, затем завершение процесса.
 * Вызовы к системе синхронны и при нехватке памяти могут зависнуть на секунды, поэтому идут на
 * отдельном потоке [connector]. Поток [handler] выполняет только короткие шаги, и отмена его не ждёт.
 */
class RemoteLlmRunner(context: Context) : LlmRunner {
    private val appContext = context.applicationContext
    private val handler by lazy { Handler(HandlerThread("llm-client").apply { start() }.looper) }
    private val connector by lazy { Executors.newSingleThreadExecutor { task -> Thread(task, "llm-connect") } }
    private val current = AtomicReference<Session?>(null)

    override suspend fun run(jobDir: File, budget: LlmBudget, progress: Progress): LlmResult {
        val model = LlmModelFile.locate(appContext)
            ?: return LlmResult(LlmStatus.FAILED, null, ErrorCode.LLM_MODEL_UNAVAILABLE.name)
        if (!LlmDevice.hasEnoughMemory(appContext)) {
            return LlmResult(LlmStatus.SKIPPED_LOW_MEMORY, null, ErrorCode.LLM_LOW_MEMORY.name)
        }
        val request = LlmRequest(jobDir.path, model.path, budget.nCtx, budget.maxChunkTokens, budget.deadlineMs)
        return suspendCancellableCoroutine { continuation ->
            val session = Session(request, progress) { result ->
                current.set(null)
                continuation.resume(result)
            }
            current.set(session)
            continuation.invokeOnCancellation { session.requestCancel() }
            handler.post(session::start)
        }
    }

    override fun cancel() {
        current.get()?.requestCancel()
    }

    /** Один запуск стадии. Всё состояние меняется только на потоке [handler]. */
    private inner class Session(
        private var request: LlmRequest,
        private val progress: Progress,
        private val onResult: (LlmResult) -> Unit,
    ) : ServiceConnection {
        private val replies = Messenger(Handler(handler.looper, ::handleReply))
        private val protocol = File(request.jobDir, "protocol.json")
        private var service: Messenger? = null
        private var bound = false
        private var finished = false
        private var cancelling = false
        private var retried = false
        private var pid = 0
        private var loadedAtMs: Long? = null
        private val watchdog = Runnable(::onWatchdog)

        fun start() {
            if (finished) return
            bound = true
            val intent = Intent(appContext, LlmService::class.java)
            val callbacks = Executor { handler.post(it) }
            connector.execute {
                if (!appContext.bindService(intent, Context.BIND_AUTO_CREATE, callbacks, this)) {
                    handler.post { finish(LlmResult(LlmStatus.FAILED, null, "система отказала в связи со службой")) }
                }
            }
            loadedAtMs = null
            armWatchdog(LlmBudgetPolicy.LOAD_LIMIT_MS)
        }

        fun requestCancel() {
            handler.post {
                if (finished || cancelling) return@post
                cancelling = true
                val target = service
                if (target == null) {
                    finishCancelled()
                    return@post
                }
                send(target, Message.obtain(null, LlmMessage.CANCEL))
                handler.postDelayed(::forceCancel, this, CANCEL_GRACE_MS)
            }
        }

        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            val target = Messenger(binder)
            service = target
            if (cancelling) {
                finishCancelled()
                return
            }
            val message = Message.obtain(null, LlmMessage.START)
            message.replyTo = replies
            message.data = request.toBundle()
            send(target, message)
        }

        override fun onServiceDisconnected(name: ComponentName?) = onProcessDied()

        override fun onBindingDied(name: ComponentName?) = onProcessDied()

        override fun onNullBinding(name: ComponentName?) {
            finish(LlmResult(LlmStatus.FAILED, null, "служба не вернула канал связи"))
        }

        private fun handleReply(message: Message): Boolean {
            if (finished) return true
            when (message.what) {
                LlmMessage.STARTED -> pid = message.arg1
                LlmMessage.PROGRESS -> {
                    if (loadedAtMs == null) onModelLoaded()
                    progress.report(LlmProgressBundle.fraction(message.data), LlmProgressBundle.note(message.data))
                }
                LlmMessage.FINISHED -> finish(LlmOutcomeBundle.result(message.data))
            }
            return true
        }

        /** Первый прогресс приходит после загрузки модели: бюджет считается от этого момента. */
        private fun onModelLoaded() {
            loadedAtMs = SystemClock.elapsedRealtime()
            if (!cancelling) armWatchdog(LlmBudgetPolicy.generationDeadlineMs(request.budgetMs))
        }

        private fun armWatchdog(delayMs: Long) {
            handler.removeCallbacks(watchdog)
            handler.postDelayed(watchdog, this, delayMs)
        }

        /** Смерть процесса не трогает контрактный файл. Одна попытка дописать протокол с чекпойнта. */
        private fun onProcessDied() {
            if (finished || !bound) return
            unbind()
            service = null
            handler.removeCallbacksAndMessages(this)
            if (cancelling) {
                finishCancelled()
                return
            }
            val diedPid = pid
            handler.postDelayed({ inspectDeath(diedPid) }, this, EXIT_RECORD_DELAY_MS)
        }

        private fun inspectDeath(diedPid: Int) {
            connector.execute {
                val death = LlmExitReason.inspect(appContext, diedPid)
                handler.post { onDeathInspected(death) }
            }
        }

        private fun onDeathInspected(death: LlmDeath) {
            if (finished) return
            val spentMs = loadedAtMs?.let { SystemClock.elapsedRealtime() - it } ?: 0L
            val remainingMs = request.budgetMs - spentMs
            if (!retried && !cancelling && remainingMs > 0) {
                retried = true
                pid = 0
                request = request.copy(budgetMs = remainingMs)
                start()
                return
            }
            val status = if (hasProtocol()) LlmStatus.PARTIAL else LlmStatus.FAILED
            finish(LlmResult(status, protocolPath(), death.detail))
        }

        private fun onWatchdog() {
            killProcess()
            val status = if (hasProtocol()) LlmStatus.PARTIAL else LlmStatus.SKIPPED_BY_BUDGET
            finish(LlmResult(status, protocolPath(), ErrorCode.LLM_TIMEOUT.name))
        }

        private fun forceCancel() {
            killProcess()
            finishCancelled()
        }

        private fun killProcess() {
            if (pid != 0) Process.killProcess(pid)
        }

        private fun finishCancelled() = finish(LlmResult(LlmStatus.CANCELLED_BY_NEW_JOB, protocolPath(), null))

        private fun finish(result: LlmResult) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(this)
            onResult(if (cancelling) result.copy(status = LlmStatus.CANCELLED_BY_NEW_JOB) else result)
            unbind()
        }

        private fun unbind() {
            if (!bound) return
            bound = false
            connector.execute { runCatching { appContext.unbindService(this) } }
        }

        private fun send(target: Messenger, message: Message) {
            try {
                target.send(message)
            } catch (_: RemoteException) {
                onProcessDied()
            }
        }

        private fun hasProtocol(): Boolean = protocol.isFile && protocol.length() > 0

        private fun protocolPath(): String? = protocol.path.takeIf { hasProtocol() }
    }

    private companion object {
        const val CANCEL_GRACE_MS = 500L
        const val EXIT_RECORD_DELAY_MS = 300L
    }
}
