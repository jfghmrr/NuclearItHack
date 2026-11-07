package ru.stenokot.core.contract

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenokot.core.model.Utterance

class TranscriptValidatorTest {
    private val validator = DefaultTranscriptValidator()
    private val sample =
        """{"transcript":[{"speaker_id":"speaker_1","start_ms":1250,"end_ms":4380,"text":"Добрый день!"}]}"""

    private fun check(json: String, durationMs: Long = 10_000): ValidationResult =
        validator.validate(json.toByteArray(Charsets.UTF_8), durationMs)

    private fun assertInvalid(json: String, durationMs: Long = 10_000) {
        assertTrue(json, check(json, durationMs) is ValidationResult.Invalid)
    }

    private fun item(speaker: String = "speaker_1", start: String = "0", end: String = "1000", text: String = "\"да\"") =
        """{"speaker_id":"$speaker","start_ms":$start,"end_ms":$end,"text":$text}"""

    private fun doc(vararg items: String) = """{"transcript":[${items.joinToString(",")}]}"""

    @Test
    fun taskSamplePasses() {
        assertEquals(ValidationResult.Ok, check(sample))
    }

    @Test
    fun emptyArrayPasses() {
        assertEquals(ValidationResult.Ok, check("""{"transcript":[]}""", 0))
    }

    @Test
    fun whitespaceBetweenTokensPasses() {
        assertEquals(ValidationResult.Ok, check("{\n  \"transcript\": [ ${item()} ]\n}\n"))
    }

    @Test
    fun bomIsRejected() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + sample.toByteArray()
        assertTrue(validator.validate(bytes, 10_000) is ValidationResult.Invalid)
    }

    @Test
    fun brokenUtf8IsRejected() {
        val bytes = """{"transcript":[${item(text = "\"X\"")}]}""".toByteArray()
        val index = bytes.indexOf('X'.code.toByte())
        bytes[index] = 0xC3.toByte()
        assertTrue(validator.validate(bytes, 10_000) is ValidationResult.Invalid)
    }

    @Test
    fun rootShapeIsExact() {
        assertInvalid("[]")
        assertInvalid("""{"transcript":{}}""")
        assertInvalid("""{"transcript":[],"extra":1}""")
        assertInvalid("""{"extra":1,"transcript":[]}""")
        assertInvalid("""{"transcript":[],"transcript":[]}""")
        assertInvalid("""{"Transcript":[]}""")
        assertInvalid("""{}""")
        assertInvalid("")
        assertInvalid("""{"transcript":[]} x""")
        assertInvalid("""{"transcript":[],}""")
        assertInvalid(doc(item(), ""))
    }

    @Test
    fun itemKeysAreExact() {
        assertInvalid(doc("""{"speaker_id":"speaker_1","start_ms":0,"end_ms":1,"text":"да","x":1}"""))
        assertInvalid(doc("""{"speaker_id":"speaker_1","start_ms":0,"end_ms":1}"""))
        assertInvalid(doc("""{"speaker_id":"speaker_1","start_ms":0,"start_ms":0,"end_ms":1,"text":"да"}"""))
        assertInvalid(doc("""{"speaker_id":"speaker_1","start_ms":0,"end_ms":1,"Text":"да"}"""))
        assertInvalid(doc("42"))
    }

    @Test
    fun integersHaveNoFractionOrExponent() {
        assertInvalid(doc(item(start = "0.0")))
        assertInvalid(doc(item(end = "1e3")))
        assertInvalid(doc(item(end = "1E3")))
        assertInvalid(doc(item(end = "1000.5")))
        assertInvalid(doc(item(end = "\"1000\"")))
        assertInvalid(doc(item(end = "null")))
        assertInvalid(doc(item(end = "true")))
        assertInvalid(doc(item(start = "-0")))
        assertInvalid(doc(item(start = "01")))
        assertInvalid(doc(item(end = "99999999999999999999")))
    }

    @Test
    fun rangesAreChecked() {
        assertInvalid(doc(item(start = "-1")))
        assertInvalid(doc(item(start = "1000", end = "1000")))
        assertInvalid(doc(item(start = "2000", end = "1000")))
        assertEquals(ValidationResult.Ok, check(doc(item(end = "10050"))))
        assertInvalid(doc(item(end = "10051")))
    }

    @Test
    fun sortOrderIsChecked() {
        assertInvalid(doc(item(start = "500", end = "900"), item(start = "100", end = "900")))
        assertEquals(
            ValidationResult.Ok,
            check(doc(item(start = "100", end = "2000"), item(speaker = "speaker_2", start = "100", end = "900"))),
        )
    }

    @Test
    fun overlapsAreAllowed() {
        assertEquals(
            ValidationResult.Ok,
            check(doc(item(start = "0", end = "2000"), item(speaker = "speaker_2", start = "1000", end = "3000"))),
        )
    }

    @Test
    fun speakerPatternAndContiguousNumbering() {
        assertInvalid(doc(item(speaker = "speaker_0")))
        assertInvalid(doc(item(speaker = "speaker_01")))
        assertInvalid(doc(item(speaker = "Speaker_1")))
        assertInvalid(doc(item(speaker = "speaker_1 ")))
        assertInvalid(doc(item(speaker = "speaker_2")))
        assertInvalid(doc(item(speaker = "speaker_1"), item(speaker = "speaker_3")))
        assertEquals(
            ValidationResult.Ok,
            check(
                doc(
                    item(speaker = "speaker_1"),
                    item(speaker = "speaker_2"),
                    item(speaker = "speaker_1"),
                    item(speaker = "speaker_3"),
                ),
            ),
        )
    }

    @Test
    fun textIsNonEmptyWithoutControlCharacters() {
        assertInvalid(doc(item(text = "\"\"")))
        assertInvalid(doc(item(text = "\"   \"")))
        assertInvalid(doc(item(text = "\"а\\nб\"")))
        assertInvalid(doc(item(text = "\"а\\u0007б\"")))
        assertInvalid(doc(item(text = "\"а\u0085б\"")))
        assertInvalid(doc(item(text = "\"а\nб\"")))
        assertInvalid(doc(item(text = "\"\\ud800\"")))
        assertEquals(ValidationResult.Ok, check(doc(item(text = "\"\\u0434\\u0430 \\\"ок\\\" \uD83D\uDE00\""))))
    }

    @Test
    fun encoderKeepsCyrillicAndFieldsOnly() {
        val bytes = TranscriptEncoder.encode(
            listOf(
                Utterance(1, 0, 1250, 4380, emptyList(), "Добрый день!", overlap = true),
                Utterance(2, 1, 4400, 5000, emptyList(), "Здравствуйте, \"коллеги\".", overlap = false),
            ),
        )
        val text = String(bytes, Charsets.UTF_8)
        assertFalse(text.contains("\\u"))
        assertFalse(text.contains("overlap"))
        assertFalse(text.contains('\n'))
        assertEquals(0x7B, bytes[0].toInt())
        assertEquals(ValidationResult.Ok, validator.validate(bytes, 10_000))
    }

    @Test
    fun encoderReproducesTaskSampleByteForByte() {
        val bytes = TranscriptEncoder.encode(
            listOf(Utterance(1, 0, 1250, 4380, emptyList(), "Добрый день!")),
        )
        assertArrayEquals(sample.toByteArray(Charsets.UTF_8), bytes)
    }

    @Test
    fun emptyEncodesToEmptyArray() {
        assertEquals("""{"transcript":[]}""", String(TranscriptEncoder.encode(emptyList()), Charsets.UTF_8))
    }
}
