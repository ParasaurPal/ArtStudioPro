package com.artstudio.pro.core.paper

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import java.util.Random
import kotlin.math.abs

enum class PaperKind(val title: String, val baseColor: Int, val grain: Float, val fiber: Float) {
    SMOOTH("Smooth", Color.rgb(250, 249, 246), 0.10f, 0.02f),
    COLD_PRESS("Cold press", Color.rgb(247, 244, 235), 0.30f, 0.12f),
    HOT_PRESS("Hot press", Color.rgb(250, 248, 241), 0.17f, 0.05f),
    WATERCOLOR("Watercolor", Color.rgb(245, 240, 226), 0.42f, 0.20f),
    CANVAS("Canvas", Color.rgb(231, 222, 204), 0.36f, 0.10f),
    KRAFT("Kraft", Color.rgb(190, 157, 111), 0.25f, 0.08f),
    RECYCLED("Recycled", Color.rgb(232, 229, 215), 0.34f, 0.18f),
    TRANSPARENT("Transparent", Color.TRANSPARENT, 0f, 0f)
}

/** Small seamless procedural paper tile. It is generated once and repeated at document scale. */
object PaperTexture {
    private const val TILE_SIZE = 256
    private val cache = mutableMapOf<PaperKind, Bitmap>()

    @Synchronized
    fun tile(kind: PaperKind): Bitmap? {
        if (kind == PaperKind.TRANSPARENT) return null
        return cache.getOrPut(kind) { create(kind) }
    }

    fun draw(canvas: Canvas, bounds: RectF, kind: PaperKind, strength: Float = 1f) {
        if (kind == PaperKind.TRANSPARENT) return
        val bitmap = tile(kind) ?: return
        val shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            this.shader = shader
            alpha = (strength.coerceIn(0f, 1f) * 255f).toInt()
        }
        canvas.drawRect(bounds, paint)
    }

    private fun create(kind: PaperKind): Bitmap {
        val random = Random(0x51A7L + kind.ordinal * 7919L)
        val pixels = IntArray(TILE_SIZE * TILE_SIZE)
        val base = kind.baseColor
        val br = Color.red(base)
        val bg = Color.green(base)
        val bb = Color.blue(base)
        val noiseScale = kind.grain
        for (y in 0 until TILE_SIZE) {
            for (x in 0 until TILE_SIZE) {
                val noise = (random.nextFloat() - 0.5f) * 34f * noiseScale
                val wave = (kotlin.math.sin(x * 0.13) + kotlin.math.sin(y * 0.17)) * 2.0 * kind.grain
                val fiberMark = if (random.nextFloat() < kind.fiber * 0.035f) -12f else 0f
                val variation = (noise + wave + fiberMark).toInt()
                pixels[y * TILE_SIZE + x] = Color.rgb(
                    (br + variation).coerceIn(0, 255),
                    (bg + variation).coerceIn(0, 255),
                    (bb + variation).coerceIn(0, 255)
                )
            }
        }
        val bitmap = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, TILE_SIZE, 0, 0, TILE_SIZE, TILE_SIZE)
        val fiberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((kind.fiber * 42).toInt(), 105, 91, 70)
            strokeWidth = 0.7f
        }
        val canvas = Canvas(bitmap)
        repeat((kind.fiber * 150).toInt()) {
            val x = random.nextFloat() * TILE_SIZE
            val y = random.nextFloat() * TILE_SIZE
            val length = 2f + random.nextFloat() * 12f * kind.fiber
            canvas.drawLine(x, y, x + length, y + random.nextFloat() * 1.8f - 0.9f, fiberPaint)
        }
        return bitmap
    }
}
