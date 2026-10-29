package cn.jiangcheng.lemon.reader

import java.net.URI
import org.json.JSONObject

object ModelProtocol {
    data class Preset(val id:String,val name:String,val base:String,val models:String="")
    val presets=listOf(
        Preset("qwen","千问 / 百炼","https://dashscope.aliyuncs.com/compatible-mode/v1","qwen-plus"),
        Preset("deepseek","DeepSeek","https://api.deepseek.com"),
        Preset("siliconflow","硅基流动","https://api.siliconflow.cn/v1"),
        Preset("moonshot","Kimi / Moonshot","https://api.moonshot.cn/v1"),
        Preset("glm","智谱 GLM","https://open.bigmodel.cn/api/paas/v4"),
        Preset("openrouter","OpenRouter","https://openrouter.ai/api/v1"),
        Preset("custom","自定义兼容接口",""))
    fun normalizeBase(raw:String):String {
        val value=raw.trim().trimEnd('/').removeSuffix("/chat/completions").removeSuffix("/models").trimEnd('/')
        val uri=runCatching{URI(value)}.getOrNull()
        require(uri!=null&&uri.scheme=="https"&&!uri.host.isNullOrBlank()&&uri.rawUserInfo==null&&uri.rawQuery==null&&uri.rawFragment==null){"请填写完整的 HTTPS 接口地址，不含密钥或查询参数"}
        return value
    }
    fun models(raw:String)=raw.split(Regex("[,，;；\\s]+" )).map{it.trim()}.filter{it.isNotEmpty()}.distinct()
    fun provider(base:String)=when(runCatching{URI(base).host.orEmpty()}.getOrDefault("")){
        "api.deepseek.com"->"deepseek"
        "dashscope.aliyuncs.com"->"qwen"
        else->"custom"
    }
    fun body(config:AiConfig,request:JSONObject,stream:Boolean):JSONObject {
        val body=JSONObject(request.toString()).put("model",config.model).put("stream",stream)
        // Unknown compatible providers receive only standard fields. Advanced options are explicit.
        for(key in listOf("thinking","enable_thinking","thinking_budget","reasoning_effort","stream_options"))body.remove(key)
        when(config.reasoningProtocol){
            "deepseek"->{body.put("thinking",JSONObject().put("type",if(config.thinking=="off")"disabled"else"enabled"));if(config.thinking!="off")body.put("reasoning_effort",config.thinking)}
            "qwen"->{body.put("enable_thinking",config.thinking!="off");if(config.thinking!="off")body.put("thinking_budget",if(config.thinking=="low")1024 else 4096)}
            "effort"->if(config.thinking!="off")body.put("reasoning_effort",config.thinking)
        }
        if(!config.jsonOutput)body.remove("response_format")
        if(config.tokenField=="max_completion_tokens"&&body.has("max_tokens")){val count=body.getInt("max_tokens");body.remove("max_tokens");body.put("max_completion_tokens",count)}
        if(stream&&config.streamUsage)body.put("stream_options",JSONObject().put("include_usage",true))
        return body
    }
}
