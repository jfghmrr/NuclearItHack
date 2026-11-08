package ru.stenokot.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.stenokot.app.R
import ru.stenokot.app.domain.RecordingUi
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.GhostAction
import ru.stenokot.app.ui.components.MascotKind
import ru.stenokot.app.ui.components.MascotPlate
import ru.stenokot.app.ui.components.PrimaryAction
import ru.stenokot.app.ui.components.QuietAction
import ru.stenokot.app.ui.layout.CenteredContent
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.EnterMotion
import ru.stenokot.app.ui.theme.LocalMotionEnabled
import ru.stenokot.app.ui.theme.StenoMotion
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private val DotSize = 8.dp
private val DotActiveWidth = 24.dp

/** Идентификаторы элементов знакомства для автоматики. */
object OnboardingTags {
    /** Корень знакомства. */
    const val ROOT = "onb_root"

    /** Листаемые страницы. */
    const val PAGER = "onb_pager"

    /** «Пропустить». */
    const val SKIP = "onb_skip"

    /** «Далее». */
    const val NEXT = "onb_next"

    /** «Начать» на последней странице. */
    const val START = "onb_start"

    /** Точки страниц. */
    const val DOTS = "onb_dots"

    /** Заголовок страницы [index]. */
    fun page(index: Int): String = "onb_page_$index"
}

/** Страница знакомства: маскот, текст и упрощённая картинка экрана. */
internal enum class IntroPage(val mascot: MascotKind, @StringRes val title: Int, @StringRes val body: Int) {
    IMPORT(MascotKind.EMPTY, R.string.onb_import_title, R.string.onb_import_body),
    DEVICE(MascotKind.WAITING, R.string.onb_device_title, R.string.onb_device_body),
    RESULT(MascotKind.DONE, R.string.onb_result_title, R.string.onb_result_body),
    EXPORT(MascotKind.DONE, R.string.onb_export_title, R.string.onb_export_body),
}

/**
 * Следит за открытым знакомством: оно закрывается само, как только снаружи пришёл файл, началась
 * запись или в библиотеке появилась идущая задача. Пока знакомство открыто, экраны под ним не
 * строятся, поэтому в дереве нет второй кнопки импорта.
 */
@Composable
fun OnboardingWatch(state: OnboardingState, controller: StenoController, externalRoute: Boolean) {
    if (!state.visible) return
    val home by controller.home.collectAsStateWithLifecycle()
    val recording by controller.recording.collectAsStateWithLifecycle()
    LaunchedEffect(externalRoute, home.jobs, recording) {
        val busy = recording !is RecordingUi.Idle
        if (OnboardingRules.interrupted(externalRoute, home.jobs.map { it.state }, busy)) state.finish()
    }
}

/**
 * Четыре коротких страницы. Листаются пальцем и кнопкой «Далее», точки показывают место. Кнопка
 * «Импорт аудио» есть на каждой странице: человек, который уже знает, что делать, сразу выбирает файл.
 */
@Composable
fun OnboardingScreen(state: OnboardingState, onImport: () -> Unit, modifier: Modifier = Modifier) {
    val onFinish: () -> Unit = state::finish
    val pages = IntroPage.entries
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val layout = LocalWindowLayout.current
    val last = pager.currentPage == pages.lastIndex
    BackHandler {
        if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } else onFinish()
    }
    val motion = LocalMotionEnabled.current
    val appear = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(StenoMotion.LONG)) }
    Column(
        modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .background(StenoTheme.colors.background)
            .testTag(OnboardingTags.ROOT),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = StenoSize.touchTarget)
                .padding(horizontal = StenoSpace.s, vertical = StenoSpace.xs),
            horizontalArrangement = Arrangement.End,
        ) {
            QuietAction(
                text = stringResource(R.string.onb_skip),
                onClick = onFinish,
                modifier = Modifier.alpha(if (last) 0f else 1f),
                enabled = !last,
                tag = OnboardingTags.SKIP,
            )
        }
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f).testTag(OnboardingTags.PAGER),
        ) { index -> IntroPageContent(pages[index], index) }
        CenteredContent {
            Column(
                modifier = Modifier
                    .padding(horizontal = layout.gutter)
                    .padding(top = StenoSpace.s, bottom = StenoSpace.s),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(StenoSpace.s),
            ) {
                PageDots(current = pager.currentPage, count = pages.size)
                PrimaryAction(
                    text = stringResource(if (last) R.string.onb_start else R.string.onb_next),
                    onClick = {
                        if (last) onFinish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    },
                    icon = if (last) null else R.drawable.ic_arrow_forward,
                    tag = if (last) OnboardingTags.START else OnboardingTags.NEXT,
                )
                GhostAction(
                    text = stringResource(R.string.common_btn_import),
                    onClick = {
                        state.finish()
                        onImport()
                    },
                    icon = R.drawable.ic_upload_file,
                    tag = Tags.IMPORT,
                )
            }
        }
    }
}

@Composable
private fun IntroPageContent(page: IntroPage, index: Int) {
    val layout = LocalWindowLayout.current
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    CenteredContent(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = layout.gutter, vertical = StenoSpace.s),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(StenoSpace.l, Alignment.CenterVertically),
        ) {
            MascotPlate(
                kind = page.mascot,
                plate = if (layout.short) StenoSize.mascotPlateSmall else StenoSize.mascotPlate,
                motion = EnterMotion.EMPTY,
            )
            IntroSketch(page)
            Text(
                text = stringResource(page.title),
                modifier = Modifier.testTag(OnboardingTags.page(index)).semantics { heading() },
                style = if (layout.short) type.sheetTitle else type.headline,
                color = colors.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(page.body),
                style = type.bodyLarge,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Точки страниц: текущая вытягивается в черту. Цвет дублируется шириной, TalkBack слышит номер. */
@Composable
private fun PageDots(current: Int, count: Int) {
    val description = stringResource(R.string.onb_page, current + 1, count)
    Row(
        modifier = Modifier
            .heightIn(min = StenoSpace.l)
            .testTag(OnboardingTags.DOTS)
            .clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val selected = index == current
            val width by animateDpAsState(
                if (selected) DotActiveWidth else DotSize,
                tween(StenoMotion.LONG),
                label = "dot_width",
            )
            val color by animateColorAsState(
                if (selected) StenoTheme.colors.primary else StenoTheme.colors.outlineVariant,
                tween(StenoMotion.LONG),
                label = "dot_color",
            )
            Box(Modifier.size(width = width, height = DotSize).background(color, CircleShape))
        }
    }
}
