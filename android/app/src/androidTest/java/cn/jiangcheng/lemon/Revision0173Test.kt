package cn.jiangcheng.lemon

import android.app.Dialog
import android.app.Instrumentation
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import cn.jiangcheng.lemon.data.Book
import cn.jiangcheng.lemon.reader.Chapter
import cn.jiangcheng.lemon.reader.Position
import cn.jiangcheng.lemon.reader.ReaderScreen
import cn.jiangcheng.lemon.reader.ReadingView

object Revision0173Test {
    fun run(test:Instrumentation,app:MainActivity):String {
        var checks=0
        fun verify(value:Boolean,message:String){check(value){message};checks++}
        fun descendants(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{descendants(view.getChildAt(it))}else emptyList()
        val owner="qa-0173"
        val text=(1..500).joinToString(""){"这是一句跨越多页且没有结束标点的测试正文"}+"。"
        lateinit var reader:ReaderScreen
        lateinit var view:ReadingView
        test.runOnMainSync {
            app.profileId=owner
            app.getSharedPreferences("reading:$owner",0).edit().clear().putInt("auto_page_seconds",5).commit()
            reader=ReaderScreen(app,Book(title="自动翻页测试",chapters=listOf(Chapter("第一章",text)),position=Position(0,0)))
            app.reader=reader;reader.show()
            view=ReaderScreen::class.java.getDeclaredField("view").apply{isAccessible=true}.get(reader) as ReadingView
            view.layout(0,0,1080,1920);view.go(Position(0,0))
        }
        test.waitForIdleSync()
        val visibleEnd=view.boundary().offset
        verify(visibleEnd in 1 until text.length,"测试正文没有跨页")
        val down=SystemClock.uptimeMillis()
        test.runOnMainSync {
            MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,540f,850f,0).also{view.dispatchTouchEvent(it);it.recycle()}
        }
        Thread.sleep(650)
        test.runOnMainSync {
            MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,540f,850f,0).also{view.dispatchTouchEvent(it);it.recycle()}
        }
        test.waitForIdleSync()
        val start=ReadingView::class.java.getDeclaredField("selectionStart").apply{isAccessible=true}.getInt(view)
        val end=ReadingView::class.java.getDeclaredField("selectionEnd").apply{isAccessible=true}.getInt(view)
        verify(start==0&&end==visibleEnd,"长按整句没有裁到当前页：$start..$end / 0..$visibleEnd")
        test.runOnMainSync{view.clearSelection();ReaderScreen::class.java.getDeclaredMethod("autoPageSettings").apply{isAccessible=true}.invoke(reader)}
        test.waitForIdleSync()
        val dialog=ReaderScreen::class.java.getDeclaredField("menu").apply{isAccessible=true}.get(reader) as Dialog
        val labels=descendants(dialog.window!!.decorView).filterIsInstance<TextView>()
        verify(labels.any{it.text=="每页 5 秒"},"自动翻页速度未显示")
        verify(labels.any{it.text=="左右翻页"}&&labels.any{it.text=="上下翻页"},"自动翻页方式不完整")
        test.runOnMainSync{labels.first{it.text=="上下翻页"}.performClick()}
        verify(labels.any{it.text=="每屏 5 秒"},"上下自动阅读未按每屏显示速度")
        val beforeScroll=ReadingView::class.java.getDeclaredField("scrollYText").apply{isAccessible=true}.getFloat(view)
        test.runOnMainSync{labels.first{it.text=="开始自动翻页"}.performClick()}
        Thread.sleep(500);test.waitForIdleSync()
        val afterScroll=ReadingView::class.java.getDeclaredField("scrollYText").apply{isAccessible=true}.getFloat(view)
        val automaticVertical=ReadingView::class.java.getDeclaredField("automaticVertical").apply{isAccessible=true}.getBoolean(view)
        verify(automaticVertical,"上下自动阅读没有切换到连续纵向模式")
        val active=ReaderScreen::class.java.getDeclaredField("autoPageActive").apply{isAccessible=true}.getBoolean(reader)
        val mode=ReaderScreen::class.java.getDeclaredField("autoPageMode").apply{isAccessible=true}.get(reader)
        val visible=ReaderScreen::class.java.getDeclaredField("visible").apply{isAccessible=true}.getBoolean(reader)
        val panel=ReaderScreen::class.java.getDeclaredField("autoPagePanelOpen").apply{isAccessible=true}.getBoolean(reader)
        val paused=ReaderScreen::class.java.getDeclaredField("autoPageSelectionPaused").apply{isAccessible=true}.getBoolean(reader)
        val menu=ReaderScreen::class.java.getDeclaredField("menu").apply{isAccessible=true}.get(reader) as Dialog?
        val lastFrame=ReaderScreen::class.java.getDeclaredField("autoVerticalLastFrame").apply{isAccessible=true}.getLong(reader)
        val delay=ReaderScreen::class.java.getDeclaredField("autoVerticalDelayUntil").apply{isAccessible=true}.getLong(reader)-SystemClock.uptimeMillis()
        verify(afterScroll>beforeScroll,"上下自动阅读没有按帧连续前进：$beforeScroll->$afterScroll active=$active mode=$mode visible=$visible panel=$panel paused=$paused menu=${menu?.isShowing} frame=$lastFrame delay=$delay")
        test.runOnMainSync{reader.stop();app.reader=null}
        return "$checks selection and auto-page checks passed"
    }
}
