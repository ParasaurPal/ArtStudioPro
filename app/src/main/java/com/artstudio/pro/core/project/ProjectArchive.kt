package com.artstudio.pro.core.project

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.artstudio.pro.core.model.ArtLayer
import com.artstudio.pro.core.paper.PaperKind
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

/**
 * Native .asp project archive.
 *
 * The archive is a ZIP containing project.json and one lossless PNG per layer.
 * Keeping layers separate preserves editability instead of silently flattening
 * the user's work on save.
 */
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
        val dpi: Int = 300
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
        val saturation: Float = 1f
    )

    data class LoadedProject(
        val width: Int,
        val height: Int,
        val layers: List<ArtLayer>,
        val paperKind: PaperKind = PaperKind.SMOOTH,
        val paperStrength: Float = 1f,
        val dpi: Int = 300
    )

    fun save(
        output: OutputStream,
        width: Int,
        height: Int,
        layers: List<ArtLayer>,
        paperKind: PaperKind = PaperKind.SMOOTH,
        paperStrength: Float = 1f,
        dpi: Int = 300
    ) {
        require(width > 0 && height > 0) { "Project dimensions must be positive." }
        require(layers.isNotEmpty()) { "A project must contain at least one layer." }
        require(layers.all { it.width == width && it.height == height }) {
            "All layers must match the project dimensions."
        }
        require(width.toLong() * height.toLong() * layers.size.toLong() <= 64_000_000L) {
            "This project exceeds the safe mobile memory budget. Merge layers or reduce the canvas size before saving."
        }

        val manifest = ProjectManifest(
            width = width,
            height = height,
            paperKind = paperKind.name,
            paperStrength = paperStrength.coerceIn(0f, 1f),
            dpi = dpi.coerceIn(36, 2400),
            layers = layers.mapIndexed { index, layer ->
                LayerManifest(
                    file = "layers/layer_%03d.png".format(index),
                    name = layer.name,
                    opacity = layer.opacity.coerceIn(0f, 1f),
                    visible = layer.isVisible,
                    alphaLocked = layer.isAlphaLocked,
                    blendMode = layer.blendMode.name,
                    clippedToBelow = layer.isClippedToBelow,
                    brightness = layer.brightness,
                    contrast = layer.contrast,
                    saturation = layer.saturation
                )
            }
        )

        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("project.json"))
            zip.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            manifest.layers.forEachIndexed { index, metadata ->
                val bitmap = layers[index].bitmap
                val bytes = ByteArrayOutputStream()
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) {
                    "Could not encode layer: ${layers[index].name}"
                }
                zip.putNextEntry(ZipEntry(metadata.file))
                zip.write(bytes.toByteArray())
                zip.closeEntry()
            }
            zip.finish()
        }
    }

    fun load(input: InputStream): LoadedProject {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    require(entry.name == "project.json" || entry.name.matches(Regex("layers/[A-Za-z0-9_.-]+\\.png"))) {
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
        require(manifest.width.toLong() * manifest.height.toLong() * manifest.layers.size.toLong() <= 64_000_000L) {
            "This project exceeds the safe mobile memory budget."
        }

        val loadedLayers = manifest.layers.map { metadata ->
            val encoded = entries[metadata.file]
                ?: throw IllegalArgumentException("A project layer is missing: ${metadata.file}")
            val bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
                ?: throw IllegalArgumentException("A project layer could not be decoded.")
            require(bitmap.width == manifest.width && bitmap.height == manifest.height) {
                bitmap.recycle()
                "A project layer has dimensions that do not match the document."
            }
            ArtLayer(
                name = metadata.name,
                width = manifest.width,
                height = manifest.height,
                opacity = metadata.opacity.coerceIn(0f, 1f),
                isVisible = metadata.visible,
                isAlphaLocked = metadata.alphaLocked,
                isClippedToBelow = metadata.clippedToBelow,
                brightness = metadata.brightness.coerceIn(-1f, 1f),
                contrast = metadata.contrast.coerceIn(0f, 2f),
                saturation = metadata.saturation.coerceIn(0f, 2f)
            ).also { layer ->
                layer.canvas.drawBitmap(bitmap, 0f, 0f, null)
                layer.blendMode = runCatching {
                    com.artstudio.pro.core.model.BlendMode.valueOf(metadata.blendMode)
                }.getOrDefault(com.artstudio.pro.core.model.BlendMode.NORMAL)
                bitmap.recycle()
            }
        }
        return LoadedProject(
            manifest.width,
            manifest.height,
            loadedLayers,
            runCatching { PaperKind.valueOf(manifest.paperKind) }.getOrDefault(PaperKind.SMOOTH),
            manifest.paperStrength.coerceIn(0f, 1f),
            manifest.dpi.coerceIn(36, 2400)
        )
    }
}
