package com.artstudio.pro.ui.studio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AColor
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.artstudio.pro.core.brush.engine.StrokeEngine
import com.artstudio.pro.core.brush.model.BrushDefinition
import com.artstudio.pro.core.brush.model.BrushMedium
import com.artstudio.pro.core.brush.model.DynamicsSource
import com.artstudio.pro.core.brush.model.BrushLibrary
import com.artstudio.pro.core.canvas.ArtCanvasView
import com.artstudio.pro.core.canvas.SymmetryMode
import com.artstudio.pro.core.canvas.SelectionMode
import com.artstudio.pro.core.canvas.DrawingTool
import com.artstudio.pro.core.model.ArtLayer
import com.artstudio.pro.core.model.AnimationFrame
import com.artstudio.pro.core.model.BlendMode
import com.artstudio.pro.core.project.ProjectArchive
import com.artstudio.pro.core.project.DocumentPresets
import com.artstudio.pro.core.paper.PaperKind

private val studioPalette = listOf(
    AColor.BLACK, AColor.WHITE, AColor.RED, AColor.rgb(255, 145, 0),
    AColor.YELLOW, AColor.GREEN, AColor.CYAN, AColor.BLUE,
    AColor.MAGENTA, AColor.rgb(130, 80, 190), AColor.rgb(120, 75, 45)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var brushSize by remember { mutableFloatStateOf(40f) }
    var brushOpacity by remember { mutableFloatStateOf(1f) }
    var brushFlow by remember { mutableFloatStateOf(0.85f) }
    var brushSpacing by remember { mutableFloatStateOf(0.08f) }
    var brushStreamline by remember { mutableFloatStateOf(0.65f) }
    var brushScatter by remember { mutableFloatStateOf(0f) }
    var brushRotationJitter by remember { mutableFloatStateOf(0f) }
    var brushSizeJitter by remember { mutableFloatStateOf(0f) }
    var brushHardness by remember { mutableFloatStateOf(1f) }
    var brushRoundness by remember { mutableFloatStateOf(1f) }
    var brushTexture by remember { mutableFloatStateOf(0f) }
    var brushWetness by remember { mutableFloatStateOf(0f) }
    var brushPigmentLoad by remember { mutableFloatStateOf(1f) }
    var brushBristles by remember { mutableFloatStateOf(3f) }
    var brushWetMixing by remember { mutableStateOf(false) }
    var activeColor by remember { mutableIntStateOf(AColor.BLACK) }
    var documentWidth by remember { mutableIntStateOf(2048) }
    var documentHeight by remember { mutableIntStateOf(2048) }
    var documentDpi by remember { mutableIntStateOf(300) }
    var customWidth by remember { mutableStateOf("2048") }
    var customHeight by remember { mutableStateOf("2048") }
    var customDpi by remember { mutableStateOf("300") }
    var pendingLayers by remember { mutableStateOf<List<ArtLayer>?>(null) }
    var pendingFrames by remember { mutableStateOf<List<AnimationFrame>?>(null) }
    var pendingFrameIndex by remember { mutableIntStateOf(0) }
    var layerRefresh by remember { mutableIntStateOf(0) }
    var showColorPicker by remember { mutableStateOf(false) }
    var customColorHex by remember { mutableStateOf("#000000") }
    var showRenameLayer by remember { mutableStateOf(false) }
    var showTextContentDialog by remember { mutableStateOf(false) }
    var comicTextContent by remember { mutableStateOf("Text") }
    var showLayerAdjustments by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
    var showPaperPicker by remember { mutableStateOf(false) }
    var showMediumPicker by remember { mutableStateOf(false) }
    var showBrushLibrary by remember { mutableStateOf(false) }
    var savedBrushes by remember { mutableStateOf(BrushLibrary.load(context)) }
    var brushMedium by remember { mutableStateOf(BrushMedium.INK_PEN) }
    var dynamicsSource by remember { mutableStateOf(DynamicsSource.PRESSURE) }
    var opacityDynamicsSource by remember { mutableStateOf(DynamicsSource.PRESSURE) }
    var tipMask by remember { mutableStateOf(BitmapFactory.decodeFile(context.getFileStreamPath("custom_brush_tip.png").absolutePath)) }
    var paperKind by remember { mutableStateOf(PaperKind.SMOOTH) }
    var paperStrength by remember { mutableFloatStateOf(1f) }
    var showNewDocument by remember { mutableStateOf(false) }
    var eraserMode by remember { mutableStateOf(false) }
    var canvasView by remember { mutableStateOf<ArtCanvasView?>(null) }
    val brush = remember(brushMedium) { BrushDefinition("medium-${brushMedium.name.lowercase()}", brushMedium.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }, brushMedium) }

    val saveProject = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-artstudio-project")
    ) { uri ->
        if (uri != null) runCatching {
            val view = canvasView ?: error("The canvas is not ready.")
            context.contentResolver.openOutputStream(uri)?.use {
                ProjectArchive.save(it, documentWidth, documentHeight, view.layers, view.paperKind, view.paperStrength, documentDpi, view.animationFramesSnapshot(), view.currentFrameIndex)
            } ?: error("Unable to open the selected file.")
        }.onSuccess {
            Toast.makeText(context, "Project saved", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "Save failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    val exportPng = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        if (uri != null) runCatching {
            val bitmap = (canvasView ?: error("The canvas is not ready.")).renderComposite()
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) {
                        "PNG encoding failed."
                    }
                } ?: error("Unable to open the selected file.")
            } finally {
                bitmap.recycle()
            }
        }.onSuccess {
            Toast.makeText(context, "PNG exported", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    val exportJpg = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/jpeg")
    ) { uri ->
        if (uri != null) runCatching {
            val bitmap = (canvasView ?: error("The canvas is not ready.")).renderComposite(AColor.WHITE)
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) { "JPEG encoding failed." }
                } ?: error("Unable to open the selected file.")
            } finally { bitmap.recycle() }
        }.onSuccess { Toast.makeText(context, "JPEG exported", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(context, "JPEG export failed: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    val exportSpriteSheet = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        if (uri != null) runCatching {
            val view = canvasView ?: error("The canvas is not ready.")
            val columns = kotlin.math.ceil(kotlin.math.sqrt(view.frameCount.toDouble())).toInt().coerceAtLeast(1)
            val rows = kotlin.math.ceil(view.frameCount.toDouble() / columns).toInt().coerceAtLeast(1)
            val scale = minOf(
                1f,
                kotlin.math.sqrt(20_000_000f / (documentWidth.toFloat() * documentHeight * view.frameCount)),
                12000f / (documentWidth * columns),
                12000f / (documentHeight * rows)
            )
            val frameWidth = (documentWidth * scale).toInt().coerceAtLeast(1)
            val frameHeight = (documentHeight * scale).toInt().coerceAtLeast(1)
            val sheet = Bitmap.createBitmap(frameWidth * columns, frameHeight * rows, Bitmap.Config.ARGB_8888)
            try {
                val sheetCanvas = android.graphics.Canvas(sheet)
                repeat(view.frameCount) { frameIndex ->
                    val frame = view.renderFrameComposite(frameIndex)
                    try {
                        val left = (frameIndex % columns) * frameWidth
                        val top = (frameIndex / columns) * frameHeight
                        sheetCanvas.drawBitmap(
                            frame, null,
                            android.graphics.Rect(left, top, left + frameWidth, top + frameHeight),
                            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                        )
                    } finally { frame.recycle() }
                }
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    check(sheet.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Sprite sheet encoding failed." }
                } ?: error("Unable to open the selected file.")
            } finally { sheet.recycle() }
        }.onSuccess { Toast.makeText(context, "Animation sprite sheet exported", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(context, "Sprite sheet export failed: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    val exportPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) runCatching {
            val view = canvasView ?: error("The canvas is not ready.")
            val pageWidth = (documentWidth.toFloat() * 72f / documentDpi).toInt().coerceAtLeast(1)
            val pageHeight = (documentHeight.toFloat() * 72f / documentDpi).toInt().coerceAtLeast(1)
            val pdf = android.graphics.pdf.PdfDocument()
            try {
                repeat(view.frameCount) { frameIndex ->
                    val bitmap = view.renderFrameComposite(frameIndex)
                    try {
                        val page = pdf.startPage(
                            android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, frameIndex + 1).create()
                        )
                        page.canvas.drawBitmap(
                            bitmap, null,
                            android.graphics.Rect(0, 0, pageWidth, pageHeight),
                            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                        )
                        pdf.finishPage(page)
                    } finally {
                        bitmap.recycle()
                    }
                }
                context.contentResolver.openOutputStream(uri)?.use { pdf.writeTo(it) }
                    ?: error("Unable to open the selected file.")
            } finally {
                pdf.close()
            }
        }.onSuccess { Toast.makeText(context, "Print PDF exported", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(context, "PDF export failed: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    val exportWebp = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/webp")
    ) { uri ->
        if (uri != null) runCatching {
            val bitmap = (canvasView ?: error("The canvas is not ready.")).renderComposite()
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.WEBP, 92, output)) { "WebP encoding failed." }
                } ?: error("Unable to open the selected file.")
            } finally { bitmap.recycle() }
        }.onSuccess { Toast.makeText(context, "WebP exported", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(context, "WebP export failed: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    val openProject = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) runCatching {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Unable to open the selected project.")
            input.use { ProjectArchive.load(it) }
        }.onSuccess { project ->
            paperKind = project.paperKind
            paperStrength = project.paperStrength
            documentDpi = project.dpi
            canvasView?.setPaperTexture(project.paperKind, project.paperStrength)
            if (project.width == documentWidth && project.height == documentHeight) {
                canvasView?.replaceFrames(project.frames, project.currentFrameIndex)
                layerRefresh++
            } else {
                pendingLayers = project.layers
                pendingFrames = project.frames
                pendingFrameIndex = project.currentFrameIndex
                documentWidth = project.width
                documentHeight = project.height
            }
            Toast.makeText(context, "Project opened", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "Open failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    val importBrushTip = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) runCatching {
            val decoded = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                ?: error("Unable to read the brush tip image.")
            try {
                val scale = minOf(1f, 512f / maxOf(decoded.width, decoded.height).toFloat())
                val scaled = if (scale < 1f) Bitmap.createScaledBitmap(
                    decoded, (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1), true
                ) else decoded
                val mask = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
                val pixels = IntArray(scaled.width * scaled.height)
                scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
                for (i in pixels.indices) {
                    val color = pixels[i]
                    val luminance = (android.graphics.Color.red(color) * 299 +
                        android.graphics.Color.green(color) * 587 +
                        android.graphics.Color.blue(color) * 114) / 1000
                    val alpha = android.graphics.Color.alpha(color) * (255 - luminance) / 255
                    pixels[i] = (alpha shl 24) or 0x00FFFFFF
                }
                mask.setPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
                context.openFileOutput("custom_brush_tip.png", android.content.Context.MODE_PRIVATE).use { output ->
                    check(mask.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Unable to save the brush tip." }
                }
                if (scaled !== decoded) scaled.recycle()
                mask
            } finally {
                decoded.recycle()
            }
        }.onSuccess { mask ->
            tipMask?.takeIf { it !== mask && !it.isRecycled }?.recycle()
            tipMask = mask
            Toast.makeText(context, "Custom brush tip loaded", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "Brush tip import failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    val importImage = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                ?: error("Unable to read the selected image.")
        }.onSuccess { bitmap ->
            canvasView?.addImageLayer(bitmap)
            bitmap.recycle()
            layerRefresh++
        }.onFailure {
            Toast.makeText(context, "Image import failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ArtStudio Pro", color = Color.White) },
                actions = {
                    TextButton(onClick = { showNewDocument = true }) { Text("New", color = Color.White) }
                    TextButton(onClick = { openProject.launch(arrayOf("*/*")) }) { Text("Open", color = Color.White) }
                    TextButton(onClick = { importImage.launch(arrayOf("image/*")) }) { Text("Image", color = Color.White) }
                    TextButton(onClick = { saveProject.launch("Artwork.asp") }) { Text("Save", color = Color.White) }
                    TextButton(onClick = { exportPng.launch("Artwork.png") }) { Text("PNG", color = Color.White) }
                    TextButton(onClick = { exportJpg.launch("Artwork.jpg") }) { Text("JPG", color = Color.White) }
                    TextButton(onClick = { exportWebp.launch("Artwork.webp") }) { Text("WebP", color = Color.White) }
                    TextButton(onClick = { exportPdf.launch("Artwork.pdf") }) { Text("PDF", color = Color.White) }
                    TextButton(onClick = { exportSpriteSheet.launch("Animation-Sheet.png") }) { Text("Sheet", color = Color.White) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E1E))
            )
        },
        bottomBar = {
            Surface(color = Color(0xFF1E1E1E), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Size", color = Color.White)
                        Slider(
                            value = brushSize,
                            onValueChange = { brushSize = it },
                            valueRange = 1f..240f,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                        Text(brushSize.toInt().toString(), color = Color.White)
                        IconButton(onClick = { showColorPicker = true }) {
                            Icon(Icons.Default.ColorLens, "Choose color", tint = Color(activeColor))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Opacity", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Slider(value = brushOpacity, onValueChange = { brushOpacity = it }, valueRange = 0.01f..1f, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                        Text("${(brushOpacity * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Text("Flow", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Slider(value = brushFlow, onValueChange = { brushFlow = it }, valueRange = 0.01f..1f, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Spacing", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Slider(value = brushSpacing, onValueChange = { brushSpacing = it }, valueRange = 0.02f..0.5f, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                        Text("${(brushSpacing * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Text("Stabilize", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Slider(value = brushStreamline, onValueChange = { brushStreamline = it }, valueRange = 0f..0.95f, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Scatter", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Slider(value = brushScatter, onValueChange = { brushScatter = it }, valueRange = 0f..2f, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                        Text("${(brushScatter * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Text("Rotation", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Slider(value = brushRotationJitter, onValueChange = { brushRotationJitter = it }, valueRange = 0f..1f, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Size jitter", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Slider(value = brushSizeJitter, onValueChange = { brushSizeJitter = it }, valueRange = 0f..1f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                        Text("${(brushSizeJitter * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Hardness", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Slider(value = brushHardness, onValueChange = { brushHardness = it }, valueRange = 0.05f..1f, modifier = Modifier.weight(1f).padding(horizontal = 5.dp))
                        Text("Round", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Slider(value = brushRoundness, onValueChange = { brushRoundness = it }, valueRange = 0.08f..1f, modifier = Modifier.weight(1f).padding(horizontal = 5.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Texture", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Slider(value = brushTexture, onValueChange = { brushTexture = it }, valueRange = 0f..1f, modifier = Modifier.weight(1f).padding(horizontal = 5.dp))
                        Text("Wet", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Slider(value = brushWetness, onValueChange = { brushWetness = it }, valueRange = 0f..1f, modifier = Modifier.weight(1f).padding(horizontal = 5.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Pigment", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Slider(value = brushPigmentLoad, onValueChange = { brushPigmentLoad = it }, valueRange = 0f..1f, modifier = Modifier.weight(1f).padding(horizontal = 5.dp))
                        Text("Bristles", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Slider(value = brushBristles, onValueChange = { brushBristles = it }, valueRange = 1f..48f, modifier = Modifier.weight(1f).padding(horizontal = 5.dp))
                        TextButton(onClick = { brushWetMixing = !brushWetMixing }) { Text(if (brushWetMixing) "Mix on" else "Mix off") }
                    }
                    Text("Layers (${canvasView?.layers?.size ?: 0}) · ${layerRefresh}", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { canvasView?.undo(); layerRefresh++ }) { Text("Undo") }
                        TextButton(onClick = { canvasView?.redo(); layerRefresh++ }) { Text("Redo") }
                        TextButton(onClick = {
                            eraserMode = !eraserMode
                            canvasView?.strokeEngine?.eraserMode = eraserMode
                        }) { Text(if (eraserMode) "Eraser on" else "Eraser") }
                        TextButton(onClick = { showPaperPicker = true }) { Text("Paper: ${paperKind.title}") }
                        TextButton(onClick = {
                            val view = canvasView
                            if (view != null) {
                                view.setGrid(!view.showGrid)
                                layerRefresh++
                            }
                        }) { Text(if (canvasView?.showGrid == true) "Grid on" else "Grid off") }
                        TextButton(onClick = {
                            val modes = SymmetryMode.values()
                            val view = canvasView
                            if (view != null) {
                                view.setSymmetry(modes[(modes.indexOf(view.symmetryMode) + 1) % modes.size])
                                layerRefresh++
                            }
                        }) { Text("Symmetry: ${canvasView?.symmetryMode?.name?.lowercase() ?: "none"}") }
                        TextButton(onClick = {
                            canvasView?.let { it.setZoom(it.zoomFactor / 1.25f); layerRefresh++ }
                        }) { Text("− zoom") }
                        TextButton(onClick = {
                            canvasView?.let { it.setZoom(it.zoomFactor * 1.25f); layerRefresh++ }
                        }) { Text("+ zoom") }
                        TextButton(onClick = {
                            canvasView?.let {
                                val next = when (it.gridSpacing) { 32 -> 64; 64 -> 128; else -> 32 }
                                it.setGrid(it.showGrid, next)
                                layerRefresh++
                            }
                        }) { Text("Grid ${canvasView?.gridSpacing ?: 64}px") }
                        TextButton(onClick = {
                            canvasView?.let { it.setRulers(!it.showRulers); layerRefresh++ }
                        }) { Text(if (canvasView?.showRulers == true) "Rulers on" else "Rulers off") }
                        TextButton(onClick = {
                            val modes = SelectionMode.values()
                            val view = canvasView
                            if (view != null) {
                                view.setSelectionMode(modes[(modes.indexOf(view.selectionMode) + 1) % modes.size])
                                layerRefresh++
                            }
                        }) { Text("Select: ${canvasView?.selectionMode?.name?.lowercase() ?: "none"}") }
                        if (canvasView?.hasSelection == true) {
                            TextButton(onClick = { canvasView?.maskActiveLayerToSelection(); layerRefresh++ }) { Text("Mask layer") }
                            TextButton(onClick = { canvasView?.clearSelection(); layerRefresh++ }) { Text("Clear selection") }
                        }
                        TextButton(onClick = {
                            val modes = DrawingTool.values()
                            val view = canvasView
                            if (view != null) {
                                view.setDrawingTool(modes[(modes.indexOf(view.drawingTool) + 1) % modes.size])
                                layerRefresh++
                            }
                        }) { Text("Tool: ${canvasView?.drawingTool?.name?.lowercase() ?: "brush"}") }
                        TextButton(onClick = {
                            comicTextContent = canvasView?.textToolContent ?: "Text"
                            showTextContentDialog = true
                        }) { Text("Text content") }
                        TextButton(onClick = { showMediumPicker = true }) { Text("Brush: ${brushMedium.name.replace('_', ' ')}") }
                        TextButton(onClick = {
                            val preset = brush.copy(
                                id = java.util.UUID.randomUUID().toString(),
                                name = "${brush.name} ${savedBrushes.size + 1}",
                                baseSize = brushSize,
                                baseOpacity = brushOpacity,
                                flow = brushFlow,
                                spacing = brushSpacing,
                                streamline = brushStreamline,
                                scatter = brushScatter,
                                rotationJitter = brushRotationJitter,
                                sizeJitter = brushSizeJitter,
                                hardness = brushHardness,
                                roundness = brushRoundness,
                                textureStrength = brushTexture,
                                wetness = brushWetness,
                                pigmentLoad = brushPigmentLoad,
                                bristleCount = brushBristles.toInt(),
                                wetMixing = brushWetMixing,
                                sizeSource = dynamicsSource,
                                opacitySource = opacityDynamicsSource,
                                tipMask = null
                            )
                            savedBrushes = savedBrushes + preset
                            runCatching { BrushLibrary.save(context, savedBrushes) }
                                .onSuccess { Toast.makeText(context, "Brush preset saved", Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(context, "Could not save brush: ${it.message}", Toast.LENGTH_LONG).show() }
                        }) { Text("Save preset") }
                        TextButton(onClick = { savedBrushes = BrushLibrary.load(context); showBrushLibrary = true }) { Text("Brushes (${savedBrushes.size})") }
                        TextButton(onClick = { importBrushTip.launch(arrayOf("image/*")) }) { Text(if (tipMask == null) "Import tip" else "Change tip") }
                        if (tipMask != null) {
                            TextButton(onClick = {
                                tipMask?.takeIf { !it.isRecycled }?.recycle()
                                tipMask = null
                                context.deleteFile("custom_brush_tip.png")
                            }) { Text("Clear tip") }
                        }
                        TextButton(onClick = {
                            dynamicsSource = DynamicsSource.values()[(DynamicsSource.values().indexOf(dynamicsSource) + 1) % DynamicsSource.values().size]
                        }) { Text("Size: ${dynamicsSource.name.lowercase()}") }
                        TextButton(onClick = {
                            opacityDynamicsSource = DynamicsSource.values()[(DynamicsSource.values().indexOf(opacityDynamicsSource) + 1) % DynamicsSource.values().size]
                        }) { Text("Opacity: ${opacityDynamicsSource.name.lowercase()}") }
                        TextButton(onClick = {
                            if (canvasView?.addLayer() == false) {
                                Toast.makeText(context, "Layer memory limit reached for this canvas size.", Toast.LENGTH_LONG).show()
                            }
                            layerRefresh++
                        }) { Text("New layer") }
                        TextButton(onClick = {
                            if (canvasView?.duplicateActiveLayer() == false) {
                                Toast.makeText(context, "Layer memory limit reached for this canvas size.", Toast.LENGTH_LONG).show()
                            }
                            layerRefresh++
                        }) { Text("Duplicate") }
                        TextButton(onClick = {
                            canvasView?.createActiveLayerMask()
                            layerRefresh++
                        }) { Text("Add mask") }
                        TextButton(onClick = {
                            canvasView?.toggleActiveLayerMask()
                            layerRefresh++
                        }) { Text(if (canvasView?.activeLayer?.maskEnabled == false) "Mask off" else "Mask on") }
                        TextButton(onClick = {
                            canvasView?.removeActiveLayerMask()
                            layerRefresh++
                        }) { Text("Remove mask") }
                        TextButton(onClick = {
                            canvasView?.mergeActiveLayerDown()
                            layerRefresh++
                        }) { Text("Merge down") }
                        TextButton(onClick = {
                            canvasView?.deleteActiveLayer()
                            layerRefresh++
                        }) { Text("Delete") }
                        TextButton(onClick = {
                            canvasView?.clearActiveLayer()
                            layerRefresh++
                        }) { Text("Clear") }
                    }
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        val currentLayers = canvasView?.layers?.toList().orEmpty()
                        itemsIndexed(currentLayers) { index, layer ->
                            val selected = canvasView?.activeLayerIndex == index
                            Surface(
                                color = if (selected) Color(0xFF665080) else Color(0xFF34313B),
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.clickable {
                                    canvasView?.selectLayer(index)
                                    layerRefresh++
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(if (layer.isVisible) "◉" else "○", color = Color.White,
                                        modifier = Modifier.clickable {
                                            layer.isVisible = !layer.isVisible
                                            canvasView?.invalidate()
                                            layerRefresh++
                                        })
                                    Spacer(Modifier.width(6.dp))
                                    Text(layer.name, color = Color.White, maxLines = 1)
                                }
                            }
                        }
                    }
                    canvasView?.activeLayer?.let { selected ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Layer", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                                TextButton(onClick = {
                                    renameValue = selected.name
                                    showRenameLayer = true
                                }) { Text("Rename") }
                                TextButton(onClick = { showLayerAdjustments = true }) { Text("Adjust") }
                                TextButton(onClick = {
                                    selected.isAlphaLocked = !selected.isAlphaLocked
                                    layerRefresh++
                                    canvasView?.invalidate()
                                }) { Text(if (selected.isAlphaLocked) "Alpha locked" else "Alpha free") }
                                TextButton(onClick = {
                                    selected.isClippedToBelow = !selected.isClippedToBelow
                                    layerRefresh++
                                    canvasView?.invalidate()
                                }) { Text(if (selected.isClippedToBelow) "Clipped" else "Unclipped") }
                                TextButton(onClick = {
                                    val modes = BlendMode.values()
                                    selected.blendMode = modes[(modes.indexOf(selected.blendMode) + 1) % modes.size]
                                    layerRefresh++
                                    canvasView?.invalidate()
                                }) { Text(selected.blendMode.name.replace('_', ' ')) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Opacity", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                                Slider(
                                    value = selected.opacity,
                                    onValueChange = {
                                        selected.opacity = it
                                        layerRefresh++
                                        canvasView?.invalidate()
                                    },
                                    valueRange = 0f..1f,
                                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                                )
                                Text("${(selected.opacity * 100).toInt()}%", color = Color.White)
                                TextButton(onClick = {
                                    canvasView?.moveActiveLayer((canvasView?.activeLayerIndex ?: 0) - 1)
                                    layerRefresh++
                                }) { Text("↓") }
                                TextButton(onClick = {
                                    canvasView?.moveActiveLayer((canvasView?.activeLayerIndex ?: 0) + 1)
                                    layerRefresh++
                                }) { Text("↑") }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Animation", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = {
                            val view = canvasView
                            if (view != null) view.selectFrame((view.currentFrameIndex - 1).coerceAtLeast(0))
                            layerRefresh++
                        }) { Text("◀") }
                        TextButton(onClick = {
                            val view = canvasView
                            if (view != null && !view.addBlankFrame()) Toast.makeText(context, "Animation frame or mobile memory limit reached.", Toast.LENGTH_SHORT).show()
                            layerRefresh++
                        }) { Text("+ Blank") }
                        TextButton(onClick = {
                            val view = canvasView
                            if (view != null && !view.duplicateFrame()) Toast.makeText(context, "Animation frame or mobile memory limit reached.", Toast.LENGTH_SHORT).show()
                            layerRefresh++
                        }) { Text("Duplicate") }
                        TextButton(onClick = { canvasView?.deleteFrame(); layerRefresh++ }) { Text("Delete") }
                        TextButton(onClick = {
                            canvasView?.let { it.setOnionSkin(!it.onionSkinEnabled) }
                            layerRefresh++
                        }) { Text(if (canvasView?.onionSkinEnabled == true) "Onion on" else "Onion off") }
                        TextButton(onClick = {
                            canvasView?.let { if (it.isPlaying) it.stopPlayback() else it.startPlayback() }
                            layerRefresh++
                        }) { Text(if (canvasView?.isPlaying == true) "Pause" else "Play") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Frames", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                        repeat(canvasView?.frameCount ?: 1) { index ->
                            TextButton(
                                onClick = { canvasView?.selectFrame(index); layerRefresh++ },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = if (canvasView?.currentFrameIndex == index) MaterialTheme.colorScheme.primary else Color.White
                                )
                            ) { Text("${index + 1}") }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Frame duration", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                        Slider(
                            value = (canvasView?.currentFrameDuration() ?: 100).toFloat(),
                            onValueChange = { canvasView?.setFrameDuration(it.toInt()) },
                            valueRange = 20f..1000f,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                        Text("${canvasView?.currentFrameDuration() ?: 100} ms", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(Color(0xFF121212))) {
            key(documentWidth, documentHeight) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        ArtCanvasView(ctx, documentWidth, documentHeight).apply {
                            strokeEngine = StrokeEngine(brush, activeColor)
                            setPaperTexture(paperKind, paperStrength)
                            pendingFrames?.let { replaceFrames(it, pendingFrameIndex) }
                                ?: pendingLayers?.let { replaceLayers(it) }
                            canvasView = this
                        }
                    },
                    update = { view ->
                        brush.baseSize = brushSize
                        brush.baseOpacity = brushOpacity
                        brush.flow = brushFlow
                        brush.spacing = brushSpacing
                        brush.streamline = brushStreamline
                        brush.scatter = brushScatter
                        brush.rotationJitter = brushRotationJitter
                        brush.sizeJitter = brushSizeJitter
                        brush.hardness = brushHardness
                        brush.roundness = brushRoundness
                        brush.textureStrength = brushTexture
                        brush.wetness = brushWetness
                        brush.pigmentLoad = brushPigmentLoad
                        brush.bristleCount = brushBristles.toInt()
                        brush.wetMixing = brushWetMixing
                        brush.sizeSource = dynamicsSource
                        brush.opacitySource = opacityDynamicsSource
                        brush.tipMask = tipMask
                        view.strokeEngine?.brush = brush
                        view.strokeEngine?.activeColor = activeColor
                        view.strokeEngine?.eraserMode = eraserMode
                        view.setPaperTexture(paperKind, paperStrength)
                        canvasView = view
                    }
                )
            }
        }
    }

    if (showNewDocument) {
        AlertDialog(
            onDismissRequest = { showNewDocument = false },
            title = { Text("New canvas") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())
                ) {
                    DocumentPresets.all.forEach { preset ->
                        TextButton(
                            onClick = {
                                val freshLayer = ArtLayer(
                                    name = "Background",
                                    width = preset.width,
                                    height = preset.height
                                )
                                documentDpi = preset.dpi
                                customWidth = preset.width.toString()
                                customHeight = preset.height.toString()
                                customDpi = preset.dpi.toString()
                                if (preset.width == documentWidth && preset.height == documentHeight) {
                                    canvasView?.replaceLayers(listOf(freshLayer))
                                    layerRefresh++
                                } else {
                                    pendingLayers = listOf(freshLayer)
                                    documentWidth = preset.width
                                    documentHeight = preset.height
                                }
                                showNewDocument = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(preset.name, color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    "${preset.width} × ${preset.height} px · ${preset.dpi} DPI · ${preset.category}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                    Text("Custom canvas", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = customWidth,
                            onValueChange = { customWidth = it.filter(Char::isDigit).take(5) },
                            label = { Text("Width") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = customHeight,
                            onValueChange = { customHeight = it.filter(Char::isDigit).take(5) },
                            label = { Text("Height") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = customDpi,
                            onValueChange = { customDpi = it.filter(Char::isDigit).take(4) },
                            label = { Text("DPI") },
                            singleLine = true,
                            modifier = Modifier.weight(0.8f)
                        )
                    }
                    Text("Up to 16,000 px per side · 20 MP total area · 36–2400 DPI", style = MaterialTheme.typography.labelSmall)
                    Button(
                        onClick = {
                            val width = customWidth.toIntOrNull()
                            val height = customHeight.toIntOrNull()
                            val dpi = customDpi.toIntOrNull()
                            if (width != null && height != null && dpi != null && width in 1..16000 && height in 1..16000 && dpi in 36..2400 && width.toLong() * height.toLong() <= 20_000_000L) {
                                val freshLayer = ArtLayer(name = "Background", width = width, height = height)
                                documentDpi = dpi
                                if (width == documentWidth && height == documentHeight) {
                                    canvasView?.replaceLayers(listOf(freshLayer))
                                    layerRefresh++
                                } else {
                                    pendingLayers = listOf(freshLayer)
                                    documentWidth = width
                                    documentHeight = height
                                }
                                showNewDocument = false
                            } else {
                                Toast.makeText(context, "Enter valid dimensions within the 20-megapixel mobile canvas limit.", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Create custom canvas") }
                }
            },
            confirmButton = {
                TextButton(onClick = { showNewDocument = false }) { Text("Cancel") }
            }
        )
    }


    if (showTextContentDialog) {
        AlertDialog(
            onDismissRequest = { showTextContentDialog = false },
            title = { Text("Comic lettering") },
            text = {
                OutlinedTextField(
                    value = comicTextContent,
                    onValueChange = { comicTextContent = it.take(240) },
                    label = { Text("Text to place") },
                    minLines = 2,
                    maxLines = 5
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    canvasView?.setTextToolContent(comicTextContent)
                    showTextContentDialog = false
                }) { Text("Use text") }
            },
            dismissButton = {
                TextButton(onClick = { showTextContentDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showRenameLayer) {
        AlertDialog(
            onDismissRequest = { showRenameLayer = false },
            title = { Text("Rename layer") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it.take(64) },
                    singleLine = true,
                    label = { Text("Layer name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    canvasView?.renameActiveLayer(renameValue)
                    layerRefresh++
                    showRenameLayer = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameLayer = false }) { Text("Cancel") }
            }
        )
    }

    if (showBrushLibrary) {
        AlertDialog(
            onDismissRequest = { showBrushLibrary = false },
            title = { Text("Brush library") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (savedBrushes.isEmpty()) Text("No saved brush presets yet.")
                    savedBrushes.forEach { preset ->
                        TextButton(
                            onClick = {
                                brushMedium = preset.medium
                                brushSize = preset.baseSize
                                brushOpacity = preset.baseOpacity
                                brushFlow = preset.flow
                                brushSpacing = preset.spacing
                                brushStreamline = preset.streamline
                                brushScatter = preset.scatter
                                brushRotationJitter = preset.rotationJitter
                                brushSizeJitter = preset.sizeJitter
                                dynamicsSource = preset.sizeSource
                                opacityDynamicsSource = preset.opacitySource
                                showBrushLibrary = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(preset.name)
                                Text(
                                    "${preset.medium.name.replace('_', ' ')} · ${preset.baseSize.toInt()} px · ${(preset.baseOpacity * 100).toInt()}% opacity",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBrushLibrary = false }) { Text("Close") }
            }
        )
    }

    if (showLayerAdjustments) {
        val selected = canvasView?.activeLayer
        AlertDialog(
            onDismissRequest = { showLayerAdjustments = false },
            title = { Text("Layer adjustments") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (selected != null) {
                        Text("Brightness · ${(selected.brightness * 100).toInt()}")
                        Slider(value = selected.brightness, onValueChange = {
                            selected.brightness = it
                            layerRefresh++
                            canvasView?.invalidate()
                        }, valueRange = -1f..1f)
                        Text("Contrast · ${(selected.contrast * 100).toInt()}%")
                        Slider(value = selected.contrast, onValueChange = {
                            selected.contrast = it
                            layerRefresh++
                            canvasView?.invalidate()
                        }, valueRange = 0f..2f)
                        Text("Saturation · ${(selected.saturation * 100).toInt()}%")
                        Slider(value = selected.saturation, onValueChange = {
                            selected.saturation = it
                            layerRefresh++
                            canvasView?.invalidate()
                        }, valueRange = 0f..2f)
                        TextButton(onClick = {
                            selected.brightness = 0f
                            selected.contrast = 1f
                            selected.saturation = 1f
                            layerRefresh++
                            canvasView?.invalidate()
                        }) { Text("Reset adjustments") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLayerAdjustments = false }) { Text("Done") }
            }
        )
    }

    if (showMediumPicker) {
        AlertDialog(
            onDismissRequest = { showMediumPicker = false },
            title = { Text("Traditional media") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Choose a brush response that works with the selected paper surface.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    BrushMedium.values().forEach { medium ->
                        TextButton(
                            onClick = {
                                brushMedium = medium
                                showMediumPicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text(medium.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() })
                                    Text(
                                        when (medium) {
                                            BrushMedium.GRAPHITE -> "Soft pencil grain and broken pigment"
                                            BrushMedium.CHARCOAL -> "Rough, dusty marks with scattered grain"
                                            BrushMedium.INK_PEN -> "Clean, solid ink strokes"
                                            BrushMedium.WATERCOLOR -> "Transparent layered washes and darker edges"
                                            BrushMedium.OIL_IMPASTO -> "Opaque paint with raised bristle ridges"
                                            BrushMedium.SCREENTONE -> "Comic halftone dots and tonal texture"
                                            BrushMedium.PROCEDURAL -> "Smooth general-purpose brush"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (brushMedium == medium) Text("✓", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMediumPicker = false }) { Text("Close") }
            }
        )
    }

    if (showPaperPicker) {
        AlertDialog(
            onDismissRequest = { showPaperPicker = false },
            title = { Text("Paper & surface") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Choose a traditional surface. Texture is included in PNG exports and remains editable as a document setting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    PaperKind.values().forEach { kind ->
                        TextButton(
                            onClick = {
                                paperKind = kind
                                canvasView?.setPaperTexture(kind, paperStrength)
                                showPaperPicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    modifier = Modifier.size(30.dp),
                                    color = if (kind == PaperKind.TRANSPARENT) Color(0xFF777777) else Color(kind.baseColor),
                                    shape = MaterialTheme.shapes.small,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray)
                                ) {}
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(kind.title, color = MaterialTheme.colorScheme.onSurface)
                                    Text(
                                        when (kind) {
                                            PaperKind.SMOOTH -> "Fine, nearly toothless surface"
                                            PaperKind.COLD_PRESS -> "Pronounced watercolor tooth"
                                            PaperKind.HOT_PRESS -> "Subtle, even watercolor grain"
                                            PaperKind.WATERCOLOR -> "Deep watercolor texture and fibers"
                                            PaperKind.CANVAS -> "Woven painting surface"
                                            PaperKind.KRAFT -> "Warm brown drawing paper"
                                            PaperKind.RECYCLED -> "Speckled natural paper"
                                            PaperKind.TRANSPARENT -> "No paper background"
                                        },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                if (paperKind == kind) Text("✓", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    if (paperKind != PaperKind.TRANSPARENT) {
                        Spacer(Modifier.height(8.dp))
                        Text("Texture strength · ${(paperStrength * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = paperStrength,
                            onValueChange = {
                                paperStrength = it
                                canvasView?.setPaperTexture(paperKind, it)
                            },
                            valueRange = 0f..1f
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPaperPicker = false }) { Text("Close") }
            }
        )
    }

    if (showColorPicker) {
        AlertDialog(
            onDismissRequest = { showColorPicker = false },
            title = { Text("Color") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = customColorHex,
                        onValueChange = { value ->
                            val normalized = if (value.startsWith("#")) value else "#$value"
                            customColorHex = normalized.take(9)
                        },
                        label = { Text("Hex color") },
                        singleLine = true
                    )
                    TextButton(onClick = {
                        runCatching { AColor.parseColor(customColorHex) }
                            .onSuccess { activeColor = it; showColorPicker = false }
                            .onFailure { Toast.makeText(context, "Enter a valid hex color.", Toast.LENGTH_SHORT).show() }
                    }) { Text("Use custom color") }
                    studioPalette.chunked(4).forEach { rowColors ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            rowColors.forEach { color ->
                                Surface(
                                    color = Color(color),
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.size(42.dp).clickable {
                                        activeColor = color
                                        showColorPicker = false
                                    },
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray)
                                ) {}
                            }
                        }
                    }
                    Text("Harmony", style = MaterialTheme.typography.labelMedium)
                    val harmonyHsv = FloatArray(3).also { AColor.colorToHSV(activeColor, it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(-30f, 0f, 30f, 180f, 120f, 240f).forEach { shift ->
                            val adjusted = harmonyHsv.copyOf()
                            adjusted[0] = (adjusted[0] + shift + 360f) % 360f
                            val harmonyColor = AColor.HSVToColor(adjusted)
                            Surface(
                                color = Color(harmonyColor),
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.size(38.dp).clickable {
                                    activeColor = harmonyColor
                                    showColorPicker = false
                                },
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray)
                            ) {}
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showColorPicker = false }) { Text("Close") }
            }
        )
    }
}
