package cn.jiangcheng.lemon.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.TextView

/** The same selection mark for every text-tab row, including nested filters. */
object ReaderControls {
    fun mark(tab:TextView,selected:Boolean,accent:Int,ink:Int) {
        val density=tab.resources.displayMetrics.density
        tab.isSelected=selected
        tab.setTextColor(if(selected)accent else ink)
        tab.typeface=if(selected)Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        tab.background=object:Drawable(){
            private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=accent}
            override fun draw(canvas:Canvas){if(selected){val x=bounds.exactCenterX();canvas.drawRoundRect(x-12*density,bounds.bottom-3*density,x+12*density,bounds.bottom.toFloat(),2*density,2*density,paint)}}
            override fun setAlpha(alpha:Int){paint.alpha=alpha}
            override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter}
            @Deprecated("Android API") override fun getOpacity()=PixelFormat.TRANSLUCENT
        }
    }
    fun tab(context:Context,title:String,selected:Boolean,accent:Int,ink:Int,action:()->Unit)=TextView(context).apply{
        val d=resources.displayMetrics.density
        text=title;contentDescription=title;textSize=14f;gravity=Gravity.CENTER
        minHeight=(48*d).toInt();setPadding((6*d).toInt(),0,(6*d).toInt(),(3*d).toInt())
        isClickable=true;isFocusable=true;setOnClickListener{action()};mark(this,selected,accent,ink)
    }
    fun ripple(color:Int,accent:Int,radius:Float)=RippleDrawable(ColorStateList.valueOf((accent and 0x00ffffff) or 0x22000000),GradientDrawable().apply{setColor(color);cornerRadius=radius},null)
}
