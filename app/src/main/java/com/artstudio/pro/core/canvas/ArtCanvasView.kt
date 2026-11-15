package com.artstudio.pro.core.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.artstudio.pro.core.brush.engine.StrokeEngine
import com.artstudio.pro.core.brush.engine.StrokePoint
import com.artstudio.pro.core.model.ArtLayer
import com.artstudio.pro.core.model.BlendMode
import kotlin.math.min

/** Fixed-resolution document viewport with a non-destructive raster layer stack. */
class ArtCanvasView(context: Context, val documentWidth: Int, val documentHeight: Int) : View(context) {
    val layers = mutableListOf(
        ArtLayer(name = "Background", width = documentWidth, height = documentHeight).apply { clear() }
    )
    var activeLayerIndex: Int = 0
        private set
    val activeLayer: ArtLayer get() = layers[activeLayerIndex.coerceIn(0, layers.lastIndex)]
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

    private data class TileState(val x: Int, val y: Int, val bitmap: Bitmap)
    private data class StrokeChange(val layerId: String, val before: List<TileState>, val after: List<TileState>)
    private val undoHistory = ArrayDeque<StrokeChange>()
    private val redoHistory = ArrayDeque<StrokeChange>()
    private val dirtyBounds = RectF()
    private var hasDirtyBounds = false
    private val undoTileSize = 128
    private val maxUndoEntries = 20

    val canUndo: Boolean get() = undoHistory.isNotEmpty()
    val canRedo: Boolean get() = redoHistory.isNotEmpty()

    private fun captureTiles(layer: ArtLayer, bounds: RectF): List<TileState> {
        val dirty = Rect()
        bounds.roundOut(dirty)
        val left = (dirty.left.coerceAtLeast(0) / undoTileSize) * undoTileSize
        val top = (dirty.top.coerceAtLeast(0) / undoTileSize) * undoTileSize
        val right = dirty.right.coerceAtMost(documentWidth)
        val bottom = dirty.bottom.coerceAtMost(documentHeight)
        val result = mutableListOf<TileState>()
        var y = top
        while (y < bottom) {
            var x = left
            val tileBottom = (y + undoTileSize).coerceAtMost(documentHeight)
            while (x < right) {
                val tileRight = (x + undoTileSize).coerceAtMost(documentWidth)
                result += TileState(x, y, Bitmap.createBitmap(layer.bitmap, x, y, tileRight - x, tileBottom - y))
                x += undoTileSize
            }
            y += undoTileSize
        }
        return result
    }

    private fun restoreTiles(layer: ArtLayer, tiles: List<TileState>) {
        val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        tiles.forEach { tile -> layer.canvas.drawBitmap(tile.bitmap, tile.x.toFloat(), tile.y.toFloat(), paint) }
    }

    private fun releaseChange(change: StrokeChange) {
        (change.before + change.after).forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
    }

    private fun trackDirty(point: StrokePoint) {
        val radius = (strokeEngine?.brush?.baseSize ?: 1f) / 2f + 3f
        val next = RectF(point.x - radius, point.y - radius, point.x + radius, point.y + radius)
        if (hasDirtyBounds) dirtyBounds.union(next) else {
            dirtyBounds.set(next)
            hasDirtyBounds = true
        }
    }

    fun undo() {
        val change = undoHistory.pollLast() ?: return
        val layer = layers.firstOrNull { it.id == change.layerId }
        if (layer == null) { releaseChange(change); return }
        restoreTiles(layer, change.before)
        redoHistory.addLast(change)
        invalidate()
    }

    fun redo() {
        val change = redoHistory.pollLast() ?: return
        val layer = layers.firstOrNull { it.id == change.layerId }
        if (layer == null) { releaseChange(change); return }
        restoreTiles(layer, change.after)
        undoHistory.addLast(change)
        invalidate()
    }

    private fun clearHistory() {
        undoHistory.forEach(::releaseChange)
        redoHistory.forEach(::releaseChange)
        undoHistory.clear()
        redoHistory.clear()
    }

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
        canvas.drawRect(viewportRect, bitmapPaint.apply { color = Color.WHITE; alpha = 255 })
        val save = canvas.save()
        canvas.clipRect(viewportRect)
        layers.filter { it.isVisible }.forEach { layer ->
            layerPaint.alpha = (layer.opacity.coerceIn(0f, 1f) * 255f).toInt()
            layerPaint.xfermode = when (layer.blendMode) {
                BlendMode.MULTIPLY -> PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                BlendMode.SCREEN -> PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                BlendMode.OVERLAY -> PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
                BlendMode.NORMAL -> null
            }
            canvas.drawBitmap(layer.bitmap, null, viewportRect, layerPaint)
        }
        layerPaint.xfermode = null
        layerPaint.alpha = 255
        canvas.drawBitmap(activeBitmap, null, viewportRect, bitmapPaint)
        canvas.restoreToCount(save)
    }

    fun addLayer(name: String = "Layer ${layers.size}") {
        layers.add(ArtLayer(name = name, width = documentWidth, height = documentHeight))
        activeLayerIndex = layers.lastIndex
        invalidate()
    }

    fun duplicateActiveLayer() {
        val source = activeLayer
        val copy = ArtLayer(
            name = "${source.name} copy", width = documentWidth, height = documentHeight,
            opacity = source.opacity, isVisible = source.isVisible,
            isAlphaLocked = source.isAlphaLocked, blendMode = source.blendMode
        )
        copy.canvas.drawBitmap(source.bitmap, 0f, 0f, null)
        layers.add(activeLayerIndex + 1, copy)
        activeLayerIndex++
        invalidate()
    }

    fun deleteActiveLayer() {
        if (layers.size <= 1) {
            activeLayer.clear()
        } else {
            layers.removeAt(activeLayerIndex)
            activeLayerIndex = activeLayerIndex.coerceAtMost(layers.lastIndex)
        }
        invalidate()
    }

    fun selectLayer(index: Int) {
        require(index in layers.indices)
        activeLayerIndex = index
        invalidate()
    }

    fun renameActiveLayer(name: String) {
        activeLayer.name = name.trim().ifEmpty { activeLayer.name }
        invalidate()
    }

    fun moveActiveLayer(toIndex: Int) {
        if (toIndex !in layers.indices || toIndex == activeLayerIndex) return
        val layer = layers.removeAt(activeLayerIndex)
        layers.add(toIndex, layer)
        activeLayerIndex = toIndex
        invalidate()
    }

    fun clearActiveLayer() {
        activeLayer.clear()
        invalidate()
    }

    fun mergeActiveLayerDown() {
        if (activeLayerIndex <= 0) return
        val upper = activeLayer
        val lower = layers[activeLayerIndex - 1]
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (upper.opacity.coerceIn(0f, 1f) * 255f).toInt()
            xfermode = when (upper.blendMode) {
                BlendMode.MULTIPLY -> PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                BlendMode.SCREEN -> PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                BlendMode.OVERLAY -> PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
                BlendMode.NORMAL -> null
            }
        }
        lower.canvas.drawBitmap(upper.bitmap, 0f, 0f, paint)
        lower.opacity = 1f
        layers.removeAt(activeLayerIndex)
        activeLayerIndex--
        invalidate()
    }

    fun mergeVisibleLayers() {
        val merged = ArtLayer(name = "Merged", width = documentWidth, height = documentHeight)
        layers.filter { it.isVisible }.forEach { layer ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                alpha = (layer.opacity.coerceIn(0f, 1f) * 255f).toInt()
                xfermode = when (layer.blendMode) {
                    BlendMode.MULTIPLY -> PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                    BlendMode.SCREEN -> PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                    BlendMode.OVERLAY -> PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
                    BlendMode.NORMAL -> null
                }
            }
            merged.canvas.drawBitmap(layer.bitmap, 0f, 0f, paint)
        }
        layers.clear()
        layers.add(merged)
        activeLayerIndex = 0
        invalidate()
    }

    fun replaceLayers(newLayers: List<ArtLayer>) {
        require(newLayers.isNotEmpty())
        require(newLayers.all { it.width == documentWidth && it.height == documentHeight })
        layers.clear()
        layers.addAll(newLayers)
        activeLayerIndex = layers.lastIndex
        clearHistory()
        invalidate()
    }

    /** Renders visible layers into a new export bitmap without changing the document. */
    fun renderComposite(backgroundColor: Int? = null): Bitmap {
        val output = Bitmap.createBitmap(documentWidth, documentHeight, Bitmap.Config.ARGB_8888)
        val outputCanvas = Canvas(output)
        backgroundColor?.let { outputCanvas.drawColor(it) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        layers.filter { it.isVisible }.forEach { layer ->
            paint.alpha = (layer.opacity.coerceIn(0f, 1f) * 255f).toInt()
            paint.xfermode = when (layer.blendMode) {
                BlendMode.MULTIPLY -> PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                BlendMode.SCREEN -> PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                BlendMode.OVERLAY -> PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
                BlendMode.NORMAL -> null
            }
            outputCanvas.drawBitmap(layer.bitmap, 0f, 0f, paint)
        }
        paint.xfermode = null
        paint.alpha = 255
        return output
    }

    fun addImageLayer(bitmap: Bitmap, name: String = "Imported image") {
        val layer = ArtLayer(name = name, width = documentWidth, height = documentHeight)
        val scale = min(documentWidth.toFloat() / bitmap.width, documentHeight.toFloat() / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = (documentWidth - width) / 2f
        val top = (documentHeight - height) / 2f
        layer.canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), Paint(Paint.FILTER_BITMAP_FLAG))
        layers.add(layer)
        activeLayerIndex = layers.lastIndex
        invalidate()
    }

    private fun documentPoint(event: MotionEvent): StrokePoint {
        val point = floatArrayOf(event.x, event.y)
        viewToDocument.mapPoints(point)
        val pressure = event.pressure.takeIf { it > 0f } ?: 0.5f
        return StrokePoint(
            point[0].coerceIn(0f, documentWidth.toFloat()),
            point[1].coerceIn(0f, documentHeight.toFloat()), pressure
        )
    }

    private fun commitStroke() {
        strokeEngine?.finishStroke(activeCanvas)
        val targetLayer = activeLayer
        val before = if (hasDirtyBounds) captureTiles(targetLayer, dirtyBounds) else emptyList()
        if (strokeEngine?.eraserMode == true) {
            val erasePaint = Paint().apply {
                xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            }
            activeLayer.canvas.drawBitmap(activeBitmap, 0f, 0f, erasePaint)
        } else {
            activeLayer.canvas.drawBitmap(activeBitmap, 0f, 0f, null)
        }
        activeCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (before.isNotEmpty()) {
            val after = captureTiles(targetLayer, dirtyBounds)
            redoHistory.forEach(::releaseChange)
            redoHistory.clear()
            undoHistory.addLast(StrokeChange(targetLayer.id, before, after))
            while (undoHistory.size > maxUndoEntries) releaseChange(undoHistory.removeFirst())
        }
        dirtyBounds.setEmpty()
        hasDirtyBounds = false
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!transformReady) updateTransform()
        if (!transformReady) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!viewportRect.contains(event.x, event.y)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                val point = documentPoint(event)
                dirtyBounds.setEmpty()
                hasDirtyBounds = false
                trackDirty(point)
                strokeEngine?.startStroke(point, activeCanvas)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val point = documentPoint(event)
                trackDirty(point)
                strokeEngine?.continueStroke(point, activeCanvas)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                commitStroke()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                commitStroke()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
