package cn.jiangcheng.lemon
import android.app.*
import android.view.accessibility.AccessibilityNodeInfo
import cn.jiangcheng.lemon.reader.*
import cn.jiangcheng.lemon.data.Book
import java.io.File
object Revision0122Test {
 fun run(test:Instrumentation,app:MainActivity):String {
  fun shot(name:String){Thread.sleep(500);val out=File(app.filesDir,"qa-cleanup").apply{mkdirs()};test.uiAutomation.takeScreenshot()?.let{bitmap->File(out,"$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}}
  var n=0;fun verify(v:Boolean){check(v){"cleanup check ${n+1} failed"};n++}
  val f=File(app.cacheDir,"cleanup-${System.nanoTime()}.json")
  val original=listOf(Chapter("一","甲广告乙广告丙"),Chapter("二","广告丁广告"))
  var current=original
  var h=CleanupHistory(f)
  val all=CleanupHistory.find(current,"广告");verify(all.size==4)
  val first=h.delete(current,"广告",listOf(all.first()));verify(first.map(Position(0,4))==Position(0,2));current=first.chapters
  val id=h.records().first().id;verify(current[0].text=="甲乙广告丙")
  val remaining=CleanupHistory.find(current,"广告");verify(remaining.size==3)
  current=h.delete(current,"广告",remaining).chapters;val second=h.records().first().id;verify(current.map{it.text}==listOf("甲乙丙","丁"))
  h=CleanupHistory(f);verify(h.records().size==2)
  current=h.restore(current,id).chapters;verify(current[0].text=="甲广告乙丙"&&current[1].text=="丁")
  current=h.restore(current,second).chapters;verify(current==original);verify(h.records().all{it.restored})
  verify(runCatching{h.restore(current,second)}.isFailure)
  current=h.delete(current,"广告",CleanupHistory.find(current,"广告")).chapters
  val third=h.records().first().id
  current=h.delete(current,"甲乙",CleanupHistory.find(current,"甲乙")).chapters
  val fourth=h.records().first().id
  current=h.restore(current,third).chapters;verify(current[0].text=="广告广告丙")
  current=h.restore(current,fourth).chapters;verify(current==original)
  current=h.delete(current,"广告",CleanupHistory.find(current,"广告")).chapters
  verify(runCatching{h.restore(current.map{it.copy(text=it.text+"新正文")},h.records().first().id)}.isFailure)
  verify(CleanupHistory.find(original,"").isEmpty());verify(CleanupHistory.find(listOf(Chapter("一","aaaa")),"aa").size==2)
  f.delete()
  lateinit var reader:ReaderScreen
  test.runOnMainSync{app.profileId="qa-cleanup";reader=ReaderScreen(app,Book(title="清理测试",chapters=original));app.reader=reader;reader.show();ReaderScreen::class.java.getDeclaredMethod("cleanupSelection",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,String::class.java).apply{isAccessible=true}.invoke(reader,0,1,3,"广告")}
  fun find(node:AccessibilityNodeInfo?,text:String):AccessibilityNodeInfo?{if(node==null)return null;if(node.text?.toString()==text)return node;for(i in 0 until node.childCount)find(node.getChild(i),text)?.let{return it};return null}
  var button:AccessibilityNodeInfo?=null
  repeat(30){if(button==null){Thread.sleep(100);button=find(test.uiAutomation.rootInActiveWindow,"全部清理（3处）")}}
  verify(button!=null);shot("batch-confirm");verify(button!!.performAction(AccessibilityNodeInfo.ACTION_CLICK));test.waitForIdleSync()
  verify(reader.book.chapters.map{it.text}==listOf("甲乙丙","丁"))
  test.runOnMainSync{ReaderScreen::class.java.getDeclaredMethod("showCleanupHistory").apply{isAccessible=true}.invoke(reader)};test.waitForIdleSync()
  shot("history");val restore=find(test.uiAutomation.rootInActiveWindow,"恢复这次清理");verify(restore!=null);verify(restore!!.performAction(AccessibilityNodeInfo.ACTION_CLICK));test.waitForIdleSync()
  verify(reader.book.chapters[0].text=="甲乙广告丙"&&reader.book.chapters[1].text==original[1].text)
  test.runOnMainSync{reader.stop();app.reader=null}
  return "$n cleanup, independent restore and native UI checks passed"
 }
}
