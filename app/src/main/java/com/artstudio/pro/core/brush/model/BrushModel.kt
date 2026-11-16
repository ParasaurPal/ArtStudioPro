package com.artstudio.pro.core.brush.model
import android.graphics.Bitmap
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

enum class BrushMedium { GRAPHITE, CHARCOAL, INK_PEN, WATERCOLOR, OIL_IMPASTO, SCREENTONE, PROCEDURAL }
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
    var scatter: Float = 0f,
    var rotationJitter: Float = 0f,
    var sizeJitter: Float = 0f,
    var angleFollowsStroke: Boolean = true,
    var sizeSource: DynamicsSource = DynamicsSource.PRESSURE,
    var opacitySource: DynamicsSource = DynamicsSource.PRESSURE,
    var flowSource: DynamicsSource = DynamicsSource.FIXED,
    var angleSource: DynamicsSource = DynamicsSource.FIXED,
    var roundness: Float = 1f,
    var hardness: Float = 1f,
    var textureStrength: Float = 0f,
    var textureScale: Float = 1f,
    var wetness: Float = 0f,
    var pigmentLoad: Float = 1f,
    var bristleCount: Int = 1,
    var wetMixing: Boolean = false,
    var flowCurve: DynamicsCurve = DynamicsCurve(),
    var opacityCurve: DynamicsCurve = DynamicsCurve(),
    var sizeCurve: DynamicsCurve = DynamicsCurve(),
    val advancedParameters: MutableMap<String, Float> = BrushParameter.defaults(),
    var tipMaskPngBase64: String? = null,
    @Transient var tipMask: Bitmap? = null
) {
    fun parameter(parameter: BrushParameter): Float = advancedParameters[parameter.name] ?: parameter.defaultValue
    fun setParameter(parameter: BrushParameter, value: Float) {
        advancedParameters[parameter.name] = value.coerceIn(parameter.minValue, parameter.maxValue)
    }
}
