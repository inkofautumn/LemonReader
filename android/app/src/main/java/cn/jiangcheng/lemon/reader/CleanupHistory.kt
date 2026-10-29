package cn.jiangcheng.lemon.reader

import org.json.*
import java.io.File
import android.util.AtomicFile

/** Original-coordinate deletion ranges allow independent undo, including adjacent removals. */
class CleanupHistory(private val file:File) {
 data class Span(val chapter:Int,val start:Int,val end:Int)
 data class Record(val id:String,val quote:String,val count:Int,val time:Long,val restored:Boolean)
 data class Change(val chapters:List<Chapter>,val map:(Position)->Position)
 private val data=if(file.exists())JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8))else JSONObject().put("epochs",JSONArray())
 private val epochs=data.getJSONArray("epochs")
 private val bases=java.util.IdentityHashMap<JSONObject,List<Chapter>>()
 private fun base(e:JSONObject):List<Chapter> = bases.getOrPut(e){e.getJSONArray("base").let{a->(0 until a.length()).map{Chapter(a.getJSONObject(it).getString("title"),a.getJSONObject(it).getString("text"))}}}
 private fun ranges(e:JSONObject,ch:Int):List<Span>{
  val result=mutableListOf<Span>();val ops=e.getJSONArray("ops")
  for(i in 0 until ops.length()){val op=ops.getJSONObject(i);if(op.optBoolean("restored"))continue;val spans=op.getJSONArray("spans")
   for(j in 0 until spans.length()){val x=spans.getJSONArray(j);if(x.getInt(0)==ch)result.add(Span(ch,x.getInt(1),x.getInt(2)))}}
  val merged=mutableListOf<Span>();for(s in result.sortedBy{it.start}){val last=merged.lastOrNull();if(last!=null&&s.start<=last.end)merged[merged.lastIndex]=last.copy(end=maxOf(last.end,s.end))else merged.add(s)}
  return merged
 }
 private fun visible(e:JSONObject,ch:Int):List<Span>{val size=base(e)[ch].text.length;var at=0;val result=mutableListOf<Span>();for(r in ranges(e,ch)){if(at<r.start)result.add(Span(ch,at,r.start));at=r.end};if(at<size)result.add(Span(ch,at,size));return result}
 private fun render(e:JSONObject)=base(e).mapIndexed{ch,c->c.copy(text=buildString{for(s in visible(e,ch))append(c.text.substring(s.start,s.end))})}
 private fun matches(e:JSONObject,current:List<Chapter>)=render(e).map{it.text}==current.map{it.text}
 private fun save(){file.parentFile?.mkdirs();val atomic=AtomicFile(file);val out=atomic.startWrite();try{out.write(data.toString().toByteArray(Charsets.UTF_8));atomic.finishWrite(out)}catch(t:Throwable){atomic.failWrite(out);throw t}}
 fun records():List<Record>{val result=mutableListOf<Record>();for(i in 0 until epochs.length()){val ops=epochs.getJSONObject(i).getJSONArray("ops");for(j in 0 until ops.length()){val o=ops.getJSONObject(j);result.add(Record(o.getString("id"),o.getString("quote"),o.getInt("count"),o.getLong("time"),o.optBoolean("restored")))}};return result.reversed()}
 private fun transition(e:JSONObject,current:List<Chapter>,mutation:()->Unit):Change {
  val before=current.indices.map{visible(e,it)}
  mutation();val after=current.indices.map{ranges(e,it)};val output=render(e).mapIndexed{i,c->c.copy(title=current[i].title)}
  save()
  return Change(output){p->
   val c=p.chapter.coerceIn(0,current.lastIndex);var remaining=p.offset.coerceIn(0,current[c].text.length);var original=base(e)[c].text.length
   for(s in before[c]){val length=s.end-s.start;if(remaining<length){original=s.start+remaining;break};remaining-=length}
   var offset=original;for(r in after[c])offset-=(minOf(original,r.end)-r.start).coerceAtLeast(0)
   Position(c,offset.coerceIn(0,output[c].text.length))
  }
 }
 fun delete(current:List<Chapter>,quote:String,occurrences:List<Span>):Change {
  require(quote.isNotEmpty()&&occurrences.isNotEmpty())
  require(occurrences.all{it.chapter in current.indices&&it.start>=0&&it.end<=current[it.chapter].text.length&&current[it.chapter].text.substring(it.start,it.end)==quote}){"选区已变化，请重新选择"}
  var e=epochs.optJSONObject(epochs.length()-1)
  if(e==null||!matches(e,current)){e=JSONObject().put("base",JSONArray(current.map{JSONObject().put("title",it.title).put("text",it.text)})).put("ops",JSONArray());epochs.put(e)}
  val epoch=e;val spans=JSONArray()
  for((ch,selected) in occurrences.groupBy{it.chapter}){
   val ordered=selected.sortedBy{it.start};var index=0;var cursor=0
   for(v in visible(epoch,ch)){val end=cursor+v.end-v.start;while(index<ordered.size&&ordered[index].end<=cursor)index++
    var k=index;while(k<ordered.size&&ordered[k].start<end){val a=maxOf(cursor,ordered[k].start);val b=minOf(end,ordered[k].end);if(a<b)spans.put(JSONArray(listOf(ch,v.start+a-cursor,v.start+b-cursor)));k++};cursor=end}
  }
  return transition(epoch,current){epoch.getJSONArray("ops").put(JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("quote",quote).put("count",occurrences.size).put("time",System.currentTimeMillis()).put("spans",spans).put("restored",false))}
 }
 fun restore(current:List<Chapter>,id:String):Change {
  for(i in 0 until epochs.length()){val e=epochs.getJSONObject(i);val ops=e.getJSONArray("ops");for(j in 0 until ops.length()){val o=ops.getJSONObject(j);if(o.getString("id")==id){
   check(!o.optBoolean("restored")){"此记录已恢复"};check(matches(e,current)){"正文经过其他整理，无法可靠恢复此记录；清理记录仍保留"}
   return transition(e,current){o.put("restored",true)}
  }}}
  error("清理记录不存在")
 }
 companion object {
  fun find(chapters:List<Chapter>,quote:String):List<Span>{if(quote.isEmpty())return emptyList();val result=mutableListOf<Span>();chapters.forEachIndexed{ch,c->var at=c.text.indexOf(quote);while(at>=0){result.add(Span(ch,at,at+quote.length));at=c.text.indexOf(quote,at+quote.length)}};return result}
 }
}
