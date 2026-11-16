package com.artstudio.pro.core.brush.model

import android.content.Context
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
            json.decodeFromString<BrushLibraryDocument>(it.readText()).brushes
        }
    }.getOrDefault(emptyList())

    fun save(context: Context, brushes: List<BrushDefinition>) {
        val document = BrushLibraryDocument(brushes.distinctBy { it.id })
        context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).bufferedWriter().use {
            it.write(json.encodeToString(document))
        }
    }
}
