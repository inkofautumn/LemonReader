package cn.jiangcheng.lemon

import android.app.Dialog
import android.app.Instrumentation
import android.graphics.PointF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import cn.jiangcheng.lemon.data.Book
import cn.jiangcheng.lemon.reader.Chapter
import cn.jiangcheng.lemon.reader.Position
import cn.jiangcheng.lemon.reader.ReaderScreen
import cn.jiangcheng.lemon.reader.ReadingView

object Revision0174Test {
    fun run(test:Instrumentation,app:MainActivity):String {
        var checks=0
        fun verify(value:Boolean,message:String){check(value){message};checks++}
        fun descendants(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{descendants(view.getChildAt(it))}else emptyList()
        fun field(name:String)=ReadingView::class.java.getDeclaredField(name).apply{isAccessible=true}
        fun handle(view:ReadingView,start:Boolean)=ReadingView::class.java.getDeclaredMethod("handlePoint",Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(view,start) as PointF
        fun touch(view:ReadingView,action:Int,x:Float,y:Float,down:Long,time:Long=down){MotionEvent.obtain(down,time,action,x,y,0).also{view.dispatchTouchEvent(it);it.recycle()}}

        val owner="qa-0174"
        val text=(1..600).joinToString(""){"跨页选择和自动翻页重新计时测试正文"}+"。"
        lateinit var reader:ReaderScreen
        lateinit var view:ReadingView
        test.runOnMainSync {
            app.profileId=owner
            app.getSharedPreferences("reading:$owner",0).edit().clear().commit()
            reader=ReaderScreen(app,Book(title="自动翻页计时测试",chapters=listOf(Chapter("第一章",text)),position=Position(0,0)))
            app.reader=reader;reader.show()
            view=ReaderScreen::class.java.getDeclaredField("view").apply{isAccessible=true}.get(reader) as ReadingView
            view.layout(0,0,1080,1920);view.go(Position(0,0))
        }
        test.waitForIdleSync()

        test.runOnMainSync{ReaderScreen::class.java.getDeclaredMethod("autoPageSettings").apply{isAccessible=true}.invoke(reader)}
        test.waitForIdleSync()
        var dialog=ReaderScreen::class.java.getDeclaredField("menu").apply{isAccessible=true}.get(reader) as Dialog
        var controls=descendants(dialog.window!!.decorView)
        var labels=controls.filterIsInstance<TextView>()
        val speed=controls.filterIsInstance<SeekBar>().first{it.contentDescription.toString().startsWith("自动翻页速度")}
        verify(labels.any{it.text=="每页 15 秒"},"默认自动翻页速度不是15秒")
        verify(speed.max==59,"自动翻页速度范围不是1到60秒")
        fun seekTouch(fraction:Float){
            val down=SystemClock.uptimeMillis();val x=(speed.width*fraction).coerceIn(1f,speed.width-1f);val y=speed.height/2f
            test.runOnMainSync{
                MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,x,y,0).also{speed.dispatchTouchEvent(it);it.recycle()}
                MotionEvent.obtain(down,down+30,MotionEvent.ACTION_UP,x,y,0).also{speed.dispatchTouchEvent(it);it.recycle()}
            }
            test.waitForIdleSync()
        }
        seekTouch(0f)
        verify(labels.any{it.text=="每页 60 秒"},"自动翻页上限不是60秒")
        seekTouch(1f)
        verify(labels.any{it.text=="每页 1 秒"},"自动翻页下限不是1秒")
        test.runOnMainSync{speed.progress=58;ReaderScreen::class.java.getDeclaredField("autoPageSeconds").apply{isAccessible=true}.setInt(reader,2);labels.first{it.text=="开始自动翻页"}.performClick()}
        val initial=view.position().offset
        Thread.sleep(1200)
        test.runOnMainSync{verify(view.turn(1),"测试手动翻页失败")}
        val manual=view.position().offset
        verify(manual>initial,"手动翻页没有前进")
        Thread.sleep(1100);test.waitForIdleSync()
        verify(view.position().offset==manual,"手动翻页后没有按完整间隔重新计时")
        Thread.sleep(1050);test.waitForIdleSync()
        verify(view.position().offset>manual,"重新计时结束后没有自动翻页")
        Thread.sleep(450);test.waitForIdleSync()

        // Stop auto paging before exercising selection handles.
        test.runOnMainSync{ReaderScreen::class.java.getDeclaredMethod("autoPageSettings").apply{isAccessible=true}.invoke(reader)}
        test.waitForIdleSync();dialog=ReaderScreen::class.java.getDeclaredField("menu").apply{isAccessible=true}.get(reader) as Dialog
        labels=descendants(dialog.window!!.decorView).filterIsInstance<TextView>()
        test.runOnMainSync{labels.first{it.text=="关闭自动翻页"}.performClick();view.go(Position(0,0))}
        test.waitForIdleSync()

        // The end handle enters the next page only from the bottom-right zone.
        val firstStart=view.position().offset;val firstEnd=view.boundary().offset
        test.runOnMainSync{field("selectionChapter").setInt(view,0);field("selectionStart").setInt(view,firstStart);field("selectionEnd").setInt(view,firstEnd)}
        val endHandle=handle(view,false);val downEnd=SystemClock.uptimeMillis()
        test.runOnMainSync{touch(view,MotionEvent.ACTION_DOWN,endHandle.x,endHandle.y,downEnd);touch(view,MotionEvent.ACTION_MOVE,1075f,1915f,downEnd,downEnd+700);touch(view,MotionEvent.ACTION_UP,1075f,1915f,downEnd,downEnd+720)}
        test.waitForIdleSync()
        verify(view.position().offset>=firstEnd,"右侧光标拖到右下角没有进入下一页")
        verify(field("selectionEnd").getInt(view)>firstEnd,"跨到下一页后选区没有继续扩展")

        // From the second page, the start handle can extend back through the top-left zone.
        test.runOnMainSync{view.clearSelection();view.go(Position(0,0))}
        Thread.sleep(400);test.waitForIdleSync()
        test.runOnMainSync{verify(view.turn(1),"无法进入第二页测试上一页扩选")}
        Thread.sleep(400);test.waitForIdleSync()
        test.waitForIdleSync()
        val secondStart=view.position().offset;val secondEnd=view.boundary().offset
        test.runOnMainSync{field("selectionChapter").setInt(view,0);field("selectionStart").setInt(view,secondStart);field("selectionEnd").setInt(view,secondEnd)}
        val startHandle=handle(view,true);val downStart=SystemClock.uptimeMillis()
        test.runOnMainSync{touch(view,MotionEvent.ACTION_DOWN,startHandle.x,startHandle.y,downStart);touch(view,MotionEvent.ACTION_MOVE,5f,5f,downStart,downStart+700);touch(view,MotionEvent.ACTION_UP,5f,5f,downStart,downStart+720)}
        test.waitForIdleSync()
        verify(view.position().offset<secondStart,"左侧光标拖到左上角没有进入上一页：${view.position().offset} / $secondStart")
        verify(field("selectionStart").getInt(view)<secondStart,"跨到上一页后选区没有继续扩展：${field("selectionStart").getInt(view)} / $secondStart")

        test.runOnMainSync{reader.stop();app.reader=null}
        return "$checks auto-page timing and cross-page selection checks passed"
    }
}
