package ru.stenokot.app

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.stenokot.app.domain.ExportKind
import ru.stenokot.app.domain.HomeUi
import ru.stenokot.app.domain.JobDetailUi
import ru.stenokot.app.domain.MicProblem
import ru.stenokot.app.domain.ModelCheckUi
import ru.stenokot.app.domain.ModelReadiness
import ru.stenokot.app.domain.ModelsUi
import ru.stenokot.app.domain.RecordingUi
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.domain.VerificationUi
import ru.stenokot.app.ui.format.STATUS_IDLE

/** Контроллер без конвейера: честно сообщает, что обработка недоступна, и ничего не делает. */
internal class InertController : StenoController {
    override val home: StateFlow<HomeUi> = MutableStateFlow(
        HomeUi(
            models = ModelsUi(ModelReadiness.ASR_UNAVAILABLE, 0, 0, 0, emptyList()),
            audioPermissionGranted = true,
            audio = emptyList(),
            jobs = emptyList(),
            statusCode = STATUS_IDLE,
        ),
    )

    override val verification: StateFlow<VerificationUi> = MutableStateFlow(
        VerificationUi(
            internetPermissionAbsent = true,
            networkStatePermissionAbsent = true,
            appVersion = "",
            models = emptyList(),
            lastJobTitle = null,
            audioDurationMs = null,
            processingMs = null,
            rtfCore = null,
            rtfFull = null,
            peakRssMb = null,
            stageMetrics = emptyList(),
            jsonValid = null,
            jsonSha256 = null,
            jsonPath = null,
            llmTokens = null,
        ),
    )

    private val noJob = MutableStateFlow<JobDetailUi?>(null)

    override fun job(id: String): StateFlow<JobDetailUi?> = noJob

    override fun import(uris: List<Uri>): String? = null

    override fun start(id: String) = Unit

    override fun cancel(id: String) = Unit

    override fun resume(id: String) = Unit

    override fun delete(id: String, removeExported: Boolean) = Unit

    override fun export(id: String, kind: ExportKind, target: Uri) = Unit

    override fun refreshAudio() = Unit

    override fun diagnosticsPackage(id: String?): String = ""

    override val recording: MutableStateFlow<RecordingUi> = MutableStateFlow(RecordingUi.Idle)

    override val micLevel: StateFlow<Float> = MutableStateFlow(0f)

    override val modelCheck: StateFlow<ModelCheckUi> = MutableStateFlow(ModelCheckUi())

    override fun checkModels() = Unit

    override fun startRecording() {
        recording.value = RecordingUi.Failed(MicProblem.UNAVAILABLE)
    }

    override fun stopRecording() = Unit

    override fun cancelRecording() = Unit

    override fun acknowledgeRecording() {
        recording.value = RecordingUi.Idle
    }
}
