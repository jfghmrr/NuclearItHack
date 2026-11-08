package ru.stenokot.core.contract

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenokot.core.model.Utterance
import ru.stenokot.core.storage.ServiceJson

class TranscriptEditorTest {
    private val validator = DefaultTranscriptValidator()
    private val machine = listOf(
        Utterance(0, 0, 0, 1_000, emptyList(), "Добрый день."),
        Utterance(1, 1, 1_000, 2_000, emptyList(), "Здравствуйте."),
        Utterance(2, 0, 2_000, 3_000, emptyList(), "Начнём."),
        Utterance(3, 2, 3_000, 4_000, emptyList(), "Да."),
    )

    private fun decode(bytes: ByteArray): TranscriptDocument =
        ContractJson.decodeFromString(TranscriptDocument.serializer(), bytes.toString(Charsets.UTF_8))

    @Test
    fun withoutEditsBytesAreMachineBytes() {
        val expected = TranscriptEncoder.encode(machine)
        assertArrayEquals(expected, TranscriptEditor.encode(machine, null))
        assertArrayEquals(expected, TranscriptEditor.encode(machine, EditsDocument(machine.size)))
        assertSame(machine, TranscriptEditor.apply(machine, EditsDocument(machine.size)))
    }

    @Test
    fun textEditRoundTripsThroughFileAndValidator() {
        val edits = TranscriptEditor.withText(machine, null, 1, "  Здравствуйте,\nколлеги.  ")!!
        val stored = ServiceJson.encodeToString(EditsDocument.serializer(), edits)
        val restored = ServiceJson.decodeFromString(EditsDocument.serializer(), stored)
        assertEquals(edits, restored)

        val bytes = TranscriptEditor.encode(machine, restored)
        assertEquals(ValidationResult.Ok, validator.validate(bytes, 4_000))
        val items = decode(bytes).transcript
        assertEquals("Здравствуйте, коллеги.", items[1].text)
        assertEquals(machine.map { it.startMs to it.endMs }, items.map { it.startMs to it.endMs })
        assertEquals(setOf(1), TranscriptEditor.editedIndices(machine, restored))
    }

    @Test
    fun speakerChangeThatEmptiesSpeakerRenumbersWithoutGaps() {
        val edits = TranscriptEditor.withSpeaker(machine, null, 1, 2)!!
        val bytes = TranscriptEditor.encode(machine, edits)
        assertEquals(ValidationResult.Ok, validator.validate(bytes, 4_000))
        assertEquals(
            listOf("speaker_1", "speaker_2", "speaker_1", "speaker_2"),
            decode(bytes).transcript.map { it.speakerId },
        )
    }

    @Test
    fun speakerChangeOfFirstUtteranceKeepsFirstAppearanceOrder() {
        val edits = TranscriptEditor.withSpeaker(machine, null, 0, 1)!!
        val ids = decode(TranscriptEditor.encode(machine, edits)).transcript.map { it.speakerId }
        assertEquals(listOf("speaker_1", "speaker_1", "speaker_2", "speaker_3"), ids)
    }

    @Test
    fun emptyTextIsRejected() {
        assertNull(TranscriptEditor.withText(machine, null, 0, "   \n "))
        val forced = EditsDocument(machine.size, listOf(UtteranceEdit(0, 0, text = "")))
        val verdict = validator.validate(TranscriptEditor.encode(machine, forced), 4_000)
        assertTrue(verdict is ValidationResult.Invalid)
    }

    @Test
    fun revertRestoresMachineBytes() {
        val text = TranscriptEditor.withText(machine, null, 2, "Начнём заседание.")!!
        val both = TranscriptEditor.withSpeaker(machine, text, 3, 0)!!
        val partly = TranscriptEditor.reverted(machine, both, 2)
        assertEquals(setOf(3), TranscriptEditor.editedIndices(machine, partly))
        val clean = TranscriptEditor.reverted(machine, partly, 3)
        assertTrue(clean.edits.isEmpty())
        assertArrayEquals(TranscriptEncoder.encode(machine), TranscriptEditor.encode(machine, clean))
    }

    @Test
    fun editBackToMachineValueDropsTheEdit() {
        val edited = TranscriptEditor.withText(machine, null, 0, "Привет.")!!
        val back = TranscriptEditor.withText(machine, edited, 0, "Добрый день.")!!
        assertTrue(back.edits.isEmpty())
    }

    @Test
    fun editsOfAnotherResultAreIgnored() {
        val stale = EditsDocument(machine.size + 1, listOf(UtteranceEdit(0, 0, text = "Чужое.")))
        assertArrayEquals(TranscriptEncoder.encode(machine), TranscriptEditor.encode(machine, stale))
        val moved = EditsDocument(machine.size, listOf(UtteranceEdit(1, 999, text = "Чужое.")))
        assertArrayEquals(TranscriptEncoder.encode(machine), TranscriptEditor.encode(machine, moved))
    }
}
