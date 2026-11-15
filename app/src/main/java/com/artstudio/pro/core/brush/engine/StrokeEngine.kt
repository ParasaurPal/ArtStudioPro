package com.artstudio.pro.core.brush.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.artstudio.pro.core.brush.model.BrushDefinition
import kotlin.math.hypot

data class StrokePoint(val x: Float, val y: Float, val pressure: Float)

class StrokeEngine(var brush: BrushDefinition, var activeColor: Int = Color.BLACK) {
    var eraserMode: Boolean = false
    private var lastX = 0f
    private var lastY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var lastPressure = 1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun startStroke(point: StrokePoint, canvas: Canvas) {
        lastX = point.x
        lastY = point.y
        targetX = point.x
        targetY = point.y
        lastPressure = point.pressure
        renderStamp(canvas, point.x, point.y, point.pressure)
    }

    fun continueStroke(point: StrokePoint, canvas: Canvas) {
        targetX = point.x
        targetY = point.y
        lastPressure = point.pressure
        val smoothing = brush.streamline.coerceIn(0f, 0.95f)
        val factor = 1f - smoothing
        val currentX = lastX + (point.x - lastX) * factor
        val currentY = lastY + (point.y - lastY) * factor
        drawSegment(canvas, lastX, lastY, currentX, currentY, point.pressure)
        lastX = currentX
        lastY = currentY
    }

    /** Flushes the smoothed tail so a stroke ends exactly at the final input point. */
    fun finishStroke(canvas: Canvas) {
        drawSegment(canvas, lastX, lastY, targetX, targetY, lastPressure)
        lastX = targetX
        lastY = targetY
    }

    private fun drawSegment(
        canvas: Canvas,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        pressure: Float
    ) {
        val dx = toX - fromX
        val dy = toY - fromY
        val distance = hypot(dx, dy)
        if (distance <= 0.001f) return
        val spacing = brush.spacing.coerceIn(0.01f, 2f)
        val step = (brush.baseSize * spacing).coerceAtLeast(1f)
        var travelled = step
        while (travelled < distance) {
            val t = travelled / distance
            renderStamp(canvas, fromX + dx * t, fromY + dy * t, pressure)
            travelled += step
        }
        renderStamp(canvas, toX, toY, pressure)
    }

    private fun renderStamp(canvas: Canvas, x: Float, y: Float, pressure: Float) {
        val normalizedPressure = pressure.coerceIn(0f, 1f)
        val pressureSize = brush.sizeCurve.evaluate(normalizedPressure)
        val diameter = brush.baseSize.coerceAtLeast(1f) *
            (brush.minSizeRatio.coerceIn(0f, 1f) +
                (1f - brush.minSizeRatio.coerceIn(0f, 1f)) * pressureSize)
        paint.color = if (eraserMode) Color.WHITE else activeColor
        paint.alpha = (255f * brush.baseOpacity.coerceIn(0f, 1f) *
            brush.flow.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        canvas.drawCircle(x, y, diameter / 2f, paint)
    }
}
