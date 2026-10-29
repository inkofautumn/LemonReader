package cn.jiangcheng.lemon

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.text.StaticLayout
import android.view.MotionEvent
import cn.jiangcheng.lemon.reader.Chapter
import cn.jiangcheng.lemon.reader.Position
import cn.jiangcheng.lemon.reader.ReadingView
import cn.jiangcheng.lemon.reader.Note
import java.io.File
import kotlin.math.abs

object Revision0178Test {
    fun run(test:Instrumentation,app:MainActivity):String {
        var checks=0
        fun verify(value:Boolean,message:String){check(value){message};checks++}
        fun field(name:String)=ReadingView::class.java.getDeclaredField(name).apply{isAccessible=true}
        fun method(name:String,vararg types:Class<*>)=ReadingView::class.java.getDeclaredMethod(name,*types).apply{isAccessible=true}
        fun touch(view:ReadingView,action:Int,x:Float,y:Float,down:Long,time:Long=down){
            MotionEvent.obtain(down,time,action,x,y,0).also{view.dispatchTouchEvent(it);it.recycle()}
        }
        fun save(bitmap:Bitmap,name:String){
            val dir=File(test.targetContext.filesDir,"qa").apply{mkdirs()}
            File(dir,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        fun render(view:ReadingView):Bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888).also{view.draw(Canvas(it))}

        lateinit var view:ReadingView
        val paragraph="孩子参加普查的时候，似乎只是在厂子职工的子女中进行，办到他们家大闺女时，也只扩大到元国都城周遭地域，刚刚听起来，竟是覆盖到了整个西荒？\n短行结束。\n这笑容满面的工作人员继续往下说。"
        test.runOnMainSync{
            view=ReadingView(app);app.setContentView(view);view.layout(0,0,720,1280)
            view.style.size=31f;view.style.mode="平移"
            view.setBook(listOf(Chapter("第一章",paragraph.repeat(8))),Position(0,0))
            field("selectionChapter").setInt(view,0)
            field("selectionStart").setInt(view,0)
            field("selectionEnd").setInt(view,paragraph.indexOf("这笑容"))
        }
        test.waitForIdleSync()
        val layout=field("layout").get(view) as StaticLayout
        val targetLine=4.coerceAtMost(layout.lineCount-1)
        val targetEnd=(layout.getLineStart(targetLine)+5).coerceAtMost(layout.getLineEnd(targetLine)-1)
        test.runOnMainSync{field("selectionEnd").setInt(view,targetEnd)}
        @Suppress("UNCHECKED_CAST")
        val boxes=method("rangeBoxes",StaticLayout::class.java,Int::class.javaPrimitiveType!!,Int::class.javaPrimitiveType!!)
            .invoke(view,layout,0,targetEnd) as List<Any>
        fun rect(box:Any)=box.javaClass.getMethod("getRect").invoke(box) as RectF
        verify(boxes.size>=3,"多行选区没有拆成逐行矩形：${boxes.size}")
        boxes.forEach{box->
            val r=rect(box);val line=box.javaClass.getMethod("getLine").invoke(box) as Int
            verify(r.left>=layout.getLineLeft(line)-1f&&r.right<=layout.getLineRight(line)+1f,
                "选区越过了第 $line 行真实文字边界：$r/${layout.getLineLeft(line)}-${layout.getLineRight(line)}")
        }
        val last=rect(boxes.last())
        val expectedEndX=layout.getPrimaryHorizontal(targetEnd)
        verify(abs(last.right-expectedEndX)<1f,"末行选区没有停在最后一个选中文字：${last.right}/$expectedEndX")
        verify(last.right<layout.getLineRight(targetLine)-20f,"末行选区仍被补到了整行右侧：${last.right}/${layout.getLineRight(targetLine)}")
        val handle=method("handlePoint",Boolean::class.javaPrimitiveType!!).invoke(view,false) as PointF
        val contentLeft=method("getContentLeft").invoke(view) as Float
        verify(abs(handle.x-(contentLeft+last.right))<1f,"结束光标没有贴住逐行选区右边界")
        val selectionShot=render(view);save(selectionShot,"revision-0178-selection-tight.png")
        test.runOnMainSync{
            view.highlights=listOf(Note(chapter=0,start=0,end=targetEnd,quote=layout.text.substring(0,targetEnd),body="",type="划线"))
            view.clearSelection()
        }
        val savedMarkShot=render(view);save(savedMarkShot,"revision-0178-saved-mark.png")

        // A diagonal gesture chooses one curl corner once. Returning to the
        // original Y or crossing it later must never turn it into landscape.
        test.runOnMainSync{view.highlights=emptyList();view.style.mode="仿真";view.go(Position(0,0))}
        val down=SystemClock.uptimeMillis()
        test.runOnMainSync{
            touch(view,MotionEvent.ACTION_DOWN,680f,1040f,down)
            touch(view,MotionEvent.ACTION_MOVE,500f,860f,down,down+40)
        }
        val modeField=field("dragCurlMode")
        val locked=modeField.get(view).toString()
        verify(locked.contains("Bottom"),"左上斜划没有锁定下角卷页：$locked")
        test.runOnMainSync{touch(view,MotionEvent.ACTION_MOVE,300f,1040f,down,down+80)}
        verify(modeField.get(view).toString()==locked,"横向移动把斜向卷页切换成了其他模式")
        test.runOnMainSync{touch(view,MotionEvent.ACTION_MOVE,210f,1140f,down,down+110)}
        verify(modeField.get(view).toString()==locked,"触点越过起始高度后卷页角发生改变")
        val curlShot=render(view);save(curlShot,"revision-0178-curl-locked.png")
        test.runOnMainSync{touch(view,MotionEvent.ACTION_CANCEL,210f,1140f,down,down+130)}

        // Directly sample the mesh renderer near completion. The committed
        // frame must be exactly the incoming page, while the preceding frame
        // still contains the curved source page.
        val old=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(214,82,57))}
        val incoming=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(61,103,199))}
        val draw=method("drawSimulationTransition",Canvas::class.java,Bitmap::class.java,Bitmap::class.java,
            Int::class.javaPrimitiveType!!,Float::class.javaPrimitiveType!!,Float::class.javaPrimitiveType!!)
        fun curl(amount:Float,y:Float)=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888).also{
            test.runOnMainSync{draw.invoke(view,Canvas(it),old,incoming,1,amount,y)}
        }
        val almost=curl(.72f,1060f);val completed=curl(1f,1060f)
        var sourcePixels=0;var wrongCompleted=0
        for(y in 0 until 1280 step 8)for(x in 0 until 720 step 8){
            if(almost.getPixel(x,y)!=incoming.getPixel(x,y))sourcePixels++
            if(completed.getPixel(x,y)!=incoming.getPixel(x,y))wrongCompleted++
        }
        verify(sourcePixels>0,"卷页结束前没有保留连续的弯曲书页")
        verify(wrongCompleted==0,"卷页完成帧不是完整下一页：$wrongCompleted")
        save(almost,"revision-0178-curl-near-end.png")
        selectionShot.recycle();savedMarkShot.recycle();curlShot.recycle();old.recycle();incoming.recycle();almost.recycle();completed.recycle()
        return "$checks tight selection and locked mesh curl checks passed"
    }
}
