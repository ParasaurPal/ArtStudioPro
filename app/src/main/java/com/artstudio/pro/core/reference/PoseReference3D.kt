package com.artstudio.pro.core.reference

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

data class Vec3(val x:Float,val y:Float,val z:Float)
enum class PosePreset { NEUTRAL, WALK, RUN, SITTING, REACHING, CROUCHING }

class PoseReferenceModel {
    var yaw=0f
    var pitch=0f
    var zoom=1f
    var preset=PosePreset.NEUTRAL
    private val jointOffsets=mutableMapOf<String,Vec3>()
    val jointNames=listOf("head","neck","chest","spine","pelvis","leftShoulder","leftElbow","leftHand","rightShoulder","rightElbow","rightHand","leftHip","leftKnee","leftFoot","rightHip","rightKnee","rightFoot")
    fun setJointOffset(name:String,dx:Float,dy:Float,dz:Float=0f){if(name in jointNames)jointOffsets[name]=Vec3(dx,dy,dz)}
    fun clearJointOffsets(){jointOffsets.clear()}
    private val joints=linkedMapOf<String,Vec3>()
    private val bones=listOf(
        "pelvis" to "spine","spine" to "chest","chest" to "neck","neck" to "head",
        "chest" to "leftShoulder","leftShoulder" to "leftElbow","leftElbow" to "leftHand",
        "chest" to "rightShoulder","rightShoulder" to "rightElbow","rightElbow" to "rightHand",
        "pelvis" to "leftHip","leftHip" to "leftKnee","leftKnee" to "leftFoot",
        "pelvis" to "rightHip","rightHip" to "rightKnee","rightKnee" to "rightFoot"
    )
    fun pose():Map<String,Vec3> {
        val p=when(preset){
            PosePreset.NEUTRAL -> listOf(0f,0f,0f,0f,0f,0f)
            PosePreset.WALK -> listOf(-.12f,-.22f,.3f,-.2f,.35f,-.3f)
            PosePreset.RUN -> listOf(-.28f,-.35f,.65f,-.45f,.75f,-.7f)
            PosePreset.SITTING -> listOf(.35f,.15f,-.85f,.7f,-.85f,.7f)
            PosePreset.REACHING -> listOf(-.25f,-.35f,-.1f,-.1f,.1f,-.1f)
            PosePreset.CROUCHING -> listOf(.45f,.25f,-.7f,.7f,-.7f,.7f)
        }
        val (la,ra,ll,rl,lk,rk)=p
        val result=linkedMapOf(
            "pelvis" to Vec3(0f,0f,0f),"spine" to Vec3(0f,-.65f,0f),"chest" to Vec3(0f,-1.15f,0f),
            "neck" to Vec3(0f,-1.4f,0f),"head" to Vec3(0f,-1.78f,0f),
            "leftShoulder" to Vec3(-.38f,-1.12f,0f),"leftElbow" to Vec3(-.65f+sin(la)*.35f,-.72f,0f),
            "leftHand" to Vec3(-.7f+sin(la)*.65f,-.35f-cos(la)*.3f,0f),
            "rightShoulder" to Vec3(.38f,-1.12f,0f),"rightElbow" to Vec3(.65f+sin(ra)*.35f,-.72f,0f),
            "rightHand" to Vec3(.7f+sin(ra)*.65f,-.35f-cos(ra)*.3f,0f),
            "leftHip" to Vec3(-.22f,.05f,0f),"leftKnee" to Vec3(-.28f+sin(ll)*.35f,.65f,0f),
            "leftFoot" to Vec3(-.3f+sin(ll)*.55f,1.25f+cos(lk)*.2f,0f),
            "rightHip" to Vec3(.22f,.05f,0f),"rightKnee" to Vec3(.28f+sin(rl)*.35f,.65f,0f),
            "rightFoot" to Vec3(.3f+sin(rl)*.55f,1.25f+cos(rk)*.2f,0f)
        )
        jointOffsets.forEach{(name,offset)->result[name]?.let{v->result[name]=Vec3(v.x+offset.x,v.y+offset.y,v.z+offset.z)}}
        return result
    }
    fun draw(canvas:Canvas) {
        val pts=pose()
        val cx=canvas.width/2f;val cy=canvas.height/2f
        val scale=minOf(canvas.width,canvas.height)*.28f*zoom.coerceIn(.35f,3f)
        fun project(v:Vec3):Pair<Float,Float>{
            val x=v.x*cos(yaw)-v.z*sin(yaw);val z=v.x*sin(yaw)+v.z*cos(yaw)
            val y=v.y*cos(pitch)-z*sin(pitch);val depth=v.y*sin(pitch)+z*cos(pitch)
            val perspective=1f/(1f+depth*.12f)
            return Pair(cx+x*scale*perspective,cy+y*scale*perspective)
        }
        val bonePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(190,178,215);strokeWidth=scale*.075f;strokeCap=Paint.Cap.ROUND}
        val jointPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(240,207,150);style=Paint.Style.FILL}
        bones.forEach{(a,b)->val p1=project(pts.getValue(a));val p2=project(pts.getValue(b));canvas.drawLine(p1.first,p1.second,p2.first,p2.second,bonePaint)}
        pts.values.forEach{v->val p=project(v);canvas.drawCircle(p.first,p.second,scale*.055f,jointPaint)}
        val head=project(pts.getValue("head"));canvas.drawCircle(head.first,head.second,scale*.13f,jointPaint)
    }
}

class PoseReferenceView(context:Context,val model:PoseReferenceModel=PoseReferenceModel()):View(context){
    private var lastX=0f;private var lastY=0f
    override fun onDraw(canvas:Canvas){super.onDraw(canvas);canvas.drawColor(Color.rgb(28,25,34));model.draw(canvas)}
    override fun onTouchEvent(event:MotionEvent):Boolean{
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{lastX=event.x;lastY=event.y;return true}
            MotionEvent.ACTION_MOVE->{model.yaw+=(event.x-lastX)*.01f;model.pitch=((model.pitch+(event.y-lastY)*.01f).coerceIn(-1.2f,1.2f));lastX=event.x;lastY=event.y;invalidate();return true}
            MotionEvent.ACTION_UP->{performClick();return true}
        };return true
    }
    override fun performClick():Boolean{super.performClick();return true}
}
