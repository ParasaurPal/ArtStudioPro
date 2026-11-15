package com.artstudio.pro.core.model

import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build

/** Uses the full Android blend pipeline where available and a safe legacy fallback below API 29. */
object BlendModePainter {
    fun apply(paint: Paint, mode: BlendMode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            paint.blendMode = when (mode) {
                BlendMode.NORMAL, BlendMode.PASS_THROUGH -> android.graphics.BlendMode.SRC_OVER
                BlendMode.DARKEN, BlendMode.DARKER_COLOR -> android.graphics.BlendMode.DARKEN
                BlendMode.MULTIPLY, BlendMode.LINEAR_BURN, BlendMode.SUBTRACT -> android.graphics.BlendMode.MULTIPLY
                BlendMode.COLOR_BURN -> android.graphics.BlendMode.COLOR_BURN
                BlendMode.LIGHTEN, BlendMode.LIGHTER_COLOR, BlendMode.PIN_LIGHT -> android.graphics.BlendMode.LIGHTEN
                BlendMode.SCREEN, BlendMode.DIVIDE -> android.graphics.BlendMode.SCREEN
                BlendMode.COLOR_DODGE, BlendMode.VIVID_LIGHT -> android.graphics.BlendMode.COLOR_DODGE
                BlendMode.LINEAR_DODGE, BlendMode.LINEAR_LIGHT -> android.graphics.BlendMode.PLUS
                BlendMode.OVERLAY -> android.graphics.BlendMode.OVERLAY
                BlendMode.SOFT_LIGHT -> android.graphics.BlendMode.SOFT_LIGHT
                BlendMode.HARD_LIGHT -> android.graphics.BlendMode.HARD_LIGHT
                BlendMode.HARD_MIX -> android.graphics.BlendMode.DIFFERENCE
                BlendMode.DIFFERENCE -> android.graphics.BlendMode.DIFFERENCE
                BlendMode.EXCLUSION -> android.graphics.BlendMode.EXCLUSION
                BlendMode.HUE -> android.graphics.BlendMode.HUE
                BlendMode.SATURATION -> android.graphics.BlendMode.SATURATION
                BlendMode.COLOR -> android.graphics.BlendMode.COLOR
                BlendMode.LUMINOSITY -> android.graphics.BlendMode.LUMINOSITY
            }
            paint.xfermode = null
        } else {
            paint.xfermode = PorterDuffXfermode(
                when (mode) {
                    BlendMode.MULTIPLY, BlendMode.LINEAR_BURN, BlendMode.SUBTRACT -> PorterDuff.Mode.MULTIPLY
                    BlendMode.SCREEN, BlendMode.DIVIDE, BlendMode.LINEAR_DODGE -> PorterDuff.Mode.SCREEN
                    BlendMode.OVERLAY, BlendMode.HARD_LIGHT -> PorterDuff.Mode.OVERLAY
                    BlendMode.DARKEN, BlendMode.DARKER_COLOR -> PorterDuff.Mode.DARKEN
                    BlendMode.LIGHTEN, BlendMode.LIGHTER_COLOR, BlendMode.PIN_LIGHT -> PorterDuff.Mode.LIGHTEN
                    else -> PorterDuff.Mode.SRC_OVER
                }
            )
        }
    }
}
