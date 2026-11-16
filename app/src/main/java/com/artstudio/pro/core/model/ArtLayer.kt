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
    var blendMode: BlendMode = BlendMode.NORMAL
) {
    val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas: Canvas = Canvas(bitmap)
    fun clear() = canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
}
