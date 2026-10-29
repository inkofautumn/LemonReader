package cn.jiangcheng.lemon.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Keep short model names compact; reserve room for both actions before truncating long names. */
class ComposerActionRow(context:Context,private val model:TextView,private val newChat:View,send:View):LinearLayout(context) {
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    init {
        orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
        addView(model,LayoutParams(LayoutParams.WRAP_CONTENT,dp(44)))
        addView(newChat,LayoutParams(LayoutParams.WRAP_CONTENT,dp(44)).apply{marginStart=dp(8)})
        addView(View(context),LayoutParams(0,1,1f))
        addView(send,LayoutParams(dp(44),dp(44)).apply{marginStart=dp(8)})
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        if(MeasureSpec.getMode(widthMeasureSpec)!=MeasureSpec.UNSPECIFIED){
            newChat.measure(MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED),MeasureSpec.makeMeasureSpec(dp(44),MeasureSpec.EXACTLY))
            model.maxWidth=(MeasureSpec.getSize(widthMeasureSpec)-paddingLeft-paddingRight-newChat.measuredWidth-dp(60)).coerceAtLeast(0)
        }
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
}
