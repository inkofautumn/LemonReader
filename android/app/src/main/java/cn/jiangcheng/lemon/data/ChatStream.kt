package cn.jiangcheng.lemon.data

import org.json.JSONObject
import org.json.JSONArray
import java.io.BufferedReader

/** OpenAI-compatible SSE: collect usage after finish_reason, never display reasoning. */
object ChatStream {
    fun read(reader:BufferedReader,onText:(String)->Unit):JSONObject {
        val text=StringBuilder();val event=StringBuilder();var usage:JSONObject?=null;var finished=false;var done=false;var finishReason=""
        fun dispatch(){
            val raw=event.toString().trim();event.clear()
            if(raw.isEmpty())return
            if(raw=="[DONE]"){done=true;return}
            val j=JSONObject(raw)
            check(!j.has("error")){"回答暂时无法完成"}
            j.optJSONObject("usage")?.let{usage=it}
            val choice=j.optJSONArray("choices")?.optJSONObject(0)?:return
            val delta=choice.optJSONObject("delta")?.optString("content").orEmpty()
            if(delta.isNotEmpty()&&delta!="null"){text.append(delta);onText(text.toString())}
            if(!choice.isNull("finish_reason")&&choice.optString("finish_reason").isNotBlank()){finished=true;finishReason=choice.getString("finish_reason")}
        }
        while(!done){val line=reader.readLine()?:break;if(line.isEmpty())dispatch()else if(line.startsWith("data:")){if(event.isNotEmpty())event.append('\n');event.append(line.substring(5).trimStart())}}
        if(event.isNotEmpty())dispatch()
        check(done||finished){"回答连接中断，请重试"};check(text.isNotBlank()){"模型未返回正文，请重试"}
        check(finishReason.isBlank()||finishReason=="stop"){if(finishReason=="length")"回答达到输出上限，尚未完成，请缩小问题范围"else"模型未能完成回答（$finishReason）"}
        return JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason",finishReason.ifBlank{"stop"}).put("message",JSONObject().put("content",text.toString())))).apply{usage?.let{put("usage",it)}}
    }
}
