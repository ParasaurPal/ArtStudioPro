package com.artstudio.pro.core.brush.engine
import android.graphics.*
import com.artstudio.pro.core.brush.model.*
import kotlin.math.*

data class StrokePoint(val x: Float, val y: Float, val pressure: Float)

class StrokeEngine(var brush: BrushDefinition, var activeColor: Int = Color.BLACK) {
    private var lastX = 0f
    private var lastY = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun startStroke(p: StrokePoint, canvas: Canvas) {
        lastX = p.x; lastY = p.y
        renderStamp(canvas, p.x, p.y, p.pressure)
    }

    fun continueStroke(p: StrokePoint, canvas: Canvas) {
        val f = 1f - brush.streamline.coerceIn(0f, 0.95f)
        val curX = lastX + (p.x - lastX) * f
        val curY = lastY + (p.y - lastY) * f
        val dist = hypot(curX - lastX, curY - lastY)
        val step = (brush.baseSize * brush.spacing).coerceAtLeast(1f)
        var d = 0f
        while (d < dist) {
            val t = d / dist
            renderStamp(canvas, lastX + (curX - lastX) * t, lastY + (curY - lastY) * t, p.pressure)
            d += step
        }
        lastX = curX; lastY = curY
    }

    private fun renderStamp(canvas: Canvas, x: Float, y: Float, pressure: Float) {
        val r = brush.baseSize * (brush.minSizeRatio + (1f - brush.minSizeRatio) * brush.sizeCurve.evaluate(pressure))
        paint.color = activeColor
        paint.alpha = (255 * brush.baseOpacity * brush.flow).toInt().coerceIn(1, 255)
        canvas.drawCircle(x, y, r / 2f, paint)
    }
}
