package cn.jiangcheng.lemon.reader

/** Citation typography is not a factual error. Source IDs and quoted words still need real evidence. */
object EvidenceAnswer {
    data class Checked(val text:String,val valid:Boolean,val reason:String="")
    private val citation=Regex("\\[\\[?([^\\[\\]\\n]{1,160})\\]\\]?|【([^【】\\n]{1,160})】")
    private val id=Regex("E\\d+_\\d+_\\d+")
    private val quoteAtEnd=Regex("[“「『\"]([^“”「」『』\"\\n]{4,500})[”」』\"][\\s*，,。.;；:：]*$")
    private val quoteAnywhere=Regex("[“「『\"]([^“”「」『』\"\\n]{4,500})[”」』\"]")
    private fun compact(s:String)=s.filterNot{it.isWhitespace()||it=='\u200b'||it=='\ufeff'}
    private fun contains(source:String,quote:String)=compact(source).contains(compact(quote))
    fun content(raw:String):String=raw.trimStart().removePrefix("```text\n").removePrefix("```\n")
        .replaceFirst(Regex("^ANSWER\\s*[:：]?\\s*"),"").removeSuffix("```").trimEnd()
    private fun resolve(token:String,sources:List<NovelRetrieval.Evidence>):List<NovelRetrieval.Evidence> {
        val ids=id.findAll(token).map{it.value}.toList()
        return if(ids.isNotEmpty())ids.mapNotNull{key->sources.firstOrNull{it.id==key}}
            else sources.filter{it.title==token.trim()}
    }
    private fun token(m:MatchResult)=m.groupValues[1].ifEmpty{m.groupValues[2]}
    private fun label(sources:List<NovelRetrieval.Evidence>)=sources.map{it.title}.distinct().joinToString(""){"【$it】"}
    fun check(raw:String,sources:List<NovelRetrieval.Evidence>):Checked {
        val text=content(raw)
        if(text.startsWith("NEED_MORE"))return Checked("",false,"insufficient")
        var count=0;var failure=""
        val rendered=citation.replace(text){m->
            val t=token(m);val ids=id.findAll(t).map{it.value}.toList();val hits=resolve(t,sources)
            if(ids.any{key->sources.none{it.id==key}}){failure="unknown_source";return@replace ""}
            if(hits.isEmpty())return@replace m.value
            val quote=quoteAtEnd.find(text.substring(0,m.range.first))?.groupValues?.get(1)
            if(quote!=null&&hits.none{contains(it.text,quote)}){failure="quote_mismatch";return@replace m.value}
            count++;label(if(quote==null)hits else hits.filter{contains(it.text,quote)})
        }
        if(failure.isNotEmpty())return Checked(rendered,false,failure)
        if(count>0)return Checked(rendered,true)
        // A provider may omit IDs but give an exact excerpt. Locate that excerpt locally;
        // never assign a source just because a person's name appears in both texts.
        var located=0
        val recovered=quoteAnywhere.replace(rendered){m->
            val hits=sources.filter{contains(it.text,m.groupValues[1])}
            if(hits.isEmpty())m.value else {located++;m.value+label(hits)}
        }
        return Checked(recovered,located>0,if(located>0)""else"missing_source")
    }

    /** Receive cumulative SSE immediately. Wait only for the first valid source anchor,
     * then reveal plain prose continuously while withholding unfinished quotations/IDs. */
    class Stream(private val sources:List<NovelRetrieval.Evidence>,private val emit:(String)->Unit) {
        private var shown=""
        fun accept(raw:String) {
            val text=content(raw)
            if(text.isBlank()||text.startsWith("NEED_MORE")||"ANSWER".startsWith(text))return
            val references=citation.findAll(text).filter{resolve(token(it),sources).isNotEmpty()}.toList()
            var anchor=-1
            for(ref in references){val end=ref.range.last+1;if(check(text.take(end),sources).valid)anchor=end else break}
            if(anchor<0)return
            var end=text.length
            // Don't show a technical ID as it is arriving, or a quote whose source isn't known yet.
            for(open in listOf('[','【','“','「','『','"')){
                val at=text.indexOf(open,anchor)
                if(at>=0)end=minOf(end,at)
            }
            val checked=check(text.take(end),sources)
            if(checked.valid&&checked.text.length>shown.length){shown=checked.text;emit(shown)}
        }
        fun finish(raw:String):Checked=check(raw,sources).also{if(it.valid&&it.text!=shown){shown=it.text;emit(shown)}}
    }
}
