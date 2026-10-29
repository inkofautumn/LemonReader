package cn.jiangcheng.lemon.reader

import org.json.JSONObject

/** A clarification reply completes a pending request; it is not a fresh standalone name lookup. */
object ConversationIntent {
    const val clarification = "你指的是哪位人物、哪一次事件？补充一个名字或引用那段原文，我再接着查。"
    data class Request(val original:String,val question:String,val clarified:Boolean=false)

    fun text(value:JSONObject,key:String):String {
        val raw=value.opt(key)
        if(raw !is String)return ""
        val s=raw.trim()
        return if(s.lowercase() in setOf("null","none","false","无","无需澄清","不需要澄清"))""else s
    }
    fun pending(entry:JSONObject)=text(entry,"pendingQuestion").isNotBlank()||entry.optString("answer")==clarification
    fun resolve(question:String,safeHistory:List<JSONObject>):Request {
        // A new explicit task must not inherit an unanswered older topic.
        val fragment=question.trim()
        if(fragment.length !in 1..60||Regex("[?？]|为什么|为何|怎么|什么|如何|解读|回顾|总结|分析|梳理|介绍|换个|另外|另一个|新问题").containsMatchIn(fragment))return Request(question,question)
        val last=safeHistory.lastOrNull()?:return Request(question,question)
        if(!pending(last))return Request(question,question)
        val chain=safeHistory.asReversed().takeWhile(::pending)
        val original=chain.firstNotNullOfOrNull{text(it,"pendingQuestion").takeIf(String::isNotBlank)}
            ?:chain.last().optString("question")
        if(original.isBlank())return Request(question,question)
        return Request(original,"$original\n读者最新补充（若与先前称呼不同，以本次为准，但需由原文核实）：$fragment",true)
    }
}
