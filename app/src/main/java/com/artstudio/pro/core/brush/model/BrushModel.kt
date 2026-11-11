package com.artstudio.pro.core.brush.model
import android.graphics.Bitmap
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

enum class BrushMedium { GRAPHITE, CHARCOAL, INK_PEN, WATERCOLOR, OIL_IMPASTO, PROCEDURAL }
enum class DynamicsSource { PRESSURE, TILT, VELOCITY, FIXED }

@Serializable
data class DynamicsCurve(val p1y: Float = 0.1f, val p2y: Float = 1.0f) {
    fun evaluate(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        val u = 1f - c
        return 3 * u * u * c * p1y + 3 * u * c * c * p2y + c * c * c
    }
}

@Serializable
data class BrushDefinition(
    val id: String,
    val name: String,
    val medium: BrushMedium,
    var baseSize: Float = 48f,
    var minSizeRatio: Float = 0.15f,
    var baseOpacity: Float = 1f,
    var flow: Float = 0.85f,
    var spacing: Float = 0.08f,
    var streamline: Float = 0.65f,
    var sizeSource: DynamicsSource = DynamicsSource.PRESSURE,
    val sizeCurve: DynamicsCurve = DynamicsCurve(),
    @Transient var tipMask: Bitmap? = null
)
