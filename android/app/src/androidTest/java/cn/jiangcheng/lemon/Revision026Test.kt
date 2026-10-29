package cn.jiangcheng.lemon

import android.app.Instrumentation
import cn.jiangcheng.lemon.data.*
import cn.jiangcheng.lemon.reader.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Revision026Test {
    fun run(i:Instrumentation):String {
        var checks=0
        fun verify(ok:Boolean,why:String){check(ok){why};checks++}
        val dir=File(i.targetContext.getExternalFilesDir(null),"audit026").apply{mkdirs()}
        val text="高个子黑衣人说：我是黑乌鸦伍玉央。另一名黑衣人说：我是杀人蜂羿剑文。二人奉红老大之命，来飞天堡夺取箱子。宁大总管被二人重创，才逼问出他们的身份。"
        val e=NovelRetrieval.Evidence(0,0,text.length,text,"正文")
        val sources=listOf(e)
        for(raw in listOf(
            "ANSWER\n杀人蜂是羿剑文。[${e.id}]",
            "ANSWER：杀人蜂是羿剑文。【${e.id}】",
            "ANSWER\n杀人蜂是羿剑文。[[${e.id}]]",
            "杀人蜂是羿剑文。【正文】",
            "原文说\"我是杀人蜂羿剑文\"， [${e.id}]。",
            "原文说“我是杀人蜂 羿剑文”。[${e.id}]",
            "原文说「我是杀人蜂羿剑文」[${e.id}]",
            "原文说“我是杀人蜂羿剑文”。"
        ))verify(EvidenceAnswer.check(raw,sources).valid,"可定位的回答被拒绝：$raw")
        for(raw in listOf("ANSWER\n他是主角[E9_0_99]","ANSWER\n“他其实是红老大”[${e.id}]","NEED_MORE","他是羿剑文。"))
            verify(!EvidenceAnswer.check(raw,sources).valid,"无来源/伪造证据通过：$raw")
        val chunks=mutableListOf<String>();val stream=EvidenceAnswer.Stream(sources){chunks.add(it)}
        stream.accept("ANSWER\n杀人蜂是羿剑文。[E1_")
        verify(chunks.isEmpty(),"不完整证据编号提前显示")
        stream.accept("ANSWER\n杀人蜂是羿剑文。[${e.id}]")
        verify(chunks.size==1,"首个合法出处到达后仍整篇扣留")
        stream.accept("ANSWER\n杀人蜂是羿剑文。[${e.id}]二人来到")
        stream.accept("ANSWER\n杀人蜂是羿剑文。[${e.id}]二人来到飞天堡，是要夺取箱子。")
        verify(chunks.size==3&&chunks.none{it.contains("E1_")||it.contains("ANSWER")},"增量正文/内部标签处理错误")
        val secret="未读秘密：箱子里装着假剑。"
        val book=Book(id="qa026",title="快捷提问回归",chapters=listOf(Chapter("正文",text+secret)))
        val edge=Position(0,text.length)
        val cfg=AiConfig("https://example.com/v1","answer","fixture",context=i.targetContext,owner="qa026")
        fun response(raw:String)=JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","stop").put("message",JSONObject().put("content",raw))))
        val prompts=listOf("说说眼前这一段有什么意味","结合我读到的位置，回顾与眼前情节有关的前因","眼前这些人物是什么关系？只说已读原文明确交代的部分","杀人蜂是谁")
        for((n,q) in prompts.withIndex()){
            var emittedBeforeReturn=false;var finalReturned=false;var updates=0
            val result=ReadingAssistant.response(cfg,cfg,book,edge,q,"伴读","闲谈","q$n",emptyList(),visibleStart=Position(0,0),onText={if(!finalReturned)emittedBeforeReturn=true;updates++},call={_,body,onText->
                verify(!body.toString().contains(secret),"快捷提问超出阅读截止位置")
                val user=JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content"))
                if(user.has("候选原文")){
                    val ids=JSONArray();val rows=user.getJSONArray("候选原文");for(k in 0 until rows.length())ids.put(rows.getJSONObject(k).getString("id"))
                    response(JSONObject().put("sufficient",true).put("ids",ids).put("queries",JSONArray()).toString())
                }else{
                    val id=Regex("E\\d+_\\d+_\\d+").find(user.getString("允许的原文"))!!.value
                    File(dir,"request-$n.json").writeText(body.toString())
                    val raw="ANSWER\n杀人蜂是羿剑文。[$id]二人与宁大总管处于对立关系。他们到飞天堡是为了夺取箱子。[$id]"
                    for(end in 1..raw.length step 3)onText?.invoke(raw.take(end))
                    onText?.invoke(raw);finalReturned=true;response(raw)
                }
            })
            verify(!result.more,"快捷提问仍被引文格式拦截：$q")
            verify(emittedBeforeReturn&&updates>2,"回答在网络结束后才显示：$q")
            verify(result.usage.getInt("calls")<=2,"普通快捷提问仍有多轮串行核对：$q")
            File(dir,"sources-$n.json").writeText(JSONArray(result.sources.map{JSONObject().put("chapter",it.chapter).put("start",it.start).put("end",it.end).put("title",it.title).put("text",it.text)}).toString())
            val live=File(dir,"live-$n.json")
            if(live.exists()){
                val data=JSONObject(live.readText());val deltas=data.getJSONArray("cumulative");var paints=0
                val renderer=EvidenceAnswer.Stream(result.sources){paints++}
                for(k in 0 until deltas.length())renderer.accept(deltas.getString(k))
                val before=paints;val checked=renderer.finish(data.getString("answer"))
                verify(checked.valid,"真实模型回答仍被误拒：${checked.reason}")
                verify(before>1,"真实模型的 SSE 不能增量显示")
                File(dir,"live-render-$n.json").writeText(JSONObject().put("valid",checked.valid).put("paintsBeforeFinish",before).put("text",checked.text).toString())
            }
        }
        // Merge a queued progress snapshot into the latest stored metadata, never an old Book copy.
        Library(i.targetContext).use{db->
            book.touched=100;book.chats=JSONArray().put(JSONObject().put("question","应保留的新对话"));db.save("qa026",book)
            verify(db.saveProgress("qa026",Library.Progress(book.id,1,Position(0,20),200,15,20)),"进度合并失败")
            val loaded=db.get("qa026",book.id)!!
            verify(loaded.position.offset==20&&loaded.chats.length()==1,"后台进度覆盖了对话")
            verify(!db.saveProgress("qa026",Library.Progress(book.id,1,Position(0,1),150,1,1)),"旧进度覆盖较新位置")
            verify(!db.saveProgress("qa026",Library.Progress(book.id,2,Position(0,1),300,1,1)),"跨正文版本写入旧进度")
            verify(!db.saveProgress("other",Library.Progress(book.id,1,Position(0,1),300,1,1)),"进度混用本地资料")
        }
        NovelRetrieval(book.chapters,edge,indexing=false).use{verify(it.rebuiltChapters==0&&it.current().none{x->x.text.contains(secret)},"直接解读仍建全书索引/泄漏后文")}
        File(dir,"checks.txt").writeText("$checks checks passed")
        return "$checks checks passed: citation variants, streaming, shortcuts, reading fence, progress merge, actual-model replay when provided"
    }
}
