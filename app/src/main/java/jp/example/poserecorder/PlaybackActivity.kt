package jp.example.poserecorder

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    private lateinit var file: File
    private var reader: BufferedReader? = null // Worker thread only.
    private var pending: PoseFrame? = null // Main thread only.
    private var ended = false
    private var lastFrameMs = 0L
    private var generation = 0
    private var foreground = false
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
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        overlay = PoseOverlay(this).apply { fitInside = true }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        time = TextView(this).apply { text = "00:00:00"; textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        root.addView(time, FrameLayout.LayoutParams(-1, dp(60), Gravity.TOP))
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
        overlay.update(null)
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
        play.show(IconButton.Icon.PAUSE, "一時停止")
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        schedule()
    }
    private fun pause() {
        clock.pause(SystemClock.uptimeMillis())
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
        worker.shutdown()
        super.onDestroy()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
