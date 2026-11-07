package ru.stenokot.app.pipeline

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenokot.core.engine.Asr
import ru.stenokot.core.engine.PcmReader
import ru.stenokot.core.model.AsrSegment
import ru.stenokot.core.model.SpeechSegment

/** Распознаватель наготове: одна загрузка, ожидание вместо второй копии, освобождение. */
class WarmAsrTest {
    private class FakeAsr : Asr {
        val closes = AtomicInteger()
        override fun transcribe(pcm: PcmReader, chunk: SpeechSegment): AsrSegment = error("не вызывается")
        override fun close() {
            closes.incrementAndGet()
        }
    }

    private val loads = AtomicInteger()

    private fun warm(room: Boolean = true, block: () -> Asr = { FakeAsr() }) =
        WarmAsr(load = { loads.incrementAndGet(); block() }, hasRoom = { room })

    @Test
    fun repeatedPrepareLoadsOnce() {
        val warm = warm()
        repeat(3) { warm.prepare() }
        assertNotNull(warm.take())
        assertEquals(1, loads.get())
    }

    @Test
    fun takeDuringLoadWaitsForTheSameModel() {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val model = FakeAsr()
        val warm = warm {
            started.countDown()
            finish.await(5, TimeUnit.SECONDS)
            model
        }
        warm.prepare()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        var taken: Asr? = null
        val job = thread { taken = warm.take() }
        job.join(50)
        assertTrue("задача ждёт конца загрузки", job.isAlive)
        finish.countDown()
        job.join(5_000)
        assertSame(model, taken)
        assertEquals(1, loads.get())
    }

    @Test
    fun modelGoesToOneOwner() {
        val warm = warm()
        warm.prepare()
        assertNotNull(warm.take())
        assertNull(warm.take())
    }

    @Test
    fun noRoomMeansNoLoad() {
        val warm = warm(room = false)
        warm.prepare()
        assertNull(warm.take())
        assertEquals(0, loads.get())
    }

    @Test
    fun failedLoadStaysSilentAndCanBeRepeated() {
        var failing = true
        val warm = warm { if (failing) error("нет модели") else FakeAsr() }
        warm.prepare()
        assertNull(warm.take())
        failing = false
        warm.prepare()
        assertNotNull(warm.take())
        assertEquals(2, loads.get())
    }

    @Test
    fun releaseClosesWaitingModel() {
        val model = FakeAsr()
        val warm = warm { model }
        warm.prepare()
        warm.release()
        assertEquals(1, model.closes.get())
        assertNull(warm.take())
    }

    @Test
    fun releaseWithoutModelDoesNothing() {
        warm().release()
        assertEquals(0, loads.get())
    }

    @Test
    fun releaseDuringLoadWaitsAndClosesIt() {
        val finish = CountDownLatch(1)
        val model = FakeAsr()
        val warm = warm {
            finish.await(5, TimeUnit.SECONDS)
            model
        }
        warm.prepare()
        val releasing = thread { warm.release() }
        releasing.join(50)
        assertTrue(releasing.isAlive)
        assertEquals(0, model.closes.get())
        finish.countDown()
        releasing.join(5_000)
        assertFalse(releasing.isAlive)
        assertEquals(1, model.closes.get())
    }
}
