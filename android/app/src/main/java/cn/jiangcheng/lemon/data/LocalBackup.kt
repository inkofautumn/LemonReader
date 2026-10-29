package cn.jiangcheng.lemon.data

import android.content.Context
import android.content.SharedPreferences
import cn.jiangcheng.lemon.shimo.ShimoStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Versioned, local-only backup for books and reading data. API keys are deliberately excluded. */
class LocalBackup(private val context:Context,private val library:Library) {
    data class RestoreResult(val books:Int,val skipped:Int,val excerpts:Int)

    private val preferenceFiles=listOf("app","reading:${LocalProfile.ID}","shimo-ui:${LocalProfile.ID}","chronicle-progress:${LocalProfile.ID}")

    fun export(owner:String,out:OutputStream){
        val books=library.all(owner)
        ZipOutputStream(out.buffered()).use{zip->
            put(zip,"manifest.json",JSONObject().put("format","lemon-reader-backup").put("version",1).put("created",System.currentTimeMillis()).toString().toByteArray())
            put(zip,"library.json",JSONArray(books.map{Library.toJson(it)}).toString().toByteArray())
            books.forEach{book->
                val source=library.sourceFile(owner,book.id)
                if(source.isFile)put(zip,"sources/${book.id}/source.bin",source)
                val cover=File(book.cover)
                if(book.cover.isNotBlank()&&cover.isFile)put(zip,"sources/${book.id}/cover.bin",cover)
            }
            val settings=JSONObject()
            preferenceFiles.forEach{name->settings.put(name,preferences(context.getSharedPreferences(name,0),name))}
            put(zip,"settings.json",settings.toString().toByteArray())
            val shimo=File(context.cacheDir,"lemon-shimo-${UUID.randomUUID()}.zip")
            try{
                shimo.outputStream().use{stream->ShimoStore(context).use{it.export(owner,stream)}}
                put(zip,"shimo.zip",shimo)
            }finally{shimo.delete()}
        }
    }

    fun restore(owner:String,input:InputStream):RestoreResult{
        val temp=File(context.cacheDir,"lemon-restore-${UUID.randomUUID()}").apply{mkdirs()}
        try{
            var entries=0;var total=0L
            ZipInputStream(input.buffered()).use{zip->while(true){
                val entry=zip.nextEntry?:break
                require(++entries<=10000){"备份文件项目过多"}
                val name=entry.name
                require(name in setOf("manifest.json","library.json","settings.json","shimo.zip")||Regex("sources/[a-zA-Z0-9_-]{1,80}/(source|cover)\\.bin").matches(name)){"备份中包含不支持的文件"}
                val target=File(temp,name);require(!target.exists()){ "备份内有重复文件" };target.parentFile?.mkdirs();var size=0L
                target.outputStream().use{out->val buffer=ByteArray(64*1024);while(true){val read=zip.read(buffer);if(read<0)break;size+=read;total+=read;require(size<=250_000_000L&&total<=2_000_000_000L){"备份文件超过大小限制"};out.write(buffer,0,read)}}
                zip.closeEntry()
            }}
            val manifest=JSONObject(File(temp,"manifest.json").takeIf{it.isFile}?.readText()?:error("备份缺少版本信息"))
            require(manifest.optString("format") in setOf("lemon-reader-backup","moling-reader-backup")&&manifest.optInt("version")==1){"不支持的灵墨阅读备份版本"}
            val rows=JSONArray(File(temp,"library.json").takeIf{it.isFile}?.readText()?:"[]")
            require(rows.length()<=10000){"备份中的书籍数量过多"}
            val books=(0 until rows.length()).map{Library.fromJson(rows.getJSONObject(it)).also{book->
                require(Regex("[a-zA-Z0-9_-]{1,80}").matches(book.id)){"书籍编号不合法"}
            }}
            require(books.map{it.id}.distinct().size==books.size){"备份中的书籍编号重复"}
            val settings=File(temp,"settings.json").takeIf{it.isFile}?.let{portableSettings(JSONObject(it.readText()))}
            var added=0;var skipped=0
            for(book in books){
                if(library.get(owner,book.id)!=null){skipped++;continue}
                val source=File(temp,"sources/${book.id}/source.bin")
                val target=library.sourceFile(owner,book.id);target.parentFile?.mkdirs()
                if(source.isFile){source.copyTo(target,true);source.copyTo(File(target.parentFile,"original-source.bin"),true);File(target.parentFile,"original-name.txt").writeText(book.sourceName)}
                val cover=File(temp,"sources/${book.id}/cover.bin")
                if(cover.isFile){val restored=File(target.parentFile,"cover-restored.img");cover.copyTo(restored,true);book.cover=restored.absolutePath}else book.cover=""
                book.localRemoved=book.chapters.isEmpty()
                library.save(owner,book,forceBody=book.chapters.isNotEmpty());added++
            }
            val shimo=File(temp,"shimo.zip")
            val excerpts=if(shimo.isFile)shimo.inputStream().use{stream->ShimoStore(context).use{it.import(owner,stream)}}else 0
            if(settings!=null)preferenceFiles.forEach{name->settings.optJSONObject(name)?.let{restorePreferences(context.getSharedPreferences(name,0),it)}}
            return RestoreResult(added,skipped,excerpts)
        }finally{temp.deleteRecursively()}
    }

    private fun put(zip:ZipOutputStream,name:String,data:ByteArray){zip.putNextEntry(ZipEntry(name));zip.write(data);zip.closeEntry()}
    private fun put(zip:ZipOutputStream,name:String,file:File){zip.putNextEntry(ZipEntry(name));file.inputStream().use{it.copyTo(zip)};zip.closeEntry()}

    private fun preferences(prefs:SharedPreferences,name:String):JSONObject=JSONObject().apply{
        prefs.all.forEach{(key,value)->
            if(!BackupSettings.allowed(name,key))return@forEach
            val item=JSONObject()
            when(value){
                is String->item.put("type","string").put("value",if(key.startsWith("models:"))models(value).toString() else value)
                is Boolean->item.put("type","boolean").put("value",value)
                is Int->item.put("type","int").put("value",value)
                is Long->item.put("type","long").put("value",value)
                is Float->item.put("type","float").put("value",value.toDouble())
                is Set<*>->item.put("type","strings").put("value",JSONArray(value.filterIsInstance<String>()))
                else->return@forEach
            }
            put(key,item)
        }
    }

    private fun models(value:String):JSONArray {
        val source=JSONArray(value);require(source.length()<=100){"模型连接数量过多"}
        val fields=setOf("id","name","provider","base","models","reasoning_protocol","json_output","stream_usage","token_field")
        return JSONArray((0 until source.length()).map{index->val original=source.getJSONObject(index)
            JSONObject().apply{original.keys().forEach{key->if(key in fields)put(key,original.get(key))}}
        })
    }

    private fun portableSettings(source:JSONObject):JSONObject=JSONObject().apply{
        for(name in preferenceFiles){
            val incoming=source.optJSONObject(name)?:continue
            val clean=JSONObject()
            incoming.keys().forEach{key->if(BackupSettings.allowed(name,key)){
                val item=incoming.getJSONObject(key)
                require(item.getString("type") in setOf("string","boolean","int","long","float","strings")){"阅读设置类型不受支持"}
                clean.put(key,JSONObject(item.toString()))
            }}
            if(name=="app"){
                val ids=mutableMapOf<String,String>()
                val imported=clean.optJSONObject("models:${LocalProfile.ID}")
                if(imported!=null){
                    val profiles=models(imported.getString("value"))
                    for(i in 0 until profiles.length()){
                        val profile=profiles.getJSONObject(i);val old=profile.getString("id")
                        require(old !in ids){"模型连接编号重复"}
                        val fresh=UUID.randomUUID().toString();ids[old]=fresh;profile.put("id",fresh)
                        if(profile.optString("base").isNotBlank())cn.jiangcheng.lemon.reader.ModelProtocol.normalizeBase(profile.getString("base"))
                    }
                    imported.put("value",profiles.toString())
                }
                clean.keys().asSequence().toList().forEach{key->if(key.startsWith("route:")){
                    val item=clean.getJSONObject(key);val fresh=ids[item.optString("value")]
                    if(fresh==null)clean.remove(key)else item.put("value",fresh)
                }}
            }
            put(name,clean)
        }
    }

    private fun restorePreferences(prefs:SharedPreferences,json:JSONObject){
        val edit=prefs.edit()
        json.keys().forEach{key->val item=json.getJSONObject(key);when(item.getString("type")){
            "string"->edit.putString(key,item.getString("value"));"boolean"->edit.putBoolean(key,item.getBoolean("value"));"int"->edit.putInt(key,item.getInt("value"));"long"->edit.putLong(key,item.getLong("value"));"float"->edit.putFloat(key,item.getDouble("value").toFloat());"strings"->{val a=item.getJSONArray("value");edit.putStringSet(key,(0 until a.length()).map{a.getString(it)}.toSet())}
        }}
        check(edit.commit()){ "无法恢复阅读设置" }
    }
}
