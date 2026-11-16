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
import com.artstudio.pro.core.paper.PaperKind
import com.artstudio.pro.core.paper.PaperTexture
import kotlin.math.min

enum class SymmetryMode { NONE, VERTICAL, HORIZONTAL, QUADRANT }

/** Fixed-resolution document viewport with a non-destructive raster layer stack. */
class ArtCanvasView(context: Context, val documentWidth: Int, val documentHeight: Int) : View(context) {
    val layers = mutableListOf(
        ArtLayer(name = "Background", width = documentWidth, height = documentHeight).apply { clear() }
    )
    var activeLayerIndex: Int = 0
        private set
    val activeLayer: ArtLayer get() = layers[activeLayerIndex.coerceIn(0, layers.lastIndex)]
    var strokeEngine: StrokeEngine? = null
    var paperKind: PaperKind = PaperKind.SMOOTH
        private set
    var paperStrength: Float = 1f
        private set
    var showGrid: Boolean = false
        private set
    var gridSpacing: Int = 64
        private set
    var symmetryMode: SymmetryMode = SymmetryMode.NONE
        private set

    fun setPaperTexture(kind: PaperKind, strength: Float = paperStrength) {
        paperKind = kind
        paperStrength = strength.coerceIn(0f, 1f)
        invalidate()
    }

    private val activeBitmap = Bitmap.createBitmap(documentWidth, documentHeight, Bitmap.Config.ARGB_8888)
    private val activeCanvas = Canvas(activeBitmap)\n    private val symmetryEngines = mutableListOf<Pair<Canvas, StrokeEngine>>()
    private val documentRect = RectF(0f, 0f, documentWidth.toFloat(), documentHeight.toFloat())
    private val viewportRect = RectF()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val viewToDocument = Matrix()
    private val documentToView = Matrix()
    private var transformReady = false
    private var previousInputX = 0f
    private var previousInputY = 0f
    private var previousInputTime = 0L

    private data class TileState(val x: Int, val y: Int, val bitmap: Bitmap)
    private data class StrokeChange(val layerId: String, val before: List<TileState>, val after: List<TileState>)
    private val undoHistory = ArrayDeque<StrokeChange>()
    private val redoHistory = ArrayDeque<StrokeChange>()
    private val dirtyBounds = RectF()
    private var hasDirtyBounds = false
    private val undoTileSize = 128
    private val maxUndoEntries = 200

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
        val change = undoHistory.removeLastOrNull() ?: return
        val layer = layers.firstOrNull { it.id == change.layerId }
        if (layer == null) { releaseChange(change); return }
        restoreTiles(layer, change.before)
        redoHistory.addLast(change)
        invalidate()
    }

    fun redo() {
        val change = redoHistory.removeLastOrNull() ?: return
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

    fun setGrid(enabled: Boolean, spacing: Int = gridSpacing) {
        showGrid = enabled
        gridSpacing = spacing.coerceIn(8, 1024)
        invalidate()
    }

    fun setSymmetry(mode: SymmetryMode) {
        symmetryMode = mode
        invalidate()
    }

    private fun prepareSymmetryEngines() {
        symmetryEngines.clear()
        val engine = strokeEngine ?: return
        val centerX = documentWidth / 2f
        val centerY = documentHeight / 2f
        val transforms = when (symmetryMode) {
            SymmetryMode.NONE -> emptyList()
            SymmetryMode.VERTICAL -> listOf(-1f to 1f)
            SymmetryMode.HORIZONTAL -> listOf(1f to -1f)
            SymmetryMode.QUADRANT -> listOf(-1f to 1f, 1f to -1f, -1f to -1f)
        }
        transforms.forEach { (scaleX, scaleY) ->
            val mirroredCanvas = Canvas(activeBitmap).apply { scale(scaleX, scaleY, centerX, centerY) }
            val mirroredEngine = StrokeEngine(engine.brush, engine.activeColor).apply {
                eraserMode = engine.eraserMode
            }
            symmetryEngines += mirroredCanvas to mirroredEngine
        }
    }

    private fun drawGrid(canvas: Canvas) {
        if (!showGrid) return
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(55, 190, 180, 220)
            strokeWidth = 1f
        }
        var x = 0
        while (x <= documentWidth) {
            val viewX = viewportRect.left + viewportRect.width() * x / documentWidth
            canvas.drawLine(viewX, viewportRect.top, viewX, viewportRect.bottom, gridPaint)
            x += gridSpacing
        }
        var y = 0
        while (y <= documentHeight) {
            val viewY = viewportRect.top + viewportRect.height() * y / documentHeight
            canvas.drawLine(viewportRect.left, viewY, viewportRect.right, viewY, gridPaint)
            y += gridSpacing
        }
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

    private fun drawLayer(
        target: Canvas,
        layer: ArtLayer,
        paint: Paint,
        destination: RectF? = null,
        clipBase: Bitmap? = null
    ) {
        val clipped = if (layer.isClippedToBelow && clipBase != null) {
            Bitmap.createBitmap(documentWidth, documentHeight, Bitmap.Config.ARGB_8888).also { temp ->
                val tempCanvas = Canvas(temp)
                tempCanvas.drawBitmap(layer.bitmap, 0f, 0f, null)
                tempCanvas.drawBitmap(clipBase, 0f, 0f, Paint().apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                })
            }
        } else null
        val source = clipped ?: layer.bitmap
        if (destination == null) target.drawBitmap(source, 0f, 0f, paint)
        else target.drawBitmap(source, null, destination, paint)
        clipped?.recycle()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!transformReady) updateTransform()
        canvas.drawColor(Color.rgb(34, 32, 38))
        if (!transformReady) return
        PaperTexture.draw(canvas, viewportRect, paperKind, paperStrength)
        val save = canvas.save()
        canvas.clipRect(viewportRect)
        var clipBase: Bitmap? = null
        layers.filter { it.isVisible }.forEach { layer ->
            layerPaint.alpha = (layer.opacity.coerceIn(0f, 1f) * 255f).toInt()
            layerPaint.xfermode = layer.blendMode.porterDuffMode?.let { PorterDuffXfermode(it) }
            drawLayer(canvas, layer, layerPaint, viewportRect, clipBase)
            if (!layer.isClippedToBelow) clipBase = layer.bitmap
        }
        layerPaint.xfermode = null
        layerPaint.alpha = 255
        canvas.drawBitmap(activeBitmap, null, viewportRect, bitmapPaint)
        canvas.restoreToCount(save)
        drawGrid(canvas)
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
            isAlphaLocked = source.isAlphaLocked, isClippedToBelow = source.isClippedToBelow,
            blendMode = source.blendMode
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
            xfermode = upper.blendMode.porterDuffMode?.let { PorterDuffXfermode(it) }
        }
        drawLayer(lower.canvas, upper, paint, clipBase = lower.bitmap)
        lower.opacity = 1f
        layers.removeAt(activeLayerIndex)
        activeLayerIndex--
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

    fun mergeVisibleLayers() {
        val merged = ArtLayer(name = "Merged", width = documentWidth, height = documentHeight)
        val mergeCanvas = merged.canvas
        var clipBase: Bitmap? = null
        layers.filter { it.isVisible }.forEach { layer ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                alpha = (layer.opacity.coerceIn(0f, 1f) * 255f).toInt()
                xfermode = layer.blendMode.porterDuffMode?.let { PorterDuffXfermode(it) }
            }
            val clipped = if (layer.isClippedToBelow && clipBase != null) {
                Bitmap.createBitmap(documentWidth, documentHeight, Bitmap.Config.ARGB_8888).also { temp ->
                    val tempCanvas = Canvas(temp)
                    tempCanvas.drawBitmap(layer.bitmap, 0f, 0f, null)
                    tempCanvas.drawBitmap(clipBase!!, 0f, 0f, Paint().apply {
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    })
                }
            } else null
            mergeCanvas.drawBitmap(clipped ?: layer.bitmap, 0f, 0f, paint)
            clipped?.recycle()
            if (!layer.isClippedToBelow) clipBase = layer.bitmap
        }
        layers.clear()
        layers.add(merged)
        activeLayerIndex = 0
        invalidate()
    }

    /** Renders the visible stack without flattening or mutating the editable layers. */
    fun renderComposite(backgroundColor: Int? = null): Bitmap {
        val output = Bitmap.createBitmap(documentWidth, documentHeight, Bitmap.Config.ARGB_8888)
        val outputCanvas = Canvas(output)
        if (backgroundColor != null) outputCanvas.drawColor(backgroundColor)
        else PaperTexture.draw(
            outputCanvas,
            RectF(0f, 0f, documentWidth.toFloat(), documentHeight.toFloat()),
            paperKind,
            paperStrength
        )
        var clipBase: Bitmap? = null
        layers.filter { it.isVisible }.forEach { layer ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                alpha = (layer.opacity.coerceIn(0f, 1f) * 255f).toInt()
                xfermode = layer.blendMode.porterDuffMode?.let { PorterDuffXfermode(it) }
            }
            val clipped = if (layer.isClippedToBelow && clipBase != null) {
                Bitmap.createBitmap(documentWidth, documentHeight, Bitmap.Config.ARGB_8888).also { temp ->
                    val tempCanvas = Canvas(temp)
                    tempCanvas.drawBitmap(layer.bitmap, 0f, 0f, null)
                    tempCanvas.drawBitmap(clipBase!!, 0f, 0f, Paint().apply {
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    })
                }
            } else null
            outputCanvas.drawBitmap(clipped ?: layer.bitmap, 0f, 0f, paint)
            clipped?.recycle()
            if (!layer.isClippedToBelow) clipBase = layer.bitmap
        }
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
        val documentX = point[0].coerceIn(0f, documentWidth.toFloat())
        val documentY = point[1].coerceIn(0f, documentHeight.toFloat())
        val stylus = event.getToolType(event.actionIndex.coerceAtLeast(0)) == MotionEvent.TOOL_TYPE_STYLUS
        val pressure = if (stylus) event.pressure.coerceIn(0f, 1f).takeIf { it > 0f } ?: 0.5f else 1f
        val elapsed = (event.eventTime - previousInputTime).coerceAtLeast(1L).toFloat()
        val speed = if (previousInputTime == 0L) 0f else
            kotlin.math.hypot(documentX - previousInputX, documentY - previousInputY) / elapsed
        previousInputX = documentX
        previousInputY = documentY
        previousInputTime = event.eventTime
        val tilt = if (stylus) event.getAxisValue(MotionEvent.AXIS_TILT).coerceIn(0f, Math.PI.toFloat()) else 0f
        val orientation = if (stylus) event.getAxisValue(MotionEvent.AXIS_ORIENTATION) else 0f
        return StrokePoint(documentX, documentY, pressure, tilt, orientation, (speed / 2.5f).coerceIn(0f, 1f))
    }

    private fun commitStroke() {
        strokeEngine?.finishStroke(activeCanvas)\n        symmetryEngines.forEach { (mirrorCanvas, engine) -> engine.finishStroke(mirrorCanvas) }
        val targetLayer = activeLayer
        val before = if (hasDirtyBounds) captureTiles(targetLayer, dirtyBounds) else emptyList()
        if (targetLayer.isAlphaLocked) {
            if (strokeEngine?.eraserMode != true) {
                val alphaLockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
                }
                targetLayer.canvas.drawBitmap(activeBitmap, 0f, 0f, alphaLockPaint)
            }
        } else if (strokeEngine?.eraserMode == true) {
            val erasePaint = Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            }
            targetLayer.canvas.drawBitmap(activeBitmap, 0f, 0f, erasePaint)
        } else {
            targetLayer.canvas.drawBitmap(activeBitmap, 0f, 0f, null)
        }
        activeCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)\n        symmetryEngines.clear()
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
                previousInputTime = 0L
                val point = documentPoint(event)
                dirtyBounds.setEmpty()
                hasDirtyBounds = false
                trackDirty(point)
                strokeEngine?.startStroke(point, activeCanvas)\n                prepareSymmetryEngines()\n                symmetryEngines.forEach { (mirrorCanvas, engine) -> engine.startStroke(point, mirrorCanvas) }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val point = documentPoint(event)
                trackDirty(point)
                strokeEngine?.continueStroke(point, activeCanvas)\n                symmetryEngines.forEach { (mirrorCanvas, engine) -> engine.continueStroke(point, mirrorCanvas) }
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
