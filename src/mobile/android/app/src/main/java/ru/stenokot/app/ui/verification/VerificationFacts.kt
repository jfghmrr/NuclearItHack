package ru.stenokot.app.ui.verification

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.stenokot.app.domain.JobSummaryUi
import ru.stenokot.app.domain.ModelItemUi
import ru.stenokot.app.domain.ModelReadiness
import ru.stenokot.app.domain.VerificationUi

private const val BYTES_IN_MIB = 1_048_576L
private const val HASH_PREFIX = 12
private const val ANDROID_PERMISSION = "android.permission."

/** Что экран проверки читает прямо из установленного пакета, мимо конвейера. */
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

/** Имя разрешения без общего системного префикса. */
fun shortPermission(name: String): String = name.removePrefix(ANDROID_PERMISSION)

/** Начало хэша: полный хэш открывается по нажатию на строку. */
fun hashPrefix(sha256: String): String = sha256.take(HASH_PREFIX)

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
