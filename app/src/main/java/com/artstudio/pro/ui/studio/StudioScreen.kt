package com.artstudio.pro.ui.studio

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
import com.artstudio.pro.core.brush.model.BrushPresets
import com.artstudio.pro.core.canvas.ArtCanvasView
import com.artstudio.pro.core.model.ArtLayer
import com.artstudio.pro.core.model.BlendMode
import com.artstudio.pro.core.project.ProjectArchive
import com.artstudio.pro.core.project.DocumentPresets

private val studioPalette = listOf(
    AColor.BLACK, AColor.WHITE, AColor.RED, AColor.rgb(255, 145, 0),
    AColor.YELLOW, AColor.GREEN, AColor.CYAN, AColor.BLUE,
    AColor.MAGENTA, AColor.rgb(130, 80, 190), AColor.rgb(120, 75, 45)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var brushSize by remember { mutableFloatStateOf(5f) }
    var brushMenuExpanded by remember { mutableStateOf(false) }
    var activeColor by remember { mutableIntStateOf(AColor.BLACK) }
    var documentWidth by remember { mutableIntStateOf(2048) }
    var documentHeight by remember { mutableIntStateOf(2048) }
    var documentDpi by remember { mutableIntStateOf(72) }
    var showCustomCanvas by remember { mutableStateOf(false) }
    var customWidth by remember { mutableStateOf("2048") }
    var customHeight by remember { mutableStateOf("2048") }
    var customDpi by remember { mutableStateOf("300") }
    var pendingLayers by remember { mutableStateOf<List<ArtLayer>?>(null) }
    var layerRefresh by remember { mutableIntStateOf(0) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showNewDocument by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var eraserMode by remember { mutableStateOf(false) }
    var fillMode by remember { mutableStateOf(false) }
    var fillTolerance by remember { mutableFloatStateOf(18f) }
    var verticalSymmetry by remember { mutableStateOf(false) }
    var horizontalSymmetry by remember { mutableStateOf(false) }
    var showGrid by remember { mutableStateOf(false) }
    var transparentBackground by remember { mutableStateOf(false) }
    var layerOpacity by remember { mutableFloatStateOf(1f) }
    var alphaLocked by remember { mutableStateOf(false) }
    var selectedBlendMode by remember { mutableStateOf(BlendMode.NORMAL) }
    var blendMenuExpanded by remember { mutableStateOf(false) }
    var hexColor by remember { mutableStateOf("#000000") }
    var hue by remember { mutableFloatStateOf(0f) }
    var saturation by remember { mutableFloatStateOf(0f) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var canvasView by remember { mutableStateOf<ArtCanvasView?>(null) }
    val gridVisible = showGrid
    val transparentPage = transparentBackground
    val fillModeEnabled = fillMode
    val fillToleranceValue = fillTolerance.toInt()
    LaunchedEffect(activeColor) {
        hexColor = String.format("#%06X", 0xFFFFFF and activeColor)
        val hsv = FloatArray(3)
        AColor.colorToHSV(activeColor, hsv)
        hue = hsv[0]
        saturation = hsv[1]
        brightness = hsv[2]
    }
    LaunchedEffect(layerRefresh, canvasView) {
        layerOpacity = canvasView?.activeLayer?.opacity ?: 1f
        selectedBlendMode = canvasView?.activeLayer?.blendMode ?: BlendMode.NORMAL
        alphaLocked = canvasView?.activeLayer?.isAlphaLocked ?: false
    }
    var brush by remember { mutableStateOf(BrushPresets.all.first { it.name == "G-Pen" }.create()) }

    val saveProject = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-artstudio-project")
    ) { uri ->
        if (uri != null) runCatching {
            val view = canvasView ?: error("The canvas is not ready.")
            context.contentResolver.openOutputStream(uri)?.use {
                ProjectArchive.save(it, documentWidth, documentHeight, view.layers, transparentBackground, documentDpi)
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

    val openProject = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) runCatching {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Unable to open the selected project.")
            input.use { ProjectArchive.load(it) }
        }.onSuccess { project ->
            transparentBackground = project.transparentBackground
            documentDpi = project.dpi
            if (project.width == documentWidth && project.height == documentHeight) {
                canvasView?.replaceLayers(project.layers)
                layerRefresh++
            } else {
                pendingLayers = project.layers
                documentWidth = project.width
                documentHeight = project.height
            }
            Toast.makeText(context, "Project opened", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "Open failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    val importImage = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val boundsInput = context.contentResolver.openInputStream(uri)
                ?: error("Unable to read the selected image.")
            boundsInput.use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The selected file is not a supported image." }
            val targetLimit = maxOf(documentWidth, documentHeight) * 2
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > targetLimit || bounds.outHeight / sampleSize > targetLimit) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: error("Unable to decode the selected image.")
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
                    if (fillMode) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Fill tolerance", color = Color.White, style = MaterialTheme.typography.labelSmall)
                            Slider(value = fillTolerance, onValueChange = { fillTolerance = it }, valueRange = 0f..128f, modifier = Modifier.weight(1f))
                            Text(fillTolerance.toInt().toString(), color = Color.White)
                        }
                    }
                    Text("Layers (${canvasView?.layers?.size ?: 0}) · ${layerRefresh}", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box {
                            TextButton(onClick = { brushMenuExpanded = true }) { Text(brush.name) }
                            DropdownMenu(
                                expanded = brushMenuExpanded,
                                onDismissRequest = { brushMenuExpanded = false }
                            ) {
                                BrushPresets.all.forEach { preset ->
                                    DropdownMenuItem(
                                        text = { Text(preset.name) },
                                        onClick = {
                                            brush = preset.create()
                                            brushSize = brush.baseSize
                                            canvasView?.strokeEngine?.brush = brush
                                            brushMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        TextButton(onClick = {
                            fillMode = !fillMode
                            canvasView?.fillMode = fillMode
                            canvasView?.fillTolerance = fillTolerance.toInt()
                        }) { Text(if (fillMode) "Bucket on" else "Bucket") }
                        TextButton(onClick = { canvasView?.undo(); layerRefresh++ }) { Text("Undo") }
                        TextButton(onClick = { canvasView?.redo(); layerRefresh++ }) { Text("Redo") }
                        TextButton(onClick = {
                            eraserMode = !eraserMode
                            canvasView?.strokeEngine?.eraserMode = eraserMode
                        }) { Text(if (eraserMode) "Eraser on" else "Eraser") }
                        TextButton(onClick = {
                            verticalSymmetry = !verticalSymmetry
                            canvasView?.strokeEngine?.symmetryVertical = verticalSymmetry
                        }) { Text(if (verticalSymmetry) "Vertical symmetry on" else "Vertical symmetry") }
                        TextButton(onClick = {
                            horizontalSymmetry = !horizontalSymmetry
                            canvasView?.strokeEngine?.symmetryHorizontal = horizontalSymmetry
                        }) { Text(if (horizontalSymmetry) "Horizontal symmetry on" else "Horizontal symmetry") }
                        TextButton(onClick = {
                            showGrid = !showGrid
                            canvasView?.showGrid = showGrid
                            canvasView?.invalidate()
                        }) { Text(if (showGrid) "Grid on" else "Grid") }
                        TextButton(onClick = {
                            transparentBackground = !transparentBackground
                            canvasView?.transparentBackground = transparentBackground
                            canvasView?.invalidate()
                        }) { Text(if (transparentBackground) "Transparent page" else "White page") }
                        TextButton(onClick = {
                            canvasView?.addLayer()
                            layerRefresh++
                        }) { Text("New layer") }
                        TextButton(onClick = {
                            canvasView?.duplicateActiveLayer()
                            layerRefresh++
                        }) { Text("Duplicate") }
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
                        TextButton(onClick = {
                            alphaLocked = !alphaLocked
                            canvasView?.activeLayer?.isAlphaLocked = alphaLocked
                            layerRefresh++
                        }) { Text(if (alphaLocked) "Alpha lock on" else "Alpha lock") }
                        TextButton(onClick = {
                            canvasView?.moveActiveLayer((canvasView?.activeLayerIndex ?: 0) + 1)
                            layerRefresh++
                        }) { Text("Move up") }
                        TextButton(onClick = {
                            canvasView?.moveActiveLayer((canvasView?.activeLayerIndex ?: 0) - 1)
                            layerRefresh++
                        }) { Text("Move down") }
                        TextButton(onClick = {
                            canvasView?.mergeVisibleLayers()
                            layerRefresh++
                        }) { Text("Merge visible") }
                        TextButton(onClick = { canvasView?.flipCanvas(horizontal = true, vertical = false); layerRefresh++ }) { Text("Flip H") }
                        TextButton(onClick = { canvasView?.flipCanvas(horizontal = false, vertical = true); layerRefresh++ }) { Text("Flip V") }
                        TextButton(onClick = { canvasView?.rotateCanvas180(); layerRefresh++ }) { Text("Rotate 180°") }
                        TextButton(onClick = {
                            renameText = canvasView?.activeLayer?.name.orEmpty()
                            showRenameDialog = true
                        }) { Text("Rename") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Opacity", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Slider(
                            value = layerOpacity,
                            onValueChange = {
                                layerOpacity = it
                                canvasView?.activeLayer?.opacity = it
                                canvasView?.invalidate()
                            },
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                        Box {
                            TextButton(onClick = { blendMenuExpanded = true }) {
                                Text(selectedBlendMode.name.replace('_', ' '), color = Color.White)
                            }
                            DropdownMenu(
                                expanded = blendMenuExpanded,
                                onDismissRequest = { blendMenuExpanded = false }
                            ) {
                                BlendMode.values().forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode.name.replace('_', ' ')) },
                                        onClick = {
                                            selectedBlendMode = mode
                                            canvasView?.activeLayer?.blendMode = mode
                                            canvasView?.invalidate()
                                            blendMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
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
                                    layerOpacity = layer.opacity
                                    selectedBlendMode = layer.blendMode
                                    alphaLocked = layer.isAlphaLocked
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
                            showGrid = gridVisible
                            fillMode = fillModeEnabled
                            fillTolerance = fillToleranceValue
                            transparentBackground = transparentPage
                            strokeEngine = StrokeEngine(brush, activeColor).apply {
                                symmetryCenterX = documentWidth / 2f
                                symmetryCenterY = documentHeight / 2f
                                symmetryVertical = verticalSymmetry
                                symmetryHorizontal = horizontalSymmetry
                                eraserMode = eraserMode
                            }
                            pendingLayers?.let { replaceLayers(it) }
                            canvasView = this
                        }
                    },
                    update = { view ->
                        brush.baseSize = brushSize
                        view.strokeEngine?.brush = brush
                        view.strokeEngine?.activeColor = activeColor
                        view.strokeEngine?.eraserMode = eraserMode
                        view.strokeEngine?.symmetryVertical = verticalSymmetry
                        view.strokeEngine?.symmetryHorizontal = horizontalSymmetry
                        view.showGrid = showGrid
                        view.transparentBackground = transparentBackground
                        view.fillMode = fillMode
                        view.fillTolerance = fillTolerance.toInt()
                        view.strokeEngine?.symmetryCenterX = documentWidth / 2f
                        view.strokeEngine?.symmetryCenterY = documentHeight / 2f
                        canvasView = view
                    }
                )
            }
        }
    }

    if (showCustomCanvas) {
        AlertDialog(
            onDismissRequest = { showCustomCanvas = false },
            title = { Text("Custom canvas") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = customWidth, onValueChange = { customWidth = it.filter { char -> char.isDigit() }.take(5) }, label = { Text("Width (px)") }, singleLine = true)
                    OutlinedTextField(value = customHeight, onValueChange = { customHeight = it.filter(Char::isDigit).take(5) }, label = { Text("Height (px)") }, singleLine = true)
                    OutlinedTextField(value = customDpi, onValueChange = { customDpi = it.filter(Char::isDigit).take(4) }, label = { Text("Resolution (DPI)") }, singleLine = true)
                    val widthPx = customWidth.toIntOrNull() ?: 0
                    val heightPx = customHeight.toIntOrNull() ?: 0
                    val dpiValue = customDpi.toIntOrNull() ?: 0
                    if (widthPx > 0 && heightPx > 0 && dpiValue > 0) {
                        Text(
                            "Physical size: ${"%.2f".format(widthPx / dpiValue.toFloat())} × ${"%.2f".format(heightPx / dpiValue.toFloat())} in",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val widthPx = customWidth.toIntOrNull()
                    val heightPx = customHeight.toIntOrNull()
                    val dpiValue = customDpi.toIntOrNull()
                    if (widthPx != null && heightPx != null && dpiValue != null &&
                        widthPx in 1..12000 && heightPx in 1..12000 && dpiValue in 1..2400 &&
                        widthPx.toLong() * heightPx.toLong() <= 100_000_000L
                    ) {
                        val freshLayer = ArtLayer(name = "Background", width = widthPx, height = heightPx)
                        documentDpi = dpiValue
                        transparentBackground = false
                        if (widthPx == documentWidth && heightPx == documentHeight) {
                            canvasView?.replaceLayers(listOf(freshLayer))
                            layerRefresh++
                        } else {
                            pendingLayers = listOf(freshLayer)
                            documentWidth = widthPx
                            documentHeight = heightPx
                        }
                        showCustomCanvas = false
                    } else {
                        Toast.makeText(context, "Enter valid dimensions and DPI within the supported limits.", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomCanvas = false }) { Text("Cancel") }
            }
        )
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename layer") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Layer name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    canvasView?.renameActiveLayer(renameText)
                    layerRefresh++
                    showRenameDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showNewDocument) {
        AlertDialog(
            onDismissRequest = { showNewDocument = false },
            title = { Text("New canvas") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())
                ) {
                    TextButton(
                        onClick = {
                            showNewDocument = false
                            showCustomCanvas = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Custom dimensions", color = MaterialTheme.colorScheme.primary) }
                    DocumentPresets.all.forEach { preset ->
                        TextButton(
                            onClick = {
                                val freshLayer = ArtLayer(
                                    name = "Background",
                                    width = preset.width,
                                    height = preset.height
                                )
                                documentDpi = preset.dpi
                                transparentBackground = false
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
                }
            },
            confirmButton = {
                TextButton(onClick = { showNewDocument = false }) { Text("Cancel") }
            }
        )
    }

    if (showColorPicker) {
        AlertDialog(
            onDismissRequest = { showColorPicker = false },
            title = { Text("Color") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Hue", color = MaterialTheme.colorScheme.onSurface)
                    Slider(value = hue, onValueChange = {
                        hue = it
                        activeColor = AColor.HSVToColor(floatArrayOf(hue, saturation, brightness))
                    }, valueRange = 0f..360f)
                    Text("Saturation", color = MaterialTheme.colorScheme.onSurface)
                    Slider(value = saturation, onValueChange = {
                        saturation = it
                        activeColor = AColor.HSVToColor(floatArrayOf(hue, saturation, brightness))
                    }, valueRange = 0f..1f)
                    Text("Brightness", color = MaterialTheme.colorScheme.onSurface)
                    Slider(value = brightness, onValueChange = {
                        brightness = it
                        activeColor = AColor.HSVToColor(floatArrayOf(hue, saturation, brightness))
                    }, valueRange = 0f..1f)
                    OutlinedTextField(
                        value = hexColor,
                        onValueChange = { hexColor = it.take(9) },
                        label = { Text("Hex color") },
                        singleLine = true
                    )
                    TextButton(onClick = {
                        runCatching { AColor.parseColor(hexColor) }.onSuccess {
                            activeColor = it
                            showColorPicker = false
                        }
                    }) { Text("Apply hex") }
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
                }
            },
            confirmButton = {
                TextButton(onClick = { showColorPicker = false }) { Text("Close") }
            }
        )
    }
}
