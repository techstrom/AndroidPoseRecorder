package jp.example.poserecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var preview: PreviewView
    private lateinit var overlay: PoseOverlay
    private lateinit var status: TextView
    private lateinit var record: IconButton
    private lateinit var folder: IconButton
    private lateinit var permissionButton: Button
    private lateinit var recorder: PoseRecorder
    private var detector: YoloPoseDetector? = null // Access only on worker.
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var lastTimestamp = -1L
    private val tracker = PoseTracker()
    @Volatile private var selectedCameraPose = -1
    @Volatile private var cameraPoses: List<List<PosePoint>> = emptyList()
    @Volatile private var cameraImageWidth = 1
    @Volatile private var cameraImageHeight = 1
    @Volatile private var active = false
    @Volatile private var failed = false
    private var ready = false
    private var changingRecording = false
    private var isRecording = false
    private var recordingStart = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val timer = object : Runnable {
        override fun run() {
            if (isRecording && active) {
                status.text = "● ${formatElapsed(SystemClock.uptimeMillis() - recordingStart)}"
                handler.postDelayed(this, 100)
            }
        }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            status.text = "カメラの使用を許可してください"
            permissionButton.visibility = android.view.View.VISIBLE
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
        recorder = PoseRecorder(File(filesDir, "recordings"))
        buildUi()
        worker.execute {
            try {
                detector = YoloPoseDetector(this)
            } catch (e: Exception) { reportFailure("姿勢検出の初期化に失敗", e) }
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        overlay = PoseOverlay(this)
        overlay.setOnPoseTapListener { index ->
            worker.execute {
                if (tracker.select(cameraPoses, index) != null) {
                    selectedCameraPose = index
                    runOnUiThread {
                        overlay.updatePoses(cameraPoses, cameraImageWidth, cameraImageHeight, index)
                        status.text = "人物${index + 1}を追跡中 · タップで変更"
                        if (!isRecording) record.isEnabled = true
                    }
                }
            }
        }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        status = TextView(this).apply {
            text = "カメラを準備しています…"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA000000.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(32) })
        val controls = FrameLayout(this).apply { setBackgroundColor(0x66000000) }
        record = IconButton(this, IconButton.Icon.RECORD).apply {
            show(IconButton.Icon.RECORD, "記録開始")
            isEnabled = false
            setOnClickListener { if (isRecording) stopRecording() else startRecording() }
        }
        folder = IconButton(this, IconButton.Icon.FOLDER).apply {
            contentDescription = "記録一覧"
            setOnClickListener { showRecordings() }
        }
        controls.addView(record, FrameLayout.LayoutParams(dp(88), dp(88), Gravity.CENTER))
        controls.addView(folder, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = dp(24) })
        permissionButton = Button(this).apply {
            text = "カメラ権限の設定"
            visibility = android.view.View.GONE
            setOnClickListener { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }
        root.addView(permissionButton, FrameLayout.LayoutParams(-1, dp(56), Gravity.TOP).apply { topMargin = dp(96) })
        root.addView(controls, FrameLayout.LayoutParams(-1, dp(104), Gravity.BOTTOM).apply { bottomMargin = dp(24) })
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            (controls.layoutParams as FrameLayout.LayoutParams).apply { bottomMargin = safe.bottom + dp(12); controls.layoutParams = this }
            (status.layoutParams as FrameLayout.LayoutParams).apply { topMargin = safe.top + dp(12); status.layoutParams = this }
            insets
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        active = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            permissionButton.visibility = android.view.View.GONE
            preview.post { startCamera() }
        } else permission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        if (!active || failed || analysis != null) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!active || failed || analysis != null) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val viewPort = preview.viewPort ?: run { preview.post { startCamera() }; return@addListener }
                val cameraPreview = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
                imageAnalysis.setAnalyzer(worker, ::analyze)
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                    UseCaseGroup.Builder().setViewPort(viewPort)
                        .addUseCase(cameraPreview).addUseCase(imageAnalysis).build())
                analysis = imageAnalysis
            } catch (e: Exception) { reportFailure("カメラを起動できません", e) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(frame: ImageProxy) {
        try {
            if (!active || failed) return
            val detector = detector ?: return
            val timestamp = maxOf(SystemClock.uptimeMillis(), lastTimestamp + 1)
            lastTimestamp = timestamp
            val original = frame.toBitmap()
            val crop = frame.cropRect
            val cropped = Bitmap.createBitmap(original, crop.left, crop.top, crop.width(), crop.height())
            val rotated = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height,
                Matrix().apply { postRotate(frame.imageInfo.rotationDegrees.toFloat()) }, true)
            try {
                val w = rotated.width
                val h = rotated.height
                val poses = detector.detect(rotated)
                val tracked = tracker.update(poses)
                selectedCameraPose = tracked
                if (isRecording) {
                    recorder.append(poses.getOrNull(tracked), timestamp, w, h)
                }
                runOnUiThread {
                    if (!active || failed) return@runOnUiThread
                    ready = true
                    cameraPoses = poses; cameraImageWidth = w; cameraImageHeight = h
                    val trackedForFrame = selectedCameraPose
                    overlay.updatePoses(poses, w, h, trackedForFrame)
                    if (!changingRecording) {
                        record.isEnabled = isRecording || selectedCameraPose >= 0
                        if (!isRecording) status.text = when {
                            poses.isEmpty() -> "人が写るようにカメラを向けてください"
                            selectedCameraPose >= 0 -> "人物${selectedCameraPose + 1}を追跡中 · 検出${poses.size}人 · タップで変更"
                            else -> "検出${poses.size}人 · 追跡する人物をタップしてください"
                        }
                    }
                }
            } finally {
                rotated.recycle()
                if (cropped !== rotated) cropped.recycle()
                if (original !== cropped && original !== rotated) original.recycle()
            }
        } catch (e: Exception) { reportFailure("検出または保存に失敗", e) }
        finally { frame.close() }
    }

    private fun startRecording() {
        if (!ready || changingRecording || isRecording || failed) return
        if (tracker.selectedIndex < 0) { status.text = "追跡する人物をタップしてください"; return }
        changingRecording = true
        record.isEnabled = false
        folder.isEnabled = false
        worker.execute {
            try {
                val start = SystemClock.uptimeMillis()
                recorder.start(start)
                runOnUiThread {
                    changingRecording = false
                    isRecording = true
                    recordingStart = start
                    record.show(IconButton.Icon.STOP, "記録停止")
                    record.isEnabled = active && tracker.selectedIndex >= 0
                    handler.post(timer)
                }
            } catch (e: Exception) { reportFailure("記録を開始できません", e) }
        }
    }

    private fun stopRecording() {
        changingRecording = true
        record.isEnabled = false
        handler.removeCallbacks(timer)
        worker.execute {
            try {
                val saved = recorder.stop()
                if (saved != null) RecordingStore(this).use { it.add(saved) }
                runOnUiThread {
                    isRecording = false
                    record.show(IconButton.Icon.RECORD, "記録開始")
                    changingRecording = false
                    record.isEnabled = active && ready && !failed
                    folder.isEnabled = true
                    if (saved != null) status.text = "保存しました"
                }
            } catch (e: Exception) { reportFailure("記録の終了に失敗", e) }
        }
    }

    private fun showRecordings() {
        if (isRecording || changingRecording) return
        startActivity(Intent(this, LibraryActivity::class.java))
    }

    private fun reportFailure(message: String, error: Exception) {
        failed = true
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            ready = false
            record.isEnabled = false
            handler.removeCallbacks(timer)
            isRecording = false
            record.show(IconButton.Icon.RECORD, "記録開始")
            folder.isEnabled = true
            status.text = "$message: ${error.localizedMessage}"
            analysis?.clearAnalyzer()
            if (!worker.isShutdown) worker.execute {
                runCatching { recorder.stop() }
            }
        }
    }

    override fun onPause() {
        active = false
        ready = false
        analysis?.clearAnalyzer()
        provider?.unbindAll()
        analysis = null
        overlay.clear()
        cameraPoses = emptyList(); selectedCameraPose = -1; tracker.reset()
        stopRecording()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        worker.execute {
            runCatching { recorder.stop() }
            detector?.close()
            detector = null
        }
        worker.shutdown()
        super.onDestroy()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
