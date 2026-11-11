package com.artstudio.pro.ui.studio
import android.graphics.Color as AColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.artstudio.pro.core.brush.engine.StrokeEngine
import com.artstudio.pro.core.brush.model.*
import com.artstudio.pro.core.canvas.ArtCanvasView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen() {
    var brushSize by remember { mutableFloatStateOf(40f) }
    var isRed by remember { mutableStateOf(false) }
    val brush = remember { BrushDefinition("pen", "Ink Pen", BrushMedium.INK_PEN) }

    val activeComposeColor = if (isRed) Color.Red else Color.Black
    val activeAndroidColor = if (isRed) AColor.RED else AColor.BLACK

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ArtStudio Pro", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E1E))
            )
        },
        bottomBar = {
            Surface(color = Color(0xFF1E1E1E), modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Size", color = Color.White)
                    Slider(value = brushSize, onValueChange = { brushSize = it }, valueRange = 2f..200f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    IconButton(onClick = { isRed = !isRed }) {
                        Icon(Icons.Default.ColorLens, "Color", tint = activeComposeColor)
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(Color(0xFF121212))) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    ArtCanvasView(ctx, 2048, 2048).apply {
                        this.strokeEngine = StrokeEngine(brush, activeAndroidColor)
                    }
                },
                update = { view ->
                    brush.baseSize = brushSize
                    view.strokeEngine?.brush = brush
                    view.strokeEngine?.activeColor = activeAndroidColor
                }
            )
        }
    }
}
