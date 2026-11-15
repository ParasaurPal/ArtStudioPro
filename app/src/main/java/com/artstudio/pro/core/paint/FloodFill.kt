package com.artstudio.pro.core.paint

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs

/** Four-connected flood fill with per-channel tolerance and bounded queue memory. */
object FloodFill {
    fun fill(
        bitmap: Bitmap,
        startX: Int,
        startY: Int,
        fillColor: Int,
        tolerance: Int = 0,
        preserveAlpha: Boolean = false,
        beforeApply: (Rect) -> Unit = {}
    ): Rect? {
        val width = bitmap.width
        val height = bitmap.height
        if (startX !in 0 until width || startY !in 0 until height) return null

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val start = startY * width + startX
        val targetColor = pixels[start]
        if (targetColor == fillColor) return null

        val threshold = tolerance.coerceIn(0, 255)
        val visited = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        var head = 0
        var tail = 0
        var minX = startX
        var minY = startY
        var maxX = startX
        var maxY = startY
        queue[tail++] = start
        visited[start] = true

        fun matches(color: Int): Boolean =
            abs(android.graphics.Color.alpha(color) - android.graphics.Color.alpha(targetColor)) <= threshold &&
            abs(android.graphics.Color.red(color) - android.graphics.Color.red(targetColor)) <= threshold &&
            abs(android.graphics.Color.green(color) - android.graphics.Color.green(targetColor)) <= threshold &&
            abs(android.graphics.Color.blue(color) - android.graphics.Color.blue(targetColor)) <= threshold

        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
            val original = pixels[index]
            val nextColor = if (preserveAlpha) {
                android.graphics.Color.argb(
                    android.graphics.Color.alpha(original),
                    android.graphics.Color.red(fillColor),
                    android.graphics.Color.green(fillColor),
                    android.graphics.Color.blue(fillColor)
                )
            } else fillColor
            pixels[index] = nextColor
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)

            val neighbors = intArrayOf(
                if (x > 0) index - 1 else -1,
                if (x + 1 < width) index + 1 else -1,
                if (y > 0) index - width else -1,
                if (y + 1 < height) index + width else -1
            )
            neighbors.forEach { next ->
                if (next >= 0 && !visited[next] && matches(pixels[next])) {
                    visited[next] = true
                    queue[tail++] = next
                }
            }
        }

        val bounds = Rect(minX, minY, maxX + 1, maxY + 1)
        beforeApply(bounds)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bounds
    }
}
