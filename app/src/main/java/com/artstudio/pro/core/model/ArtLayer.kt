package com.artstudio.pro.core.model

import android.graphics.*
import java.util.UUID

enum class BlendMode(val porterDuffMode: PorterDuff.Mode?) {
    NORMAL(null),
    MULTIPLY(PorterDuff.Mode.MULTIPLY),
    SCREEN(PorterDuff.Mode.SCREEN),
    OVERLAY(PorterDuff.Mode.OVERLAY),
    DARKEN(PorterDuff.Mode.DARKEN),
    LIGHTEN(PorterDuff.Mode.LIGHTEN),
    ADD(PorterDuff.Mode.ADD),
    XOR(PorterDuff.Mode.XOR),
    SRC_ATOP(PorterDuff.Mode.SRC_ATOP)
}

data class ArtLayer(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val width: Int,
    val height: Int,
    var opacity: Float = 1.0f,
    var isVisible: Boolean = true,
    var isAlphaLocked: Boolean = false,
    var isClippedToBelow: Boolean = false,
    var brightness: Float = 0f,
    var contrast: Float = 1f,
    var saturation: Float = 1f,
    var blendMode: BlendMode = BlendMode.NORMAL
) {
    fun applyColorAdjustments(paint: Paint) {
        if (brightness == 0f && contrast == 1f && saturation == 1f) {
            paint.colorFilter = null
            return
        }
        val matrix = ColorMatrix().apply { setSaturation(saturation.coerceIn(0f, 2f)) }
        val c = contrast.coerceIn(0f, 2f)
        val b = brightness.coerceIn(-1f, 1f) * 255f
        val tone = ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, b,
            0f, c, 0f, 0f, b,
            0f, 0f, c, 0f, b,
            0f, 0f, 0f, 1f, 0f
        ))
        matrix.postConcat(tone)
        paint.colorFilter = ColorMatrixColorFilter(matrix)
    }

    val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas: Canvas = Canvas(bitmap)
    var maskBitmap: Bitmap? = null
        private set
    var maskEnabled: Boolean = true

    fun createMask(fillWhite: Boolean = true): Bitmap {
        maskBitmap?.takeUnless { it.isRecycled }?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            maskBitmap = it
            if (fillWhite) Canvas(it).drawColor(Color.WHITE)
        }
    }

    fun ensureMask(): Bitmap = maskBitmap?.takeUnless { it.isRecycled } ?: createMask()

    fun clearMask() {
        maskBitmap?.takeUnless { it.isRecycled }?.eraseColor(Color.WHITE)
    }

    fun removeMask() {
        maskBitmap?.takeUnless { it.isRecycled }?.recycle()
        maskBitmap = null
    }

    fun renderMaskedBitmap(): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val mask = maskBitmap
        if (maskEnabled && mask != null && !mask.isRecycled) {
            Canvas(result).drawBitmap(mask, 0f, 0f, Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            })
        }
        return result
    }

    fun clear() = canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
}
