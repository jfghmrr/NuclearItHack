package ru.stenokot.app.ui.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.stenokot.app.R
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.ui.AppActions
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.BannerTone
import ru.stenokot.app.ui.components.BottomActions
import ru.stenokot.app.ui.components.PrimaryAction
import ru.stenokot.app.ui.components.StatusChip
import ru.stenokot.app.ui.components.StenoTopBar
import ru.stenokot.app.ui.format.formatTimecode
import ru.stenokot.app.ui.layout.CenteredContent
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.nav.ResultTab
import ru.stenokot.app.ui.nav.Route
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private val QuoteRule = 3.dp

/**
 * Пункт протокола и его основание. Модель не даёт отдельной формулировки, исполнителя и срока, поэтому
 * их нет и здесь: экран показывает дословную реплику, по которой пункт можно проверить.
 */
@Composable
fun ProtocolItemRoute(route: Route.ProtocolItem, controller: StenoController, actions: AppActions) {
    val job by remember(route.jobId) { controller.job(route.jobId) }.collectAsStateWithLifecycle()
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val document = job?.result?.protocol?.document
    val item = remember(document, route.index) { document?.let(::protocolItems)?.getOrNull(route.index) }
    if (item == null) {
        StenoTopBar(stringResource(R.string.common_tab_protocol), onBack = actions.back)
        return
    }
    val kind = itemKind(item.kind)
    val number = speakerNumber(item.speakerId)
    Column(Modifier.fillMaxSize()) {
        StenoTopBar(stringResource(kind.title), onBack = actions.back)
        CenteredContent(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(
                modifier = Modifier.padding(horizontal = LocalWindowLayout.current.gutter, vertical = StenoSpace.m),
                verticalArrangement = Arrangement.spacedBy(StenoSpace.l),
            ) {
                StatusChip(stringResource(R.string.scr05_item_chip), kind.icon, BannerTone.WARNING)
                Text(
                    text = stringResource(R.string.scr05_item_basis),
                    modifier = Modifier.padding(top = StenoSpace.s).semantics { heading() },
                    style = type.labelMedium,
                    color = colors.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(StenoSpace.l),
                ) {
                    Spacer(
                        Modifier
                            .width(QuoteRule)
                            .fillMaxHeight()
                            .background(StenoTheme.speakers.style(number).line, RoundedCornerShape(StenoRadius.xs)),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StenoSpace.s)) {
                        Text(
                            text = stringResource(R.string.scr05_item_quote, item.text),
                            modifier = Modifier.testTag(Tags.ITEM_QUOTE),
                            style = type.titleLarge.copy(fontWeight = type.bodyLarge.fontWeight),
                            color = colors.onSurface,
                        )
                        Text(
                            text = stringResource(
                                R.string.scr05_item_author,
                                stringResource(R.string.common_speaker, number),
                                formatTimecode(item.startMs),
                            ),
                            style = type.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        BottomActions {
            PrimaryAction(
                text = stringResource(R.string.scr05_item_goto),
                onClick = {
                    actions.navigator.back()
                    actions.navigator.replaceTop(
                        Route.Result(route.jobId, ResultTab.TRANSCRIPT, item.utterance, play = true),
                    )
                },
                icon = R.drawable.ic_play_arrow_fill,
                tag = Tags.GOTO_UTTERANCE,
            )
        }
    }
}
