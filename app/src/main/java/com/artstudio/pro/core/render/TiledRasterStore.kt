package com.artstudio.pro.core.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import java.util.LinkedHashMap

enum class ColorPrecision { RGBA_8, RGBA_16_FLOAT }

/**
 * Sparse tile-backed raster surface. Tiles are allocated on demand and may be
 * evicted/reloaded through the supplied backing callbacks to cap mobile RAM.
 */
class TiledRasterStore(
    val width:Int,
    val height:Int,
    val tileSize:Int=512,
    val precision:ColorPrecision=ColorPrecision.RGBA_16_FLOAT,
    private val maxResidentTiles:Int=24,
    private val readTile:((Int,Int)->Bitmap?)?=null,
    private val writeTile:((Int,Int,Bitmap)->Unit)?=null
) {
    private data class Key(val x:Int,val y:Int)
    private val resident=object:LinkedHashMap<Key,Bitmap>(16,.75f,true){}
    private val dirty=mutableSetOf<Key>()
    private val config get()=if(precision==ColorPrecision.RGBA_16_FLOAT) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
    val columns get()=(width+tileSize-1)/tileSize
    val rows get()=(height+tileSize-1)/tileSize
    val tileCount get()=columns*rows

    init { require(width>0&&height>0&&tileSize in 64..2048&&maxResidentTiles>0) }

    @Synchronized
    fun tile(column:Int,row:Int,create:Boolean=true):Bitmap? {
        require(column in 0 until columns&&row in 0 until rows)
        val key=Key(column,row)
        resident[key]?.let{return it}
        val loaded=readTile?.invoke(column,row)
        val bitmap=loaded?:if(create) Bitmap.createBitmap(tileWidth(column),tileHeight(row),config) else return null
        resident[key]=bitmap
        evictIfNeeded(key)
        return bitmap
    }

    fun tileWidth(column:Int)=minOf(tileSize,width-column*tileSize)
    fun tileHeight(row:Int)=minOf(tileSize,height-row*tileSize)

    @Synchronized
    fun markDirty(column:Int,row:Int){dirty+=Key(column,row)}

    @Synchronized
    fun drawBitmap(source:Bitmap,destination:RectF,paint:android.graphics.Paint=android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)){
        val left=destination.left.toInt().coerceIn(0,width);val top=destination.top.toInt().coerceIn(0,height)
        val right=destination.right.toInt().coerceIn(0,width);val bottom=destination.bottom.toInt().coerceIn(0,height)
        if(right<=left||bottom<=top)return
        val firstCol=left/tileSize;val lastCol=(right-1)/tileSize
        val firstRow=top/tileSize;val lastRow=(bottom-1)/tileSize
        for(row in firstRow..lastRow)for(col in firstCol..lastCol){
            val tile=tile(col,row)?:continue
            val tileRect=Rect(col*tileSize,row*tileSize,col*tileSize+tile.width,row*tileSize+tile.height)
            val intersect=Rect(left,top,right,bottom)
            if(!intersect.intersect(tileRect))continue
            val sx=((intersect.left-destination.left)/destination.width()*source.width).toInt().coerceIn(0,source.width-1)
            val sy=((intersect.top-destination.top)/destination.height()*source.height).toInt().coerceIn(0,source.height-1)
            val ex=(((intersect.right-destination.left)/destination.width()*source.width).toInt()).coerceIn(sx+1,source.width)
            val ey=(((intersect.bottom-destination.top)/destination.height()*source.height).toInt()).coerceIn(sy+1,source.height)
            val dst=Rect(intersect.left-tileRect.left,intersect.top-tileRect.top,intersect.right-tileRect.left,intersect.bottom-tileRect.top)
            Canvas(tile).drawBitmap(source,Rect(sx,sy,ex,ey),dst,paint)
            markDirty(col,row)
        }
    }

    @Synchronized
    fun render(target:Canvas,scale:Float=1f,paint:android.graphics.Paint=android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)){
        for(row in 0 until rows)for(col in 0 until columns){
            val tile=tile(col,row,create=false)?:continue
            val left=col*tileSize*scale;val top=row*tileSize*scale
            target.drawBitmap(tile,null,RectF(left,top,left+tile.width*scale,top+tile.height*scale),paint)
        }
    }

    @Synchronized
    fun flush(){
        dirty.toList().forEach{key->resident[key]?.let{writeTile?.invoke(key.x,key.y,it)}}
        dirty.clear()
    }

    @Synchronized
    fun clear(){
        resident.values.forEach{if(!it.isRecycled)it.recycle()}
        resident.clear();dirty.clear()
    }

    private fun evictIfNeeded(protected:Key){
        while(resident.size>maxResidentTiles){
            val first=resident.entries.firstOrNull{it.key!=protected}?:return
            if(first.key in dirty)writeTile?.invoke(first.key.x,first.key.y,first.value)
            dirty.remove(first.key)
            resident.remove(first.key)
            first.value.recycle()
        }
    }
}
