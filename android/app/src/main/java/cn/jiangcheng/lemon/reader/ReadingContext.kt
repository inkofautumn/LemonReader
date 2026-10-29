package cn.jiangcheng.lemon.reader

/** Cuts at paragraph/sentence ends and never reaches beyond the supplied reading boundary. */
object ReadingSegments {
    fun end(text:String,start:Int,limit:Int,maxChars:Int=4800):Int {
        val cap=(start+maxChars).coerceAtMost(limit).coerceAtMost(text.length)
        if(cap>=limit)return cap
        val floor=start+maxChars/2
        for(chars in listOf("\n","。！？!?；;"))for(at in cap-1 downTo floor)if(text[at] in chars)return at+1
        return if(cap>start&&Character.isHighSurrogate(text[cap-1]))cap-1 else cap
    }
}

object ReadingContext {
    private val stop=setOf("什么","怎么","这个","那个","这里","一下","为什么","是谁","人物","剧情","分析","原文","之前","现在","请问","看看","的关","关系")
    fun terms(question:String)=question.split(Regex("[\\s，。？！、：:；;（）()“”\"]+")).filter{it.length>=2}.flatMap{if(it.length>4)it.windowed(2)else listOf(it)}.filter{it !in stop}.distinct().take(48)
    fun allowedQuote(chapters:List<Chapter>,boundary:Position,quote:String):String {
        if(quote.isBlank()||chapters.isEmpty())return ""
        val end=ReaderCore.clamp(boundary,chapters);var tail=""
        for(ch in 0..end.chapter){val allowed=if(ch==end.chapter)chapters[ch].text.take(end.offset)else chapters[ch].text
            if(allowed.contains(quote)||(tail+"\n"+allowed.take(quote.length)).contains(quote))return quote
            tail=(tail+"\n"+allowed.takeLast(quote.length)).takeLast(quote.length)
        }
        return ""
    }
    data class Piece(val chapter:Int,val start:Int,val end:Int,val score:Int)
    fun build(chapters:List<Chapter>,boundary:Position,question:String,maxChars:Int=6000):String {
        if(chapters.isEmpty()||maxChars<=0)return ""
        val p=ReaderCore.clamp(boundary,chapters);val budget=maxChars
        if(budget<100)return chapters[p.chapter].text.take(p.offset).takeLast(budget)
        fun header(ch:Int)="【${chapters[ch].title.take(80)}】\n"
        val chosen=mutableListOf<Piece>();var recentBudget=(budget*.5).toInt().coerceAtMost(2800)
        var ch=p.chapter
        while(ch>=0&&recentBudget>0){val limit=if(ch==p.chapter)p.offset else chapters[ch].text.length;var from=(limit-(recentBudget-header(ch).length-2).coerceAtLeast(0)).coerceAtLeast(0)
            if(from>0&&from<limit&&Character.isLowSurrogate(chapters[ch].text[from]))from++
            if(limit>from){chosen.add(Piece(ch,from,limit,Int.MAX_VALUE));recentBudget-=limit-from+header(ch).length+2};ch--}
        val terms=terms(question);val candidates=mutableListOf<Piece>()
        if(terms.isNotEmpty())for(c in 0..p.chapter){val text=chapters[c].text;val limit=if(c==p.chapter)p.offset else text.length;var at=0
            while(at<limit){val end=ReadingSegments.end(text,at,limit,1100);val snippet=text.substring(at,end);val score=terms.map{t->if(snippet.contains(t,true))if(t.length>2)4 else 1 else 0}.sum()
                if(score>0&&chosen.none{it.chapter==c&&at<it.end&&end>it.start}){candidates.add(Piece(c,at,end,score));if(candidates.size>20){candidates.sortWith(compareByDescending<Piece>{it.score}.thenByDescending{it.chapter}.thenByDescending{it.start});candidates.removeAt(candidates.lastIndex)}}
                at=end
            }
        }
        var remaining=budget-chosen.sumOf{it.end-it.start+header(it.chapter).length+2}
        candidates.sortedWith(compareByDescending<Piece>{it.score}.thenByDescending{it.chapter}.thenByDescending{it.start}).forEach{piece->
            val overhead=header(piece.chapter).length+2
            if(remaining>overhead+80){val take=minOf(piece.end-piece.start,remaining-overhead);chosen.add(piece.copy(end=piece.start+take));remaining-=take+overhead}
        }
        // Local context is always included, even if a name also scores heavily in much older chapters.
        val seen=HashSet<String>()
        return chosen.sortedWith(compareBy({it.chapter},{it.start})).mapNotNull{piece->val t=chapters[piece.chapter].text.substring(piece.start,piece.end);if(!seen.add(t))null else header(piece.chapter)+t}.joinToString("\n\n").take(budget)
    }
}
