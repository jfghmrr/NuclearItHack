package ru.stenokot.app.ui.record

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import ru.stenokot.app.R
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.ui.AppActions
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.ActionPair
import ru.stenokot.app.ui.components.BottomActions
import ru.stenokot.app.ui.components.GhostAction
import ru.stenokot.app.ui.components.MascotKind
import ru.stenokot.app.ui.components.MascotPlate
import ru.stenokot.app.ui.components.PrimaryAction
import ru.stenokot.app.ui.components.StenoTopBar
import ru.stenokot.app.ui.home.micGranted
import ru.stenokot.app.ui.home.openAppSettings
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.EnterMotion
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private val TextWidth = 310.dp

/**
 * Вместе с микрофоном просим уведомления: без них служба записи работает невидимо, а кнопка
 * остановки в шторке пропадает. Запись от второго ответа не зависит.
 */
private fun recordPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.RECORD_AUDIO)
    }

/**
 * Объяснение перед системным запросом. Экран открывается только по нажатию «Записать», поэтому в
 * автоматическом проходе его нет. После отказа единственный путь это настройки Android: запрос сам
 * не повторяется, а импорт файлов остаётся доступным.
 */
@Composable
fun MicPermissionRoute(controller: StenoController, actions: AppActions) {
    val context = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }
    val proceed = {
        actions.back()
        controller.startRecording()
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) proceed() else denied = true
    }
    LifecycleResumeEffect(context) {
        if (denied && micGranted(context)) proceed()
        onPauseOrDispose {}
    }
    val layout = LocalWindowLayout.current
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    Column(Modifier.fillMaxSize()) {
        StenoTopBar(stringResource(R.string.mic_title), onBack = actions.back)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = StenoSpace.xxl, vertical = StenoSpace.l),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (!layout.tiny) {
                MascotPlate(
                    kind = MascotKind.RECORD,
                    plate = if (layout.short) StenoSize.mascotPlateSmall else StenoSize.mascotPlate,
                    motion = EnterMotion.EMPTY,
                )
            }
            Text(
                text = stringResource(R.string.mic_headline),
                modifier = Modifier.padding(top = StenoSpace.l).semantics { heading() },
                style = type.headline,
                color = colors.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(if (denied) R.string.rec_denied else R.string.mic_body),
                modifier = Modifier
                    .padding(top = StenoSpace.m)
                    .widthIn(max = TextWidth)
                    .testTag(Tags.MIC_NOTICE)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = type.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.mic_note),
                modifier = Modifier.padding(top = StenoSpace.xl),
                style = type.meta,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        BottomActions {
            ActionPair(
                first = {
                    if (denied) {
                        PrimaryAction(
                            text = stringResource(R.string.rec_open_settings),
                            onClick = { openAppSettings(context) },
                            modifier = it,
                            tag = Tags.MIC_SETTINGS,
                        )
                    } else {
                        PrimaryAction(
                            text = stringResource(R.string.mic_continue),
                            onClick = { microphone.launch(recordPermissions()) },
                            modifier = it,
                            icon = R.drawable.ic_mic,
                            tag = Tags.MIC_CONTINUE,
                        )
                    }
                },
                second = {
                    GhostAction(
                        text = stringResource(R.string.mic_import),
                        onClick = {
                            actions.back()
                            actions.importAudio()
                        },
                        modifier = it,
                        tag = Tags.IMPORT,
                    )
                },
            )
        }
    }
}
