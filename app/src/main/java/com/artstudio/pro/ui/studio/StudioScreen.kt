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
import com.artstudio.pro.core.canvas.ArtCanvasView
import com.artstudio.pro.core.model.ArtLayer
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
    var brushSize by remember { mutableFloatStateOf(40f) }
    var activeColor by remember { mutableIntStateOf(AColor.BLACK) }
    var documentWidth by remember { mutableIntStateOf(2048) }
    var documentHeight by remember { mutableIntStateOf(2048) }
    var pendingLayers by remember { mutableStateOf<List<ArtLayer>?>(null) }
    var layerRefresh by remember { mutableIntStateOf(0) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showNewDocument by remember { mutableStateOf(false) }
    var eraserMode by remember { mutableStateOf(false) }
    var canvasView by remember { mutableStateOf<ArtCanvasView?>(null) }
    val brush = remember { BrushDefinition("basic-ink", "Ink Pen", BrushMedium.INK_PEN) }

    val saveProject = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-artstudio-project")
    ) { uri ->
        if (uri != null) runCatching {
            val view = canvasView ?: error("The canvas is not ready.")
            context.contentResolver.openOutputStream(uri)?.use {
                ProjectArchive.save(it, documentWidth, documentHeight, view.layers)
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
                            pendingLayers?.let { replaceLayers(it) }
                            canvasView = this
                        }
                    },
                    update = { view ->
                        brush.baseSize = brushSize
                        view.strokeEngine?.brush = brush
                        view.strokeEngine?.activeColor = activeColor
                        view.strokeEngine?.eraserMode = eraserMode
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
