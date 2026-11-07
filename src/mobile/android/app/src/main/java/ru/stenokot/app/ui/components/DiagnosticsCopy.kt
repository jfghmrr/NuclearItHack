package ru.stenokot.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.stenokot.app.R
import ru.stenokot.app.domain.StenoController

private const val COPIED_VISIBLE_MS = 2_500L

/** Копирование диагностического пакета. [copied] держится пару секунд как подтверждение. */
@Stable
class DiagnosticsCopy internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val controller: StenoController,
    private val jobId: String?,
    private val clipLabel: String,
) {
    /** Пакет только что попал в буфер обмена. */
    var copied by mutableStateOf(false)
        private set

    /** Собирает пакет вне главного потока и кладёт его в буфер обмена. */
    fun copy() {
        scope.launch {
            val text = withContext(Dispatchers.Default) { controller.diagnosticsPackage(jobId) }
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText(clipLabel, text))
            copied = true
        }
    }

    internal fun reset() {
        copied = false
    }
}

/** Действие копирования для задачи [jobId] или для всех задач, если он равен null. */
@Composable
fun rememberDiagnosticsCopy(controller: StenoController, jobId: String?): DiagnosticsCopy {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipLabel = stringResource(R.string.diagnostics_clip_label)
    val state = remember(controller, jobId) { DiagnosticsCopy(context, scope, controller, jobId, clipLabel) }
    LaunchedEffect(state.copied) {
        if (state.copied) {
            delay(COPIED_VISIBLE_MS)
            state.reset()
        }
    }
    return state
}
