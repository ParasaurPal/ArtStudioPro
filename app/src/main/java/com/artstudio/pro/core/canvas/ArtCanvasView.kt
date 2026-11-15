package com.artstudio.pro.core.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.artstudio.pro.core.brush.engine.StrokeEngine
import com.artstudio.pro.core.brush.engine.StrokePoint
import com.artstudio.pro.core.model.ArtLayer
import kotlin.math.min

/**
 * Viewport for a fixed-resolution document. Input is transformed into document
 * coordinates so the canvas remains correctly drawable at every screen size/density.
 */
class ArtCanvasView(context: Context, val documentWidth: Int, val documentHeight: Int) : View(context) {
    val layers = mutableListOf(
        ArtLayer(name = "Background", width = documentWidth, height = documentHeight).apply { clear() }
    )
    var strokeEngine: StrokeEngine? = null

    private val activeBitmap = Bitmap.createBitmap(documentWidth, documentHeight, Bitmap.Config.ARGB_8888)
    private val activeCanvas = Canvas(activeBitmap)
    private val documentRect = RectF(0f, 0f, documentWidth.toFloat(), documentHeight.toFloat())
    private val viewportRect = RectF()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val viewToDocument = Matrix()
    private val documentToView = Matrix()
    private var transformReady = false

    private fun updateTransform() {
        if (width <= 0 || height <= 0) return
        val scale = min(width.toFloat() / documentWidth, height.toFloat() / documentHeight)
        val left = (width - documentWidth * scale) / 2f
        val top = (height - documentHeight * scale) / 2f
        viewportRect.set(left, top, left + documentWidth * scale, top + documentHeight * scale)
        documentToView.setRectToRect(documentRect, viewportRect, Matrix.ScaleToFit.FILL)
        transformReady = documentToView.invert(viewToDocument)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateTransform()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!transformReady) updateTransform()
        canvas.drawColor(Color.rgb(34, 32, 38))
        if (!transformReady) return

        // The page is deliberately opaque white; artwork layers remain transparent.
        canvas.drawRect(viewportRect, bitmapPaint.apply { color = Color.WHITE })
        val save = canvas.save()
        canvas.clipRect(viewportRect)
        layers.filter { it.isVisible }.forEach { layer ->
            layerPaint.alpha = (layer.opacity.coerceIn(0f, 1f) * 255f).toInt()
            canvas.drawBitmap(layer.bitmap, null, viewportRect, layerPaint)
        }
        bitmapPaint.alpha = 255
        canvas.drawBitmap(activeBitmap, null, viewportRect, bitmapPaint)
        canvas.restoreToCount(save)
    }

    private fun documentPoint(event: MotionEvent): StrokePoint {
        val point = floatArrayOf(event.x, event.y)
        viewToDocument.mapPoints(point)
        val pressure = event.pressure.takeIf { it > 0f } ?: 0.5f
        return StrokePoint(point[0].coerceIn(0f, documentWidth.toFloat()), point[1].coerceIn(0f, documentHeight.toFloat()), pressure)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!transformReady) updateTransform()
        if (!transformReady) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!viewportRect.contains(event.x, event.y)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                strokeEngine?.startStroke(documentPoint(event), activeCanvas)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                strokeEngine?.continueStroke(documentPoint(event), activeCanvas)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                strokeEngine?.finishStroke(activeCanvas)
                layers.lastOrNull()?.canvas?.drawBitmap(activeBitmap, 0f, 0f, null)
                activeCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                strokeEngine?.finishStroke(activeCanvas)
                layers.lastOrNull()?.canvas?.drawBitmap(activeBitmap, 0f, 0f, null)
                activeCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
