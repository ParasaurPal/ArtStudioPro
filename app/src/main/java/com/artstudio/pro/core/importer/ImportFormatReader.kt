package com.artstudio.pro.core.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ImportFormatReader {
    fun decode(context:Context,uri:Uri):Bitmap {
        val mime=context.contentResolver.getType(uri)?.lowercase().orEmpty()
        val name=uri.lastPathSegment?.lowercase().orEmpty()
        val bytes=if(mime.contains("photoshop")||mime.contains("tiff")||name.endsWith(".psd")||name.endsWith(".tif")||name.endsWith(".tiff")||mime.contains("svg")||name.endsWith(".svg")) {
            context.contentResolver.openInputStream(uri)?.use{it.readBytes()}?:error("Unable to read file.")
        } else null
        return when {
            mime.contains("pdf")||name.endsWith(".pdf")->decodePdf(context,uri)
            mime.contains("svg")||name.endsWith(".svg")->decodeSvg(bytes!!)
            mime.contains("photoshop")||name.endsWith(".psd")->decodePsd(bytes!!)
            mime.contains("tiff")||name.endsWith(".tif")||name.endsWith(".tiff")->decodeTiff(bytes!!)
            else->context.contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it)}?:error("Unsupported or invalid image.")
        } ?: error("The selected file could not be decoded.")
    }

    private fun decodePdf(context:Context,uri:Uri):Bitmap {
        val descriptor=context.contentResolver.openFileDescriptor(uri,"r")?:error("Unable to open PDF.")
        descriptor.use { fd ->
            PdfRenderer(fd).use { renderer ->
                require(renderer.pageCount>0){"PDF has no pages."}
                renderer.openPage(0).use { page ->
                    val scale=minOf(1f,4096f/maxOf(page.width,page.height).toFloat())
                    val bitmap=Bitmap.createBitmap((page.width*scale).toInt().coerceAtLeast(1),(page.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bitmap
                }
            }
        }
    }

    private fun decodePsd(data:ByteArray):Bitmap {
        val b=ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        require(data.size>=40&&String(data,0,4,Charsets.US_ASCII)=="8BPS"){"Invalid PSD file."}
        b.position(6);b.position(12)
        val channels=b.short.toInt() and 65535
        val height=b.int;val width=b.int;val depth=b.short.toInt() and 65535;val mode=b.short.toInt() and 65535
        require(width in 1..16000&&height in 1..16000&&width.toLong()*height<=20_000_000L&&channels>=3&&depth==8&&mode==3){"Unsupported PSD dimensions or color mode."}
        b.position(26)
        repeat(3){val len=b.int.toLong() and 0xffffffffL;require(len<=b.remaining());b.position(b.position()+len.toInt())}
        val compression=b.short.toInt() and 65535
        require(compression==0){"Only uncompressed PSD composite data is supported."}
        val count=width*height;require(b.remaining()>=count*3){"PSD pixel data is incomplete."}
        val r=ByteArray(count);val g=ByteArray(count);val bl=ByteArray(count)
        b.get(r);b.get(g);b.get(bl)
        val pixels=IntArray(count){i->Color.rgb(r[i].toInt() and 255,g[i].toInt() and 255,bl[i].toInt() and 255)}
        return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888)
    }

    private fun decodeTiff(data:ByteArray):Bitmap {
        val b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        require(data.size>=8&&data[0]=='I'.code.toByte()&&data[1]=='I'.code.toByte()){"Only little-endian baseline TIFF is supported."}
        require((b.getShort(2).toInt() and 65535)==42){"Invalid TIFF header."}
        val ifd=b.getInt(4)
        require(ifd>=8&&ifd+2<=data.size){"Invalid TIFF directory."}
        val count=b.getShort(ifd).toInt() and 65535
        val tags=mutableMapOf<Int,Long>()
        var pos=ifd+2
        repeat(count){
            val tag=b.getShort(pos).toInt() and 65535;val type=b.getShort(pos+2).toInt() and 65535
            val n=b.getInt(pos+4).toLong() and 0xffffffffL
            val value=if(type==3&&n==1L)(b.getShort(pos+8).toInt() and 65535).toLong() else b.getInt(pos+8).toLong() and 0xffffffffL
            tags[tag]=value;pos+=12
        }
        val width=tags[256]?.toInt()?:error("TIFF width missing.")
        val height=tags[257]?.toInt()?:error("TIFF height missing.")
        require(width in 1..16000&&height in 1..16000&&width.toLong()*height<=20_000_000L){"TIFF dimensions exceed the mobile limit."}
        require(tags[259]==1L&&tags[262]==2L&&tags[277]==3L){"Unsupported TIFF compression or color model."}
        val bitsOffset=tags[258]?.toInt()?:error("TIFF bit depth missing.")
        require(bitsOffset>=0&&bitsOffset+6<=data.size){"Invalid TIFF bit-depth data."}
        require((b.getShort(bitsOffset).toInt() and 65535)==8&&(b.getShort(bitsOffset+2).toInt() and 65535)==8&&(b.getShort(bitsOffset+4).toInt() and 65535)==8){"Only 8-bit RGB TIFF is supported."}
        val offset=tags[273]?.toInt()?:error("TIFF pixel offset missing.")
        val byteCount=tags[279]?.toLong()?:error("TIFF pixel data missing.")
        require(offset>=0&&byteCount>=width.toLong()*height*3&&offset.toLong()+byteCount<=data.size){"TIFF pixel data is incomplete."}
        val pixels=IntArray(width*height);var index=offset
        for(i in pixels.indices){val r=data[index++].toInt() and 255;val g=data[index++].toInt() and 255;val blue=data[index++].toInt() and 255;pixels[i]=Color.rgb(r,g,blue)}
        return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888)
    }

    private fun decodeSvg(data:ByteArray):Bitmap {
        val parser=Xml.newPullParser();parser.setInput(ByteArrayInputStream(data),"UTF-8")
        var width=1024f;var height=1024f
        val elements=mutableListOf<(Canvas)->Unit>()
        var event=parser.eventType
        while(event!=XmlPullParser.END_DOCUMENT){
            if(event==XmlPullParser.START_TAG){
                val tag=parser.name
                fun attr(name:String,default:Float=0f)=parser.getAttributeValue(null,name)?.replace("px","")?.toFloatOrNull()?:default
                fun color(name:String,default:Int)=parser.getAttributeValue(null,name)?.let{runCatching{Color.parseColor(it)}.getOrDefault(default)}?:default
                if(tag=="svg"){width=attr("width",1024f).coerceIn(1f,4096f);height=attr("height",1024f).coerceIn(1f,4096f)}
                else if(tag=="image") {
                    val x=attr("x");val y=attr("y");val w=attr("width",width);val h=attr("height",height)
                    val href=parser.getAttributeValue("http://www.w3.org/1999/xlink","href")
                        ?: parser.getAttributeValue(null,"href")
                    val encoded=href?.substringAfter("base64,", "")?.takeIf{it.isNotBlank()}
                    val embedded=encoded?.let{runCatching{BitmapFactory.decodeByteArray(android.util.Base64.decode(it,android.util.Base64.DEFAULT),0,android.util.Base64.decode(it,android.util.Base64.DEFAULT).size)}.getOrNull()}
                    if(embedded!=null) elements += { canvas ->
                        canvas.drawBitmap(embedded,null,RectF(x,y,x+w,y+h),Paint(Paint.FILTER_BITMAP_FLAG))
                    }
                }
                else if(tag=="rect"||tag=="circle"||tag=="ellipse"||tag=="line"){
                    val x=attr("x");val y=attr("y");val w=attr("width");val h=attr("height")
                    val cx=attr("cx");val cy=attr("cy");val rx=attr("rx",attr("r"));val ry=attr("ry",attr("r"))
                    val x2=attr("x2");val y2=attr("y2");val fill=color("fill",Color.BLACK);val stroke=color("stroke",Color.TRANSPARENT)
                    val sw=attr("stroke-width",1f)
                    elements+= { canvas ->
                        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=fill;style=Paint.Style.FILL}
                        when(tag){
                            "rect"->canvas.drawRect(x,y,x+w,y+h,p)
                            "circle"->canvas.drawCircle(cx,cy,rx,p)
                            "ellipse"->canvas.drawOval(RectF(cx-rx,cy-ry,cx+rx,cy+ry),p)
                            "line"->{p.color=stroke;p.style=Paint.Style.STROKE;p.strokeWidth=sw;canvas.drawLine(x,y,x2,y2,p)}
                        }
                        if(tag=="rect"&&stroke!=Color.TRANSPARENT){p.color=stroke;p.style=Paint.Style.STROKE;p.strokeWidth=sw;canvas.drawRect(x,y,x+w,y+h,p)}
                    }
                }
            }
            event=parser.next()
        }
        val bitmap=Bitmap.createBitmap(width.toInt().coerceAtLeast(1),height.toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);elements.forEach{it(canvas)}
        return bitmap
    }
}
