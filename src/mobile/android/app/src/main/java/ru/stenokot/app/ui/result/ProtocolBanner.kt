package ru.stenokot.app.ui.result

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import ru.stenokot.app.R
import ru.stenokot.app.domain.ProtocolUi
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.BannerTone
import ru.stenokot.app.ui.components.StenoIcon
import ru.stenokot.app.ui.components.toneColors
import ru.stenokot.app.ui.format.formatMibValue
import ru.stenokot.app.ui.format.windowsLabel
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

/** Тон баннера. Красный только там, где нет файла: сбой протокола это предупреждение. */
internal val ProtocolPhase.tone: BannerTone
    get() = when (this) {
        ProtocolPhase.SOON,
        ProtocolPhase.LOADING,
        ProtocolPhase.RUNNING,
        ProtocolPhase.WAITING_MODEL,
        -> BannerTone.WORKING
        ProtocolPhase.APPLIED -> BannerTone.SUCCESS
        ProtocolPhase.DEFERRED,
        ProtocolPhase.CANCELLED_BY_NEW_JOB,
        ProtocolPhase.NO_SPEECH,
        -> BannerTone.NEUTRAL
        ProtocolPhase.PARTIAL,
        ProtocolPhase.SKIPPED_MEMORY,
        ProtocolPhase.SKIPPED_BUDGET,
        ProtocolPhase.FAILED,
        ProtocolPhase.NO_MODEL,
        -> BannerTone.WARNING
    }

/** Значок положения протокола. Он же показывается на вкладке, когда баннер ушёл за край списка. */
@get:DrawableRes
internal val ProtocolPhase.icon: Int
    get() = when (this) {
        ProtocolPhase.SOON, ProtocolPhase.LOADING, ProtocolPhase.RUNNING -> R.drawable.ic_pending
        ProtocolPhase.WAITING_MODEL -> R.drawable.ic_hourglass_top
        ProtocolPhase.APPLIED -> R.drawable.ic_check_circle_fill
        ProtocolPhase.PARTIAL, ProtocolPhase.FAILED, ProtocolPhase.NO_MODEL -> R.drawable.ic_warning
        ProtocolPhase.DEFERRED, ProtocolPhase.CANCELLED_BY_NEW_JOB -> R.drawable.ic_schedule
        ProtocolPhase.SKIPPED_MEMORY -> R.drawable.ic_memory
        ProtocolPhase.SKIPPED_BUDGET -> R.drawable.ic_timer_off
        ProtocolPhase.NO_SPEECH -> R.drawable.ic_info
    }

/** Текст баннера для положения [phase]. Счётчики подставляются только реальные. */
@Composable
internal fun bannerText(phase: ProtocolPhase, protocol: ProtocolUi): String = when (phase) {
    ProtocolPhase.SOON -> stringResource(R.string.banner_soon)
    ProtocolPhase.LOADING -> stringResource(R.string.banner_loading)
    ProtocolPhase.RUNNING -> stringResource(
        R.string.banner_running,
        protocol.windowsDone ?: 0,
        protocol.windowsTotal ?: 0,
    )
    ProtocolPhase.WAITING_MODEL -> stringResource(
        R.string.banner_waiting_model,
        formatMibValue(protocol.modelCopiedBytes ?: 0),
        formatMibValue(protocol.modelTotalBytes ?: 0),
    )
    ProtocolPhase.APPLIED -> stringResource(R.string.banner_applied)
    ProtocolPhase.PARTIAL -> stringResource(
        R.string.banner_partial,
        windowsLabel(protocol.windowsDone ?: 0),
        protocol.windowsTotal ?: 0,
    )
    ProtocolPhase.DEFERRED -> stringResource(R.string.banner_deferred)
    ProtocolPhase.CANCELLED_BY_NEW_JOB -> stringResource(R.string.banner_cancelled)
    ProtocolPhase.SKIPPED_MEMORY -> stringResource(R.string.banner_skipped_memory)
    ProtocolPhase.SKIPPED_BUDGET -> stringResource(R.string.banner_skipped_budget)
    ProtocolPhase.FAILED -> stringResource(R.string.banner_failed)
    ProtocolPhase.NO_MODEL -> stringResource(R.string.banner_no_model)
    ProtocolPhase.NO_SPEECH -> stringResource(R.string.scr05_no_speech)
}

/**
 * Статус протокола. Изменения озвучиваются вежливо, не перебивая чтение. Без [onClick] баннер
 * только сообщает: на вкладке протокола переходить уже некуда.
 */
@Composable
internal fun ProtocolBanner(
    phase: ProtocolPhase,
    protocol: ProtocolUi,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val palette = toneColors(phase.tone)
    val shape = RoundedCornerShape(StenoRadius.m)
    val label = stringResource(R.string.banner_open)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = StenoSize.touchTarget)
            .clip(shape)
            .background(palette.container)
            .testTag(Tags.BANNER_LLM)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = StenoSpace.m, vertical = StenoSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
    ) {
        StenoIcon(phase.icon, palette.icon)
        Text(
            text = bannerText(phase, protocol),
            modifier = Modifier.weight(1f),
            style = StenoTheme.type.labelMedium.copy(fontWeight = FontWeight.W600),
            color = palette.content,
        )
        if (onClick != null) StenoIcon(R.drawable.ic_chevron_right, palette.content)
    }
}
