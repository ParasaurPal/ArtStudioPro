package com.artstudio.pro.core.canvas

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
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
import com.artstudio.pro.core.model.AnimationFrame
import com.artstudio.pro.core.model.BlendMode
import com.artstudio.pro.core.vector.VectorPath
import com.artstudio.pro.core.vector.VectorNode
import com.artstudio.pro.core.vector.VectorRenderer
import com.artstudio.pro.core.paper.PaperKind
import com.artstudio.pro.core.paper.PaperTexture
import kotlin.math.min

enum class SymmetryMode { NONE, VERTICAL, HORIZONTAL, QUADRANT }
enum class SelectionMode { NONE, RECTANGLE, ELLIPSE }
enum class DrawingTool { BRUSH, LINE, RECTANGLE, ELLIPSE, PANEL, SPEECH_BALLOON, TEXT }

/** Fixed-resolution document viewport with a non-destructive raster layer stack. */
class ArtCanvasView(context: Context, val documentWidth: Int, val documentHeight: Int) : View(context) {
    var layers = mutableListOf(
        ArtLayer(name = "Background", width = documentWidth, height = documentHeight).apply { clear() }
    )
        private set
    private val animationFrames = mutableListOf(AnimationFrame(100, layers))
    var currentFrameIndex: Int = 0
        private set
    var onionSkinEnabled: Boolean = false
        private set
    var isPlaying: Boolean = false
        private set
    val frameCount: Int get() = animationFrames.size
    private val playbackHandler = Handler(Looper.getMainLooper())
    private val playbackRunnable = object : Runnable {
        override fun run() {
            if (!isPlaying || animationFrames.size < 2) return
            selectFrame((currentFrameIndex + 1) % animationFrames.size)
            playbackHandler.postDelayed(this, animationFrames[currentFrameIndex].durationMs.toLong())
        }
    }
    var activeLayerIndex: Int = 0
        private set
    val activeLayer: ArtLayer get() = layers[activeLayerIndex.coerceIn(0, layers.lastIndex)]
    private val maxLayerCount: Int = (64_000_000L / (documentWidth.toLong() * documentHeight.toLong()).coerceAtLeast(1L)).coerceIn(2L, 32L).toInt()
    var strokeEngine: StrokeEngine? = null
    var paperKind: PaperKind = PaperKind.SMOOTH
        private set
    var paperStrength: Float = 1f
        private set
    var showGrid: Boolean = false
        private set
    var showRulers: Boolean = false
        private set
    var gridSpacing: Int = 64
        private set
    var symmetryMode: SymmetryMode = SymmetryMode.NONE
        private set
    var zoomFactor: Float = 1f
        private set
    var selectionMode: SelectionMode = SelectionMode.NONE
        private set
    var drawingTool: DrawingTool = DrawingTool.BRUSH
        private set
    private var shapeStartX = 0f
    private var shapeStartY = 0f
    var textToolContent: String = "Text"
        private set
    val hasSelection: Boolean get() = selectionRect != null
    private var selectionRect: RectF? = null
    private var selectionIsEllipse = false
    private var selectionStartX = 0f
    private var selectionStartY = 0f
    private var isSelecting = false
    private var activeCanvasSaveCount: Int? = null
    private val mirrorSaveCounts = mutableListOf<Pair<Canvas, Int>>()

    fun setPaperTexture(kind: PaperKind, strength: Float = paperStrength) {
        paperKind = kind
        paperStrength = strength.coerceIn(0f, 1f)
        invalidate()
    }

    private val activeBitmap = Bitmap.createBitmap(documentWidth, documentHeight, Bitmap.Config.ARGB_8888)
    private val activeCanvas = Canvas(activeBitmap)
    private val symmetryEngines = mutableListOf<Pair<Canvas, StrokeEngine>>()
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
    private var panX = 0f
    private var panY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var isNavigating = false
    private var strokeActive = false
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomFactor = (zoomFactor * detector.scaleFactor).coerceIn(0.25f, 16f)
            updateTransform()
            invalidate()
            return true
        }
    })

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

    private fun cloneLayer(source: ArtLayer, copyPixels: Boolean): ArtLayer {
        return ArtLayer(
            name = source.name,
            width = documentWidth,
            height = documentHeight,
            opacity = source.opacity,
            isVisible = source.isVisible,
            isAlphaLocked = source.isAlphaLocked,
            isClippedToBelow = source.isClippedToBelow,
            brightness = source.brightness,
            contrast = source.contrast,
            saturation = source.saturation,
            blendMode = source.blendMode
        ).also {
            if (copyPixels) it.canvas.drawBitmap(source.bitmap, 0f, 0f, null)
            it.vectorPaths.addAll(source.vectorPaths)
            source.maskBitmap?.let { mask -> it.createMask(false).also { targetMask -> Canvas(targetMask).drawBitmap(mask, 0f, 0f, null) } }
            it.maskEnabled = source.maskEnabled
        }
    }

    fun selectFrame(index: Int) {
        if (index !in animationFrames.indices || index == currentFrameIndex) return
        commitStroke()
        currentFrameIndex = index
        layers = animationFrames[index].layers
        activeLayerIndex = activeLayerIndex.coerceIn(0, layers.lastIndex)
        clearHistory()
        invalidate()
    }

    private fun canAddAnimationFrame(): Boolean {
        if (animationFrames.size >= 120) return false
        val existingLayerCount = animationFrames.sumOf { it.layers.size.toLong() }
        val addedLayerCount = layers.size.toLong()
        return documentWidth.toLong() * documentHeight.toLong() *
            (existingLayerCount + addedLayerCount) <= 64_000_000L
    }

    fun addBlankFrame(): Boolean {
        if (!canAddAnimationFrame()) return false
        val blank = layers.map { cloneLayer(it, false) }.toMutableList()
        animationFrames.add(currentFrameIndex + 1, AnimationFrame(100, blank))
        selectFrame(currentFrameIndex + 1)
        return true
    }

    fun duplicateFrame(): Boolean {
        if (!canAddAnimationFrame()) return false
        val duplicate = layers.map { cloneLayer(it, true) }.toMutableList()
        animationFrames.add(currentFrameIndex + 1, AnimationFrame(
            animationFrames[currentFrameIndex].durationMs, duplicate
        ))
        selectFrame(currentFrameIndex + 1)
        return true
    }

    fun deleteFrame(): Boolean {
        if (animationFrames.size <= 1) {
            layers.forEach { it.clear() }
            invalidate()
            return false
        }
        animationFrames.removeAt(currentFrameIndex)
        currentFrameIndex = (currentFrameIndex - 1).coerceAtLeast(0)
        layers = animationFrames[currentFrameIndex].layers
        activeLayerIndex = activeLayerIndex.coerceIn(0, layers.lastIndex)
        clearHistory()
        invalidate()
        return true
    }

    fun setFrameDuration(durationMs: Int) {
        animationFrames[currentFrameIndex].durationMs = durationMs.coerceIn(20, 5000)
    }

    fun currentFrameDuration(): Int = animationFrames[currentFrameIndex].durationMs

    fun animationFramesSnapshot(): List<AnimationFrame> = animationFrames.toList()

    fun replaceFrames(frames: List<AnimationFrame>, selectedIndex: Int = 0) {
        require(frames.isNotEmpty() && frames.size <= 120)
        require(frames.all { frame ->
            frame.layers.isNotEmpty() &&
                frame.layers.all { it.width == documentWidth && it.height == documentHeight }
        })
        require(frames.sumOf { it.layers.size.toLong() } * documentWidth.toLong() * documentHeight.toLong() <= 64_000_000L)
        stopPlayback()
        animationFrames.clear()
        animationFrames.addAll(frames)
        currentFrameIndex = selectedIndex.coerceIn(0, animationFrames.lastIndex)
        layers = animationFrames[currentFrameIndex].layers
        activeLayerIndex = layers.lastIndex
        clearHistory()
        invalidate()
    }



    fun setOnionSkin(enabled: Boolean) {
        onionSkinEnabled = enabled
        invalidate()
    }

    fun startPlayback() {
        if (animationFrames.size < 2 || isPlaying) return
        isPlaying = true
        playbackHandler.postDelayed(playbackRunnable, animationFrames[currentFrameIndex].durationMs.toLong())
    }

    fun stopPlayback() {
        isPlaying = false
        playbackHandler.removeCallbacks(playbackRunnable)
    }

    fun setTextToolContent(value: String) {
        textToolContent = value.take(240).ifBlank { "Text" }
    }

    fun setDrawingTool(tool: DrawingTool) {
        drawingTool = tool
        invalidate()
    }

    private fun drawShapePreview(target: Canvas, endX: Float, endY: Float) {
        val engine = strokeEngine ?: return
        val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = engine.activeColor
            style = Paint.Style.STROKE
            strokeWidth = engine.brush.baseSize.coerceAtLeast(1f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val left = minOf(shapeStartX, endX)
        val top = minOf(shapeStartY, endY)
        val right = maxOf(shapeStartX, endX)
        val bottom = maxOf(shapeStartY, endY)
        when (drawingTool) {
            DrawingTool.LINE -> target.drawLine(shapeStartX, shapeStartY, endX, endY, shapePaint)
            DrawingTool.RECTANGLE -> target.drawRect(left, top, right, bottom, shapePaint)
            DrawingTool.ELLIPSE -> target.drawOval(left, top, right, bottom, shapePaint)
            DrawingTool.PANEL -> {
                shapePaint.strokeWidth = (engine.brush.baseSize * 0.18f).coerceAtLeast(4f)
                target.drawRect(left, top, right, bottom, shapePaint)
            }
            DrawingTool.SPEECH_BALLOON -> {
                val balloonBottom = (bottom - (bottom - top) * 0.18f).coerceAtLeast(top + 1f)
                target.drawOval(left, top, right, balloonBottom, shapePaint)
                val tail = Path().apply {
                    moveTo(left + (right - left) * 0.22f, balloonBottom - 2f)
                    lineTo(left + (right - left) * 0.12f, bottom)
                    lineTo(left + (right - left) * 0.42f, balloonBottom - 2f)
                    close()
                }
                target.drawPath(tail, shapePaint)
            }
            DrawingTool.TEXT -> {
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = engine.activeColor
                    textSize = engine.brush.baseSize.coerceAtLeast(12f) * 1.5f
                    style = Paint.Style.FILL
                    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
                }
                target.drawText(textToolContent, shapeStartX, shapeStartY, textPaint)
            }
            DrawingTool.BRUSH -> Unit
        }
    }

    fun setSelectionMode(mode: SelectionMode) {
        selectionMode = mode
        invalidate()
    }

    fun maskActiveLayerToSelection() {
        val rect = selectionRect ?: return
        val target = activeLayer
        val before = captureTiles(target, rect)
        val selectionMask = Bitmap.createBitmap(documentWidth, documentHeight, Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(selectionMask)
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        if (selectionIsEllipse) maskCanvas.drawOval(rect, maskPaint)
        else maskCanvas.drawRect(rect, maskPaint)
        val existing = target.maskBitmap
        if (existing == null) {
            val newMask = target.createMask(fillWhite = false)
            Canvas(newMask).drawBitmap(selectionMask, 0f, 0f, null)
        } else {
            Canvas(existing).drawBitmap(selectionMask, 0f, 0f, Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            })
        }
        selectionMask.recycle()
        invalidate()
    }

    fun createActiveLayerMask() {
        activeLayer.ensureMask()
        invalidate()
    }

    fun toggleActiveLayerMask() {
        if (activeLayer.maskBitmap != null) {
            activeLayer.maskEnabled = !activeLayer.maskEnabled
            invalidate()
        }
    }

    fun removeActiveLayerMask() {
        activeLayer.removeMask()
        invalidate()
    }

    fun clearSelection() {
        selectionRect = null
        selectionIsEllipse = false
        selectionMode = SelectionMode.NONE
        invalidate()
    }

    private fun updateSelection(point: StrokePoint) {
        selectionRect = RectF(
            minOf(selectionStartX, point.x),
            minOf(selectionStartY, point.y),
            maxOf(selectionStartX, point.x),
            maxOf(selectionStartY, point.y)
        )
        invalidate()
    }

    private fun applySelectionClip(target: Canvas): Int? {
        val rect = selectionRect ?: return null
        val save = target.save()
        if (selectionIsEllipse) {
            val path = Path().apply { addOval(rect, Path.Direction.CW) }
            target.clipPath(path)
        } else {
            target.clipRect(rect)
        }
        return save
    }

    private fun drawSelection(canvas: Canvas) {
        val rect = selectionRect ?: return
        val viewRect = RectF(rect)
        documentToView.mapRect(viewRect)
        val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(210, 190, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2f
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(9f, 6f), 0f)
        }
        if (selectionIsEllipse) canvas.drawOval(viewRect, selectionPaint)
        else canvas.drawRect(viewRect, selectionPaint)
    }

    fun setZoom(value: Float) {
        zoomFactor = value.coerceIn(0.25f, 16f)
        updateTransform()
        invalidate()
    }

    fun setRulers(enabled: Boolean) {
        showRulers = enabled
        invalidate()
    }

    private fun drawRulers(canvas: Canvas) {
        if (!showRulers) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(220, 210, 235)
            strokeWidth = 1f
            textSize = 14f
        }
        var x = 0
        while (x <= documentWidth) {
            val viewX = viewportRect.left + viewportRect.width() * x / documentWidth
            val major = x % 500 == 0
            canvas.drawLine(viewX, viewportRect.top, viewX, viewportRect.top + if (major) 18f else 8f, paint)
            if (major) canvas.drawText(x.toString(), viewX + 2f, viewportRect.top + 30f, paint)
            x += 100
        }
        var y = 0
        while (y <= documentHeight) {
            val viewY = viewportRect.top + viewportRect.height() * y / documentHeight
            val major = y % 500 == 0
            canvas.drawLine(viewportRect.left, viewY, viewportRect.left + if (major) 18f else 8f, viewY, paint)
            if (major && y > 0) canvas.drawText(y.toString(), viewportRect.left + 22f, viewY - 2f, paint)
            y += 100
        }
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
            applySelectionClip(mirroredCanvas)?.let { mirrorSaveCounts += mirroredCanvas to it }
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
        val scale = min(width.toFloat() / documentWidth, height.toFloat() / documentHeight) * zoomFactor
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
        val masked = if (layer.maskEnabled && layer.maskBitmap != null) {
            val result = source.copy(Bitmap.Config.ARGB_8888, true)
            Canvas(result).drawBitmap(layer.maskBitmap!!, 0f, 0f, Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            })
            result
        } else null
        val drawable = masked ?: source
        if (destination == null) {
            target.drawBitmap(drawable, 0f, 0f, paint)
            VectorRenderer.draw(target, layer.vectorPaths)
        } else {
            target.drawBitmap(drawable, null, destination, paint)
            val vectorSave = target.save()
            target.translate(destination.left, destination.top)
            target.scale(destination.width() / documentWidth, destination.height() / documentHeight)
            VectorRenderer.draw(target, layer.vectorPaths)
            target.restoreToCount(vectorSave)
        }
        masked?.recycle()
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
        if (onionSkinEnabled && currentFrameIndex > 0) {
            val previousFrame = animationFrames[currentFrameIndex - 1]
            val onionPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 42 }
            previousFrame.layers.filter { it.isVisible }.forEach { previousLayer ->
                canvas.drawBitmap(previousLayer.bitmap, null, viewportRect, onionPaint)
            }
        }
        var clipBase: Bitmap? = null
        layers.filter { it.isVisible }.forEach { layer ->
            layerPaint.alpha = (layer.opacity.coerceIn(0f, 1f) * 255f).toInt()
            layerPaint.xfermode = layer.blendMode.porterDuffMode?.let { PorterDuffXfermode(it) }
            layer.applyColorAdjustments(layerPaint)
            drawLayer(canvas, layer, layerPaint, viewportRect, clipBase)
            layerPaint.colorFilter = null
            if (!layer.isClippedToBelow) clipBase = layer.bitmap
        }
        layerPaint.xfermode = null
        layerPaint.alpha = 255
        canvas.drawBitmap(activeBitmap, null, viewportRect, bitmapPaint)
        canvas.restoreToCount(save)
        drawGrid(canvas)
        drawSelection(canvas)
        drawRulers(canvas)
    }

    fun addVectorPath(path: VectorPath) {
        activeLayer.vectorPaths.add(path)
        invalidate()
    }

    fun removeVectorPath(id: String) {
        activeLayer.vectorPaths.removeAll { it.id == id }
        invalidate()
    }

    fun moveVectorNode(pathId: String, nodeIndex: Int, x: Float, y: Float) {
        val path = activeLayer.vectorPaths.firstOrNull { it.id == pathId } ?: return
        if (nodeIndex !in path.nodes.indices) return
        val updated = path.nodes.toMutableList()
        val node = updated[nodeIndex]
        val dx = x - node.x
        val dy = y - node.y
        updated[nodeIndex] = node.copy(
            x = x, y = y,
            controlInX = node.controlInX + dx, controlInY = node.controlInY + dy,
            controlOutX = node.controlOutX + dx, controlOutY = node.controlOutY + dy
        )
        val index = activeLayer.vectorPaths.indexOf(path)
        activeLayer.vectorPaths[index] = path.copy(nodes = updated)
        invalidate()
    }

    fun addLayer(name: String = "Layer ${layers.size}"): Boolean {
        if (layers.size >= maxLayerCount) return false
        layers.add(ArtLayer(name = name, width = documentWidth, height = documentHeight))
        activeLayerIndex = layers.lastIndex
        invalidate()
        return true
    }

    fun duplicateActiveLayer(): Boolean {
        if (layers.size >= maxLayerCount) return false
        val source = activeLayer
        val copy = ArtLayer(
            name = "${source.name} copy", width = documentWidth, height = documentHeight,
            opacity = source.opacity, isVisible = source.isVisible,
            isAlphaLocked = source.isAlphaLocked, isClippedToBelow = source.isClippedToBelow,
            brightness = source.brightness, contrast = source.contrast, saturation = source.saturation,
            blendMode = source.blendMode
        )
        copy.canvas.drawBitmap(source.bitmap, 0f, 0f, null)
        copy.vectorPaths.addAll(source.vectorPaths)
        source.maskBitmap?.let { mask -> copy.createMask(false).also { Canvas(it).drawBitmap(mask, 0f, 0f, null) } }
        copy.maskEnabled = source.maskEnabled
        layers.add(activeLayerIndex + 1, copy)
        activeLayerIndex++
        invalidate()
        return true
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
        upper.applyColorAdjustments(paint)
        drawLayer(lower.canvas, upper, paint, clipBase = lower.bitmap)
        paint.colorFilter = null
        lower.opacity = 1f
        layers.removeAt(activeLayerIndex)
        activeLayerIndex--
        invalidate()
    }

    fun replaceLayers(newLayers: List<ArtLayer>) {
        require(newLayers.isNotEmpty())
        require(newLayers.size <= maxLayerCount) { "This project has more layers than this canvas can safely hold on this device." }
        require(newLayers.all { it.width == documentWidth && it.height == documentHeight })
        layers = newLayers.toMutableList()
        animationFrames.clear()
        animationFrames.add(AnimationFrame(100, layers))
        currentFrameIndex = 0
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
            layer.applyColorAdjustments(paint)
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
            paint.colorFilter = null
            clipped?.recycle()
            if (!layer.isClippedToBelow) clipBase = layer.bitmap
        }
        layers.clear()
        layers.add(merged)
        activeLayerIndex = 0
        invalidate()
    }

    /** Renders the visible stack without flattening or mutating the editable layers. */
    private fun renderLayersComposite(sourceLayers: List<ArtLayer>, backgroundColor: Int? = null): Bitmap {
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
        sourceLayers.filter { it.isVisible }.forEach { layer ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                alpha = (layer.opacity.coerceIn(0f, 1f) * 255f).toInt()
                xfermode = layer.blendMode.porterDuffMode?.let { PorterDuffXfermode(it) }
            }
            layer.applyColorAdjustments(paint)
            val layerRaster = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true)
            VectorRenderer.draw(Canvas(layerRaster), layer.vectorPaths)
            val clipped = if (layer.isClippedToBelow && clipBase != null) {
                Bitmap.createBitmap(documentWidth, documentHeight, Bitmap.Config.ARGB_8888).also { temp ->
                    val tempCanvas = Canvas(temp)
                    tempCanvas.drawBitmap(layerRaster, 0f, 0f, null)
                    tempCanvas.drawBitmap(clipBase!!, 0f, 0f, Paint().apply {
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    })
                }
            } else null
            val source = clipped ?: layerRaster
            val masked = if (layer.maskEnabled && layer.maskBitmap != null) {
                val result = source.copy(Bitmap.Config.ARGB_8888, true)
                Canvas(result).drawBitmap(layer.maskBitmap!!, 0f, 0f, Paint().apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                })
                result
            } else null
            outputCanvas.drawBitmap(masked ?: source, 0f, 0f, paint)
            masked?.recycle()
            clipped?.recycle()
            layerRaster.recycle()
            if (!layer.isClippedToBelow) clipBase = layer.bitmap
        }
        return output
    }

    fun renderComposite(backgroundColor: Int? = null): Bitmap =
        renderLayersComposite(layers, backgroundColor)

    fun renderFrameComposite(index: Int, backgroundColor: Int? = null): Bitmap {
        val frame = animationFrames.getOrNull(index) ?: error("Animation frame does not exist.")
        return renderLayersComposite(frame.layers, backgroundColor)
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
        if (!strokeActive) return
        strokeActive = false
        if (drawingTool == DrawingTool.BRUSH) strokeEngine?.finishStroke(activeCanvas)
        symmetryEngines.forEach { (mirrorCanvas, engine) -> engine.finishStroke(mirrorCanvas) }
        activeCanvasSaveCount?.let { activeCanvas.restoreToCount(it) }
        activeCanvasSaveCount = null
        mirrorSaveCounts.forEach { (mirrorCanvas, saveCount) -> mirrorCanvas.restoreToCount(saveCount) }
        mirrorSaveCounts.clear()
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
        activeCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        symmetryEngines.clear()
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
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            isNavigating = true
            lastFocusX = (0 until event.pointerCount).map { event.getX(it) }.average().toFloat()
            lastFocusY = (0 until event.pointerCount).map { event.getY(it) }.average().toFloat()
            commitStroke()
            return true
        }
        if (isNavigating) {
            if (event.pointerCount > 1 && event.actionMasked == MotionEvent.ACTION_MOVE) {
                val focusX = (0 until event.pointerCount).map { event.getX(it) }.average().toFloat()
                val focusY = (0 until event.pointerCount).map { event.getY(it) }.average().toFloat()
                panX += focusX - lastFocusX
                panY += focusY - lastFocusY
                lastFocusX = focusX
                lastFocusY = focusY
                updateTransform()
                invalidate()
            } else if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                val remaining = (0 until event.pointerCount).filter { it != event.actionIndex }
                if (remaining.isNotEmpty()) {
                    lastFocusX = remaining.map { event.getX(it) }.average().toFloat()
                    lastFocusY = remaining.map { event.getY(it) }.average().toFloat()
                }
            } else if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                isNavigating = false
            }
            return true
        }
        if (selectionMode != SelectionMode.NONE) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!viewportRect.contains(event.x, event.y)) return false
                    val point = documentPoint(event)
                    selectionStartX = point.x
                    selectionStartY = point.y
                    isSelecting = true
                    selectionRect = RectF(point.x, point.y, point.x, point.y)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isSelecting) updateSelection(documentPoint(event))
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isSelecting) {
                        updateSelection(documentPoint(event))
                        selectionIsEllipse = selectionMode == SelectionMode.ELLIPSE
                        selectionMode = SelectionMode.NONE
                        isSelecting = false
                        performClick()
                    }
                    return true
                }
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!viewportRect.contains(event.x, event.y)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                previousInputTime = 0L
                val point = documentPoint(event)
                dirtyBounds.setEmpty()
                hasDirtyBounds = false
                trackDirty(point)
                strokeActive = true
                activeCanvasSaveCount = applySelectionClip(activeCanvas)
                if (drawingTool == DrawingTool.BRUSH) {
                    strokeEngine?.startStroke(point, activeCanvas)
                    prepareSymmetryEngines()
                    symmetryEngines.forEach { (mirrorCanvas, engine) -> engine.startStroke(point, mirrorCanvas) }
                } else {
                    shapeStartX = point.x
                    shapeStartY = point.y
                    drawShapePreview(activeCanvas, point.x, point.y)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val point = documentPoint(event)
                trackDirty(point)
                if (drawingTool == DrawingTool.BRUSH) {
                    strokeEngine?.continueStroke(point, activeCanvas)
                    symmetryEngines.forEach { (mirrorCanvas, engine) -> engine.continueStroke(point, mirrorCanvas) }
                } else {
                    activeCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    drawShapePreview(activeCanvas, point.x, point.y)
                }
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

    override fun onDetachedFromWindow() {
        stopPlayback()
        super.onDetachedFromWindow()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
