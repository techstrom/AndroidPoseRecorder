package jp.example.poserecorder

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.util.UUID

/** All operations run on the same camera executor, ordered with inference. */
class PoseRecorder(private val directory: File) {
    private var writer: BufferedWriter? = null
    private var file: File? = null
    private var startMs = 0L
    private var source = "camera"
    private var sourceUri: String? = null
    private var sourceName: String? = null
    private var showVideoOnPlayback = false
    var frames = 0L
        private set
    val recording get() = writer != null

    fun start(nowMs: Long, source: String = "camera", sourceUri: String? = null,
              sourceName: String? = null, showVideoOnPlayback: Boolean = false, analysisFps: Int? = null) {
        check(!recording)
        check(directory.exists() || directory.mkdirs()) { "保存フォルダーを作成できません" }
        this.source = source
        this.sourceUri = sourceUri
        this.sourceName = sourceName
        this.showVideoOnPlayback = showVideoOnPlayback
        val output = File(directory, "pose_${System.currentTimeMillis()}_${UUID.randomUUID()}.partial")
        val stream = output.bufferedWriter(Charsets.UTF_8)
        try {
            stream.write(JSONObject().put("type", "session").put("schema_version", 1)
                .put("started_at_epoch_ms", System.currentTimeMillis()).put("source", source)
                .put("camera", if (source == "camera") "back" else JSONObject.NULL)
                .put("time_base", if (source == "camera") "uptime" else "video")
                .put("source_uri", sourceUri).put("source_video_name", sourceName)
                .put("source_video_start_ms", if (source == "video") nowMs else JSONObject.NULL)
                .put("show_video_on_playback", showVideoOnPlayback)
                .put("analysis_fps", if (source == "video") analysisFps ?: JSONObject.NULL else JSONObject.NULL)
                .put("pose_model", "YOLOv8n-pose ONNX")
                .put("coordinate_space", if (source == "camera") "rotated_viewport_image_unmirrored" else "upright_video_image_unmirrored")
                .put("world_unit", JSONObject.NULL).put("landmark_count", 33).toString())
            stream.newLine()
            stream.flush()
        } catch (e: Exception) { stream.close(); throw e }
        file = output
        startMs = nowMs
        frames = 0
        writer = stream
    }

    fun append(points: List<PosePoint>?, timestampMs: Long, width: Int, height: Int) {
        val stream = writer ?: return
        if (timestampMs < startMs) return
        val poses = JSONArray()
        if (points != null) {
            val landmarks = JSONArray()
            points.forEachIndexed { index, point ->
                val item = JSONObject().put("index", index)
                    .put("x", point.x).put("y", point.y).put("z", 0f)
                    .put("visibility", point.visibility).put("presence", point.visibility)
                landmarks.put(item)
            }
            poses.put(JSONObject().put("landmarks", landmarks))
        }
        stream.write(JSONObject().put("type", "frame").put("elapsed_ms", timestampMs - startMs)
            .put("selected_pose_index", if (points == null) -1 else 0)
            .put(if (source == "camera") "timestamp_monotonic_ms" else "timestamp_video_ms", timestampMs).put("image_width", width)
            .put("image_height", height).put("poses", poses).toString())
        stream.newLine()
        frames++
        if (frames % 30L == 0L) stream.flush()
    }

    fun stop(): File? {
        val stream = writer ?: return null
        writer = null
        try { stream.flush() } finally { stream.close() }
        val partial = file ?: return null
        val complete = File(directory, partial.nameWithoutExtension + ".jsonl")
        check(partial.renameTo(complete)) { "記録ファイルを確定できません" }
        file = null
        return complete
    }
    fun abort() {
        try { writer?.close() } finally { writer = null; file?.delete(); file = null }
    }
}
