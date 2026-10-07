package jp.example.poserecorder

import org.json.JSONObject

data class PosePoint(val x: Float, val y: Float, val visibility: Float)
data class PoseFrame(val elapsedMs: Long, val width: Int, val height: Int, val poses: List<List<PosePoint>>, val selectedPose: Int = -1) {
    companion object {
        fun parse(line: String): PoseFrame? {
            val json = JSONObject(line)
            if (json.optString("type") != "frame") return null
            val poses = json.getJSONArray("poses")
            return PoseFrame(json.getLong("elapsed_ms").coerceAtLeast(0),
                json.getInt("image_width").coerceAtLeast(1), json.getInt("image_height").coerceAtLeast(1),
                List(poses.length()) { poseIndex ->
                    val points = poses.getJSONObject(poseIndex).getJSONArray("landmarks")
                    // Preserve stable app landmark slots when a sparse JSON array is reordered.
                    val indexed = MutableList(33) { PosePoint(0f, 0f, 0f) }
                    for (i in 0 until points.length()) {
                        val p = points.getJSONObject(i)
                        val index = p.getInt("index")
                        if (index in indexed.indices) indexed[index] = PosePoint(
                            p.getDouble("x").toFloat(), p.getDouble("y").toFloat(), p.optDouble("visibility", 0.0).toFloat())
                    }
                    indexed
                }, json.optInt("selected_pose_index", -1))
        }
    }
}

fun formatElapsed(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return java.util.Locale.ROOT.let { java.lang.String.format(it, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60) }
}
