package cn.jiangcheng.lemon

import android.app.Instrumentation
import android.app.Dialog
import android.graphics.Bitmap
import android.view.*
import cn.jiangcheng.lemon.reader.*
import cn.jiangcheng.lemon.data.*
import org.json.*
import java.io.File

object Revision0112Test {
    fun run(test:Instrumentation,app:MainActivity):String {
        check(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"))
        var checks=0
        fun verify(ok:Boolean,message:String){check(ok){message};checks++}
        fun ui(block:()->Unit){test.runOnMainSync(block);test.waitForIdleSync()}
        fun shot(name:String){Thread.sleep(600);val dir=File(app.filesDir,"qa").apply{mkdirs()};test.uiAutomation.takeScreenshot().let{b->File(dir,"112-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
        val owner="revision0112"
        ui{app.profileId=owner;app.prefs.edit().putBoolean("auto_chronicles:$owner",false).apply()}
        val bytes=test.context.assets.open("reader-fixture.txt").use{it.readBytes()}
        val begin=System.currentTimeMillis()
        val local=LocalPrepare.read(bytes,"桥边来信.txt",ImportAgent.Options())
        val localMs=System.currentTimeMillis()-begin
        verify(local.chapters.isNotEmpty(),"本地处理可阅读")
        verify(LocalPrepare.clean("句子中途\n下一行\n\n\n段落。  ",true)=="句子中途\n下一行\n\n段落。","有歧义的断行保留")
        val source=(1..60).joinToString("\n"){"第${it}章 标题$it\n正文内容，保持不变。"}.toByteArray()
        var calls=0;val reviewed=mutableSetOf<Int>()
        val mock=ImportAgent(AiConfig("","mock","test")){request->calls++;val input=JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"));val candidates=input.getJSONArray("candidates");for(i in 0 until candidates.length())reviewed.add(candidates.getJSONObject(i).getInt("id"));JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","stop").put("message",JSONObject().put("content","{\"keep\":[],\"remove\":[]}"))))}
        val unchanged=mock.process(source,"sample.txt",ImportAgent.Options())
        verify(calls==3&&reviewed.size==60,"覆盖完整目录而非仅疑点")
        verify(unchanged.chapters==LocalPrepare.read(source,"sample.txt",ImportAgent.Options()).chapters,"不确定结果不修改原目录")
        calls=0;mock.process(source,"sample.txt",ImportAgent.Options(chapters=false));verify(calls==0,"仅格式处理不调用模型")
        val mockExtract=ChronicleTasks.extract(AiConfig("","mock","test"),2,50,"林舟走进书店，发现一封未署名的信。",1,call={request->
            verify(!request.toString().contains("第一章"),"自动书志仅接收增量片段")
            JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","stop").put("message",JSONObject().put("content","{\"entries\":[{\"kind\":\"人物\",\"title\":\"林舟\",\"content\":\"进入书店\",\"evidence\":\"林舟走进书店\"},{\"kind\":\"线索\",\"title\":\"虚构线索\",\"content\":\"未知内容\",\"evidence\":\"原文没有这句话\"}]}"))))
        })
        verify(mockExtract.size==1&&mockExtract.single().offset==56,"自动书志过滤无证据项并记录原文位置")
        val budget=runCatching{AiGateway.request(AiConfig("https://invalid.example","mock","test",budget=1),JSONObject().put("max_tokens",1).put("messages",JSONArray()))}.exceptionOrNull()
        verify(budget?.message?.contains("预算")==true,"预算耗尽在发请求前停止")
        val secret=File(app.filesDir,"qwen-test-key")
        var optimized=local;var realMs=0L;var autoEntries=emptyList<Chronicle>()
        if(secret.isFile){
            val cfg=AiConfig("https://dashscope.aliyuncs.com/compatible-mode/v1","qwen3.7-flash",secret.readText().trim(),context=app,owner=owner,task="真实小说目录测试",budget=100000)
            try{val started=System.currentTimeMillis();optimized=ImportAgent(cfg).process(bytes,"桥边来信.txt",ImportAgent.Options());realMs=System.currentTimeMillis()-started;File(app.filesDir,"qa").mkdirs();File(app.filesDir,"qa/112-model-result.json").writeText(JSONArray(optimized.chapters.map{JSONObject().put("title",it.title).put("length",it.text.length)}).toString(2));verify(optimized.chapters.size==local.chapters.size,"原书连续编号目录不误增正文小标题");verify(optimized.chapters.joinToString(""){it.text}==local.chapters.joinToString(""){it.text},"AI 目录优化不改正文")
                val chapter=local.chapters.indexOfFirst{it.text.length>1000};val fragment=local.chapters[chapter].text.take(4000)
                val entries=ChronicleTasks.extract(cfg.copy(task="自动书志真实增量测试"),chapter,0,fragment,1);autoEntries=entries
                verify(entries.all{fragment.contains(it.evidence)},"Qwen 自动书志有逐字证据")
                File(app.filesDir,"qa/112-auto-chronicles.json").writeText(JSONArray(entries.map{JSONObject().put("title",it.title).put("kind",it.kind).put("content",it.content).put("evidence",it.evidence)}).toString(2))}finally{secret.delete()}
        }
        var book=app.library.import(owner,optimized,bytes,"桥边来信.txt",app.library.duplicate(owner,bytes))
        if(autoEntries.isNotEmpty()){book.chronicles=autoEntries;app.library.save(owner,book)}
        ui{app.openBook(book)};Thread.sleep(1800)
        val screen=app.reader!!
        val reader=ReaderScreen::class.java.getDeclaredField("view").apply{isAccessible=true}.get(screen) as ReadingView
        val counts=ReadingView::class.java.getDeclaredField("bookPageCounts").apply{isAccessible=true}
        val timeout=System.currentTimeMillis()+30000;while(counts.get(reader)==null&&System.currentTimeMillis()<timeout)Thread.sleep(100)
        verify((counts.get(reader) as? IntArray)?.size==optimized.chapters.size,"真实排版统计整本页数")
        ui{reader.go(Position(if(book.chapters.size>1)1 else 0,0))};shot("chapter-opening")
        ui{screen.showCollection("目录")};shot("directory")
        val menu=ReaderScreen::class.java.getDeclaredField("menu").apply{isAccessible=true};val first=menu.get(screen) as Dialog
        val switch=ReaderScreen::class.java.getDeclaredMethod("switchCollection",String::class.java,String::class.java).apply{isAccessible=true}
        ui{switch.invoke(screen,"札记","全部")};verify(menu.get(screen)===first&&first.isShowing,"札记在同一个弹层切换");shot("notes")
        ui{reader.go(Position(1,book.chapters[1].text.length));switch.invoke(screen,"书志","全部")};verify(menu.get(screen)===first,"书志在同一个弹层切换");shot("chronicle")
        ui{app.getSharedPreferences("reading:$owner",0).edit().putBoolean("panels_full",true).apply();switch.invoke(screen,"书志","全部")};shot("chronicle-full")
        ui{(menu.get(screen) as Dialog).dismiss();app.getSharedPreferences("reading:$owner",0).edit().putBoolean("panels_full",false).putBoolean("toc_drawer",true).apply();screen.showCollection("目录")};shot("directory-drawer")
        val drawer=menu.get(screen)
        ui{switch.invoke(screen,"札记","全部")};verify(menu.get(screen)===drawer,"侧栏切换复用同一窗口");shot("notes-drawer")
        ui{(menu.get(screen) as Dialog).dismiss();app.getSharedPreferences("reading:$owner",0).edit().putBoolean("toc_drawer",false).apply();screen.showCollection("目录")}
        val titleMethod=ReaderScreen::class.java.getDeclaredMethod("titleCompletion").apply{isAccessible=true}
        ui{titleMethod.invoke(screen)};shot("title-completion")
        ui{(menu.get(screen) as Dialog).dismiss()}
        val before=reader.position();ui{screen.updateDirectory(optimized.copy(chapters=optimized.chapters.mapIndexed{i,c->if(i==0)c.copy(title="优化后的章名")else c}));switch.invoke(screen,"目录","全部")}
        verify(reader.position()==before,"目录实时更新不跳动正文")
        ui{(menu.get(screen) as Dialog).dismiss();
            app.prefs.edit().putString("models:$owner",JSONArray().put(JSONObject().put("id","qa").put("name","Qwen").put("provider","qwen").put("base","https://dashscope.aliyuncs.com/compatible-mode/v1").put("models","qwen3.7-flash")).toString()).apply()
            app.secrets.set("model-key:$owner:qa","test-only")
            verify(ModelProfiles.resolve(app,owner,"daily").model==ModelProfiles.resolve(app,owner,"deep").model,"单模型自动承担两类任务")
            verify(ModelProfiles.resolve(app,owner).thinking=="off","默认不思考")
            ModelProfiles.show(app)
        };shot("model-settings")
        ui{app.importSettings()};shot("import-options")
        ui{app.showSettings()};shot("settings")
        ui{val usage=ModelProfiles::class.java.getDeclaredMethod("usage",MainActivity::class.java).apply{isAccessible=true};usage.invoke(ModelProfiles,app)};shot("usage")
        ui{screen.pause()}
        val automaticBook=app.library.import(owner,Novel("自动书志增量测试",listOf(Chapter("第一章", "林舟走进书店。".repeat(300))),"UTF-8"),"automatic unique fixture ${System.nanoTime()}".toByteArray(),"automatic-fixture.txt")
        var automaticCalls=0
        val transport:(JSONObject)->JSONObject={automaticCalls++;JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","stop").put("message",JSONObject().put("content","{\"entries\":[]}"))))}
        ui{ChronicleTasks.maybe(app,automaticBook,Position(0,automaticBook.chapters[0].text.length),true,transport)}
        Thread.sleep(200);verify(automaticCalls==0,"关闭书志开关禁止自动请求")
        ui{app.prefs.edit().putBoolean("auto_chronicles:$owner",true).apply();ChronicleTasks.maybe(app,automaticBook,Position(0,automaticBook.chapters[0].text.length),true,transport)}
        val completed=System.currentTimeMillis()+5000
        while(!ChronicleTasks.status(app,owner,automaticBook.id).startsWith("已整理")&&System.currentTimeMillis()<completed){test.waitForIdleSync();Thread.sleep(50)}
        verify(automaticCalls==1&&ChronicleTasks.status(app,owner,automaticBook.id).startsWith("已整理"),"自动书志任务完成并保存状态")
        ui{ChronicleTasks.maybe(app,automaticBook,Position(0,automaticBook.chapters[0].text.length),true,transport)}
        Thread.sleep(200);verify(automaticCalls==1,"重新打开已处理章节不重复消耗 token")
        ui{app.prefs.edit().putBoolean("auto_chronicles:$owner",false).apply()}
        val parent=app.library.sourceFile(owner,automaticBook.id).parentFile!!
        File(parent,"import-job.json").writeText("{\"status\":\"complete\",\"fresh\":true}")
        File(parent,"readable-latest.json").writeText(JSONObject().put("snapshot","qa-consumed").put("chapters",JSONArray().put(JSONObject().put("title","旧章名").put("text",automaticBook.chapters[0].text))).toString())
        ImportTasks.markApplied(app,owner,automaticBook.id)
        ui{automaticBook.chapters=automaticBook.chapters.map{it.copy(title="手动补全的章名")};automaticBook.revision++;app.library.save(owner,automaticBook);ImportTasks.commitLatest(app,automaticBook.id)}
        verify(app.library.get(owner,automaticBook.id)!!.chapters[0].title=="手动补全的章名","已消费整理结果不覆盖后续标题编辑")
        verify(JSONObject(File(parent,"processed-v${automaticBook.revision}.json").readText()).getJSONArray("chapters").getJSONObject(0).getString("title")=="手动补全的章名","新章名与目录写入本地正文")

        ui{screen.stop();app.reader=null;app.secrets.remove("model-key:$owner:qa")}
        val usage=app.getSharedPreferences("ai-usage:$owner",0)
        val report=JSONObject().put("checks",checks).put("local_ms",localMs).put("real_ai_ms",realMs).put("local_chapters",local.chapters.size).put("ai_chapters",optimized.chapters.size).put("usage",JSONArray(usage.getString("entries","[]")))
        report.put("directory",JSONArray(optimized.chapters.map{JSONObject().put("title",it.title).put("length",it.text.length).put("start",it.text.take(90))}))
        File(app.filesDir,"qa/112-report.json").writeText(report.toString(2))
        return "$checks checks passed; local ${localMs}ms, AI ${realMs}ms; chapters ${local.chapters.size} -> ${optimized.chapters.size}"
    }
}
