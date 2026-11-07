package ru.stenokot.app.debug

import java.time.LocalDateTime
import java.time.ZoneId
import ru.stenokot.app.domain.AudioFileUi
import ru.stenokot.app.domain.JobSummaryUi
import ru.stenokot.app.domain.ModelItemUi
import ru.stenokot.app.domain.ModelReadiness
import ru.stenokot.app.domain.ModelsUi
import ru.stenokot.app.domain.StageMetricUi
import ru.stenokot.app.domain.VerificationUi
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.model.PreflightInfo
import ru.stenokot.core.model.Stage
import ru.stenokot.core.model.Utterance
import ru.stenokot.core.protocol.ProtocolDocument
import ru.stenokot.core.protocol.ProtocolItem
import ru.stenokot.core.protocol.ProtocolSection

/** Демо-наборы. Написаны вручную для проверки экранов и в релизную сборку не входят. */
internal object DemoData {
    const val FILE_DS1 = "podcast_gorodskie_sady_ep12.mp3"
    const val FILE_DS2 = "zasedanie_kotov_2026-11-05.mp3"
    const val FILE_DS3 = "podcast_gorodskie_sady_marafon.mp3"
    const val FILE_DS4 =
        "Заседание котов - квартальный бюджет на корм, когтеточки и график дежурств, итоговая запись от 5 ноября 2026.mp3"
    const val FILE_DS5 = "garden_talk_ep03.mp3"
    const val FILE_DS6 = "tishina_v_kabinete.mp3"
    const val FILE_DS8 = "podcast_gorodskie_sady_ep11.mp3"

    const val DURATION_DS1 = 768_000L
    const val DURATION_DS2 = 1_450_000L
    const val DURATION_DS3 = 10_116_000L
    const val DURATION_DS4 = 2_282_000L
    const val DURATION_DS5 = 380_000L
    const val DURATION_DS6 = 45_000L
    const val DURATION_DS8 = 1_120_000L

    private const val FREE_BYTES = 12_400_000_000L

    /** Мебибайты в байты. */
    fun mib(value: Double): Long = (value * 1_048_576).toLong()

    /** Момент времени в поясе устройства, чтобы дата на экране совпадала с брифом. */
    fun at(month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, month, day, hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val modelsReady = ModelsUi(ModelReadiness.READY, mib(378.0), mib(378.0), FREE_BYTES, modelItems(true))
    val modelsCopying = ModelsUi(ModelReadiness.COPYING, mib(214.0), mib(378.0), FREE_BYTES, modelItems(false))
    val modelsNoLlm = ModelsUi(ModelReadiness.LLM_UNAVAILABLE, 0, mib(378.0), FREE_BYTES, modelItems(false))
    val modelsNoAsr = ModelsUi(ModelReadiness.ASR_UNAVAILABLE, 0, mib(378.0), FREE_BYTES, modelItems(true))
    val modelsNoSpace = ModelsUi(ModelReadiness.STORAGE_LOW, 0, mib(378.0), mib(120.0), modelItems(false))

    private fun modelItems(llmReady: Boolean) = listOf(
        ModelItemUi("GigaAM v3 e2e RNNT int8", "Русская речь", mib(221.0), "demo-hash-01", "MIT", true),
        ModelItemUi("Parakeet TDT 110M int8", "Английская речь", mib(130.0), "demo-hash-02", "CC-BY-4.0", true),
        ModelItemUi("Silero VAD", "Поиск речи", mib(0.6), "demo-hash-03", "MIT", true),
        ModelItemUi("pyannote-segmentation-3-0 int8", "Границы говорящих", mib(1.5), "demo-hash-04", "MIT", true),
        ModelItemUi("TitaNet-small fp32", "Голоса говорящих", mib(38.0), "demo-hash-05", "Apache-2.0", true),
        ModelItemUi("Qwen3-0.6B Q4_K_M", "Оглавление и протокол", mib(378.0), "demo-hash-06", "Apache-2.0", llmReady),
    )

    val audioDs1 = AudioFileUi("demo://ds1", FILE_DS1, DURATION_DS1, mib(11.7), "Download")
    val audioDs2 = AudioFileUi("demo://ds2", FILE_DS2, DURATION_DS2, mib(22.1), "Download")
    val audioDs4 = AudioFileUi("demo://ds4", FILE_DS4, DURATION_DS4, mib(34.8), "Download")
    val audioDs5 = AudioFileUi("demo://ds5", FILE_DS5, DURATION_DS5, mib(5.8), "Music")

    val preflightDs1 = PreflightInfo(
        fileName = FILE_DS1,
        sizeBytes = mib(11.7),
        durationMs = DURATION_DS1,
        mime = "audio/mpeg",
        freeBytes = 1_800_000_000L,
        requiredBytes = mib(240.0),
        estimateMinMs = 240_000L,
        estimateMaxMs = 360_000L,
        estimateMeasured = true,
    )

    val preflightDs4Rough = PreflightInfo(
        fileName = FILE_DS4,
        sizeBytes = mib(34.8),
        durationMs = DURATION_DS4,
        mime = "audio/mpeg",
        freeBytes = FREE_BYTES,
        requiredBytes = mib(700.0),
        estimateMinMs = 900_000L,
        estimateMaxMs = 1_500_000L,
    )

    fun summary(
        id: String,
        title: String,
        createdAtMs: Long,
        durationMs: Long?,
        speakers: Int?,
        state: JobState,
        queuePosition: Int? = null,
    ) = JobSummaryUi(id, title, createdAtMs, durationMs, speakers, state, queuePosition)

    val historyDs8 = summary("ds8", FILE_DS8, at(11, 4, 19, 12), DURATION_DS8, 2, JobState.Done(LlmStatus.APPLIED, emptyList()))
    val historyDs3 = summary("ds3", FILE_DS3, at(11, 2, 11, 40), DURATION_DS3, 3, JobState.Done(LlmStatus.PARTIAL, emptyList()))

    private fun utt(id: Int, speaker: Int, startMs: Long, endMs: Long, text: String, overlap: Boolean = false) =
        Utterance(id, speaker - 1, startMs, endMs, emptyList(), text, overlap)

    private val ds1Base = listOf(
        utt(1, 1, 4_120, 9_480, "Всем привет, это подкаст «Городские сады», и сегодня мы говорим про огороды на крышах."),
        utt(2, 2, 9_900, 15_260, "Привет. Ну, э-э, крыша - это не самое простое место, там ветер и солнце весь день."),
        utt(3, 3, 15_700, 18_340, "А что лучше всего приживается?"),
        utt(4, 2, 18_600, 23_950, "Шпинат, салат, редис. Помидоры тоже можно, но им нужна опора."),
        utt(5, 3, 24_300, 26_100, "Понял, записываю."),
        utt(6, 1, 26_700, 32_480, "Значит, следующий выпуск посвятим поливу, решено."),
        utt(7, 3, 32_900, 38_250, "Хорошо, я соберу вопросы слушателей до пятницы."),
        utt(8, 1, 38_800, 44_160, "Теперь про почву. Обычная земля из магазина на крыше подойдёт?"),
        utt(9, 2, 44_500, 49_720, "Она тяжёлая, лучше лёгкий субстрат, иначе нагрузка на кровлю слишком большая."),
        utt(10, 3, 50_100, 54_600, "А кто вообще разрешает такие посадки? И, э-э, сколько это стоит?"),
        utt(11, 2, 55_000, 58_300, "Зависит от дома, надо уточнять."),
        utt(12, 1, 59_020, 65_400, "Хорошо, вернёмся к этому в конце выпуска."),
    )

    private const val LONG_TEXT = "Смотрите, я, э-э, подробно расскажу, как мы считали нагрузку. Квадратный метр мокрого " +
        "субстрата весит примерно сто двадцать килограммов, а если добавить ящики и воду, то получается уже сто " +
        "пятьдесят. Поэтому, ну, сначала надо посмотреть документы на дом, потом позвать инженера, и только после " +
        "этого покупать ящики. Если ничего не считать, можно испортить гидроизоляцию, и тогда ремонт обойдётся дороже " +
        "всего огорода. Я предлагаю, во-первых, начать с двух ящиков, во-вторых, ставить их ближе к несущим стенам и, " +
        "в-третьих, не забывать про слив."

    /** Дополняет набор строками из его же реплик до нужного числа. Нужно только для объёма списка. */
    private fun extended(
        base: List<Utterance>,
        total: Int,
        durationMs: Long,
        insert: Utterance? = null,
    ): List<Utterance> {
        val out = base.toMutableList()
        val fillers = total - base.size - if (insert != null) 1 else 0
        val reserved = insert?.let { it.endMs - it.startMs } ?: 0L
        val step = (durationMs - base.last().endMs - reserved) / fillers.coerceAtLeast(1)
        var time = base.last().endMs + 300
        var pending = insert
        repeat(fillers) { index ->
            pending?.let { long ->
                if (time >= long.startMs) {
                    out += long.copy(id = out.size + 1)
                    time = long.endMs + 300
                    pending = null
                }
            }
            val source = base[index % base.size]
            val length = minOf(source.endMs - source.startMs, step - 200)
            out += source.copy(id = out.size + 1, startMs = time, endMs = time + length)
            time += step
        }
        return out
    }

    val utterancesDs1: List<Utterance> by lazy {
        extended(ds1Base, 214, DURATION_DS1, utt(0, 2, 192_000, 221_000, LONG_TEXT))
    }

    val utterancesDs2: List<Utterance> by lazy {
        extended(
            listOf(
                utt(1, 1, 8_000, 11_000, "Все на месте? Начинаем общее собрание."),
                utt(2, 2, 11_000, 13_000, "Я здесь."),
                utt(3, 3, 13_000, 15_000, "Тоже здесь."),
                utt(4, 4, 15_000, 18_000, "А почему в зале так холодно?"),
                utt(5, 5, 18_000, 20_000, "Окно открыто."),
                utt(6, 6, 20_000, 23_000, "Сейчас закрою, мне ближе."),
                utt(7, 7, 23_000, 25_000, "Можно мне слово?"),
                utt(8, 8, 25_000, 28_000, "Мур, сначала повестка."),
            ),
            486,
            DURATION_DS2,
        )
    }

    val utterancesDs3: List<Utterance> by lazy {
        val tail = listOf(
            utt(0, 2, 5_047_000, 5_053_000, "Вернёмся к вопросу про компост. Это у нас уже второй час разговора."),
            utt(0, 1, 5_053_000, 5_058_000, "Да, и слушатели спрашивают, можно ли держать его на балконе."),
            utt(0, 3, 5_058_000, 5_067_000, "Можно, если закрытый контейнер и есть вентиляция. Запаха почти не будет."),
            utt(0, 2, 5_067_000, 5_071_000, "Почти - ключевое слово."),
        )
        val all = extended(ds1Base, 2_418 - tail.size, DURATION_DS3)
        val split = all.indexOfFirst { it.startMs >= 5_047_000 }
        val before = all.take(split).filter { it.endMs < 5_047_000 }
        val after = all.drop(split).filter { it.startMs > 5_071_000 }
        (before + tail + after).mapIndexed { index, item -> item.copy(id = index + 1) }
    }

    val utterancesDs5: List<Utterance> by lazy {
        extended(
            listOf(
                utt(1, 1, 3_000, 8_000, "Hello and welcome back to Garden Talk."),
                utt(2, 2, 8_000, 14_000, "Thanks. Today we are talking about tomatoes on a balcony."),
                utt(3, 1, 14_000, 18_000, "How much sun do they really need?"),
                utt(4, 2, 18_000, 26_000, "At least six hours a day, and, um, a big pot helps a lot."),
            ),
            96,
            DURATION_DS5,
        )
    }

    val protocolDs1 = ProtocolDocument(
        sections = listOf(
            ProtocolSection(
                title = "Огороды на крышах",
                startMs = 4_120,
                endMs = 38_250,
                firstUtterance = 1,
                items = listOf(
                    ProtocolItem("decision", 6, "speaker_1", 26_700, "Значит, следующий выпуск посвятим поливу, решено."),
                    ProtocolItem("action", 7, "speaker_3", 32_900, "Хорошо, я соберу вопросы слушателей до пятницы."),
                ),
            ),
            ProtocolSection(
                title = "Почва и нагрузка на кровлю",
                startMs = 38_800,
                endMs = 65_400,
                firstUtterance = 8,
                items = listOf(
                    ProtocolItem(
                        "question",
                        10,
                        "speaker_3",
                        50_100,
                        "А кто вообще разрешает такие посадки? И, э-э, сколько это стоит?",
                    ),
                ),
            ),
            ProtocolSection("Полив и ветер", 65_400, 400_000, 13, emptyList()),
            ProtocolSection("Вопросы слушателей", 400_000, DURATION_DS1, 120, emptyList()),
        ),
        coveredMs = DURATION_DS1,
        totalMs = DURATION_DS1,
    )

    val protocolDs1Partial = ProtocolDocument(
        sections = listOf(
            ProtocolSection(
                title = "Огороды на крышах",
                startMs = 4_120,
                endMs = 38_250,
                firstUtterance = 1,
                items = listOf(
                    ProtocolItem("decision", 6, "speaker_1", 26_700, "Значит, следующий выпуск посвятим поливу, решено."),
                ),
            ),
            ProtocolSection("Фрагмент 06:20 - 09:30", 380_000, 570_000, 110, emptyList()),
        ),
        coveredMs = 380_000,
        totalMs = DURATION_DS1,
    )

    val protocolDs8 = ProtocolDocument(
        sections = listOf(
            ProtocolSection("Балконные ящики", 5_000, 190_000, 1, emptyList()),
            ProtocolSection("Свет и тень", 190_000, 380_000, 54, emptyList()),
            ProtocolSection("Рассада весной", 380_000, 585_000, 108, emptyList()),
            ProtocolSection("Вредители без химии", 585_000, 785_000, 165, emptyList()),
            ProtocolSection("Сбор урожая", 785_000, 970_000, 222, emptyList()),
            ProtocolSection("Вопросы слушателей", 970_000, DURATION_DS8, 275, emptyList()),
        ),
        coveredMs = DURATION_DS8,
        totalMs = DURATION_DS8,
    )

    val verificationEmpty = VerificationUi(
        internetPermissionAbsent = true,
        networkStatePermissionAbsent = true,
        appVersion = "1.0.0",
        models = modelItems(true),
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
    )

    val verificationCopying = verificationEmpty.copy(models = modelItems(false))

    val verificationMetrics = verificationEmpty.copy(
        lastJobTitle = FILE_DS1,
        audioDurationMs = DURATION_DS1,
        processingMs = 322_000,
        rtfCore = 0.42,
        rtfFull = 0.51,
        peakRssMb = 1_229,
        stageMetrics = listOf(
            StageMetricUi(Stage.IMPORT, 1_000, null),
            StageMetricUi(Stage.DECODE, 6_000, null),
            StageMetricUi(Stage.VAD, 8_000, null),
            StageMetricUi(Stage.LANG_ID, 5_000, null),
            StageMetricUi(Stage.ASR, 232_000, 0.30),
            StageMetricUi(Stage.DIARIZE, 68_000, 0.09),
            StageMetricUi(Stage.MERGE, 1_000, null),
            StageMetricUi(Stage.VALIDATE, 1_000, null),
        ),
        jsonValid = true,
        jsonSha256 = "demo-not-a-real-hash",
        jsonPath = "Download/Stenokot/podcast_gorodskie_sady_ep12.json",
        llmTokens = 4_200,
    )
}
