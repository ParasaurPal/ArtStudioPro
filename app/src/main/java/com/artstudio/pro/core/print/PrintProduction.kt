package com.artstudio.pro.core.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.artstudio.pro.core.color.Cmyk
import com.artstudio.pro.core.color.ColorManagement

data class PrintSettings(
    val dpi:Int=300,
    val bleedMm:Float=3f,
    val safeMarginMm:Float=5f,
    val showCropMarks:Boolean=false,
    val includeBleed:Boolean=true,
    val profileName:String="sRGB IEC61966-2.1"
) {
    init { require(dpi in 36..2400);require(bleedMm in 0f..30f);require(safeMarginMm in 0f..50f) }
    fun pixelsFromMm(mm:Float)=(mm*dpi/25.4f).toInt()
}

object PrintProduction {
    fun withBleed(source:Bitmap,settings:PrintSettings):Bitmap {
        val bleed=if(settings.includeBleed)settings.pixelsFromMm(settings.bleedMm) else 0
        val out=Bitmap.createBitmap(source.width+bleed*2,source.height+bleed*2,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(out);val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source,null,RectF(bleed.toFloat(),bleed.toFloat(),(bleed+source.width).toFloat(),(bleed+source.height).toFloat()),paint)
        if(bleed>0){
            canvas.drawBitmap(source,android.graphics.Rect(0,0,source.width,1),RectF(bleed.toFloat(),0f,(bleed+source.width).toFloat(),bleed.toFloat()),paint)
            canvas.drawBitmap(source,android.graphics.Rect(0,source.height-1,source.width,source.height),RectF(bleed.toFloat(),(bleed+source.height).toFloat(),(bleed+source.width).toFloat(),out.height.toFloat()),paint)
            canvas.drawBitmap(source,android.graphics.Rect(0,0,1,source.height),RectF(0f,bleed.toFloat(),bleed.toFloat(),(bleed+source.height).toFloat()),paint)
            canvas.drawBitmap(source,android.graphics.Rect(source.width-1,0,source.width,source.height),RectF((bleed+source.width).toFloat(),bleed.toFloat(),out.width.toFloat(),(bleed+source.height).toFloat()),paint)
        }
        return out
    }
    fun drawCropMarks(canvas:Canvas,content:RectF,bleed:Float,markLength:Float=24f){
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;strokeWidth=1f}
        val l=content.left-bleed;val t=content.top-bleed;val r=content.right+bleed;val b=content.bottom+bleed
        listOf(l to t,l to b,r to t,r to b).forEach{(x,y)->
            val sx=if(x==l)-1f else 1f;val sy=if(y==t)-1f else 1f
            canvas.drawLine(x+sx*4,y,x+sx*(markLength+4),y,p)
            canvas.drawLine(x,y+sy*4,x,y+sy*(markLength+4),p)
        }
    }
    fun cmykProof(color:Int):Cmyk=ColorManagement.toCmyk(color)
}
