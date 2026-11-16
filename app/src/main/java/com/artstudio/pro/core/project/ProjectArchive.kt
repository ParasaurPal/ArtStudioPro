package com.artstudio.pro.core.project

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.artstudio.pro.core.model.ArtLayer
import com.artstudio.pro.core.model.AnimationFrame
import com.artstudio.pro.core.model.BlendMode
import com.artstudio.pro.core.paper.PaperKind
import com.artstudio.pro.core.vector.VectorPath
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Editable native project archive: metadata plus lossless per-layer PNGs. */
object ProjectArchive {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    @Serializable
    data class ProjectManifest(
        val format: String = "artstudio-project",
        val version: Int = 1,
        val width: Int,
        val height: Int,
        val layers: List<LayerManifest>,
        val paperKind: String = PaperKind.SMOOTH.name,
        val paperStrength: Float = 1f,
        val dpi: Int = 300,
        val frames: List<FrameManifest> = emptyList(),
        val currentFrameIndex: Int = 0
    )

    @Serializable
    data class FrameManifest(
        val durationMs: Int = 100,
        val layers: List<LayerManifest>
    )

    @Serializable
    data class LayerManifest(
        val file: String,
        val name: String,
        val opacity: Float,
        val visible: Boolean,
        val alphaLocked: Boolean,
        val blendMode: String,
        val clippedToBelow: Boolean = false,
        val brightness: Float = 0f,
        val contrast: Float = 1f,
        val saturation: Float = 1f,
        val maskFile: String? = null,
        val maskEnabled: Boolean = true,
        val vectorPaths: List<VectorPath> = emptyList()
    )

    data class LoadedProject(
        val width: Int,
        val height: Int,
        val layers: List<ArtLayer>,
        val paperKind: PaperKind = PaperKind.SMOOTH,
        val paperStrength: Float = 1f,
        val dpi: Int = 300,
        val frames: List<AnimationFrame> = emptyList(),
        val currentFrameIndex: Int = 0
    )

    private fun layerManifest(layer: ArtLayer, file: String) = LayerManifest(
        file = file,
        name = layer.name,
        opacity = layer.opacity.coerceIn(0f, 1f),
        visible = layer.isVisible,
        alphaLocked = layer.isAlphaLocked,
        blendMode = layer.blendMode.name,
        clippedToBelow = layer.isClippedToBelow,
        brightness = layer.brightness,
        contrast = layer.contrast,
        saturation = layer.saturation,
        maskFile = if (layer.maskBitmap != null) file.removeSuffix(".png") + "_mask.png" else null,
        maskEnabled = layer.maskEnabled,
        vectorPaths = layer.vectorPaths.toList()
    )

    private fun writeBitmap(zip: ZipOutputStream, bitmap: Bitmap, file: String) {
        val bytes = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) {
            "Could not encode bitmap: $file"
        }
        zip.putNextEntry(ZipEntry(file))
        zip.write(bytes.toByteArray())
        zip.closeEntry()
    }

    private fun writeLayer(zip: ZipOutputStream, layer: ArtLayer, metadata: LayerManifest) {
        writeBitmap(zip, layer.bitmap, metadata.file)
        layer.maskBitmap?.let { mask -> metadata.maskFile?.let { writeBitmap(zip, mask, it) } }
    }

    fun save(
        output: OutputStream,
        width: Int,
        height: Int,
        layers: List<ArtLayer>,
        paperKind: PaperKind = PaperKind.SMOOTH,
        paperStrength: Float = 1f,
        dpi: Int = 300,
        frames: List<AnimationFrame> = emptyList(),
        currentFrameIndex: Int = 0
    ) {
        require(width > 0 && height > 0) { "Project dimensions must be positive." }
        require(layers.isNotEmpty()) { "A project must contain at least one layer." }
        require(layers.all { it.width == width && it.height == height }) {
            "All layers must match the project dimensions."
        }
        val framesToSave = if (frames.isEmpty()) {
            listOf(AnimationFrame(100, layers.toMutableList()))
        } else frames
        require(framesToSave.size <= 120 && framesToSave.all { frame ->
            frame.layers.isNotEmpty() && frame.layers.all { it.width == width && it.height == height }
        }) { "The animation frame data is invalid." }
        val totalLayerBitmaps = framesToSave.sumOf { it.layers.size.toLong() }
        require(width.toLong() * height.toLong() * totalLayerBitmaps <= 64_000_000L) {
            "This project exceeds the safe mobile memory budget. Reduce layers, frames, or canvas size before saving."
        }
        val selectedIndex = currentFrameIndex.coerceIn(0, framesToSave.lastIndex)
        val frameManifests = framesToSave.mapIndexed { frameIndex, frame ->
            FrameManifest(
                durationMs = frame.durationMs.coerceIn(20, 5000),
                layers = frame.layers.mapIndexed { layerIndex, layer ->
                    layerManifest(layer, "frames/frame_%03d/layer_%03d.png".format(frameIndex, layerIndex))
                }
            )
        }
        val manifest = ProjectManifest(
            width = width,
            height = height,
            paperKind = paperKind.name,
            paperStrength = paperStrength.coerceIn(0f, 1f),
            dpi = dpi.coerceIn(36, 2400),
            layers = frameManifests[selectedIndex].layers,
            frames = frameManifests,
            currentFrameIndex = selectedIndex
        )

        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("project.json"))
            zip.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            framesToSave.forEachIndexed { frameIndex, frame ->
                frame.layers.forEachIndexed { layerIndex, layer ->
                    writeLayer(zip, layer, frameManifests[frameIndex].layers[layerIndex])
                }
            }
            zip.finish()
        }
    }

    fun load(input: InputStream): LoadedProject {
        val entries = mutableMapOf<String, ByteArray>()
        val allowedPath = Regex("(project\\.json|layers/[A-Za-z0-9_.-]+\\.png|frames/frame_[0-9]{3}/layer_[0-9]{3}(_mask)?\\.png)")
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    require(allowedPath.matches(entry.name)) {
                        "The project contains an unsupported file path."
                    }
                    val bytes = ByteArrayOutputStream()
                    zip.copyTo(bytes)
                    entries[entry.name] = bytes.toByteArray()
                }
                zip.closeEntry()
            }
        }

        val manifestBytes = entries["project.json"]
            ?: throw IllegalArgumentException("This is not a valid ArtStudio project.")
        val manifest = json.decodeFromString<ProjectManifest>(manifestBytes.toString(Charsets.UTF_8))
        require(manifest.format == "artstudio-project" && manifest.version == 1) {
            "This project version is not supported."
        }
        require(manifest.width in 1..20000 && manifest.height in 1..20000 &&
            manifest.width.toLong() * manifest.height.toLong() <= 20_000_000L) {
            "Project dimensions exceed the safe mobile canvas memory limit."
        }
        require(manifest.layers.isNotEmpty() && manifest.layers.size <= 512) {
            "The project layer count is invalid."
        }
        val frameManifests = manifest.frames.ifEmpty { listOf(FrameManifest(100, manifest.layers)) }
        require(frameManifests.size <= 120 && frameManifests.all { it.layers.isNotEmpty() && it.layers.size <= 512 }) {
            "The animation frame count or layer count is invalid."
        }
        val totalLayerBitmaps = frameManifests.sumOf { it.layers.size.toLong() }
        require(manifest.width.toLong() * manifest.height.toLong() * totalLayerBitmaps <= 64_000_000L) {
            "This project exceeds the safe mobile memory budget."
        }

        fun decodeLayer(metadata: LayerManifest): ArtLayer {
            val encoded = entries[metadata.file]
                ?: throw IllegalArgumentException("A project layer is missing: ${metadata.file}")
            val bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
                ?: throw IllegalArgumentException("A project layer could not be decoded.")
            require(bitmap.width == manifest.width && bitmap.height == manifest.height) {
                bitmap.recycle()
                "A project layer has dimensions that do not match the document."
            }
            return ArtLayer(
                name = metadata.name,
                width = manifest.width,
                height = manifest.height,
                opacity = metadata.opacity.coerceIn(0f, 1f),
                isVisible = metadata.visible,
                isAlphaLocked = metadata.alphaLocked,
                isClippedToBelow = metadata.clippedToBelow,
                brightness = metadata.brightness.coerceIn(-1f, 1f),
                contrast = metadata.contrast.coerceIn(0f, 2f),
                saturation = metadata.saturation.coerceIn(0f, 2f),
                blendMode = runCatching { BlendMode.valueOf(metadata.blendMode) }.getOrDefault(BlendMode.NORMAL),
                maskEnabled = metadata.maskEnabled,
                vectorPaths = metadata.vectorPaths.toMutableList()
            ).also { layer ->
                layer.canvas.drawBitmap(bitmap, 0f, 0f, null)
                bitmap.recycle()
                metadata.maskFile?.let { maskPath ->
                    entries[maskPath]?.let { maskBytes ->
                        val maskBitmap = BitmapFactory.decodeByteArray(maskBytes, 0, maskBytes.size)
                        if (maskBitmap != null && maskBitmap.width == manifest.width && maskBitmap.height == manifest.height) {
                            layer.createMask(fillWhite = false).also { Canvas(it).drawBitmap(maskBitmap, 0f, 0f, null) }
                        }
                        maskBitmap?.recycle()
                    }
                }
            }
        }

        val loadedFrames = frameManifests.map { frame ->
            AnimationFrame(
                durationMs = frame.durationMs.coerceIn(20, 5000),
                layers = frame.layers.map(::decodeLayer).toMutableList()
            )
        }
        val selectedIndex = manifest.currentFrameIndex.coerceIn(0, loadedFrames.lastIndex)
        val selectedLayers = loadedFrames[selectedIndex].layers
        return LoadedProject(
            width = manifest.width,
            height = manifest.height,
            layers = selectedLayers,
            paperKind = runCatching { PaperKind.valueOf(manifest.paperKind) }.getOrDefault(PaperKind.SMOOTH),
            paperStrength = manifest.paperStrength.coerceIn(0f, 1f),
            dpi = manifest.dpi.coerceIn(36, 2400),
            frames = loadedFrames,
            currentFrameIndex = selectedIndex
        )
    }
}
