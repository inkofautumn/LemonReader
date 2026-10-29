package cn.jiangcheng.lemon.reader

import cn.jiangcheng.lemon.data.Book
import org.json.*
import java.io.File
import android.util.AtomicFile
import java.util.concurrent.CancellationException

/** Exhaustive within the read boundary; cached batches are validated against exact source text. */
class CharacterResearch(private val cfg:AiConfig,private val book:Book?,private val call:((JSONObject)->JSONObject)?) {
 data class Result(val hits:List<ResearchAgent.Hit>,val complete:Boolean,val message:String)
 companion object {
  fun matches(query:String):Boolean {
   val q=query.replace(Regex("[\\s，。！？?！]"),"")
   if(!Regex("人物|角色").containsMatchIn(q)||Regex("关系|死亡|死时|谁是|为什么").containsMatchIn(q))return false
   return Regex("梳理|整理|列出|列举|汇总|盘点|统计|查看|看看|有哪些|所有|全部|出场|登场|出现").containsMatchIn(q)
  }
 }
 fun run(chapters:List<Chapter>,boundary:Position,cancelled:()->Boolean,progress:(Int,Int)->Unit,partial:(List<ResearchAgent.Hit>)->Unit):Result {
  val cutoff=ReaderCore.clamp(boundary,chapters)
  data class Batch(val chapter:Int,val start:Int,val end:Int,val source:String)
  val batches=mutableListOf<Batch>()
  chapters.take(cutoff.chapter+1).forEachIndexed{ch,c->val end=if(ch==cutoff.chapter)cutoff.offset.coerceIn(0,c.text.length)else c.text.length;var start=0
   while(start<end){var stop=ReadingSegments.end(c.text,start,end);if(stop<end&&Character.isHighSurrogate(c.text[stop-1]))stop--;val from=(start-160).coerceAtLeast(0);batches.add(Batch(ch,from,stop,c.text.substring(from,stop)));start=stop}
  }
  val hits=linkedMapOf<String,ResearchAgent.Hit>()
  fun accept(ch:Int,offset:Int,name:String,evidence:String,description:String,contextEnd:Int=offset+evidence.length){
   val text=chapters.getOrNull(ch)?.text?:return
   if(name.isBlank()||evidence.isBlank()||!evidence.contains(name)||offset<0||offset+evidence.length>text.length||text.substring(offset,offset+evidence.length)!=evidence)return
   if(ch>cutoff.chapter||ch==cutoff.chapter&&maxOf(offset+evidence.length,contextEnd)>cutoff.offset)return
   val hit=ResearchAgent.Hit(ch,offset,evidence,name,description,"人物",contextEnd);val previous=hits[name]
   if(previous==null||ch<previous.chapter||ch==previous.chapter&&offset<previous.offset)hits[name]=hit
  }
  book?.let{ChronicleCatalog.visible(it.chronicles,cutoff,it.revision)}?.filter{it.kind=="人物"}?.forEach{accept(it.chapter,it.offset-it.evidence.length,it.title,it.evidence,it.content,it.contextEnd)}
  fun current()=hits.values.sortedWith(compareBy({it.chapter},{it.offset}))
  if(hits.isNotEmpty())partial(current())
  fun namespace(value:String)=android.util.Base64.encodeToString(value.toByteArray(Charsets.UTF_8),android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
  val folder=if(book!=null&&cfg.context!=null)File(cfg.context.cacheDir,"character-overview/${namespace(cfg.owner)}/${namespace(book.id)}").apply{mkdirs()}else null
  var done=0
  for(batch in batches){
   if(cancelled())throw CancellationException()
   progress(done,batches.size)
   val file=folder?.let{File(it,"${batch.chapter}-${batch.start}-${batch.end}.json")}
   val cached=file?.let{runCatching{JSONObject(AtomicFile(it).readFully().toString(Charsets.UTF_8))}.getOrNull()}?.takeIf{it.optInt("version")==1&&it.optString("source")==batch.source}
   val entries:JSONArray
   if(cached!=null)entries=cached.getJSONArray("entries")
   else {
    val extracted=try{ChronicleTasks.extract(cfg,batch.chapter,batch.start,batch.source,book?.revision?:1,names=emptyList(),call=call,bookId=book?.id.orEmpty()).filter{it.kind=="人物"}}catch(e:Exception){
     if(cancelled())throw CancellationException()
     cfg.context?.let{cn.jiangcheng.lemon.data.CrashLog.event(it,cn.jiangcheng.lemon.data.CrashLog.Event.AI_FAILED,error=e)}
     val why=if(e.message?.contains("预算")==true)"本次预算已到"else"模型请求未完成"
     return Result(current(),false,"$why · 已处理 $done/${batches.size} 段，已找到 ${hits.size} 位人物；已完成部分会保留")
    }
    if(cancelled())throw CancellationException()
    entries=JSONArray(extracted.map{JSONObject().put("name",it.title).put("description",it.content).put("evidence",it.evidence).put("offset",it.offset-it.evidence.length)})
    if(file!=null)runCatching{val cache=AtomicFile(file);val stream=cache.startWrite();try{stream.write(JSONObject().put("version",1).put("source",batch.source).put("entries",entries).toString().toByteArray());cache.finishWrite(stream)}catch(e:Exception){cache.failWrite(stream);throw e}}
   }
   for(i in 0 until entries.length()){val item=entries.getJSONObject(i);accept(batch.chapter,item.getInt("offset"),item.getString("name"),item.getString("evidence"),item.getString("description"),batch.end)}
   done++;progress(done,batches.size);partial(current())
  }
  return Result(current(),true,if(hits.isEmpty())"已检查全部已读内容，暂未识别到有明确姓名或稳定称呼的人物"else"已梳理全部已读内容 · ${hits.size} 位人物")
 }
}
