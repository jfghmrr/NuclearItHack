package ru.stenokot.app.ui.licenses

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import ru.stenokot.app.R

/** Запись перечня: что входит в приложение, для чего, под какой лицензией и где лежит её текст. */
@Immutable
data class LicenseEntry(
    val id: String,
    val name: String,
    val role: String,
    val license: String,
    val holder: String,
    val file: String,
)

/** Группа записей. */
@Immutable
data class LicenseGroup(@StringRes val title: Int, val entries: List<LicenseEntry>)

private const val APACHE = "Apache-2.0"
private const val APACHE_FILE = "Apache-2.0.txt"
private const val MIT = "MIT"
private const val OFL = "SIL OFL 1.1"

/**
 * Состав приложения. Лицензии моделей совпадают с `docs/MODEL_MANIFEST.md`, лицензии библиотек
 * с их опубликованными описаниями. Тексты лежат в `assets/licenses`.
 */
object LicenseCatalog {
    /** Группы в порядке показа. */
    val groups = listOf(
        LicenseGroup(
            R.string.licenses_group_fonts,
            listOf(
                LicenseEntry("noto", "Noto Sans", "Шрифт интерфейса", OFL, "The Noto Project Authors", "OFL-NotoSans.txt"),
            ),
        ),
        LicenseGroup(
            R.string.licenses_group_models,
            listOf(
                LicenseEntry("gigaam", "GigaAM v3 e2e RNNT int8", "Русская речь", MIT, "GigaChat Team", "MIT-GigaAM.txt"),
                LicenseEntry(
                    "parakeet",
                    "Parakeet TDT 0.6B v2 int8",
                    "Английская речь",
                    "веса CC-BY-4.0, код Apache-2.0",
                    "NVIDIA",
                    "CC-BY-4.0.txt",
                ),
                LicenseEntry("silero", "Silero VAD", "Поиск речи", MIT, "Silero Team", "MIT-SileroVAD.txt"),
                LicenseEntry("pyannote", "pyannote segmentation 3.0 int8", "Границы говорящих", MIT, "CNRS", "MIT-pyannote.txt"),
                LicenseEntry("titanet", "TitaNet small", "Голоса говорящих", "$APACHE по карточке модели", "NVIDIA", APACHE_FILE),
                LicenseEntry("qwen", "Qwen3-0.6B Q4_K_M", "Оглавление и протокол", APACHE, "Qwen", APACHE_FILE),
            ),
        ),
        LicenseGroup(
            R.string.licenses_group_libraries,
            listOf(
                LicenseEntry("sherpa", "sherpa-onnx", "Распознавание речи и говорящие", APACHE, "k2-fsa", APACHE_FILE),
                LicenseEntry("onnxruntime", "ONNX Runtime", "Запуск моделей речи", MIT, "Microsoft Corporation", "MIT-OnnxRuntime.txt"),
                LicenseEntry("llama", "llama.cpp", "Запуск языковой модели", MIT, "The ggml authors", "MIT-llama.cpp.txt"),
                LicenseEntry("androidx", "AndroidX и Jetpack Compose", "Интерфейс и системные компоненты", APACHE, "The Android Open Source Project", APACHE_FILE),
                LicenseEntry("media3", "AndroidX Media3", "Воспроизведение записи", APACHE, "The Android Open Source Project", APACHE_FILE),
                LicenseEntry("kotlin", "Kotlin, kotlinx.coroutines, kotlinx.serialization", "Язык и его библиотеки", APACHE, "JetBrains", APACHE_FILE),
            ),
        ),
    )

    /** Запись по номеру или null, если такой нет. */
    fun entry(id: String): LicenseEntry? = groups.firstNotNullOfOrNull { group -> group.entries.firstOrNull { it.id == id } }
}

private val RuleLine = Regex("^[-=_*]{5,}$")

private fun isCapitalized(line: String): Boolean = line.any(Char::isLetter) && line == line.uppercase()

/** Заголовок раздела в тексте лицензии: короткая строка заглавными буквами. */
fun isLicenseHeading(paragraph: String): Boolean = paragraph.length < 60 && isCapitalized(paragraph)

/**
 * Делит текст лицензии на абзацы. В файлах строки разорваны под ширину 70 знаков, а на экране
 * ширина другая, поэтому строки абзаца склеиваются обратно. Линии из тире отбрасываются,
 * а заголовок, стоящий прямо над абзацем, остаётся отдельной строкой.
 */
fun licenseParagraphs(text: String): List<String> =
    text.replace("\r\n", "\n")
        .split(Regex("\n[ \t]*\n"))
        .flatMap { block ->
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() && !RuleLine.matches(it) }
            val head = lines.firstOrNull()?.takeIf { lines.size > 1 && isLicenseHeading(it) }
            listOfNotNull(head, lines.drop(if (head != null) 1 else 0).joinToString(" ").ifEmpty { null })
        }
