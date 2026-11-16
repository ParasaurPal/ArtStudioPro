package com.artstudio.pro.core.color

import android.graphics.Color
import kotlin.math.pow

enum class ColorProfile(val displayName:String) {
    SRGB("sRGB IEC61966-2.1"), DISPLAY_P3("Display P3"), ADOBE_RGB("Wide-gamut RGB"), GRAYSCALE("Grayscale"), CMYK_SWOP("CMYK print simulation")
}

enum class ColorHarmony { COMPLEMENTARY, ANALOGOUS, TRIADIC, SPLIT_COMPLEMENTARY, TETRADIC, MONOCHROMATIC }

data class Cmyk(val c:Float,val m:Float,val y:Float,val k:Float)

object ColorManagement {
    fun toCmyk(color:Int):Cmyk {
        val r=Color.red(color)/255f;val g=Color.green(color)/255f;val b=Color.blue(color)/255f
        val k=1f-maxOf(r,g,b)
        if(k>=0.999f)return Cmyk(0f,0f,0f,1f)
        return Cmyk(((1-r-k)/(1-k)).coerceIn(0f,1f),((1-g-k)/(1-k)).coerceIn(0f,1f),((1-b-k)/(1-k)).coerceIn(0f,1f),k)
    }
    fun fromCmyk(cmyk:Cmyk):Int {
        val c=cmyk.c.coerceIn(0f,1f);val m=cmyk.m.coerceIn(0f,1f);val y=cmyk.y.coerceIn(0f,1f);val k=cmyk.k.coerceIn(0f,1f)
        return Color.rgb(((1-c)*(1-k)*255).toInt(),((1-m)*(1-k)*255).toInt(),((1-y)*(1-k)*255).toInt())
    }
    fun convert(color:Int,from:ColorProfile,to:ColorProfile):Int {
        if(from==to)return color
        if(to==ColorProfile.GRAYSCALE){val y=(.2126f*Color.red(color)+.7152f*Color.green(color)+.0722f*Color.blue(color)).toInt();return Color.argb(Color.alpha(color),y,y,y)}
        if(to==ColorProfile.CMYK_SWOP)return fromCmyk(toCmyk(color))
        if(from==ColorProfile.CMYK_SWOP)return color
        val gamma=when(from){ColorProfile.ADOBE_RGB->2.2f;else->2.4f}
        val r=(Color.red(color)/255f).pow(gamma);val g=(Color.green(color)/255f).pow(gamma);val b=(Color.blue(color)/255f).pow(gamma)
        val matrix=when {
            from==ColorProfile.SRGB && to==ColorProfile.DISPLAY_P3 -> floatArrayOf(.8225f,.1774f,0f,.0332f,.9668f,0f,.0171f,.0724f,.9105f)
            from==ColorProfile.DISPLAY_P3 && to==ColorProfile.SRGB -> floatArrayOf(1.2249f,-.2247f,0f,-.042f,1.042f,0f,-.0197f,-.0786f,1.0983f)
            else -> floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f)
        }
        val rr=(matrix[0]*r+matrix[1]*g+matrix[2]*b).coerceIn(0f,1f).pow(1f/2.4f)
        val gg=(matrix[3]*r+matrix[4]*g+matrix[5]*b).coerceIn(0f,1f).pow(1f/2.4f)
        val bb=(matrix[6]*r+matrix[7]*g+matrix[8]*b).coerceIn(0f,1f).pow(1f/2.4f)
        return Color.argb(Color.alpha(color),(rr*255).toInt(),(gg*255).toInt(),(bb*255).toInt())
    }
    fun harmonies(color:Int,harmony:ColorHarmony):List<Int> {
        val hsv=FloatArray(3);Color.colorToHSV(color,hsv)
        val offsets=when(harmony){
            ColorHarmony.COMPLEMENTARY->listOf(0f,180f)
            ColorHarmony.ANALOGOUS->listOf(-30f,0f,30f)
            ColorHarmony.TRIADIC->listOf(0f,120f,240f)
            ColorHarmony.SPLIT_COMPLEMENTARY->listOf(0f,150f,210f)
            ColorHarmony.TETRADIC->listOf(0f,90f,180f,270f)
            ColorHarmony.MONOCHROMATIC->listOf(0f,0f,0f,0f)
        }
        return offsets.mapIndexed { i,offset ->
            val h=hsv.copyOf();h[0]=(h[0]+offset+360f)%360f
            if(harmony==ColorHarmony.MONOCHROMATIC) h[2]=(0.28f+i*.2f).coerceIn(0f,1f)
            Color.HSVToColor(Color.alpha(color),h)
        }
    }
}
