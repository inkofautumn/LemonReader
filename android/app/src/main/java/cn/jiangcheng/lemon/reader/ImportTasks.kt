package cn.jiangcheng.lemon.reader

import android.os.Handler
import android.os.Looper
import cn.jiangcheng.lemon.MainActivity
import cn.jiangcheng.lemon.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

/** Persistent per-book jobs, independent of the import page and Activity worker. */
object ImportTasks {
    val waiting=Chapter("待整理部分","该部分正在整理，请稍后。")
    private val executor=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private val active=ConcurrentHashMap.newKeySet<String>()
    private val agents=ConcurrentHashMap<String,ImportAgent>()
    private val cancelled=ConcurrentHashMap.newKeySet<String>()
    private var activity=WeakReference<MainActivity>(null)
    private fun <T> ui(block:()->T):T {if(Looper.myLooper()==Looper.getMainLooper())return block();val task=FutureTask(block);main.post(task);return task.get()}
    private fun file(app:android.content.Context,owner:String,id:String)=File(app.filesDir,"sources/$owner/$id/import-job.json")
    fun state(app:android.content.Context,owner:String,id:String):JSONObject?=runCatching{JSONObject(file(app,owner,id).readText())}.getOrNull()
    @Synchronized private fun write(file:File,j:JSONObject){
        if(j.optString("status")!in listOf("queued","restored")&&runCatching{JSONObject(file.readText()).optString("status")=="restored"}.getOrDefault(false))return
        file.parentFile?.mkdirs();val temp=File(file.parentFile,"job.pending");temp.writeText(j.toString());check(temp.renameTo(file))
    }
    fun attach(app:MainActivity){activity=WeakReference(app);if(app.profileId.isBlank())return
        resume(app,app.profileId)
    }
    fun isRunning()=active.isNotEmpty()
    fun cancelForEdit(context:android.content.Context,owner:String,id:String){
        val key="$owner:$id";cancelled.add(key);agents[key]?.cancelled=true
        state(context,owner,id)?.let{write(file(context,owner,id),it.put("status","restored").put("message","正文已手动修改，可重新整理"))}
    }
    fun resume(context:android.content.Context,owner:String){File(context.filesDir,"sources/$owner").listFiles()?.filter{it.isDirectory}?.forEach{dir->if(state(context,owner,dir.name)?.optString("status") in listOf("queued","running"))run(context,owner,dir.name)}}
    fun pause(context:android.content.Context){active.toList().forEach{key->cancelled.add(key);agents[key]?.cancelled=true;val owner=key.substringBefore(':');val id=key.substringAfter(':');state(context,owner,id)?.let{write(file(context,owner,id),it.put("status","failed").put("message","后台运行时限已到，已保存进度；点击继续整理"))}}}
    fun start(app:MainActivity,book:Book,options:ImportAgent.Options,feedback:String="",fresh:Boolean=false):Boolean{
        val owner=app.profileId;val id=book.id;val key="$owner:$id"
        if(key in active){app.message("正在整理","当前任务完成后可以补充意见重新整理。");return false}
        cancelled.remove(key)
        val job=file(app,owner,id);File(job.parentFile,"agent-checkpoint.json").delete()
        File(job.parentFile,"readable-latest.json").delete()
        File(job.parentFile,"agent-result.json").delete()
        File(job.parentFile,"before-organize.json").writeText(Library.toJson(book).toString())
        write(job,JSONObject().put("status","queued").put("session",java.util.UUID.randomUUID().toString()).put("message","等待整理").put("fresh",fresh).put("feedback",feedback).put("base",app.prefs.getString("ai_base:$owner","https://api.deepseek.com")).put("model",app.prefs.getString("ai_model:$owner","")).put("encoding",options.encoding).put("chapters",options.chapters).put("titles",options.titles).put("tidy",options.tidy).put("ads",options.ads).put("preserveStructure",options.preserveStructure))
        run(app,owner,id);return true
    }
    private fun run(app:android.content.Context,owner:String,id:String){
        val key="$owner:$id";if(!active.add(key))return
        cancelled.remove(key)
        val context=app.applicationContext;val token=Secrets(context).get("ai_key:$owner");val jobFile=file(context,owner,id)
        runCatching{context.startForegroundService(android.content.Intent(context,ImportService::class.java).putExtra("owner",owner))}
        executor.execute{val library=Library(context)
            try{
                val job=state(context,owner,id)?:return@execute
                val book=library.get(owner,id)?:return@execute;if(book.localRemoved)return@execute
                val source=library.sourceFile(owner,id);val bytes=source.readBytes()
                val cfg=ModelProfiles.resolve(context,owner,"daily","目录优化").copy(session=job.optString("session","$owner:$id")).let{if(context.getSharedPreferences("app",0).getBoolean("ai_directory:$owner",true))it else it.copy(key="")}
                val agent=ImportAgent(cfg);agents[key]=agent;agent.restore(File(jobFile.parentFile,"agent-checkpoint.json"))
                agent.onDiagnostic={code->CrashLog.importDiagnostic(context,code)}
                fun publish(novel:Novel,finished:Boolean){ui{
                    if(key in cancelled)return@ui
                    val currentApp=activity.get()?.takeIf{!it.isDestroyed&&it.profileId==owner}
                    // Keep the visible book stable. The complete snapshot is staged and
                    // applied after the reader closes, without ever replacing text by a placeholder.
                    if(currentApp?.reader?.book?.id==id){currentApp.reader?.updateDirectory(novel);return@ui}
                    val current=library.get(owner,id)?:return@ui;if(current.localRemoved)return@ui
                    val chapters=novel.chapters
                    if(current.chapters!=chapters){current.position=ReaderCore.remapPosition(current.position,current.chapters,chapters);current.notes=current.notes.map{ReaderCore.remap(it,current.chapters,chapters)};current.chapters=chapters;current.revision++
                        val processed=File(jobFile.parentFile,"processed-v${current.revision}.json");val pending=File(jobFile.parentFile,"stream-body.pending")
                        pending.writeText(JSONObject().put("chapters",JSONArray(chapters.map{JSONObject().put("title",it.title).put("text",it.text)})).toString());check(pending.renameTo(processed))
                        library.save(owner,current);currentApp?.reader?.takeIf{it.book.id==id}?.acceptImport(current)}
                    markApplied(context,owner,id)
                }}
                job.put("status","running");write(jobFile,job)
                agent.onSnapshot={snapshot,ready,processed,total->
                    if(key in cancelled)throw java.util.concurrent.CancellationException()
                    job.put("ready",ready).put("processed",processed).put("total",total).put("percent",(processed.toLong()*100/total).toInt()).put("directory",JSONArray(snapshot.chapters.map{it.title}))
                    val staged=File(jobFile.parentFile,"readable-latest.json");val pending=File(jobFile.parentFile,"readable.pending")
                    pending.writeText(JSONObject().put("snapshot",java.util.UUID.randomUUID().toString()).put("title",snapshot.title).put("chapters",JSONArray(snapshot.chapters.map{JSONObject().put("title",it.title).put("text",it.text)})).toString());check(pending.renameTo(staged))
                    write(jobFile,job)
                    publish(snapshot,processed==total)
                }
                val options=ImportAgent.Options(job.optBoolean("encoding"),job.optBoolean("chapters"),job.optBoolean("titles"),job.optBoolean("tidy"),job.optBoolean("preserveStructure",true),job.optBoolean("ads",job.optBoolean("tidy")))
                val novel=agent.process(bytes,book.sourceName,options,job.optString("feedback"),{partial->
                    if(partial.chapters.size>=job.optInt("ready")){job.put("ready",partial.chapters.size).put("directory",JSONArray(partial.chapters.map{it.title}));write(jobFile,job)}
                },existing=if(!job.optBoolean("fresh"))Novel(book.title,book.chapters,"当前正文")else null){message->if(library.get(owner,id)?.localRemoved!=false){agent.cancelled=true}else{job.put("message",message);write(jobFile,job)}}
                if(key in cancelled)throw java.util.concurrent.CancellationException()
                val staged=File(jobFile.parentFile,"agent-result.json");staged.writeText(JSONObject().put("title",novel.title).put("encoding",novel.encoding).put("chapters",JSONArray(novel.chapters.map{JSONObject().put("title",it.title).put("text",it.text)})).toString())
                publish(novel,true)
                val noDirectory=novel.chapters.size==1&&novel.chapters[0].title=="正文"
                job.put("noDirectory",noDirectory)
                job.put("status","complete").put("percent",100).put("processed",job.optInt("total")).put("message",if(noDirectory)"未识别到章节目录，正文可正常阅读"else novel.warnings.lastOrNull()?:"整理完成").put("ready",novel.chapters.size).put("directory",JSONArray(novel.chapters.map{it.title}));write(jobFile,job)
            }catch(e:Exception){if(key !in cancelled){CrashLog.event(context,CrashLog.Event.IMPORT_FAILED,error=e);state(context,owner,id)?.let{write(jobFile,it.put("status","failed").put("message",failureMessage(e)+"；可继续阅读原文"))}}}
            finally{active.remove(key);agents.remove(key);library.close();if(active.isEmpty())context.stopService(android.content.Intent(context,ImportService::class.java))}
        }
    }
    fun retry(app:MainActivity,id:String){val j=state(app,app.profileId,id)?:return;j.put("status","queued");write(file(app,app.profileId,id),j);run(app,app.profileId,id)}
    private fun failureMessage(e:Exception)=if(e.message?.contains("预算")==true)"本次 AI 预算已到，可提高预算后继续"else when((e as? ImportAgent.OutputFailure)?.code){"OUTPUT_LIMIT"->"模型输出超过长度限制";"OUTPUT_INCOMPLETE"->"模型未完整返回结果";"INVALID_JSON"->"模型返回格式异常";"SOURCE_VALIDATION"->"模型结果未通过原文校验";"AUTH"->"API 密钥或权限异常";"RATE_LIMIT"->"请求过于频繁";"NETWORK"->"连接暂时中断";else->"当前片段整理失败"}
    fun markApplied(context:android.content.Context,owner:String,id:String){
        val parent=file(context,owner,id).parentFile
        val staged=File(parent,"readable-latest.json")
        if(staged.isFile)runCatching{File(parent,"applied-snapshot.txt").writeText(JSONObject(staged.readText()).optString("snapshot","legacy"))}
    }
    fun commitLatest(app:MainActivity,id:String){
        val j=state(app,app.profileId,id)?:return
        if(j.optString("status")=="restored")return
        val staged=File(file(app,app.profileId,id).parentFile,"readable-latest.json");if(!staged.isFile)return
        val data=JSONObject(staged.readText());val snapshot=data.optString("snapshot","legacy");val applied=File(staged.parentFile,"applied-snapshot.txt");if(applied.isFile&&applied.readText()==snapshot)return
        val arr=data.getJSONArray("chapters");val book=app.library.get(app.profileId,id)?:return
        val novel=Novel(book.title,(0 until arr.length()).map{arr.getJSONObject(it).let{c->Chapter(c.getString("title"),c.getString("text"))}},"整理稿")
        if(book.chapters!=novel.chapters)app.library.import(app.profileId,novel,app.library.sourceFile(app.profileId,id).readBytes(),book.sourceName,book)
        applied.writeText(snapshot)
    }
    fun restore(app:MainActivity,id:String,original:Boolean){
        val owner=app.profileId;val key="$owner:$id";cancelled.add(key);agents[key]?.cancelled=true
        val book=app.library.get(owner,id)?:return;val source=app.library.sourceFile(owner,id);val previous=File(source.parentFile,"before-organize.json")
        state(app,owner,id)?.let{write(file(app,owner,id),it.put("status","restored").put("message","正在恢复正文"))}
        val originalSource=File(source.parentFile,"original-source.bin").takeIf{it.isFile}?:source
        val originalName=File(source.parentFile,"original-name.txt").takeIf{it.isFile}?.readText()?:book.sourceName
        val novel=if(!original&&previous.isFile){val old=Library.fromJson(JSONObject(previous.readText()));Novel(book.title,old.chapters,"整理前版本")}else ImportAgent(AiConfig("","","")).process(originalSource.readBytes(),originalName,ImportAgent.Options(false,false,false,false))
        app.library.import(owner,novel,source.readBytes(),book.sourceName,book)
        state(app,owner,id)?.let{write(file(app,owner,id),it.put("status","restored").put("message","已恢复正文").put("percent",0).put("ready",0).put("directory",JSONArray(novel.chapters.map{c->c.title})))}
    }
    fun apply(app:MainActivity,id:String){val owner=app.profileId;val j=state(app,owner,id)?:return;check(j.optString("status")=="ready")
        val source=app.library.sourceFile(owner,id);val result=JSONObject(File(source.parentFile,"agent-result.json").readText());val arr=result.getJSONArray("chapters")
        val novel=Novel(result.getString("title"),(0 until arr.length()).map{arr.getJSONObject(it).let{c->Chapter(c.getString("title"),c.getString("text"))}},result.getString("encoding"))
        val book=app.library.get(owner,id)?:return;app.library.import(owner,novel,source.readBytes(),book.sourceName,book)
        write(file(app,owner,id),j.put("status","complete").put("message","整理完成"))
    }
}
