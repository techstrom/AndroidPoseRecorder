package jp.example.poserecorder

import android.graphics.Bitmap
import android.graphics.Color
import android.widget.CheckBox
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

class VideoImportActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var cancelled = false
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var overlay: PoseOverlay
    private lateinit var videoFrame: ImageView
    private lateinit var cancel: Button
    private var done = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(24, 24, 24, 24) }
        status = TextView(this).apply { text = "動画を準備しています…"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.WHITE) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        overlay = PoseOverlay(this).apply { fitInside = true }
        videoFrame = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = android.view.View.GONE }
        val visual = FrameLayout(this).apply {
            addView(videoFrame, FrameLayout.LayoutParams(-1, -1))
            addView(this@VideoImportActivity.overlay, FrameLayout.LayoutParams(-1, -1))
        }
        cancel = Button(this).apply {
            text = "中止"
            setOnClickListener { if (done) finish() else { cancelled = true; isEnabled = false; status.text = "中止しています…" } }
        }
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(status, LinearLayout.LayoutParams(0, dp(60), 1f))
        val background = CheckBox(this).apply {
            text = "背景の動画"; setTextColor(Color.WHITE); isChecked = intent.getBooleanExtra("show_video", false)
            setOnCheckedChangeListener { _, checked -> videoFrame.visibility = if (checked) android.view.View.VISIBLE else android.view.View.GONE }
        }
        toolbar.addView(background)
        root.addView(toolbar); root.addView(progress, LinearLayout.LayoutParams(-1, -2))
        root.addView(visual, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(cancel)
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { cancelled = true; finish() }
        })
        val uri = intent.data ?: run { finish(); return }
        val name = intent.getStringExtra("name")?.trim()?.takeIf { it.isNotEmpty() } ?: "動画の記録"
        val tags = intent.getStringArrayListExtra("tags")?.toList().orEmpty()
        val sourceName = intent.getStringExtra("source_name") ?: name
        val showVideo = background.isChecked
        worker.execute {
            val retriever = MediaMetadataRetriever()
            val recorder = PoseRecorder(File(filesDir, "recordings"))
            var detector: PoseLandmarker? = null
            try {
                retriever.setDataSource(this, uri)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: throw IllegalArgumentException("動画の長さを取得できません")
                require(duration > 0) { "動画にフレームがありません" }
                detector = PoseLandmarker.createFromOptions(this, PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").build())
                    .setRunningMode(RunningMode.VIDEO).setNumPoses(1).build())
                recorder.start(0, "video", uri.toString(), sourceName, showVideo)
                var timestamp = 0L
                var lastUi = 0L
                while (timestamp < duration) {
                    if (cancelled) throw CancellationException()
                    // OPTION_CLOSEST decodes frames between keyframes as well.
                    val decoded = if (android.os.Build.VERSION.SDK_INT >= 27)
                        retriever.getScaledFrameAtTime(timestamp * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 720, 720)
                    else retriever.getFrameAtTime(timestamp * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                    val bitmap = decoded ?: throw IllegalArgumentException("${timestamp}msのフレームを読み込めません")
                    val scale = minOf(1f, 720f / maxOf(bitmap.width, bitmap.height))
                    val resized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1), (bitmap.height * scale).roundToInt().coerceAtLeast(1), true) else bitmap
                    // Keep the retriever's bitmap alive; MediaMetadataRetriever can reuse its
                    // decoded buffer for another timestamp. Inference and preview get owned copies.
                    val argb = if (resized === bitmap || resized.config != Bitmap.Config.ARGB_8888)
                        resized.copy(Bitmap.Config.ARGB_8888, false) else resized
                    try {
                        // MPImage.close() releases its owned bitmap, so copy the preview first.
                        val displayed = if (showVideo) argb.copy(Bitmap.Config.ARGB_8888, false) else null
                        val image = BitmapImageBuilder(argb).build()
                        val result = try { detector.detectForVideo(image, timestamp) } finally { image.close() }
                        recorder.append(result, timestamp, argb.width, argb.height)
                        val now = SystemClock.uptimeMillis()
                        if (now - lastUi >= 100) {
                            lastUi = now
                            val elapsed = timestamp
                            val percentage = (timestamp * 100 / duration).toInt()
                            val frame = PoseFrame(elapsed, argb.width, argb.height, result.landmarks().map { pose -> pose.map { PosePoint(it.x(), it.y(), it.visibility().orElse(0f)) } })
                            runOnUiThread {
                                if (!isDestroyed) {
                                    status.text = "$name\n${formatElapsed(elapsed)} / ${formatElapsed(duration)} · $percentage%"
                                    progress.progress = percentage; overlay.update(frame)
                                    if (displayed != null) videoFrame.setImageBitmap(displayed)
                                } else displayed?.recycle()
                            }
                        }
                    } finally {
                        if (argb !== bitmap && argb !== resized) argb.recycle()
                        if (resized !== bitmap && resized !== argb) resized.recycle()
                    }
                    timestamp += 100
                }
                if (cancelled) throw CancellationException()
                val saved = recorder.stop() ?: throw IllegalStateException("記録を保存できません")
                RecordingStore(this).use { it.add(saved, name, tags, "video", uri.toString(), sourceName, showVideo) }
                val output = saved
                runOnUiThread {
                    if (!isDestroyed) {
                        done = true; progress.progress = 100; status.text = "$name\n保存しました · ${recorder.frames} フレーム"
                        cancel.text = "一覧へ戻る"; cancel.isEnabled = true
                        val replay = Button(this).apply { text = "再生"; setOnClickListener {
                            startActivity(Intent(this@VideoImportActivity, PlaybackActivity::class.java)
                                .putExtra("recording_name", output.name).putExtra("source_uri", uri.toString()).putExtra("show_video", showVideo))
                        } }
                        root.addView(replay)
                    }
                }
            } catch (e: Exception) {
                runCatching { recorder.abort() }
                // A file finalized just before metadata failed is still recovered by library sync.
                runOnUiThread {
                    if (!isDestroyed) {
                        done = true; status.text = if (e is CancellationException) "解析を中止しました" else "解析に失敗: ${e.localizedMessage}"
                        cancel.text = "一覧へ戻る"; cancel.isEnabled = true
                    }
                }
            } finally { detector?.close(); retriever.release() }
        }
    }
    override fun onDestroy() { cancelled = true; worker.shutdown(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
