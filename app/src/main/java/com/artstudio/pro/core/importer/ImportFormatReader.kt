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
import com.artstudio.pro.core.color.Cmyk
import com.artstudio.pro.core.color.ColorManagement
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ImportFormatReader {
    fun decode(context:Context,uri:Uri):Bitmap {
        val mime=context.contentResolver.getType(uri)?.lowercase().orEmpty()
        val name=uri.lastPathSegment?.lowercase().orEmpty()
        val bytes=if(mime.contains("tiff")||name.endsWith(".psd")||name.endsWith(".tif")||name.endsWith(".tiff")||mime.contains("svg")||name.endsWith(".svg")||mime=="application/octet-stream"||(mime.startsWith("image/")&&!mime.contains("png")&&!mime.contains("jpeg")&&!mime.contains("webp"))) {
            context.contentResolver.openInputStream(uri)?.use{it.readBytes()}?:error("Unable to read file.")
        } else null
        val isPsd = name.endsWith(".psd") || bytes?.let { it.size>=4 && String(it,0,4,Charsets.US_ASCII)=="8BPS" } == true
        val isTiff = mime.contains("tiff") || name.endsWith(".tif") || name.endsWith(".tiff") || bytes?.let { it.size>=4 && it[0]=='I'.code.toByte() && it[1]=='I'.code.toByte() && it[2]==42.toByte() && it[3]==0.toByte() } == true
        val isSvg = mime.contains("svg") || name.endsWith(".svg") || bytes?.let { String(it.take(64).toByteArray(),Charsets.UTF_8).trimStart().startsWith("<") } == true
        val isPdf = mime.contains("pdf") || name.endsWith(".pdf") || bytes?.let { it.size>=4 && String(it,0,4,Charsets.US_ASCII)=="%PDF" } == true
        return when {
            isPdf -> decodePdf(context,uri)
            isSvg -> decodeSvg(bytes ?: error("SVG data is missing."))
            isPsd -> decodePsd(bytes ?: error("PSD data is missing."))
            isTiff -> decodeTiff(bytes ?: error("TIFF data is missing."))
            else -> context.contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it)} ?: error("Unsupported or invalid image.")
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
        val photometric=tags[262]?.toInt()?:error("TIFF color model missing.")
        val samples=tags[277]?.toInt()?:error("TIFF channel count missing.")
        require(tags[259]==1L&&((photometric==2&&samples==3)||(photometric==5&&samples==4))){"Unsupported TIFF compression or color model."}
        val bitsOffset=tags[258]?.toInt()?:error("TIFF bit depth missing.")
        require(bitsOffset>=0&&bitsOffset+samples*2<=data.size){"Invalid TIFF bit-depth data."}
        repeat(samples){channel->require((b.getShort(bitsOffset+channel*2).toInt() and 65535)==8){"Only 8-bit RGB/CMYK TIFF is supported."}}
        val offset=tags[273]?.toInt()?:error("TIFF pixel offset missing.")
        val byteCount=tags[279]?.toLong()?:error("TIFF pixel data missing.")
        require(offset>=0&&byteCount>=width.toLong()*height*samples&&offset.toLong()+byteCount<=data.size){"TIFF pixel data is incomplete."}
        val pixels=IntArray(width*height);var index=offset
        for(i in pixels.indices){
            val a=data[index++].toInt() and 255;val g=data[index++].toInt() and 255
            val third=data[index++].toInt() and 255
            if(photometric==2) pixels[i]=Color.rgb(a,g,third)
            else {
                val k=data[index++].toInt() and 255
                pixels[i]=ColorManagement.fromCmyk(Cmyk(a/255f,g/255f,third/255f,k/255f))
            }
        }
        return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888)
    }

    private fun decodeSvg(data:ByteArray):Bitmap {
        val parser=Xml.newPullParser();parser.setInput(ByteArrayInputStream(data),"UTF-8")
        var width=1024f;var height=1024f
        val elements=mutableListOf<(Canvas)->Unit>()
        val embeddedBitmaps=mutableListOf<Bitmap>()
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
                    val embedded=encoded?.let{runCatching{
                        val decoded=android.util.Base64.decode(it,android.util.Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(decoded,0,decoded.size)
                    }.getOrNull()}
                    if(embedded!=null) {
                        embeddedBitmaps += embedded
                        elements += { canvas ->
                            canvas.drawBitmap(embedded,null,RectF(x,y,x+w,y+h),Paint(Paint.FILTER_BITMAP_FLAG))
                        }
                    }
                }
                else if(tag=="path") {
                    val d=parser.getAttributeValue(null,"d").orEmpty()
                    val fillValue=parser.getAttributeValue(null,"fill") ?: "black"
                    val strokeValue=parser.getAttributeValue(null,"stroke") ?: "none"
                    val fill=runCatching{Color.parseColor(fillValue)}.getOrDefault(Color.BLACK)
                    val stroke=runCatching{Color.parseColor(strokeValue)}.getOrDefault(Color.TRANSPARENT)
                    val strokeWidth=attr("stroke-width",1f)
                    val opacity=attr("opacity",1f).coerceIn(0f,1f)
                    val tokens=Regex("[MLCZ]|[-+]?(?:[0-9]*\\.)?[0-9]+(?:[eE][-+]?[0-9]+)?").findAll(d).map{it.value}.toList()
                    val vectorPath=Path()
                    var cursor=0
                    var command='M'
                    fun number():Float=tokens.getOrNull(cursor++)?.toFloatOrNull()?:0f
                    while(cursor<tokens.size){
                        val token=tokens[cursor]
                        if(token.length==1&&token[0].isLetter()){command=token[0];cursor++;if(command=='Z'||command=='z'){vectorPath.close();continue}}
                        when(command){
                            'M','m'->{val px=number();val py=number();vectorPath.moveTo(px,py);command=if(command=='m')'l' else 'L'}
                            'L','l'->{vectorPath.lineTo(number(),number())}
                            'C','c'->{val x1=number();val y1=number();val x2=number();val y2=number();val x=number();val y=number();vectorPath.cubicTo(x1,y1,x2,y2,x,y)}
                            else->{cursor++}
                        }
                    }
                    elements += { canvas ->
                        if(fillValue!="none"){
                            val fp=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=fill;alpha=(Color.alpha(fill)*opacity).toInt();style=Paint.Style.FILL}
                            canvas.drawPath(vectorPath,fp)
                        }
                        if(stroke!=Color.TRANSPARENT){
                            val sp=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=stroke;alpha=(Color.alpha(stroke)*opacity).toInt();style=Paint.Style.STROKE;this.strokeWidth=strokeWidth;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
                            canvas.drawPath(vectorPath,sp)
                        }
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
        embeddedBitmaps.forEach { if(!it.isRecycled) it.recycle() }
        return bitmap
    }
}
