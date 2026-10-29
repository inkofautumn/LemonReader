package cn.jiangcheng.lemon

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Environment
import android.os.SystemClock
import android.view.*
import android.widget.*
import cn.jiangcheng.lemon.reader.*
import cn.jiangcheng.lemon.data.*
import org.json.*
import java.io.File

object Revision0111Test {
    fun run(test:Instrumentation,app:MainActivity):Int {
        check(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"))
        var checks=0
        fun verify(ok:Boolean,label:String){check(ok){label};checks++}
        fun ui(block:()->Unit){test.runOnMainSync(block);test.waitForIdleSync()}
        fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
        fun root()=views(app.window.decorView)
        fun waitFor(label:String,condition:()->Boolean){val until=System.currentTimeMillis()+25000;while(System.currentTimeMillis()<until){test.waitForIdleSync();if(condition())return;Thread.sleep(100)};error(label+" | "+views(app.window.decorView).filterIsInstance<TextView>().joinToString{it.text.toString()}+" | lists="+views(app.window.decorView).filterIsInstance<ListView>().map{it.adapter?.count})}
        fun shot(name:String){Thread.sleep(500);val dir=File(app.filesDir,"qa").apply{mkdirs()};test.uiAutomation.takeScreenshot().let{b->File(dir,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
        val owner="reading0111-${System.currentTimeMillis()}"
        ui{app.profileId=owner;app.prefs.edit().putString("appearance","默认主题").apply();app.showShelf()}
        val sample="夜色落在江面上，远处的灯火随着水波轻轻摇晃。林舟收起雨伞，沿着青石路走向书店。\n\n"
        var book=app.library.import(owner,Novel("夜航",listOf(Chapter("第一章 夜航",sample.repeat(50))),"UTF-8"),sample.repeat(50).toByteArray(),"夜航.txt")
        ui{app.openBook(book)}
        waitFor("阅读布局"){root().filterIsInstance<ReadingView>().firstOrNull()?.width?:0>0}
        val view=root().filterIsInstance<ReadingView>().single()
        book=app.reader!!.book
        ui{view.style.mode="无动画";view.applyStyle()}
        fun field(name:String)=ReadingView::class.java.getDeclaredField(name).apply{isAccessible=true}.get(view)
        fun range()=(field("selectionStart") as Int) to (field("selectionEnd") as Int)
        fun popup()=ReaderScreen::class.java.getDeclaredField("selectionPopup").apply{isAccessible=true}.get(app.reader) as PopupWindow
        fun point(at:Int):PointF{
            val l=field("layout") as android.text.StaticLayout
            val line=l.getLineForOffset(at);return PointF(l.getPrimaryHorizontal(at)+view.style.margin*app.resources.displayMetrics.density,view.top+l.getLineBaseline(line)-view.style.size*app.resources.displayMetrics.density*.3f)
        }
        fun touch(action:Int,p:PointF,time:Long){ui{val e=MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,p.x,p.y,0);view.dispatchTouchEvent(e);e.recycle()}}
        fun select(){ui{view.go(Position(0,0));view.clearSelection()};val p=point(5);val t=SystemClock.uptimeMillis();touch(MotionEvent.ACTION_DOWN,p,t);Thread.sleep(700);touch(MotionEvent.ACTION_UP,p,t);waitFor("选区工具条"){runCatching{popup().isShowing}.getOrDefault(false)}}
        select()
        verify(range()==TextSelection.sentence(sample,5),"长按选中整句")
        val actions=views(popup().contentView).mapNotNull{it.contentDescription?.toString()}
        verify(actions==listOf("复制","分享","划线","写札记","纠错","问说书人"),"六个紧凑操作")
        shot("90-sentence-selection")
        val old=range();val h=ReadingView::class.java.getDeclaredMethod("handlePoint",Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(view,false) as PointF
        val t=SystemClock.uptimeMillis();touch(MotionEvent.ACTION_DOWN,h,t);touch(MotionEvent.ACTION_MOVE,point(old.second+5),t);touch(MotionEvent.ACTION_UP,point(old.second+5),t)
        verify(range().first==old.first&&range().second==old.second+5,"右端拖动扩展选区")
        val left=ReadingView::class.java.getDeclaredMethod("handlePoint",Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(view,true) as PointF
        val leftTime=SystemClock.uptimeMillis();touch(MotionEvent.ACTION_DOWN,left,leftTime);touch(MotionEvent.ACTION_MOVE,point(2),leftTime);touch(MotionEvent.ACTION_UP,point(2),leftTime)
        verify(range().first==2&&range().second==old.second+5,"左端拖动缩小选区")
        shot("91-drag-selection")
        ui{views(popup().contentView).first{it.contentDescription=="划线"}.performClick()}
        verify(book.notes.last().quote==book.chapters[0].text.substring(2,old.second+5),"划线保存精确选区")
        verify(!view.hasSelection(),"划线后退出选区")
        shot("92-soft-highlight")
        ui{view.style.night=true;view.applyStyle()};select();shot("96-night-selection");ui{view.clearSelection();view.style.night=false;view.applyStyle()}
        select();ui{views(popup().contentView).first{it.contentDescription=="复制"}.performClick()}
        val clip=(app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
        verify(clip?.getItemAt(0)?.text==sample.substring(TextSelection.sentence(sample,5).first,TextSelection.sentence(sample,5).second),"复制准确摘录")
        ui{view.go(Position(0,0));app.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_DOWN));app.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_VOLUME_DOWN))}
        verify(view.position().offset>0,"音量下键下一页")
        ui{app.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_VOLUME_UP));app.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_VOLUME_UP))}
        verify(view.position().offset==0,"音量上键上一页")
        ui{app.prefs.edit().putBoolean("volume_turn:$owner",false).apply()}
        verify(!app.reader!!.volumeTurn(1),"关闭开关不接管按键")
        ui{app.prefs.edit().putBoolean("volume_turn:$owner",true).apply();app.reader!!.stop();app.reader=null;app.showSettings()}
        val toggle=root().filterIsInstance<Switch>().single{it.text=="音量键翻页"}
        verify(toggle.isChecked,"设置默认开启");ui{(toggle.parent.parent as View).requestRectangleOnScreen(android.graphics.Rect(0,0,toggle.width,toggle.height),true)};Thread.sleep(2300);shot("93-volume-setting")
        val downloads=File(Environment.getExternalStorageDirectory(),"Download/阅读导入测试").apply{mkdirs()}
        val now=System.currentTimeMillis()+60000
        val files=(1..12).map{i->File(downloads,"夜航外传${i}.txt").apply{writeText(sample+i);setLastModified(now-i*1000)}}
        val folder=File(downloads,"分章长篇").apply{mkdirs()}
        val chapters=(1..120).map{i->File(folder,"第${i}章 夜航.txt").apply{writeText(sample+i);setLastModified(now+1000)}}
        app.prefs.edit().putString("scan_mode:$owner:${downloads.absolutePath}","separate").commit()
        ui{MainActivity::class.java.getDeclaredMethod("showImport").apply{isAccessible=true}.invoke(app)}
        waitFor("近期下载列表"){root().filterIsInstance<ListView>().firstOrNull()?.adapter?.count==10}
        val list=root().filterIsInstance<ListView>().single()
        verify((list.adapter.getItem(0) as ScanCatalog.Entry).parts.size==120,"分章文件夹先聚合再截取十项")
        verify((list.adapter.getItem(1) as ScanCatalog.Entry).file==files.first(),"按保存时间倒序")
        ui{views(list.getChildAt(1)).filterIsInstance<CheckBox>().first().performClick()}
        verify(root().filterIsInstance<Button>().any{it.text=="导入所选（1）"&&it.isEnabled},"近期下载复用勾选及导入")
        shot("94-recent-downloads")
        val footer=root().filterIsInstance<Button>().first{it.text=="导入所选（1）"};val before=IntArray(2);footer.getLocationOnScreen(before)
        ui{list.setSelection(9)};Thread.sleep(250);val after=IntArray(2);footer.getLocationOnScreen(after)
        verify(before.contentEquals(after),"滚动到底部导入按钮位置保持不变")
        // FileProvider URI follows the same untrusted URI path as another app's share.
        val shared=File(app.cacheDir,"reader-log-exports/分享测试.txt").apply{parentFile!!.mkdirs();writeText(sample+"分享小说")}
        val uri=androidx.core.content.FileProvider.getUriForFile(app,app.packageName+".readerlogs",shared)
        val intent=Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}
        val matches=app.packageManager.queryIntentActivities(intent,0)
        verify(matches.any{it.activityInfo.name.endsWith("ImportNovel")},"系统分享匹配导入小说")
        ui{app.startActivity(Intent.createChooser(intent,"导入小说"))};Thread.sleep(3300);shot("97-system-share");test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);Thread.sleep(500)
        ui{MainActivity::class.java.getDeclaredMethod("onNewIntent",Intent::class.java).apply{isAccessible=true}.invoke(app,intent)}
        waitFor("分享进入预览"){root().filterIsInstance<TextView>().any{it.text=="分享测试.txt"}}
        verify(root().filterIsInstance<Button>().any{it.text=="仅本地整理"},"分享进入正常导入流程")
        shot("95-shared-import")
        val saved=android.os.Bundle();ui{MainActivity::class.java.getDeclaredMethod("onSaveInstanceState",android.os.Bundle::class.java).apply{isAccessible=true}.invoke(app,saved)}
        verify(saved.getStringArrayList("shared_novels")?.contains(uri.toString())==true,"分享预览保存待导入URI供重建恢复")
        val json=Library.toJson(book,false).put("chats",JSONArray().put(JSONObject().put("content","聊天资料"))).put("chronicles",JSONArray().put(JSONObject().put("id","clue").put("kind","线索").put("title","雨伞").put("content","夜航线索").put("chapter",0).put("offset",10)))
        val restored=Library.fromJson(json)
        verify(restored.notes.last().quote==book.notes.last().quote&&restored.chats.length()==1&&restored.chronicles.size==1,"阅读资料序列化保留划线对话线索")
        files.forEach{it.delete()};chapters.forEach{it.delete()};folder.delete();downloads.delete();shared.delete()
        return checks
    }
}
