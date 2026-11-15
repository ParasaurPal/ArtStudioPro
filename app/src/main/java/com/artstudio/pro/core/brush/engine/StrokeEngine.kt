package com.artstudio.pro.core.brush.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.artstudio.pro.core.brush.model.BrushDefinition
import kotlin.math.hypot
import kotlin.math.sin

data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val tiltRadians: Float = 0f,
    val orientationRadians: Float = 0f,
    val timeMillis: Long = 0L
)

class StrokeEngine(var brush: BrushDefinition, var activeColor: Int = Color.BLACK) {
    var eraserMode: Boolean = false
    var symmetryVertical: Boolean = false
    var symmetryHorizontal: Boolean = false
    var symmetryCenterX: Float = 0f
    var symmetryCenterY: Float = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var lastPressure = 1f
    private var lastTilt = 0f
    private var lastOrientation = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun startStroke(point: StrokePoint, canvas: Canvas) {
        lastX = point.x
        lastY = point.y
        targetX = point.x
        targetY = point.y
        lastPressure = point.pressure
        lastTilt = point.tiltRadians
        lastOrientation = point.orientationRadians
        renderStamp(canvas, point.x, point.y, point.pressure, point.tiltRadians, point.orientationRadians)
    }

    fun continueStroke(point: StrokePoint, canvas: Canvas) {
        targetX = point.x
        targetY = point.y
        lastPressure = point.pressure
        lastTilt = point.tiltRadians
        lastOrientation = point.orientationRadians
        val smoothing = brush.streamline.coerceIn(0f, 0.95f)
        val factor = 1f - smoothing
        val currentX = lastX + (point.x - lastX) * factor
        val currentY = lastY + (point.y - lastY) * factor
        drawSegment(canvas, lastX, lastY, currentX, currentY, point.pressure, point.tiltRadians, point.orientationRadians)
        lastX = currentX
        lastY = currentY
    }

    fun finishStroke(canvas: Canvas) {
        drawSegment(canvas, lastX, lastY, targetX, targetY, lastPressure, lastTilt, lastOrientation)
        lastX = targetX
        lastY = targetY
    }

    private fun drawSegment(
        canvas: Canvas,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        pressure: Float,
        tilt: Float,
        orientation: Float
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
            renderStamp(canvas, fromX + dx * t, fromY + dy * t, pressure, tilt, orientation)
            travelled += step
        }
        renderStamp(canvas, toX, toY, pressure, tilt, orientation)
    }

    private fun renderStamp(
        canvas: Canvas,
        x: Float,
        y: Float,
        pressure: Float,
        tiltRadians: Float,
        orientationRadians: Float
    ) {
        val normalizedPressure = pressure.coerceIn(0f, 1f)
        val pressureSize = brush.sizeCurve.evaluate(normalizedPressure)
        val diameter = brush.baseSize.coerceAtLeast(1f) *
            (brush.minSizeRatio.coerceIn(0f, 1f) +
                (1f - brush.minSizeRatio.coerceIn(0f, 1f)) * pressureSize)
        paint.color = if (eraserMode) Color.WHITE else activeColor
        paint.alpha = (255f * brush.baseOpacity.coerceIn(0f, 1f) *
            brush.flow.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)

        val tilt = tiltRadians.coerceIn(0f, 1.5708f)
        val radiusX = diameter / 2f
        val radiusY = radiusX * (1f - sin(tilt) * 0.65f).coerceIn(0.35f, 1f)
        val points = linkedSetOf<Pair<Float, Float>>()
        points.add(x to y)
        if (symmetryVertical) points.add((2f * symmetryCenterX - x) to y)
        if (symmetryHorizontal) points.add(x to (2f * symmetryCenterY - y))
        if (symmetryVertical && symmetryHorizontal) {
            points.add((2f * symmetryCenterX - x) to (2f * symmetryCenterY - y))
        }
        points.forEach { (px, py) ->
            val oval = RectF(px - radiusX, py - radiusY, px + radiusX, py + radiusY)
            if (orientationRadians == 0f || radiusX == radiusY) {
                canvas.drawOval(oval, paint)
            } else {
                val save = canvas.save()
                canvas.rotate(Math.toDegrees(orientationRadians.toDouble()).toFloat(), px, py)
                canvas.drawOval(oval, paint)
                canvas.restoreToCount(save)
            }
        }
    }
}
