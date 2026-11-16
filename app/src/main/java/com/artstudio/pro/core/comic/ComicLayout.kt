package com.artstudio.pro.core.comic

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ComicPanel(
    val id:String=UUID.randomUUID().toString(),
    val bounds:List<Float>,
    val cornerRadius:Float=0f,
    val borderWidth:Float=8f,
    val borderColor:Int=Color.BLACK,
    val backgroundColor:Int=Color.WHITE,
    val order:Int=0,
    val bleed:Boolean=false
)

@Serializable
data class ComicBalloon(
    val id:String=UUID.randomUUID().toString(),
    val text:String,
    val x:Float,val y:Float,val width:Float,val height:Float,
    val tailX:Float,val tailY:Float,
    val fontSize:Float=32f,
    val isThought:Boolean=false
)

@Serializable
data class ComicPage(
    val pageNumber:Int,
    val width:Int,
    val height:Int,
    val gutter:Float=32f,
    val panels:List<ComicPanel> = emptyList(),
    val balloons:List<ComicBalloon> = emptyList()
)

object ComicLayoutEngine {
    fun grid(width:Int,height:Int,rows:Int,columns:Int,gutter:Float=32f):ComicPage {
        require(rows in 1..8 && columns in 1..8)
        val cellW=(width-gutter*(columns+1))/columns
        val cellH=(height-gutter*(rows+1))/rows
        val panels=(0 until rows*columns).map{index->
            val row=index/columns;val col=index%columns
            val left=gutter+col*(cellW+gutter);val top=gutter+row*(cellH+gutter)
            ComicPanel(bounds=listOf(left,top,left+cellW,top+cellH),order=index)
        }
        return ComicPage(1,width,height,gutter,panels)
    }
    fun drawGuides(canvas:Canvas,page:ComicPage,showBleed:Boolean=true){
        val border=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=1f;color=Color.GRAY}
        page.panels.sortedBy{it.order}.forEach{panel->
            if(panel.bounds.size<4)return@forEach
            val r=RectF(panel.bounds[0],panel.bounds[1],panel.bounds[2],panel.bounds[3])
            canvas.drawRoundRect(r,panel.cornerRadius,panel.cornerRadius,border.apply{strokeWidth=panel.borderWidth;color=panel.borderColor})
            if(showBleed&&panel.bleed){val bleed=RectF(r).apply{inset(-page.gutter/2,-page.gutter/2)};border.color=Color.RED;border.strokeWidth=1f;canvas.drawRect(bleed,border)}
        }
    }
    fun drawBalloon(canvas:Canvas,balloon:ComicBalloon,color:Int=Color.BLACK){
        val box=RectF(balloon.x,balloon.y,balloon.x+balloon.width,balloon.y+balloon.height)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.FILL;color=Color.WHITE}
        val stroke=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;color=color;strokeWidth=4f}
        if(balloon.isThought){
            val path=Path();path.addOval(box,Path.Direction.CW);canvas.drawPath(path,paint);canvas.drawPath(path,stroke)
            canvas.drawCircle(balloon.tailX,balloon.tailY,8f,paint);canvas.drawCircle(balloon.tailX,balloon.tailY,8f,stroke)
        }else{
            val path=Path().apply{addRoundRect(box,box.height()*.35f,box.height()*.35f,Path.Direction.CW);moveTo(balloon.x+box.width()*.25f,box.bottom-2);lineTo(balloon.tailX,balloon.tailY);lineTo(balloon.x+box.width()*.42f,box.bottom-2);close()}
            canvas.drawPath(path,paint);canvas.drawPath(path,stroke)
        }
        val textPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;textSize=balloon.fontSize;textAlign=Paint.Align.CENTER;typeface=android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.BOLD)}
        val words=balloon.text.split(" ");val lines=mutableListOf<String>();var line=""
        words.forEach{word->val candidate=if(line.isEmpty())word else "$line $word";if(textPaint.measureText(candidate)>box.width()*.82f&&line.isNotEmpty()){lines+=line;line=word}else line=candidate};if(line.isNotEmpty())lines+=line
        val lineHeight=balloon.fontSize*1.15f;val start=box.centerY()-(lines.size-1)*lineHeight/2f
        lines.forEachIndexed{i,s->canvas.drawText(s,box.centerX(),start+i*lineHeight,textPaint)}
    }
}
