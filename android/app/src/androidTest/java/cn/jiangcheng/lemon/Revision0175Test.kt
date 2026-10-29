package cn.jiangcheng.lemon

import android.app.Instrumentation
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.text.StaticLayout
import android.view.MotionEvent
import cn.jiangcheng.lemon.reader.Chapter
import cn.jiangcheng.lemon.reader.Position
import cn.jiangcheng.lemon.reader.ReadingView
import kotlin.math.abs

object Revision0175Test {
    fun run(test:Instrumentation,app:MainActivity):String {
        var checks=0
        fun verify(value:Boolean,message:String){check(value){message};checks++}
        fun field(name:String)=ReadingView::class.java.getDeclaredField(name).apply{isAccessible=true}
        fun method(name:String,vararg types:Class<*>)=ReadingView::class.java.getDeclaredMethod(name,*types).apply{isAccessible=true}
        fun handle(view:ReadingView,start:Boolean)=method("handlePoint",Boolean::class.javaPrimitiveType!!).invoke(view,start) as PointF
        fun touch(view:ReadingView,action:Int,x:Float,y:Float,down:Long,time:Long=down){MotionEvent.obtain(down,time,action,x,y,0).also{view.dispatchTouchEvent(it);it.recycle()}}

        lateinit var view:ReadingView
        val chapter0=(1..900).joinToString(""){"第一章连续阅读与跨页选择测试正文。"}
        val chapter1=(1..500).joinToString(""){"第二章连续滚动衔接测试正文。"}
        test.runOnMainSync{
            view=ReadingView(app)
            app.setContentView(view)
            view.layout(0,0,1080,1920)
            view.style.mode="平移"
            view.setBook(listOf(Chapter("第一章",chapter0),Chapter("第二章",chapter1)),Position(0,0))
        }
        test.waitForIdleSync()

        val start=view.position().offset
        val end=view.boundary().offset
        test.runOnMainSync{
            field("selectionChapter").setInt(view,0)
            field("selectionStart").setInt(view,start)
            field("selectionEnd").setInt(view,end)
            view.invalidate()
        }
        val layout=field("layout").get(view) as StaticLayout
        val endPoint=handle(view,false)
        @Suppress("UNCHECKED_CAST")
        val boxes=ReadingView::class.java.getDeclaredMethod("rangeBoxes",StaticLayout::class.java,Int::class.javaPrimitiveType!!,Int::class.javaPrimitiveType!!).apply{isAccessible=true}.invoke(view,layout,start,end) as List<Any>
        val glyph=boxes.last().javaClass.getMethod("getRect").invoke(boxes.last()) as RectF
        val contentLeft=ReadingView::class.java.getDeclaredMethod("getContentLeft").apply{isAccessible=true}.invoke(view) as Float
        val top=view.top-(ReadingView::class.java.getDeclaredMethod("startY").apply{isAccessible=true}.invoke(view) as Int)
        val density=app.resources.displayMetrics.density
        verify(abs(endPoint.x-(contentLeft+glyph.right))<1.5f,"右侧光标没有贴住末字选区边界")
        verify(abs(endPoint.y-(top+glyph.bottom))<1.5f,"右侧光标没有贴齐末字下边界")

        // A glyph at the upper-left edge is still text, not a page-turn zone.
        test.runOnMainSync{view.go(Position(0,end));field("selectionChapter").setInt(view,0);field("selectionStart").setInt(view,end);field("selectionEnd").setInt(view,view.boundary().offset)}
        val secondPage=view.position().offset
        val startHandle=handle(view,true);val glyphDown=SystemClock.uptimeMillis()
        test.runOnMainSync{
            touch(view,MotionEvent.ACTION_DOWN,startHandle.x,startHandle.y,glyphDown)
            touch(view,MotionEvent.ACTION_MOVE,startHandle.x+1f,startHandle.y+1f,glyphDown,glyphDown+80)
            touch(view,MotionEvent.ACTION_UP,startHandle.x+1f,startHandle.y+1f,glyphDown,glyphDown+100)
        }
        verify(view.position().offset==secondPage,"拖到本页第一个字时错误触发了上一页")

        // A held corner turns exactly once; leaving the zone rearms it and
        // the same handle can return to the original page.
        test.runOnMainSync{view.clearSelection();view.go(Position(0,0));field("selectionChapter").setInt(view,0);field("selectionStart").setInt(view,0);field("selectionEnd").setInt(view,view.boundary().offset)}
        val firstPage=view.position().offset
        val h=handle(view,false);val down=SystemClock.uptimeMillis()
        test.runOnMainSync{
            touch(view,MotionEvent.ACTION_DOWN,h.x,h.y,down)
            touch(view,MotionEvent.ACTION_MOVE,1078f,1918f,down,down+80)
        }
        val afterOne=view.position().offset
        test.runOnMainSync{touch(view,MotionEvent.ACTION_MOVE,1078f,1918f,down,down+1200)}
        verify(afterOne>firstPage,"右下角没有进入下一页")
        verify(view.position().offset==afterOne,"停留在右下角时连续触发了多次翻页")
        test.runOnMainSync{
            touch(view,MotionEvent.ACTION_MOVE,540f,960f,down,down+1250)
            touch(view,MotionEvent.ACTION_MOVE,1f,1f,down,down+1320)
            touch(view,MotionEvent.ACTION_UP,1f,1f,down,down+1350)
        }
        verify(view.position().offset==firstPage,"同一光标反向拖动不能返回原页")

        test.runOnMainSync{
            view.clearSelection();view.style.mode="上下";view.applyStyle();view.go(Position(0,0))
        }
        val scrollField=field("scrollYText")
        val beforeScroll=scrollField.getFloat(view)
        val smallStep=24f
        test.runOnMainSync{verify(view.automaticVerticalStep(smallStep),"纵向模式不能连续推进")}
        val afterScroll=scrollField.getFloat(view)
        verify(abs((afterScroll-beforeScroll)-smallStep)<1f,"纵向滚动不是像素级连续移动")

        val beforeHorizontal=view.position()
        val horizontalDown=SystemClock.uptimeMillis()
        test.runOnMainSync{
            touch(view,MotionEvent.ACTION_DOWN,900f,900f,horizontalDown)
            touch(view,MotionEvent.ACTION_MOVE,100f,910f,horizontalDown,horizontalDown+180)
            touch(view,MotionEvent.ACTION_UP,100f,910f,horizontalDown,horizontalDown+220)
        }
        verify(view.position()==beforeHorizontal,"上下阅读时左右滑动仍然触发了翻页")

        test.runOnMainSync{view.automaticVerticalStep(view.verticalViewportHeight()*200f)}
        verify(view.position().chapter==1,"连续纵向滚动没有跨章节衔接")
        verify(!view.hasPage(1),"滚到全书末尾后仍报告可以继续")

        return "$checks selection geometry, edge state and continuous vertical reading checks passed"
    }
}
