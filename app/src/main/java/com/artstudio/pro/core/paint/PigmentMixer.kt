package com.artstudio.pro.core.paint
import android.graphics.Color
import kotlin.math.sqrt

object PigmentMixer {
    fun mixPigments(colorA: Int, colorB: Int, ratioB: Float): Int {
        val t = ratioB.coerceIn(0f, 1f)
        val wA = 1f - t
        val solve = { cA: Int, cB: Int ->
            val rA = (cA / 255f).coerceIn(0.01f, 0.99f)
            val rB = (cB / 255f).coerceIn(0.01f, 0.99f)
            val ks = (1f - rA) * (1f - rA) / (2f * rA) * wA + (1f - rB) * (1f - rB) / (2f * rB) * t
            ((1f + ks - sqrt(ks * ks + 2f * ks)).coerceIn(0f, 1f) * 255).toInt()
        }
        return Color.argb(
            (Color.alpha(colorA) * wA + Color.alpha(colorB) * t).toInt(),
            solve(Color.red(colorA), Color.red(colorB)),
            solve(Color.green(colorA), Color.green(colorB)),
            solve(Color.blue(colorA), Color.blue(colorB))
        )
    }
}
