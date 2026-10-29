package cn.jiangcheng.lemon.reader

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ConversationMemory {
    data class Window(val recent:List<JSONObject>,val older:List<JSONObject>,val summary:String,val processed:Int) {
        fun recentText()=recent.joinToString("\n\n"){"读者：${it.optString("question")}\n说书人（旧回答不是事实依据）：${it.optString("answer")}"}
    }
    private fun file(context:Context,owner:String,book:String,conversation:String,mode:String):File {
        fun key(s:String)=android.util.Base64.encodeToString(s.toByteArray(),11)
        return File(context.cacheDir,"conversation-memory/${key(owner)}/${key(book)}/${key(conversation+":"+mode)}.json").apply{parentFile!!.mkdirs()}
    }
    fun safe(entries:List<JSONObject>,revision:Int,boundary:Position,mode:String)=entries.filter{
        it.optInt("revision")==revision&&(it.optString("mode")=="天机")== (mode=="天机")&&
            (it.optInt("chapter")<boundary.chapter||it.optInt("chapter")==boundary.chapter&&it.optInt("offset")<=boundary.offset)
    }
    fun window(context:Context?,owner:String,book:String,conversation:String,mode:String,revision:Int,boundary:Position,entries:List<JSONObject>,profile:String=""):Window {
        val safe=safe(entries,revision,boundary,mode);val recent=mutableListOf<JSONObject>();var length=0
        for(e in safe.asReversed()){val size=e.optString("question").length+e.optString("answer").length+40
            if(recent.size>=4||recent.isNotEmpty()&&length+size>4400)break
            recent.add(0,e);length+=size
        }
        val older=safe.dropLast(recent.size)
        val cached=if(context==null)null else runCatching{JSONObject(AtomicFile(file(context,owner,book,conversation,mode)).readFully().toString(Charsets.UTF_8))}.getOrNull()
        val processed=cached?.optInt("processed",0)?:0
        val valid=cached!=null&&cached.optString("profile")==profile&&cached.optInt("revision")==revision&&cached.optString("mode")==mode&&processed in 1..older.size&&
            (cached.optInt("chapter")<boundary.chapter||cached.optInt("chapter")==boundary.chapter&&cached.optInt("offset")<=boundary.offset)&&
            cached.optString("prefix")==JSONArray(older.take(processed)).toString()
        return Window(recent,older,if(valid)cached!!.optString("summary")else"",if(valid)processed else 0)
    }
    fun save(context:Context?,owner:String,book:String,conversation:String,mode:String,revision:Int,boundary:Position,older:List<JSONObject>,summary:String,profile:String=""){
        if(context==null||older.isEmpty()||summary.isBlank())return
        val data=JSONObject().put("profile",profile).put("revision",revision).put("mode",mode).put("chapter",boundary.chapter).put("offset",boundary.offset)
            .put("processed",older.size).put("prefix",JSONArray(older).toString()).put("summary",summary.take(1800))
        val target=AtomicFile(file(context,owner,book,conversation,mode));val stream=target.startWrite()
        try{stream.write(data.toString().toByteArray());target.finishWrite(stream)}catch(e:Exception){target.failWrite(stream);throw e}
    }
}
