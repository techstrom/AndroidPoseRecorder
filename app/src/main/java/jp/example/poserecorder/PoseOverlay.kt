package jp.example.poserecorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlin.math.max

class PoseOverlay(context: Context) : View(context) {
    private var result: PoseLandmarkerResult? = null
    private var imageWidth = 1
    private var imageHeight = 1
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(66, 255, 186)
        strokeWidth = resources.displayMetrics.density * 2.5f
    }
    fun update(value: PoseLandmarkerResult?, width: Int = 1, height: Int = 1) {
        result = value
        imageWidth = width
        imageHeight = height
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val dx = (width - imageWidth * scale) / 2f
        val dy = (height - imageHeight * scale) / 2f
        result?.landmarks()?.forEach { points ->
            fun visible(i: Int) = points[i].visibility().orElse(0f) >= 0.5f
            fun x(i: Int) = points[i].x() * imageWidth * scale + dx
            fun y(i: Int) = points[i].y() * imageHeight * scale + dy
            connections.forEach { (a, b) ->
                if (a < points.size && b < points.size && visible(a) && visible(b))
                    canvas.drawLine(x(a), y(a), x(b), y(b), paint)
            }
            points.indices.filter { visible(it) }.forEach {
                canvas.drawCircle(x(it), y(it), resources.displayMetrics.density * 4, paint)
            }
        }
    }
    private val connections = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 7, 0 to 4, 4 to 5, 5 to 6, 6 to 8, 9 to 10,
        11 to 12, 11 to 13, 13 to 15, 15 to 17, 15 to 19, 15 to 21, 17 to 19,
        12 to 14, 14 to 16, 16 to 18, 16 to 20, 16 to 22, 18 to 20,
        11 to 23, 12 to 24, 23 to 24, 23 to 25, 24 to 26, 25 to 27, 26 to 28,
        27 to 29, 28 to 30, 29 to 31, 30 to 32, 27 to 31, 28 to 32)
}
