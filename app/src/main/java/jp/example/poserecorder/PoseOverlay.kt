package jp.example.poserecorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PoseOverlay(context: Context) : View(context) {
    private var poses: List<List<PosePoint>> = emptyList()
    var fitInside = false
    private var imageWidth = 1
    private var imageHeight = 1
    private var selectedPose = -1
    private var tapListener: ((Int) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(66, 255, 186)
        strokeWidth = resources.displayMetrics.density * 2.5f
    }
    fun clear() { updatePoses(emptyList(), 1, 1, -1) }
    fun update(frame: PoseFrame) {
        poses = frame.poses
        imageWidth = frame.width
        imageHeight = frame.height
        selectedPose = frame.selectedPose
        invalidate()
    }
    fun updatePoses(value: List<List<PosePoint>>, width: Int, height: Int, selected: Int) {
        poses = value
        imageWidth = width
        imageHeight = height
        selectedPose = selected
        invalidate()
    }
    fun setOnPoseTapListener(listener: ((Int) -> Unit)?) { tapListener = listener }
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action != android.view.MotionEvent.ACTION_UP) return true
        val scale = if (fitInside) min(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
            else max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val dx = (width - imageWidth * scale) / 2f
        val dy = (height - imageHeight * scale) / 2f
        val hit = poses.indices.mapNotNull { index ->
            val visible = poses[index].filter { it.visibility >= 0.35f }
            if (visible.isEmpty()) return@mapNotNull null
            val minX = visible.minOf { it.x } * imageWidth * scale + dx
            val maxX = visible.maxOf { it.x } * imageWidth * scale + dx
            val minY = visible.minOf { it.y } * imageHeight * scale + dy
            val maxY = visible.maxOf { it.y } * imageHeight * scale + dy
            val pad = resources.displayMetrics.density * 32f
            if (event.x in (minX - pad)..(maxX + pad) && event.y in (minY - pad)..(maxY + pad)) {
                val cx = (minX + maxX) / 2f; val cy = (minY + maxY) / 2f
                index to (abs(event.x - cx) + abs(event.y - cy))
            } else null
        }.minByOrNull { it.second }?.first
        if (hit != null) { tapListener?.invoke(hit); return true }
        return poses.isNotEmpty()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = if (fitInside) min(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
            else max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val dx = (width - imageWidth * scale) / 2f
        val dy = (height - imageHeight * scale) / 2f
        poses.forEachIndexed { poseIndex, points ->
            paint.color = if (poseIndex == selectedPose) Color.rgb(255, 64, 64) else Color.rgb(66, 255, 186)
            fun visible(i: Int) = points[i].visibility >= 0.5f
            fun x(i: Int) = points[i].x * imageWidth * scale + dx
            fun y(i: Int) = points[i].y * imageHeight * scale + dy
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
