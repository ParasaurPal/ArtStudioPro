package com.artstudio.pro.core.brush.model

data class BrushPreset(
    val name: String,
    val medium: BrushMedium,
    val size: Float,
    val opacity: Float,
    val flow: Float,
    val spacing: Float,
    val streamline: Float,
    val minimumSizeRatio: Float
) {
    fun create(): BrushDefinition = BrushDefinition(
        id = name.lowercase().replace(Regex("[^a-z0-9]+"), "-"),
        name = name,
        medium = medium,
        baseSize = size,
        minSizeRatio = minimumSizeRatio,
        baseOpacity = opacity,
        flow = flow,
        spacing = spacing,
        streamline = streamline
    )
}

/** Tuned starting points for the current stamp-based brush engine. */
object BrushPresets {
    val all = listOf(
        BrushPreset("HB Pencil", BrushMedium.GRAPHITE, 12f, 0.62f, 0.72f, 0.045f, 0.28f, 0.22f),
        BrushPreset("Soft Graphite", BrushMedium.GRAPHITE, 28f, 0.42f, 0.48f, 0.08f, 0.32f, 0.18f),
        BrushPreset("Charcoal", BrushMedium.CHARCOAL, 34f, 0.82f, 0.68f, 0.12f, 0.25f, 0.3f),
        BrushPreset("G-Pen", BrushMedium.INK_PEN, 5f, 1f, 1f, 0.035f, 0.62f, 0.12f),
        BrushPreset("Technical Pen", BrushMedium.INK_PEN, 3f, 1f, 1f, 0.025f, 0.78f, 0.7f),
        BrushPreset("Round Marker", BrushMedium.PROCEDURAL, 30f, 0.72f, 0.82f, 0.06f, 0.42f, 0.5f),
        BrushPreset("Soft Airbrush", BrushMedium.PROCEDURAL, 96f, 0.28f, 0.22f, 0.025f, 0.18f, 0.8f),
        BrushPreset("Watercolor Wash", BrushMedium.WATERCOLOR, 72f, 0.38f, 0.34f, 0.035f, 0.2f, 0.45f),
        BrushPreset("Oil Paint", BrushMedium.OIL_IMPASTO, 42f, 0.96f, 0.94f, 0.055f, 0.3f, 0.28f),
        BrushPreset("Fine Detail", BrushMedium.INK_PEN, 2f, 1f, 1f, 0.025f, 0.86f, 0.85f)
    )
}
