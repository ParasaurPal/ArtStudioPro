package com.artstudio.pro.core.export

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

object SvgExporter {
    /** SVG wrapper retaining the complete raster composite without losing artwork layers. */
    fun embedRaster(bitmap:Bitmap):String {
        val png=ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG,100,png)){"Could not encode SVG image data."}
        val encoded=Base64.encodeToString(png.toByteArray(),Base64.NO_WRAP)
        return """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="${bitmap.width}" height="${bitmap.height}" viewBox="0 0 ${bitmap.width} ${bitmap.height}">
  <image width="${bitmap.width}" height="${bitmap.height}" xlink:href="data:image/png;base64,$encoded"/>
</svg>"""
    }
}
