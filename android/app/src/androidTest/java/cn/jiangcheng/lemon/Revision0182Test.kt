package cn.jiangcheng.lemon

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.text.StaticLayout
import cn.jiangcheng.lemon.data.Book
import cn.jiangcheng.lemon.reader.Chapter
import cn.jiangcheng.lemon.reader.Note
import cn.jiangcheng.lemon.reader.ReaderScreen
import cn.jiangcheng.lemon.reader.ReadingView
import java.io.File
import kotlin.math.abs

/** Regression for paragraph-indent paint and handle/highlight alignment. */
object Revision0182Test {
    fun run(test: Instrumentation, app: MainActivity): String {
        var checks = 0
        fun verify(value: Boolean, message: String) { check(value) { message }; checks++ }
        fun viewField(name: String) = ReadingView::class.java.getDeclaredField(name).apply { isAccessible = true }

        val owner = "qa-0182"
        val text = "\u3000\u3000前面的视觉缩进不应被划线，光标必须贴合真实文字边缘。" +
            "这一句继续延长，用来覆盖换行后的选区绘制。\n\uFEFF\u200B第二段正文。"
        val firstVisible = text.indexOf('前')
        lateinit var reader: ReaderScreen
        lateinit var view: ReadingView
        test.runOnMainSync {
            app.profileId = owner
            val oldNote = Note(chapter = 0, start = 0, end = text.indexOf('\n'), quote = text.substring(0, text.indexOf('\n')), body = "", type = "划线")
            val book = Book(title = "选区坐标", chapters = listOf(Chapter("第一章", text)), notes = listOf(oldNote))
            reader = ReaderScreen(app, book)
            app.reader = reader
            reader.show()
            view = ReaderScreen::class.java.getDeclaredField("view").apply { isAccessible = true }.get(reader) as ReadingView
            view.layout(0, 0, 1080, 1920)
        }
        test.waitForIdleSync()

        val layout = viewField("layout").get(view) as StaticLayout
        val rangeBoxes = ReadingView::class.java.getDeclaredMethod(
            "rangeBoxes", StaticLayout::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!
        ).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val oldBoxes = rangeBoxes.invoke(view, layout, 0, text.indexOf('\n')) as List<Any>
        verify(oldBoxes.isNotEmpty(), "旧划线没有生成文字矩形")
        val firstBox = oldBoxes.first()
        val firstStart = firstBox.javaClass.getMethod("getStart").invoke(firstBox) as Int
        val firstRect = firstBox.javaClass.getMethod("getRect").invoke(firstBox) as RectF
        verify(firstStart == firstVisible, "旧划线仍包含段首空白：$firstStart/$firstVisible")
        verify(abs(firstRect.left - layout.getPrimaryHorizontal(firstVisible)) < 0.75f,
            "首行高亮没有从真实首字开始：${firstRect.left}/${layout.getPrimaryHorizontal(firstVisible)}")

        val firstLineEnd = layout.getLineEnd(layout.getLineForOffset(firstVisible)).coerceAtMost(text.indexOf('\n'))
        test.runOnMainSync {
            viewField("selectionChapter").setInt(view, 0)
            viewField("selectionStart").setInt(view, firstVisible)
            viewField("selectionEnd").setInt(view, firstLineEnd)
            view.invalidate()
        }
        test.waitForIdleSync()
        val bounds = view.selectionBounds()
        val handleGeometry = ReadingView::class.java.getDeclaredMethod("handleGeometry", Boolean::class.javaPrimitiveType!!).apply { isAccessible = true }
        val startHandle = handleGeometry.invoke(view, true) ?: error("左光标没有生成")
        val endHandle = handleGeometry.invoke(view, false) ?: error("右光标没有生成")
        fun value(handle: Any, name: String) = handle.javaClass
            .getDeclaredMethod("get${name.replaceFirstChar { it.uppercase() }}")
            .apply { isAccessible = true }
            .invoke(handle) as Float
        verify(abs(value(startHandle, "x") - bounds.left) < 0.75f, "左光标没有贴合高亮左边：${value(startHandle, "x")}/${bounds.left}")
        verify(abs(value(endHandle, "x") - bounds.right) < 0.75f, "右光标没有贴合高亮右边：${value(endHandle, "x")}/${bounds.right}")
        verify(abs(value(startHandle, "top") - bounds.top) < 0.75f, "左光标顶部没有贴合当前文字行")
        verify(abs(value(endHandle, "bottom") - bounds.bottom) < 0.75f, "右光标底部没有贴合当前文字行")
        verify(abs(value(startHandle, "knobY") - bounds.top) < 0.75f, "左光标圆点没有落在高亮左上角")
        verify(abs(value(endHandle, "knobY") - bounds.bottom) < 0.75f, "右光标圆点没有落在高亮右下角")

        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        test.runOnMainSync { view.draw(Canvas(bitmap)) }
        val qa = File(test.targetContext.filesDir, "qa").apply { mkdirs() }
        File(qa, "revision-0182-handle-alignment.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        test.runOnMainSync { reader.stop(); app.reader = null }
        return "$checks paragraph-indent and handle-alignment checks passed"
    }
}
