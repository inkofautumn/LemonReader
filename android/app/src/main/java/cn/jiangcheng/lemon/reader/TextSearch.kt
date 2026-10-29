package cn.jiangcheng.lemon.reader

import java.text.Normalizer
import java.util.Locale

object TextSearch {
    data class Match(val chapter:Int,val start:Int,val end:Int)
    private data class Indexed(val text:String,val starts:IntArray,val ends:IntArray)
    private fun index(text:String):Indexed {
        val normalized=StringBuilder();val starts=ArrayList<Int>();val ends=ArrayList<Int>();var at=0
        while(at<text.length){val end=at+Character.charCount(text.codePointAt(at));val value=Normalizer.normalize(text.substring(at,end),Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            value.forEach{c->if(!c.isWhitespace()&&c !in "\u200b\ufeff\u200e\u200f"){normalized.append(c);starts.add(at);ends.add(end)}};at=end
        }
        return Indexed(normalized.toString(),starts.toIntArray(),ends.toIntArray())
    }
    fun find(chapters:List<Chapter>,query:String,boundary:Position?=null,limit:Int=20000):List<Match>{
        val needle=index(query).text;if(needle.isBlank())return emptyList()
        val matches=ArrayList<Match>()
        for((chapter,c) in chapters.withIndex()){
            if(boundary!=null&&chapter>boundary.chapter)break
            val text=if(boundary?.chapter==chapter)c.text.take(boundary.offset)else c.text
            val data=index(text);var at=data.text.indexOf(needle)
            while(at>=0){matches.add(Match(chapter,data.starts[at],data.ends[at+needle.length-1]));if(matches.size>=limit)return matches;at=data.text.indexOf(needle,at+needle.length)}
        }
        return matches
    }
    fun order(matches:List<Match>,chapters:List<Chapter>,current:Position,order:String):List<Match>{
        if(order=="从头到尾")return matches.sortedWith(compareBy({it.chapter},{it.start}))
        if(order=="从尾到头")return matches.sortedWith(compareByDescending<Match>{it.chapter}.thenByDescending{it.start})
        val offsets=LongArray(chapters.size);for(i in 1 until chapters.size)offsets[i]=offsets[i-1]+chapters[i-1].text.length
        val position=offsets.getOrElse(current.chapter){0}+current.offset
        return matches.sortedWith(compareBy<Match>{kotlin.math.abs(offsets[it.chapter]+it.start-position)}.thenBy{it.chapter}.thenBy{it.start})
    }
}
