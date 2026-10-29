package cn.jiangcheng.lemon.reader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlin.math.ln

/** Search data is disposable. All returned material is clipped to the frozen reading boundary. */
class NovelRetrieval(private val chapters:List<Chapter>, private val boundary:Position,
                     context:Context?=null, owner:String="", bookId:String="", revision:Int=0,
                     private val indexing:Boolean=true,private val cancelled:()->Boolean={false}):Closeable {
    data class Evidence(val chapter:Int,val start:Int,val end:Int,val text:String,val title:String,val score:Double=0.0){
        val id get()="E${chapter+1}_${start}_${end}"
        fun tagged()="【$title】[$id]\n$text"
    }
    data class Plan(val queries:List<String>,val names:List<String>,val chapter:Int?,val current:Boolean,val followup:Boolean,val complex:Boolean)
    private val cutoff=ReaderCore.clamp(boundary,chapters)
    private val db:SQLiteDatabase
    var rebuiltChapters=0;private set
    init {
        fun namespace(s:String)=android.util.Base64.encodeToString(s.toByteArray(),11)
        val file=if(indexing&&context!=null&&bookId.isNotBlank())File(context.cacheDir,"reading-index/${namespace(owner)}/${namespace(bookId)}.db").apply{parentFile!!.mkdirs()}else null
        db=if(file==null)SQLiteDatabase.create(null)else SQLiteDatabase.openOrCreateDatabase(file,null)
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY,v TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS chapters(c INTEGER PRIMARY KEY,source TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS chunks(id INTEGER PRIMARY KEY,c INTEGER,s INTEGER,e INTEGER,body TEXT)")
            db.execSQL("CREATE INDEX IF NOT EXISTS chunk_position ON chunks(c,s)")
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS words USING fts4(terms,tokenize=unicode61)")
            val version="2:$revision"
            val old=db.rawQuery("SELECT v FROM meta WHERE k='version'",null).use{if(it.moveToFirst())it.getString(0)else""}
            if(old!=version){db.beginTransaction();try{db.execSQL("DELETE FROM words");db.execSQL("DELETE FROM chunks");db.execSQL("DELETE FROM chapters");db.execSQL("INSERT OR REPLACE INTO meta VALUES('version',?)",arrayOf(version));db.setTransactionSuccessful()}finally{db.endTransaction()}}
            if(indexing&&chapters.isNotEmpty())for(c in 0..cutoff.chapter){checkCancelled();val source=chapters[c].text.let{if(c==cutoff.chapter)it.take(cutoff.offset)else it}
                // A cached longer prefix is safe only when it agrees with the complete visible text.
                val same=db.rawQuery("SELECT length(source)>=length(?) AND substr(source,1,length(?))=? FROM chapters WHERE c=?",arrayOf(source,source,source,c.toString())).use{it.moveToFirst()&&it.getInt(0)==1}
                if(same)continue
                db.beginTransaction()
                try {
                    db.execSQL("DELETE FROM words WHERE docid IN (SELECT id FROM chunks WHERE c=?)",arrayOf(c))
                    db.execSQL("DELETE FROM chunks WHERE c=?",arrayOf(c))
                    val insert=db.compileStatement("INSERT INTO chunks(c,s,e,body) VALUES(?,?,?,?)")
                    val terms=db.compileStatement("INSERT INTO words(docid,terms) VALUES(?,?)")
                    try{var start=0;while(start<source.length){checkCancelled();val end=ReadingSegments.end(source,start,source.length,800);val text=source.substring(start,end)
                        insert.bindLong(1,c.toLong());insert.bindLong(2,start.toLong());insert.bindLong(3,end.toLong());insert.bindString(4,text);val id=insert.executeInsert()
                        terms.bindLong(1,id);terms.bindString(2,tokens(text).joinToString(" "));terms.executeInsert();start=end
                    }}finally{insert.close();terms.close()}
                    db.execSQL("INSERT OR REPLACE INTO chapters(c,source) VALUES(?,?)",arrayOf<Any>(c,source));db.setTransactionSuccessful();rebuiltChapters++
                }finally{db.endTransaction()}
            }
        }catch(e:Exception){db.close();throw e}
    }
    private fun checkCancelled(){if(cancelled()||Thread.currentThread().isInterrupted)throw CancellationException("查找已停止")}
    override fun close(){db.close()}
    private fun limit(c:Int)=if(c==cutoff.chapter)cutoff.offset else chapters[c].text.length
    fun at(c:Int,start:Int,end:Int,score:Double=0.0):Evidence? {
        if(c !in chapters.indices||c>cutoff.chapter)return null
        val text=chapters[c].text;var a=start.coerceIn(0,limit(c));var b=end.coerceIn(a,limit(c))
        if(a<text.length&&a>0&&Character.isLowSurrogate(text[a]))a++
        if(b>0&&b<text.length&&Character.isHighSurrogate(text[b-1]))b--
        return if(b>a)Evidence(c,a,b,text.substring(a,b),chapters[c].title.take(100),score)else null
    }
    fun current(maxChars:Int=2000):List<Evidence>{val result=mutableListOf<Evidence>();var remaining=maxChars
        for(c in cutoff.chapter downTo 0){if(remaining<=0)break;at(c,(limit(c)-remaining).coerceAtLeast(0),limit(c),100.0)?.let{result.add(it);remaining-=it.text.length}}
        return result.reversed()
    }
    fun search(plan:Plan,extra:List<String> = emptyList(),seeds:List<Position> = emptyList()):List<Evidence> {
        checkCancelled();if(plan.chapter!=null&&plan.chapter !in 0..cutoff.chapter)return emptyList()
        val q=(plan.queries+extra).distinct().takeLast(8);val needles=q.flatMap(::queryTerms).distinct().take(96)
        data class Candidate(val id:Long,val chapter:Int,val start:Int,val end:Int,val score:Double)
        val candidates=mutableListOf<Candidate>()
        if(needles.isNotEmpty()){
            val match=needles.joinToString(" OR "){"\"${it.replace("\"", "\"\"")}\""}
            val sql="SELECT chunks.id,c,s,e,matchinfo(words,'pcnx') FROM words JOIN chunks ON chunks.id=words.docid WHERE words MATCH ? AND (c<? OR (c=? AND s<?))"+(if(plan.chapter!=null)" AND c=?"else"")
            val args=mutableListOf(match,cutoff.chapter.toString(),cutoff.chapter.toString(),cutoff.offset.toString());plan.chapter?.let{args.add(it.toString())}
            db.rawQuery(sql,args.toTypedArray()).use{cursor->while(cursor.moveToNext()){checkCancelled();val bytes=ByteBuffer.wrap(cursor.getBlob(4)).order(ByteOrder.nativeOrder());val p=bytes.int;val cols=bytes.int;val n=bytes.int.coerceAtLeast(1);var score=0.0;val length=(cursor.getInt(3)-cursor.getInt(2)).coerceAtLeast(1)
                repeat(p){repeat(cols){val tf=bytes.int;bytes.int;val df=bytes.int;if(tf>0)score+=ln(1.0+(n-df+.5)/(df+.5))*tf*2.2/(tf+1.2*(.25+.75*length/650.0))}}
                candidates.add(Candidate(cursor.getLong(0),cursor.getInt(1),cursor.getInt(2),cursor.getInt(3),score))
            }}
        }
        val results=candidates.sortedWith(compareByDescending<Candidate>{it.score}.thenBy{it.chapter}.thenBy{it.start}).take(80).mapNotNull{candidate->
            val e=at(candidate.chapter,candidate.start,candidate.end,candidate.score)?:return@mapNotNull null
            val normalized=normalize(e.text);val hits=needles.count{normalized.contains(it)}
            if(hits==0)return@mapNotNull null // cached block may match only text after the cutoff
            val eventHits=needles.filter{t->plan.names.none{normalize(it).contains(t)}}.count{normalized.contains(it)}
            val names=plan.names.count{e.text.contains(it)}
            e.copy(score=e.score+names*1.5+eventHits*.7+q.count{it.length in 2..60&&e.text.contains(it)}*12)
        }.toMutableList()
        seeds.filter{it.chapter<=cutoff.chapter&&(plan.chapter==null||it.chapter==plan.chapter)&&(it.chapter<cutoff.chapter||it.offset<=cutoff.offset)}.take(8).forEach{at(it.chapter,it.offset-450,it.offset+350,8.0)?.let(results::add)}
        // Explicit chapter remains useful even when the user's wording doesn't occur literally.
        if(plan.chapter!=null&&results.isEmpty()){
            val c=plan.chapter;if(c in 0..cutoff.chapter){var s=0;while(s<limit(c)&&results.size<12){val e=(s+800).coerceAtMost(limit(c));at(c,s,e,1.0)?.let(results::add);s=e}}
        }
        if(plan.current&&plan.chapter==null)results.addAll(current())
        return results.distinctBy{it.chapter to it.start}.sortedWith(compareByDescending<Evidence>{it.score}.thenBy{it.chapter}.thenBy{it.start}).take(40)
    }
    fun pack(candidates:List<Evidence>,maxChars:Int,preferred:List<String> = emptyList(),query:String="",pinned:List<Evidence> = emptyList()):List<Evidence> {
        val selected=mutableListOf<Evidence>();var remaining=maxChars
        // Preserve the actual reading scene before fitting optional search hits around it.
        // In a tight budget, retain the end at the reading position, not an older block prefix.
        for(hit in pinned.asReversed()){
            if(remaining<=80)break
            at(hit.chapter,maxOf(hit.start,hit.end-(remaining-80)),hit.end,hit.score)?.let{selected.add(it);remaining-=it.text.length+80}
        }
        val order=candidates.sortedByDescending{if(it.id in preferred)100000.0+it.score else it.score}
        for(hit in order){checkCancelled();if(remaining<150)break
            if(selected.any{it.chapter==hit.chapter&&it.start<=hit.start&&it.end>=hit.end})continue
            var a=(hit.start-240).coerceAtLeast(0);var b=(hit.end+320).coerceAtMost(limit(hit.chapter))
            val overlap=selected.filter{it.chapter==hit.chapter&&a<=it.end&&b>=it.start}
            if(overlap.isNotEmpty()){a=minOf(a,overlap.minOf{it.start});b=maxOf(b,overlap.maxOf{it.end});val returned=overlap.sumOf{it.text.length+80};if(b-a+80>remaining+returned)continue;selected.removeAll(overlap.toSet());remaining+=returned}
            if(b-a+80>remaining){
                val text=chapters[hit.chapter].text;val anchor=queryTerms(query).map{text.indexOf(it,hit.start)}.filter{it in hit.start until hit.end}.minOrNull()?:hit.start
                a=maxOf(hit.start,anchor-(remaining-80)/3);b=minOf(limit(hit.chapter),a+remaining-80)
            }
            at(hit.chapter,a,b,hit.score)?.let{selected.add(it);remaining-=it.text.length+80}
        }
        return selected.sortedWith(compareBy({it.chapter},{it.start}))
    }
    companion object {
        private val stop=setOf("什么","怎么","这个","那个","这里","一下","为何","为什么","是谁","人物","剧情","分析","原文","之前","现在","请问","看看","关系","时候","是不是","为什么会","后来","当时","一下子","的","了","吗","呢","他","她","它")
        fun normalize(text:String)=Normalizer.normalize(text,Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        fun tokens(text:String):List<String>{val s=normalize(text);val out=ArrayList<String>();val iterator=BreakIterator.getWordInstance(Locale.CHINA);iterator.setText(s);var a=iterator.first();var b=iterator.next()
            while(b!=BreakIterator.DONE){val word=s.substring(a,b);if(word.any{it.isLetterOrDigit()}&&word.length in 1..40)out.add(word);a=b;b=iterator.next()}
            for(i in s.indices){val c=s[i];if(c in '\u3400'..'\u9fff'){out.add(c.toString());if(i+1<s.length&&s[i+1] in '\u3400'..'\u9fff')out.add(s.substring(i,i+2))}}
            return out
        }
        fun queryTerms(query:String):List<String>{val stripped=normalize(query).replace(Regex("第[零〇一二两三四五六七八九十百千万0-9]+[章节回卷]")," ")
            val words=tokens(stripped).filter{it.length>=2&&it !in stop&&it !in setOf("为什","么会","为能","那他","那她","的时")}.distinct()
            return words.sortedByDescending{it.length}.take(48)
        }
        private fun number(raw:String):Int? {raw.toIntOrNull()?.let{return it};val digit="零一二三四五六七八九";var value=0;var pending=0;for(c in raw){val d=digit.indexOf(c);when{d>=0->pending=d;c=='两'->pending=2;c=='十'||c=='百'||c=='千'->{value+=(if(pending==0)1 else pending)*when(c){'十'->10;'百'->100;else->1000};pending=0};else->return null}};return value+pending}
        fun plan(question:String,quote:String,chapters:List<Chapter>,boundary:Position,known:List<Chronicle>,historyQuestions:List<String>):Plan {
            val safe=CharacterAliases.resolveVisible(ChronicleCatalog.visible(known,boundary,known.firstOrNull()?.revision?:0))
            val groups=safe.filter{it.kind=="人物"}.groupBy{it.entity.ifBlank{it.title}}.values
            val direct=groups.filter{g->g.any{question.contains(it.title)}}
            val follow=Regex("(?:他|她|它|他们|她们|这人|那人|这件事|那件事|刚才|刚刚)").containsMatchIn(question)
            val recent=historyQuestions.takeLast(2).joinToString(" ")
            val recalled=if(direct.isEmpty()&&follow)groups.filter{g->g.any{recent.contains(it.title)}}else emptyList()
            val names=(direct+recalled).flatMap{g->g.map{it.title}}.distinct().take(16)
            val chapterPattern=Regex("第([零〇一二两三四五六七八九十百千万0-9]+)[章回]")
            val chapterNumber=chapterPattern.find(question)?.groupValues?.get(1)?.let(::number)
            val explicit=chapterNumber?.let{n->chapters.indices.firstOrNull{chapterPattern.find(chapters[it].title)?.groupValues?.get(1)?.let(::number)==n}?:-1}
            val byTitle=chapters.take(boundary.chapter+1).indices.firstOrNull{chapters[it].title.length>2&&question.contains(chapters[it].title)}
            val local=quote.isNotBlank()||Regex("这段|眼前|当前|这一段|这句话|这里").containsMatchIn(question)
            val queries=mutableListOf(question);if(names.isNotEmpty())queries.add((names+question).joinToString(" "));if(quote.isNotBlank())queries.add(quote.take(180))
            return Plan(queries,names,explicit?:byTitle,local,follow,Regex("关系|伏笔|梳理|对比|前因后果|详细|总结|回顾|为什么|为何").containsMatchIn(question))
        }
    }
}
