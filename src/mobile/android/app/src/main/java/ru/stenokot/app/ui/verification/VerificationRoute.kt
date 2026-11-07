package ru.stenokot.app.ui.verification

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import ru.stenokot.app.domain.ExportKind
import ru.stenokot.app.domain.JobDetailUi
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.ui.AppActions
import ru.stenokot.app.ui.components.rememberDiagnosticsCopy
import ru.stenokot.app.ui.nav.Route

/** Действия экрана проверки. */
@Stable
internal class VerificationCallbacks(
    val onBack: () -> Unit,
    val onExportMeta: () -> Unit,
    val onNotifications: () -> Unit,
    val onLicenses: () -> Unit,
)

private fun notificationsAllowed(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

/** SCR-08. Нет ни одного значения, которое не пришло бы из пакета или из замера. */
@Composable
fun VerificationRoute(controller: StenoController, actions: AppActions) {
    val context = LocalContext.current
    val verification by controller.verification.collectAsStateWithLifecycle()
    val home by controller.home.collectAsStateWithLifecycle()
    val facts by rememberPackageFacts()
    val jobId = lastJobId(verification, home.jobs)
    val jobFlow = remember(jobId) { jobId?.let(controller::job) ?: MutableStateFlow<JobDetailUi?>(null) }
    val job by jobFlow.collectAsStateWithLifecycle()
    var allowed by remember { mutableStateOf(notificationsAllowed(context)) }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = it
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null && jobId != null) controller.export(jobId, ExportKind.METADATA, uri)
    }
    val copy = rememberDiagnosticsCopy(controller, null)
    val title = verification.lastJobTitle
    val callbacks = remember(actions, title) {
        VerificationCallbacks(
            onBack = actions.back,
            onExportMeta = {
                if (title != null) {
                    try {
                        exporter.launch(metadataFileName(title))
                    } catch (_: ActivityNotFoundException) {
                        Unit
                    }
                }
            },
            onNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onLicenses = { actions.navigator.push(Route.Licenses) },
        )
    }
    VerificationScreen(
        state = VerificationScreenState(
            verification = verification,
            models = home.models,
            items = verification.models.ifEmpty { home.models.items },
            facts = facts,
            canExport = jobId != null,
            exportFailed = job?.result?.output?.exportFailed == true,
            notificationsAllowed = allowed,
        ),
        copy = copy,
        callbacks = callbacks,
    )
}
