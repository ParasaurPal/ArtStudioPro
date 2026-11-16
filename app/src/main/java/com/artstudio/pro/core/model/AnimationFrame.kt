package com.artstudio.pro.core.model

data class AnimationFrame(
    var durationMs: Int = 100,
    val layers: MutableList<ArtLayer>
)
