package cn.jiangcheng.lemon
import cn.jiangcheng.lemon.reader.*
import cn.jiangcheng.lemon.data.Book
import org.json.*
object Revision0121Test {
 fun run(app:MainActivity):String {
  var n=0;fun verify(v:Boolean){check(v){"check ${n+1} failed"};n++}
  listOf("梳理目前出场的人物","整理现在出场的人物","截止到现在登场的所有人物").forEach{verify(CharacterResearch.matches(it))}
  verify(!CharacterResearch.matches("梳理林舟的人物关系"))
  fun response(entries:JSONArray)=JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","stop").put("message",JSONObject().put("content",JSONObject().put("entries",entries).toString()))))
  val chapters=(0..12).map{Chapter("第${it+1}章","人物甲${it}走进了书房。")}
  val book=Book(title="人物覆盖测试",chapters=chapters)
  val cfg=AiConfig("fixture","fixture","fixture",context=app,owner="qa0121")
  var calls=0
  val mock:(JSONObject)->JSONObject={request->calls++;val body=JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"));val text=body.getString("newText");val name=text.substringBefore("走进");response(JSONArray().put(JSONObject().put("kind","人物").put("title",name).put("content","来到书房").put("evidence",text)))}
  val agent=ResearchAgent(cfg,book,mock)
  val hits=agent.search(chapters,Position(11,chapters[11].text.length),"梳理目前出场的人物",true)
  verify(calls==12);verify(hits.size==12);verify(hits.last().title=="人物甲11");verify(hits.none{it.title=="人物甲12"});verify(agent.overviewComplete)
  val again=ResearchAgent(cfg,book,mock).search(chapters,Position(11,chapters[11].text.length),"整理现在出场的人物",true);verify(calls==12&&again.size==12)
  ResearchAgent(cfg,book,mock).search(chapters,Position(12,chapters[12].text.length),"截止到现在登场的所有人物",true);verify(calls==13)
  val partial=ResearchAgent(cfg,call={request->if(++calls>14)error("网络中断")else mock(request)})
  val partialHits=partial.search(chapters,Position(2,chapters[2].text.length),"整理出场人物",true);verify(!partial.overviewComplete&&partialHits.isNotEmpty())
  val source="次日清晨，杨会来到书房，发现一张车票。"
  val person=ArchiveRecord.parse(JSONObject().put("kind","人物").put("name","杨会").put("identity","学生").put("evidence",source),0,0,source)!!;verify(person.title=="杨会"&&!person.reason.contains("匹配"))
  verify(ArchiveRecord.parse(JSONObject().put("kind","人物").put("name","杨会登场").put("summary","学生").put("evidence",source),0,0,source)==null)
  val event=JSONObject().put("kind","时间线").put("time","次日清晨").put("event","杨会来到书房").put("evidence",source)
  verify(ArchiveRecord.parse(event,0,0,source)!!.title=="次日清晨")
  event.put("time","");verify(ArchiveRecord.parse(event,0,0,source)!!.title=="时间未明确")
  event.put("time","永和三年");verify(ArchiveRecord.parse(event,0,0,source)==null)
  val clue=ArchiveRecord.parse(JSONObject().put("kind","线索").put("clue","书房的车票").put("summary","杨会发现一张车票").put("evidence",source),0,0,source)!!;verify(clue.title=="书房的车票")
  val a=Chronicle(kind="时间线",title="次日清晨",content="事件一",chapter=0,offset=10,evidence="事件一")
  val b=a.copy(id="another",content="事件二",offset=20,evidence="事件二")
  verify(ReaderCore.mergeChronicles(listOf(a),listOf(b)).size==2);verify(ReaderCore.mergeChronicles(listOf(a),listOf(a.copy(id="duplicate"))).size==1)
  val timelineAgent=ResearchAgent(cfg,call={request->
   verify(request.getJSONArray("messages").getJSONObject(0).getString("content").contains("time="))
   response(JSONArray().put(JSONObject().put("kind","时间线").put("time","次日清晨").put("event","杨会来到书房").put("evidence",source)))
  })
  val timeline=timelineAgent.search(listOf(Chapter("第一卷 第一章",source)),Position(0,source.length),"整理时间线",true)
  verify(timeline.single().title=="次日清晨"&&timeline.single().kind=="时间线")
  var live=""
  val key=java.io.File(app.filesDir,"qwen-character-test-key")
  if(key.exists()){
   val secret=key.readText().trim();key.delete()
   val c=listOf(Chapter("第一章", "杨会是音乐系的学生。程颐非刚毕业，在蓉城师范大学教书。次日，杨会在琴房见到了老师林舟。"))
   val real=ResearchAgent(AiConfig("https://dashscope.aliyuncs.com/compatible-mode/v1","qwen3.7-flash",secret,context=app,owner="qa0121",task="人物总览实测",budget=8000)).search(c,Position(0,c[0].text.length),"梳理目前出场的人物",true)
   verify(real.map{it.title}.toSet().containsAll(setOf("杨会","程颐非","林舟")));live="; live Qwen 3 characters passed"
  }
  return "$n structured archive and coverage checks passed$live"
 }
}
