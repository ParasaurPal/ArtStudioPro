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
import android.view.ScaleGestureDetector
import android.view.View
import com.artstudio.pro.core.brush.engine.StrokeEngine
import com.artstudio.pro.core.brush.engine.StrokePoint
import com.artstudio.pro.core.model.ArtLayer
import com.artstudio.pro.core.paint.FloodFill
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
    var showGrid: Boolean = false
    var gridSpacing: Int = 128
    var transparentBackground: Boolean = false

    private val activeBitmap = Bitmap.createBitmap(documentWidth, documentHeight, Bitmap.Config.ARGB_8888)
    private val activeCanvas = Canvas(activeBitmap)
    private val documentRect = RectF(0f, 0f, documentWidth.toFloat(), documentHeight.toFloat())
    private val viewportRect = RectF()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val checkerPaint = Paint()
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 110, 150, 190)
        strokeWidth = 1f
    }
    private val viewToDocument = Matrix()
    private val documentToView = Matrix()
    private var transformReady = false
    private var zoomScale = 1f
    private var panX = 0f
    private var panY = 0f
    private var isPanning = false
    private var suppressSinglePointer = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val previous = zoomScale
                zoomScale = (zoomScale * detector.scaleFactor).coerceIn(0.25f, 8f)
                val factor = zoomScale / previous
                val centerX = width / 2f
                val centerY = height / 2f
                panX = (detector.focusX - centerX) - ((detector.focusX - centerX) - panX) * factor
                panY = (detector.focusY - centerY) - ((detector.focusY - centerY) - panY) * factor
                updateTransform()
                invalidate()
                return true
            }
        }
    )

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
        if (undoHistory.isEmpty()) return
        val change = undoHistory.removeLast()
        val layer = layers.firstOrNull { it.id == change.layerId }
        if (layer == null) { releaseChange(change); return }
        restoreTiles(layer, change.before)
        redoHistory.addLast(change)
        invalidate()
    }

    fun redo() {
        if (redoHistory.isEmpty()) return
        val change = redoHistory.removeLast()
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
        val fitScale = min(width.toFloat() / documentWidth, height.toFloat() / documentHeight)
        val scale = fitScale * zoomScale
        val left = (width - documentWidth * scale) / 2f + panX
        val top = (height - documentHeight * scale) / 2f + panY
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
        if (transparentBackground) {
            val checkerSize = 24f
            var row = 0
            var y = viewportRect.top
            while (y < viewportRect.bottom) {
                var column = 0
                var x = viewportRect.left
                while (x < viewportRect.right) {
                    checkerPaint.color = if ((row + column) % 2 == 0) Color.rgb(220, 220, 220) else Color.rgb(170, 170, 170)
                    canvas.drawRect(x, y, (x + checkerSize).coerceAtMost(viewportRect.right),
                        (y + checkerSize).coerceAtMost(viewportRect.bottom), checkerPaint)
                    x += checkerSize
                    column++
                }
                y += checkerSize
                row++
            }
        } else {
            canvas.drawRect(viewportRect, bitmapPaint.apply { color = Color.WHITE; alpha = 255 })
        }
        val save = canvas.save()
        canvas.clipRect(viewportRect)
        layers.filter { it.isVisible }.forEach { layer ->
            layerPaint.alpha = (layer.opacity.coerceIn(0f, 1f) * 255f).toInt()
            com.artstudio.pro.core.model.BlendModePainter.apply(layerPaint, layer.blendMode)
            canvas.drawBitmap(layer.bitmap, null, viewportRect, layerPaint)
        }
        layerPaint.xfermode = null
        layerPaint.alpha = 255
        canvas.drawBitmap(activeBitmap, null, viewportRect, bitmapPaint)
        if (showGrid) {
            val fitScale = min(width.toFloat() / documentWidth, height.toFloat() / documentHeight)
            val effectiveScale = fitScale * zoomScale
            val spacing = if (effectiveScale >= 4f) 1 else gridSpacing.coerceAtLeast(1)
            var x = 0
            while (x <= documentWidth) {
                val line = floatArrayOf(x.toFloat(), 0f, x.toFloat(), documentHeight.toFloat())
                documentToView.mapPoints(line)
                canvas.drawLine(line[0], line[1], line[2], line[3], guidePaint)
                x += spacing
            }
            var y = 0
            while (y <= documentHeight) {
                val line = floatArrayOf(0f, y.toFloat(), documentWidth.toFloat(), y.toFloat())
                documentToView.mapPoints(line)
                canvas.drawLine(line[0], line[1], line[2], line[3], guidePaint)
                y += spacing
            }
        }
        if (strokeEngine?.symmetryVertical == true) {
            val line = floatArrayOf(documentWidth / 2f, 0f, documentWidth / 2f, documentHeight.toFloat())
            documentToView.mapPoints(line)
            canvas.drawLine(line[0], line[1], line[2], line[3], guidePaint)
        }
        if (strokeEngine?.symmetryHorizontal == true) {
            val line = floatArrayOf(0f, documentHeight / 2f, documentWidth.toFloat(), documentHeight / 2f)
            documentToView.mapPoints(line)
            canvas.drawLine(line[0], line[1], line[2], line[3], guidePaint)
        }
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
            com.artstudio.pro.core.model.BlendModePainter.apply(this, upper.blendMode)
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
                com.artstudio.pro.core.model.BlendModePainter.apply(this, layer.blendMode)
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
    fun renderComposite(backgroundColor: Int? = if (transparentBackground) null else Color.WHITE): Bitmap {
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

    fun floodFillAt(x: Int, y: Int, color: Int, tolerance: Int = 18) {
        val target = activeLayer
        var before = emptyList<TileState>()
        val bounds = FloodFill.fill(
            bitmap = target.bitmap,
            startX = x,
            startY = y,
            fillColor = color,
            tolerance = tolerance,
            preserveAlpha = target.isAlphaLocked,
            beforeApply = { rect -> before = captureTiles(target, RectF(rect)) }
        ) ?: return
        if (before.isNotEmpty()) {
            val after = captureTiles(target, RectF(bounds))
            redoHistory.forEach(::releaseChange)
            redoHistory.clear()
            undoHistory.addLast(StrokeChange(target.id, before, after))
            while (undoHistory.size > maxUndoEntries) releaseChange(undoHistory.removeFirst())
        }
        invalidate()
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
        val hasStylusAxes = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        val tilt = if (hasStylusAxes) event.getAxisValue(MotionEvent.AXIS_TILT) else 0f
        val orientation = if (hasStylusAxes) event.getAxisValue(MotionEvent.AXIS_ORIENTATION) else 0f
        return StrokePoint(
            point[0].coerceIn(0f, documentWidth.toFloat()),
            point[1].coerceIn(0f, documentHeight.toFloat()),
            pressure,
            tilt,
            orientation,
            event.eventTime
        )
    }

    private fun commitStroke() {
        strokeEngine?.finishStroke(activeCanvas)
        val targetLayer = activeLayer
        val before = if (hasDirtyBounds) captureTiles(targetLayer, dirtyBounds) else emptyList()
        if (strokeEngine?.eraserMode == true) {
            if (!targetLayer.isAlphaLocked) {
                val erasePaint = Paint().apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                }
                targetLayer.canvas.drawBitmap(activeBitmap, 0f, 0f, erasePaint)
            }
        } else {
            val paint = if (targetLayer.isAlphaLocked) {
                Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
            } else null
            targetLayer.canvas.drawBitmap(activeBitmap, 0f, 0f, paint)
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
        scaleDetector.onTouchEvent(event)
        if (suppressSinglePointer) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                suppressSinglePointer = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            if (!isPanning) commitStroke()
            isPanning = true
            lastFocusX = (0 until event.pointerCount).map { event.getX(it) }.average().toFloat()
            lastFocusY = (0 until event.pointerCount).map { event.getY(it) }.average().toFloat()
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            isPanning = false
            suppressSinglePointer = true
            return true
        }
        if (isPanning && event.pointerCount >= 2) {
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val focusX = (0 until event.pointerCount).map { event.getX(it) }.average().toFloat()
                val focusY = (0 until event.pointerCount).map { event.getY(it) }.average().toFloat()
                panX += focusX - lastFocusX
                panY += focusY - lastFocusY
                lastFocusX = focusX
                lastFocusY = focusY
                updateTransform()
                invalidate()
            }
            return true
        }
        if (isPanning) {
            isPanning = false
            parent?.requestDisallowInterceptTouchEvent(false)
            return true
        }
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
