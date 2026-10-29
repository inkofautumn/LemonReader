package cn.jiangcheng.lemon.reader.pagecurl

import android.graphics.Path
import android.graphics.PointF

internal inline fun computeTime(tag:String,message:String,block:()->Unit){ block() }

internal fun Path.buildPath(
    edge1:FloatArray,edge2:FloatArray,edge3:FloatArray,edge4:FloatArray
){
    reset();moveTo(edge1[0],edge1[1])
    for(i in edge1.indices step 2)lineTo(edge1[i],edge1[i+1])
    for(i in edge4.indices step 2)lineTo(edge4[i],edge4[i+1])
    for(i in edge2.size-2 downTo 0 step 2)lineTo(edge2[i],edge2[i+1])
    for(i in edge3.size-2 downTo 0 step 2)lineTo(edge3[i],edge3[i+1])
    close()
}

internal fun perpendicularFoot(
    ax:Float,ay:Float,bx:Float,by:Float,cx:Float,cy:Float,result:PointF
){
    val vx=cx-bx;val vy=cy-by;val denominator=vx*vx+vy*vy
    if(denominator==0f){result.set(bx,by);return}
    val t=((ax-bx)*vx+(ay-by)*vy)/denominator
    result.set(bx+t*vx,by+t*vy)
}

internal fun computeCrossPoint(
    p1:PointF,p2:PointF,p3:PointF,p4:PointF,result:PointF
):Boolean{
    val a1=p2.y-p1.y;val b1=p1.x-p2.x;val c1=a1*p1.x+b1*p1.y
    val a2=p4.y-p3.y;val b2=p3.x-p4.x;val c2=a2*p3.x+b2*p3.y
    val determinant=a1*b2-a2*b1
    if(kotlin.math.abs(determinant)<.0001f)return false
    result.set((b2*c1-b1*c2)/determinant,(a1*c2-a2*c1)/determinant)
    return true
}

internal fun reflectPointAboutLine(
    px:Float,py:Float,ax:Float,ay:Float,bx:Float,by:Float
):Pair<Float,Float>{
    val vx=bx-ax;val vy=by-ay;val denominator=vx*vx+vy*vy
    if(denominator<.0001f)return px to py
    val t=((px-ax)*vx+(py-ay)*vy)/denominator
    val hx=ax+t*vx;val hy=ay+t*vy
    return 2f*hx-px to 2f*hy-py
}
