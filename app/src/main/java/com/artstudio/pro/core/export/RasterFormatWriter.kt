package com.artstudio.pro.core.export

import android.graphics.Bitmap
import com.artstudio.pro.core.color.ColorManagement
import com.artstudio.pro.core.color.ColorProfile
import com.artstudio.pro.core.model.ArtLayer
import com.artstudio.pro.core.vector.VectorRenderer
import java.io.DataOutputStream
import java.io.OutputStream

/** Minimal standards-compliant flattened RGB PSD and baseline TIFF writers. */
object RasterFormatWriter {
    fun writeLayeredPsd(
        layers:List<ArtLayer>,
        composite:Bitmap,
        output:OutputStream,
        profile:ColorProfile=ColorProfile.SRGB,
        isVisible:(ArtLayer)->Boolean={it.isVisible}
    ) {
        require(layers.isNotEmpty() && layers.size<=512){"PSD layer count is invalid."}
        val width=composite.width;val height=composite.height
        require(width in 1..16000&&height in 1..16000&&width.toLong()*height<=20_000_000L){"PSD dimensions exceed the mobile limit."}
        require(layers.all{it.width==width&&it.height==height}){"PSD layers must match the composite dimensions."}
        val ordered=layers.asReversed()
        val pixelCount=width.toLong()*height
        val channelLength=2L+pixelCount
        val recordBytes=ordered.sumOf { layer ->
            val name=layer.name.toByteArray(Charsets.UTF_8).take(255).toByteArray()
            val padded=((name.size+1+3)/4)*4
            66L+padded
        }
        val layerInfoLength=2L+recordBytes+ordered.size*4L*channelLength
        val layerMaskLength=4L+layerInfoLength+4L
        require(layerMaskLength<=Int.MAX_VALUE){"Layered PSD exceeds the supported file size."}
        val out=java.io.DataOutputStream(output.buffered())
        out.writeBytes("8BPS");out.writeShort(1);out.write(ByteArray(6))
        out.writeShort(4);out.writeInt(height);out.writeInt(width);out.writeShort(8);out.writeShort(3)
        out.writeInt(0);out.writeInt(0)
        out.writeInt(layerMaskLength.toInt())
        out.writeInt(layerInfoLength.toInt())
        out.writeShort(ordered.size)
        ordered.forEach { layer ->
            out.writeInt(0);out.writeInt(0);out.writeInt(height);out.writeInt(width)
            out.writeShort(4)
            val channelIds=shortArrayOf(-1,0,1,2)
            channelIds.forEach { id -> out.writeShort(id.toInt());out.writeInt(channelLength.toInt()) }
            out.writeBytes("8BIM")
            out.writeBytes(blendKey(layer.blendMode))
            out.writeByte((layer.opacity.coerceIn(0f,1f)*255f).toInt())
            out.writeByte(if(layer.isClippedToBelow)1 else 0)
            out.writeByte(if(isVisible(layer))0 else 2)
            out.writeByte(0)
            val name=layer.name.toByteArray(Charsets.UTF_8).take(255).toByteArray()
            val padded=((name.size+1+3)/4)*4
            val extraLength=8+padded
            out.writeInt(extraLength)
            out.writeInt(0)
            out.writeInt(0)
            out.writeByte(name.size)
            out.write(name)
            repeat(padded-name.size-1){out.writeByte(0)}
        }
        ordered.forEach { layer ->
            val raw=layer.renderMaskedBitmap()
            VectorRenderer.draw(android.graphics.Canvas(raw),layer.vectorPaths)
            val adjusted=android.graphics.Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            val adjustmentPaint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
            layer.applyColorAdjustments(adjustmentPaint)
            android.graphics.Canvas(adjusted).drawBitmap(raw,0f,0f,adjustmentPaint)
            raw.recycle()
            val profiled=if(profile==ColorProfile.SRGB)adjusted else ColorManagement.convertBitmap(adjusted,ColorProfile.SRGB,profile).also{adjusted.recycle()}
            val row=IntArray(width)
            val channelSelectors=listOf<(Int)->Int>({android.graphics.Color.alpha(it)},{android.graphics.Color.red(it)},{android.graphics.Color.green(it)},{android.graphics.Color.blue(it)})
            channelSelectors.forEach { selector ->
                out.writeShort(0)
                for(y in 0 until height){
                    profiled.getPixels(row,0,width,0,y,width,1)
                    row.forEach { out.writeByte(selector(it)) }
                }
            }
            profiled.recycle()
        }
        out.writeInt(0)
        out.writeShort(0)
        val row=IntArray(width)
        val compositeChannels=listOf<(Int)->Int>({android.graphics.Color.red(it)},{android.graphics.Color.green(it)},{android.graphics.Color.blue(it)},{android.graphics.Color.alpha(it)})
        compositeChannels.forEach { selector ->
            for(y in 0 until height){
                composite.getPixels(row,0,width,0,y,width,1)
                row.forEach { out.writeByte(selector(it)) }
            }
        }
        out.flush()
    }

    private fun blendKey(mode:com.artstudio.pro.core.model.BlendMode):String = when(mode) {
        com.artstudio.pro.core.model.BlendMode.MULTIPLY -> "mul "
        com.artstudio.pro.core.model.BlendMode.SCREEN -> "scrn"
        com.artstudio.pro.core.model.BlendMode.OVERLAY -> "over"
        com.artstudio.pro.core.model.BlendMode.DARKEN -> "dark"
        com.artstudio.pro.core.model.BlendMode.LIGHTEN -> "lite"
        com.artstudio.pro.core.model.BlendMode.COLOR_BURN -> "idiv"
        com.artstudio.pro.core.model.BlendMode.COLOR_DODGE -> "div "
        com.artstudio.pro.core.model.BlendMode.DIFFERENCE -> "diff"
        com.artstudio.pro.core.model.BlendMode.EXCLUSION -> "excl"
        else -> "norm"
    }

    fun writePsd(bitmap:Bitmap,output:OutputStream) {
        val out=DataOutputStream(output.buffered())
        out.writeBytes("8BPS");out.writeShort(1);out.write(ByteArray(6))
        out.writeShort(3);out.writeInt(bitmap.height);out.writeInt(bitmap.width)
        out.writeShort(8);out.writeShort(3)
        out.writeInt(0);out.writeInt(0);out.writeInt(0)
        out.writeShort(0) // raw composite data
        val row=IntArray(bitmap.width)
        for(channel in 0..2) for(y in 0 until bitmap.height){
            bitmap.getPixels(row,0,bitmap.width,0,y,bitmap.width,1)
            for(color in row) out.writeByte(when(channel){0->android.graphics.Color.red(color);1->android.graphics.Color.green(color);else->android.graphics.Color.blue(color)})
        }
        out.flush()
    }

    fun writeTiff(bitmap:Bitmap,output:OutputStream,dpi:Int=300) {
        val entries=13
        val ifdOffset=8
        val ifdSize=2+entries*12+4
        val bitsOffset=ifdOffset+ifdSize
        val xResOffset=bitsOffset+6
        val yResOffset=xResOffset+8
        val pixelOffset=yResOffset+8
        val bytesPerPixel=3
        val pixelBytes=bitmap.width.toLong()*bitmap.height*bytesPerPixel
        require(pixelBytes<=Int.MAX_VALUE){"TIFF output is too large for this device."}
        val out=LittleEndianWriter(output)
        out.ascii("II");out.u16(42);out.u32(ifdOffset.toLong())
        out.u16(entries)
        fun entry(tag:Int,type:Int,count:Long,value:Long){out.u16(tag);out.u16(type);out.u32(count);out.u32(value)}
        entry(256,4,1,bitmap.width.toLong())
        entry(257,4,1,bitmap.height.toLong())
        entry(258,3,3,bitsOffset.toLong())
        entry(259,3,1,1)
        entry(262,3,1,2)
        entry(273,4,1,pixelOffset.toLong())
        entry(277,3,1,3)
        entry(278,4,1,bitmap.height.toLong())
        entry(279,4,1,pixelBytes)
        entry(282,5,1,xResOffset.toLong())
        entry(283,5,1,yResOffset.toLong())
        entry(284,3,1,1)
        entry(296,3,1,2)
        out.u32(0)
        repeat(3){out.u16(8)}
        out.u32(dpi.toLong());out.u32(1)
        out.u32(dpi.toLong());out.u32(1)
        val row=IntArray(bitmap.width)
        for(y in 0 until bitmap.height){
            bitmap.getPixels(row,0,bitmap.width,0,y,bitmap.width,1)
            for(c in row){out.u8(android.graphics.Color.red(c));out.u8(android.graphics.Color.green(c));out.u8(android.graphics.Color.blue(c))}
        }
        out.flush()
    }

    fun writeTiffCmyk(bitmap:Bitmap,output:OutputStream,dpi:Int=300) {
        val entries=14
        val ifdOffset=8
        val ifdSize=2+entries*12+4
        val bitsOffset=ifdOffset+ifdSize
        val xResOffset=bitsOffset+8
        val yResOffset=xResOffset+8
        val pixelOffset=yResOffset+8
        val byteCount=bitmap.width.toLong()*bitmap.height*4L
        require(byteCount<=Int.MAX_VALUE){"CMYK TIFF is too large for this device."}
        val out=LittleEndianWriter(output)
        out.ascii("II");out.u16(42);out.u32(ifdOffset.toLong())
        out.u16(entries)
        fun entry(tag:Int,type:Int,count:Long,value:Long){out.u16(tag);out.u16(type);out.u32(count);out.u32(value)}
        entry(256,4,1,bitmap.width.toLong())
        entry(257,4,1,bitmap.height.toLong())
        entry(258,3,4,bitsOffset.toLong())
        entry(259,3,1,1)
        entry(262,3,1,5)
        entry(273,4,1,pixelOffset.toLong())
        entry(277,3,1,4)
        entry(278,4,1,bitmap.height.toLong())
        entry(279,4,1,byteCount)
        entry(282,5,1,xResOffset.toLong())
        entry(283,5,1,yResOffset.toLong())
        entry(284,3,1,1)
        entry(296,3,1,2)
        entry(332,3,1,1)
        entry(334,3,1,4)
        out.u32(0)
        repeat(4){out.u16(8)}
        out.u32(dpi.toLong());out.u32(1)
        out.u32(dpi.toLong());out.u32(1)
        val row=IntArray(bitmap.width)
        for(y in 0 until bitmap.height){
            bitmap.getPixels(row,0,bitmap.width,0,y,bitmap.width,1)
            for(color in row){
                val cmyk=ColorManagement.toCmyk(color)
                out.u8((cmyk.c*255).toInt());out.u8((cmyk.m*255).toInt())
                out.u8((cmyk.y*255).toInt());out.u8((cmyk.k*255).toInt())
            }
        }
        out.flush()
    }

    fun writeTiffFloat16(bitmap:Bitmap,output:OutputStream,dpi:Int=300) {
        require(bitmap.config==Bitmap.Config.RGBA_F16){"A 16-bit float bitmap is required."}
        val entries=15
        val ifdOffset=8
        val ifdSize=2+entries*12+4
        val bitsOffset=ifdOffset+ifdSize
        val xResOffset=bitsOffset+8
        val yResOffset=xResOffset+8
        val sampleFormatOffset=yResOffset+8
        val pixelOffset=sampleFormatOffset+8
        val byteCount=bitmap.width.toLong()*bitmap.height*8L
        require(byteCount<=Int.MAX_VALUE){"16-bit TIFF is too large for this device."}
        val out=LittleEndianWriter(output)
        out.ascii("II");out.u16(42);out.u32(ifdOffset.toLong())
        out.u16(entries)
        fun entry(tag:Int,type:Int,count:Long,value:Long){out.u16(tag);out.u16(type);out.u32(count);out.u32(value)}
        entry(256,4,1,bitmap.width.toLong())
        entry(257,4,1,bitmap.height.toLong())
        entry(258,3,4,bitsOffset.toLong())
        entry(259,3,1,1)
        entry(262,3,1,2)
        entry(273,4,1,pixelOffset.toLong())
        entry(277,3,1,4)
        entry(278,4,1,bitmap.height.toLong())
        entry(279,4,1,byteCount)
        entry(282,5,1,xResOffset.toLong())
        entry(283,5,1,yResOffset.toLong())
        entry(284,3,1,1)
        entry(296,3,1,2)
        entry(338,3,1,1)
        entry(339,3,4,sampleFormatOffset.toLong())
        out.u32(0)
        repeat(4){out.u16(16)}
        out.u32(dpi.toLong());out.u32(1)
        out.u32(dpi.toLong());out.u32(1)
        repeat(4){out.u16(3)}
        val raw=java.nio.ByteBuffer.allocate(byteCount.toInt()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bitmap.copyPixelsToBuffer(raw)
        out.bytes(raw.array())
        out.flush()
    }

    private class LittleEndianWriter(output:OutputStream){
        private val out=output.buffered()
        fun ascii(s:String){out.write(s.toByteArray(Charsets.US_ASCII))}
        fun bytes(data:ByteArray){out.write(data)}
        fun u8(v:Int){out.write(v and 255)}
        fun u16(v:Int){u8(v);u8(v ushr 8)}
        fun u32(v:Long){u16((v and 65535).toInt());u16(((v ushr 16) and 65535).toInt())}
        fun flush(){out.flush()}
    }
}
