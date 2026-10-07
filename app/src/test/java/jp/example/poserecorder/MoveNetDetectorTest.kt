package jp.example.poserecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseLandmarkMappingTest {
    @Test fun mapsCoco17ToStablePoseSlots() {
        val source = List(17) { index -> PosePoint(index / 17f, index / 34f, 0.9f) }
        val pose = PoseLandmarkMapping.coco17ToPose33(source)

        assertEquals(33, pose.size)
        val slots = listOf(0, 1, 4, 7, 8, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)
        slots.forEachIndexed { sourceIndex, slot ->
            assertEquals(source[sourceIndex], pose[slot])
        }
        assertTrue(pose.indices.filterNot { it in slots }.all { pose[it].visibility == 0f })
    }
}
