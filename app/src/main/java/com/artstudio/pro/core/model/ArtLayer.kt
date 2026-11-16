package com.artstudio.pro.core.model

import android.graphics.*
import java.util.UUID
import com.artstudio.pro.core.vector.VectorPath

enum class BlendMode(
    val porterDuffMode: PorterDuff.Mode?,
    val nativeMode: android.graphics.BlendMode?
) {
    NORMAL(null, null),
    MULTIPLY(PorterDuff.Mode.MULTIPLY, android.graphics.BlendMode.MULTIPLY),
    SCREEN(PorterDuff.Mode.SCREEN, android.graphics.BlendMode.SCREEN),
    OVERLAY(PorterDuff.Mode.OVERLAY, android.graphics.BlendMode.OVERLAY),
    DARKEN(PorterDuff.Mode.DARKEN, android.graphics.BlendMode.DARKEN),
    LIGHTEN(PorterDuff.Mode.LIGHTEN, android.graphics.BlendMode.LIGHTEN),
    ADD(PorterDuff.Mode.ADD, android.graphics.BlendMode.PLUS),
    XOR(PorterDuff.Mode.XOR, android.graphics.BlendMode.XOR),
    SRC_ATOP(PorterDuff.Mode.SRC_ATOP, android.graphics.BlendMode.SRC_ATOP),
    COLOR_BURN(null, android.graphics.BlendMode.COLOR_BURN),
    COLOR_DODGE(null, android.graphics.BlendMode.COLOR_DODGE),
    LINEAR_BURN(PorterDuff.Mode.MULTIPLY, android.graphics.BlendMode.MULTIPLY),
    LINEAR_DODGE(PorterDuff.Mode.ADD, android.graphics.BlendMode.PLUS),
    DARKER_COLOR(PorterDuff.Mode.DARKEN, android.graphics.BlendMode.DARKEN),
    LIGHTER_COLOR(PorterDuff.Mode.LIGHTEN, android.graphics.BlendMode.LIGHTEN),
    SOFT_LIGHT(null, android.graphics.BlendMode.SOFT_LIGHT),
    HARD_LIGHT(null, android.graphics.BlendMode.HARD_LIGHT),
    VIVID_LIGHT(null, android.graphics.BlendMode.HARD_LIGHT),
    LINEAR_LIGHT(null, android.graphics.BlendMode.HARD_LIGHT),
    PIN_LIGHT(null, android.graphics.BlendMode.LIGHTEN),
    HARD_MIX(null, android.graphics.BlendMode.DIFFERENCE),
    DIFFERENCE(null, android.graphics.BlendMode.DIFFERENCE),
    EXCLUSION(null, android.graphics.BlendMode.EXCLUSION),
    SUBTRACT(null, android.graphics.BlendMode.DIFFERENCE),
    DIVIDE(null, android.graphics.BlendMode.SCREEN),
    HUE(null, android.graphics.BlendMode.HUE),
    SATURATION(null, android.graphics.BlendMode.SATURATION),
    COLOR(null, android.graphics.BlendMode.COLOR),
    LUMINOSITY(null, android.graphics.BlendMode.LUMINOSITY),
    GRAIN_MERGE(null, android.graphics.BlendMode.OVERLAY),
    GRAIN_EXTRACT(null, android.graphics.BlendMode.SOFT_LIGHT),
    REFLECT(null, android.graphics.BlendMode.COLOR_DODGE),
    GLOW(null, android.graphics.BlendMode.COLOR_DODGE),
    PHOENIX(null, android.graphics.BlendMode.EXCLUSION),
    NEGATION(null, android.graphics.BlendMode.DIFFERENCE);

    fun applyTo(paint: Paint) {
        if (android.os.Build.VERSION.SDK_INT >= 29 && nativeMode != null) {
            paint.blendMode = nativeMode
            paint.xfermode = null
        } else {
            paint.blendMode = null
            paint.xfermode = porterDuffMode?.let { PorterDuffXfermode(it) }
        }
    }
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
    var blendMode: BlendMode = BlendMode.NORMAL,
    val vectorPaths: MutableList<VectorPath> = mutableListOf(),
    var maskEnabled: Boolean = true,
    var groupId: String? = null
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
