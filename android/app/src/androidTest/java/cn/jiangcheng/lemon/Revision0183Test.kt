package cn.jiangcheng.lemon

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import cn.jiangcheng.lemon.data.Book
import cn.jiangcheng.lemon.reader.Chapter
import cn.jiangcheng.lemon.reader.JustifiedTextCoordinates
import cn.jiangcheng.lemon.reader.Note
import cn.jiangcheng.lemon.reader.ReaderScreen
import cn.jiangcheng.lemon.reader.ReadingView
import cn.jiangcheng.lemon.reader.TextRangeGeometry
import java.io.File
import kotlin.math.abs

/** Android 15/16 regression for justified selection coordinates and actions. */
object Revision0183Test {
    fun run(test: Instrumentation, app: MainActivity): String {
        if (Build.VERSION.SDK_INT < 35) return "skipped: Android 15+ justified-selection regression"
        var checks = 0
        fun verify(value: Boolean, message: String) { check(value) { message }; checks++ }
        fun viewField(name: String) = ReadingView::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun descendants(root: View): List<View> = listOf(root) +
            if (root is ViewGroup) (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList()

        val owner = "qa-0183"
        val text = "　　这么天真，然而，在见识了修士层出不穷匪夷所思的手段之后，如今不只元国，西荒诸国绝没有哪个凡人再冒充驻凡办行事，被抓住就不说了，抓住之后各种手段想起来真是叫人不寒而栗。" +
            "这工作人员十分耐心地解释道，因为今年灵根普查工作覆盖了整个西荒。下一段继续验证右侧最后一个字仍然可以选中。"
        lateinit var reader: ReaderScreen
        lateinit var view: ReadingView
        lateinit var book: Book
        test.runOnMainSync {
            app.profileId = owner
            app.getSharedPreferences("shimo-ui:$owner", 0).edit().putBoolean("auto", false).commit()
            book = Book(title = "两端对齐选区", chapters = listOf(Chapter("第一章", text)))
            reader = ReaderScreen(app, book)
            app.reader = reader
            reader.show()
            view = ReaderScreen::class.java.getDeclaredField("view").apply { isAccessible = true }.get(reader) as ReadingView
            view.layout(0, 0, 1080, 1920)
        }
        test.waitForIdleSync()

        val layout = viewField("layout").get(view) as StaticLayout
        if (Build.VERSION.SDK_INT >= 35) {
            verify(layout.justificationMode == Layout.JUSTIFICATION_MODE_INTER_CHARACTER, "测试页面没有启用字间两端对齐")
        }
        val justifiedLine = (0 until layout.lineCount).firstOrNull {
            JustifiedTextCoordinates.boundaries(layout, it).size >= 4
        } ?: error("没有生成可验证的两端对齐行，SDK=${Build.VERSION.SDK_INT}")
        val boundaries = JustifiedTextCoordinates.boundaries(layout, justifiedLine)
        verify(boundaries.zipWithNext().all { it.first.second < it.second.second }, "字边界坐标不是严格递增")
        verify(abs(boundaries.last().second - layout.getLineRight(justifiedLine)) < 0.75f,
            "最后一个字边界没有到达视觉行尾：${boundaries.last().second}/${layout.getLineRight(justifiedLine)}")
        val middle = boundaries[boundaries.size / 2]
        verify(middle.second > layout.getPrimaryHorizontal(middle.first) + 1f,
            "专项用例没有覆盖系统未计入字间距的坐标差")
        boundaries.forEach { (offset, x) ->
            verify(JustifiedTextCoordinates.offsetForHorizontal(layout, justifiedLine, x) == offset,
                "绘制与命中边界不一致：$offset/$x")
        }
        verify(JustifiedTextCoordinates.offsetForHorizontal(layout, justifiedLine, layout.getLineRight(justifiedLine) + 80f) == boundaries.last().first,
            "拖到右侧后仍无法到达最后一个字边界")

        val selectedStart = boundaries[1].first
        val selectedEnd = boundaries[boundaries.lastIndex - 1].first
        val boxes = TextRangeGeometry.lineBoxes(layout, selectedStart, selectedEnd)
        verify(boxes.size == 1, "同一行选区被错误拆分")
        verify(abs(boxes.single().rect.left - boundaries[1].second) < 0.75f, "选区左边没有使用字边界表")
        verify(abs(boxes.single().rect.right - boundaries[boundaries.lastIndex - 1].second) < 0.75f, "选区右边没有使用字边界表")

        test.runOnMainSync {
            viewField("selectionChapter").setInt(view, 0)
            viewField("selectionStart").setInt(view, selectedStart)
            viewField("selectionEnd").setInt(view, selectedEnd)
            view.invalidate()
        }
        test.waitForIdleSync()
        val dragY = view.selectionBounds().centerY()
        val updateSelection = ReadingView::class.java.getDeclaredMethod("updateSelectionFromPointer").apply { isAccessible = true }
        test.runOnMainSync {
            viewField("selectionStart").setInt(view, boundaries.first().first)
            viewField("selectionEnd").setInt(view, boundaries[1].first)
            viewField("selectionHandle").setInt(view, 2)
            viewField("selectionPointerX").setFloat(view, view.width - 1f)
            viewField("selectionPointerY").setFloat(view, dragY)
            updateSelection.invoke(view)
        }
        verify(viewField("selectionEnd").getInt(view) == boundaries.last().first,
            "右光标实际拖动到屏幕右侧后没有到达行尾")
        test.runOnMainSync {
            viewField("selectionHandle").setInt(view, 0)
            viewField("selectionStart").setInt(view, selectedStart)
            viewField("selectionEnd").setInt(view, selectedEnd)
        }
        val beforeTap = view.position()
        val eventTime = SystemClock.uptimeMillis()
        test.runOnMainSync {
            val down = MotionEvent.obtain(eventTime, eventTime, MotionEvent.ACTION_DOWN, view.width - 4f, view.height / 2f, 0)
            view.onTouchEvent(down);down.recycle()
            val up = MotionEvent.obtain(eventTime, eventTime + 20, MotionEvent.ACTION_UP, view.width - 4f, view.height / 2f, 0)
            view.onTouchEvent(up);up.recycle()
        }
        test.waitForIdleSync();Thread.sleep(450);test.waitForIdleSync()
        verify(!view.hasSelection(), "点击页面没有取消选中")
        verify(view.position() == beforeTap, "取消选中的同一次点击仍然执行了翻页或菜单动作")

        val underline = Note(chapter = 0, start = selectedStart, end = selectedEnd,
            quote = text.substring(selectedStart, selectedEnd), body = "", type = "划线")
        test.runOnMainSync { book.notes = listOf(underline);reader.save() }
        val selection = ReaderScreen::class.java.getDeclaredMethod(
            "selection", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!
        ).apply { isAccessible = true }
        fun popupAction(label: String): View {
            val popup = ReaderScreen::class.java.getDeclaredField("selectionPopup").apply { isAccessible = true }.get(reader) as PopupWindow
            return descendants(popup.contentView).firstOrNull { it.contentDescription?.toString() == label }
                ?: error("选区操作栏缺少：$label")
        }
        test.runOnMainSync { selection.invoke(reader, 0, selectedStart, selectedEnd) }
        test.waitForIdleSync()
        verify(popupAction("取消划线").isClickable, "已划线选区没有显示取消划线")
        test.runOnMainSync { popupAction("取消划线").performClick() }
        test.waitForIdleSync()
        verify(book.notes.none { it.type == "划线" }, "取消划线没有删除对应划线")
        test.runOnMainSync { selection.invoke(reader, 0, selectedStart, selectedEnd) }
        test.waitForIdleSync()
        verify(popupAction("划线").isClickable, "取消后没有恢复划线操作")
        test.runOnMainSync { popupAction("划线").performClick() }
        test.waitForIdleSync()
        verify(book.notes.count { it.type == "划线" } == 1, "重新划线没有生成唯一记录")

        test.runOnMainSync {
            viewField("selectionChapter").setInt(view, 0)
            viewField("selectionStart").setInt(view, selectedStart)
            viewField("selectionEnd").setInt(view, selectedEnd)
            view.invalidate()
        }
        test.waitForIdleSync()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        test.runOnMainSync { view.draw(Canvas(bitmap)) }
        val qa = File(test.targetContext.filesDir, "qa").apply { mkdirs() }
        File(qa, "revision-0183-justified-selection.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        test.runOnMainSync { reader.stop();app.reader = null }
        return "$checks justified-boundary, underline-toggle and selection-dismiss checks passed"
    }

}
