package jp.example.poserecorder

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.app.AlertDialog
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
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
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
    private var landmarker: PoseLandmarker? = null // Access only on worker.
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var lastTimestamp = -1L
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
                landmarker = PoseLandmarker.createFromOptions(this,
                    PoseLandmarker.PoseLandmarkerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").build())
                        // Synchronous inference on a background thread. CameraX drops stale frames.
                        .setRunningMode(RunningMode.VIDEO).setNumPoses(1)
                        .setMinPoseDetectionConfidence(0.5f).setMinPosePresenceConfidence(0.5f)
                        .setMinTrackingConfidence(0.5f).build())
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
            val detector = landmarker ?: return
            val timestamp = maxOf(SystemClock.uptimeMillis(), lastTimestamp + 1)
            lastTimestamp = timestamp
            val original = frame.toBitmap()
            val crop = frame.cropRect
            val cropped = Bitmap.createBitmap(original, crop.left, crop.top, crop.width(), crop.height())
            val rotated = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height,
                Matrix().apply { postRotate(frame.imageInfo.rotationDegrees.toFloat()) }, true)
            try {
                val image = BitmapImageBuilder(rotated).build()
                val result = try { detector.detectForVideo(image, timestamp) } finally { image.close() }
                recorder.append(result, timestamp, rotated.width, rotated.height)
                val w = rotated.width
                val h = rotated.height
                runOnUiThread {
                    if (!active || failed) return@runOnUiThread
                    ready = true
                    overlay.update(result, w, h)
                    if (!changingRecording) {
                        record.isEnabled = true
                        if (!isRecording) status.text =
                            if (result.landmarks().isEmpty()) "人が写るようにカメラを向けてください" else "姿勢を検出中 · 33点"
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
                    record.isEnabled = active
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
        val files = File(filesDir, "recordings").listFiles()?.filter { it.extension == "jsonl" }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        if (files.isEmpty()) {
            AlertDialog.Builder(this).setTitle("記録一覧").setMessage("記録したデータはありません")
                .setPositiveButton("閉じる", null).show()
            return
        }
        val date = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.JAPAN)
        val labels = files.map { "${date.format(java.util.Date(it.lastModified()))}  ·  ${it.length() / 1024} KB" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("記録一覧").setItems(labels) { _, index ->
            val file = files[index]
            AlertDialog.Builder(this).setTitle(labels[index]).setItems(arrayOf("再生", "共有")) { _, action ->
                if (action == 0) startActivity(Intent(this, PlaybackActivity::class.java).putExtra("recording_name", file.name))
                else shareFile(file)
            }.setNegativeButton("戻る", { _, _ -> showRecordings() }).show()
        }.setNegativeButton("閉じる", null).show()
    }

    private fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/x-ndjson"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("姿勢データ", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "姿勢データを共有"))
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
        overlay.update(null)
        stopRecording()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        worker.execute {
            runCatching { recorder.stop() }
            landmarker?.close()
            landmarker = null
        }
        worker.shutdown()
        super.onDestroy()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
