package cn.jiangcheng.lemon

import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import cn.jiangcheng.lemon.data.*
import cn.jiangcheng.lemon.reader.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

object ImportRegression {
    fun run(test:Instrumentation):String {
        var passed=0
        fun verify(value:Boolean,label:String){check(value){label};passed++}
        fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
        fun waitFor(label:String,condition:()->Boolean){val end=System.currentTimeMillis()+30000;while(System.currentTimeMillis()<end){test.waitForIdleSync();if(condition())return;Thread.sleep(100)};error(label)}
        val context=test.targetContext;val owner="ImportQA${System.currentTimeMillis()}"
        
        context.getSharedPreferences("app",0).edit().commit()
        val activity=test.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        test.runOnMainSync { activity.profileId = owner }
        val library=activity.library
        val bytes="花灯初上\n江水安静。\n雨后的书店\n灯火亮起。".toByteArray()
        val first=library.import(owner,Novel("原名",listOf(Chapter("正文",String(bytes))),"UTF-8"),bytes,"甲.txt")
        first.position=Position(0,3);first.notes=listOf(Note(chapter=0,start=0,end=2,quote="花灯",body="保留札记"));library.save(owner,first)
        verify(library.import(owner,Novel("别名",emptyList(),"UTF-8"),bytes,"乙.txt").id==first.id,"同内容不同文件名去重")
        verify(library.all(owner).size==1&&library.get(owner,first.id)!!.notes.size==1,"重复导入不覆盖进度札记")
        val changed=bytes.copyOf().apply{this[lastIndex]=33}
        verify(library.duplicate(owner,changed)==null,"同大小不同内容不合并")
        verify(library.duplicate("other-profile",bytes)==null,"本地资料隔离")
        test.runOnMainSync{activity.openBook(first)}
        waitFor("小小说阅读页未打开"){activity.reader!=null&&views(activity.window.decorView).any{it is ReadingView}}
        Thread.sleep(500);test.waitForIdleSync();verify(!activity.isFinishing,"小小说阅读页绘制")
        test.runOnMainSync{activity.showShelf()}
        val chapters=(1..60).map{Chapter("第${it}章",("灯火照着江面，少年走过长街。\n").repeat(1400))}
        val huge=library.import(owner,Novel("大文件回归",chapters,"UTF-8"),"large source fixture".toByteArray(),"large.txt")
        val oldFailure=runCatching{library.readableDatabase.rawQuery("SELECT payload FROM book_chapters WHERE id=?",arrayOf(huge.id)).use{it.moveToFirst();it.getString(0)}}.exceptionOrNull()
        verify(oldFailure is android.database.sqlite.SQLiteBlobTooBigException,"复现旧版整本正文 CursorWindow 读取崩溃")
        verify(library.get(owner,huge.id)!!.chapters==chapters,"大字段分块读取保留完整旧版内容")
        test.runOnMainSync{activity.openBook(huge)}
        waitFor("大书未打开"){activity.reader?.book?.id==huge.id&&views(activity.window.decorView).any{it is ReadingView}}
        Thread.sleep(700);test.waitForIdleSync();verify(!activity.isFinishing,"大书阅读页绘制")
        test.runOnMainSync{activity.showShelf()}
        val source=File(context.filesDir,"agent-test.txt").apply{writeText("未曾导入的内容，配置门禁测试。")}
        test.runOnMainSync{activity.readImport(Uri.fromFile(source))}
        waitFor("导入入口未出现"){views(activity.window.decorView).filterIsInstance<TextView>().any{it.text.toString()=="开始智能整理"}}
        test.runOnMainSync{views(activity.window.decorView).filterIsInstance<TextView>().first{it.text.toString()=="开始智能整理"}.performClick()}
        verify(views(activity.window.decorView).filterIsInstance<TextView>().any{it.text.toString()=="请先配置 DeepSeek，再进行智能整理"},"未配置 API 阻止智能整理")
        verify(library.all(owner,false).size==2,"未配置时不落书")
        var calls=0
        fun answer(value:JSONObject)=JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","stop").put("message",JSONObject().put("content",value.toString()))))
        val agent=ImportAgent(AiConfig("fixture","fixture","fixture")){request->
            calls++;val data=JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
            val content=if(data.has("samples"))JSONObject().put("encoding","UTF-8")else JSONObject().put("chapters",JSONArray().put(JSONObject().put("id",0).put("title","花灯初上")).put(JSONObject().put("id",2).put("title","雨后的书店"))).put("edits",JSONArray()).put("trim",JSONArray())
            answer(content)
        }
        val processed=agent.process(bytes,"甲.txt",ImportAgent.Options())
        verify(calls==2&&processed.chapters.map{it.title}==listOf("花灯初上","雨后的书店"),"由模型响应识别非正则章节标题")
        verify(processed.chapters.joinToString("\n"){it.text}==String(bytes),"模型分章完整保留原文")
        agent.process(bytes,"甲.txt",ImportAgent.Options());verify(calls==2,"重试复用已完成模型批次")
        var blockedCalls=0
        verify(runCatching{ImportAgent(AiConfig("","","")){blockedCalls++;answer(JSONObject())}.process(bytes,"甲.txt",ImportAgent.Options())}.isFailure&&blockedCalls==0,"API 门禁在模型请求前校验")
        val raw=ImportAgent(AiConfig("","","")).process(bytes,"甲.txt",ImportAgent.Options(false,false,false,false))
        verify(raw.chapters.size==1&&raw.chapters[0].text==String(bytes),"全部关闭时只读原文")
        val invalid=ImportAgent(AiConfig("fixture","fixture","fixture")){answer(JSONObject().put("chapters",JSONArray().put(JSONObject().put("id",99999).put("title","虚构章"))).put("edits",JSONArray()).put("trim",JSONArray()))}
        verify(runCatching{invalid.process(bytes,"甲.txt",ImportAgent.Options(false,true,false,false))}.isFailure,"拒绝不存在的章节定位")
        val correction=ImportAgent(AiConfig("fixture","fixture","fixture")){request->
            val d=JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
            answer(if(d.has("samples"))JSONObject().put("encoding","UTF-8")else JSONObject().put("chapters",JSONArray()).put("edits",JSONArray().put(JSONObject().put("id",0).put("old","江�").put("new","江水"))).put("trim",JSONArray()))
        }.process("江�安静。".toByteArray(),"文本.txt",ImportAgent.Options(true,false,false,false))
        verify(correction.chapters.single().text=="江水安静。","模型精确补丁修复乱码")
        test.runOnMainSync{activity.editBook(first)}
        test.waitForIdleSync()
        test.runOnMainSync{val fields=views(activity.window.decorView).filterIsInstance<android.widget.EditText>();fields[0].setText("修改后的书名");fields[1].setText("自定义封面");views(activity.window.decorView).filterIsInstance<TextView>().first{it.text.toString()=="保存"}.performClick()}
        val edited=library.get(owner,first.id)!!
        verify(edited.title=="修改后的书名"&&edited.coverText=="自定义封面"&&edited.notes.size==1,"书名封面编辑保留阅读资料")
        edited.coverColor=0xff123456.toInt();edited.coverInk=0xffeeeeee.toInt();library.save(owner,edited)
        verify(library.get(owner,first.id)!!.coverColor==edited.coverColor&&Library.fromJson(Library.toJson(edited)).coverInk==edited.coverInk,"封面颜色持久化")
        test.runOnMainSync{activity.editBook(edited)};test.waitForIdleSync()
        val image=test.uiAutomation.takeScreenshot();File(context.filesDir,"qa").mkdirs();File(context.filesDir,"qa/013-book-editor.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        test.runOnMainSync{views(activity.window.decorView).filterIsInstance<TextView>().first{it.text.toString()=="选择默认封面"}.performClick()}
        waitFor("默认封面列表未出现"){test.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("青黛")?.firstOrNull{it.text?.toString()=="青黛"}?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)==true}
        test.runOnMainSync{views(activity.window.decorView).filterIsInstance<TextView>().first{it.text.toString()=="保存"}.performClick()}
        verify(library.get(owner,first.id)!!.coverColor==0xffdbe3ed.toInt(),"默认封面实际选择保存")
        val bitmap=android.graphics.Bitmap.createBitmap(2400,3600,android.graphics.Bitmap.Config.ARGB_8888).apply{eraseColor(0xffcc7733.toInt())}
        val picture=File(context.filesDir,"cover-fixture.png");picture.outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        test.runOnMainSync{activity.editBook(library.get(owner,first.id)!!);views(activity.window.decorView).filterIsInstance<TextView>().first{it.text.toString()=="导入封面图片"}.performClick()}
        test.waitForIdleSync();test.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        test.runOnMainSync{activity.pickCallback!!.invoke(Uri.fromFile(picture))}
        waitFor("封面图片未写入"){library.sourceFile(owner,first.id).parentFile!!.listFiles()!!.any{it.name.startsWith("cover-")&&it.extension=="jpg"}}
        Thread.sleep(300);test.waitForIdleSync()
        test.runOnMainSync{views(activity.window.decorView).filterIsInstance<TextView>().first{it.text.toString()=="保存"}.performClick()}
        val cover=library.get(owner,first.id)!!.cover
        val bounds=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true};android.graphics.BitmapFactory.decodeFile(cover,bounds)
        verify(cover.isNotBlank()&&maxOf(bounds.outWidth,bounds.outHeight)<=1200,"导入图片封面自动缩小并保存")
        library.removeLocal(owner,huge)
        verify(library.sourceFile(owner,huge.id).parentFile!!.listFiles()!!.none{it.name.startsWith("processed-v")},"删除本机正文同时移除整理稿")
        return "$passed checks passed; legacy oversized-row crash reproduced and fixed; API gate, model-directed import, dedupe, covers verified"
    }
}
