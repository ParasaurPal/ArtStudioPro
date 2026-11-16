package com.artstudio.pro.core.model

import android.graphics.*
import java.util.UUID
import com.artstudio.pro.core.vector.VectorPath

private val nativeBlendApi by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    val type = Class.forName("android.graphics.BlendMode")
    type to android.graphics.Paint::class.java.getMethod("setBlendMode", type)
}

enum class BlendMode(
    val porterDuffMode: PorterDuff.Mode?,
    val nativeModeName: String?
) {
    NORMAL(null, null),
    MULTIPLY(PorterDuff.Mode.MULTIPLY, "MULTIPLY"),
    SCREEN(PorterDuff.Mode.SCREEN, "SCREEN"),
    OVERLAY(PorterDuff.Mode.OVERLAY, "OVERLAY"),
    DARKEN(PorterDuff.Mode.DARKEN, "DARKEN"),
    LIGHTEN(PorterDuff.Mode.LIGHTEN, "LIGHTEN"),
    ADD(PorterDuff.Mode.ADD, "PLUS"),
    XOR(PorterDuff.Mode.XOR, "XOR"),
    SRC_ATOP(PorterDuff.Mode.SRC_ATOP, "SRC_ATOP"),
    COLOR_BURN(null, "COLOR_BURN"),
    COLOR_DODGE(null, "COLOR_DODGE"),
    LINEAR_BURN(PorterDuff.Mode.MULTIPLY, "MULTIPLY"),
    LINEAR_DODGE(PorterDuff.Mode.ADD, "PLUS"),
    DARKER_COLOR(PorterDuff.Mode.DARKEN, "DARKEN"),
    LIGHTER_COLOR(PorterDuff.Mode.LIGHTEN, "LIGHTEN"),
    SOFT_LIGHT(null, "SOFT_LIGHT"),
    HARD_LIGHT(null, "HARD_LIGHT"),
    VIVID_LIGHT(null, "HARD_LIGHT"),
    LINEAR_LIGHT(null, "HARD_LIGHT"),
    PIN_LIGHT(null, "LIGHTEN"),
    HARD_MIX(null, "DIFFERENCE"),
    DIFFERENCE(null, "DIFFERENCE"),
    EXCLUSION(null, "EXCLUSION"),
    SUBTRACT(null, "DIFFERENCE"),
    DIVIDE(null, "SCREEN"),
    HUE(null, "HUE"),
    SATURATION(null, "SATURATION"),
    COLOR(null, "COLOR"),
    LUMINOSITY(null, "LUMINOSITY"),
    GRAIN_MERGE(null, "OVERLAY"),
    GRAIN_EXTRACT(null, "SOFT_LIGHT"),
    REFLECT(null, "COLOR_DODGE"),
    GLOW(null, "COLOR_DODGE"),
    PHOENIX(null, "EXCLUSION"),
    NEGATION(null, "DIFFERENCE");

    fun applyTo(paint: Paint) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val (type, setter) = nativeBlendApi
            val mode = nativeModeName?.let { name ->
                type.enumConstants.firstOrNull { (it as Enum<*>).name == name }
            }
            setter.invoke(paint, mode)
            paint.xfermode = null
        } else {
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
    var groupId: String? = null,
    var isLocked: Boolean = false
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
