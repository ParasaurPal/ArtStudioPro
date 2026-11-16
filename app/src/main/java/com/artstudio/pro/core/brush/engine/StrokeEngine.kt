package com.artstudio.pro.core.brush.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import com.artstudio.pro.core.brush.model.BrushDefinition
import com.artstudio.pro.core.brush.model.DynamicsSource
import com.artstudio.pro.core.brush.model.BrushMedium
import com.artstudio.pro.core.paint.PigmentMixer
import kotlin.math.hypot

data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val tilt: Float = 0f,
    val orientation: Float = 0f,
    val velocity: Float = 0f
)

class StrokeEngine(var brush: BrushDefinition, var activeColor: Int = Color.BLACK) {
    var eraserMode: Boolean = false
    private var lastX = 0f
    private var lastY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var lastPressure = 1f
    private var currentTilt = 0f
    private var currentOrientation = 0f
    private var currentVelocity = 0f
    private var currentStrokeAngle = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var stampIndex = 0
    private var previousPigmentColor = activeColor

    private fun noise(seed: Float): Float {
        val value = kotlin.math.sin(seed * 12.9898f + stampIndex * 78.233f) * 43758.5453f
        return value - kotlin.math.floor(value)
    }

    private fun applyPointDynamics(point: StrokePoint) {
        currentTilt = point.tilt.coerceIn(0f, Math.PI.toFloat())
        currentOrientation = point.orientation
        currentVelocity = point.velocity.coerceIn(0f, 1f)
    }

    fun startStroke(point: StrokePoint, canvas: Canvas) {
        lastX = point.x
        lastY = point.y
        targetX = point.x
        targetY = point.y
        lastPressure = point.pressure
        currentStrokeAngle = 0f
        previousPigmentColor = activeColor
        stampIndex = 0
        applyPointDynamics(point)
        renderStamp(canvas, point.x, point.y, point.pressure)
    }

    fun continueStroke(point: StrokePoint, canvas: Canvas) {
        targetX = point.x
        targetY = point.y
        lastPressure = point.pressure
        applyPointDynamics(point)
        currentStrokeAngle = kotlin.math.atan2(point.y - lastY, point.x - lastX)
        val smoothing = maxOf(brush.streamline, brush.parameter(com.artstudio.pro.core.brush.model.BrushParameter.STROKE_SMOOTHING), brush.parameter(com.artstudio.pro.core.brush.model.BrushParameter.STABILIZATION_AMOUNT)).coerceIn(0f, 0.95f)
        val factor = 1f - smoothing
        val currentX = lastX + (point.x - lastX) * factor
        val currentY = lastY + (point.y - lastY) * factor
        drawSegment(canvas, lastX, lastY, currentX, currentY, point.pressure)
        lastX = currentX
        lastY = currentY
    }

    fun finishStroke(canvas: Canvas) {
        drawSegment(canvas, lastX, lastY, targetX, targetY, lastPressure)
        lastX = targetX
        lastY = targetY
    }

    private fun drawSegment(canvas: Canvas, fromX: Float, fromY: Float, toX: Float, toY: Float, pressure: Float) {
        val dx = toX - fromX
        val dy = toY - fromY
        val distance = hypot(dx, dy)
        if (distance <= 0.001f) return
        val step = (brush.baseSize * brush.spacing.coerceIn(0.01f, 2f)).coerceAtLeast(1f)
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
        val dynamicsValue = when (brush.sizeSource) {
            DynamicsSource.PRESSURE -> normalizedPressure
            DynamicsSource.TILT -> 1f - currentTilt / Math.PI.toFloat()
            DynamicsSource.VELOCITY -> 1f - currentVelocity
            DynamicsSource.FIXED -> 1f
        }.coerceIn(0f, 1f)
        val pressureSize = brush.parameter(com.artstudio.pro.core.brush.model.BrushParameter.PRESSURE_SIZE)
        val sizeFactor = (brush.sizeCurve.evaluate(dynamicsValue) * (1f - pressureSize + pressureSize * normalizedPressure)).coerceIn(0f,1f)
        val minRatio = brush.minSizeRatio.coerceIn(0f, 1f)
        val sizeJitter = (noise(x * 0.73f + y * 0.37f) - 0.5f) * 2f * brush.sizeJitter.coerceIn(0f, 1f)
        val diameter = brush.baseSize.coerceAtLeast(1f) * (minRatio + (1f - minRatio) * sizeFactor) * (1f + sizeJitter)
        val radius = diameter / 2f
        val scatterAngle = noise(x * 1.17f + y * 2.31f) * (Math.PI.toFloat() * 2f)
        val scatterDistance = radius * (brush.scatter + brush.parameter(com.artstudio.pro.core.brush.model.BrushParameter.SCATTER_AMOUNT) * 2f).coerceIn(0f, 2f)
        val drawX = x + kotlin.math.cos(scatterAngle) * scatterDistance
        val drawY = y + kotlin.math.sin(scatterAngle) * scatterDistance
        val rotationBase = when (brush.angleSource) {
            DynamicsSource.PRESSURE -> currentStrokeAngle * normalizedPressure
            DynamicsSource.TILT -> currentOrientation + currentTilt
            DynamicsSource.VELOCITY -> currentStrokeAngle + currentVelocity * Math.PI.toFloat()
            DynamicsSource.FIXED -> if (brush.angleFollowsStroke) currentStrokeAngle else currentOrientation
        }
        val rotationNoise = (noise(x + y + 17f) - 0.5f) * 2f * brush.rotationJitter.coerceIn(0f, 1f) * Math.PI.toFloat()
        val stampRotation = rotationBase + rotationNoise
        val opacityDynamics = when (brush.opacitySource) {
            DynamicsSource.PRESSURE -> normalizedPressure
            DynamicsSource.TILT -> 1f - currentTilt / Math.PI.toFloat()
            DynamicsSource.VELOCITY -> 1f - currentVelocity
            DynamicsSource.FIXED -> 1f
        }.coerceIn(0f, 1f)
        val flowDynamics = when (brush.flowSource) {
            DynamicsSource.PRESSURE -> normalizedPressure
            DynamicsSource.TILT -> 1f - currentTilt / Math.PI.toFloat()
            DynamicsSource.VELOCITY -> 1f - currentVelocity
            DynamicsSource.FIXED -> 1f
        }.coerceIn(0f, 1f)
        val flowFactor = brush.flowCurve.evaluate(flowDynamics)
        val opacityFactor = brush.opacityCurve.evaluate(opacityDynamics)
        val pressureOpacity = brush.parameter(com.artstudio.pro.core.brush.model.BrushParameter.PRESSURE_OPACITY)
        val pressureFlow = brush.parameter(com.artstudio.pro.core.brush.model.BrushParameter.PRESSURE_FLOW)
        val flowAlpha = (255f * brush.baseOpacity.coerceIn(0f, 1f) *
            brush.flow.coerceIn(0f, 1f) * flowFactor * opacityFactor *
            (1f - pressureOpacity + pressureOpacity * normalizedPressure) *
            (1f - pressureFlow + pressureFlow * normalizedPressure)).toInt().coerceIn(0, 255)
        val wetness = maxOf(brush.wetness, brush.parameter(com.artstudio.pro.core.brush.model.BrushParameter.WETNESS))
        var pigmentColor = if ((brush.wetMixing || brush.parameter(com.artstudio.pro.core.brush.model.BrushParameter.WET_MIXING) > 0f) && wetness > 0f) {
            PigmentMixer.mixPigments(previousPigmentColor, activeColor, (wetness * 0.18f).coerceIn(0f, 1f)).also {
                previousPigmentColor = it
            }
        } else activeColor
        val hueJitter = brush.parameter(com.artstudio.pro.core.brush.model.BrushParameter.HUE_JITTER)
        val saturationJitter = brush.parameter(com.artstudio.pro.core.brush.model.BrushParameter.SATURATION_JITTER)
        val brightnessJitter = brush.parameter(com.artstudio.pro.core.brush.model.BrushParameter.BRIGHTNESS_JITTER)
        if (hueJitter + saturationJitter + brightnessJitter > 0f) {
            val hsv = FloatArray(3)
            Color.colorToHSV(pigmentColor,hsv)
            hsv[0] = (hsv[0] + (noise(x+y+29f)-.5f)*360f*hueJitter + 360f) % 360f
            hsv[1] = (hsv[1] * (1f + (noise(x-y+31f)-.5f)*saturationJitter)).coerceIn(0f,1f)
            hsv[2] = (hsv[2] * (1f + (noise(x*y+37f)-.5f)*brightnessJitter)).coerceIn(0f,1f)
            pigmentColor = Color.HSVToColor(Color.alpha(pigmentColor),hsv)
        }
        paint.color = if (eraserMode) Color.WHITE else pigmentColor
        paint.alpha = when (brush.medium) {
            BrushMedium.WATERCOLOR -> (flowAlpha * 0.22f).toInt()
            BrushMedium.GRAPHITE -> (flowAlpha * 0.78f).toInt()
            else -> flowAlpha
        }

        val customTip: Bitmap? = brush.tipMask
        when {
            eraserMode -> canvas.drawCircle(drawX, drawY, radius, paint)
            customTip != null && !customTip.isRecycled -> {
                tipPaint.colorFilter = PorterDuffColorFilter(activeColor, PorterDuff.Mode.SRC_IN)
                tipPaint.alpha = flowAlpha
                val aspect = customTip.width.toFloat() / customTip.height.toFloat()
                val tipWidth = if (aspect >= 1f) diameter else diameter * aspect
                val tipHeight = if (aspect >= 1f) diameter / aspect else diameter
                val destination = RectF(
                    drawX - tipWidth / 2f, drawY - tipHeight / 2f,
                    drawX + tipWidth / 2f, drawY + tipHeight / 2f
                )
                canvas.save()
                canvas.rotate(Math.toDegrees(stampRotation.toDouble()).toFloat(), drawX, drawY)
                canvas.drawBitmap(customTip, null, destination, tipPaint)
                canvas.restore()
                tipPaint.colorFilter = null
                tipPaint.alpha = 255
            }
            brush.medium == BrushMedium.WATERCOLOR -> {
                canvas.drawCircle(drawX, drawY, radius, paint)
                detailPaint.color = pigmentColor
                detailPaint.alpha = (flowAlpha * (0.18f + brush.parameter(com.artstudio.pro.core.brush.model.BrushParameter.WET_EDGE) * 0.4f)).toInt().coerceIn(0, 255)
                detailPaint.style = Paint.Style.STROKE
                detailPaint.strokeWidth = (diameter * 0.10f).coerceAtLeast(1f)
                canvas.drawCircle(drawX, drawY, radius * 0.88f, detailPaint)
                detailPaint.style = Paint.Style.FILL
            }
            brush.medium == BrushMedium.OIL_IMPASTO -> {
                canvas.drawCircle(drawX, drawY, radius, paint)
                detailPaint.color = pigmentColor
                detailPaint.alpha = (flowAlpha * 0.42f).toInt().coerceIn(0, 255)
                detailPaint.style = Paint.Style.STROKE
                detailPaint.strokeCap = Paint.Cap.ROUND
                detailPaint.strokeWidth = (diameter * 0.07f).coerceAtLeast(1f)
                val bristleCount = maxOf(brush.bristleCount, brush.parameter(com.artstudio.pro.core.brush.model.BrushParameter.BRISTLE_COUNT).toInt()).coerceIn(1,48)
                repeat(bristleCount) { i ->
                    val offset = (i - (bristleCount-1)/2f) * diameter / bristleCount
                    canvas.drawLine(drawX - radius * 0.48f, drawY + offset, drawX + radius * 0.48f, drawY + offset, detailPaint)
                }
                detailPaint.style = Paint.Style.FILL
            }
            brush.medium == BrushMedium.SCREENTONE -> {
                detailPaint.color = activeColor
                detailPaint.alpha = flowAlpha
                detailPaint.style = Paint.Style.FILL
                val spacing = (diameter / 5f).coerceAtLeast(2f)
                val dotRadius = (spacing * (0.12f + normalizedPressure * 0.18f)).coerceAtLeast(0.5f)
                var offsetY = -radius
                while (offsetY <= radius) {
                    var offsetX = -radius
                    while (offsetX <= radius) {
                        if (offsetX * offsetX + offsetY * offsetY <= radius * radius) {
                            canvas.drawCircle(drawX + offsetX, drawY + offsetY, dotRadius, detailPaint)
                        }
                        offsetX += spacing
                    }
                    offsetY += spacing
                }
            }
            brush.medium == BrushMedium.GRAPHITE || brush.medium == BrushMedium.CHARCOAL -> {
                canvas.drawCircle(drawX, drawY, radius, paint)
                detailPaint.color = activeColor
                val flecks = if (brush.medium == BrushMedium.CHARCOAL) 10 else 5
                repeat(flecks) { i ->
                    val angle = noise(x + i * 19.7f) * (Math.PI.toFloat() * 2f)
                    val distance = noise(y + i * 31.3f) * radius
                    val px = drawX + kotlin.math.cos(angle) * distance
                    val py = drawY + kotlin.math.sin(angle) * distance
                    detailPaint.alpha = (flowAlpha * (0.12f + noise(px + py) * 0.28f)).toInt().coerceIn(0, 255)
                    val dot = (diameter * (0.012f + noise(px - py) * 0.025f)).coerceAtLeast(0.5f)
                    canvas.drawCircle(px, py, dot, detailPaint)
                }
            }
            else -> {
                val ovalHeight = diameter * (brush.roundness * (1f - brush.parameter(com.artstudio.pro.core.brush.model.BrushParameter.TIP_ROUNDNESS) * .5f)).coerceIn(0.08f, 1f)
                val effectiveHardness = (brush.hardness * (1f - brush.parameter(com.artstudio.pro.core.brush.model.BrushParameter.TIP_EDGE_SOFTNESS))).coerceIn(0.05f,1f)
                if (effectiveHardness < 0.999f && !eraserMode) {
                    val center = Color.argb(flowAlpha, Color.red(pigmentColor), Color.green(pigmentColor), Color.blue(pigmentColor))
                    paint.shader = android.graphics.RadialGradient(
                        drawX, drawY, radius,
                        intArrayOf(center, Color.TRANSPARENT),
                        floatArrayOf(effectiveHardness.coerceIn(0f, 0.98f), 1f),
                        android.graphics.Shader.TileMode.CLAMP
                    )
                }
                canvas.save()
                canvas.rotate(Math.toDegrees(stampRotation.toDouble()).toFloat(), drawX, drawY)
                canvas.drawOval(drawX-radius, drawY-ovalHeight/2f, drawX+radius, drawY+ovalHeight/2f, paint)
                canvas.restore()
                paint.shader = null
                if (brush.medium == BrushMedium.OIL_IMPASTO && brush.bristleCount > 1) {
                    detailPaint.color = pigmentColor
                    detailPaint.alpha = (flowAlpha * (0.15f + brush.pigmentLoad.coerceIn(0f,1f)*0.35f)).toInt()
                    detailPaint.strokeWidth = (diameter * 0.025f).coerceAtLeast(0.6f)
                    repeat(brush.bristleCount.coerceIn(1,48)) { i ->
                        val offset = (i - (brush.bristleCount-1)/2f) * diameter / (brush.bristleCount.coerceAtLeast(1))
                        canvas.drawLine(drawX-radius*.65f,drawY+offset,drawX+radius*.65f,drawY+offset,detailPaint)
                    }
                }
                val textureStrength = maxOf(brush.textureStrength, brush.parameter(com.artstudio.pro.core.brush.model.BrushParameter.TEXTURE_STRENGTH))
                if (textureStrength > 0f) {
                    detailPaint.color = pigmentColor
                    repeat((textureStrength.coerceIn(0f,1f)*24).toInt()) { i ->
                        val angle=noise(x+i*13.7f)*Math.PI.toFloat()*2f
                        val distance=noise(y+i*7.3f)*radius
                        detailPaint.alpha=(flowAlpha*textureStrength*.55f).toInt().coerceIn(0,255)
                        val dot=(diameter*.008f*brush.textureScale.coerceIn(.2f,5f)*(1f+brush.parameter(com.artstudio.pro.core.brush.model.BrushParameter.TEXTURE_SCALE)*4f)).coerceAtLeast(.4f)
                        canvas.drawCircle(drawX+kotlin.math.cos(angle)*distance,drawY+kotlin.math.sin(angle)*distance,dot,detailPaint)
                    }
                }
            }
        }
        val customTexture = brush.textureBitmap
        val textureAmount = maxOf(brush.textureStrength, brush.parameter(com.artstudio.pro.core.brush.model.BrushParameter.TEXTURE_STRENGTH))
        if (!eraserMode && customTexture != null && !customTexture.isRecycled && textureAmount > 0f) {
            detailPaint.shader = android.graphics.BitmapShader(
                customTexture, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT
            )
            detailPaint.colorFilter = PorterDuffColorFilter(pigmentColor, PorterDuff.Mode.SRC_IN)
            detailPaint.alpha = (flowAlpha * textureAmount).toInt().coerceIn(0,255)
            detailPaint.style = Paint.Style.FILL
            canvas.save()
            canvas.rotate(Math.toDegrees(stampRotation.toDouble()).toFloat(), drawX, drawY)
            canvas.drawCircle(drawX, drawY, radius, detailPaint)
            canvas.restore()
            detailPaint.shader = null
            detailPaint.colorFilter = null
            detailPaint.alpha = 255
        }
        stampIndex++
    }
}
