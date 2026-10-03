package jp.example.poserecorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

class IconButton(context: Context, var icon: Icon) : View(context) {
    enum class Icon { RECORD, STOP, FOLDER, PAUSE, PLAY, BACK }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { isClickable = true; isFocusable = true; setBackgroundResource(android.R.drawable.list_selector_background) }
    fun show(value: Icon, label: String) { icon = value; contentDescription = label; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        val scale = minOf(width, height) / 80f
        canvas.scale(scale, scale)
        paint.alpha = if (isEnabled) 255 else 90
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        when (icon) {
            Icon.RECORD, Icon.STOP -> {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
                canvas.drawCircle(0f, 0f, 31f, paint)
                paint.style = Paint.Style.FILL; paint.color = Color.rgb(255, 58, 66)
                if (icon == Icon.RECORD) canvas.drawCircle(0f, 0f, 25f, paint)
                else canvas.drawRoundRect(RectF(-13f, -13f, 13f, 13f), 3f, 3f, paint)
            }
            Icon.FOLDER -> {
                val path = Path().apply {
                    moveTo(-23f, -13f); lineTo(-5f, -13f); lineTo(0f, -7f)
                    lineTo(23f, -7f); lineTo(23f, 18f); lineTo(-23f, 18f); close()
                }
                canvas.drawPath(path, paint)
            }
            Icon.PAUSE -> { canvas.drawRect(-13f, -19f, -4f, 19f, paint); canvas.drawRect(4f, -19f, 13f, 19f, paint) }
            Icon.PLAY -> canvas.drawPath(Path().apply { moveTo(-12f, -21f); lineTo(20f, 0f); lineTo(-12f, 21f); close() }, paint)
            Icon.BACK -> {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f
                canvas.drawPath(Path().apply { moveTo(5f, -20f); lineTo(-15f, 0f); lineTo(5f, 20f); moveTo(-15f, 0f); lineTo(23f, 0f) }, paint)
            }
        }
        canvas.restore()
    }
}
