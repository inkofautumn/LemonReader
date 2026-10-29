package cn.jiangcheng.lemon

import android.app.Instrumentation
import cn.jiangcheng.lemon.data.Book
import cn.jiangcheng.lemon.reader.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException

/** Actual Android SQLite and disk caches; model responses are deliberately controlled fixtures. */
object Revision024Test {
    fun run(i:Instrumentation):String {
        val ctx=i.targetContext;val owner="qa024-${System.currentTimeMillis()}";var checks=0
        fun verify(ok:Boolean,message:String){check(ok){message};checks++}
        fun reply(content:String,body:JSONObject)=JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","stop").put("message",JSONObject().put("content",content))))
            .put("usage",JSONObject().put("prompt_tokens",ReadingTurnBudget.estimate(body)).put("completion_tokens",120))
        val past="林舟交出铜钥匙，为了救出被困的妹妹。守卫于是打开了地牢。"
        val visible="林舟来到青石桥，等候老友的消息。"
        val secret="后文秘密红龙原来是妹妹。"
        val chapters=listOf(Chapter("序章","漫长的往事。"),Chapter("第一章 铜钥匙",past),Chapter("第二章 青石桥",visible+secret))
        val boundary=Position(2,visible.length);val book=Book(id="fixture",title="回归故事",chapters=chapters,revision=7)
        val cfg=AiConfig("https://example.com/v1","answer","fixture",context=ctx,owner=owner)
        val helper=cfg.copy(model="helper")
        val p=NovelRetrieval.plan("第一章林舟交出铜钥匙的缘由是什么？","",chapters,boundary,emptyList(),emptyList())
        verify(p.chapter==1,"序章令第一章定位错位")
        NovelRetrieval(chapters,boundary,ctx,owner,"fixture",7).use{index->
            verify(index.rebuiltChapters==3,"初次未建索引")
            val found=index.pack(index.search(p),3800,query=p.queries.first());verify(found.any{it.text.contains(past)},"旧剧情未召回")
            verify(found.all{!it.text.contains(secret)},"旧剧情检索泄漏后文")
        }
        NovelRetrieval(chapters,boundary,ctx,owner,"fixture",7).use{verify(it.rebuiltChapters==0,"热检索重复重建")}
        NovelRetrieval(chapters,boundary,ctx,owner,"fixture",8).use{verify(it.rebuiltChapters==3,"版本未使索引失效")}
        val changed=chapters.toMutableList().apply{this[1]=Chapter("第一章 铜钥匙",past+"城门已开。")}
        NovelRetrieval(changed,boundary,ctx,owner,"fixture",8).use{verify(it.rebuiltChapters==1,"同版本改文复用过期索引")}
        // Build a later cache first, then rewind within the same text chunk in twenty different places.
        repeat(20){n->val prefix="第${n}位行人走过青石桥。".repeat(n+1);val text=prefix+secret
            val cs=listOf(Chapter("本章",text));val id="fence$n"
            NovelRetrieval(cs,Position(0,text.length),ctx,owner,id,1).close()
            NovelRetrieval(cs,Position(0,prefix.length),ctx,owner,id,1).use{index->
                val plan=NovelRetrieval.plan("红龙妹妹的秘密是什么","",cs,Position(0,prefix.length),emptyList(),emptyList())
                verify(index.search(plan).isEmpty(),"第${n}个边界从缓存后半块召回秘密")
                verify(index.current().all{it.end<=prefix.length&&!it.text.contains("红龙")},"第${n}个邻段越界")
            }
        }
        val person=Chronicle(kind="人物",title="林舟",content="",chapter=1,offset=past.length,revision=7,entity="hero")
        val alias=person.copy(title="小舟",entity="hero");val future=person.copy(title="红龙",chapter=2,offset=visible.length+secret.length)
        val follow=NovelRetrieval.plan("他为什么交出钥匙？","",chapters,boundary,listOf(person,alias,future),listOf("小舟当时在哪里？"))
        verify(follow.names.containsAll(listOf("林舟","小舟"))&&!follow.names.contains("红龙"),"别名或代词解析泄漏/断线")
        fun entry(n:Int,mode:String="伴读",position:Position=Position(1,past.length))=JSONObject().put("question","林舟的问题$n").put("answer","守卫打开地牢$n").put("mode",mode).put("revision",7).put("chapter",position.chapter).put("offset",position.offset)
        val history=(0..7).map{entry(it)}
        val window=ConversationMemory.window(ctx,owner,book.id,"thread","伴读",7,boundary,history+entry(9,"天机")+entry(10,position=Position(2,chapters[2].text.length)))
        verify(window.recent.size==4&&window.older.size==4,"会话未保留四个完整安全轮次")
        ConversationMemory.save(ctx,owner,book.id,"thread","伴读",7,boundary,window.older,"林舟正在追查钥匙")
        verify(ConversationMemory.window(ctx,owner,book.id,"thread","伴读",7,boundary,history).summary.isNotBlank(),"会话缓存未复用")
        verify(ConversationMemory.window(ctx,owner,book.id,"thread","伴读",7,Position(1,past.length),history).summary.isEmpty(),"回退后会话记忆未失效")
        verify(ConversationMemory.window(ctx,owner,book.id,"other","伴读",7,boundary,history).summary.isEmpty(),"新会话混入记忆")
        verify(ConversationMemory.window(ctx,owner,book.id,"thread","伴读",7,boundary,history,"new-model").summary.isEmpty(),"换辅助模型后仍沿用旧摘要")
        verify(ConversationMemory.window(ctx,owner,book.id,"thread","天机",7,boundary,history).recent.isEmpty(),"模式串线")
        val requests=mutableListOf<JSONObject>();val models=mutableListOf<String>()
        val fake:(AiConfig,JSONObject,((String)->Unit)?)->JSONObject={config,body,stream->
            requests.add(body);models.add(config.model);verify(!body.toString().contains(secret),"某次辅助或回答调用泄漏后文")
            val user=JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content"))
            val response=if(user.has("候选原文")){
                val candidates=user.getJSONArray("候选原文");val id=(0 until candidates.length()).map{candidates.getJSONObject(it)}.first{it.getString("text").contains("铜钥匙")}.getString("id")
                reply(JSONObject().put("sufficient",true).put("ids",JSONArray().put(id)).put("queries",JSONArray()).toString(),body)
            }else if(user.has("新增对话")){reply("{\"memory\":\"读者正在追问林舟交出铜钥匙的缘由。\"}",body)}else{
                val material=user.getString("允许的原文");val id=Regex("\\[(E\\d+_\\d+_\\d+)\\]").findAll(material).first{it.groupValues[1].startsWith("E2_")}.groupValues[1]
                reply("ANSWER\n他是为救妹妹才交出钥匙。原文说“林舟交出铜钥匙”[$id]。",body)
            }
            stream?.invoke(response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"));response
        }
        val result=ReadingAssistant.response(cfg,helper,book,boundary,"第一章林舟交出铜钥匙是为了什么？","伴读","闲谈","t",emptyList(),call=fake)
        verify(!result.more&&result.answer.contains("第一章 铜钥匙"),"有效证据未形成回答")
        verify(models==listOf("helper","answer"),"辅助与回答模型路由错误：$models")
        verify(result.usage.getInt("calls")==2&&result.usage.getInt("input")<=6000,"总预算错误")
        val remembered=ReadingAssistant.response(cfg,helper,book,boundary,"这段是什么意思？","伴读","闲谈","remembered",history,past,call=fake)
        verify(!remembered.more&&remembered.usage.getInt("calls")==1,"眼前解读被旧轮次压缩阻塞")
        val older=ReadingAssistant.response(cfg,helper,book,boundary,"第一章林舟交出铜钥匙是为了什么？","伴读","闲谈","remembered",history,call=fake)
        verify(!older.more&&older.usage.getInt("calls")==3,"前文检索、旧轮次摘要、回答未共同计费")
        verify(ConversationMemory.window(ctx,owner,book.id,"remembered","伴读",7,boundary,history,"1|${helper.base}|${helper.model}").summary.contains("林舟"),"模型生成的安全会话记忆未保存")
        val followHistory=listOf(entry(1).put("question","谁交出了钥匙？").put("answer","林舟交出了钥匙。"))
        val followed=ReadingAssistant.response(cfg,helper,book,boundary,"他为什么交出铜钥匙？","伴读","闲谈","follow",followHistory,call=fake)
        verify(!followed.more&&requests[requests.size-2].toString().contains("旧回答"),"追问未利用上轮答复消除指代")
        var streamed=""
        val invalid=ReadingAssistant.response(cfg,helper,book,boundary,"这段是什么意思？","伴读","闲谈","x",emptyList(),past,call={_,body,stream->val r=reply("ANSWER\n“这是伪造的引文”[E2_0_${past.length}]。",body);stream?.invoke("错误内容");r},onText={streamed=it})
        verify(invalid.more&&streamed.isEmpty(),"未经引文校验的答案显示给用户")
        var attempts=0
        val missing=ReadingAssistant.response(cfg,helper,book,boundary,"皇帝的火箭为什么坠落","伴读","闲谈","m",emptyList(),call={_,body,_->attempts++;reply("{\"sufficient\":false,\"queries\":[\"不存在的火箭\"],\"ids\":[]}",body)})
        verify(missing.more&&attempts<=3,"缺证据仍回答或无限补查")
        val cancellation=AiCancellation().apply{cancel()}
        verify(runCatching{ReadingAssistant.response(cfg,helper,book,boundary,"钥匙","伴读","闲谈","c",emptyList(),cancel=cancellation,call=fake)}.exceptionOrNull() is CancellationException,"取消未停止本地/模型工作")
        val budget=ReadingTurnBudget(500,1000,1);val body=JSONObject().put("messages",JSONArray()).put("max_tokens",300)
        budget.begin(body);budget.failed(300);verify(!budget.canSpend(body)&&budget.output==300&&budget.estimated,"失败调用未计预算")
        // Long chapter work resumes across bounded jobs and invalidates on source changes.
        val long="林舟交出铜钥匙，守卫打开地牢。他救出了妹妹。\n".repeat(420)
        val longBook=book.copy(id="summary",chapters=listOf(Chapter("长章",long+secret)))
        var recapCalls=0;var recap:ChapterRecap.Result;var runs=0
        do{recap=ChapterRecap.run(cfg,helper,longBook,Position(0,long.length),call={_,b->recapCalls++;verify(!b.toString().contains(secret),"章节回顾超出范围");reply("林舟用钥匙救出妹妹，事情有了转机。",b)});verify(recap.usage.getInt("calls")<=4,"回顾单轮超出调用限制");runs++}while(!recap.complete&&runs<8)
        verify(recap.complete&&runs>1,"长章不可续接")
        val cached=ChapterRecap.run(cfg,helper,longBook,Position(0,long.length),call={_,_->error("已完成总结重复付费")})
        verify(cached.complete&&cached.usage.getInt("calls")==0,"总结缓存未复用")
        val report=JSONObject().put("checks",checks).put("summaryCalls",recapCalls).put("summaryRuns",runs)
        // Original synthetic lexical-location regression, deliberately not a semantic answer accuracy score.
        val books=JSONArray(i.context.assets.open("retrieval-fixtures.json").bufferedReader().readText());val rows=JSONArray();var oldHits=0;var newHits=0;var count=0
        for(b in 0 until books.length()){
            val test=books.getJSONObject(b);val cs=test.getJSONArray("chapters").let{a->(0 until a.length()).map{j->a.getJSONObject(j).let{Chapter(it.getString("title"),it.getString("text"))}}}
            val edge=Position((cs.size*.8).toInt(),cs[(cs.size*.8).toInt()].text.length);val start=System.nanoTime()
            NovelRetrieval(cs,edge,ctx,owner,"real$b",1).use{index->
                val coldMs=(System.nanoTime()-start)/1000000;val questions=test.getJSONArray("questions")
                for(q in 0 until questions.length()){val item=questions.getJSONObject(q);val question=item.getString("question");val cue=item.getString("cue");val t=System.nanoTime()
                    val found=index.pack(index.search(NovelRetrieval.plan(question,"",cs,edge,emptyList(),emptyList())),3800,query=question)
                    val newHit=found.any{it.chapter==item.getInt("chapter")&&it.start<=item.getInt("offset")&&it.end>=item.getInt("offset")+cue.length}
                    val newMs=(System.nanoTime()-t)/1000000;val oldStart=System.nanoTime()
                    val oldHit=ReadingContext.build(cs,edge,question,5200).contains(cue)
                    if(newHit)newHits++;if(oldHit)oldHits++;count++
                    rows.put(JSONObject().put("book",test.getString("name")).put("question",question).put("new",newHit).put("old",oldHit).put("queryMs",newMs).put("oldQueryMs",(System.nanoTime()-oldStart)/1000000).put("coldMs",coldMs))
                }
            }
            val warm=System.nanoTime();NovelRetrieval(cs,edge,ctx,owner,"real$b",1).use{verify(it.rebuiltChapters==0,"测试小说热检索重复建库")};report.put("warm$b",(System.nanoTime()-warm)/1000000)
        }
        report.put("lexicalCases",count).put("newHits",newHits).put("oldHits",oldHits).put("rows",rows).put("checks",checks)
        File(ctx.getExternalFilesDir(null),"audit024/report.json").apply{parentFile!!.mkdirs()}.writeText(report.toString(2))
        verify(count==100,"测试素材不完整")
        return "$checks checks passed; lexical retrieval $newHits/$count (old $oldHits); controlled models only"
    }
}
