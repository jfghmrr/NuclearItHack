package ru.stenokot.app.llm

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import kotlin.concurrent.thread
import ru.stenokot.core.model.LlmResult
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.llm.LlamaBridge

/**
 * Связанная служба в отдельном процессе. Падение llama.cpp не задевает интерфейс и записанный файл.
 * Обычная служба, а не foreground: перезапустить такую из фона после падения было бы нельзя.
 */
class LlmService : Service() {
    private val incoming by lazy { Messenger(Handler(Looper.getMainLooper(), ::handle)) }
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    /** Завершение процесса гарантированно возвращает системе всю память модели. */
    override fun onDestroy() {
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }

    private fun handle(message: Message): Boolean {
        when (message.what) {
            LlmMessage.START -> start(LlmRequest.from(message.data), message.replyTo)
            LlmMessage.CANCEL -> LlamaBridge.cancel()
        }
        return true
    }

    private fun start(request: LlmRequest, client: Messenger) {
        if (worker?.isAlive == true) {
            val busy = LlmResult(LlmStatus.FAILED, null, "стадия уже выполняется")
            client.post(LlmMessage.FINISHED, data = LlmOutcomeBundle.of(busy))
            return
        }
        LlamaBridge.resetCancel()
        client.post(LlmMessage.STARTED, arg1 = Process.myPid())
        val nativeLibDir = applicationInfo.nativeLibraryDir
        worker = thread(name = "llm-worker") {
            val job = LlmWorker(request, nativeLibDir) { fraction, note ->
                client.post(LlmMessage.PROGRESS, data = LlmProgressBundle.of(fraction, note))
            }
            client.post(LlmMessage.FINISHED, data = LlmOutcomeBundle.of(job.run()))
        }
    }

    private fun Messenger.post(what: Int, arg1: Int = 0, data: Bundle? = null) {
        val message = Message.obtain(null, what, arg1, 0)
        message.data = data
        try {
            send(message)
        } catch (_: RemoteException) {
            stopSelf()
        }
    }
}
