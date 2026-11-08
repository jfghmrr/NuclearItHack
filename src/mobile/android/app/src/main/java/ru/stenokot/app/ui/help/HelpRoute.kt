package ru.stenokot.app.ui.help

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import ru.stenokot.app.R
import ru.stenokot.app.ui.AppActions
import ru.stenokot.app.ui.components.LinkRow
import ru.stenokot.app.ui.components.ListBlock
import ru.stenokot.app.ui.components.Mascot
import ru.stenokot.app.ui.components.MascotKind
import ru.stenokot.app.ui.components.SectionHeader
import ru.stenokot.app.ui.components.StenoIcon
import ru.stenokot.app.ui.theme.StenoMotion
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme
import ru.stenokot.app.ui.verification.ServicePage

/** Идентификаторы элементов «Помощи» для автоматики. */
object HelpTags {
    /** Строка «Помощь» в настройках. */
    const val OPEN = "help_open"

    /** Вводный абзац. */
    const val LEAD = "help_lead"

    /** Строка повторного показа знакомства. */
    const val REPLAY_INTRO = "help_replay_intro"

    /** Заголовок раздела [key]. */
    fun section(key: String): String = "help_section_$key"

    /** Вопрос [key] в частых вопросах. */
    fun question(key: String): String = "help_faq_$key"

    /** Ответ на вопрос [key]. */
    fun answer(key: String): String = "help_faq_${key}_answer"
}

/** Пункт раздела: значок, заголовок и короткий абзац. */
private class Topic(@DrawableRes val icon: Int, @StringRes val title: Int, val body: String)

/** Частый вопрос: [key] входит в идентификатор строки. */
private class Question(val key: String, @StringRes val question: Int, val answer: String)

/**
 * «Помощь»: как начать, что происходит с файлом, что видно на результате, частые вопросы и советы для
 * записи. Названия кнопок и вкладок берутся из тех же строк, что и на экранах, поэтому текст не
 * расходится с интерфейсом.
 */
@Composable
fun HelpRoute(actions: AppActions, onReplayIntro: () -> Unit) {
    val colors = StenoTheme.colors
    val importLabel = stringResource(R.string.common_btn_import)
    val recordLabel = stringResource(R.string.common_btn_record)
    val transcriptTab = stringResource(R.string.common_tab_transcript)
    val protocolTab = stringResource(R.string.common_tab_protocol)
    val editText = stringResource(R.string.scr04_edit_text)
    val editSpeaker = stringResource(R.string.scr04_edit_speaker)
    val editRevert = stringResource(R.string.scr04_edit_revert)
    val exportLabel = stringResource(R.string.scr04_btn_export)

    ServicePage(title = stringResource(R.string.help_title), onBack = actions.back) {
        Mascot(MascotKind.HELP, size = StenoSize.mascotPlateSmall)
        Text(
            text = stringResource(R.string.help_lead),
            modifier = Modifier.testTag(HelpTags.LEAD),
            style = StenoTheme.type.bodyLarge,
            color = colors.onSurfaceVariant,
        )
        TopicSection(
            key = "start",
            title = R.string.help_section_start,
            topics = listOf(
                Topic(R.drawable.ic_upload_file, R.string.help_start_import_title, stringResource(R.string.help_start_import_body, importLabel)),
                Topic(R.drawable.ic_mic, R.string.help_start_record_title, stringResource(R.string.help_start_record_body, recordLabel)),
            ),
        )
        TopicSection(
            key = "file",
            title = R.string.help_section_file,
            topics = listOf(
                Topic(R.drawable.ic_check_circle, R.string.help_file_check_title, stringResource(R.string.help_file_check_body)),
                Topic(R.drawable.ic_hourglass_top, R.string.help_file_stages_title, stringResource(R.string.help_file_stages_body)),
                Topic(R.drawable.ic_wifi_off, R.string.help_file_offline_title, stringResource(R.string.help_file_offline_body)),
            ),
        )
        TopicSection(
            key = "result",
            title = R.string.help_section_result,
            topics = listOf(
                Topic(R.drawable.ic_description, R.string.help_result_transcript_title, stringResource(R.string.help_result_transcript_body, transcriptTab)),
                Topic(R.drawable.ic_volume, R.string.help_result_player_title, stringResource(R.string.help_result_player_body)),
                Topic(
                    R.drawable.ic_assignment_ind,
                    R.string.help_result_edit_title,
                    stringResource(R.string.help_result_edit_body, editText, editSpeaker, editRevert),
                ),
                Topic(R.drawable.ic_toc, R.string.help_result_protocol_title, stringResource(R.string.help_result_protocol_body, protocolTab)),
                Topic(R.drawable.ic_download, R.string.help_result_export_title, stringResource(R.string.help_result_export_body, exportLabel)),
            ),
        )
        FaqSection(
            listOf(
                Question("formats", R.string.help_faq_formats_q, stringResource(R.string.help_faq_formats_a)),
                Question("time", R.string.help_faq_time_q, stringResource(R.string.help_faq_time_a)),
                Question("speakers", R.string.help_faq_speakers_q, stringResource(R.string.help_faq_speakers_a, editSpeaker)),
                Question("language", R.string.help_faq_language_q, stringResource(R.string.help_faq_language_a)),
                Question("error", R.string.help_faq_error_q, stringResource(R.string.help_faq_error_a)),
                Question("files", R.string.help_faq_files_q, stringResource(R.string.help_faq_files_a)),
                Question("private", R.string.help_faq_private_q, stringResource(R.string.help_faq_private_a)),
                Question("background", R.string.help_faq_background_q, stringResource(R.string.help_faq_background_a)),
            ),
        )
        TopicSection(
            key = "tips",
            title = R.string.help_section_tips,
            topics = listOf(
                Topic(R.drawable.ic_mic, R.string.help_tip_distance_title, stringResource(R.string.help_tip_distance_body)),
                Topic(R.drawable.ic_music_off, R.string.help_tip_noise_title, stringResource(R.string.help_tip_noise_body)),
                Topic(R.drawable.ic_overlap, R.string.help_tip_turns_title, stringResource(R.string.help_tip_turns_body)),
            ),
        )
        ListBlock(
            modifier = Modifier.padding(top = StenoSpace.s),
            rows = listOf(
                {
                    LinkRow(
                        title = stringResource(R.string.help_replay_intro),
                        subtitle = stringResource(R.string.help_replay_intro_hint),
                        icon = R.drawable.ic_spark,
                        onClick = onReplayIntro,
                        tag = HelpTags.REPLAY_INTRO,
                    )
                },
            ),
        )
    }
}

@Composable
private fun TopicSection(key: String, @StringRes title: Int, topics: List<Topic>) {
    Column {
        SectionHeader(stringResource(title), tag = HelpTags.section(key))
        ListBlock(rows = topics.map { topic -> { TopicRow(topic) } })
    }
}

/** Пункт раздела читается TalkBack целиком: заголовок и абзац вместе. */
@Composable
private fun TopicRow(topic: Topic) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = StenoSize.touchTarget)
            .semantics(mergeDescendants = true) { }
            .padding(vertical = StenoSpace.m),
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.m),
    ) {
        StenoIcon(topic.icon, colors.primary, size = StenoSize.icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StenoSpace.xs)) {
            Text(stringResource(topic.title), style = type.cardTitle, color = colors.onSurface)
            Text(topic.body, style = type.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun FaqSection(questions: List<Question>) {
    Column {
        SectionHeader(stringResource(R.string.help_section_faq), tag = HelpTags.section("faq"))
        ListBlock(rows = questions.map { question -> { FaqRow(question) } })
    }
}

/**
 * Сворачиваемый вопрос. Заголовок это одна кнопка не ниже 48 dp, состояние «развёрнуто» слышно в
 * TalkBack. Ответ раскрывается одним коротким движением.
 */
@Composable
private fun FaqRow(question: Question) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    var open by rememberSaveable(question.key) { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(StenoMotion.MEDIUM), label = "faq_chevron")
    val state = stringResource(if (open) R.string.help_faq_open else R.string.help_faq_closed)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = StenoSize.touchTarget)
                .testTag(HelpTags.question(question.key))
                .clickable(role = Role.Button) { open = !open }
                .semantics { stateDescription = state }
                .padding(vertical = StenoSpace.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StenoSpace.m),
        ) {
            Text(stringResource(question.question), Modifier.weight(1f), style = type.cardTitle, color = colors.onSurface)
            StenoIcon(
                R.drawable.ic_expand_more,
                colors.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { rotationZ = turn },
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(tween(StenoMotion.LONG)) + fadeIn(tween(StenoMotion.LONG)),
            exit = shrinkVertically(tween(StenoMotion.MEDIUM)) + fadeOut(tween(StenoMotion.SHORT)),
        ) {
            Text(
                text = question.answer,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(HelpTags.answer(question.key))
                    .padding(bottom = StenoSpace.m),
                style = type.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}
