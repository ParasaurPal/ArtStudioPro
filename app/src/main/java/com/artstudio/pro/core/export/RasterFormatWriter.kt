package com.artstudio.pro.core.export

import android.graphics.Bitmap
import com.artstudio.pro.core.color.ColorManagement
import java.io.DataOutputStream
import java.io.OutputStream

/** Minimal standards-compliant flattened RGB PSD and baseline TIFF writers. */
object RasterFormatWriter {
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
