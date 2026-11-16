package com.artstudio.pro.core.brush.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class BrushLibraryDocument(val brushes: List<BrushDefinition> = emptyList())

object BrushLibrary {
    private const val FILE_NAME = "brush_library.json"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun load(context: Context): List<BrushDefinition> = runCatching {
        val file = context.getFileStreamPath(FILE_NAME)
        if (!file.exists()) emptyList()
        else file.inputStream().bufferedReader().use {
            json.decodeFromString<BrushLibraryDocument>(it.readText()).brushes.map(::restoreTip)
        }
    }.getOrDefault(emptyList())

    private fun encodeTip(bitmap: Bitmap): String {
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Could not encode brush tip." }
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    private fun encodeBrush(brush: BrushDefinition): BrushDefinition = brush.copy(
        advancedParameters = brush.advancedParameters.toMutableMap(),
        tipMaskPngBase64 = brush.tipMask?.takeUnless { it.isRecycled }?.let(::encodeTip) ?: brush.tipMaskPngBase64,
        texturePngBase64 = brush.textureBitmap?.takeUnless { it.isRecycled }?.let(::encodeTip) ?: brush.texturePngBase64
    )

    private fun restoreTip(brush: BrushDefinition): BrushDefinition {
        val bitmap = brush.tipMaskPngBase64?.let { encoded ->
            runCatching {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
        val texture = brush.texturePngBase64?.let { encoded ->
            runCatching {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
        return brush.copy(
            advancedParameters = brush.advancedParameters.toMutableMap(),
            tipMask = bitmap,
            textureBitmap = texture
        )
    }

    fun encode(brushes: List<BrushDefinition>): String =
        json.encodeToString(BrushLibraryDocument(brushes.distinctBy { it.id }.map(::encodeBrush)))

    fun decode(serialized: String): List<BrushDefinition> =
        json.decodeFromString<BrushLibraryDocument>(serialized).brushes.map(::restoreTip)

    fun save(context: Context, brushes: List<BrushDefinition>) {
        val document = BrushLibraryDocument(brushes.distinctBy { it.id }.map(::encodeBrush))
        context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).bufferedWriter().use {
            it.write(json.encodeToString(document))
        }
    }
}
