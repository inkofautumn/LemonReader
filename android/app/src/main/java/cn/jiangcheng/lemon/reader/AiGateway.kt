package cn.jiangcheng.lemon.reader

import cn.jiangcheng.lemon.data.ModelHttp
import org.json.JSONArray
import org.json.JSONObject

/** Every model call has explicit thinking parameters and a bounded, accountable session. */
object AiGateway {
    private val spent=mutableMapOf<String,Int>()
    private val reserved=mutableMapOf<String,Int>()
    fun request(config:AiConfig,request:JSONObject,onText:((String)->Unit)?=null):JSONObject {
        config.cancellation?.check()
        require(config.key.isNotBlank()&&config.model.isNotBlank()){ "请在模型与 AI 中添加可用模型" }
        val base=ModelProtocol.normalizeBase(config.base)
        val body=ModelProtocol.body(config,request,onText!=null)
        val allowance=body.optInt(config.tokenField,1200)
        // UTF-8 byte count is a conservative input allowance, not a displayed exact tokenizer count.
        val estimate=body.optJSONArray("messages").toString().toByteArray().size+allowance+512
        val usagePrefs=config.context?.getSharedPreferences("ai-budget:${config.owner}",0)
        synchronized(this){
            if(config.session !in spent)spent[config.session]=usagePrefs?.getInt(config.session,0)?:0
            check((spent[config.session]?:0)+(reserved[config.session]?:0)+estimate<=config.budget){"本次 AI 预算已到，已保留结果；可提高预算后继续"}
            reserved[config.session]=(reserved[config.session]?:0)+estimate
        }
        val started=System.currentTimeMillis()
        var actual:Int?=null;var chargeEstimate=true
        try{
            val result=if(onText==null)ModelHttp.request(base,"/chat/completions",body,config.key,cancellation=config.cancellation)else ModelHttp.stream(base,body,config.key,onText,config.cancellation)
            val usage=result.optJSONObject("usage")
            actual=usage?.optInt("total_tokens")?.takeIf{it>0}
            runCatching{record(config,usage,started,actual==null)}
            return result
        }catch(e:Exception){chargeEstimate=!(e is ModelHttp.Failure&&e.status in listOf(400,401,403,404,405,413,422,429));runCatching{record(config,null,started,chargeEstimate)};throw e}
        finally{synchronized(this){reserved[config.session]=((reserved[config.session]?:estimate)-estimate).coerceAtLeast(0);spent[config.session]=(spent[config.session]?:0)+(actual?:if(chargeEstimate)estimate else 0);usagePrefs?.edit()?.putInt(config.session,spent[config.session]?:0)?.apply();if(reserved[config.session]==0){reserved.remove(config.session);if(usagePrefs!=null)spent.remove(config.session)}}}
    }
    @Synchronized private fun record(config:AiConfig,usage:JSONObject?,started:Long,unknown:Boolean){
        val prefs=config.context?.getSharedPreferences("ai-usage:${config.owner}",0)?:return
        val entries=runCatching{JSONArray(prefs.getString("entries","[]"))}.getOrDefault(JSONArray())
        val input=usage?.optInt("prompt_tokens")?:0
        val hit=usage?.optInt("prompt_cache_hit_tokens",usage.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens")?:0)?:0
        val output=usage?.optInt("completion_tokens")?:0
        entries.put(JSONObject().put("time",started).put("session",config.session).put("task",config.task).put("model",config.model).put("input",input).put("hit",hit).put("miss",(input-hit).coerceAtLeast(0)).put("output",output).put("reasoning",usage?.optJSONObject("completion_tokens_details")?.optInt("reasoning_tokens")?:0).put("ms",System.currentTimeMillis()-started).put("unknown",unknown))
        while(entries.length()>500)entries.remove(0)
        prefs.edit().putString("entries",entries.toString()).putLong("total",prefs.getLong("total",0)+input+output).putInt("unknown",prefs.getInt("unknown",0)+if(unknown)1 else 0).apply()
    }
}
