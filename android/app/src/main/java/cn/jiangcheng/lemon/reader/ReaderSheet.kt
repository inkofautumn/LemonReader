package cn.jiangcheng.lemon.reader

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import cn.jiangcheng.lemon.MainActivity
import cn.jiangcheng.lemon.R

/** One sheet keeps its view tree intact while changing size, including draft and scroll state. */
class ReaderSheet(val app:MainActivity,val title:String,val paper:Int,val ink:Int,height:Float=.57f,scrollable:Boolean=true,private val drawer:Boolean=false,existing:Dialog?=null,private val sizeKey:String=title){
    val dialog=existing?:Dialog(app)
    val body=LinearLayout(app).apply{orientation=LinearLayout.VERTICAL}
    val outer=LinearLayout(app).apply{orientation=LinearLayout.VERTICAL;setPadding(app.dp(20),0,app.dp(20),app.dp(12));background=shape(paper,18)}
    private val preferences=app.getSharedPreferences("reading:${app.profileId}",0)
    private val half=height
    private var full=preferences.getBoolean("panel_full:$sizeKey",false)
    val sizeButton=icon(R.drawable.ic_expand,"展开面板"){resize(!full)}
    private var fraction=preferences.getFloat("panel_fraction:$sizeKey",if(full)1f else half).coerceIn(.35f,1f)
    val header=app.row()
    init{
        if(drawer){outer.background=GradientDrawable().apply{setColor(paper);val r=app.dp(18).toFloat();cornerRadii=floatArrayOf(0f,0f,r,r,r,r,0f,0f)};outer.clipToOutline=true}
        val grip=FrameLayout(app).apply{contentDescription="拖动调整面板高度"}
        grip.addView(View(app).apply{background=shape(0xffb8b09a.toInt(),3)},FrameLayout.LayoutParams(app.dp(36),app.dp(4),Gravity.CENTER))
        outer.addView(grip,LinearLayout.LayoutParams(-1,app.dp(28)))
        grip.visibility=if(drawer)View.GONE else View.VISIBLE
        var start=0f;var startHeight=0
        grip.setOnTouchListener{v,e->when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{start=e.rawY;startHeight=dialog.window?.attributes?.height?:available();v.parent.requestDisallowInterceptTouchEvent(true);true}
            MotionEvent.ACTION_MOVE->{setHeight((startHeight+start-e.rawY)/available());true}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{preferences.edit().putFloat("panel_fraction:$sizeKey",fraction).putBoolean("panel_full:$sizeKey",full).apply();true}
            else->true}}
        header.addView(label(title,19f).apply{typeface=Typeface.DEFAULT_BOLD;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,-2,1f))
        header.addView(sizeButton,LinearLayout.LayoutParams(app.dp(48),app.dp(44)))
        header.addView(icon(R.drawable.ic_close,"收起"){dialog.dismiss()},LinearLayout.LayoutParams(app.dp(44),app.dp(44)))
        outer.addView(header)
        if(scrollable)outer.addView(ScrollView(app).apply{isFillViewport=true;isVerticalScrollBarEnabled=false;addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        else outer.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        dialog.setContentView(outer)
        dialog.window?.apply{setBackgroundDrawableResource(android.R.color.transparent);setGravity(if(drawer)Gravity.START or Gravity.CENTER_VERTICAL else Gravity.BOTTOM);setWindowAnimations(if(drawer)R.style.ReaderDrawerAnimation else R.style.ReaderPanelAnimation);setDimAmount(.18f);setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)}
        if(!dialog.isShowing)dialog.show();setHeight(fraction)
    }
    fun navigation(back:()->Unit,reading:()->Unit){
        header.addView(icon(R.drawable.ic_back,"返回上一级",back),0,LinearLayout.LayoutParams(app.dp(44),app.dp(44)))
        (header.getChildAt(header.childCount-1) as TextView).apply{contentDescription="返回阅读";tooltipText="返回阅读";setOnClickListener{reading()}}
    }
    private fun available():Int{val bounds=app.windowManager.currentWindowMetrics;val bars=bounds.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());return bounds.bounds.height()-bars.top-bars.bottom}
    private fun setHeight(value:Float){fraction=value.coerceIn(.35f,1f);full=fraction>=.98f;sizeButton.visibility=if(drawer)View.GONE else View.VISIBLE
        val drawable=app.getDrawable(if(full)R.drawable.ic_collapse else R.drawable.ic_expand)!!.mutate().apply{setTint(ink);setBounds(0,0,app.dp(20),app.dp(20))};sizeButton.setCompoundDrawables(null,null,drawable,null);sizeButton.contentDescription=if(full)"收为半屏"else"展开全屏";sizeButton.tooltipText=sizeButton.contentDescription
        dialog.window?.setLayout(if(drawer)(app.resources.displayMetrics.widthPixels*.82).toInt()else -1,if(drawer)available()else(available()*fraction).toInt())}
    fun resize(value:Boolean){setHeight(if(value)1f else half);preferences.edit().putBoolean("panel_full:$sizeKey",full).putFloat("panel_fraction:$sizeKey",fraction).apply()}
    /** Reserve editable space while leaving a useful conversation viewport above the composer. */
    fun reserveBodyHeight(pixels:Int){
        if(!dialog.isShowing||drawer)return
        val needed=outer.paddingTop+outer.paddingBottom+app.dp(28)+header.height+pixels
        if(needed>(dialog.window?.attributes?.height?:0))setHeight(needed.toFloat()/available())
    }
    /** A floating window can be clipped by the IME without remeasuring its requested height. */
    fun followKeyboard(){
        outer.viewTreeObserver.addOnGlobalLayoutListener{
            val window=dialog.window
            if(dialog.isShowing&&window!=null){
                val visible=android.graphics.Rect();window.decorView.getWindowVisibleDisplayFrame(visible)
                val height=minOf((available()*fraction).toInt(),visible.height()).coerceAtLeast(1)
                if(window.attributes.height!=height)window.setLayout(-1,height)
            }
        }
    }
    /** Compact tool menus should initially reveal all actions, including at larger font scales. */
    fun fitActions(){outer.post{
        if(!dialog.isShowing||drawer||body.width<=0)return@post
        body.measure(View.MeasureSpec.makeMeasureSpec(body.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val needed=outer.paddingTop+outer.paddingBottom+app.dp(28)+header.measuredHeight+body.measuredHeight+app.dp(4)
        if(needed>(dialog.window?.attributes?.height?:0))setHeight(needed.toFloat()/available())
    }}
    private fun icon(resource:Int,description:String,action:()->Unit)=quiet("",action).apply{contentDescription=description;tooltipText=description;gravity=Gravity.CENTER;setPadding(app.dp(12),0,app.dp(12),0);val d=app.getDrawable(resource)!!.mutate();d.setTint(ink);d.setBounds(0,0,app.dp(20),app.dp(20));setCompoundDrawables(null,null,d,null)}
    fun shape(color:Int,radius:Int,stroke:Int?=null)=GradientDrawable().apply{setColor(color);cornerRadius=app.dp(radius).toFloat();stroke?.let{setStroke(app.dp(1),it)}}
    fun label(value:String,size:Float=15f)=TextView(app).apply{text=value;textSize=size;setTextColor(ink);setPadding(0,app.dp(7),0,app.dp(7))}
    fun quiet(value:String,action:()->Unit)=TextView(app).apply{text=value;textSize=14f;setTextColor(ink);gravity=Gravity.CENTER;minimumHeight=app.dp(42);setPadding(app.dp(8),0,app.dp(8),0);isClickable=true;isFocusable=true;setOnClickListener{action()}}
}
