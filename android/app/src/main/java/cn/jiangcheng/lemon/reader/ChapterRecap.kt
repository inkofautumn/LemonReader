package cn.jiangcheng.lemon.reader

import android.util.AtomicFile
import cn.jiangcheng.lemon.data.Book
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Resumable chapter summaries; source is clipped before any map or reduce call. */
object ChapterRecap {
    data class Result(val text:String,val complete:Boolean,val completed:Int,val total:Int,val usage:JSONObject)
    fun run(config:AiConfig,helper:AiConfig,book:Book,boundary:Position,cancel:AiCancellation=AiCancellation(),
            status:(String)->Unit={},call:((AiConfig,JSONObject)->JSONObject)?=null):Result {
        val p=ReaderCore.clamp(boundary,book.chapters);val chapter=book.chapters[p.chapter];val source=chapter.text.take(p.offset)
        require(source.isNotBlank()){"当前位置还没有可总结的正文"}
        val cache=config.context?.let{context->fun key(s:String)=android.util.Base64.encodeToString(s.toByteArray(),11)
            AtomicFile(File(context.cacheDir,"chapter-recap/${key(config.owner)}/${key(book.id)}/${p.chapter}.json").apply{parentFile!!.mkdirs()})}
        val signature="1|${book.revision}|${config.base}|${config.model}|${helper.base}|${helper.model}"
        val previous=cache?.let{runCatching{JSONObject(it.readFully().toString(Charsets.UTF_8))}.getOrNull()}
        val nodes=if(previous?.optString("signature")==signature&&previous.optString("source")==source)previous.optJSONObject("nodes")?:JSONObject()else JSONObject()
        fun save(){if(cache==null)return;val stream=cache.startWrite();try{stream.write(JSONObject().put("signature",signature).put("source",source).put("nodes",nodes).toString().toByteArray());cache.finishWrite(stream)}catch(e:Exception){cache.failWrite(stream);throw e}}
        val budget=ReadingTurnBudget(12000,config.budget,4);val session=java.util.UUID.randomUUID().toString()
        fun generate(key:String,material:String,final:Boolean):String? {
            cancel.check();nodes.optString(key).takeIf{it.isNotBlank()}?.let{return it}
            val rules=Storyteller.voice+if(final)"\n根据以下本章材料回顾主要事情与变化，保留未解疑问。500字以内。不把猜测写成事实，不补后文。"else"\n把以下已读材料压缩为不超过220字的记录，保留人物、事件先后、因果、否定及未解疑问。不增加资料外内容。"
            val body=JSONObject().put("max_tokens",if(final)1000 else 450).put("messages",JSONArray().put(JSONObject().put("role","system").put("content",rules)).put(JSONObject().put("role","user").put("content","【${chapter.title}】\n$material")))
            if(!budget.canSpend(body))return null
            status("正在回顾本章 · 本轮 ${budget.calls+1}/4")
            val reserve=budget.begin(body)
            val cfg=(if(final)config else helper.copy(thinking="off")).copy(session=session,cancellation=cancel)
            val response=try{(call?.invoke(cfg,body)?:AiGateway.request(cfg,body)).also{budget.finish(it,reserve)}}catch(e:Exception){budget.failed(body.optInt("max_tokens"));throw e}
            cancel.check();val choice=response.getJSONArray("choices").getJSONObject(0)
            check(choice.optString("finish_reason","stop")=="stop"){"这一段回顾未完成，已保留此前进度"}
            val text=choice.getJSONObject("message").getString("content").trim();check(text.isNotBlank()){"模型未返回总结"}
            nodes.put(key,if(final)text else text.take(700));save();return nodes.getString(key)
        }
        if(source.length<=3200){val text=generate("final",source,true);return Result(text.orEmpty(),text!=null,if(text!=null)1 else 0,1,budget.json())}
        val parts=mutableListOf<String>();var at=0
        while(at<source.length){cancel.check();val end=ReadingSegments.end(source,at,source.length,2600);parts.add(source.substring(at,end));at=end}
        fun pending()=Result("已完成 ${nodes.length()} 段回顾，继续即可接着处理。",false,nodes.length(),parts.size,budget.json())
        var summaries=mutableListOf<String>()
        for((i,text)in parts.withIndex()){val summary=generate("part:$i",text,false)?:return pending();summaries.add(summary)}
        var level=0
        while(summaries.sumOf{it.length}>4000){
            val merged=mutableListOf<String>()
            for((i,group)in summaries.chunked(5).withIndex()){val summary=generate("reduce:$level:$i",group.joinToString("\n\n"),false)?:return pending();merged.add(summary)}
            summaries=merged;level++
        }
        val final=generate("final",summaries.joinToString("\n\n"),true)?:return pending()
        return Result(final,true,parts.size,parts.size,budget.json())
    }
}
