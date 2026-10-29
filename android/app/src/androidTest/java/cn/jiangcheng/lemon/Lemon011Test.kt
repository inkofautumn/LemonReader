package cn.jiangcheng.lemon

import android.app.Instrumentation
import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.widget.*
import cn.jiangcheng.lemon.data.*
import cn.jiangcheng.lemon.reader.*
import org.json.*

/** Contract + interaction regression; fake responses avoid consuming a user's paid API. */
object Lemon011Test {
    fun run(i:Instrumentation,app:MainActivity):String {
        var checks=0
        fun verify(ok:Boolean,message:String){check(ok){message};checks++}
        fun response(content:String,reason:String="stop")=JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason",reason).put("message",JSONObject().put("content",content))))
        val cfg=AiConfig("https://example.com/v1","custom/model","fixture",provider="custom")
        val request=JSONObject().put("max_tokens",900).put("response_format",JSONObject().put("type","json_object")).put("messages",JSONArray().put(JSONObject().put("role","user").put("content","你好")))
        val normal=ModelProtocol.body(cfg,request,true)
        verify(!normal.has("enable_thinking")&&!normal.has("thinking")&&!normal.has("stream_options")&&!normal.has("response_format"),"自定义接口被注入专用参数")
        verify(request.has("response_format")&&!request.has("model"),"改写了调用方原请求")
        val modern=ModelProtocol.body(cfg.copy(tokenField="max_completion_tokens",jsonOutput=true,streamUsage=true),request,true)
        verify(modern.optInt("max_completion_tokens")==900&&!modern.has("max_tokens")&&modern.has("stream_options")&&modern.has("response_format"),"兼容开关无效")
        verify(ModelProtocol.body(cfg.copy(reasoningProtocol="qwen",thinking="high"),request,false).optBoolean("enable_thinking"),"千问思考格式不正确")
        verify(ModelProtocol.body(cfg.copy(reasoningProtocol="deepseek"),request,false).getJSONObject("thinking").getString("type")=="disabled","DeepSeek 关闭思考无效")
        verify(ModelProtocol.normalizeBase(" https://example.com/v1/chat/completions/ ")=="https://example.com/v1","完整接口地址未规范化")
        listOf("http://example.com","https://key@example.com/v1","https://example.com?key=x").forEach{verify(runCatching{ModelProtocol.normalizeBase(it)}.isFailure,"接受了不安全地址")}
        verify(ModelProtocol.models("m1，m2\nm1;m3")==listOf("m1","m2","m3"),"多模型输入未去重")
        verify(ModelOutput.json(response("```json\n{\"entries\":[]}\n```")).has("entries"),"合法 JSON 围栏被拒绝")
        verify(runCatching{ModelOutput.json(response("{\"entries\":[]}","length"))}.isFailure,"截断回答误判成功")
        val sse="data: {\"choices\":[{\"delta\":{\"content\":\"渡口\"},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: {\"choices\":[],\"usage\":{\"total_tokens\":12}}\n\ndata: [DONE]\n\n"
        val streamed=ChatStream.read(sse.reader().buffered()){}
        verify(streamed.getJSONObject("usage").getInt("total_tokens")==12,"流尾用量丢失")
        verify(runCatching{ChatStream.read(sse.replace("stop","length").reader().buffered()) {}}.isFailure,"流式截断误判成功")
        verify(runCatching{ChatStream.read("data: {\"choices\":[{\"delta\":{\"content\":\"半句\"}}]}\n\n".reader().buffered()) {}}.isFailure,"断流误判完整回答")

        val ch=listOf(Chapter("初见","沈青把银钥匙交给江宁。"+"渡口风声。".repeat(800)),Chapter("来信","江宁在客栈拆开信封。"+"信上没有落款。".repeat(50)+"眼前的独特句。"+"禁止泄露的后文"),Chapter("未知章节","秘密结局不可发送"))
        val boundary=Position(1,ch[1].text.indexOf("禁止泄露"))
        val context=ReadingContext.build(ch,boundary,"沈青的银钥匙",1800)
        verify(context.contains("银钥匙"),"历史相关证据没有召回")
        verify(context.contains("眼前的独特句"),"当前页上下文被历史资料挤掉")
        verify(!context.contains("禁止泄露")&&!context.contains("秘密结局"),"检索越过已读边界")
        verify(context.length<=1800,"上下文超过预算")
        val prompts=Storyteller.request(ch,boundary,"回顾一下","伴读","闲谈").toString()
        verify(!prompts.contains("禁止泄露")&&!prompts.contains("秘密结局"),"说书人请求包含后文")
        verify(!Storyteller.request(ch,boundary,"解读这段","伴读","闲谈",quote="禁止泄露的后文").toString().contains("禁止泄露"),"引用绕过了阅读边界")
        verify(prompts.contains("不自称AI")&&prompts.contains("来龙去脉"),"说书口吻约束缺失")
        val longChapter=Chapter("长章","开篇关键事件。"+"正文".repeat(5500)+"结尾关键事件。")
        val summary=Storyteller.request(listOf(longChapter),Position(0,longChapter.text.length),"总结本章","伴读","闲谈",summary=true).toString()
        verify(summary.contains("开篇关键事件")&&summary.contains("结尾关键事件"),"章节总结静默丢弃开篇")
        val segmented="甲".repeat(2800)+"。\n"+"乙".repeat(2800)
        verify(ReadingSegments.end(segmented,0,segmented.length)==2802,"未按段落结尾切片")
        verify(ReadingSegments.end(segmented,0,600)==600,"切片越界")

        val text="江宁遇见沈青。江宁又遇见沈青。阿宁正是江宁。未读秘密"
        val seen=Position(0,text.indexOf("未读秘密"));val chapters=listOf(Chapter("渡口",text))
        val a=Chronicle(kind="人物",title="江宁",content="初遇",chapter=0,offset=8,evidence="江宁遇见沈青。",manual=true)
        val later=a.copy(id="later",content="再次相遇",offset=16,evidence="江宁又遇见沈青。",manual=false)
        val alias=a.copy(id="alias",title="阿宁",content="别名",offset=22,evidence="阿宁正是江宁。")
        val future=a.copy(id="future",title="隐秘人物",chapter=1,offset=1)
        val merged=ReaderCore.mergeChronicles(listOf(a),listOf(a.copy(content="覆盖历史",manual=false),later,later.copy(id="duplicate")))
        verify(merged.size==2&&merged.first().content=="初遇","重复覆盖快照或丢失后续事实")
        val catalog=ChronicleCatalog.items(merged+alias+future,chapters,seen,1)
        verify(catalog.size==2,"同名人物未归组或泄露后文人物: ${catalog.map{it.key}} boundary=$seen alias=${alias.offset}")
        val person=catalog.first{it.entry.title=="江宁"};val other=catalog.first{it.entry.title=="阿宁"}
        verify(person.frequency==3&&person.records.size==2,"提及次数或多条证据错误")
        verify(ChronicleCatalog.sort(catalog,"提及最多").first().entry.title=="江宁","按频次排序错误")
        verify(ChronicleCatalog.sort(catalog,"首次出现").first().first.offset==0,"首次出现排序错误")
        verify(ChronicleCatalog.sort(catalog,"最近提及").first().entry.title=="江宁","最近提及排序错误")
        verify(ChronicleCatalog.sort(catalog,"名称","再次").single().entry.title=="江宁","内容搜索无效")
        val all=ChronicleCatalog.merge(merged+alias,person,other)
        val combined=ChronicleCatalog.items(all,chapters,seen,1).single()
        verify(combined.names.size==2&&combined.records.size==3&&combined.frequency==4,"别名合并丢失历史或重复计数")
        val serialized=Library.toJson(Book(title="合并人物",chapters=chapters,chronicles=all))
        verify(Library.fromJson(Library.toJson(Book(title="边界",chapters=chapters,chronicles=listOf(a.copy(contextEnd=20))))).chronicles.single().contextEnd==20,"书志知识边界没有保存")
        verify(Library.fromJson(serialized).chronicles.map{it.entity}==all.map{it.entity},"合并身份不能持久化")
        val deleted=ChronicleCatalog.delete(all,combined)
        verify(ReaderCore.mergeChronicles(deleted,listOf(later.copy(id="new"))).size==deleted.size,"删除人物被后台复活")
        val extraction=JSONObject().put("entries",JSONArray().put(JSONObject().put("kind","人物").put("name","江宁").put("identity","收信人").put("evidence","江宁遇见沈青。")))
        val entries=ChronicleTasks.extract(cfg,0,0,text,1,call={response(extraction.toString())})
        verify(ChronicleCatalog.visible(entries,Position(0,8),1).isEmpty(),"回退阅读位置后泄露整理片段后半部的资料")
        verify(entries.single().offset=="江宁遇见沈青。".length&&entries.single().title=="江宁","书志出处位置错误")
        verify(runCatching{ChronicleTasks.extract(cfg,0,0,text,1,call={response(extraction.toString().replace("江宁遇见沈青。","虚构的证据"))})}.isFailure,"接受了没有原文依据的记录")
        var calls=0
        val cacheCfg=cfg.copy(context=app,owner="qa011-cache")
        val cacheBookId="cache-${java.util.UUID.randomUUID()}"
        repeat(2){ChronicleTasks.extract(cacheCfg,0,0,text,1,bookId=cacheBookId,call={calls++;response(extraction.toString())})}
        verify(calls==1,"重试重复消耗模型调用")

        val owner="qa011-config";val profile=JSONObject().put("id","fixture-profile").put("name","自定义").put("base",cfg.base).put("models","one,two").put("provider","custom").put("json_output",true)
        app.prefs.edit().putString("models:$owner",JSONArray().put(profile).toString()).putString("route:$owner:deep","fixture-profile").putString("route-model:$owner:deep","two").commit()
        ModelProfiles.assignQuickRoutes(app,owner)
        verify(ModelProfiles.resolve(app,owner,"deep").model=="two","保存连接重置了任务模型")
        verify(ModelProfiles.resolve(app,owner,"record").jsonOutput,"任务模型丢失兼容配置")

        fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
        fun main(f:()->Unit){i.runOnMainSync(f);i.waitForIdleSync();Thread.sleep(220)}
        lateinit var reader:ReaderScreen
        val book=Book(title="交互回归",chapters=chapters,chronicles=all,position=seen)
        main{app.profileId="qa011-ui";app.prefs.edit().putBoolean("auto_chronicles:${app.profileId}",false).commit();reader=ReaderScreen(app,book);app.reader=reader;reader.show();reader.showCollection("书志")}
        val field=ReaderScreen::class.java.getDeclaredField("menu").apply{isAccessible=true}
        val dialog=field.get(reader) as Dialog
        lateinit var search:EditText
        main{search=views(dialog.window!!.decorView).filterIsInstance<EditText>().first{it.hint.toString().contains("搜索人物")};search.setText("江宁");reader.refreshChronicles()}
        Thread.sleep(500);i.waitForIdleSync()
        main{verify(search.isAttachedToWindow&&search.text.toString()=="江宁","后台刷新破坏搜索输入");verify(views(dialog.window!!.decorView).filterIsInstance<TextView>().any{it.text=="全部"&&it.isSelected},"书志分类缺少选中状态")}
        main{dialog.dismiss();ReaderScreen::class.java.getDeclaredMethod("chat",Position::class.java,String::class.java,String::class.java).apply{isAccessible=true}.invoke(reader,seen,"","")}
        val chat=field.get(reader) as Dialog
        main{val labels=views(chat.window!!.decorView).filterIsInstance<TextView>();verify(labels.any{it.text=="伴读"}&&labels.none{it.text=="温故"},"伴读温故未合并");labels.first{it.text=="回顾一下"}.performClick();verify(views(chat.window!!.decorView).filterIsInstance<EditText>().any{it.text.contains("前因")},"回顾入口没有填入问题");chat.dismiss();reader.stop();app.reader=null;app.showHome()}
        return "$checks checks passed: providers, scope, streaming, catalog, persistence, caching, live UI"
    }
}
