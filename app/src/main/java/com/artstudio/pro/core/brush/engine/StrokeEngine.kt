package com.artstudio.pro.core.brush.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.artstudio.pro.core.brush.model.BrushDefinition
import kotlin.math.hypot
import kotlin.math.abs

data class StrokePoint(val x: Float, val y: Float, val pressure: Float)

class StrokeEngine(var brush: BrushDefinition, var activeColor: Int = Color.BLACK) {
    var eraserMode: Boolean = false
    private var lastX = 0f
    private var lastY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var lastPressure = 1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var stampIndex = 0

    private fun noise(seed: Float): Float {
        val value = kotlin.math.sin(seed * 12.9898f + stampIndex * 78.233f) * 43758.5453f
        return (value - kotlin.math.floor(value)).toFloat()
    }

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
        val radius = diameter / 2f
        val flowAlpha = (255f * brush.baseOpacity.coerceIn(0f, 1f) *
            brush.flow.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        paint.color = if (eraserMode) Color.WHITE else activeColor
        paint.alpha = when (brush.medium) {
            com.artstudio.pro.core.brush.model.BrushMedium.WATERCOLOR -> (flowAlpha * 0.22f).toInt()
            com.artstudio.pro.core.brush.model.BrushMedium.GRAPHITE -> (flowAlpha * 0.78f).toInt()
            else -> flowAlpha
        }

        when {
            eraserMode -> canvas.drawCircle(x, y, radius, paint)
            brush.medium == com.artstudio.pro.core.brush.model.BrushMedium.WATERCOLOR -> {
                // Layered translucent washes with a slightly darker pigment-rich edge.
                canvas.drawCircle(x, y, radius, paint)
                detailPaint.color = activeColor
                detailPaint.alpha = (flowAlpha * 0.18f).toInt().coerceIn(0, 255)
                detailPaint.style = Paint.Style.STROKE
                detailPaint.strokeWidth = (diameter * 0.10f).coerceAtLeast(1f)
                canvas.drawCircle(x, y, radius * 0.88f, detailPaint)
                detailPaint.style = Paint.Style.FILL
            }
            brush.medium == com.artstudio.pro.core.brush.model.BrushMedium.OIL_IMPASTO -> {
                canvas.drawCircle(x, y, radius, paint)
                detailPaint.color = activeColor
                detailPaint.alpha = (flowAlpha * 0.42f).toInt().coerceIn(0, 255)
                detailPaint.style = Paint.Style.STROKE
                detailPaint.strokeCap = Paint.Cap.ROUND
                detailPaint.strokeWidth = (diameter * 0.07f).coerceAtLeast(1f)
                repeat(3) { i ->
                    val offset = (i - 1) * radius * 0.28f
                    canvas.drawLine(x - radius * 0.48f, y + offset, x + radius * 0.48f, y + offset, detailPaint)
                }
                detailPaint.style = Paint.Style.FILL
            }
            brush.medium == com.artstudio.pro.core.brush.model.BrushMedium.GRAPHITE ||
                brush.medium == com.artstudio.pro.core.brush.model.BrushMedium.CHARCOAL -> {
                canvas.drawCircle(x, y, radius, paint)
                detailPaint.color = activeColor
                val flecks = if (brush.medium == com.artstudio.pro.core.brush.model.BrushMedium.CHARCOAL) 10 else 5
                repeat(flecks) { i ->
                    val angle = noise(x + i * 19.7f) * (Math.PI.toFloat() * 2f)
                    val distance = noise(y + i * 31.3f) * radius
                    val px = x + kotlin.math.cos(angle) * distance
                    val py = y + kotlin.math.sin(angle) * distance
                    detailPaint.alpha = (flowAlpha * (0.12f + noise(px + py) * 0.28f)).toInt().coerceIn(0, 255)
                    val dot = (diameter * (0.012f + noise(px - py) * 0.025f)).coerceAtLeast(0.5f)
                    canvas.drawCircle(px, py, dot, detailPaint)
                }
            }
            else -> canvas.drawCircle(x, y, radius, paint)
        }
        stampIndex++
    }
}
