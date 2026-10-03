package jp.example.poserecorder

import org.junit.Assert.*
import org.junit.Test

class PlaybackTest {
    @Test fun pauseExcludesTimeSpentPausedAndCanRestart() {
        val clock = PlaybackClock()
        clock.resume(1000)
        assertEquals(750L, clock.position(1750))
        clock.pause(1750)
        assertEquals(750L, clock.position(9000))
        clock.resume(9000)
        assertEquals(1000L, clock.position(9250))
        clock.reset()
        assertEquals(0L, clock.position(10000))
        assertFalse(clock.running)
    }
    @Test fun preservesMissingPosesAndLandmarkIndices() {
        assertNull(PoseFrame.parse("""{"type":"session"}"""))
        val empty = PoseFrame.parse("""{"type":"frame","elapsed_ms":120,"image_width":480,"image_height":640,"poses":[]}""")!!
        assertTrue(empty.poses.isEmpty())
        assertEquals(120L, empty.elapsedMs)
        val frame = PoseFrame.parse("""{"type":"frame","elapsed_ms":250,"image_width":480,"image_height":640,"poses":[{"landmarks":[{"index":11,"x":0.4,"y":0.5,"visibility":0.9}]}]}""")!!
        assertEquals(33, frame.poses[0].size)
        assertEquals(0.4f, frame.poses[0][11].x)
        assertEquals(0f, frame.poses[0][0].visibility)
    }
    @Test fun elapsedTimeHandlesHours() {
        assertEquals("01:01:01", formatElapsed(3661000))
    }
}
