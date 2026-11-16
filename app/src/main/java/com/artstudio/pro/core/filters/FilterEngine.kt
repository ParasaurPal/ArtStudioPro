package com.artstudio.pro.core.filters

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class ImageFilter(val label: String) {
    GRAYSCALE("Grayscale"), SEPIA("Sepia"), INVERT("Invert"), POSTERIZE("Posterize"),
    SOLARIZE("Solarize"), VINTAGE("Vintage"), HIGH_CONTRAST("High contrast"),
    WARM("Warm"), COOL("Cool"), PIXELATE("Pixelate"), GAUSSIAN_BLUR("Gaussian blur"),
    SHARPEN("Sharpen"), EDGE_DETECT("Edge detect"), EMBOSS("Emboss"),
    HUE_ROTATE("Hue rotate"), SATURATE("Saturate")
}

/** Non-destructive-friendly bitmap effects. Every operation returns a new bitmap. */
object FilterEngine {
    fun apply(source: Bitmap, filter: ImageFilter, amount: Float = 1f): Bitmap {
        val strength = amount.coerceIn(0f, 1f)
        return when (filter) {
            ImageFilter.GAUSSIAN_BLUR -> blur(source, (1f + strength * 24f).toInt())
            ImageFilter.SHARPEN -> convolve(source, floatArrayOf(0f,-1f,0f,-1f,5f,-1f,0f,-1f,0f), strength)
            ImageFilter.EDGE_DETECT -> convolve(source, floatArrayOf(-1f,-1f,-1f,-1f,8f,-1f,-1f,-1f,-1f), strength)
            ImageFilter.EMBOSS -> convolve(source, floatArrayOf(-2f,-1f,0f,-1f,1f,1f,0f,1f,2f), strength)
            ImageFilter.PIXELATE -> pixelate(source, (2 + strength * 38).toInt())
            else -> mapPixels(source) { color ->
                val a=Color.alpha(color); val r=Color.red(color).toFloat(); val g=Color.green(color).toFloat(); val b=Color.blue(color).toFloat()
                val gray=0.299f*r+0.587f*g+0.114f*b
                val transformed = when(filter) {
                    ImageFilter.GRAYSCALE -> floatArrayOf(gray,gray,gray)
                    ImageFilter.SEPIA -> floatArrayOf((0.393f*r+0.769f*g+0.189f*b).coerceAtMost(255f),(0.349f*r+0.686f*g+0.168f*b).coerceAtMost(255f),(0.272f*r+0.534f*g+0.131f*b).coerceAtMost(255f))
                    ImageFilter.INVERT -> floatArrayOf(255-r,255-g,255-b)
                    ImageFilter.POSTERIZE -> floatArrayOf(quantize(r,5),quantize(g,5),quantize(b,5))
                    ImageFilter.SOLARIZE -> floatArrayOf(if(r>127)255-r else r,if(g>127)255-g else g,if(b>127)255-b else b)
                    ImageFilter.VINTAGE -> floatArrayOf(0.72f*r+0.18f*g+24f,0.68f*g+0.16f*r+14f,0.62f*b+0.12f*g)
                    ImageFilter.HIGH_CONTRAST -> floatArrayOf(((r-128)*1.5f+128).coerceIn(0f,255f),((g-128)*1.5f+128).coerceIn(0f,255f),((b-128)*1.5f+128).coerceIn(0f,255f))
                    ImageFilter.WARM -> floatArrayOf((r+30f*strength).coerceAtMost(255f),g,(b-22f*strength).coerceAtLeast(0f))
                    ImageFilter.COOL -> floatArrayOf((r-18f*strength).coerceAtLeast(0f),g,(b+28f*strength).coerceAtMost(255f))
                    ImageFilter.HUE_ROTATE -> hueRotate(r,g,b,amount*360f)
                    ImageFilter.SATURATE -> floatArrayOf(gray+(r-gray)*(1f+strength),gray+(g-gray)*(1f+strength),gray+(b-gray)*(1f+strength))
                    else -> floatArrayOf(r,g,b)
                }
                val mix = if(filter==ImageFilter.GRAYSCALE || filter==ImageFilter.SEPIA || filter==ImageFilter.INVERT || filter==ImageFilter.POSTERIZE || filter==ImageFilter.SOLARIZE || filter==ImageFilter.VINTAGE || filter==ImageFilter.HIGH_CONTRAST || filter==ImageFilter.HUE_ROTATE || filter==ImageFilter.SATURATE) strength else 1f
                Color.argb(a, blend(r,transformed[0],mix), blend(g,transformed[1],mix), blend(b,transformed[2],mix))
            }
        }
    }

    private fun quantize(v:Float, levels:Int):Float {
        val step=255f/(levels-1)
        return (kotlin.math.round(v/step)*step).coerceIn(0f,255f)
    }
    private fun blend(a:Float,b:Float,t:Float)= (a+(b-a)*t).toInt().coerceIn(0,255)
    private fun mapPixels(src:Bitmap, transform:(Int)->Int):Bitmap {
        val out=src.copy(Bitmap.Config.ARGB_8888,true)
        val pixels=IntArray(out.width*out.height)
        out.getPixels(pixels,0,out.width,0,0,out.width,out.height)
        for(i in pixels.indices) pixels[i]=transform(pixels[i])
        out.setPixels(pixels,0,out.width,0,0,out.width,out.height)
        return out
    }
    private fun pixelate(src:Bitmap, block:Int):Bitmap {
        val out=Bitmap.createBitmap(src.width,src.height,Bitmap.Config.ARGB_8888)
        val c=android.graphics.Canvas(out); val p=android.graphics.Paint()
        var y=0
        while(y<src.height){var x=0;while(x<src.width){
            val color=src.getPixel(x,y);p.color=color
            c.drawRect(x.toFloat(),y.toFloat(),(x+block).toFloat(),(y+block).toFloat(),p);x+=block
        };y+=block}
        return out
    }
    private fun blur(src:Bitmap,radius:Int):Bitmap {
        val scale=0.35f
        val small=Bitmap.createScaledBitmap(src,(src.width*scale).toInt().coerceAtLeast(1),(src.height*scale).toInt().coerceAtLeast(1),true)
        val out=Bitmap.createBitmap(small.width,small.height,Bitmap.Config.ARGB_8888)
        val paint=android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply { maskFilter=android.graphics.BlurMaskFilter(radius*scale,android.graphics.BlurMaskFilter.Blur.NORMAL) }
        android.graphics.Canvas(out).drawBitmap(small,0f,0f,paint)
        val result=Bitmap.createScaledBitmap(out,src.width,src.height,true)
        small.recycle();out.recycle()
        return result
    }
    private fun convolve(src:Bitmap,k:FloatArray,amount:Float):Bitmap {
        val w=src.width;val h=src.height;val input=IntArray(w*h);val output=IntArray(w*h)
        src.getPixels(input,0,w,0,0,w,h)
        for(y in 0 until h) for(x in 0 until w){
            var rr=0f;var gg=0f;var bb=0f
            for(ky in -1..1) for(kx in -1..1){
                val px=(x+kx).coerceIn(0,w-1);val py=(y+ky).coerceIn(0,h-1);val c=input[py*w+px];val weight=k[(ky+1)*3+kx+1]
                rr+=Color.red(c)*weight;gg+=Color.green(c)*weight;bb+=Color.blue(c)*weight
            }
            val base=input[y*w+x];val a=Color.alpha(base)
            output[y*w+x]=Color.argb(a,blend(Color.red(base).toFloat(),rr.coerceIn(0f,255f),amount),blend(Color.green(base).toFloat(),gg.coerceIn(0f,255f),amount),blend(Color.blue(base).toFloat(),bb.coerceIn(0f,255f),amount))
        }
        return Bitmap.createBitmap(output,w,h,Bitmap.Config.ARGB_8888)
    }
    private fun hueRotate(r:Float,g:Float,b:Float,degrees:Float):FloatArray {
        val angle=degrees*PI.toFloat()/180f;val c=cos(angle);val s=sin(angle)
        val nr=(0.213f+0.787f*c-0.213f*s)*r+(0.715f-0.715f*c-0.715f*s)*g+(0.072f-0.072f*c+0.928f*s)*b
        val ng=(0.213f-0.213f*c+0.143f*s)*r+(0.715f+0.285f*c+0.140f*s)*g+(0.072f-0.072f*c-0.283f*s)*b
        val nb=(0.213f-0.213f*c-0.787f*s)*r+(0.715f-0.715f*c+0.715f*s)*g+(0.072f+0.928f*c+0.072f*s)*b
        return floatArrayOf(nr.coerceIn(0f,255f),ng.coerceIn(0f,255f),nb.coerceIn(0f,255f))
    }
}
