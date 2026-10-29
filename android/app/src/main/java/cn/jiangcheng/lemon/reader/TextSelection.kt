package cn.jiangcheng.lemon.reader

/** Offsets are UTF-16, matching StaticLayout and persisted annotations. */
object TextSelection {
    fun boundary(text:String,offset:Int):Int {
        val at=offset.coerceIn(0,text.length)
        return if(at>0&&at<text.length&&text[at].isLowSurrogate()&&text[at-1].isHighSurrogate())at-1 else at
    }
    fun sentence(text:String,offset:Int):Pair<Int,Int>{
        if(text.isEmpty())return 0 to 0
        val at=boundary(text,offset.coerceAtMost(text.lastIndex))
        val stops="。！？!?\n"
        var start=at;var end=at
        while(start>0&&text[start-1] !in stops)start--
        while(end<text.length&&text[end] !in stops)end++
        if(end<text.length&&text[end]!='\n')end++
        while(start<end&&ReaderDisplayText.isPresentationWhitespace(text[start]))start++
        return start to end
    }

    /** Keep persisted excerpts on visible characters instead of paragraph padding. */
    fun trimWhitespace(text:String,start:Int,end:Int):Pair<Int,Int>{
        var first=boundary(text,start.coerceIn(0,text.length))
        var last=boundary(text,end.coerceIn(first,text.length))
        while(first<last&&ReaderDisplayText.isPresentationWhitespace(text[first]))first++
        while(last>first&&ReaderDisplayText.isPresentationWhitespace(text[last-1]))last--
        return first to last
    }

    /** Select the touched sentence without crossing the text currently visible on screen. */
    fun visibleSentence(text:String,offset:Int,visibleStart:Int,visibleEnd:Int):Pair<Int,Int>{
        if(text.isEmpty())return 0 to 0
        val first=boundary(text,visibleStart.coerceIn(0,text.length))
        val last=boundary(text,visibleEnd.coerceIn(first,text.length))
        val sentence=sentence(text,offset.coerceIn(first,(last-1).coerceAtLeast(first)))
        return maxOf(first,sentence.first) to minOf(last,sentence.second)
    }

    /** Existing visible underlines that exactly cover the selected text. */
    fun exactUnderlines(notes:List<Note>,chapter:Int,text:String,start:Int,end:Int):List<Note>{
        val selected=trimWhitespace(text,start,end)
        return notes.filter{note->
            if(note.chapter!=chapter||note.type!="划线"||note.changed)return@filter false
            trimWhitespace(text,note.start,note.end)==selected
        }
    }
}
