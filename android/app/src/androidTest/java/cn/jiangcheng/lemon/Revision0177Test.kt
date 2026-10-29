package cn.jiangcheng.lemon

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.text.StaticLayout
import android.view.MotionEvent
import cn.jiangcheng.lemon.reader.Chapter
import cn.jiangcheng.lemon.reader.Position
import cn.jiangcheng.lemon.reader.ReadingView
import java.io.File
import kotlin.math.abs

object Revision0177Test {
    fun run(test:Instrumentation,app:MainActivity):String {
        var checks=0
        fun verify(value:Boolean,message:String){check(value){message};checks++}
        fun field(name:String)=ReadingView::class.java.getDeclaredField(name).apply{isAccessible=true}
        fun method(name:String,vararg types:Class<*>)=ReadingView::class.java.getDeclaredMethod(name,*types).apply{isAccessible=true}
        fun handle(view:ReadingView,start:Boolean)=method("handlePoint",Boolean::class.javaPrimitiveType!!).invoke(view,start) as PointF
        fun touch(view:ReadingView,action:Int,x:Float,y:Float,down:Long,time:Long=down){MotionEvent.obtain(down,time,action,x,y,0).also{view.dispatchTouchEvent(it);it.recycle()}}
        fun select(view:ReadingView,start:Int,end:Int){field("selectionChapter").setInt(view,0);field("selectionStart").setInt(view,start);field("selectionEnd").setInt(view,end)}
        fun dragHandle(view:ReadingView,start:Boolean,x:Float,y:Float){
            val p=handle(view,start);val down=SystemClock.uptimeMillis()
            touch(view,MotionEvent.ACTION_DOWN,p.x,p.y,down)
            touch(view,MotionEvent.ACTION_MOVE,x,y,down,down+90)
            touch(view,MotionEvent.ACTION_UP,x,y,down,down+120)
        }
        fun save(view:ReadingView,name:String){
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap));val dir=File(test.targetContext.filesDir,"qa").apply{mkdirs()}
            File(dir,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
        fun verifyHandleAttached(view:ReadingView,start:Boolean){
            val endpoint=if(start)field("selectionStart").getInt(view)else field("selectionEnd").getInt(view)
            val layout=field("layout").get(view) as StaticLayout
            @Suppress("UNCHECKED_CAST")
            val boxes=method("rangeBoxes",StaticLayout::class.java,Int::class.javaPrimitiveType!!,Int::class.javaPrimitiveType!!).invoke(view,layout,if(start)endpoint else endpoint-1,if(start)endpoint+1 else endpoint) as List<Any>
            val cell=boxes.first().javaClass.getMethod("getRect").invoke(boxes.first()) as RectF
            val left=method("getContentLeft").invoke(view) as Float
            val startY=method("startY").invoke(view) as Int
            val expectedX=left+if(start)cell.left else cell.right
            val expectedY=view.top-startY+if(start)cell.top else cell.bottom
            val point=handle(view,start)
            verify(abs(point.x-expectedX)<1f,"光标横向位置没有贴住选区边界：${point.x}/$expectedX")
            verify(abs(point.y-expectedY)<1f,"光标纵向位置没有贴住选区边界：${point.y}/$expectedY")
        }

        lateinit var view:ReadingView
        val text=(1..1300).joinToString(""){"跨页选择端点必须和高亮保持同一套坐标。"}
        test.runOnMainSync{
            view=ReadingView(app);app.setContentView(view);view.layout(0,0,1080,1920)
            view.style.mode="平移";view.setBook(listOf(Chapter("第一章",text)),Position(0,0))
            view.turn(1)
        }
        SystemClock.sleep(420);test.waitForIdleSync()
        test.waitForIdleSync()

        val secondStart=view.position().offset
        test.runOnMainSync{select(view,secondStart,view.boundary().offset);dragHandle(view,true,1f,1f)}
        verify(view.position().offset<secondStart,"起始光标没有翻到上一页")
        verify(field("selectionStart").getInt(view)==view.position().offset,"跨到上一页后逻辑端点没有原子更新到本页起点")
        test.runOnMainSync{verifyHandleAttached(view,true);save(view,"revision-0177-selection-previous.png")}

        test.runOnMainSync{dragHandle(view,true,1079f,1919f)}
        verify(view.position().offset==secondStart,"同一起始光标不能返回下一页")
        test.runOnMainSync{verifyHandleAttached(view,true)}

        test.runOnMainSync{view.clearSelection();view.go(Position(0,0));select(view,0,view.boundary().offset);dragHandle(view,false,1079f,1919f)}
        verify(view.position().offset>0,"结束光标没有翻到下一页")
        verify(field("selectionEnd").getInt(view)==view.boundary().offset,"跨到下一页后逻辑端点没有原子更新到本页末尾")
        test.runOnMainSync{verifyHandleAttached(view,false)}

        // Render the page curl from solid source pages. A lower touch must
        // produce a visibly diagonal, curved edge, and changing touch height
        // must change the fold rather than replaying one fixed animation.
        lateinit var curlView:ReadingView
        test.runOnMainSync{curlView=ReadingView(app);app.setContentView(curlView);curlView.layout(0,0,720,1280)}
        val old=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(218,82,58))}
        val incoming=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(60,102,198))}
        val drawCurl=method("drawSimulationTransition",Canvas::class.java,Bitmap::class.java,Bitmap::class.java,Int::class.javaPrimitiveType!!,Float::class.javaPrimitiveType!!,Float::class.javaPrimitiveType!!)
        fun curl(y:Float):Bitmap=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888).also{bitmap->test.runOnMainSync{drawCurl.invoke(curlView,Canvas(bitmap),old,incoming,1,.46f,y)}}
        val lower=curl(1080f);val upper=curl(180f)
        fun rightmostRed(bitmap:Bitmap,y:Int):Int{var result=-1;for(x in 0 until bitmap.width){val c=bitmap.getPixel(x,y);if(Color.red(c)>Color.blue(c)+60)result=x};return result}
        val topEdge=rightmostRed(lower,80);val bottomEdge=rightmostRed(lower,1200)
        verify(topEdge-bottomEdge>80,"下方触点没有形成明显的斜向卷页边缘：$topEdge/$bottomEdge")
        var changed=0
        for(y in 40 until 1240 step 20)for(x in 30 until 690 step 20)if(lower.getPixel(x,y)!=upper.getPixel(x,y))changed++
        verify(changed>180,"卷页形状没有随触点纵向位置变化：$changed")
        val dir=File(test.targetContext.filesDir,"qa").apply{mkdirs()}
        File(dir,"revision-0177-curl-lower.png").outputStream().use{lower.compress(Bitmap.CompressFormat.PNG,100,it)}
        File(dir,"revision-0177-curl-upper.png").outputStream().use{upper.compress(Bitmap.CompressFormat.PNG,100,it)}
        test.runOnMainSync{
            curlView.style.mode="仿真";curlView.setBook(listOf(Chapter("第一章",text)),Position(0,0))
            val down=SystemClock.uptimeMillis();touch(curlView,MotionEvent.ACTION_DOWN,690f,1080f,down);touch(curlView,MotionEvent.ACTION_MOVE,355f,1040f,down,down+140)
            save(curlView,"revision-0177-curl-text.png")
            touch(curlView,MotionEvent.ACTION_CANCEL,355f,1040f,down,down+160)
        }
        old.recycle();incoming.recycle();lower.recycle();upper.recycle()
        return "$checks selection attachment and touch-driven curl checks passed"
    }
}
