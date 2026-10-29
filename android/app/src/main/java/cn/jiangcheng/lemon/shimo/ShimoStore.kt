package cn.jiangcheng.lemon.shimo

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import cn.jiangcheng.lemon.data.Book
import cn.jiangcheng.lemon.reader.Note
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

data class Excerpt(
    val id:String=UUID.randomUUID().toString(), var title:String="",var text:String="",var thought:String="",
    var book:String="",var author:String="",var page:String="",var chapterName:String="",var folder:String="",
    var tags:List<String> = emptyList(),var images:List<String> = emptyList(),var ocr:String="",var source:String="手动",
    var created:Long=System.currentTimeMillis(),var updated:Long=created,var deleted:Boolean=false,
    var bookId:String="",var noteId:String="",var chapter:Int=0,var start:Int=0,var original:String=""
) {
    fun json()=JSONObject().put("id",id).put("title",title).put("text",text).put("thought",thought).put("book",book)
        .put("author",author).put("page",page).put("chapterName",chapterName).put("folder",folder).put("tags",JSONArray(tags))
        .put("images",JSONArray(images)).put("ocr",ocr).put("source",source).put("created",created).put("updated",updated)
        .put("deleted",deleted).put("bookId",bookId).put("noteId",noteId).put("chapter",chapter).put("start",start).put("original",original)
    val nonempty get()=text.isNotBlank()||images.isNotEmpty()
    val heading get()=title.ifBlank{text.lineSequence().firstOrNull{it.isNotBlank()}?.take(50)?:"图片摘抄"}
    companion object {
        fun strings(a:JSONArray?)=(0 until (a?.length()?:0)).map{a!!.getString(it)}
        fun parse(j:JSONObject)=Excerpt(j.getString("id"),j.optString("title"),j.optString("text"),j.optString("thought"),j.optString("book"),
            j.optString("author"),j.optString("page"),j.optString("chapterName"),j.optString("folder"),strings(j.optJSONArray("tags")),
            strings(j.optJSONArray("images")),j.optString("ocr"),j.optString("source","手动"),j.optLong("created"),j.optLong("updated"),
            j.optBoolean("deleted"),j.optString("bookId"),j.optString("noteId"),j.optInt("chapter"),j.optInt("start"),j.optString("original"))
    }
}

/** Local excerpt collection with isolated reading profiles. */
class ShimoStore(private val context:Context):SQLiteOpenHelper(context,"shimo.db",null,1) {
    override fun onCreate(db:SQLiteDatabase){db.execSQL("CREATE TABLE excerpts(account TEXT NOT NULL,id TEXT NOT NULL,payload TEXT NOT NULL,folder TEXT NOT NULL,deleted INTEGER NOT NULL,created INTEGER NOT NULL,updated INTEGER NOT NULL,search TEXT NOT NULL,PRIMARY KEY(account,id))");db.execSQL("CREATE INDEX excerpts_list ON excerpts(account,deleted,created)");db.execSQL("CREATE TABLE folders(account TEXT NOT NULL,id TEXT NOT NULL,name TEXT NOT NULL,PRIMARY KEY(account,id),UNIQUE(account,name))");db.execSQL("CREATE TABLE collected(account TEXT NOT NULL,book TEXT NOT NULL,note TEXT NOT NULL,excerpt TEXT NOT NULL,PRIMARY KEY(account,book,note))")}
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){}
    val mediaRoot get()=File(context.filesDir,"shimo-media").apply{mkdirs()}
    fun media(name:String):File {require(Regex("[a-zA-Z0-9-]+\\.(jpg|png|webp)").matches(name));return File(mediaRoot,name)}
    fun get(owner:String,id:String):Excerpt?=readableDatabase.rawQuery("SELECT payload FROM excerpts WHERE account=? AND id=?",arrayOf(owner,id)).use{if(it.moveToFirst())Excerpt.parse(JSONObject(it.getString(0)))else null}
    fun all(owner:String,trash:Boolean=false):List<Excerpt> = readableDatabase.rawQuery("SELECT payload FROM excerpts WHERE account=? AND deleted=? ORDER BY created DESC,id",arrayOf(owner,if(trash)"1"else"0")).use{c->buildList{while(c.moveToNext())add(Excerpt.parse(JSONObject(c.getString(0))))}}
    fun save(owner:String,e:Excerpt,touch:Boolean=true){require(owner.isNotBlank());require(e.nonempty){"请填写摘抄文字或添加图片"};require(e.text.length+e.thought.length+e.ocr.length<=300000){"单条内容过长，请拆分收藏"};require(e.images.size<=20){"每条最多保存20张图片"};e.images.forEach{media(it)};if(touch)e.updated=System.currentTimeMillis()
        val v=ContentValues().apply{put("account",owner);put("id",e.id);put("payload",e.json().toString());put("folder",e.folder);put("deleted",if(e.deleted)1 else 0);put("created",e.created);put("updated",e.updated);put("search",listOf(e.title,e.text,e.thought,e.book,e.author,e.ocr,e.tags.joinToString(" ")).joinToString("\n"))};check(writableDatabase.insertWithOnConflict("excerpts",null,v,SQLiteDatabase.CONFLICT_REPLACE)!=-1L){"本地保存失败，请检查可用存储空间"}
    }
    fun folders(owner:String):List<Pair<String,String>> = readableDatabase.rawQuery("SELECT id,name FROM folders WHERE account=? ORDER BY name",arrayOf(owner)).use{c->buildList{while(c.moveToNext())add(c.getString(0) to c.getString(1))}}
    fun folder(owner:String,name:String,id:String=UUID.randomUUID().toString()):String{val clean=name.trim();require(clean.isNotBlank()&&clean.length<=40){"文件夹名称需为1至40字"};require(folders(owner).none{it.second==clean&&it.first!=id}){"已有同名文件夹"};writableDatabase.execSQL("INSERT INTO folders VALUES(?,?,?) ON CONFLICT(account,id) DO UPDATE SET name=excluded.name",arrayOf(owner,id,clean));return id}
    fun removeFolder(owner:String,id:String){val db=writableDatabase;db.beginTransaction();try{(all(owner)+all(owner,true)).filter{it.folder==id}.forEach{it.folder="";save(owner,it)};db.delete("folders","account=? AND id=?",arrayOf(owner,id));db.setTransactionSuccessful()}finally{db.endTransaction()}}
    fun tags(owner:String)=all(owner).flatMap{it.tags}.distinct().sorted()
    fun renameTag(owner:String,old:String,new:String){val clean=new.trim();require(clean.length<=30);all(owner).plus(all(owner,true)).filter{old in it.tags}.forEach{it.tags=it.tags.map{v->if(v==old)clean else v}.filter{v->v.isNotBlank()}.distinct();save(owner,it)}}
    fun purge(owner:String,e:Excerpt){writableDatabase.delete("excerpts","account=? AND id=?",arrayOf(owner,e.id)) /* Collection tombstones intentionally retained. */}
    fun collect(owner:String,book:Book,note:Note,historical:Boolean=false):Boolean {
        if(note.quote.isBlank()||note.type !in listOf("划线","札记"))return false
        val db=writableDatabase;db.beginTransaction()
        try {
            val known=db.rawQuery("SELECT excerpt FROM collected WHERE account=? AND book=? AND note=?",arrayOf(owner,book.id,note.id)).use{it.moveToFirst()}
            if(known)return false
            val same=(all(owner)+all(owner,true)).firstOrNull{it.bookId==book.id&&it.original==note.quote&&it.chapter==note.chapter&&it.start==note.start}
            val e=same?:Excerpt(text=note.quote,thought=note.body,book=book.title,bookId=book.id,noteId=note.id,chapter=note.chapter,start=note.start,
                chapterName=book.chapters.getOrNull(note.chapter)?.title.orEmpty(),original=note.quote,source="阅读",created=if(historical)0 else System.currentTimeMillis())
            if(same==null)save(owner,e)else if(!e.deleted&&e.thought.isBlank()&&note.body.isNotBlank()){e.thought=note.body;save(owner,e)}
            db.execSQL("INSERT INTO collected VALUES(?,?,?,?)",arrayOf(owner,book.id,note.id,e.id));db.setTransactionSuccessful();return same==null
        }finally{db.endTransaction()}
    }
    fun export(owner:String,out:OutputStream){ZipOutputStream(out).use{zip->
        val entries=all(owner)+all(owner,true);val manifest=JSONObject().put("format","lemon-shimo").put("version",1).put("entries",JSONArray(entries.map{it.json()})).put("folders",JSONArray(folders(owner).map{JSONObject().put("id",it.first).put("name",it.second)}))
        zip.putNextEntry(ZipEntry("shimo.json"));zip.write(manifest.toString().toByteArray(Charsets.UTF_8));zip.closeEntry()
        entries.flatMap{it.images}.distinct().forEach{name->val file=media(name);require(file.isFile){"摘抄图片缺失，请检查后重试"};if(file.isFile){zip.putNextEntry(ZipEntry("images/$name"));file.inputStream().use{it.copyTo(zip)};zip.closeEntry()}}
    }}
    fun import(owner:String,input:InputStream):Int {
        val temp=File(context.cacheDir,"shimo-import-${UUID.randomUUID()}").apply{mkdirs()};val written=mutableListOf<File>()
        try {
            var total=0L;var count=0
            ZipInputStream(input).use{zip->while(true){val entry=zip.nextEntry?:break;require(++count<=10001){"备份文件项目过多"};val name=entry.name
                require(name=="shimo.json"||Regex("images/[a-zA-Z0-9-]+\\.(jpg|png|webp)").matches(name)){"不支持的备份文件"}
                val target=File(temp,name);require(!target.exists()){ "备份内有重复文件" };target.parentFile!!.mkdirs();var size=0L
                target.outputStream().use{out->val buffer=ByteArray(32768);while(true){val n=zip.read(buffer);if(n<0)break;size+=n;total+=n;require(size<=30_000_000&&total<=250_000_000){"备份超过大小限制（单文件30MB、总计250MB）"};out.write(buffer,0,n)}};zip.closeEntry()
            }}
            val j=JSONObject(File(temp,"shimo.json").readText());require(j.optString("format").let{it.length<=40&&it.matches(Regex("[a-z]+-shimo"))}&&j.optInt("version")==1){"不支持的拾墨备份版本"}
            val arr=j.getJSONArray("entries");require(arr.length()<=10000);val data=(0 until arr.length()).map{Excerpt.parse(arr.getJSONObject(it))};require(data.map{it.id}.distinct().size==data.size){"条目编号重复"}
            val db=writableDatabase;var added=0;db.beginTransaction()
            try {
                val mapping=mutableMapOf<String,String>();val fs=j.optJSONArray("folders")?:JSONArray()
                for(i in 0 until fs.length()){val f=fs.getJSONObject(i);val name=f.getString("name");mapping[f.getString("id")]=folders(owner).firstOrNull{it.second==name}?.first?:folder(owner,name)}
                for(e in data){if(get(owner,e.id)!=null)continue
                    e.images=e.images.map{name->media(name);val from=File(temp,"images/$name");require(from.isFile){"备份缺少图片"};val newName="${UUID.randomUUID()}.${name.substringAfterLast('.')}";val target=media(newName);from.copyTo(target);written.add(target);newName}
                    e.folder=mapping[e.folder].orEmpty();e.tags=e.tags.map{it.trim().take(30)}.filter{it.isNotBlank()}.distinct().take(30);save(owner,e,false)
                    if(e.bookId.isNotBlank()&&e.noteId.isNotBlank())db.execSQL("INSERT OR IGNORE INTO collected VALUES(?,?,?,?)",arrayOf(owner,e.bookId,e.noteId,e.id));added++
                }
                db.setTransactionSuccessful()
            }finally{db.endTransaction()}
            written.clear();return added
        }finally{written.forEach{it.delete()};temp.deleteRecursively()}
    }
}
