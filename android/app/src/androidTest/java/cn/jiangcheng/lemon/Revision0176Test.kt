package cn.jiangcheng.lemon

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.os.SystemClock
import android.view.MotionEvent
import cn.jiangcheng.lemon.reader.Chapter
import cn.jiangcheng.lemon.reader.Position
import cn.jiangcheng.lemon.reader.ReadingView
import kotlin.math.abs

object Revision0176Test {
    fun run(test:Instrumentation,app:MainActivity):String {
        var checks=0
        fun verify(value:Boolean,message:String){check(value){message};checks++}
        fun field(name:String)=ReadingView::class.java.getDeclaredField(name).apply{isAccessible=true}
        fun handle(view:ReadingView,start:Boolean)=ReadingView::class.java.getDeclaredMethod("handlePoint",Boolean::class.javaPrimitiveType!!).apply{isAccessible=true}.invoke(view,start) as PointF
        fun touch(view:ReadingView,action:Int,x:Float,y:Float,down:Long,time:Long=down){MotionEvent.obtain(down,time,action,x,y,0).also{view.dispatchTouchEvent(it);it.recycle()}}
        fun select(view:ReadingView,start:Int,end:Int){field("selectionChapter").setInt(view,0);field("selectionStart").setInt(view,start);field("selectionEnd").setInt(view,end)}
        fun dragHandle(view:ReadingView,start:Boolean,x:Float,y:Float){
            val p=handle(view,start);val down=SystemClock.uptimeMillis()
            touch(view,MotionEvent.ACTION_DOWN,p.x,p.y,down)
            touch(view,MotionEvent.ACTION_MOVE,x,y,down,down+90)
            touch(view,MotionEvent.ACTION_UP,x,y,down,down+120)
        }

        lateinit var view:ReadingView
        val text=(1..1200).joinToString(""){"光标跨页往返和仿真翻页测试正文。"}
        test.runOnMainSync{
            view=ReadingView(app);app.setContentView(view);view.layout(0,0,1080,1920)
            view.style.mode="平移";view.setBook(listOf(Chapter("第一章",text)),Position(0,0))
        }
        test.waitForIdleSync()

        // Start handle: previous page, release, then next page with the same handle.
        test.runOnMainSync{view.turn(1)};test.waitForIdleSync()
        val originalSecond=view.position().offset
        test.runOnMainSync{select(view,originalSecond,view.boundary().offset);dragHandle(view,true,1f,1f)}
        val previous=view.position().offset
        verify(previous<originalSecond,"起始光标没有进入上一页")
        test.runOnMainSync{dragHandle(view,true,1079f,1919f)}
        verify(view.position().offset==originalSecond,"起始光标翻到上一页后不能返回下一页：${view.position().offset}/$originalSecond")

        // End handle: next page, release, then previous page with the same handle.
        test.runOnMainSync{view.clearSelection();view.go(Position(0,0));select(view,0,view.boundary().offset);dragHandle(view,false,1079f,1919f)}
        val next=view.position().offset
        verify(next>0,"结束光标没有进入下一页")
        test.runOnMainSync{dragHandle(view,false,1f,1f)}
        verify(view.position().offset==0,"结束光标翻到下一页后不能返回上一页：${view.position().offset}")

        // Both handles use the exact same cell boundary as the selection path.
        test.runOnMainSync{view.clearSelection();view.go(Position(0,0));select(view,0,view.boundary().offset)}
        val startPoint=handle(view,true);val endPoint=handle(view,false)
        val layout=field("layout").get(view) as android.text.StaticLayout
        val left=ReadingView::class.java.getDeclaredMethod("getContentLeft").apply{isAccessible=true}.invoke(view) as Float
        val endOffset=field("selectionEnd").getInt(view)
        @Suppress("UNCHECKED_CAST")
        val boxes=ReadingView::class.java.getDeclaredMethod("rangeBoxes",android.text.StaticLayout::class.java,Int::class.javaPrimitiveType!!,Int::class.javaPrimitiveType!!).apply{isAccessible=true}.invoke(view,layout,0,endOffset) as List<Any>
        val firstBounds=boxes.first().javaClass.getMethod("getRect").invoke(boxes.first()) as android.graphics.RectF
        val lastBounds=boxes.last().javaClass.getMethod("getRect").invoke(boxes.last()) as android.graphics.RectF
        verify(abs(startPoint.x-(left+firstBounds.left))<1f,"起始光标没有贴住首字选区边界")
        verify(abs(endPoint.x-(left+lastBounds.right))<1f,"结束光标没有贴住末字选区边界")

        fun renderDrag(mode:String):Bitmap{
            lateinit var candidate:ReadingView
            test.runOnMainSync{
                candidate=ReadingView(app);app.setContentView(candidate);candidate.layout(0,0,720,1280)
                candidate.style.mode=mode;candidate.setBook(listOf(Chapter("第一章",text)),Position(0,0))
                val down=SystemClock.uptimeMillis();touch(candidate,MotionEvent.ACTION_DOWN,650f,620f,down);touch(candidate,MotionEvent.ACTION_MOVE,350f,620f,down,down+120)
            }
            return Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888).also{bitmap->test.runOnMainSync{candidate.draw(Canvas(bitmap))}}
        }
        val simulation=renderDrag("仿真")
        val slide=renderDrag("平移")
        var different=0
        for(y in 80 until 1180 step 20)for(x in 40 until 680 step 20)if(simulation.getPixel(x,y)!=slide.getPixel(x,y))different++
        verify(different>80,"仿真模式的跟手画面仍与平移模式相同：$different")
        simulation.recycle();slide.recycle()

        return "$checks bidirectional handle and simulated page curl checks passed"
    }
}
