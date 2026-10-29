package cn.jiangcheng.lemon.ui

import android.graphics.*
import android.graphics.drawable.Drawable

/** Small native cover artwork: deterministic palettes, book spine and quiet botanical/landscape motifs. */
class CoverArt(seed:String,private val custom:Int?=null):Drawable(){
    private val variant=Math.floorMod(seed.hashCode(),6)
    val paper=custom?:intArrayOf(0xffd8e6e2.toInt(),0xffe7ddc5.toInt(),0xffdce1eb.toInt(),0xffe8d8d5.toInt(),0xffdfe5d2.toInt(),0xffe6dded.toInt())[variant]
    val ink=intArrayOf(0xff355651.toInt(),0xff685334.toInt(),0xff435674.toInt(),0xff784f4b.toInt(),0xff4b6242.toInt(),0xff645176.toInt())[variant]
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas:Canvas){
        val w=bounds.width().toFloat();val h=bounds.height().toFloat();canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
        paint.style=Paint.Style.FILL;paint.color=paper;canvas.drawRoundRect(0f,0f,w,h,w*.045f,w*.045f,paint)
        paint.color=Color.argb(22,30,40,30);canvas.drawRect(w*.035f,0f,w*.07f,h,paint)
        paint.color=Color.argb(90,255,255,255);canvas.drawRect(w*.075f,0f,w*.083f,h,paint)
        paint.color=ink;paint.alpha=45;paint.strokeWidth=w*.008f;paint.style=Paint.Style.STROKE
        when(variant%3){
            0->{val path=Path();path.moveTo(w*.62f,h*.96f);path.quadTo(w*.84f,h*.83f,w*.81f,h*.66f);canvas.drawPath(path,paint)
                paint.style=Paint.Style.FILL;for(i in 0..5){val x=w*(.68f+i*.025f);val y=h*(.91f-i*.037f);canvas.save();canvas.rotate(if(i%2==0)-40f else 35f,x,y);canvas.drawOval(x-w*.05f,y-h*.018f,x+w*.05f,y+h*.018f,paint);canvas.restore()}}
            1->{for(i in 0..3){val path=Path();path.moveTo(w*.18f,h*(.94f+i*.014f));path.quadTo(w*.5f,h*(.65f+i*.045f),w*.95f,h*(.93f+i*.014f));canvas.drawPath(path,paint)};paint.style=Paint.Style.FILL;canvas.drawCircle(w*.78f,h*.73f,w*.045f,paint)}
            else->{for(i in 0..3)canvas.drawOval(w*(.45f-i*.08f),h*(.76f+i*.025f),w*(1.1f-i*.08f),h*(.85f+i*.025f),paint);paint.style=Paint.Style.FILL;canvas.drawCircle(w*.79f,h*.7f,w*.045f,paint)}
        }
        paint.alpha=255;paint.style=Paint.Style.FILL;canvas.restore()
    }
    override fun setAlpha(alpha:Int){}
    override fun setColorFilter(filter:ColorFilter?){}
    @Deprecated("Drawable API") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
