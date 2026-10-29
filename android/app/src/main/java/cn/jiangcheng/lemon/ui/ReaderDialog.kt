package cn.jiangcheng.lemon.ui

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.*
import android.widget.*
import cn.jiangcheng.lemon.MainActivity

/** App-owned prompt, menu and form surfaces. Business callbacks retain Dialog semantics. */
class ReaderDialog private constructor(context:Context, private val spec:Builder):Dialog(context) {
    companion object { const val BUTTON_POSITIVE=-1; const val BUTTON_NEGATIVE=-2 }
    private val buttons=mutableMapOf<Int,Button>()
    private fun dp(n:Int)=(n*context.resources.displayMetrics.density).toInt()
    private val app=context as? MainActivity
    private val reading=app?.reader!=null
    private val prefs=context.getSharedPreferences("reading:${app?.profileId.orEmpty()}",0)
    private val night=app?.reader?.isNight ?: Appearance.dark(context)
    private val paper=if(night)0xff292b26.toInt() else if(reading)prefs.getInt("paper",0xffe9e4cd.toInt()) else 0xfffaf9f4.toInt()
    private val ink=if(night)0xffe9e6dc.toInt()else 0xff2d2c26.toInt()
    private val muted=if(night)0xffb5b3a8.toInt()else 0xff77766c.toInt()
    private val accent=if(night)0xffc9bc94.toInt()else 0xff69593c.toInt()
    private val field=if(night)0xff35382f.toInt()else if(reading)0xfff4f0e1.toInt()else 0xfff0eee5.toInt()
    private fun shape(color:Int,radius:Int=12)=GradientDrawable().apply{setColor(color);cornerRadius=dp(radius).toFloat()}
    private fun ripple(color:Int)=RippleDrawable(ColorStateList.valueOf(if(night)0x22ffffff else 0x2269593c),shape(color),null)
    fun getButton(which:Int):Button=buttons.getValue(which)
    fun restyleSpinner(spinner:Spinner){style(spinner)}
    private fun label(value:CharSequence,size:Float=16f)=TextView(context).apply{text=value;textSize=size;setTextColor(ink);setLineSpacing(dp(3).toFloat(),1f)}
    private fun style(view:View){
        when(view){
            is EditText->{view.backgroundTintList=null;view.background=shape(field,10);view.setTextColor(ink);view.setHintTextColor(muted);view.textSize=16f;view.setPadding(dp(14),dp(14),dp(14),dp(14));view.minimumHeight=dp(50);view.gravity=Gravity.TOP or Gravity.START
                view.layoutParams=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8);bottomMargin=dp(8)}}
            is CheckBox->{view.setTextColor(ink);view.textSize=16f;view.buttonTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(accent,muted));view.minHeight=dp(48);view.setPadding(dp(3),dp(4),dp(8),dp(4))}
            is CompoundButton->{view.setTextColor(ink);view.buttonTintList=ColorStateList.valueOf(accent);view.minHeight=dp(48);if(view is Switch){view.thumbTintList=ColorStateList.valueOf(accent);view.trackTintList=ColorStateList.valueOf(muted)}}
            is Button->{view.backgroundTintList=null;view.background=ripple(field);view.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(muted,accent)));view.isAllCaps=false;view.minHeight=dp(48);view.setPadding(dp(12),dp(8),dp(12),dp(8));view.compoundDrawablesRelative.filterNotNull().forEach{it.setTint(accent)}}
            is Spinner->{val values=(0 until (view.adapter?.count?:0)).map{view.adapter.getItem(it).toString()};val selected=view.selectedItemPosition
                view.adapter=object:ArrayAdapter<String>(context,android.R.layout.simple_spinner_item,values){
                    override fun getView(position:Int,convertView:View?,parent:ViewGroup)=label(getItem(position).orEmpty()).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),0,dp(14),0);minHeight=dp(50);val arrow=context.getDrawable(cn.jiangcheng.lemon.R.drawable.ic_down)!!.mutate();arrow.setTint(muted);arrow.setBounds(0,0,dp(15),dp(15));setCompoundDrawablesRelative(null,null,arrow,null);compoundDrawablePadding=dp(10)}
                    override fun getDropDownView(position:Int,convertView:View?,parent:ViewGroup)=label(getItem(position).orEmpty()).apply{setPadding(dp(16),dp(14),dp(16),dp(14));setBackgroundColor(field);minHeight=dp(48)}
                };view.setSelection(selected.coerceAtLeast(0));view.background=shape(field,10);view.setPopupBackgroundDrawable(shape(field,10));view.minimumHeight=dp(50)}
            is TextView->{view.setTextColor(ink);view.setLineSpacing(dp(2).toFloat(),1f)}
            is ViewGroup->{view.background=null;if(view is LinearLayout)view.setPadding(0,0,0,0);for(i in 0 until view.childCount)style(view.getChildAt(i))}
        }
    }
    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val header=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(24),dp(24),dp(12))}
        spec.title?.let{header.addView(label(it,20f).apply{typeface=Typeface.DEFAULT_BOLD})}
        if(spec.title==null)header.setPadding(dp(24),dp(12),dp(24),0)
        val content=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),0,dp(24),0)}
        spec.message?.let{content.addView(label(it,16f).apply{setTextColor(muted);setPadding(0,dp(4),0,dp(8))})}
        spec.view?.let{style(it);(it.parent as? ViewGroup)?.removeView(it);content.addView(it)}
        spec.items?.forEachIndexed{i,item->
            val destructive=item=="删除"||item=="彻底删除"
            content.addView(label(item).apply{gravity=Gravity.CENTER_VERTICAL;minHeight=dp(52);setPadding(dp(14),dp(12),dp(14),dp(12));background=ripple(field)
                if(destructive)setTextColor(if(night)0xffe1aaa0.toInt()else 0xffa35545.toInt())
                isClickable=true;isFocusable=true;setOnClickListener{dismiss();spec.itemClick?.onClick(this@ReaderDialog,i)}
            },LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6);bottomMargin=dp(2)})
        }
        val scroll=ScrollView(context).apply{isFillViewport=false;isVerticalScrollBarEnabled=false;addView(content)}
        val actions=LinearLayout(context).apply{setPadding(dp(24),dp(16),dp(24),dp(24))}
        fun action(which:Int,value:CharSequence,listener:DialogInterface.OnClickListener?):Button=Button(context).apply{
            text=value;isAllCaps=false;textSize=15f;typeface=Typeface.DEFAULT_BOLD;minWidth=0;minimumWidth=0;minHeight=dp(48);minimumHeight=dp(48);stateListAnimator=null;elevation=0f;backgroundTintList=null
            setPadding(dp(12),dp(12),dp(12),dp(12));maxLines=3;setTextColor(if(which==BUTTON_POSITIVE)if(night)0xff25271f.toInt()else Color.WHITE else accent);background=ripple(if(which==BUTTON_POSITIVE)accent else field)
            setOnClickListener{dismiss();listener?.onClick(this@ReaderDialog,which)};buttons[which]=this
        }
        val positive=spec.positive?.let{action(BUTTON_POSITIVE,it,spec.positiveClick)}
        val negative=spec.negative?.let{action(BUTTON_NEGATIVE,it,spec.negativeClick)}
        val available=minOf(context.resources.displayMetrics.widthPixels-dp(40),dp(400))-dp(48)
        val stacked=positive!=null&&negative!=null&&(maxOf(positive.paint.measureText(positive.text.toString()),negative.paint.measureText(negative.text.toString()))*2+dp(64)>available)
        actions.orientation=if(stacked)LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        if(stacked){positive?.let{actions.addView(it,LinearLayout.LayoutParams(-1,-2))};negative?.let{actions.addView(it,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})}}
        else {negative?.let{actions.addView(it,LinearLayout.LayoutParams(0,-2,1f))};positive?.let{actions.addView(it,LinearLayout.LayoutParams(0,-2,1f).apply{if(negative!=null)leftMargin=dp(10)})}}
        val root=object:LinearLayout(context){
            override fun onMeasure(widthSpec:Int,heightSpec:Int){
                val cap=minOf((resources.displayMetrics.heightPixels*.85).toInt(),if(MeasureSpec.getMode(heightSpec)==MeasureSpec.UNSPECIFIED)Int.MAX_VALUE else MeasureSpec.getSize(heightSpec))
                header.measure(widthSpec,MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));actions.measure(widthSpec,MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
                scroll.layoutParams.height=ViewGroup.LayoutParams.WRAP_CONTENT
                scroll.measure(widthSpec,MeasureSpec.makeMeasureSpec((cap-header.measuredHeight-actions.measuredHeight).coerceAtLeast(0),MeasureSpec.AT_MOST))
                scroll.layoutParams.height=scroll.measuredHeight
                super.onMeasure(widthSpec,MeasureSpec.makeMeasureSpec(cap,MeasureSpec.AT_MOST))
            }
        }.apply{orientation=LinearLayout.VERTICAL;background=shape(paper,18);clipToOutline=true;isFocusableInTouchMode=true;addView(header);addView(scroll,LinearLayout.LayoutParams(-1,-2));addView(actions,LinearLayout.LayoutParams(-1,-2))}
        setContentView(root)
        window?.apply{setBackgroundDrawableResource(android.R.color.transparent);addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);setDimAmount(.32f);setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)}
    }
    override fun show(){super.show();window?.setLayout(minOf(context.resources.displayMetrics.widthPixels-dp(40),dp(400)),ViewGroup.LayoutParams.WRAP_CONTENT)}
    class Builder(private val context:Context){
        internal var title:CharSequence?=null;internal var message:CharSequence?=null;internal var view:View?=null
        internal var positive:CharSequence?=null;internal var negative:CharSequence?=null
        internal var positiveClick:DialogInterface.OnClickListener?=null;internal var negativeClick:DialogInterface.OnClickListener?=null
        internal var items:Array<out CharSequence>?=null;internal var itemClick:DialogInterface.OnClickListener?=null
        fun setTitle(value:CharSequence)=apply{title=value}
        fun setMessage(value:CharSequence)=apply{message=value}
        fun setView(value:View)=apply{view=value}
        fun setPositiveButton(value:CharSequence,listener:DialogInterface.OnClickListener?)=apply{positive=value;positiveClick=listener}
        fun setNegativeButton(value:CharSequence,listener:DialogInterface.OnClickListener?)=apply{negative=value;negativeClick=listener}
        fun setItems(values:Array<out CharSequence>,listener:DialogInterface.OnClickListener)=apply{items=values;itemClick=listener}
        fun create()=ReaderDialog(context,this)
        fun show()=create().also{it.show()}
    }
}
