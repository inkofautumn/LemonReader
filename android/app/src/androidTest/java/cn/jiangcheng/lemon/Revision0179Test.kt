package cn.jiangcheng.lemon

import android.app.Dialog
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import java.io.File

object Revision0179Test {
    fun run(test:Instrumentation,app:MainActivity):String {
        var checks=0
        fun verify(value:Boolean,message:String){check(value){message};checks++}
        fun descendants(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{descendants(view.getChildAt(it))}else emptyList()
        fun save(bitmap:Bitmap,name:String){
            val dir=File(test.targetContext.filesDir,"qa").apply{mkdirs()}
            File(dir,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }

        // Previous-page curl mirrors its geometry to the left edge. The page
        // texture must be mirrored a second time so text and asymmetric page
        // content remain in their original left/right orientation.
        lateinit var probe:ReadingView
        test.runOnMainSync{probe=ReadingView(app);app.setContentView(probe);probe.layout(0,0,720,1280);probe.style.mode="仿真"}
        val old=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888).apply{
            val canvas=Canvas(this)
            canvas.drawColor(Color.rgb(54,156,86))
            canvas.drawRect(0f,0f,180f,1280f,android.graphics.Paint().apply{color=Color.rgb(214,55,47)})
            canvas.drawRect(540f,0f,720f,1280f,android.graphics.Paint().apply{color=Color.rgb(37,91,211)})
            val textPaint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;textSize=42f}
            canvas.drawText("LEFT 123",18f,150f,textPaint);canvas.drawText("RIGHT 789",500f,230f,textPaint)
        }
        val incoming=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888).apply{
            val canvas=Canvas(this);canvas.drawColor(Color.rgb(235,213,74))
            canvas.drawRect(0f,0f,180f,1280f,android.graphics.Paint().apply{color=Color.rgb(174,65,190)})
            canvas.drawRect(540f,0f,720f,1280f,android.graphics.Paint().apply{color=Color.rgb(38,192,206)})
            val textPaint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=38f}
            canvas.drawText("PREVIOUS LEFT",18f,330f,textPaint);canvas.drawText("PREV RIGHT",510f,410f,textPaint)
        }
        val draw=ReadingView::class.java.getDeclaredMethod("drawSimulationTransition",Canvas::class.java,
            Bitmap::class.java,Bitmap::class.java,Int::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!,Float::class.javaPrimitiveType!!).apply{isAccessible=true}
        val previous=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888)
        test.runOnMainSync{draw.invoke(probe,Canvas(previous),old,incoming,-1,.34f,930f)}
        save(previous,"revision-0179-previous-curl.png")
        val right=previous.getPixel(680,640)
        // The curled back is tinted toward the paper color. Its blue side
        // must still remain bluer than red; requiring the untinted 60-point
        // gap incorrectly rejects the mature renderer on API 30.
        verify(Color.blue(right)>Color.red(right),"上一页卷页仍把当前页左右镜像：${Integer.toHexString(right)}")

        // Start automatic paging, tap the reading page to open its settings,
        // dismiss the sheet, and verify a fresh full interval is scheduled.
        val owner="qa-0179"
        val text=(1..650).joinToString(""){"自动翻页关闭设置面板后必须继续运行。"}
        lateinit var reader:ReaderScreen
        lateinit var view:ReadingView
        test.runOnMainSync{
            app.profileId=owner
            app.getSharedPreferences("reading:$owner",0).edit().clear()
                .putInt("auto_page_seconds",1).putString("auto_page_mode","左右翻页").commit()
            reader=ReaderScreen(app,Book(title="自动翻页续播测试",chapters=listOf(Chapter("第一章",text)),position=Position(0,0)))
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
        test.waitForIdleSync()
        verify(ReaderScreen::class.java.getDeclaredField("autoPageActive").apply{isAccessible=true}.getBoolean(reader),"自动翻页没有启动")
        val down=SystemClock.uptimeMillis()
        test.runOnMainSync{
            MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,540f,850f,0).also{view.dispatchTouchEvent(it);it.recycle()}
            MotionEvent.obtain(down,down+40,MotionEvent.ACTION_UP,540f,850f,0).also{view.dispatchTouchEvent(it);it.recycle()}
        }
        test.waitForIdleSync()
        dialog=menu()?:error("运行中点击页面没有打开自动翻页设置")
        verify(ReaderScreen::class.java.getDeclaredField("autoPagePanelOpen").apply{isAccessible=true}.getBoolean(reader),"自动翻页设置状态没有打开")
        val pausedAt=view.position().offset
        Thread.sleep(1150);test.waitForIdleSync()
        verify(view.position().offset==pausedAt,"设置面板打开时自动翻页仍在运行")
        test.runOnMainSync{dialog.dismiss()};test.waitForIdleSync()
        verify(!ReaderScreen::class.java.getDeclaredField("autoPagePanelOpen").apply{isAccessible=true}.getBoolean(reader),"关闭设置后面板状态未清除")
        Thread.sleep(1250);test.waitForIdleSync()
        verify(view.position().offset>pausedAt,"关闭自动翻页设置后没有恢复计时：${view.position().offset}/$pausedAt")

        test.runOnMainSync{reader.stop();app.reader=null}
        old.recycle();incoming.recycle();previous.recycle()
        return "$checks previous-page curl and automatic-page resume checks passed"
    }
}
