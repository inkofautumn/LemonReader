package cn.jiangcheng.lemon

import android.app.Instrumentation
import android.os.SystemClock
import android.view.MotionEvent
import cn.jiangcheng.lemon.reader.Chapter
import cn.jiangcheng.lemon.reader.Position
import cn.jiangcheng.lemon.reader.ReadingStyle
import cn.jiangcheng.lemon.reader.ReadingView

/** Exercises a chapter boundary, including a reverse swipe before settling ends. */
object ReaderChapterTransitionRegression {
    fun run(test:Instrumentation,app:MainActivity):String {
        val text=(1..75).joinToString("\n\n") { "第${it}段。夜色落在江面上，行人沿着青石路走向远方。" }
        lateinit var view:ReadingView
        test.runOnMainSync {
            view=ReadingView(app).apply { style=ReadingStyle(mode="平移") }
            app.setContentView(view)
            view.layout(0,0,1080,1920)
            view.setBook(listOf(Chapter("第一章",text),Chapter("第二章",text)),Position(0,0))
            view.go(Position(0,text.length))
        }
        val cache=ReadingView::class.java.getDeclaredField("preparedChapters").apply { isAccessible=true }
        val dragDistance=ReadingView::class.java.getDeclaredField("dragDistance").apply { isAccessible=true }
        val previousBitmap=ReadingView::class.java.getDeclaredField("dragPreviousBitmap").apply { isAccessible=true }
        val nextBitmap=ReadingView::class.java.getDeclaredField("dragNextBitmap").apply { isAccessible=true }
        fun continuousReverse(mode:String,finishOnPrevious:Boolean){
            test.runOnMainSync {
                view.style=ReadingStyle(mode=mode)
                view.setBook(listOf(Chapter("第一章",text),Chapter("第二章",text)),Position(0,text.length/2))
                val start=view.position().offset
                val down=SystemClock.uptimeMillis()
                fun dispatch(action:Int,x:Float,time:Long){
                    val event=MotionEvent.obtain(down,time,action,x,900f,0)
                    view.onTouchEvent(event);event.recycle()
                }
                dispatch(MotionEvent.ACTION_DOWN,540f,down)
                dispatch(MotionEvent.ACTION_MOVE,160f,down+50)
                check((dragDistance.get(view) as Float)<0f && nextBitmap.get(view)!=null) { "$mode 左滑未显示下一页" }
                dispatch(MotionEvent.ACTION_MOVE,930f,down+110)
                check((dragDistance.get(view) as Float)>0f && previousBitmap.get(view)!=null) { "$mode 同一次触摸反向时未显示上一页" }
                if(!finishOnPrevious){
                    dispatch(MotionEvent.ACTION_MOVE,100f,down+170)
                    check((dragDistance.get(view) as Float)<0f && nextBitmap.get(view)!=null) { "$mode 再次反向时未返回下一页" }
                }
                dispatch(MotionEvent.ACTION_UP,if(finishOnPrevious)930f else 100f,down+210)
                val final=view.position().offset
                check(if(finishOnPrevious)final<start else final>start) { "$mode 连续反向松手后落页错误：$start -> $final" }
            }
        }
        continuousReverse("平移",true)
        continuousReverse("平移",false)
        continuousReverse("覆盖",true)
        continuousReverse("覆盖",false)
        test.runOnMainSync { view.style=ReadingStyle(mode="平移");view.setBook(listOf(Chapter("第一章",text),Chapter("第二章",text)),Position(0,text.length)) }
        val limit=SystemClock.uptimeMillis()+5000
        while(SystemClock.uptimeMillis()<limit){
            var ready=false
            test.runOnMainSync { ready=(cache.get(view) as Map<*,*>).containsKey(1) }
            if(ready)break
            Thread.sleep(30)
        }
        test.runOnMainSync { check((cache.get(view) as Map<*,*>).containsKey(1)) { "相邻章节没有提前排版" } }
        fun swipe(from:Float,to:Float){
            test.runOnMainSync {
                val down=SystemClock.uptimeMillis()
                listOf(
                    MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,from,900f,0),
                    MotionEvent.obtain(down,down+55,MotionEvent.ACTION_MOVE,to,900f,0),
                    MotionEvent.obtain(down,down+95,MotionEvent.ACTION_UP,to,900f,0)
                ).forEach { event -> view.onTouchEvent(event);event.recycle() }
            }
        }
        swipe(850f,170f)
        test.runOnMainSync { check(view.chapter==1) { "跨章左滑没有立即提交到第二章" } }
        swipe(170f,850f)
        test.runOnMainSync { check(view.chapter==0) { "动画未结束时反向滑动没有返回第一章" } }
        return "Continuous left/right reversals and adjacent chapter swipes passed"
    }
}
