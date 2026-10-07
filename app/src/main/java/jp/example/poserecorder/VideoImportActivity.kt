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
import android.widget.SeekBar
import android.widget.TextView
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
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
    private lateinit var seek: SeekBar
    private lateinit var fpsSlider: SeekBar
    private lateinit var fpsLabel: TextView
    private lateinit var playPause: Button
    private val tracker = PoseTracker()
    @Volatile private var paused = true
    @Volatile private var requestedTimestamp = -1L
    @Volatile private var durationMs = 1L
    @Volatile private var latestPoses: List<List<PosePoint>> = emptyList()
    @Volatile private var imageWidth = 1
    @Volatile private var imageHeight = 1
    @Volatile private var selectedIndex = -1
    @Volatile private var requestedAnalysisFps = 15
    @Volatile private var activeAnalysisFps = 15
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
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val background = CheckBox(this).apply {
            text = "背景の動画"; setTextColor(Color.WHITE); isChecked = intent.getBooleanExtra("show_video", false)
            setOnCheckedChangeListener { _, checked -> videoFrame.visibility = if (checked) android.view.View.VISIBLE else android.view.View.GONE }
        }
        // Apply the initial checkbox value too; the listener only handles later changes.
        videoFrame.visibility = if (background.isChecked) android.view.View.VISIBLE else android.view.View.GONE
        toolbar.addView(status, LinearLayout.LayoutParams(-1, dp(84)))
        toolbar.addView(background, LinearLayout.LayoutParams(-2, dp(48)).apply { gravity = Gravity.END })
        val fpsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fpsLabel = TextView(this).apply {
            text = "解析fps: 15"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, dp(8), 0)
        }
        fpsSlider = SeekBar(this).apply {
            max = 25 // 5 to 30 frames per second.
            progress = 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    requestedAnalysisFps = value + 5
                    fpsLabel.text = "解析fps: ${value + 5}"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        fpsRow.addView(fpsLabel, LinearLayout.LayoutParams(dp(120), -2))
        fpsRow.addView(fpsSlider, LinearLayout.LayoutParams(0, dp(44), 1f))
        toolbar.addView(fpsRow, LinearLayout.LayoutParams(-1, dp(44)))
        root.addView(toolbar); root.addView(progress, LinearLayout.LayoutParams(-1, -2))
        root.addView(visual, LinearLayout.LayoutParams(-1, 0, 1f))
        val navigation = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        playPause = Button(this).apply { text = "一時停止"; isEnabled = false; setOnClickListener {
            paused = !paused; text = if (paused) "再開" else "一時停止"
        } }
        seek = SeekBar(this).apply {
            max = 1000; isEnabled = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) { paused = true; playPause.text = "再開"; requestedTimestamp = durationMs * value / 1000 }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) { paused = true; playPause.text = "再開" }
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        navigation.addView(playPause); navigation.addView(seek, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(navigation); root.addView(cancel)
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { cancelled = true; finish() }
        })
        val uri = intent.data ?: run { finish(); return }
        overlay.setOnPoseTapListener { index ->
            if (tracker.select(latestPoses, index) != null) {
                selectedIndex = index
                activeAnalysisFps = requestedAnalysisFps
                fpsSlider.isEnabled = false
                overlay.updatePoses(latestPoses, imageWidth, imageHeight, index)
                status.text = "人物${index + 1}を追跡中 · ${activeAnalysisFps}fps · タップで変更"
                seek.isEnabled = true
                playPause.isEnabled = true
                paused = false; playPause.text = "一時停止"
            }
        }
        val name = intent.getStringExtra("name")?.trim()?.takeIf { it.isNotEmpty() } ?: "動画の記録"
        val tags = intent.getStringArrayListExtra("tags")?.toList().orEmpty()
        val sourceName = intent.getStringExtra("source_name") ?: name
        val showVideo = background.isChecked
        worker.execute {
            val retriever = MediaMetadataRetriever()
            val recorder = PoseRecorder(File(filesDir, "recordings"))
            var detector: YoloPoseDetector? = null
            try {
                retriever.setDataSource(this, uri)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: throw IllegalArgumentException("動画の長さを取得できません")
                require(duration > 0) { "動画にフレームがありません" }
                durationMs = duration
                detector = YoloPoseDetector(this)
                var timestamp = 0L
                var lastUi = 0L
                var trackingStarted = false
                var intervalRemainder = 0L
                fun nextFrameTimestamp(current: Long): Long {
                    val fps = activeAnalysisFps.coerceIn(1, 60).toLong()
                    val numerator = 1000L + intervalRemainder
                    intervalRemainder = numerator % fps
                    return current + (numerator / fps).coerceAtLeast(1L)
                }
                fun uiIntervalMs() = (1000.0 / activeAnalysisFps).roundToInt().coerceAtLeast(1).toLong()
                var hasPreview = false
                while (timestamp < duration && !trackingStarted) {
                    if (cancelled) throw CancellationException()
                    val seekRequest = requestedTimestamp.takeIf { it >= 0 }
                    seekRequest?.let { timestamp = it; requestedTimestamp = -1; intervalRemainder = 0L }
                    if (paused && hasPreview && seekRequest == null) { Thread.sleep(50); continue }
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
                        val candidates = detectVideoPoseCandidates(detector, argb)
                        val poses = candidates.map { it.points }
                        latestPoses = poses; imageWidth = argb.width; imageHeight = argb.height
                        val tracked = tracker.update(poses)
                        selectedIndex = tracked
                        hasPreview = true
                        if (tracked >= 0 && !trackingStarted) {
                            val chosen = candidates[tracked]
                            recorder.start(timestamp, "video", uri.toString(), sourceName, showVideo, activeAnalysisFps)
                            recorder.append(chosen.points, timestamp, argb.width, argb.height)
                            trackingStarted = true
                            runOnUiThread { seek.isEnabled = false; fpsSlider.isEnabled = false }
                        }
                        val now = SystemClock.uptimeMillis()
                        if (now - lastUi >= minOf(100L, uiIntervalMs())) {
                            lastUi = now
                            val elapsed = timestamp
                            val percentage = (timestamp * 100 / duration).toInt()
                            val displayedFps = if (trackingStarted) activeAnalysisFps else requestedAnalysisFps
                            val frame = PoseFrame(elapsed, argb.width, argb.height, poses, tracked)
                            runOnUiThread {
                                if (!isDestroyed) {
                                    status.text = if (tracked >= 0) "$name\n人物${tracked + 1}を追跡中 · ${displayedFps}fps · 検出${poses.size}人 · ${formatElapsed(elapsed)} / ${formatElapsed(duration)}"
                                        else "$name\n${displayedFps}fps · 検出${poses.size}人 · 人物をタップして選択 · ${formatElapsed(elapsed)} / ${formatElapsed(duration)}"
                                    progress.progress = percentage; seek.progress = (timestamp * 1000 / duration).toInt(); seek.isEnabled = !trackingStarted; playPause.isEnabled = true; overlay.update(frame)
                                    if (displayed != null) videoFrame.setImageBitmap(displayed)
                                } else displayed?.recycle()
                            }
                        }
                    } finally {
                        if (argb !== bitmap && argb !== resized) argb.recycle()
                        if (resized !== bitmap && resized !== argb) resized.recycle()
                    }
                    timestamp = nextFrameTimestamp(timestamp)
                }
                if (!trackingStarted) throw CancellationException()
                while (timestamp < duration) {
                    if (cancelled) throw CancellationException()
                    requestedTimestamp.takeIf { it >= 0 }?.let { timestamp = it; requestedTimestamp = -1; intervalRemainder = 0L }
                    if (paused) { Thread.sleep(50); continue }
                    val decoded = if (android.os.Build.VERSION.SDK_INT >= 27)
                        retriever.getScaledFrameAtTime(timestamp * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 720, 720)
                    else retriever.getFrameAtTime(timestamp * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                    val bitmap = decoded ?: throw IllegalArgumentException("${timestamp}msのフレームを読み込めません")
                    val scale = minOf(1f, 720f / maxOf(bitmap.width, bitmap.height))
                    val resized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1), (bitmap.height * scale).roundToInt().coerceAtLeast(1), true) else bitmap
                    val argb = if (resized === bitmap || resized.config != Bitmap.Config.ARGB_8888) resized.copy(Bitmap.Config.ARGB_8888, false) else resized
                    try {
                        val displayed = if (showVideo) argb.copy(Bitmap.Config.ARGB_8888, false) else null
                        val candidates = detectVideoPoseCandidates(detector, argb)
                        val poses = candidates.map { it.points }
                        val tracked = tracker.update(poses); selectedIndex = tracked
                        val chosen = candidates.getOrNull(tracked)
                        recorder.append(chosen?.points, timestamp, argb.width, argb.height)
                        val frame = PoseFrame(timestamp, argb.width, argb.height, poses, tracked)
                        runOnUiThread {
                            if (!isDestroyed) {
                                status.text = if (tracked >= 0) "人物${tracked + 1}を追跡中 · ${activeAnalysisFps}fps · 検出${poses.size}人 · タップで変更" else "${activeAnalysisFps}fps · 検出${poses.size}人 · 追跡対象を見失いました · 人物をタップしてください"
                                progress.progress = (timestamp * 100 / duration).toInt(); seek.progress = (timestamp * 1000 / duration).toInt()
                                overlay.update(frame); if (displayed != null) videoFrame.setImageBitmap(displayed)
                            } else displayed?.recycle()
                        }
                    } finally {
                        if (argb !== bitmap && argb !== resized) argb.recycle()
                        if (resized !== bitmap && resized !== argb) resized.recycle()
                    }
                    timestamp = nextFrameTimestamp(timestamp)
                }
                if (cancelled) throw CancellationException()
                val saved = recorder.stop() ?: throw IllegalStateException("記録を保存できません")
                RecordingStore(this).use { it.add(saved, name, tags, "video", uri.toString(), sourceName, showVideo) }
                val output = saved
                runOnUiThread {
                    if (!isDestroyed) {
                        done = true; progress.progress = 100; status.text = "$name\n保存しました · ${recorder.frames} フレーム · ${activeAnalysisFps}fps"
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
