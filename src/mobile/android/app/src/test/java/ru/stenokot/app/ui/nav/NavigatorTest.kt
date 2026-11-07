package ru.stenokot.app.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.model.Stage

class NavigatorTest {
    @Test
    fun routesSurviveEncoding() {
        val routes = listOf(
            Route.Home,
            Route.Record,
            Route.Diagnostics,
            Route.Models,
            Route.ModelCard("GigaAM v3 e2e RNNT int8"),
            Route.Privacy,
            Route.Permissions,
            Route.About,
            Route.Processing("job-1"),
            Route.Failure("job-2"),
            Route.Result("job-3", ResultTab.PROTOCOL, 12),
            Route.Result("job-4"),
            Route.Result("job-5", ResultTab.TRANSCRIPT, 7, play = true),
            Route.ResultFile("job-6"),
            Route.ProtocolItem("job-7", 3),
            Route.Licenses,
            Route.LicenseText("gigaam"),
        )
        routes.forEach { assertEquals(it, decodeRoute(it.encode())) }
    }

    @Test
    fun savedResultRouteWithoutPlayFlagStillOpens() {
        assertEquals(Route.Result("job-1", ResultTab.PROTOCOL, 5), decodeRoute("result|job-1|PROTOCOL|5"))
    }

    @Test
    fun replaceKeepsBackPath() {
        val navigator = Navigator(listOf(Route.Home))
        navigator.push(Route.Processing("a"))
        navigator.replaceTop(Route.Result("a"))
        assertEquals(Route.Result("a"), navigator.current)
        assertTrue(navigator.back())
        assertEquals(Route.Home, navigator.current)
        assertFalse(navigator.back())
    }

    @Test
    fun pushIgnoresSameScreen() {
        val navigator = Navigator(listOf(Route.Home))
        navigator.push(Route.Diagnostics)
        navigator.push(Route.Diagnostics)
        navigator.back()
        assertEquals(Route.Home, navigator.current)
    }

    @Test
    fun jobStateDecidesScreen() {
        assertEquals(Route.Processing("a"), routeForJob("a", JobState.Running(Stage.ASR, 0f)))
        assertEquals(Route.Result("a"), routeForJob("a", JobState.Running(Stage.LLM, 0f)))
        assertEquals(Route.Result("a"), routeForJob("a", JobState.TranscriptReady("x")))
        assertEquals(Route.Result("a"), routeForJob("a", JobState.Done(LlmStatus.FAILED, emptyList())))
        assertEquals(Route.Failure("a"), routeForJob("a", JobState.Interrupted(Stage.ASR, ErrorCode.FGS_TIMEOUT)))
        assertEquals(Route.Processing("a"), routeForJob("a", JobState.Cancelled))
    }
}
