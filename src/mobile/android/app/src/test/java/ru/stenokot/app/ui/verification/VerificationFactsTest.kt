package ru.stenokot.app.ui.verification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenokot.app.domain.JobSummaryUi
import ru.stenokot.app.domain.ModelItemUi
import ru.stenokot.app.domain.ModelReadiness
import ru.stenokot.app.domain.VerificationUi
import ru.stenokot.core.model.JobState

class VerificationFactsTest {
    private fun model(ready: Boolean) = ModelItemUi("m", "r", 1, "abc", "MIT", ready)

    private fun verification(id: String? = null, title: String? = null) = VerificationUi(
        internetPermissionAbsent = true,
        networkStatePermissionAbsent = true,
        appVersion = "1",
        models = emptyList(),
        lastJobTitle = title,
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
        lastJobId = id,
    )

    private fun job(id: String, title: String) = JobSummaryUi(id, title, 0, null, null, JobState.Queued, null)

    private fun facts(vararg permissions: String) = PackageFacts("p", "1", permissions.toList(), "m", 1, 36)

    @Test
    fun readyModelStaysReadyWhateverTheOthersDo() {
        ModelReadiness.entries.forEach {
            assertEquals(ModelRowState.READY, modelRowState(model(true), it))
        }
    }

    @Test
    fun missingModelIsExplainedByReadiness() {
        assertEquals(ModelRowState.COPYING, modelRowState(model(false), ModelReadiness.COPYING))
        assertEquals(ModelRowState.UNAVAILABLE, modelRowState(model(false), ModelReadiness.LLM_UNAVAILABLE))
        assertEquals(ModelRowState.ASR_UNAVAILABLE, modelRowState(model(false), ModelReadiness.ASR_UNAVAILABLE))
        assertEquals(ModelRowState.STORAGE_LOW, modelRowState(model(false), ModelReadiness.STORAGE_LOW))
    }

    @Test
    fun internetIsCheckedAgainstThePackage() {
        assertTrue(facts("android.permission.WAKE_LOCK").internetAbsent)
        assertFalse(facts("android.permission.INTERNET").internetAbsent)
        assertFalse(facts("android.permission.ACCESS_NETWORK_STATE").networkStateAbsent)
    }

    @Test
    fun textHelpers() {
        assertEquals("zapis.metadata.json", metadataFileName("zapis.mp3"))
        assertEquals("WAKE_LOCK", shortPermission("android.permission.WAKE_LOCK"))
        assertEquals("ru.app.CUSTOM", shortPermission("ru.app.CUSTOM"))
    }

    @Test
    fun lastJobIsFoundByIdThenByTitle() {
        val jobs = listOf(job("a", "x.mp3"), job("b", "y.mp3"))
        assertEquals("z", lastJobId(verification(id = "z"), jobs))
        assertEquals("b", lastJobId(verification(title = "y.mp3"), jobs))
        assertNull(lastJobId(verification(title = "n.mp3"), jobs))
        assertNull(lastJobId(verification(), jobs))
    }
}
