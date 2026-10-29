package cn.jiangcheng.lemon.reader

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.util.UUID

data class Chapter(val title: String, val text: String)
data class Novel(val title: String, val chapters: List<Chapter>, val encoding: String, val warnings: List<String> = emptyList(), val cover: ByteArray? = null)
data class Position(val chapter: Int, val offset: Int)
data class Note(
    val id: String = UUID.randomUUID().toString(), val chapter: Int, val start: Int, val end: Int,
    val quote: String, val body: String, val type: String = "札记", val changed: Boolean = false,
    val replacement: String? = null, val before: String = "", val after: String = ""
)
data class Chronicle(val id: String = UUID.randomUUID().toString(), val kind: String, val title: String,
    val content: String, val chapter: Int, val offset: Int, val manual: Boolean = false,
    val deleted: Boolean = false, val revision: Int = 1, val evidence: String = "", val entity:String="", val contextEnd:Int=-1)

object ReaderCore {
    val chapterPattern = Regex("^\\s*((第[0-9０-９零〇一二三四五六七八九十百千万两]+[章节卷回部篇].{0,55})|((?:序章|序言|前言|楔子|尾声|后记|番外).{0,40})|(?:Chapter\\s+\\d+.{0,55}))\\s*$", RegexOption.IGNORE_CASE)

    fun decode(bytes: ByteArray, forced: String? = null): Pair<String, String> {
        val encoding = forced ?: when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> "UTF-8"
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> "UTF-16LE"
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> "UTF-16BE"
            else -> try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)); "UTF-8"
            } catch (_: Exception) { "GB18030" }
        }
        return String(bytes, Charset.forName(encoding)).removePrefix("\uFEFF") to encoding
    }

    fun parseTxt(bytes: ByteArray, title: String, forced: String? = null, rule: String? = null, tidy: Boolean = true, recognize:Boolean=true, completeTitles:Boolean=false, correctEncoding:Boolean=true): Novel {
        val (decoded, encoding) = decode(bytes, forced ?: if(correctEncoding)null else "UTF-8")
        val source = decoded.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "")
        val sourceLines=source.lines()
        val detected=if(recognize&&rule.isNullOrBlank())TxtDirectory.headings(sourceLines)else emptyMap()
        val pattern = if (rule.isNullOrBlank()) null else Regex(rule)
        val chapters = mutableListOf<Chapter>()
        var currentTitle = "正文"
        val lines = mutableListOf<String>()
        fun flush(frontMatter:Boolean=false) {
            val body = if (tidy) lines.joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim() else lines.joinToString("\n").trim()
            if (body.isNotBlank() || currentTitle != "正文") chapters.add(Chapter(if(frontMatter&&currentTitle=="正文")"书前资料"else currentTitle, body))
            lines.clear()
        }
        for ((index,line) in sourceLines.withIndex()) {
            val heading=detected[index]?:if(recognize&&line.length<=100&&pattern?.matches(line.trim())==true)line.trim()else null
            if (heading!=null) { flush(chapters.isEmpty()&&currentTitle=="正文"); currentTitle = heading }
            else lines.add(line)
        }
        flush()
        if (chapters.isEmpty()) error("文件中没有可阅读的正文")
        val warnings = mutableListOf<String>()
        if (recognize && completeTitles && chapters.size == 1 && chapters[0].title == "正文") {
            val text=chapters.single().text;chapters.clear();var start=0
            while(start<text.length){
                var end=(start+6000).coerceAtMost(text.length)
                if(end<text.length){
                    val paragraph=text.lastIndexOf('\n',end)
                    if(paragraph>=start+3000)end=paragraph+1
                    if(end>start&&Character.isHighSurrogate(text[end-1]))end--
                }
                chapters.add(Chapter("第${chapters.size+1}节",text.substring(start,end)));start=end
            }
            warnings.add("未识别到原书章节，已自动分节；可调整目录规则")
        }
        else if(recognize&&chapters.size==1&&chapters[0].title=="正文")warnings.add("未识别到章节目录，正文可正常阅读；可在目录设置中重新识别")
        if ('\uFFFD' in source) warnings.add("存在无法解码的字符，请切换编码检查原文件")
        return Novel(title.substringBeforeLast('.'), chapters, encoding, warnings)
    }

    fun clamp(position: Position, chapters: List<Chapter>): Position {
        val index = position.chapter.coerceIn(0, chapters.lastIndex.coerceAtLeast(0))
        return Position(index, position.offset.coerceIn(0, chapters.getOrNull(index)?.text?.length ?: 0))
    }

    /** Relocate a saved position after chapter splitting using unique surrounding text. */
    fun remapPosition(position:Position,old:List<Chapter>,newer:List<Chapter>):Position {
        val p=clamp(position,old);val source=old.getOrNull(p.chapter)?.text.orEmpty()
        if(old==newer)return clamp(p,newer)
        for(width in listOf(48,24,12)){
            val start=(p.offset-width/2).coerceAtLeast(0);val anchor=source.substring(start,(start+width).coerceAtMost(source.length))
            if(anchor.length<8)continue
            val hits=newer.flatMapIndexed{index,c->val first=c.text.indexOf(anchor);if(first<0)emptyList()else if(c.text.indexOf(anchor,first+1)>=0)listOf(Position(-1,0),Position(-1,0))else listOf(Position(index,first+p.offset-start))}
            if(hits.size==1&&hits[0].chapter>=0)return clamp(hits[0],newer)
        }
        val title=old.getOrNull(p.chapter)?.title
        val matching=newer.indices.filter{newer[it].title==title}
        if(matching.size==1)return clamp(Position(matching.first(),p.offset),newer)
        if(old.size==newer.size)return clamp(p,newer)
        // Without a reliable anchor restart safely, rather than move the spoiler boundary forward.
        return Position(0,0)
    }

    /** Only already allowed source text reaches retrieval; future chapters never enter scoring. */
    fun directoryTitle(title:String,index:Int,mode:String):String=when(mode){"仅显示编号"->"第${index+1}章";"两者都显示"->"（第${index+1}章）$title";else->title}
    fun citationTitles(value:String,chapters:List<Chapter>):String = Regex("\\[第(\\d+)章[·・ ·]*字\\s*\\d+\\]").replace(value){match->
        chapters.getOrNull((match.groupValues[1].toIntOrNull()?:0)-1)?.let{"【${it.title}】"}?:match.value
    }
    fun context(chapters: List<Chapter>, boundary: Position, question: String, maxChars: Int = 24000): String {
        val end = clamp(boundary, chapters)
        val eligible = chapters.take(end.chapter + 1).mapIndexed { index, chapter ->
            index to if (index == end.chapter) chapter.text.take(end.offset) else chapter.text
        }
        val tokens = question.split(Regex("[\\s，。？！、：:]+" )).filter { it.length >= 2 }.flatMap { word ->
            if (word.length <= 4) listOf(word) else word.windowed(2)
        }.distinct()
        data class Piece(val chapter: Int, val offset: Int, val text: String, val score: Int)
        val pieces = eligible.flatMap { (chapter, text) -> text.chunked(1400).mapIndexed { index, block ->
            Piece(chapter, index * 1400, block, tokens.count { block.contains(it, true) } * 3 + if (chapter == end.chapter) 2 else 0)
        }}
        var remaining = maxChars
        val chosen = pieces.sortedWith(compareByDescending<Piece> { it.score }.thenByDescending { it.chapter }.thenByDescending { it.offset }).mapNotNull {
            if (remaining <= 0) null else it.copy(text = it.text.take(remaining)).also { piece -> remaining -= piece.text.length }
        }.sortedWith(compareBy<Piece> { it.chapter }.thenBy { it.offset })
        return chosen.joinToString("\n\n") { "【${chapters[it.chapter].title}】\n${it.text}" }
    }

    fun remap(note: Note, old: List<Chapter>, newer: List<Chapter>): Note {
        val title = old.getOrNull(note.chapter)?.title
        val exactTitles = newer.indices.filter { newer[it].title == title }
        var index = if (exactTitles.size == 1) exactTitles.first() else -1
        if(index<0&&note.type!="书签"&&note.quote.isNotBlank()){
            val hits=newer.flatMapIndexed{chapter,c->val start=c.text.indexOf(note.quote);if(start<0)emptyList()else if(c.text.indexOf(note.quote,start+1)>=0)listOf(-1 to 0,-1 to 0)else listOf(chapter to start)}
            if(hits.size==1&&hits.first().first>=0){val(chapter,start)=hits.first();return note.copy(chapter=chapter,start=start,end=start+note.quote.length,changed=false,replacement=null)}
            // Renamed chapters may still be identified by a unique pair of unchanged
            // surrounding anchors. A changed quote remains a user-confirmed replacement.
            if(note.before.isNotEmpty()&&note.after.isNotEmpty()){
                val anchored=newer.indices.filter{chapter->
                    val text=newer[chapter].text;val before=text.indexOf(note.before)
                    val after=if(before>=0)text.indexOf(note.after,before+note.before.length)else -1
                    before>=0&&text.indexOf(note.before,before+1)<0&&after>=0&&text.indexOf(note.after,after+1)<0
                }
                if(anchored.size==1)index=anchored.single()
            }
        }
        if(index<0&&note.type=="书签"){val p=remapPosition(Position(note.chapter,note.start),old,newer);return note.copy(chapter=p.chapter,start=p.offset,end=p.offset,changed=p==Position(0,0)&&note.start>0)}
        val body = newer.getOrNull(index)?.text ?: return note.copy(changed = true, replacement = null)
        if(note.type=="书签")return note.copy(chapter=index,start=note.start.coerceIn(0,body.length),end=note.start.coerceIn(0,body.length),changed=false)
        if(old.getOrNull(note.chapter)?.text==body)return note.copy(chapter=index)
        val first = body.indexOf(note.quote)
        if (note.quote.isNotEmpty() && first >= 0 && body.indexOf(note.quote, first + 1) < 0) {
            return note.copy(chapter = index, start = first, end = first + note.quote.length, changed = false, replacement = null)
        }
        // Only offer a replacement when both surrounding anchors uniquely agree.
        val beforeIndex = if (note.before.isNotEmpty()) body.indexOf(note.before) else -1
        val start = beforeIndex + note.before.length
        val afterIndex = if (beforeIndex >= 0 && note.after.isNotEmpty()) body.indexOf(note.after, start) else -1
        val unique = beforeIndex >= 0 && body.indexOf(note.before, beforeIndex + 1) < 0 && afterIndex >= start && body.indexOf(note.after, afterIndex + 1) < 0
        return if (unique) note.copy(chapter = index, start = start, end = afterIndex, changed = true, replacement = body.substring(start, afterIndex))
        else note.copy(changed = true, replacement = null)
    }

    fun chronicleKey(entry:Chronicle):String = "${entry.kind}:${entry.title}" + if(entry.kind=="时间线") ":${entry.chapter}:${entry.offset}:${entry.evidence}" else ""
    fun mergeChronicles(old: List<Chronicle>, generated: List<Chronicle>): List<Chronicle> {
        val result = old.toMutableList()
        for (entry in generated) {
            val related=result.filter{it.revision==entry.revision&&it.kind==entry.kind&&ChronicleCatalog.name(it.title)==ChronicleCatalog.name(entry.title)}
            if(related.any{it.deleted})continue
            // New evidence is a new dated observation, never an overwrite of an earlier snapshot.
            val duplicate=related.any{it.chapter==entry.chapter&&it.offset==entry.offset&&it.evidence==entry.evidence}
            if(!duplicate)result.add(entry.copy(entity=related.firstOrNull{it.entity.isNotBlank()}?.entity?:entry.entity))
        }
        return result
    }
}
