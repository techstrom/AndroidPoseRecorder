package jp.example.poserecorder

import org.junit.Assert.assertEquals
import org.junit.Test

class PoseTrackerTest {
    @Test fun keepsSelectionThroughFastVerticalJump() {
        val tracker = PoseTracker()
        tracker.select(listOf(pose(0.40f, 0.55f)), 0)
        assertEquals(0, tracker.update(listOf(pose(0.41f, 0.20f))))
    }

    @Test fun reacquiresSameSubjectAfterBriefDetectionGapDuringJump() {
        val tracker = PoseTracker()
        tracker.select(listOf(pose(0.40f, 0.55f)), 0)
        assertEquals(0, tracker.update(listOf(pose(0.40f, 0.30f))))
        assertEquals(-1, tracker.update(emptyList()))
        assertEquals(0, tracker.update(listOf(pose(0.41f, 0.08f))))
    }

    @Test fun clearsSelectionInsteadOfDriftingToFarSubjectAfterMiss() {
        val tracker = PoseTracker()
        tracker.select(listOf(pose(0.66f, 0.35f)), 0)
        assertEquals(0, tracker.update(listOf(pose(0.70f, 0.36f))))
        assertEquals(0, tracker.update(listOf(pose(0.76f, 0.37f))))
        assertEquals(-1, tracker.update(emptyList()))
        repeat(6) { tracker.update(listOf(pose(0.19f, 0.87f))) }
        assertEquals(-1, tracker.selectedIndex)
    }

    private fun pose(x: Float, y: Float): List<PosePoint> {
        val points = MutableList(33) { PosePoint(0f, 0f, 0f) }
        points[11] = PosePoint(x - 0.03f, y, 0.95f)
        points[12] = PosePoint(x + 0.03f, y, 0.95f)
        points[23] = PosePoint(x - 0.02f, y + 0.12f, 0.95f)
        points[24] = PosePoint(x + 0.02f, y + 0.12f, 0.95f)
        points[15] = PosePoint(x - 0.06f, y - 0.04f, 0.9f)
        points[16] = PosePoint(x + 0.06f, y - 0.04f, 0.9f)
        points[27] = PosePoint(x - 0.03f, y + 0.22f, 0.9f)
        points[28] = PosePoint(x + 0.03f, y + 0.22f, 0.9f)
        return points
    }
}
