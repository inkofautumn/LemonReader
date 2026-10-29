package cn.jiangcheng.lemon

import android.app.Instrumentation
import android.content.Intent
import android.os.StrictMode
import android.view.Choreographer
import cn.jiangcheng.lemon.data.Book
import cn.jiangcheng.lemon.reader.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

object Scroll026Test {
    fun run(i:Instrumentation):String {
        val app=i.startActivitySync(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val text="林舟在桥边等候老友，他仍记得交出铜钥匙的那个夜晚。\n".repeat(180)
        val book=Book(title="连续滚动验证",chapters=(1..24).map{Chapter("第${it}章",text)},chats=JSONArray((1..2000).map{JSONObject().put("question","林舟的往事$it").put("answer",text.take(600))}))
        app.library.save("qa-scroll026",book)
        lateinit var reader:ReaderScreen;lateinit var view:ReadingView
        fun field(name:String)=ReaderScreen::class.java.getDeclaredField(name).apply{isAccessible=true}
        i.runOnMainSync{
            app.profileId="qa-scroll026";
            app.prefs.edit().putBoolean("auto_chronicles:${app.profileId}",false).commit()
            app.getSharedPreferences("reading:${app.profileId}",0).edit().putString("mode","上下").putInt("auto_page_seconds",15).putString("auto_page_mode","上下翻页").commit()
            reader=ReaderScreen(app,book);app.reader=reader;reader.show();view=field("view").get(reader) as ReadingView
        }
        Thread.sleep(1800);i.waitForIdleSync()
        val intervals=CopyOnWriteArrayList<Double>();val violations=CopyOnWriteArrayList<String>()
        var previous=0L;var running=true;var oldPolicy:StrictMode.ThreadPolicy?=null
        val frames=object:Choreographer.FrameCallback{override fun doFrame(t:Long){if(!running)return;if(previous>0)intervals.add((t-previous)/1e6);previous=t;Choreographer.getInstance().postFrameCallback(this)}}
        i.runOnMainSync{
            oldPolicy=StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyListener(java.util.concurrent.Executor{it.run()}){violations.add(it.stackTraceToString())}.build())
            field("autoPageActive").setBoolean(reader,true)
            ReaderScreen::class.java.getDeclaredMethod("scheduleAutoPage",Boolean::class.javaPrimitiveType!!).apply{isAccessible=true}.invoke(reader,false)
            Choreographer.getInstance().postFrameCallback(frames)
        }
        Thread.sleep(18500)
        var progressed=0
        i.runOnMainSync{running=false;Choreographer.getInstance().removeFrameCallback(frames);oldPolicy?.let{StrictMode.setThreadPolicy(it)};progressed=view.position().offset}
        val saved=app.library.get("qa-scroll026",book.id)!!
        val sorted=intervals.sorted();fun percentile(p:Double)=sorted.getOrElse((sorted.size*p).toInt().coerceAtMost(sorted.lastIndex)){0.0}
        val report=JSONObject().put("frames",intervals.size).put("medianMs",percentile(.5)).put("p95Ms",percentile(.95)).put("p99Ms",percentile(.99)).put("over50ms",intervals.count{it>50}).put("maxMs",sorted.lastOrNull()).put("position",progressed).put("savedPosition",saved.position.offset).put("mainThreadDiskViolations",JSONArray(violations))
        File(app.getExternalFilesDir(null),"audit026/scroll.json").apply{parentFile!!.mkdirs();writeText(report.toString())}
        check(progressed>0&&saved.position.offset>0){"滚动或定时进度保存失败"}
        check(saved.chats.length()==2000){"后台进度保存丢失旧对话"}
        check(violations.none{it.contains("Library.save")||it.contains("Library.payload")}){"滚动期间依旧在 UI 线程保存/读取资料"}
        i.runOnMainSync{reader.stop();app.reader=null;app.showHome()}
        return report.toString()
    }
}
