package cn.jiangcheng.lemon

import android.app.Instrumentation
import cn.jiangcheng.lemon.data.Book
import cn.jiangcheng.lemon.reader.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Replays the reported screen and short correction using controlled helper responses. */
object Clarification025Test {
    fun run(i:Instrumentation):String {
        val failures=mutableListOf<String>();var checks=0
        fun verify(ok:Boolean,message:String){checks++;if(!ok)failures.add(message)}
        fun reply(text:String)=JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","stop").put("message",JSONObject().put("content",text))))
        val prompt="结合我读到的位置，回顾与眼前情节有关的前因"
        val legacy="你指的是哪位人物、哪一次事件？补充一个名字或引用那段原文，我再接着查。"
        val scene="而身为这一切剧变的地盘主人，刑明亮居然是事情结束之后才知道。\n第一个赶来通知刑明亮的人居然是这个御兽宗的宗主。"
        val prefix="远处的集市依旧喧闹，商贩在街边吆喝，行人走过长街。".repeat(100)
        val visible=prefix+scene;val secret="未读秘密：刑明亮明天会离开城池。"
        val book=Book(id="qa025",title="澄清回归",revision=1,chapters=listOf(Chapter("第203章",visible+secret)))
        val edge=Position(0,visible.length)
        val cfg=AiConfig("https://example.com/v1","answer","fixture",context=i.targetContext,owner="qa025")
        fun entry(question:String)=JSONObject().put("question",question).put("answer",legacy).put("revision",1).put("mode","伴读").put("chapter",0).put("offset",edge.offset)
        var helperHasScene=false;var finalCalls=0;var followQuestion=""
        val fake:(AiConfig,JSONObject,((String)->Unit)?)->JSONObject={_,body,_->
            verify(!body.toString().contains(secret),"请求泄漏未读正文")
            val user=JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content"))
            if(user.has("候选原文")){
                val snippets=user.getJSONArray("候选原文")
                val rows=(0 until snippets.length()).map{snippets.getJSONObject(it)}
                helperHasScene=helperHasScene||rows.any{it.getString("text").contains(scene)}
                followQuestion=user.getString("问题")
                val id=rows.firstOrNull()?.optString("id").orEmpty()
                reply(JSONObject().put("sufficient",true).put("ids",JSONArray().put(id)).put("queries",JSONArray()).put("clarification",JSONObject.NULL).toString())
            }else{
                finalCalls++
                val material=user.getString("允许的原文")
                val blocks=Regex("\\[(E\\d+_\\d+_\\d+)\\]\\n([^【]*)").findAll(material).toList()
                val id=blocks.firstOrNull{it.groupValues[2].contains("刑明亮居然是事情结束之后才知道") }?.groupValues?.get(1)
                if(id==null)reply("NEED_MORE")else reply("ANSWER\n原文说“刑明亮居然是事情结束之后才知道”[$id]，他是事后才得到消息的。")
            }
        }
        val first=ReadingAssistant.response(cfg,cfg,book,edge,prompt,"伴读","闲谈","first",emptyList(),call=fake)
        verify(helperHasScene,"回顾助手没看到当前页的完整场景")
        verify(finalCalls==1&&!first.more&&first.answer!=legacy,"JSON null 被当成澄清而跳过正式回答")
        finalCalls=0
        val corrected=ReadingAssistant.response(cfg,cfg,book,edge,"刑明亮","伴读","闲谈","old-loop",listOf(entry(prompt),entry("邢明亮")),call=fake)
        verify(followQuestion.contains(prompt)&&followQuestion.contains("刑明亮"),"补充人名未接续原来的回顾任务")
        verify(finalCalls==1&&!corrected.more&&corrected.answer!=legacy,"补充正确人名后依旧重复追问")
        // Providers differ in how optional empty fields are serialized.
        for(value in listOf<Any>(JSONObject.NULL,"null","", "  ","无",false,"无需澄清","仍需说明人物")){
            finalCalls=0
            val r=ReadingAssistant.response(cfg,cfg,book,edge,prompt,"伴读","闲谈","optional-$value",emptyList(),call={c,b,s->
                val response=fake(c,b,s)
                if(b.has("response_format")){
                    val message=response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                    message.put("content",JSONObject(message.getString("content")).put("clarification",value).toString())
                }
                response
            })
            verify(finalCalls==1&&!r.more,"可回答结果被可选澄清字段阻断：$value")
            verify(r.usage.getInt("calls")<=3&&r.usage.getInt("input")<=r.usage.getInt("inputLimit"),"空字段用例超预算")
        }
        var helperCalls=0
        val retry=ReadingAssistant.response(cfg,cfg,book,edge,prompt,"伴读","闲谈","expand",emptyList(),call={c,b,s->
            if(b.has("response_format")&&helperCalls++==0)reply("""{"sufficient":false,"ids":[],"queries":["刑明亮 御兽宗 通知"],"clarification":"请给人名"}""")else fake(c,b,s)
        },visibleStart=Position(0,prefix.length))
        verify(!retry.more&&retry.queries.any{it.contains("御兽宗")},"助手要求补查时未先执行有效查询")
        verify(retry.usage.getInt("calls")==2,"当前页补查与回答调用数失控")
        // Even a stubborn helper cannot force another name request for a page-based shortcut.
        finalCalls=0
        val grounded=ReadingAssistant.response(cfg,cfg,book,edge,prompt,"伴读","闲谈","stubborn",emptyList(),call={c,b,s->
            if(b.has("response_format"))reply("""{"sufficient":false,"ids":[],"queries":[],"clarification":"请说明是谁"}""")else fake(c,b,s)
        },visibleStart=Position(0,prefix.length))
        verify(finalCalls==1&&grounded.answer!=legacy,"有当前正文仍被助手反复追问")
        verify(!grounded.more&&grounded.sources.any{it.text.contains(scene)},"当前页没有保留到最后回答")

        val unsure:(AiConfig,JSONObject,((String)->Unit)?)->JSONObject={_,b,_->
            if(b.has("response_format"))reply("""{"sufficient":false,"ids":[],"queries":[],"clarification":"无法确认"}""")else reply("NEED_MORE")
        }
        val typo=ReadingAssistant.response(cfg,cfg,book,edge,"邢明亮","伴读","闲谈","typo",listOf(entry(prompt)),call=unsure)
        verify(typo.answer!=legacy&&typo.more&&typo.pendingQuestion==prompt,"错字补充后既重复追问又丢失待解问题")
        val pendingEntry=entry("邢明亮").put("answer",typo.answer).put("incomplete",true).put("pendingQuestion",typo.pendingQuestion)
        val afterTypo=ReadingAssistant.response(cfg,cfg,book,edge,"刑明亮","伴读","闲谈","typo",listOf(entry(prompt),pendingEntry),call=fake)
        verify(!afterTypo.more&&afterTypo.pendingQuestion.isBlank(),"纠正称呼后待解状态未完成")
        verify(followQuestion.contains(prompt)&&followQuestion.contains("刑明亮"),"不完整回答后的补充丢失原任务")
        val ambiguous=ReadingAssistant.response(cfg,cfg,book,edge,"他呢","伴读","闲谈","ambiguous",emptyList(),call=unsure)
        verify(ambiguous.answer==legacy&&ambiguous.pendingQuestion=="他呢","真正无指代上下文时未保留澄清状态")
        val named=ReadingAssistant.response(cfg,cfg,book,edge,"刑明亮","伴读","闲谈","ambiguous",listOf(entry("他呢").put("pendingQuestion","他呢")),call=unsure)
        verify(named.more&&named.answer!=legacy,"读者已补充主体却重复同一句追问")
        val history=listOf(entry(prompt),entry("邢明亮"))
        verify(ConversationIntent.resolve("另外，御兽宗为什么撤退？",history).question=="另外，御兽宗为什么撤退？","新问题错误继承旧任务")
        verify(ConversationIntent.resolve("刑明亮",emptyList()).question=="刑明亮","新会话错误继承待解任务")
        val unsafe=listOf(entry(prompt).put("mode","天机"),entry(prompt).put("revision",2),entry(prompt).put("offset",edge.offset+10))
        verify(ConversationIntent.resolve("刑明亮",ConversationMemory.safe(unsafe,1,edge,"伴读")).question=="刑明亮","待解问题穿透阅读/版本/模式隔离")
        NovelRetrieval(book.chapters,edge).use{index->
            val pin=index.at(0,prefix.length,edge.offset)!!
            val old=index.at(0,0,800,100000.0)!!
            val packed=index.pack(listOf(old,pin),450,listOf(old.id),prompt,listOf(pin))
            verify(packed.any{it.text.contains(scene)}&&packed.sumOf{it.text.length+80}<=450,"紧预算下丢弃当前页或突破预算")
        }
        val report=JSONObject().put("checks",checks).put("failures",JSONArray(failures)).put("androidNullString",JSONObject("{\"clarification\":null}").optString("clarification"))
        File(i.targetContext.getExternalFilesDir(null),"audit025/clarification.json").apply{parentFile!!.mkdirs()}.writeText(report.toString(2))
        check(failures.isEmpty()){report.toString()}
        return "$checks clarification checks passed; controlled models"
    }
}
