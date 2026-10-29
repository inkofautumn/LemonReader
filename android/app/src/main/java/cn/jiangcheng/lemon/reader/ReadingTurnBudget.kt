package cn.jiangcheng.lemon.reader

import org.json.JSONObject

/** Limits the whole user turn, not just its last completion. Estimates are explicitly labelled. */
class ReadingTurnBudget(val inputLimit:Int,val totalLimit:Int=30000,val maxCalls:Int=3) {
    var calls=0;private set
    var input=0;private set
    var output=0;private set
    var estimated=false;private set
    val remaining get()=(inputLimit-input).coerceAtLeast(0)
    fun canSpend(body:JSONObject,reserve:Int=0)=calls<maxCalls&&estimate(body)+reserve<=remaining&&input+output+estimate(body)+body.optInt("max_tokens",1800)+reserve<=totalLimit
    fun begin(body:JSONObject):Int {check(canSpend(body)){"本次查找预算已到，已保留找到的原文"};val count=estimate(body);calls++;input+=count;return count}
    fun finish(response:JSONObject,reserved:Int){val usage=response.optJSONObject("usage");val actual=usage?.optInt("prompt_tokens",0)?:0
        if(actual>0)input+=actual-reserved else estimated=true
        val generated=usage?.optInt("completion_tokens",0)?:0
        if(generated>0)output+=generated else {estimated=true;output+=estimateText(response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty())}
    }
    fun failed(maxOutput:Int=0){estimated=true;output+=maxOutput.coerceAtLeast(0)}
    fun json()=JSONObject().put("calls",calls).put("input",input).put("output",output).put("estimated",estimated).put("inputLimit",inputLimit)
    companion object {
        // Model-independent planning estimate, not an exact tokenizer or a billing guarantee.
        fun estimateText(text:String):Int {var wide=0;var ascii=0;text.codePoints().forEach{if(it>127)wide++ else ascii++};return kotlin.math.ceil(wide*1.5+ascii/3.0).toInt()}
        fun estimate(body:JSONObject)=estimateText(body.optJSONArray("messages")?.toString().orEmpty())+96
    }
}
