package com.artstudio.pro.core.canvas
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.artstudio.pro.core.brush.engine.*
import com.artstudio.pro.core.model.ArtLayer

class ArtCanvasView(context: Context, val w: Int, val h: Int) : View(context) {
    val layers = mutableListOf(ArtLayer(name = "Background", width = w, height = h).apply { clear() })
    var strokeEngine: StrokeEngine? = null
    private val activeBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    private val activeCanvas = Canvas(activeBmp)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        layers.filter { it.isVisible }.forEach { canvas.drawBitmap(it.bitmap, 0f, 0f, null) }
        canvas.drawBitmap(activeBmp, 0f, 0f, null)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = StrokePoint(e.x, e.y, if (e.pressure > 0f) e.pressure else 0.5f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { strokeEngine?.startStroke(p, activeCanvas); invalidate(); return true }
            MotionEvent.ACTION_MOVE -> { strokeEngine?.continueStroke(p, activeCanvas); invalidate(); return true }
            MotionEvent.ACTION_UP -> {
                layers[0].canvas.drawBitmap(activeBmp, 0f, 0f, null)
                activeCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                invalidate(); return true
            }
        }
        return super.onTouchEvent(e)
    }
}
