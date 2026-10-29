package cn.jiangcheng.lemon.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import cn.jiangcheng.lemon.reader.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Book(
    val id: String = UUID.randomUUID().toString(), var title: String, var chapters: List<Chapter>,
    var position: Position = Position(0, 0), var notes: List<Note> = emptyList(),
    var chronicles: List<Chronicle> = emptyList(), var category: String = "未分类", var revision: Int = 1,
    var touched: Long = System.currentTimeMillis(), var readSeconds: Long = 0,
    var readChars: Long = 0, var sourceName: String = "", var summaries: JSONObject = JSONObject(),
    var chats: JSONArray = JSONArray(), var localRemoved: Boolean = false, var cover: String = "", var coverText: String = "", var coverColor: Int = -1909038, var coverInk: Int = -13882589, var folder:String="", var folderUpdated:Long=0
)

class Library(private val context: Context) : SQLiteOpenHelper(context, "library.db", null, 2) {
    data class Progress(val id:String,val revision:Int,val position:Position,val touched:Long,val seconds:Long,val chars:Long)
    /** Merge only progress on the storage thread. Never replace newer notes or resurrect a removed book. */
    @Synchronized fun saveProgress(profileId:String,p:Progress):Boolean {
        val db=writableDatabase;db.beginTransaction()
        try {
            val json=JSONObject(payload("books",profileId,p.id)?:return false)
            if(json.optInt("revision")!=p.revision||json.optBoolean("localRemoved")||json.optLong("touched")>p.touched)return false
            json.put("chapter",p.position.chapter).put("offset",p.position.offset).put("touched",p.touched)
                .put("readSeconds",maxOf(json.optLong("readSeconds"),p.seconds)).put("readChars",maxOf(json.optLong("readChars"),p.chars))
            db.update("books",ContentValues().apply{put("payload",json.toString());put("touched",p.touched)},"id=? AND account=?",arrayOf(p.id,profileId))
            db.setTransactionSuccessful();return true
        }finally{db.endTransaction()}
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE books(id TEXT PRIMARY KEY, account TEXT NOT NULL, title TEXT NOT NULL, touched INTEGER NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX books_account ON books(account)")
        createChapters(db)
    }
    private fun createChapters(db:SQLiteDatabase) { db.execSQL("CREATE TABLE IF NOT EXISTS book_chapters(id TEXT PRIMARY KEY,account TEXT NOT NULL,revision INTEGER NOT NULL,payload TEXT NOT NULL)") }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, newer: Int) { if(old<2)createChapters(db) }
    // Bounded SQL reads also support existing v2 books whose JSON exceeds CursorWindow.
    private fun payload(table:String, profileId:String, id:String):String? {
        require(table in listOf("books","book_chapters"))
        val db=readableDatabase
        val length=db.rawQuery("SELECT length(payload) FROM $table WHERE account=? AND id=?",arrayOf(profileId,id)).use{if(it.moveToFirst())it.getInt(0)else return null}
        return buildString { var offset=1;while(offset<=length){
            db.rawQuery("SELECT substr(payload,?,65536) FROM $table WHERE account=? AND id=?",arrayOf(offset.toString(),profileId,id)).use{check(it.moveToFirst());append(it.getString(0))}
            offset+=65536
        } }
    }
    fun all(profileId: String,includeText:Boolean=true): List<Book> {
        val ids=readableDatabase.rawQuery("SELECT id FROM books WHERE account=? ORDER BY touched DESC",arrayOf(profileId)).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
        return ids.mapNotNull{readBook(profileId,it,includeText)}
    }
    private fun readBook(profileId:String,id:String,includeText:Boolean):Book? {
        val json=JSONObject(payload("books",profileId,id) ?: return null)
        if(includeText)payload("book_chapters",profileId,id)?.let{json.put("chapters",JSONArray(it))}
        else {val titles=json.optJSONArray("chapterTitles") ?: JSONArray();json.put("chapters",JSONArray((0 until titles.length()).map{JSONObject().put("title",titles.getString(it)).put("text","")}))}
        return fromJson(json)
    }
    fun get(profileId:String,id:String):Book?=readBook(profileId,id,true)
    /** Exact source identity, independent of filename. Never merge distinct files by title. */
    fun duplicate(profileId:String,source:ByteArray):Book? = all(profileId,false).firstOrNull { book ->
        val file=sourceFile(profileId,book.id)
        !book.localRemoved && file.isFile && file.length()==source.size.toLong() && file.inputStream().buffered().use { input ->
            val buffer=ByteArray(32768);var offset=0;var equal=true
            while(offset<source.size && equal){val n=input.read(buffer);if(n<=0){equal=false;break};for(i in 0 until n)if(buffer[i]!=source[offset+i]){equal=false;break};offset+=n};equal&&offset==source.size
        }
    }
    @Synchronized fun save(profileId: String, book: Book, forceBody:Boolean=false) {
        val json = toJson(book,includeChapters=false).toString()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply { put("id",book.id); put("account",profileId); put("title",book.title); put("touched",book.touched); put("payload",json) }
            db.insertWithOnConflict("books", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            val stored=db.rawQuery("SELECT revision FROM book_chapters WHERE id=? AND account=?",arrayOf(book.id,profileId)).use{if(it.moveToFirst())it.getInt(0)else -1}
            if(book.localRemoved) db.delete("book_chapters","id=? AND account=?",arrayOf(book.id,profileId))
            else if(forceBody||stored!=book.revision) {
                val chapters=JSONArray(book.chapters.map{JSONObject().put("title",it.title).put("text",it.text)})
                db.execSQL("INSERT OR REPLACE INTO book_chapters VALUES(?,?,?,?)",arrayOf(book.id,profileId,book.revision,chapters.toString()))
                val parent=sourceFile(profileId,book.id).parentFile!!;parent.mkdirs()
                val pending=File(parent,"body-${book.revision}.pending")
                pending.writeText(JSONObject().put("chapters",chapters).toString())
                check(pending.renameTo(File(parent,"processed-v${book.revision}.json"))){"整理正文保存失败"}
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun import(profileId: String, novel: Novel, source: ByteArray, name: String, update: Book? = null): Book {
        if(update==null)duplicate(profileId,source)?.let{return get(profileId,it.id) ?: it}
        val book = update ?: Book(title=novel.title, chapters=novel.chapters)
        if (update != null) {
            book.notes = book.notes.map { ReaderCore.remap(it, book.chapters, novel.chapters) }
            book.position = ReaderCore.remapPosition(book.position, book.chapters, novel.chapters)
            book.revision++
            book.summaries = JSONObject()
        }
        book.chapters = novel.chapters; book.sourceName = name; book.localRemoved = false
        book.touched = System.currentTimeMillis()
        val sourceFile = sourceFile(profileId, book.id)
        sourceFile.parentFile?.mkdirs()
        val original=File(sourceFile.parentFile,"original-source.bin")
        if(!original.isFile){original.writeBytes(if(sourceFile.isFile)sourceFile.readBytes()else source);File(sourceFile.parentFile,"original-name.txt").writeText(book.sourceName)}
        val staging = File(sourceFile.parentFile,"source.pending")
        staging.writeBytes(source)
        check(staging.renameTo(sourceFile)) { "源文件保存失败" }
        if(update==null||book.cover.isBlank())novel.cover?.let { bytes -> val cover=File(sourceFile.parentFile,"cover.img");cover.writeBytes(bytes);book.cover=cover.absolutePath }
        val processed=File(sourceFile.parentFile,"processed-v${book.revision}.json")
        val pending=File(sourceFile.parentFile,"processed.pending")
        pending.writeText(JSONObject().put("chapters",JSONArray(book.chapters.map{JSONObject().put("title",it.title).put("text",it.text)})).toString())
        check(pending.renameTo(processed)){"整理后的正文保存失败"}
        save(profileId,book)
        return book
    }
    fun sourceFile(profileId: String, bookId: String): File = File(context.filesDir, "sources/$profileId/$bookId/source.bin")
    private fun removeBodyFiles(profileId:String,id:String){
        val source=sourceFile(profileId,id);source.delete()
        source.parentFile?.listFiles()?.filter{it.name in listOf("original-source.bin","original-name.txt","readable-latest.json","before-organize.json","agent-result.json","agent-checkpoint.json","import-job.json")}?.forEach{it.delete()}
        source.parentFile?.listFiles()?.filter{it.isFile&&it.name.startsWith("processed-v")&&it.extension=="json"}?.forEach{it.delete()}
    }
    fun removeLocal(profileId: String, book: Book) {
        book.chapters = emptyList(); book.localRemoved = true
        removeBodyFiles(profileId,book.id)
        save(profileId, book)
    }
    fun deleteBook(profileId:String,book:Book){
        val db=writableDatabase
        db.beginTransaction()
        try{
            db.delete("book_chapters","id=? AND account=?",arrayOf(book.id,profileId))
            db.delete("books","id=? AND account=?",arrayOf(book.id,profileId))
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
        sourceFile(profileId,book.id).parentFile?.deleteRecursively()
    }
    fun export(profileId: String) = JSONArray(all(profileId).map { toJson(it) }).toString(2)
    companion object {
        fun toJson(b: Book,includeChapters:Boolean=true): JSONObject = JSONObject().apply {
            put("id",b.id);put("title",b.title);put("category",b.category);put("revision",b.revision);put("touched",b.touched);put("readSeconds",b.readSeconds);put("readChars",b.readChars);put("sourceName",b.sourceName);put("localRemoved",b.localRemoved);put("cover",b.cover);put("coverText",b.coverText);put("coverColor",b.coverColor);put("coverInk",b.coverInk);put("folder",b.folder);put("folderUpdated",b.folderUpdated)
            put("chapter",b.position.chapter);put("offset",b.position.offset);put("summaries",b.summaries);put("chats",b.chats)
            put("chapterTitles",JSONArray(b.chapters.map{it.title}))
            if(includeChapters)put("chapters",JSONArray(b.chapters.map { JSONObject().put("title",it.title).put("text",it.text) }))
            put("notes",JSONArray(b.notes.map { n -> JSONObject().apply { put("id",n.id);put("chapter",n.chapter);put("start",n.start);put("end",n.end);put("quote",n.quote);put("body",n.body);put("type",n.type);put("changed",n.changed);put("replacement",n.replacement ?: JSONObject.NULL);put("before",n.before);put("after",n.after) }}))
            put("chronicles",JSONArray(b.chronicles.map { c -> JSONObject().apply { put("id",c.id);put("kind",c.kind);put("title",c.title);put("content",c.content);put("chapter",c.chapter);put("offset",c.offset);put("manual",c.manual);put("deleted",c.deleted);put("revision",c.revision);put("evidence",c.evidence);put("entity",c.entity);put("contextEnd",c.contextEnd) }}))
        }
        fun fromJson(j: JSONObject): Book {
            val chapters = j.optJSONArray("chapters") ?: JSONArray()
            val notes = j.optJSONArray("notes") ?: JSONArray()
            val chron = j.optJSONArray("chronicles") ?: JSONArray()
            return Book(id=j.getString("id"),title=j.getString("title"),chapters=(0 until chapters.length()).map { chapters.getJSONObject(it).let { c -> Chapter(c.getString("title"),c.getString("text")) } },
                position=Position(j.optInt("chapter"),j.optInt("offset")), notes=(0 until notes.length()).map { notes.getJSONObject(it).let { n -> Note(n.getString("id"),n.getInt("chapter"),n.getInt("start"),n.getInt("end"),n.getString("quote"),n.getString("body"),n.optString("type","札记"),n.optBoolean("changed"),if(n.isNull("replacement")) null else n.getString("replacement"),n.optString("before"),n.optString("after")) } },
                chronicles=(0 until chron.length()).map { chron.getJSONObject(it).let { c -> Chronicle(c.getString("id"),c.getString("kind"),c.getString("title"),c.getString("content"),c.getInt("chapter"),c.getInt("offset"),c.optBoolean("manual"),c.optBoolean("deleted"),c.optInt("revision",1),c.optString("evidence"),c.optString("entity"),c.optInt("contextEnd",-1)) } },
                category=j.optString("category","未分类"),revision=j.optInt("revision",1),touched=j.optLong("touched"),readSeconds=j.optLong("readSeconds"),readChars=j.optLong("readChars"),sourceName=j.optString("sourceName"),summaries=j.optJSONObject("summaries") ?: JSONObject(),chats=j.optJSONArray("chats") ?: JSONArray(),localRemoved=j.optBoolean("localRemoved"),cover=j.optString("cover"),coverText=j.optString("coverText"),coverColor=j.optInt("coverColor",-1909038),coverInk=j.optInt("coverInk",-13882589),folder=j.optString("folder"),folderUpdated=j.optLong("folderUpdated"))
        }
    }
}
