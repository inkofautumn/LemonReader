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

object Revision0180Test {
    fun run(test:Instrumentation,app:MainActivity):String {
        var checks=0
        fun verify(value:Boolean,message:String){check(value){message};checks++}
        fun descendants(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{descendants(view.getChildAt(it))}else emptyList()
        val owner="qa-0180"
        val text=(1..900).joinToString(""){"上下自动阅读关闭设置以后应当立即继续滚动。"}
        lateinit var reader:ReaderScreen
        lateinit var view:ReadingView
        test.runOnMainSync{
            app.profileId=owner
            app.getSharedPreferences("reading:$owner",0).edit().clear()
                .putInt("auto_page_seconds",5).putString("auto_page_mode","上下翻页").commit()
            reader=ReaderScreen(app,Book(title="上下自动阅读续播",chapters=listOf(Chapter("第一章",text)),position=Position(0,0)))
            app.reader=reader;reader.show()
            view=ReaderScreen::class.java.getDeclaredField("view").apply{isAccessible=true}.get(reader) as ReadingView
            view.layout(0,0,1080,1920);view.go(Position(0,0))
            ReaderScreen::class.java.getDeclaredMethod("autoPageSettings").apply{isAccessible=true}.invoke(reader)
        }
        test.waitForIdleSync()
        fun menu()=ReaderScreen::class.java.getDeclaredField("menu").apply{isAccessible=true}.get(reader) as Dialog?
        var dialog=menu()?:error("自动翻页设置没有打开")
        var labels=descendants(dialog.window!!.decorView).filterIsInstance<TextView>()
        test.runOnMainSync{labels.first{it.text=="开始自动翻页"}.performClick()}
        Thread.sleep(650);test.waitForIdleSync()
        val scrollField=ReadingView::class.java.getDeclaredField("scrollYText").apply{isAccessible=true}
        val initialScroll=scrollField.getFloat(view)
        val activeField=ReaderScreen::class.java.getDeclaredField("autoPageActive").apply{isAccessible=true}
        verify(initialScroll>0f,
            "上下自动阅读启动后没有立即滚动：active=${activeField.getBoolean(reader)} mode=${ReaderScreen::class.java.getDeclaredField("autoPageMode").apply{isAccessible=true}.get(reader)}")

        val down=SystemClock.uptimeMillis()
        test.runOnMainSync{
            MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,540f,850f,0).also{view.dispatchTouchEvent(it);it.recycle()}
            MotionEvent.obtain(down,down+40,MotionEvent.ACTION_UP,540f,850f,0).also{view.dispatchTouchEvent(it);it.recycle()}
        }
        test.waitForIdleSync()
        dialog=menu()?:error("运行中点击正文没有打开设置")
        val pausedAt=scrollField.getFloat(view)
        Thread.sleep(350);test.waitForIdleSync()
        verify(kotlin.math.abs(scrollField.getFloat(view)-pausedAt)<1f,"设置打开时上下自动阅读没有暂停")
        test.runOnMainSync{dialog.dismiss()};test.waitForIdleSync()
        Thread.sleep(300);test.waitForIdleSync()
        val resumedAt=scrollField.getFloat(view)
        verify(resumedAt>pausedAt+10f,"关闭设置后上下自动阅读没有立即续滚：$pausedAt->$resumedAt")
        verify(activeField.getBoolean(reader),"返回正文后自动翻页状态被关闭")
        test.runOnMainSync{reader.stop();app.reader=null}
        return "$checks immediate vertical auto-page resume checks passed"
    }
}
