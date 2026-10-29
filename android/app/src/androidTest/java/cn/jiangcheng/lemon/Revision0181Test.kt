package cn.jiangcheng.lemon

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.StaticLayout
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import cn.jiangcheng.lemon.data.Book
import cn.jiangcheng.lemon.reader.Chapter
import cn.jiangcheng.lemon.reader.ReaderScreen
import cn.jiangcheng.lemon.reader.ReadingView
import java.io.File

object Revision0181Test {
    fun run(test:Instrumentation,app:MainActivity):String {
        var checks=0
        fun verify(value:Boolean,message:String){check(value){message};checks++}
        fun descendants(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{descendants(view.getChildAt(it))}else emptyList()
        fun viewField(name:String)=ReadingView::class.java.getDeclaredField(name).apply{isAccessible=true}
        val owner="qa-0181"
        val quote="「成功了吗！？」"
        val paragraph="\u3000 \t$quote　 \n\u3000\u3000第二段前也有全角空格。\n下一句正文。"
        lateinit var reader:ReaderScreen
        lateinit var view:ReadingView
        test.runOnMainSync{
            app.profileId=owner
            val book=Book(title="划线空白裁剪",chapters=listOf(Chapter("第一章",paragraph)))
            reader=ReaderScreen(app,book);app.reader=reader;reader.show()
            view=ReaderScreen::class.java.getDeclaredField("view").apply{isAccessible=true}.get(reader) as ReadingView
            view.layout(0,0,1080,1920)
        }
        test.waitForIdleSync()

        val layout=viewField("layout").get(view) as StaticLayout
        val rangeBoxes=ReadingView::class.java.getDeclaredMethod("rangeBoxes",StaticLayout::class.java,
            Int::class.javaPrimitiveType!!,Int::class.javaPrimitiveType!!).apply{isAccessible=true}
        @Suppress("UNCHECKED_CAST")
        val boxes=rangeBoxes.invoke(view,layout,0,paragraph.indexOf("下一句")) as List<Any>
        verify(boxes.isNotEmpty(),"包含缩进空格的旧划线没有绘制可见文字")
        val starts=boxes.map{it.javaClass.getMethod("getStart").invoke(it) as Int}
        verify(starts.all{!layout.text[it].isWhitespace()},"划线背景仍从行首空白开始：$starts")
        verify(starts.first()==3,"首行全角和半角缩进没有全部裁掉：${starts.first()}")
        verify(starts.drop(1).any{it>paragraph.indexOf('\n')+1},"后续段落的行首缩进没有从显示区域排除")

        val rawEnd=paragraph.indexOf('\n')
        test.runOnMainSync{
            ReaderScreen::class.java.getDeclaredMethod("selection",Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).apply{isAccessible=true}.invoke(reader,0,0,rawEnd)
        }
        test.waitForIdleSync()
        val popup=ReaderScreen::class.java.getDeclaredField("selectionPopup").apply{isAccessible=true}.get(reader) as PopupWindow
        test.runOnMainSync{descendants(popup.contentView).first{it.contentDescription=="划线"}.performClick()}
        test.waitForIdleSync()
        val note=reader.book.notes.single()
        verify(note.start==3&&note.end==3+quote.length,"保存划线没有裁掉首尾空白：${note.start}-${note.end}")
        verify(note.quote==quote,"划线摘录仍包含缩进或尾部空白：${note.quote}")

        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        test.runOnMainSync{view.draw(Canvas(bitmap))}
        val qa=File(test.targetContext.filesDir,"qa").apply{mkdirs()}
        File(qa,"revision-0181-trimmed-highlight.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
        test.runOnMainSync{reader.stop();app.reader=null}
        return "$checks whitespace-trimmed highlight checks passed"
    }
}
