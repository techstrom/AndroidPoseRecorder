package jp.example.poserecorder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/** YOLOv8n-pose ONNX detector. One full-frame inference returns multiple people. */
class YoloPoseDetector(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val side = 640
    private val letterbox = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(letterbox)
    private val destination = RectF()
    private val pixels = IntArray(side * side)
    private val input = FloatArray(3 * side * side)

    init {
        val modelFile = File(context.filesDir, "yolov8n-pose.onnx")
        if (!modelFile.exists() || modelFile.length() < 1_000_000L) {
            context.assets.open("yolov8n-pose.onnx").use { source -> modelFile.outputStream().use(source::copyTo) }
        }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(modelFile.absolutePath, options)
        inputName = session.inputNames.first()
    }

    fun detect(image: Bitmap, confidenceThreshold: Float = 0.20f): List<List<PosePoint>> {
        val scale = min(side.toFloat() / image.width, side.toFloat() / image.height)
        val renderedWidth = image.width * scale
        val renderedHeight = image.height * scale
        val padX = (side - renderedWidth) / 2f
        val padY = (side - renderedHeight) / 2f
        canvas.drawColor(Color.rgb(114, 114, 114))
        destination.set(padX, padY, padX + renderedWidth, padY + renderedHeight)
        canvas.drawBitmap(image, null, destination, null)
        letterbox.getPixels(pixels, 0, side, 0, 0, side, side)
        val plane = side * side
        for (index in pixels.indices) {
            val pixel = pixels[index]
            input[index] = Color.red(pixel) / 255f
            input[plane + index] = Color.green(pixel) / 255f
            input[plane * 2 + index] = Color.blue(pixel) / 255f
        }

        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, side.toLong(), side.toLong()))
        val result = session.run(mapOf(inputName to tensor))
        val candidates = try { parseOutput(result[0].value, image.width, image.height, scale, padX, padY, confidenceThreshold) }
            finally { result.close(); tensor.close() }
        return nonMaximumSuppression(candidates).map { PoseLandmarkMapping.coco17ToPose33(it.points) }
    }

    private data class Detection(val points: List<PosePoint>, val left: Float, val top: Float,
                                 val right: Float, val bottom: Float, val confidence: Float)

    private fun parseOutput(value: Any, imageWidth: Int, imageHeight: Int, scale: Float,
                            padX: Float, padY: Float, threshold: Float): List<Detection> {
        val batch = value as? Array<*> ?: return emptyList()
        val channels = batch.firstOrNull() as? Array<*> ?: return emptyList()
        if (channels.size < 56) return emptyList()
        val numPredictions = (channels[0] as? FloatArray)?.size ?: return emptyList()
        fun at(channel: Int, prediction: Int) = (channels[channel] as FloatArray)[prediction]
        val detections = ArrayList<Detection>()
        for (index in 0 until numPredictions) {
            val confidence = at(4, index)
            if (confidence < threshold) continue
            val centerX = (at(0, index) - padX) / scale
            val centerY = (at(1, index) - padY) / scale
            val halfWidth = at(2, index) / (2f * scale)
            val halfHeight = at(3, index) / (2f * scale)
            val left = (centerX - halfWidth).coerceIn(0f, imageWidth.toFloat())
            val top = (centerY - halfHeight).coerceIn(0f, imageHeight.toFloat())
            val right = (centerX + halfWidth).coerceIn(0f, imageWidth.toFloat())
            val bottom = (centerY + halfHeight).coerceIn(0f, imageHeight.toFloat())
            if (right <= left || bottom <= top) continue
            val points = List(17) { pointIndex ->
                val x = ((at(5 + pointIndex * 3, index) - padX) / scale / imageWidth).coerceIn(0f, 1f)
                val y = ((at(6 + pointIndex * 3, index) - padY) / scale / imageHeight).coerceIn(0f, 1f)
                PosePoint(x, y, at(7 + pointIndex * 3, index).coerceIn(0f, 1f))
            }
            detections += Detection(points, left, top, right, bottom, confidence)
        }
        return detections.sortedByDescending { it.confidence }.take(100)
    }

    private fun nonMaximumSuppression(input: List<Detection>): List<Detection> {
        val kept = mutableListOf<Detection>()
        for (candidate in input) {
            if (kept.none { iou(it, candidate) > 0.45f }) kept += candidate
            if (kept.size >= 20) break
        }
        return kept
    }

    private fun iou(a: Detection, b: Detection): Float {
        val width = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val height = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val intersection = width * height
        val union = (a.right - a.left) * (a.bottom - a.top) +
            (b.right - b.left) * (b.bottom - b.top) - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    override fun close() { session.close(); letterbox.recycle() }
}
