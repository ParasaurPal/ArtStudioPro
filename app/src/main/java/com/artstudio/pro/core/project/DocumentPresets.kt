package com.artstudio.pro.core.project

/** Pixel-accurate starting points; dimensions are pixels at the listed resolution. */
data class DocumentPreset(
    val name: String,
    val width: Int,
    val height: Int,
    val dpi: Int,
    val category: String
) {
    init {
        require(width > 0 && height > 0 && dpi > 0)
    }
}

object DocumentPresets {
    val all = listOf(
        DocumentPreset("HD Landscape", 1920, 1080, 72, "Screen"),
        DocumentPreset("2K Landscape", 2560, 1440, 72, "Screen"),
        DocumentPreset("4K UHD", 3840, 2160, 72, "Screen"),
        DocumentPreset("Square Social", 2048, 2048, 72, "Screen"),
        DocumentPreset("Portrait Social", 1080, 1920, 72, "Screen"),
        DocumentPreset("Story / Reel", 1080, 1920, 72, "Screen"),
        DocumentPreset("Web Banner", 2560, 1024, 72, "Screen"),
        DocumentPreset("Icon / Avatar", 1024, 1024, 72, "Screen"),
        DocumentPreset("A5 Print", 1748, 2480, 300, "Print"),
        DocumentPreset("A4 Print", 2480, 3508, 300, "Print"),
        DocumentPreset("A3 Print", 3508, 4961, 300, "Print"),
        DocumentPreset("US Letter", 2550, 3300, 300, "Print"),
        DocumentPreset("US Legal", 2550, 4200, 300, "Print"),
        DocumentPreset("Postcard 6 × 4 in", 1800, 1200, 300, "Print"),
        DocumentPreset("Comic Manuscript", 2550, 3300, 600, "Comic"),
        DocumentPreset("Square Print 10 in", 3000, 3000, 300, "Print")
    )
}
