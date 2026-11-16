package com.artstudio.pro.core.canvas

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.artstudio.pro.core.model.LayerGroup
import com.artstudio.pro.core.vector.VectorPath
import com.artstudio.pro.core.vector.VectorNode
import com.artstudio.pro.core.vector.VectorRenderer
import com.artstudio.pro.core.filters.FilterEngine
import com.artstudio.pro.core.filters.ImageFilter
import com.artstudio.pro.core.comic.ComicLayoutEngine
import com.artstudio.pro.core.paper.PaperKind
import com.artstudio.pro.core.paper.PaperTexture
import kotlin.math.min

enum class SymmetryMode { NONE, VERTICAL, HORIZONTAL, QUADRANT }
enum class SelectionMode { NONE, RECTANGLE, ELLIPSE, LASSO, COLOR_RANGE }
enum class PerspectiveMode { OFF, ONE_POINT, TWO_POINT, THREE_POINT }
enum class DrawingTool { BRUSH, LINE, RECTANGLE, ELLIPSE, PANEL, SPEECH_BALLOON, TEXT, VECTOR_EDIT, FILL, GRADIENT, FILLED_RECTANGLE, FILLED_ELLIPSE }

/** Fixed-resolution document viewport with a non-destructive raster layer stack. */
class ArtCanvasView(context: Context, val documentWidth: Int, val documentHeight: Int) : View(context) {
    val layerGroups = mutableListOf<LayerGroup>()
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
    var isEditingLayerMask: Boolean = false
        private set
    var paperKind: PaperKind = PaperKind.SMOOTH
        private set
    var paperStrength: Float = 1f
        private set
    var showGrid: Boolean = false
        private set
    var showRulers: Boolean = false
        private set
    var safeMarginPixels: Int = 0
        private set
    var gridSpacing: Int = 64
        private set
    var symmetryMode: SymmetryMode = SymmetryMode.NONE
        private set
    var perspectiveMode: PerspectiveMode = PerspectiveMode.OFF
        private set
    var zoomFactor: Float = 1f
        private set
    var selectionMode: SelectionMode = SelectionMode.NONE
        private set
    var selectionTolerance: Int = 24
        private set
    var drawingTool: DrawingTool = DrawingTool.BRUSH
        private set
    private var shapeStartX = 0f
    private var shapeStartY = 0f
    var textToolContent: String = "Text"
        private set
    var gradientEndColor: Int = Color.WHITE
        private set
    private var selectedVectorPathId: String? = null
    private var selectedVectorNodeIndex: Int? = null
    private var draggingVectorNode = false
    val hasSelection: Boolean get() = selectionRect != null || selectionPath != null || selectionMaskBitmap != null
    private var selectionRect: RectF? = null
    private var selectionPath: Path? = null
    private var selectionMaskBitmap: Bitmap? = null
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
    private data class StrokeChange(val layerId: String, val before: List<TileState>, val after: List<TileState>, val isMask: Boolean = false, val maskBeforePng: ByteArray? = null, val maskAfterPng: ByteArray? = null)
    private val undoHistory = ArrayDeque<StrokeChange>()
    private val redoHistory = ArrayDeque<StrokeChange>()
    private val dirtyBounds = RectF()
    private var hasDirtyBounds = false
    private val undoTileSize = 128
    private val maxUndoEntries = 200

    val canUndo: Boolean get() = undoHistory.isNotEmpty()
    val canRedo: Boolean get() = redoHistory.isNotEmpty()

    private fun captureTiles(layer: ArtLayer, bounds: RectF, source: Bitmap = layer.bitmap): List<TileState> {
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
                result += TileState(x, y, Bitmap.createBitmap(source, x, y, tileRight - x, tileBottom - y))
                x += undoTileSize
            }
            y += undoTileSize
        }
        return result
    }

    private fun restoreTiles(layer: ArtLayer, tiles: List<TileState>, destination: Bitmap = layer.bitmap) {
        val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        tiles.forEach { tile -> Canvas(destination).drawBitmap(tile.bitmap, tile.x.toFloat(), tile.y.toFloat(), paint) }
    }

    private fun compressMask(bitmap: Bitmap): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Could not snapshot layer mask." }
        return output.toByteArray()
    }

    private fun restoreMask(layer: ArtLayer, encoded: ByteArray) {
        val decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.size) ?: return
        val target = layer.ensureMask()
        Canvas(target).drawBitmap(decoded, 0f, 0f, Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        })
        decoded.recycle()
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
        if (change.maskBeforePng != null) restoreMask(layer, change.maskBeforePng)
        else restoreTiles(layer, change.before, if (change.isMask) layer.ensureMask() else layer.bitmap)
        redoHistory.addLast(change)
        invalidate()
    }

    fun redo() {
        val change = redoHistory.removeLastOrNull() ?: return
        val layer = layers.firstOrNull { it.id == change.layerId }
        if (layer == null) { releaseChange(change); return }
        if (change.maskAfterPng != null) restoreMask(layer, change.maskAfterPng)
        else restoreTiles(layer, change.after, if (change.isMask) layer.ensureMask() else layer.bitmap)
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
            blendMode = source.blendMode,
            groupId = source.groupId
        ).also {
            if (copyPixels) {
                it.canvas.drawBitmap(source.bitmap, 0f, 0f, null)
                it.vectorPaths.addAll(source.vectorPaths)
                source.maskBitmap?.let { mask ->
                    it.createMask(false).also { targetMask -> Canvas(targetMask).drawBitmap(mask, 0f, 0f, null) }
                }
            }
            it.maskEnabled = source.maskEnabled
        }
    }

    fun selectFrame(index: Int) {
        if (index !in animationFrames.indices || index == currentFrameIndex) return
        commitStroke()
        isEditingLayerMask = false
        currentFrameIndex = index
        layers = animationFrames[index].layers
        activeLayerIndex = activeLayerIndex.coerceIn(0, layers.lastIndex)
        clearHistory()
        invalidate()
    }

    private fun canAddAnimationFrame(): Boolean {
        if (animationFrames.size >= 120) return false
        val existingLayerCount = animationFrames.sumOf { frame -> frame.layers.sumOf { 1L + if (it.maskBitmap != null) 1L else 0L } }
        val addedLayerCount = layers.sumOf { 1L + if (it.maskBitmap != null) 1L else 0L }
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
            layers.forEach { it.clear(); it.removeMask(); it.vectorPaths.clear() }
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
        require(frames.sumOf { frame -> frame.layers.sumOf { 1L + if (it.maskBitmap != null) 1L else 0L } } * documentWidth.toLong() * documentHeight.toLong() <= 64_000_000L)
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

    fun setGradientEndColor(color: Int) { gradientEndColor = color; invalidate() }

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
            DrawingTool.FILLED_RECTANGLE -> { shapePaint.style = Paint.Style.FILL; target.drawRect(left, top, right, bottom, shapePaint) }
            DrawingTool.FILLED_ELLIPSE -> { shapePaint.style = Paint.Style.FILL; target.drawOval(left, top, right, bottom, shapePaint) }
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
            DrawingTool.GRADIENT -> {
                shapePaint.style = Paint.Style.FILL
                shapePaint.shader = android.graphics.LinearGradient(
                    shapeStartX, shapeStartY, endX, endY,
                    engine.activeColor, gradientEndColor, android.graphics.Shader.TileMode.CLAMP
                )
                target.drawRect(left, top, right, bottom, shapePaint)
                shapePaint.shader = null
            }
            DrawingTool.BRUSH, DrawingTool.VECTOR_EDIT, DrawingTool.FILL -> Unit
        }
    }

    fun setSelectionTolerance(value: Int) { selectionTolerance = value.coerceIn(0,255) }

    fun setSelectionMode(mode: SelectionMode) {
        selectionMode = mode
        invalidate()
    }

    fun maskActiveLayerToSelection() {
        val rect = selectionRect ?: selectionPath?.let { path -> RectF().also { path.computeBounds(it, true) } } ?: return
        val target = activeLayer
        val mask = target.ensureMask()
        val before = compressMask(mask)
        val selectionMask = selectionMaskBitmap ?: Bitmap.createBitmap(documentWidth, documentHeight, Bitmap.Config.ARGB_8888).also { created ->
            val maskCanvas = Canvas(created)
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            when {
                selectionPath != null -> maskCanvas.drawPath(selectionPath!!, maskPaint)
                selectionIsEllipse -> maskCanvas.drawOval(rect, maskPaint)
                else -> maskCanvas.drawRect(rect, maskPaint)
            }
        }
        Canvas(mask).drawBitmap(selectionMask, 0f, 0f, Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        })
        if (selectionMaskBitmap == null) selectionMask.recycle()
        val after = compressMask(mask)
        redoHistory.forEach(::releaseChange)
        redoHistory.clear()
        undoHistory.addLast(StrokeChange(target.id, emptyList(), emptyList(), true, before, after))
        while (undoHistory.size > maxUndoEntries) releaseChange(undoHistory.removeFirst())
        invalidate()
    }

    fun toggleActiveLayerMaskEditing() {
        if (activeLayer.maskBitmap == null) activeLayer.ensureMask()
        isEditingLayerMask = !isEditingLayerMask
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
        isEditingLayerMask = false
        invalidate()
    }

    fun clearSelection() {
        selectionRect = null
        selectionPath = null
        selectionMaskBitmap?.recycle()
        selectionMaskBitmap = null
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
        val path = selectionPath
        val rect = selectionRect
        if (selectionMaskBitmap != null) return null
        if (path == null && rect == null) return null
        val save = target.save()
        if (path != null) target.clipPath(path)
        else if (selectionIsEllipse) target.clipPath(Path().apply { addOval(rect!!, Path.Direction.CW) })
        else target.clipRect(rect!!)
        return save
    }

    private fun drawVectorNodeHandles(canvas: Canvas) {
        val pathId = selectedVectorPathId ?: return
        val path = activeLayer.vectorPaths.firstOrNull { it.id == pathId } ?: return
        val save = canvas.save()
        canvas.concat(documentToView)
        val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 205, 120); style = Paint.Style.FILL }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f }
        path.nodes.forEachIndexed { index, node ->
            val radius = if (index == selectedVectorNodeIndex) 18f else 12f
            canvas.drawCircle(node.x, node.y, radius, handlePaint)
            canvas.drawCircle(node.x, node.y, radius, outline)
        }
        canvas.restoreToCount(save)
    }

    private fun hitTestVectorNode(point: StrokePoint): Pair<String, Int>? {
        val radius = 36f / zoomFactor.coerceAtLeast(.25f)
        var best: Pair<String, Int>? = null
        var bestDistance = radius
        activeLayer.vectorPaths.forEach { path ->
            path.nodes.forEachIndexed { index, node ->
                val distance = kotlin.math.hypot(node.x - point.x, node.y - point.y)
                if (distance < bestDistance) { bestDistance = distance; best = path.id to index }
            }
        }
        return best
    }

    private fun drawSelection(canvas: Canvas) {
        val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(210, 190, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2f
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(9f, 6f), 0f)
        }
        selectionMaskBitmap?.let { mask ->
            val overlay = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                alpha = 80
                colorFilter = android.graphics.PorterDuffColorFilter(Color.CYAN, PorterDuff.Mode.SRC_ATOP)
            }
            canvas.drawBitmap(mask,null,viewportRect,overlay)
        }
        selectionPath?.let { path ->
            val viewPath = Path(path)
            viewPath.transform(documentToView)
            canvas.drawPath(viewPath, selectionPaint)
            return
        }
        val rect = selectionRect ?: return
        val viewRect = RectF(rect)
        documentToView.mapRect(viewRect)
        if (selectionIsEllipse) canvas.drawOval(viewRect, selectionPaint)
        else canvas.drawRect(viewRect, selectionPaint)
    }

    fun setZoom(value: Float) {
        zoomFactor = value.coerceIn(0.25f, 16f)
        updateTransform()
        invalidate()
    }

    fun setSafeMargin(marginPixels: Int) {
        safeMarginPixels = marginPixels.coerceIn(0, minOf(documentWidth, documentHeight) / 3)
        invalidate()
    }

    private fun drawSafeMargin(canvas: Canvas) {
        if (safeMarginPixels <= 0) return
        val safe = RectF(
            safeMarginPixels.toFloat(), safeMarginPixels.toFloat(),
            (documentWidth-safeMarginPixels).toFloat(), (documentHeight-safeMarginPixels).toFloat()
        )
        documentToView.mapRect(safe)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 90, 210, 160)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 6f), 0f)
        }
        canvas.drawRect(safe, paint)
    }

    fun setPerspectiveMode(mode: PerspectiveMode) {
        perspectiveMode = mode
        invalidate()
    }

    private fun drawPerspectiveGuides(canvas: Canvas) {
        if (perspectiveMode == PerspectiveMode.OFF) return
        val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(125, 115, 190, 235)
            strokeWidth = 1.5f
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
        }
        val left = floatArrayOf(0f, documentHeight * .42f)
        val right = floatArrayOf(documentWidth.toFloat(), documentHeight * .42f)
        val top = floatArrayOf(documentWidth * .5f, 0f)
        val bottomLeft = floatArrayOf(0f, documentHeight.toFloat())
        val bottomRight = floatArrayOf(documentWidth.toFloat(), documentHeight.toFloat())
        fun line(a: FloatArray, b: FloatArray) {
            val points = floatArrayOf(a[0],a[1],b[0],b[1])
            documentToView.mapPoints(points)
            canvas.drawLine(points[0],points[1],points[2],points[3],guide)
        }
        when (perspectiveMode) {
            PerspectiveMode.ONE_POINT -> {
                val vanishing = floatArrayOf(documentWidth*.5f,documentHeight*.42f)
                line(vanishing,floatArrayOf(0f,0f));line(vanishing,floatArrayOf(documentWidth.toFloat(),0f))
                line(vanishing,bottomLeft);line(vanishing,bottomRight)
            }
            PerspectiveMode.TWO_POINT -> {
                val lv=floatArrayOf(documentWidth*.08f,documentHeight*.42f)
                val rv=floatArrayOf(documentWidth*.92f,documentHeight*.42f)
                line(lv,floatArrayOf(0f,0f));line(lv,bottomLeft);line(lv,floatArrayOf(documentWidth*.5f,documentHeight*.65f))
                line(rv,floatArrayOf(documentWidth.toFloat(),0f));line(rv,bottomRight);line(rv,floatArrayOf(documentWidth*.5f,documentHeight*.65f))
            }
            PerspectiveMode.THREE_POINT -> {
                val lv=floatArrayOf(documentWidth*.08f,documentHeight*.55f)
                val rv=floatArrayOf(documentWidth*.92f,documentHeight*.55f)
                val tv=floatArrayOf(documentWidth*.5f,-documentHeight*.7f)
                line(lv,floatArrayOf(0f,0f));line(lv,bottomLeft);line(rv,floatArrayOf(documentWidth.toFloat(),0f));line(rv,bottomRight)
                line(tv,floatArrayOf(0f,0f));line(tv,floatArrayOf(documentWidth.toFloat(),0f));line(tv,bottomLeft);line(tv,bottomRight)
            }
            PerspectiveMode.OFF -> Unit
        }
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

    private fun createLayerAlphaSource(layer: ArtLayer): Bitmap =
        layer.renderMaskedBitmap().also { VectorRenderer.draw(Canvas(it), layer.vectorPaths) }

    private fun drawLayer(
        target: Canvas,
        layer: ArtLayer,
        paint: Paint,
        destination: RectF? = null,
        clipBase: Bitmap? = null
    ) {
        val vectorRaster = if (layer.vectorPaths.isNotEmpty()) {
            layer.bitmap.copy(Bitmap.Config.ARGB_8888, true).also { VectorRenderer.draw(Canvas(it), layer.vectorPaths) }
        } else layer.bitmap
        val clipped = if (layer.isClippedToBelow && clipBase != null) {
            Bitmap.createBitmap(documentWidth, documentHeight, Bitmap.Config.ARGB_8888).also { temp ->
                val tempCanvas = Canvas(temp)
                tempCanvas.drawBitmap(vectorRaster, 0f, 0f, null)
                tempCanvas.drawBitmap(clipBase, 0f, 0f, Paint().apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                })
            }
        } else null
        val source = clipped ?: vectorRaster
        val masked = if (layer.maskEnabled && layer.maskBitmap != null) {
            Bitmap.createBitmap(documentWidth, documentHeight, Bitmap.Config.ARGB_8888).also { result ->
                Canvas(result).drawBitmap(source, 0f, 0f, null)
                Canvas(result).drawBitmap(layer.maskBitmap!!, 0f, 0f, Paint().apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                })
            }
        } else null
        val drawable = masked ?: source
        if (destination == null) target.drawBitmap(drawable, 0f, 0f, paint)
        else target.drawBitmap(drawable, null, destination, paint)
        masked?.recycle()
        clipped?.recycle()
        if (vectorRaster !== layer.bitmap) vectorRaster.recycle()
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
            previousFrame.layers.filter { isLayerVisible(it) }.forEach { previousLayer ->
                val faded = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    alpha = (previousLayer.opacity * 42f).toInt().coerceIn(0, 255)
                }
                drawLayer(canvas, previousLayer, faded, viewportRect)
            }
        }
        if (onionSkinEnabled && currentFrameIndex < animationFrames.lastIndex) {
            val nextFrame = animationFrames[currentFrameIndex + 1]
            nextFrame.layers.filter { isLayerVisible(it) }.forEach { nextLayer ->
                val faded = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    alpha = (nextLayer.opacity * 30f).toInt().coerceIn(0, 255)
                    colorFilter = android.graphics.PorterDuffColorFilter(Color.CYAN, PorterDuff.Mode.SRC_ATOP)
                }
                drawLayer(canvas, nextLayer, faded, viewportRect)
            }
        }
        var clipBase: Bitmap? = null
        layers.filter { isLayerVisible(it) }.forEach { layer ->
            layerPaint.alpha = (layer.opacity.coerceIn(0f, 1f) * 255f).toInt()
            layer.blendMode.applyTo(layerPaint)
            layer.applyColorAdjustments(layerPaint)
            drawLayer(canvas, layer, layerPaint, viewportRect, clipBase)
            layerPaint.colorFilter = null
            if (!layer.isClippedToBelow) {
                clipBase?.recycle()
                clipBase = createLayerAlphaSource(layer)
            }
        }
        clipBase?.recycle()
        layerPaint.xfermode = null
        layerPaint.alpha = 255
        canvas.drawBitmap(activeBitmap, null, viewportRect, bitmapPaint)
        canvas.restoreToCount(save)
        drawGrid(canvas)
        drawPerspectiveGuides(canvas)
        drawSelection(canvas)
        drawVectorNodeHandles(canvas)
        drawRulers(canvas)
        drawSafeMargin(canvas)
    }

    fun transformActiveLayer(scaleX: Float = 1f, scaleY: Float = 1f, rotationDegrees: Float = 0f, translateX: Float = 0f, translateY: Float = 0f) {
        val layer = activeLayer
        val cx = documentWidth / 2f
        val cy = documentHeight / 2f
        fun transformed(source: Bitmap): Bitmap {
            val result = Bitmap.createBitmap(documentWidth, documentHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            canvas.translate(cx + translateX, cy + translateY)
            canvas.rotate(rotationDegrees)
            canvas.scale(scaleX.coerceIn(-4f,4f), scaleY.coerceIn(-4f,4f))
            canvas.translate(-cx, -cy)
            canvas.drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            return result
        }
        val transformedPixels = transformed(layer.bitmap)
        layer.bitmap.eraseColor(Color.TRANSPARENT)
        layer.canvas.drawBitmap(transformedPixels, 0f, 0f, null)
        transformedPixels.recycle()
        layer.maskBitmap?.let { mask ->
            val transformedMask = transformed(mask)
            Canvas(mask).drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            Canvas(mask).drawBitmap(transformedMask, 0f, 0f, null)
            transformedMask.recycle()
        }
        val radians = Math.toRadians(rotationDegrees.toDouble())
        val cosA = kotlin.math.cos(radians).toFloat()
        val sinA = kotlin.math.sin(radians).toFloat()
        layer.vectorPaths.indices.forEach { pathIndex ->
            val path = layer.vectorPaths[pathIndex]
            val nodes = path.nodes.map { node ->
                fun point(x:Float,y:Float):Pair<Float,Float>{
                    val sx=(x-cx)*scaleX+translateX
                    val sy=(y-cy)*scaleY+translateY
                    return Pair(cx+sx*cosA-sy*sinA,cy+sx*sinA+sy*cosA)
                }
                val p=point(node.x,node.y);val incoming=point(node.controlInX,node.controlInY);val outgoing=point(node.controlOutX,node.controlOutY)
                VectorNode(p.first,p.second,incoming.first,incoming.second,outgoing.first,outgoing.second)
            }
            layer.vectorPaths[pathIndex]=path.copy(nodes=nodes)
        }
        invalidate()
    }

    fun createFilterLayer(filter: ImageFilter, amount: Float = 1f): Boolean {
        if (layers.size >= maxLayerCount) return false
        val source = activeLayer.renderMaskedBitmap()
        val filtered = try { FilterEngine.apply(source, filter, amount) } finally { source.recycle() }
        val layer = ArtLayer(name = "${filter.label} adjustment", width = documentWidth, height = documentHeight)
        layer.canvas.drawBitmap(filtered, 0f, 0f, null)
        filtered.recycle()
        layers.add(activeLayerIndex + 1, layer)
        activeLayerIndex++
        invalidate()
        return true
    }

    fun addComicGrid(rows: Int = 2, columns: Int = 2) {
        val page = ComicLayoutEngine.grid(documentWidth, documentHeight, rows, columns, minOf(documentWidth, documentHeight) * .018f)
        page.panels.forEach { panel ->
            val b = panel.bounds
            if (b.size >= 4) {
                val nodes = listOf(
                    VectorNode(b[0], b[1]), VectorNode(b[2], b[1]),
                    VectorNode(b[2], b[3]), VectorNode(b[0], b[3])
                )
                activeLayer.vectorPaths.add(VectorPath(
                    id = java.util.UUID.randomUUID().toString(),
                    nodes = nodes, closed = true,
                    strokeColor = Color.BLACK, strokeWidth = minOf(documentWidth, documentHeight) * .003f,
                    name = "Comic panel ${panel.order + 1}"
                ))
            }
        }
        invalidate()
    }

    fun addVectorRectangle() {
        val cx = documentWidth / 2f
        val cy = documentHeight / 2f
        val w = documentWidth * .35f
        val h = documentHeight * .25f
        val color = strokeEngine?.activeColor ?: Color.BLACK
        addVectorPath(VectorPath(
            id = java.util.UUID.randomUUID().toString(),
            nodes = listOf(
                VectorNode(cx-w/2,cy-h/2),VectorNode(cx+w/2,cy-h/2),
                VectorNode(cx+w/2,cy+h/2),VectorNode(cx-w/2,cy+h/2)
            ),
            closed = true, strokeColor = color, strokeWidth = 8f, name = "Vector rectangle"
        ))
    }

    fun addVectorEllipse() {
        val cx = documentWidth / 2f
        val cy = documentHeight / 2f
        val rx = documentWidth * .18f
        val ry = documentHeight * .13f
        val k = .55228475f
        val color = strokeEngine?.activeColor ?: Color.BLACK
        addVectorPath(VectorPath(
            id = java.util.UUID.randomUUID().toString(),
            nodes = listOf(
                VectorNode(cx,cy-ry,cx-rx*k,cy-ry,cx+rx*k,cy-ry),
                VectorNode(cx+rx,cy,cx+rx,cy-ry*k,cx+rx,cy+ry*k),
                VectorNode(cx,cy+ry,cx+rx*k,cy+ry,cx-rx*k,cy+ry),
                VectorNode(cx-rx,cy,cx-rx,cy+ry*k,cx-rx,cy-ry*k)
            ),
            closed = true, strokeColor = color, strokeWidth = 8f, name = "Vector ellipse"
        ))
    }

    fun addVectorLine() {
        val color = strokeEngine?.activeColor ?: Color.BLACK
        addVectorPath(VectorPath(
            id = java.util.UUID.randomUUID().toString(),
            nodes = listOf(
                VectorNode(documentWidth*.2f,documentHeight*.5f),
                VectorNode(documentWidth*.8f,documentHeight*.5f)
            ),
            strokeColor = color, strokeWidth = 8f, name = "Vector line"
        ))
    }

    fun selectColorRange(x: Float, y: Float, tolerance: Int = 24): Boolean {
        val width = documentWidth
        val height = documentHeight
        val count = width.toLong() * height
        if (count > 4_000_000L) return false
        val pixels = IntArray(count.toInt())
        activeLayer.bitmap.getPixels(pixels,0,width,0,0,width,height)
        val sx=x.toInt().coerceIn(0,width-1);val sy=y.toInt().coerceIn(0,height-1)
        val seed=pixels[sy*width+sx]
        val limit=tolerance.coerceIn(0,255).let{it*it*3}
        fun matches(c:Int):Boolean {
            if(Color.alpha(c)==0&&Color.alpha(seed)==0)return true
            val dr=Color.red(c)-Color.red(seed);val dg=Color.green(c)-Color.green(seed);val db=Color.blue(c)-Color.blue(seed)
            return dr*dr+dg*dg+db*db<=limit
        }
        val visited=BooleanArray(count.toInt());val queue=IntArray(count.toInt())
        var head=0;var tail=0;val start=sy*width+sx;queue[tail++]=start;visited[start]=true
        var left=sx;var right=sx;var top=sy;var bottom=sy
        val maskPixels=IntArray(count.toInt())
        while(head<tail){
            val index=queue[head++];val px=index%width;val py=index/width
            if(!matches(pixels[index]))continue
            maskPixels[index]=Color.WHITE
            left=minOf(left,px);right=maxOf(right,px);top=minOf(top,py);bottom=maxOf(bottom,py)
            if(px>0){val n=index-1;if(!visited[n]&&matches(pixels[n])){visited[n]=true;queue[tail++]=n}}
            if(px<width-1){val n=index+1;if(!visited[n]&&matches(pixels[n])){visited[n]=true;queue[tail++]=n}}
            if(py>0){val n=index-width;if(!visited[n]&&matches(pixels[n])){visited[n]=true;queue[tail++]=n}}
            if(py<height-1){val n=index+width;if(!visited[n]&&matches(pixels[n])){visited[n]=true;queue[tail++]=n}}
        }
        selectionMaskBitmap?.recycle()
        selectionMaskBitmap=Bitmap.createBitmap(maskPixels,width,height,Bitmap.Config.ARGB_8888)
        selectionRect=RectF(left.toFloat(),top.toFloat(),(right+1).toFloat(),(bottom+1).toFloat())
        selectionPath=null;selectionIsEllipse=false;selectionMode=SelectionMode.NONE
        invalidate()
        return true
    }

    fun floodFillAt(x: Float, y: Float, color: Int, tolerance: Int = 24): Boolean {
        val width = documentWidth
        val height = documentHeight
        val count = width.toLong() * height
        if (count > 4_000_000L) return false
        val pixels = IntArray(count.toInt())
        activeLayer.bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val sx = x.toInt().coerceIn(0, width - 1)
        val sy = y.toInt().coerceIn(0, height - 1)
        val seed = pixels[sy * width + sx]
        if (seed == color) return true
        val threshold = tolerance.coerceIn(0, 255) * tolerance.coerceIn(0, 255) * 3
        fun matches(pixel: Int): Boolean {
            if (Color.alpha(pixel) == 0 && Color.alpha(seed) == 0) return true
            val dr = Color.red(pixel) - Color.red(seed)
            val dg = Color.green(pixel) - Color.green(seed)
            val db = Color.blue(pixel) - Color.blue(seed)
            return dr * dr + dg * dg + db * db <= threshold
        }
        val visited = BooleanArray(count.toInt())
        val queue = IntArray(count.toInt())
        var head = 0
        var tail = 0
        val start = sy * width + sx
        queue[tail++] = start
        visited[start] = true
        var left = sx; var right = sx; var top = sy; var bottom = sy
        while (head < tail) {
            val index = queue[head++]
            val px = index % width; val py = index / width
            if (!matches(pixels[index])) continue
            left = minOf(left, px); right = maxOf(right, px)
            top = minOf(top, py); bottom = maxOf(bottom, py)
            if (px > 0) { val n=index-1; if(!visited[n]&&matches(pixels[n])){visited[n]=true;queue[tail++]=n} }
            if (px < width-1) { val n=index+1; if(!visited[n]&&matches(pixels[n])){visited[n]=true;queue[tail++]=n} }
            if (py > 0) { val n=index-width; if(!visited[n]&&matches(pixels[n])){visited[n]=true;queue[tail++]=n} }
            if (py < height-1) { val n=index+width; if(!visited[n]&&matches(pixels[n])){visited[n]=true;queue[tail++]=n} }
        }
        if (tail == 0) return true
        val bounds = RectF(left.toFloat(), top.toFloat(), (right+1).toFloat(), (bottom+1).toFloat())
        val before = captureTiles(activeLayer, bounds)
        val replacement = if (activeLayer.isAlphaLocked) Color.argb(Color.alpha(seed),Color.red(color),Color.green(color),Color.blue(color)) else color
        for(i in 0 until tail) {
            val index=queue[i]
            if(matches(pixels[index])) pixels[index]=replacement
        }
        activeLayer.bitmap.setPixels(pixels,0,width,0,0,width,height)
        val after = captureTiles(activeLayer,bounds)
        redoHistory.forEach(::releaseChange);redoHistory.clear()
        undoHistory.addLast(StrokeChange(activeLayer.id,before,after))
        while(undoHistory.size>maxUndoEntries)releaseChange(undoHistory.removeFirst())
        invalidate()
        return true
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

    private fun isLayerVisible(layer: ArtLayer): Boolean =
        layer.isVisible && (layer.groupId?.let { id -> layerGroups.firstOrNull { it.id == id }?.isVisible } ?: true)

    fun createLayerGroup(name: String = "Group ${layerGroups.size + 1}"): LayerGroup {
        val group = LayerGroup(name = name)
        layerGroups.add(group)
        activeLayer.groupId = group.id
        invalidate()
        return group
    }

    fun assignActiveLayerToGroup(groupId: String?) {
        if (groupId != null && layerGroups.none { it.id == groupId }) return
        activeLayer.groupId = groupId
        invalidate()
    }

    fun toggleLayerGroupVisibility(groupId: String) {
        layerGroups.firstOrNull { it.id == groupId }?.let { it.isVisible = !it.isVisible }
        invalidate()
    }

    fun replaceLayerGroups(groups: List<LayerGroup>) {
        layerGroups.clear()
        layerGroups.addAll(groups)
        val valid = groups.map { it.id }.toSet()
        layers.forEach { if (it.groupId !in valid) it.groupId = null }
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
            blendMode = source.blendMode,
            groupId = source.groupId
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
        isEditingLayerMask = false
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
            upper.blendMode.applyTo(this)
        }
        upper.applyColorAdjustments(paint)
        val lowerAlpha = createLayerAlphaSource(lower)
        drawLayer(lower.canvas, upper, paint, clipBase = lowerAlpha)
        lowerAlpha.recycle()
        paint.colorFilter = null
        lower.opacity = 1f
        layers.removeAt(activeLayerIndex)
        activeLayerIndex--
        invalidate()
    }

    fun replaceLayers(newLayers: List<ArtLayer>) {
        require(newLayers.isNotEmpty())
        require(newLayers.size <= maxLayerCount) { "This project has more layers than this canvas can safely hold on this device." }
        require(newLayers.sumOf { 1L + if (it.maskBitmap != null) 1L else 0L } * documentWidth.toLong() * documentHeight.toLong() <= 64_000_000L) {
            "This project exceeds the safe mobile memory budget including layer masks."
        }
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
        val mergedBitmap = renderLayersComposite(layers, Color.TRANSPARENT)
        val merged = ArtLayer(name = "Merged", width = documentWidth, height = documentHeight)
        merged.canvas.drawBitmap(mergedBitmap, 0f, 0f, null)
        mergedBitmap.recycle()
        layers.clear()
        layers.add(merged)
        activeLayerIndex = 0
        layerGroups.clear()
        invalidate()
    }

    fun renderComposite(backgroundColor: Int? = null): Bitmap =
        renderLayersComposite(layers, backgroundColor)

    fun renderRasterOnlyComposite(backgroundColor: Int? = null): Bitmap =
        renderLayersComposite(layers, backgroundColor, includeVectors = false)

    fun forEachHighPrecisionTile(
        tileSize: Int = 512,
        consumer: (left: Int, top: Int, tile: Bitmap) -> Unit
    ) {
        require(tileSize in 64..2048)
        var top = 0
        while (top < documentHeight) {
            var left = 0
            val tileHeight = minOf(tileSize, documentHeight - top)
            while (left < documentWidth) {
                val tileWidth = minOf(tileSize, documentWidth - left)
                val bounds = Rect(left, top, left + tileWidth, top + tileHeight)
                val tile = Bitmap.createBitmap(tileWidth, tileHeight, Bitmap.Config.RGBA_F16)
                val tileCanvas = Canvas(tile)
                tileCanvas.save()
                tileCanvas.translate(-left.toFloat(), -top.toFloat())
                PaperTexture.draw(tileCanvas, documentRect, paperKind, paperStrength)
                var clipBaseTile: Bitmap? = null
                layers.filter { it.isVisible }.forEach { layer ->
                    val layerTile = Bitmap.createBitmap(tileWidth, tileHeight, Bitmap.Config.ARGB_8888)
                    val layerCanvas = Canvas(layerTile)
                    layerCanvas.save()
                    layerCanvas.translate(-left.toFloat(), -top.toFloat())
                    layerCanvas.drawBitmap(layer.bitmap, bounds, bounds, Paint(Paint.FILTER_BITMAP_FLAG))
                    VectorRenderer.draw(layerCanvas, layer.vectorPaths)
                    layer.maskBitmap?.takeIf { layer.maskEnabled }?.let { mask ->
                        layerCanvas.drawBitmap(mask, bounds, bounds, Paint().apply {
                            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                        })
                    }
                    if (layer.isClippedToBelow && clipBaseTile != null) {
                        layerCanvas.drawBitmap(
                            clipBaseTile!!,
                            Rect(0, 0, tileWidth, tileHeight),
                            bounds,
                            Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
                        )
                    }
                    layerCanvas.restore()
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        alpha = (layer.opacity.coerceIn(0f, 1f) * 255f).toInt()
                        layer.blendMode.applyTo(this)
                    }
                    layer.applyColorAdjustments(paint)
                    tileCanvas.drawBitmap(layerTile, Rect(0, 0, tileWidth, tileHeight), bounds, paint)
                    paint.colorFilter = null
                    if (!layer.isClippedToBelow) {
                        clipBaseTile?.recycle()
                        clipBaseTile = layerTile.copy(Bitmap.Config.ARGB_8888, false)
                    }
                    layerTile.recycle()
                }
                clipBaseTile?.recycle()
                tileCanvas.restore()
                try { consumer(left, top, tile) } finally { tile.recycle() }
                left += tileWidth
            }
            top += tileHeight
        }
    }

    fun renderHighPrecisionComposite(): Bitmap {
        require(documentWidth.toLong() * documentHeight.toLong() <= 12_000_000L) {
            "A full-size 16-bit float composite exceeds the mobile memory budget. Use tiled export instead."
        }
        val output = Bitmap.createBitmap(documentWidth, documentHeight, Bitmap.Config.RGBA_F16)
        val outputCanvas = Canvas(output)
        forEachHighPrecisionTile { left, top, tile ->
            outputCanvas.drawBitmap(tile, left.toFloat(), top.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return output
    }

    fun renderFrameComposite(index: Int, backgroundColor: Int? = null): Bitmap {
        val frame = animationFrames.getOrNull(index) ?: error("Animation frame does not exist.")
        return renderLayersComposite(frame.layers, backgroundColor)
    }

    fun addImageLayer(bitmap: Bitmap, name: String = "Imported image"): Boolean {
        if (layers.size >= maxLayerCount) return false
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
        return true
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
        selectionMaskBitmap?.let { mask ->
            Canvas(activeBitmap).drawBitmap(mask,0f,0f,Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            })
        }
        val targetLayer = activeLayer
        val historyBitmap = if (isEditingLayerMask) targetLayer.ensureMask() else targetLayer.bitmap
        val before = if (hasDirtyBounds) captureTiles(targetLayer, dirtyBounds, historyBitmap) else emptyList()
        if (isEditingLayerMask) {
            val mask = targetLayer.ensureMask()
            val maskCanvas = Canvas(mask)
            val luminance = (android.graphics.Color.red(strokeEngine?.activeColor ?: Color.BLACK) * 0.2126f +
                android.graphics.Color.green(strokeEngine?.activeColor ?: Color.BLACK) * 0.7152f +
                android.graphics.Color.blue(strokeEngine?.activeColor ?: Color.BLACK) * 0.0722f)
            if (strokeEngine?.eraserMode == true || luminance < 128f) {
                maskCanvas.drawBitmap(activeBitmap, 0f, 0f, Paint().apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                })
            } else {
                maskCanvas.drawBitmap(activeBitmap, 0f, 0f, Paint().apply {
                    colorFilter = android.graphics.PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
                })
            }
        } else if (targetLayer.isAlphaLocked) {
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
            val after = captureTiles(targetLayer, dirtyBounds, historyBitmap)
            redoHistory.forEach(::releaseChange)
            redoHistory.clear()
            undoHistory.addLast(StrokeChange(targetLayer.id, before, after, isEditingLayerMask))
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
        if (drawingTool == DrawingTool.FILL && event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (!viewportRect.contains(event.x, event.y)) return false
            val point = documentPoint(event)
            floodFillAt(point.x, point.y, strokeEngine?.activeColor ?: Color.BLACK)
            performClick()
            return true
        }
        if (drawingTool == DrawingTool.VECTOR_EDIT) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val point = documentPoint(event)
                    val hit = hitTestVectorNode(point)
                    selectedVectorPathId = hit?.first
                    selectedVectorNodeIndex = hit?.second
                    draggingVectorNode = hit != null
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (draggingVectorNode) {
                        val point = documentPoint(event)
                        selectedVectorPathId?.let { id ->
                            selectedVectorNodeIndex?.let { index -> moveVectorNode(id, index, point.x, point.y) }
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    draggingVectorNode = false
                    performClick()
                    return true
                }
            }
            return true
        }
        if (selectionMode != SelectionMode.NONE) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!viewportRect.contains(event.x, event.y)) return false
                    val point = documentPoint(event)
                    if (selectionMode == SelectionMode.COLOR_RANGE) {
                        selectColorRange(point.x,point.y,selectionTolerance)
                        selectionMode = SelectionMode.NONE
                        return true
                    }
                    selectionMaskBitmap?.recycle()
                    selectionMaskBitmap = null
                    selectionStartX = point.x
                    selectionStartY = point.y
                    isSelecting = true
                    if (selectionMode == SelectionMode.LASSO) {
                        selectionRect = null
                        selectionPath = Path().apply { moveTo(point.x, point.y) }
                    } else {
                        selectionPath = null
                        selectionRect = RectF(point.x, point.y, point.x, point.y)
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isSelecting) {
                        val point = documentPoint(event)
                        if (selectionMode == SelectionMode.LASSO) selectionPath?.lineTo(point.x, point.y)
                        else updateSelection(point)
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isSelecting) {
                        val point = documentPoint(event)
                        if (selectionMode == SelectionMode.LASSO) {
                            selectionPath?.apply { lineTo(point.x, point.y); close() }
                            selectionPath?.let { path -> selectionRect = RectF().also { path.computeBounds(it, true) } }
                        } else updateSelection(point)
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
