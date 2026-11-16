package com.artstudio.pro.core.export

import android.graphics.Bitmap
import android.graphics.Color
import java.io.OutputStream
import java.io.ByteArrayOutputStream

object GifAnimationWriter {
    fun write(frames:List<Bitmap>,durationsMs:List<Int>,output:OutputStream){
        require(frames.isNotEmpty()){"Animation has no frames."}
        val width=frames.first().width;val height=frames.first().height
        require(width<=65535&&height<=65535&&frames.all{it.width==width&&it.height==height}){"GIF frames must share dimensions within the GIF size limit."}
        val out=output.buffered()
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        u16(out,width);u16(out,height);out.write(0xF7);out.write(0);out.write(0)
        writePalette(out)
        out.write(byteArrayOf(0x21,0xFF.toByte(),0x0B,0x4E,0x45,0x54,0x53,0x43,0x41,0x50,0x45,0x32,0x2E,0x30,0x03,0x01,0x00,0x00,0x00))
        frames.forEachIndexed{index,bitmap->
            out.write(0x21);out.write(0xF9);out.write(4);out.write(0)
            u16(out,(durationsMs.getOrElse(index){100}/10).coerceIn(2,500))
            out.write(0);out.write(0)
            out.write(0x2C);u16(out,0);u16(out,0);u16(out,width);u16(out,height);out.write(0)
            out.write(8)
            val indices=ByteArray(width*height);val row=IntArray(width)
            for(y in 0 until height){bitmap.getPixels(row,0,width,0,y,width,1);for(x in 0 until width){val c=row[x];indices[y*width+x]=(((Color.red(c) shr 5) shl 5) or ((Color.green(c) shr 5) shl 2) or (Color.blue(c) shr 6)).toByte()}}
            val compressed=lzw(indices)
            var offset=0
            while(offset<compressed.size){val n=minOf(255,compressed.size-offset);out.write(n);out.write(compressed,offset,n);offset+=n}
            out.write(0)
        }
        out.write(0x3B);out.flush()
    }
    private fun writePalette(out:OutputStream){
        for(i in 0..255){
            val r=((i shr 5) and 7)*255/7
            val g=((i shr 2) and 7)*255/7
            val b=(i and 3)*255/3
            out.write(r);out.write(g);out.write(b)
        }
    }
    private fun u16(out:OutputStream,v:Int){out.write(v and 255);out.write((v shr 8) and 255)}
    private fun lzw(input:ByteArray):ByteArray{
        val out=ByteArrayOutputStream()
        var bitBuffer=0;var bitCount=0
        fun emit(code:Int,size:Int){bitBuffer=bitBuffer or (code shl bitCount);bitCount+=size;while(bitCount>=8){out.write(bitBuffer and 255);bitBuffer=bitBuffer ushr 8;bitCount-=8}}
        val clear=256;val end=257
        var codeSize=9;var next=258
        var dictionary=HashMap<Int,Int>(4096)
        emit(clear,codeSize)
        if(input.isNotEmpty()){
            var prefix=input[0].toInt() and 255
            for(i in 1 until input.size){
                val symbol=input[i].toInt() and 255
                val key=(prefix shl 8) or symbol
                val found=dictionary[key]
                if(found!=null){prefix=found}
                else{
                    emit(prefix,codeSize)
                    if(next<4096){
                        dictionary[key]=next++
                        if(next==(1 shl codeSize)&&codeSize<12)codeSize++
                    }else{
                        emit(clear,codeSize);dictionary=HashMap(4096);codeSize=9;next=258
                    }
                    prefix=symbol
                }
            }
            emit(prefix,codeSize)
        }
        emit(end,codeSize)
        if(bitCount>0)out.write(bitBuffer and 255)
        return out.toByteArray()
    }
}
