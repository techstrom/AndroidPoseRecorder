package jp.example.poserecorder

/** COCO-17 joint order to the app's stable 33-slot pose-file and overlay layout. */
object PoseLandmarkMapping {
    private val slots = intArrayOf(0, 1, 4, 7, 8, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)

    fun coco17ToPose33(points: List<PosePoint>): List<PosePoint> {
        val result = MutableList(33) { PosePoint(0f, 0f, 0f) }
        points.take(slots.size).forEachIndexed { index, point -> result[slots[index]] = point }
        return result
    }
}
