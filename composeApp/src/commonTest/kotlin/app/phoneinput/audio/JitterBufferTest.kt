package app.phoneinput.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JitterBufferTest {
    private fun frame(v: Int) = ShortArray(4) { (100 + v).toShort() }

    private fun JitterBuffer.takeValue(): Int? = take()?.get(0)?.toInt()?.minus(100)

    @Test
    fun waitsUntilPrimedThenPlaysInOrder() {
        val jb = JitterBuffer(targetFrames = 3, maxFrames = 10, adaptive = false)
        jb.put(1, frame(1))
        jb.put(2, frame(2))
        assertNull(jb.take(), "not primed yet")
        jb.put(3, frame(3))
        assertEquals(listOf(1, 2, 3), List(3) { jb.takeValue() })
    }

    @Test
    fun reordersOutOfOrderArrivals() {
        val jb = JitterBuffer(targetFrames = 3, maxFrames = 10, adaptive = false)
        jb.put(12, frame(12))
        jb.put(10, frame(10))
        jb.put(11, frame(11))
        assertEquals(listOf(10, 11, 12), List(3) { jb.takeValue() })
    }

    @Test
    fun missingFrameIsConcealedAndLateFrameDropped() {
        val jb = JitterBuffer(targetFrames = 2, maxFrames = 10, adaptive = false)
        jb.put(0, frame(0))
        jb.put(2, frame(2))
        assertEquals(0, jb.takeValue())
        val concealed = assertNotNull(jb.take(), "seq 1 missing → fade of the last frame, not a hard cut")
        assertTrue(concealed.first() > concealed.last() && concealed.last() > 0, "fading out")
        jb.put(1, frame(1)) // too late now
        assertEquals(2, jb.takeValue())
        val stats = jb.stats()
        assertEquals(2, stats.played)
        assertEquals(1, stats.concealed)
        assertEquals(1, stats.dropped)
    }

    @Test
    fun capsLatencyByDroppingOldest() {
        val jb = JitterBuffer(targetFrames = 2, maxFrames = 4, adaptive = false)
        for (seq in 0 until 10) jb.put(seq, frame(seq))
        assertEquals(4, jb.depthFrames())
        assertEquals(6, jb.takeValue())
    }

    @Test
    fun rePrimesAfterUnderrunFadingOutMeanwhile() {
        val jb = JitterBuffer(targetFrames = 2, maxFrames = 10, adaptive = false)
        jb.put(0, frame(0))
        jb.put(1, frame(1))
        assertEquals(0, jb.takeValue())
        assertEquals(1, jb.takeValue())
        // Underrun: a few fading frames, then silence.
        val fade = List(6) { jb.take() }
        assertEquals(4, fade.count { it != null })
        assertNull(fade.last())
        jb.put(2, frame(2))
        assertNull(jb.take(), "waiting to re-prime")
        jb.put(3, frame(3))
        assertEquals(2, jb.takeValue())
    }

    @Test
    fun steadyArrivalsKeepTheMinimumDepth() {
        var now = 0L
        val jb = JitterBuffer(targetFrames = 3, clockMs = { now })
        for (seq in 0 until 300) {
            now = seq * 10L
            jb.put(seq, frame(seq))
        }
        assertEquals(3, jb.targetDepthFrames())
    }

    @Test
    fun burstyArrivalsRaiseTheTargetThenTrimBack() {
        var now = 0L
        val jb = JitterBuffer(targetFrames = 3, clockMs = { now })
        // A stream link that stalls 120 ms and then delivers the backlog at once.
        for (seq in 0 until 300) {
            now = (seq / 12) * 120L + 120L
            jb.put(seq, frame(seq))
        }
        assertTrue(jb.targetDepthFrames() >= 12, "target ${jb.targetDepthFrames()} should cover a 120 ms stall")

        // Calm again for longer than the window: the target drops back to the minimum…
        for (seq in 300 until 1_000) {
            now = seq * 10L + 200L
            jb.put(seq, frame(seq))
        }
        assertEquals(3, jb.targetDepthFrames())
    }

    @Test
    fun trimsExtraDepthWhenTheBufferNeverRunsLow() {
        var now = 0L
        val jb = JitterBuffer(targetFrames = 3, maxFrames = 40, clockMs = { now })
        for (seq in 0 until 20) jb.put(seq, frame(seq)) // a backlog of 20 frames
        var seq = 20
        repeat(3_000) { // the backlog leaves the 6 s jitter window, then 1 frame/s is trimmed
            now += 10
            jb.put(seq, frame(seq))
            seq++
            jb.take()
        }
        assertTrue(jb.depthFrames() <= 6, "depth ${jb.depthFrames()} should have been trimmed toward the target")
    }
}
