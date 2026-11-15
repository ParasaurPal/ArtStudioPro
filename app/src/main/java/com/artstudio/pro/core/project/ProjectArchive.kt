package com.artstudio.pro.core.project

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.artstudio.pro.core.model.ArtLayer
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
        val layers: List<LayerManifest>
    )

    @Serializable
    data class LayerManifest(
        val file: String,
        val name: String,
        val opacity: Float,
        val visible: Boolean,
        val alphaLocked: Boolean,
        val blendMode: String
    )

    data class LoadedProject(
        val width: Int,
        val height: Int,
        val layers: List<ArtLayer>
    )

    fun save(output: OutputStream, width: Int, height: Int, layers: List<ArtLayer>) {
        require(width in 1..12000 && height in 1..12000) { "Project dimensions are outside the supported range." }
        require(layers.isNotEmpty() && layers.size <= 128) { "A project must contain between 1 and 128 layers." }
        require(width.toLong() * height.toLong() * layers.size.toLong() <= 80_000_000L) {
            "This project exceeds the safe memory budget for saving."
        }
        require(layers.all { it.width == width && it.height == height }) {
            "All layers must match the project dimensions."
        }

        val manifest = ProjectManifest(
            width = width,
            height = height,
            layers = layers.mapIndexed { index, layer ->
                LayerManifest(
                    file = "layers/layer_%03d.png".format(index),
                    name = layer.name,
                    opacity = layer.opacity.coerceIn(0f, 1f),
                    visible = layer.isVisible,
                    alphaLocked = layer.isAlphaLocked,
                    blendMode = layer.blendMode.name
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
        var totalBytes = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    require(entry.name == "project.json" || entry.name.matches(Regex("layers/[A-Za-z0-9_.-]+\\.png"))) {
                        "The project contains an unsupported file path."
                    }
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var entryBytes = 0L
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        entryBytes += count
                        totalBytes += count
                        require(entryBytes <= 256L * 1024 * 1024 && totalBytes <= 512L * 1024 * 1024) {
                            "The project archive is too large to open safely."
                        }
                        bytes.write(buffer, 0, count)
                    }
                    require(entries.put(entry.name, bytes.toByteArray()) == null) {
                        "The project contains duplicate file entries."
                    }
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
        require(manifest.width in 1..12000 && manifest.height in 1..12000) {
            "Project dimensions are outside the supported range."
        }
        require(manifest.layers.isNotEmpty() && manifest.layers.size <= 128) {
            "The project layer count is invalid."
        }
        val totalPixels = manifest.width.toLong() * manifest.height.toLong() * manifest.layers.size.toLong()
        require(totalPixels <= 80_000_000L) {
            "This project exceeds the safe memory budget for opening on this device."
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
                isAlphaLocked = metadata.alphaLocked
            ).also { layer ->
                layer.canvas.drawBitmap(bitmap, 0f, 0f, null)
                layer.blendMode = runCatching {
                    com.artstudio.pro.core.model.BlendMode.valueOf(metadata.blendMode)
                }.getOrDefault(com.artstudio.pro.core.model.BlendMode.NORMAL)
                bitmap.recycle()
            }
        }
        return LoadedProject(manifest.width, manifest.height, loadedLayers)
    }
}
