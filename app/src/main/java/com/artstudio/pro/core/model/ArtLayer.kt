package com.artstudio.pro.core.model
import android.graphics.*
import java.util.UUID

enum class BlendMode { NORMAL, MULTIPLY, SCREEN, OVERLAY }

data class ArtLayer(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val width: Int,
    val height: Int,
    var opacity: Float = 1.0f,
    var isVisible: Boolean = true,
    var isAlphaLocked: Boolean = false,
    var blendMode: BlendMode = BlendMode.NORMAL
) {
    val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas: Canvas = Canvas(bitmap)
    fun clear() = canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
}
