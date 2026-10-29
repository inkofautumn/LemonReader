package cn.jiangcheng.lemon.reader

import org.json.JSONObject

/** Accept a complete JSON object, optionally fenced; never silently accept a truncated reply. */
object ModelOutput {
    fun json(response:JSONObject):JSONObject {
        val choice=response.getJSONArray("choices").getJSONObject(0)
        check(choice.optString("finish_reason","stop")=="stop"){"模型结果未完整返回，请稍后继续"}
        val value=choice.getJSONObject("message").getString("content").trim().let{
            if(it.startsWith("```"))it.substringAfter('\n').substringBeforeLast("```").trim()else it
        }
        return JSONObject(value)
    }
}
