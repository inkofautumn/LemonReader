package cn.jiangcheng.lemon

import android.app.Instrumentation
import cn.jiangcheng.lemon.reader.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

object Revision016Test {
    fun run(test:Instrumentation,app:MainActivity):Int{
        var checks=0
        fun verify(ok:Boolean,message:String){check(ok){message};checks++}
        fun waitFor(label:String,condition:()->Boolean){val end=System.currentTimeMillis()+30000;while(System.currentTimeMillis()<end){test.waitForIdleSync();if(condition())return;Thread.sleep(80)};error(label)}
        fun response(value:JSONObject)=JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","stop").put("message",JSONObject().put("content",value.toString()))))
        val cfg=AiConfig("http://127.0.0.1:18767","fixture","fixture")
        val source=buildString{for(ch in 1..6){append("第${ch}章 异步验收\n");repeat(40){append("这是第${ch}章的独立正文，灯火在雨夜里轻轻摇晃。".repeat(5)+"\n")}}}.toByteArray()
        var calls=0;var partialBeforeEnd=false;var feedbackSeen=false;var fail=true
        val cache=File(app.cacheDir,"agent-resume-${System.nanoTime()}.json")
        fun model(request:JSONObject):JSONObject{
            calls++;val input=JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"));feedbackSeen=input.optString("feedback")=="第二章边界要核对"
            if(calls==2&&fail)throw IllegalStateException("模拟断网")
            val fragments=input.getJSONArray("fragments");val headings=JSONArray()
            for(i in 0 until fragments.length()){val f=fragments.getJSONObject(i);val text=f.getString("text").trim();if(text.startsWith("第")&&text.contains("章 异步验收"))headings.put(JSONObject().put("id",f.getInt("id")).put("title",text))}
            return response(JSONObject().put("chapters",headings).put("edits",JSONArray()).put("trim",JSONArray()))
        }
        val agent=ImportAgent(cfg,::model);agent.restore(cache)
        verify(runCatching{agent.process(source,"异步.txt",ImportAgent.Options(false,true,false,false),"第二章边界要核对",{if(calls==1&&it.chapters.isNotEmpty())partialBeforeEnd=true})}.isFailure,"模拟第二批中断")
        verify(partialBeforeEnd,"全部完成前已发布完整章节")
        val before=calls;fail=false;val resumed=ImportAgent(cfg,::model);resumed.restore(cache)
        val result=resumed.process(source,"异步.txt",ImportAgent.Options(false,true,false,false),"第二章边界要核对")
        verify(result.chapters.size==6&&calls>before&&feedbackSeen,"恢复缓存并携带用户纠正意见")
        var secondCalls=0;val cached=ImportAgent(cfg,{secondCalls++;model(it)});cached.restore(cache);cached.process(source,"异步.txt",ImportAgent.Options(false,true,false,false),"第二章边界要核对")
        verify(secondCalls==0,"新实例恢复已完成请求且不重复调用模型")
        var scanned=0;var leaked=false
        val research=ResearchAgent(cfg){request->val data=JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"));val text=data.getString("source");scanned++;if(text.contains("未来秘密"))leaked=true
            response(JSONObject().put("matches",JSONArray().put(JSONObject().put("evidence","江水安静。").put("title","江边").put("reason","原文依据").put("kind","线索")).put(JSONObject().put("evidence","伪造证据").put("title","错误"))))}
        val found=research.search(listOf(Chapter("上文","江水安静。"+"前文。".repeat(2500)),Chapter("当前","江水安静。未来秘密")),Position(1,5),"江边")
        verify(scanned>=3&&!leaked,"模糊检索遍历多批且严格截断已读边界")
        verify(found.none{it.title=="错误"}&&found.all{it.evidence=="江水安静。"},"伪造原文依据被丢弃")
        val mobiFixtures=File(test.targetContext.getExternalFilesDir(null),"test-fixtures/mobi")
        for(name in listOf("sample-ncx.mobi","sample-cp1252.mobi","sample-unicode-huffdic.mobi","sample-unicode-uncompressed.mobi","sample-kf8.azw3")){
            val fixture=File(mobiFixtures,name);if(!fixture.isFile)continue
            val bytes=fixture.readBytes()
            val novel=ImportAgent(AiConfig("","","")).process(bytes,name,ImportAgent.Options(false,true,false,false))
            verify(novel.chapters.isNotEmpty()&&novel.chapters.sumOf{it.text.length}>100,"MOBI 本地解析 $name")
            if(name=="sample-ncx.mobi")verify(novel.chapters.size>1,"MOBI 自带目录拆分")
        }
        val epubBytes=java.io.ByteArrayOutputStream().also{out->java.util.zip.ZipOutputStream(out).use{zip->
            fun entry(name:String,value:String){zip.putNextEntry(java.util.zip.ZipEntry(name));zip.write(value.toByteArray());zip.closeEntry()}
            entry("META-INF/container.xml","<container><rootfiles><rootfile full-path='content.opf'/></rootfiles></container>")
            entry("content.opf","<package><metadata><title>测试目录</title></metadata><manifest><item id='a' href='body.xhtml'/><item id='nav' href='nav.xhtml' properties='nav'/></manifest><spine><itemref idref='a'/></spine></package>")
            entry("nav.xhtml","<html xmlns:epub='http://www.idpf.org/2007/ops'><nav epub:type='toc'><a href='body.xhtml#a'>原章名甲</a><a href='body.xhtml#b'>原章名乙</a></nav></html>")
            entry("body.xhtml","<html><body><h1 id='a'>原章名甲</h1><p>正文甲</p><h1 id='b'>原章名乙</h1><p>正文乙</p></body></html>")
        }}.toByteArray()
        val preserved=ImportAgent(AiConfig("","","")).process(epubBytes,"test.epub",ImportAgent.Options(false,true,false,false))
        verify(preserved.chapters.map{it.title}==listOf("原章名甲","原章名乙"),"EPUB 原目录无需模型")
        var splitRequested=false
        ImportAgent(cfg){request->val input=JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"));splitRequested=input.getBoolean("chapters");response(JSONObject().put("chapters",JSONArray()).put("edits",JSONArray()).put("trim",JSONArray()))}.process(epubBytes,"test.epub",ImportAgent.Options(false,true,false,false,preserveStructure=false))
        verify(splitRequested,"明确重识别 EPUB 时才启用模型章节处理")
        val protected=File(mobiFixtures,"sample-drm-v1.mobi")
        if(protected.isFile)verify(runCatching{MobiImporter.read(protected.readBytes(),"protected.mobi")}.exceptionOrNull()?.message?.contains("DRM")==true,"DRM 文件明确拒绝")
        verify(runCatching{MobiImporter.read(byteArrayOf(1,2,3),"broken.mobi")}.isFailure,"损坏 MOBI 不导致进程闪退")
        val existing=Novel("原书",listOf(Chapter("甲","第一段"),Chapter("乙","第二段")),"UTF-8")
        val tidyAgent=ImportAgent(cfg){request->
            val input=JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
            verify(!input.getBoolean("chapters")&&!input.getBoolean("encoding")&&input.getBoolean("tidy"),"仅格式优化的处理范围")
            response(JSONObject().put("chapters",JSONArray()).put("edits",JSONArray()).put("trim",JSONArray()))
        }
        verify(tidyAgent.process("一整本原文件".toByteArray(),"book.txt",ImportAgent.Options(false,false,false,true),existing=existing).chapters==existing.chapters,"单独格式优化保留当前目录")
        val previous=app.reader?.book
        val book=app.library.import(app.profileId,Novel("异步验收",listOf(ImportTasks.waiting),"待识别"),source,"异步验收.txt")
        app.prefs.edit().putString("ai_base:${app.profileId}",cfg.base).putString("ai_model:${app.profileId}",cfg.model).commit();app.secrets.set("ai_key:${app.profileId}",cfg.key)
        test.runOnMainSync{ImportTasks.start(app,book,ImportAgent.Options(false,true,false,false),fresh=true);app.showHome()}
        waitFor("未发布首批章节"){val b=app.library.get(app.profileId,book.id)!!;b.chapters.size>1&&b.chapters.last()==ImportTasks.waiting}
        verify(ImportTasks.state(app,app.profileId,book.id)!!.optString("status")=="running","离开导入页面仍在异步整理")
        test.runOnMainSync{app.openBook(book)};waitFor("异步阅读未打开"){app.reader?.book?.id==book.id}
        waitFor("异步整理未完成"){ImportTasks.state(app,app.profileId,book.id)?.optString("status")=="complete"}
        verify(app.reader!!.book.chapters.size==6&&app.reader!!.book.chapters.none{it==ImportTasks.waiting},"阅读中追加章节并移除待整理占位")
        test.runOnMainSync{app.reader!!.save()}
        verify(app.library.get(app.profileId,book.id)!!.chapters.size==6,"旧阅读会话保存不会覆盖新增章节")
        val stored=app.library.get(app.profileId,book.id)!!
        verify(File(app.library.sourceFile(app.profileId,book.id).parentFile,"processed-v${stored.revision}.json").isFile,"分批结果生成本地可读取的正文版本")
        test.runOnMainSync{if(previous!=null)app.openBook(previous)};if(previous!=null)waitFor("原阅读页未恢复"){app.reader?.book?.id==previous.id}
        return checks
    }
}
