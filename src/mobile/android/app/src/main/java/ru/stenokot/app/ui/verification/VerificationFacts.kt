package ru.stenokot.app.ui.verification

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.stenokot.app.domain.JobSummaryUi
import ru.stenokot.app.domain.ModelCheckUi
import ru.stenokot.app.domain.ModelItemUi
import ru.stenokot.app.domain.ModelReadiness
import ru.stenokot.app.domain.VerificationUi

private const val BYTES_IN_MIB = 1_048_576L
private const val ANDROID_PERMISSION = "android.permission."

/** Что экраны проверки читают прямо из установленного пакета, мимо конвейера. */
@Immutable
data class PackageFacts(
    val packageName: String,
    val versionName: String,
    val permissions: List<String>,
    val deviceModel: String,
    val totalMemoryMib: Long,
    val apiLevel: Int,
) {
    /** Разрешение на интернет в пакете действительно не запрошено. */
    val internetAbsent: Boolean get() = Manifest.permission.INTERNET !in permissions

    /** Разрешение на состояние сети в пакете действительно не запрошено. */
    val networkStateAbsent: Boolean get() = Manifest.permission.ACCESS_NETWORK_STATE !in permissions
}

private fun readPackageFacts(context: Context): PackageFacts {
    val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
    val memory = ActivityManager.MemoryInfo()
    context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
    return PackageFacts(
        packageName = context.packageName,
        versionName = info.versionName.orEmpty(),
        permissions = info.requestedPermissions.orEmpty().sorted(),
        deviceModel = Build.MODEL.orEmpty(),
        totalMemoryMib = memory.totalMem / BYTES_IN_MIB,
        apiLevel = Build.VERSION.SDK_INT,
    )
}

/** Сведения о пакете. Читаются вне главного потока, до этого значение равно null. */
@Composable
fun rememberPackageFacts(): State<PackageFacts?> {
    val context = LocalContext.current
    return produceState<PackageFacts?>(null, context) {
        value = withContext(Dispatchers.IO) { readPackageFacts(context) }
    }
}

/** Что приложению сейчас разрешено системой. */
@Immutable
data class DeviceAccess(val microphone: Boolean, val notifications: Boolean, val audioFiles: Boolean)

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun readDeviceAccess(context: Context): DeviceAccess {
    val audio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return DeviceAccess(
        microphone = granted(context, Manifest.permission.RECORD_AUDIO),
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        audioFiles = granted(context, audio),
    )
}

/** Разрешения перечитываются при каждом возврате на экран: их могли поменять в настройках системы. */
@Composable
fun rememberDeviceAccess(): State<DeviceAccess> {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val access = remember { mutableStateOf(readDeviceAccess(context)) }
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) access.value = readDeviceAccess(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return access
}

/** Имя разрешения без общего системного префикса. */
fun shortPermission(name: String): String = name.removePrefix(ANDROID_PERMISSION)

/** Название модели без формата весов и сам формат, если он указан. */
@Immutable
data class ModelTitle(val name: String, val format: String?)

private val WeightFormat = Regex("(?i)int\\d+|fp\\d+|q\\d+(_[a-z0-9]+)*")

/** Разбирает полное имя вида «GigaAM v3 e2e RNNT int8»: формат всегда стоит последним словом. */
fun modelTitle(full: String): ModelTitle {
    val last = full.substringAfterLast(' ', "")
    return if (WeightFormat.matches(last)) ModelTitle(full.substringBeforeLast(' '), last) else ModelTitle(full, null)
}

/** Итог сверки одной модели с хэшами сборки. */
enum class ModelIntegrity { NOT_CHECKED, CHECKING, PASSED, FAILED }

/** Состояние сверки для модели [name]. Без настоящей сверки итога нет. */
fun modelIntegrity(name: String, check: ModelCheckUi): ModelIntegrity = when {
    check.running -> ModelIntegrity.CHECKING
    check.verdicts[name] == true -> ModelIntegrity.PASSED
    check.verdicts[name] == false -> ModelIntegrity.FAILED
    else -> ModelIntegrity.NOT_CHECKED
}

/** Имя файла служебных данных для системного окна сохранения. */
fun metadataFileName(title: String): String = "${title.substringBeforeLast('.')}.metadata.json"

/** Что показать в строке модели. */
enum class ModelRowState { READY, COPYING, UNAVAILABLE, ASR_UNAVAILABLE, STORAGE_LOW }

/** Состояние строки: готовая модель всегда готова, остальные объясняются общей готовностью. */
fun modelRowState(item: ModelItemUi, readiness: ModelReadiness): ModelRowState = when {
    item.ready -> ModelRowState.READY
    readiness == ModelReadiness.COPYING -> ModelRowState.COPYING
    readiness == ModelReadiness.STORAGE_LOW -> ModelRowState.STORAGE_LOW
    readiness == ModelReadiness.ASR_UNAVAILABLE -> ModelRowState.ASR_UNAVAILABLE
    else -> ModelRowState.UNAVAILABLE
}

/**
 * Задача, к которой относятся замеры. Контроллер называет её прямо, а если не назвал, она находится
 * по имени в истории: экспорт служебных данных работает только с номером задачи.
 */
fun lastJobId(verification: VerificationUi, jobs: List<JobSummaryUi>): String? =
    verification.lastJobId ?: verification.lastJobTitle?.let { title -> jobs.firstOrNull { it.title == title }?.id }
