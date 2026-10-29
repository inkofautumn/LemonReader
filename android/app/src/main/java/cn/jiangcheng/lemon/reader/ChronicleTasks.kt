package cn.jiangcheng.lemon.reader

import android.content.Context
import cn.jiangcheng.lemon.MainActivity
import cn.jiangcheng.lemon.data.Book
import cn.jiangcheng.lemon.data.Library
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Incremental, evidence-backed reading notes. No prior-book body is resent. */
object ChronicleTasks {
    private val executor=Executors.newSingleThreadExecutor()
    private val active=ConcurrentHashMap.newKeySet<String>()
    private val latest=ConcurrentHashMap<String,Position>()
    private fun prefs(context:Context,owner:String)=context.getSharedPreferences("chronicle-progress:$owner",0)
    fun status(context:Context,owner:String,id:String)=prefs(context,owner).getString("status:$id","读到完整段落或章末时，增量整理人物、事件和线索").orEmpty()
    fun extract(config:AiConfig,chapter:Int,start:Int,text:String,revision:Int,names:List<String> = emptyList(),call:((JSONObject)->JSONObject)?=null,known:List<Chronicle> = emptyList(),bookId:String=""):List<Chronicle>{
        val people=known.filter{!it.deleted&&it.revision==revision}
        val nearby=people.groupBy{it.entity.ifBlank{it.title}}.values.filter{group->group.any{text.contains(it.title)}}.take(12).map{group->
            JSONObject().put("name",CharacterAliases.canonical(group)).put("aliases",JSONArray(group.map{it.title}.distinct())).put("known",group.last().content.take(100))}
        val request=JSONObject().put("max_tokens",2400).put("response_format",JSONObject().put("type","json_object")).put("messages",JSONArray()
            .put(JSONObject().put("role","system").put("content",ArchiveRecord.instruction+"自动积累：只提取有新信息的身份或关系变化、重要事件和具体未解线索；不为日常动作创建卡片，不重复给定的已知事实。保留有明确姓名的次要人物。疑点不能当作已经揭晓，对照已知资料识别全名、简称和别名，同一人物保持已有身份。name 仍用本段证据实际出现的称呼，不把不同身份或形态仅凭同一个尾字合并；别名关系必须由原文明确说明。忽略出版信息、目录与广告。没有新事实就返回空 entries。每项 summary 简明，不超过100字。"))
            .put(JSONObject().put("role","user").put("content",JSONObject().put("已知资料",JSONArray(nearby)).put("已有人名",JSONArray(names.filter{text.contains(it)}.take(40))).put("正文",text).toString())))
        val folder=if(bookId.isNotBlank()&&config.context!=null)java.io.File(config.context.cacheDir,"chronicle-extract/"+android.util.Base64.encodeToString((config.owner+":"+bookId).toByteArray(),android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)).apply{mkdirs()}else null
        val file=folder?.let{java.io.File(it,"$revision-$chapter-$start.json")}
        val signature=JSONObject().put("version",4).put("source",text).put("model",config.model).put("base",config.base).put("context",JSONArray(nearby)).toString()
        val cached=file?.let{runCatching{JSONObject(android.util.AtomicFile(it).readFully().toString(Charsets.UTF_8))}.getOrNull()}?.takeIf{it.optString("signature")==signature}?.optJSONObject("result")
        val result=cached?:ModelOutput.json(call?.invoke(request)?:AiGateway.request(config,request))
        val items=result.optJSONArray("entries")?:error("书志结果缺少 entries")
        val entries=(0 until minOf(items.length(),16)).mapNotNull{i->
            val item=items.optJSONObject(i)?:return@mapNotNull null
            // Old providers/templates may still return title/content; validate their evidence too.
            val hit=ArchiveRecord.parse(item,chapter,start,text)
            if(hit!=null)Chronicle(kind=hit.kind,title=hit.title,content=hit.reason,chapter=chapter,offset=hit.offset+hit.evidence.length,revision=revision,evidence=hit.evidence,contextEnd=start+text.length)
            else if(item.optString("kind")=="人物"){
                val evidence=item.optString("evidence");val at=text.indexOf(evidence);val title=item.optString("title").trim();val content=item.optString("content").trim()
                if(evidence.length !in 2..240||at<0||title.isBlank()||!evidence.contains(title)||content.isBlank())null
                else Chronicle(kind="人物",title=title.take(40),content=content.take(240),chapter=chapter,offset=start+at+evidence.length,revision=revision,evidence=evidence,contextEnd=start+text.length)
            }else null
        }
        check(items.length()==0||entries.isNotEmpty()){"书志结果缺少有效原文依据"}
        if(file!=null&&cached==null){val atomic=android.util.AtomicFile(file);val stream=atomic.startWrite();try{stream.write(JSONObject().put("signature",signature).put("result",result).toString().toByteArray());atomic.finishWrite(stream)}catch(e:Exception){atomic.failWrite(stream)}}
        return entries
    }
    fun maybe(app:MainActivity,book:Book,boundary:Position,immediate:Boolean=false,call:((JSONObject)->JSONObject)?=null){
        val owner=app.profileId;val id=book.id;val key="$owner:$id";val context=app.applicationContext
        if(!app.prefs.getBoolean("auto_chronicles:$owner",true))return
        latest[key]=boundary.copy()
        val state=prefs(context,owner);val now=System.currentTimeMillis()
        if(state.getInt("algorithm:$id",0)!=3&&!active.contains(key))state.edit().putInt("algorithm:$id",3).apply()
        if(key in active||(!immediate&&now-state.getLong("attempt:$id",0)<45000))return
        val revision=book.revision
        var sameRevision=state.getInt("revision:$id",-1)==revision
        if(!sameRevision){
            val anchor=state.getString("anchor:$id","").orEmpty()
            if(anchor.length>=16){val hits=book.chapters.flatMapIndexed{index,c->val at=c.text.indexOf(anchor);if(at>=0&&c.text.indexOf(anchor,at+1)<0)listOf(Position(index,at+anchor.length))else emptyList()}
                if(hits.size==1){val hit=hits.single();state.edit().putInt("revision:$id",revision).putInt("chapter:$id",hit.chapter).putInt("offset:$id",hit.offset).apply();sameRevision=true}
            }
        }
        val chapter=if(sameRevision)state.getInt("chapter:$id",0)else 0
        if(chapter>boundary.chapter||chapter !in book.chapters.indices)return
        val text=book.chapters[chapter].text
        val start=if(sameRevision)state.getInt("offset:$id",0).coerceIn(0,text.length)else 0
        val limit=if(chapter<boundary.chapter)text.length else boundary.offset.coerceIn(0,text.length)
        val available=limit-start
        if(available<=0){if(chapter<boundary.chapter){state.edit().putInt("revision:$id",revision).putInt("chapter:$id",chapter+1).putInt("offset:$id",0).apply();android.os.Handler(android.os.Looper.getMainLooper()).post{maybe(app,book,boundary,true,call)}};return}
        if(available<1600&&limit<text.length)return
        val cfg=ModelProfiles.resolve(context,owner,"record","自动整理书志")
        if(cfg.key.isBlank()){state.edit().putLong("attempt:$id",now).putString("status:$id","添加模型 API 后自动整理").apply();return}
        var end=ReadingSegments.end(text,start,limit)
        if(end<text.length&&end>start&&Character.isHighSurrogate(text[end-1]))end--
        val fragmentStart=(start-160).coerceAtLeast(0)
        val fragment=text.substring(fragmentStart,end);val finish=end
        if(!active.add(key))return
        state.edit().putLong("attempt:$id",now).putString("status:$id","正在整理新增的已读内容…").apply()
        val known=ChronicleCatalog.visible(book.chronicles,Position(chapter,end),revision).toList()
        val names=known.filter{it.kind=="人物"}.map{it.title}.distinct()
        executor.execute{
            val day=java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.ROOT).format(java.util.Date())
            val result=runCatching{extract(cfg.copy(session="auto:$owner:$day",budget=app.prefs.getInt("auto_chronicles_budget:$owner",30000).coerceAtMost(cfg.budget)),chapter,fragmentStart,fragment,revision,names,call,known,book.id)}
            app.runOnUiThread{
                try{
                    result.onSuccess{entries->
                        // A closed reader still saves to its account; a switched account is never overwritten.
                        val library=Library(context)
                        try{
                            val current=if(!app.isDestroyed&&app.profileId==owner)app.reader?.book?.takeIf{it.id==id}?:library.get(owner,id)else library.get(owner,id)
                            if(current!=null&&current.revision==revision&&app.prefs.getBoolean("auto_chronicles:$owner",true)){
                                val oldCount=current.chronicles.size
                                current.chronicles=ReaderCore.mergeChronicles(current.chronicles,entries);library.save(owner,current)
                                state.edit().putString("anchor:$id",text.substring((finish-80).coerceAtLeast(0),finish)).putInt("revision:$id",revision).putInt("chapter:$id",if(finish==text.length)chapter+1 else chapter).putInt("offset:$id",if(finish==text.length)0 else finish).putString("status:$id",if(entries.isEmpty())"已整理至当前片段，暂无新发现"else"已整理：新增 ${current.chronicles.size-oldCount} 条发现").apply()
                            }else state.edit().putString("status:$id","正文已更新，将从阅读位置继续整理").apply()
                        }finally{library.close()}
                    }.onFailure{cn.jiangcheng.lemon.data.CrashLog.event(context,cn.jiangcheng.lemon.data.CrashLog.Event.AI_FAILED,error=it);state.edit().putString("status:$id",if(it.message?.contains("预算")==true)"今日自动书志预算已到，明日继续；可在设置调整"else"本次整理未完成，继续阅读后重试").apply()}
                }finally{
                    active.remove(key)
                    if(!app.isDestroyed&&app.profileId==owner){val reader=app.reader
                        if(reader?.book?.id==id){reader.refreshChronicles()
                            if(result.isSuccess&&reader.book.revision==revision)android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                if(app.reader===reader&&app.profileId==owner)latest[key]?.let{maybe(app,reader.book,it,true,call)}
                            },2000)
                        }
                    }
                }
            }
        }
    }
}
