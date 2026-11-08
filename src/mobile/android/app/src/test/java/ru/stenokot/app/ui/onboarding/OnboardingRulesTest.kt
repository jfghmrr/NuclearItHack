package ru.stenokot.app.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.model.Stage

class OnboardingRulesTest {
    private fun decide(
        seen: Boolean = false,
        action: String? = "android.intent.action.MAIN",
        external: Boolean = false,
        debug: Boolean = false,
        jobs: List<JobState> = emptyList(),
        recording: Boolean = false,
    ) = OnboardingRules.decide(seen, action, external, debug, jobs, recording)

    @Test
    fun firstLaunchFromLauncherShowsIntro() {
        assertEquals(OnboardingDecision.SHOW, decide())
        assertEquals(OnboardingDecision.SHOW, decide(action = null))
    }

    @Test
    fun seenIntroIsNotShownAgain() {
        assertEquals(OnboardingDecision.HIDE, decide(seen = true))
    }

    @Test
    fun fileIntentsNeverShowIntro() {
        listOf(
            "android.intent.action.VIEW",
            "android.intent.action.SEND",
            "android.intent.action.SEND_MULTIPLE",
        ).forEach { action ->
            assertEquals(action, OnboardingDecision.HIDE_FOR_GOOD, decide(action = action))
            assertEquals(action, OnboardingDecision.HIDE_FOR_GOOD, decide(action = action, external = true))
        }
    }

    @Test
    fun fileIntentDoesNotDependOnAcceptedRoute() {
        assertEquals(OnboardingDecision.HIDE_FOR_GOOD, decide(action = "android.intent.action.VIEW", external = false))
    }

    @Test
    fun importInProgressNeverShowsIntro() {
        assertEquals(OnboardingDecision.HIDE_FOR_GOOD, decide(jobs = listOf(JobState.Queued)))
        assertEquals(OnboardingDecision.HIDE_FOR_GOOD, decide(jobs = listOf(JobState.Running(Stage.DECODE, 0.1f))))
        assertEquals(OnboardingDecision.HIDE_FOR_GOOD, decide(jobs = listOf(JobState.Cancelled, JobState.Queued)))
        assertEquals(OnboardingDecision.HIDE_FOR_GOOD, decide(external = true))
        assertEquals(OnboardingDecision.HIDE_FOR_GOOD, decide(recording = true))
    }

    @Test
    fun finishedRecordsInLibraryDoNotBlockIntro() {
        assertEquals(OnboardingDecision.SHOW, decide(jobs = listOf(JobState.Done(LlmStatus.APPLIED, emptyList()))))
    }

    @Test
    fun debugSceneHidesWithoutRememberingFlag() {
        assertEquals(OnboardingDecision.HIDE, decide(debug = true, external = true))
    }

    @Test
    fun openIntroClosesWhenFileArrivesOrImportStarts() {
        assertTrue(OnboardingRules.interrupted(externalRoute = true, jobs = emptyList(), recording = false))
        assertTrue(OnboardingRules.interrupted(false, listOf(JobState.Queued), false))
        assertTrue(OnboardingRules.interrupted(false, listOf(JobState.Running(Stage.ASR, 0.5f)), false))
        assertTrue(OnboardingRules.interrupted(false, emptyList(), recording = true))
    }

    @Test
    fun finishedJobsDoNotCloseReplayedIntro() {
        assertFalse(OnboardingRules.interrupted(false, emptyList(), false))
        assertFalse(OnboardingRules.interrupted(false, listOf(JobState.Done(LlmStatus.APPLIED, emptyList())), false))
        assertFalse(OnboardingRules.interrupted(false, listOf(JobState.Cancelled), false))
    }
}
