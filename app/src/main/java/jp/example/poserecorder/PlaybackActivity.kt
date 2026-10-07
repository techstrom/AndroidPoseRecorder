package jp.example.poserecorder

import android.graphics.Color
import android.graphics.Matrix
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.CheckBox
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.Executors

/** Reads one frame ahead, keeping memory bounded even for long recordings. */
class PlaybackActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val clock = PlaybackClock()
    private lateinit var overlay: PoseOverlay
    private lateinit var time: TextView
    private lateinit var play: IconButton
    private lateinit var video: TextureView
    private lateinit var backgroundToggle: CheckBox
    private lateinit var file: File
    private var reader: BufferedReader? = null // Worker thread only.
    private var pending: PoseFrame? = null // Main thread only.
    private var ended = false
    private var lastFrameMs = 0L
    private var generation = 0
    private var foreground = false
    private var sourceVideoStartMs = 0L
    private var sourceUri: String? = null
    private var sourceAspectRatio = 0f
    private var mediaPlayer: MediaPlayer? = null // Main thread only.
    private var videoSurface: Surface? = null // Main thread only.
    private var videoPrepared = false
    private val render = Runnable {
        if (clock.running) {
            pending?.let { frame ->
                overlay.update(frame)
                lastFrameMs = frame.elapsedMs
                pending = null
                requestNext()
            }
        }
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (clock.running) {
                time.text = formatElapsed(clock.position(SystemClock.uptimeMillis()))
                handler.postDelayed(this, 100)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val name = intent.getStringExtra("recording_name")
        if (name == null || File(name).name != name || !name.endsWith(".jsonl")) { finish(); return }
        file = File(File(filesDir, "recordings"), name)
        // The session header travels with the pose file, so this remains reliable even
        // when the SQLite catalogue has not refreshed or the launch came from a shortcut.
        val session = runCatching { file.bufferedReader().use { JSONObject(it.readLine() ?: "{}") } }.getOrNull()
        val sourceUri = session?.takeUnless { it.isNull("source_uri") }?.optString("source_uri")?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra("source_uri")
        this.sourceUri = sourceUri
        val showVideo = session?.optBoolean("show_video_on_playback")
            ?: intent.getBooleanExtra("show_video", false)
        sourceVideoStartMs = session?.optLong("source_video_start_ms", 0L)?.coerceAtLeast(0L) ?: 0L
        sourceAspectRatio = runCatching {
            file.bufferedReader().use { reader ->
                reader.readLine()
                PoseFrame.parse(reader.readLine() ?: "")?.let { it.width.toFloat() / it.height }
            }
        }.getOrNull() ?: 0f
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = TextureView(this).apply { visibility = View.GONE }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        overlay = PoseOverlay(this).apply { fitInside = true }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        time = TextView(this).apply { text = "00:00:00"; textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        root.addView(time, FrameLayout.LayoutParams(-1, dp(60), Gravity.TOP))
        backgroundToggle = CheckBox(this).apply {
            text = "背景動画"; setTextColor(Color.WHITE); setShadowLayer(3f, 1f, 1f, Color.BLACK)
            isChecked = showVideo
            visibility = if (sourceUri.isNullOrBlank()) View.GONE else View.VISIBLE
            setOnCheckedChangeListener { _, checked ->
                video.visibility = if (checked) View.VISIBLE else View.GONE
                if (checked) {
                    if (video.isAvailable && mediaPlayer == null) prepareVideoPlayer()
                    if (videoPrepared && clock.running) runCatching { mediaPlayer?.start() }
                } else if (videoPrepared) runCatching { mediaPlayer?.pause() }
            }
        }
        backgroundToggle.background = android.graphics.drawable.ColorDrawable(0x66000000)
        video.visibility = if (backgroundToggle.isChecked && !sourceUri.isNullOrBlank()) View.VISIBLE else View.GONE
        if (!sourceUri.isNullOrBlank()) {
            video.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                    applyVideoTransform()
                    prepareVideoPlayer()
                }
                override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) = applyVideoTransform()
                override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                    releaseVideoPlayer()
                    return true
                }
                override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit
            }
        }
        root.addView(backgroundToggle, FrameLayout.LayoutParams(-2, dp(56), Gravity.TOP or Gravity.END).apply { rightMargin = dp(12) })
        val controls = FrameLayout(this)
        play = IconButton(this, IconButton.Icon.PAUSE).apply {
            show(IconButton.Icon.PAUSE, "一時停止")
            setOnClickListener { if (ended) begin() else if (clock.running) pause() else resume() }
        }
        val back = IconButton(this, IconButton.Icon.BACK).apply { contentDescription = "戻る"; setOnClickListener { finish() } }
        controls.addView(play, FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER))
        controls.addView(back, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(24) })
        root.addView(controls, FrameLayout.LayoutParams(-1, dp(104), Gravity.BOTTOM))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            (time.layoutParams as FrameLayout.LayoutParams).apply { topMargin = safe.top + dp(12); time.layoutParams = this }
            (controls.layoutParams as FrameLayout.LayoutParams).apply { bottomMargin = safe.bottom + dp(12); controls.layoutParams = this }
            (overlay.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = safe.top + dp(72); bottomMargin = safe.bottom + dp(116); overlay.layoutParams = this
            }
            (video.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = safe.top + dp(72); bottomMargin = safe.bottom + dp(116); video.layoutParams = this
            }
            video.post { applyVideoTransform() }
            insets
        }
        setContentView(root)
        begin()
    }

    private fun begin() {
        generation++
        pending = null
        ended = false
        lastFrameMs = 0
        handler.removeCallbacks(render)
        handler.removeCallbacks(ticker)
        clock.reset()
        if (videoPrepared) runCatching { mediaPlayer?.seekTo(sourceVideoStartMs.toInt()) }
        overlay.clear()
        play.isEnabled = false
        time.text = "読み込み中…"
        val token = generation
        worker.execute {
            try {
                reader?.close()
                reader = file.bufferedReader()
                handler.post {
                    if (!isDestroyed && token == generation) {
                        play.isEnabled = true
                        if (foreground) resume() else play.show(IconButton.Icon.PLAY, "再生")
                        requestNext()
                    }
                }
            } catch (e: Exception) { showError(token, e) }
        }
    }

    private fun requestNext() {
        val token = generation
        worker.execute {
            try {
                var frame: PoseFrame? = null
                while (frame == null) {
                    val line = reader?.readLine() ?: break
                    if (line.isNotBlank()) frame = PoseFrame.parse(line)
                }
                val next = frame
                handler.post {
                    if (isDestroyed || token != generation) return@post
                    if (next == null) {
                        pause()
                        ended = true
                        time.text = "${formatElapsed(lastFrameMs)} · 再生終了"
                        play.show(IconButton.Icon.PLAY, "もう一度再生")
                    } else { pending = next; schedule() }
                }
            } catch (e: Exception) { showError(token, e) }
        }
    }

    private fun schedule() {
        handler.removeCallbacks(render)
        val next = pending ?: return
        if (clock.running) handler.postDelayed(render, (next.elapsedMs - clock.position(SystemClock.uptimeMillis())).coerceAtLeast(0))
    }
    private fun resume() {
        clock.resume(SystemClock.uptimeMillis())
        if (backgroundToggle.isChecked && videoPrepared) runCatching { mediaPlayer?.start() }
        play.show(IconButton.Icon.PAUSE, "一時停止")
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        schedule()
    }
    private fun pause() {
        clock.pause(SystemClock.uptimeMillis())
        if (videoPrepared) runCatching { mediaPlayer?.pause() }
        handler.removeCallbacks(render)
        handler.removeCallbacks(ticker)
        play.show(IconButton.Icon.PLAY, "再生")
        time.text = formatElapsed(clock.position(SystemClock.uptimeMillis()))
    }
    private fun showError(token: Int, e: Exception) {
        handler.post {
            if (!isDestroyed && token == generation) {
                pause()
                time.text = "データを再生できません: ${e.localizedMessage}"
                play.isEnabled = false
            }
        }
    }
    override fun onResume() { super.onResume(); foreground = true }
    override fun onPause() { foreground = false; if (::play.isInitialized) pause(); super.onPause() }
    override fun onDestroy() {
        generation++
        handler.removeCallbacksAndMessages(null)
        worker.execute { reader?.close(); reader = null }
        releaseVideoPlayer()
        worker.shutdown()
        super.onDestroy()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun prepareVideoPlayer() {
        if (mediaPlayer != null || sourceUri.isNullOrBlank() || !video.isAvailable) return
        runCatching {
            val surface = Surface(video.surfaceTexture)
            videoSurface = surface
            val player = MediaPlayer()
            mediaPlayer = player
            player.setDataSource(this, Uri.parse(sourceUri))
            player.setSurface(surface)
            player.isLooping = false
            player.setOnPreparedListener {
                videoPrepared = true
                player.seekTo((sourceVideoStartMs + clock.position(SystemClock.uptimeMillis())).toInt())
                if (backgroundToggle.isChecked && clock.running) player.start()
            }
            player.setOnVideoSizeChangedListener { _, _, _ -> applyVideoTransform() }
            player.setOnErrorListener { _, _, _ ->
                videoPrepared = false
                backgroundToggle.isChecked = false
                time.text = "元動画を開けません"
                true
            }
            player.prepareAsync()
        }.onFailure {
            releaseVideoPlayer()
            backgroundToggle.isChecked = false
        }
    }

    /** Letterbox the decoded movie into the same content rectangle PoseOverlay uses. */
    private fun applyVideoTransform() {
        if (video.width <= 0 || video.height <= 0 || sourceAspectRatio <= 0f) return
        val viewAspect = video.width.toFloat() / video.height
        val scaleX: Float
        val scaleY: Float
        if (viewAspect > sourceAspectRatio) {
            scaleX = sourceAspectRatio / viewAspect
            scaleY = 1f
        } else {
            scaleX = 1f
            scaleY = viewAspect / sourceAspectRatio
        }
        video.setTransform(Matrix().apply { setScale(scaleX, scaleY, video.width / 2f, video.height / 2f) })
    }

    private fun releaseVideoPlayer() {
        videoPrepared = false
        runCatching { mediaPlayer?.reset(); mediaPlayer?.release() }
        mediaPlayer = null
        runCatching { videoSurface?.release() }
        videoSurface = null
    }
}
