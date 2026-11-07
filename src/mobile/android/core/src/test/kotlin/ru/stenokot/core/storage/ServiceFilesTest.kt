package ru.stenokot.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenokot.core.model.AsrSegment
import ru.stenokot.core.model.AsrToken
import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.Language
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.model.SpeechSegment
import ru.stenokot.core.model.Stage

class ServiceFilesTest {
    private val job = JobFile(
        id = "a",
        sourceName = "запись.mp3",
        sourceExt = "mp3",
        stem = "запись",
        createdAtMs = 1,
        sizeBytes = 2,
        configHash = "c",
        modelsHash = "m",
    )

    @Test
    fun jobRoundTripsWithAllFields() {
        val full = job.withCompleted(Stage.IMPORT, 10).withCompleted(Stage.DECODE, 20).copy(
            currentStage = Stage.ASR,
            asrChunksDone = 3,
            asrChunksTotal = 9,
            language = Language.EN,
            errorCode = ErrorCode.FGS_TIMEOUT,
            llmStatus = LlmStatus.DEFERRED,
            llmRetryUsed = true,
        )
        val text = ServiceJson.encodeToString(JobFile.serializer(), full)
        assertTrue(text.contains("\"completed_stages\":[\"IMPORT\",\"DECODE\"]"))
        assertEquals(full, ServiceJson.decodeFromString(JobFile.serializer(), text))
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val text = ServiceJson.encodeToString(JobFile.serializer(), job).dropLast(1) + ""","new_field":1}"""
        assertEquals(job, ServiceJson.decodeFromString(JobFile.serializer(), text))
    }

    @Test
    fun nextStageFollowsPipelineOrder() {
        assertEquals(Stage.IMPORT, job.nextStage())
        val partial = job.withCompleted(Stage.IMPORT, 1).withCompleted(Stage.DECODE, 1).withCompleted(Stage.VAD, 1)
        assertEquals(Stage.LANG_ID, partial.nextStage())
        val all = Stage.entries.fold(job) { state, stage -> state.withCompleted(stage, 1) }
        assertNull(all.nextStage())
    }

    @Test
    fun repeatedCompletionStaysSingle() {
        val state = job.withCompleted(Stage.IMPORT, 1).withCompleted(Stage.IMPORT, 2)
        assertEquals(listOf(Stage.IMPORT), state.completedStages)
        assertEquals(2L, state.stageMs[Stage.IMPORT])
    }

    @Test
    fun changedModelsInvalidateStagesAfterDecode() {
        val state = job.withCompleted(Stage.IMPORT, 1).withCompleted(Stage.DECODE, 1).withCompleted(Stage.VAD, 1)
            .withCompleted(Stage.ASR, 1).copy(asrChunksDone = 5)
        assertEquals(state, state.invalidatedFor("c", "m"))
        val changed = state.invalidatedFor("c", "m2")
        assertEquals(listOf(Stage.IMPORT, Stage.DECODE), changed.completedStages)
        assertEquals(0, changed.asrChunksDone)
        assertEquals("m2", changed.modelsHash)
        assertFalse(Stage.ASR in changed.stageMs)
    }

    @Test
    fun asrLineRestoresSegment() {
        val segment = AsrSegment(
            SpeechSegment(4, 160, 320),
            "привет.",
            listOf(AsrToken(" при", 10), AsrToken("вет", 50), AsrToken(".", 90)),
        )
        val line = AsrJournalLine.of(segment)
        val text = encodeJournalLine(AsrJournalLine.serializer(), line)
        assertTrue(text.endsWith("\n"))
        assertEquals(1, text.count { it == '\n' })
        val back = decodeJournal(AsrJournalLine.serializer(), text).single()
        assertEquals(segment, back.toSegment())
    }

    @Test
    fun brokenTailIsDropped() {
        val serializer = AsrJournalLine.serializer()
        val good = (0 until 3).joinToString("") {
            encodeJournalLine(serializer, AsrJournalLine(it, 0, 1, "текст"))
        }
        val broken = encodeJournalLine(serializer, AsrJournalLine(3, 0, 1, "обрыв")).dropLast(9)
        assertEquals(3, decodeJournal(serializer, good + broken).size)
    }

    @Test
    fun breakInMiddleStopsReading() {
        val serializer = AsrJournalLine.serializer()
        val line = encodeJournalLine(serializer, AsrJournalLine(0, 0, 1, "текст"))
        assertEquals(1, decodeJournal(serializer, line + "{мусор\n" + line).size)
    }

    @Test
    fun diarLineRoundTrips() {
        val line = DiarJournalLine(
            1, 0, 300_000,
            listOf(DiarTurnRecord(0, 10, 20)),
            listOf(DiarClusterRecord(0, 10, listOf(0.1f, -0.2f)), DiarClusterRecord(1, 5, null)),
        )
        val back = decodeJournal(
            DiarJournalLine.serializer(),
            encodeJournalLine(DiarJournalLine.serializer(), line),
        ).single()
        assertEquals(line, back)
    }

    @Test
    fun metadataHasSpecFieldsAndNoText() {
        val document = MetadataDocument(
            appVersion = "1.0",
            commit = "abc",
            models = listOf(ModelHash("encoder", "00")),
            device = "emu",
            apiLevel = 36,
            totalMemBytes = 4,
            threads = 3,
            language = Language.RU,
            langVotes = LanguageVotes(5, 0, 3),
            stages = listOf(StageTiming(Stage.ASR, 100, 0.1)),
            rtfCore = 0.4,
            rtfFull = 0.5,
            vmHwmKb = 900,
            thermal = listOf(ThermalEvent(1, 0)),
            llm = LlmMetadata(LlmStatus.APPLIED, "qwen", 10, 20, 1, 2, 3),
            warnings = listOf("w"),
        )
        val text = ServiceJson.encodeToString(MetadataDocument.serializer(), document)
        listOf("rtf_core", "rtf_full", "vm_hwm_kb", "lang_votes", "prompt_tokens", "api_level", "total_mem_bytes").forEach {
            assertTrue(it, text.contains("\"$it\""))
        }
        assertEquals(document, ServiceJson.decodeFromString(MetadataDocument.serializer(), text))
    }
}
