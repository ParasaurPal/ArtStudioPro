package com.artstudio.pro.core.vector

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlinx.serialization.Serializable

@Serializable
data class VectorNode(val x:Float,val y:Float,val controlInX:Float=x,val controlInY:Float=y,val controlOutX:Float=x,val controlOutY:Float=y)

@Serializable
data class VectorPath(
    val id:String,
    val nodes:List<VectorNode>,
    val closed:Boolean=false,
    val fillColor:Int?=null,
    val strokeColor:Int=0xff17151b.toInt(),
    val strokeWidth:Float=4f,
    val opacity:Float=1f,
    val name:String="Vector path"
) {
    fun bounds():RectF {
        if(nodes.isEmpty())return RectF()
        val xs=nodes.flatMap{listOf(it.x,it.controlInX,it.controlOutX)}
        val ys=nodes.flatMap{listOf(it.y,it.controlInY,it.controlOutY)}
        return RectF(xs.min(),ys.min(),xs.max(),ys.max())
    }
}

object VectorRenderer {
    fun toPath(vector:VectorPath):Path {
        val path=Path()
        val nodes=vector.nodes
        if(nodes.isEmpty())return path
        path.moveTo(nodes.first().x,nodes.first().y)
        for(i in 1 until nodes.size){
            val prev=nodes[i-1];val next=nodes[i]
            val hasCurve=prev.controlOutX!=prev.x || prev.controlOutY!=prev.y || next.controlInX!=next.x || next.controlInY!=next.y
            if(hasCurve)path.cubicTo(prev.controlOutX,prev.controlOutY,next.controlInX,next.controlInY,next.x,next.y)
            else path.lineTo(next.x,next.y)
        }
        if(vector.closed)path.close()
        return path
    }
    fun draw(canvas:Canvas,paths:List<VectorPath>) {
        paths.forEach { vector ->
            val path=toPath(vector)
            vector.fillColor?.let { fill ->
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=fill;alpha=(vector.opacity.coerceIn(0f,1f)*255).toInt();style=Paint.Style.FILL }
                canvas.drawPath(path,paint)
            }
            val stroke=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color=vector.strokeColor;alpha=(vector.opacity.coerceIn(0f,1f)*255).toInt()
                style=Paint.Style.STROKE;strokeWidth=vector.strokeWidth.coerceAtLeast(.1f)
                strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND
            }
            canvas.drawPath(path,stroke)
        }
    }
    fun toSvg(paths:List<VectorPath>,width:Int,height:Int):String {
        val body=paths.joinToString("\n") { v ->
            val p=toPath(v)
            val points=v.nodes
            if(points.isEmpty())"" else {
                val commands=buildString {
                    append("M ${points.first().x} ${points.first().y} ")
                    for(i in 1 until points.size){
                        val a=points[i-1];val b=points[i]
                        if(a.controlOutX!=a.x || a.controlOutY!=a.y || b.controlInX!=b.x || b.controlInY!=b.y)
                            append("C ${a.controlOutX} ${a.controlOutY}, ${b.controlInX} ${b.controlInY}, ${b.x} ${b.y} ")
                        else append("L ${b.x} ${b.y} ")
                    }
                    if(v.closed)append("Z")
                }
                val fill=v.fillColor?.let { "#%06X".format(it and 0xffffff) } ?: "none"
                val stroke="#%06X".format(v.strokeColor and 0xffffff)
                "<path d=\"$commands\" fill=\"$fill\" stroke=\"$stroke\" stroke-width=\"${v.strokeWidth}\" opacity=\"${v.opacity}\"/>"
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$width\" height=\"$height\" viewBox=\"0 0 $width $height\">\n$body\n</svg>"
    }
}
