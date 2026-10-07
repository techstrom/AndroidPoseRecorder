package jp.example.poserecorder

import kotlin.math.sqrt
import kotlin.math.abs
import kotlin.math.ln

/** Greedy nearest-centre association for the selected subject across adjacent frames. */
class PoseTracker {
    private var selected = -1
    private var previous: List<PosePoint>? = null
    private var anchorCenter: Pair<Float, Float>? = null
    private var anchorWidth = 0f
    private var anchorHeight = 0f
    private var velocityX = 0f
    private var velocityY = 0f
    private var missedFrames = 0

    val selectedIndex: Int get() = selected

    fun select(poses: List<List<PosePoint>>, index: Int): List<PosePoint>? {
        if (index !in poses.indices) return null
        selected = index
        previous = poses[index]
        anchorCenter = center(poses[index])
        anchorWidth = width(poses[index])
        anchorHeight = size(poses[index])
        velocityX = 0f
        velocityY = 0f
        missedFrames = 0
        return previous
    }

    fun reset() { clearSelection() }

    fun update(poses: List<List<PosePoint>>): Int {
        if (selected < 0) return -1
        if (poses.isEmpty()) return miss()
        val prior = previous
        if (prior == null) { clearSelection(); return -1 }
        val priorCenter = center(prior)
        val predictionSteps = (missedFrames + 1).coerceAtMost(MAX_MISSED_FRAMES + 1)
        val predictedX = priorCenter.first + velocityX * predictionSteps
        val predictedY = priorCenter.second + velocityY * predictionSteps
        val match = poses.indices.minByOrNull { index ->
            val currentCenter = center(poses[index])
            val positionCost = distance(predictedX, predictedY, currentCenter.first, currentCenter.second)
            val widthCost = abs(ln(ratio(width(prior), width(poses[index])).coerceAtLeast(0.01f)))
            val heightCost = abs(ln(ratio(size(prior), size(poses[index])).coerceAtLeast(0.01f)))
            positionCost + (widthCost + heightCost) * 0.06f
        } ?: -1
        if (match < 0) return miss()
        val current = poses[match]
        val currentCenter = center(current)
        val scale = maxOf(size(prior), size(current), 0.08f)
        val predictionError = distance(predictedX, predictedY, currentCenter.first, currentCenter.second)
        val previousWidth = width(prior)
        val currentWidth = width(current)
        val widthRatio = if (previousWidth > 0.025f && currentWidth > 0f)
            currentWidth / previousWidth else 1f
        val origin = anchorCenter
        val anchorMovementX = if (origin == null) Float.POSITIVE_INFINITY else abs(origin.first - currentCenter.first)
        val anchorMovementY = if (origin == null) Float.POSITIVE_INFINITY else abs(origin.second - currentCenter.second)
        val anchorWidthRatio = ratio(anchorWidth, width(current))
        val anchorHeightRatio = ratio(anchorHeight, size(current))
        // Predict fast jumps from recent motion. Keep the original horizontal anchor
        // tighter than the vertical one because jumps can move the torso by half a
        // frame while nearby people usually remain at a different x position.
        if (predictionError > maxOf(0.46f, scale * 1.10f) ||
            widthRatio !in 0.42f..2.25f || anchorMovementX > 0.42f || anchorMovementY > 0.88f ||
            anchorWidthRatio !in 0.35f..2.8f || anchorHeightRatio !in 0.35f..2.8f) {
            return miss()
        }
        val stepX = currentCenter.first - priorCenter.first
        val stepY = currentCenter.second - priorCenter.second
        velocityX = (velocityX * 0.55f + stepX * 0.45f).coerceIn(-0.40f, 0.40f)
        velocityY = (velocityY * 0.55f + stepY * 0.45f).coerceIn(-0.45f, 0.45f)
        selected = match
        previous = current
        missedFrames = 0
        return match
    }

    private fun miss(): Int {
        missedFrames++
        if (missedFrames > MAX_MISSED_FRAMES) clearSelection()
        return -1
    }

    private fun center(points: List<PosePoint>): Pair<Float, Float> {
        val anchors = listOf(11, 12, 23, 24).filter { it < points.size && points[it].visibility >= 0.25f }
        val visible = if (anchors.isNotEmpty()) anchors.map(points::get) else points.filter { it.visibility >= 0.25f }
        if (visible.isEmpty()) return 0f to 0f
        return visible.map { it.x }.average().toFloat() to visible.map { it.y }.average().toFloat()
    }

    private fun size(points: List<PosePoint>): Float {
        val visible = points.filter { it.visibility >= 0.25f }
        if (visible.isEmpty()) return 0f
        return visible.maxOf { it.y } - visible.minOf { it.y }
    }

    private fun width(points: List<PosePoint>): Float {
        val visible = points.filter { it.visibility >= 0.25f }
        if (visible.isEmpty()) return 0f
        return visible.maxOf { it.x } - visible.minOf { it.x }
    }

    private fun ratio(reference: Float, value: Float) = if (reference > 0.025f && value > 0f) value / reference else 1f

    private fun clearSelection() {
        selected = -1; previous = null; anchorCenter = null; anchorWidth = 0f; anchorHeight = 0f
        velocityX = 0f; velocityY = 0f; missedFrames = 0
    }

    private fun distance(ax: Float, ay: Float, bx: Float, by: Float) = sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by))

    private companion object { const val MAX_MISSED_FRAMES = 5 }
}
