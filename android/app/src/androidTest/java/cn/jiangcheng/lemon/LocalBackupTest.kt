package cn.jiangcheng.lemon

import android.app.Instrumentation
import cn.jiangcheng.lemon.data.Library
import cn.jiangcheng.lemon.data.LocalBackup
import cn.jiangcheng.lemon.reader.Chapter
import cn.jiangcheng.lemon.reader.Chronicle
import cn.jiangcheng.lemon.reader.Note
import cn.jiangcheng.lemon.reader.Novel
import cn.jiangcheng.lemon.shimo.Excerpt
import cn.jiangcheng.lemon.shimo.ShimoStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import cn.jiangcheng.lemon.data.LocalProfile
import cn.jiangcheng.lemon.data.Secrets

object LocalBackupTest {
    fun run(test:Instrumentation):String {
        val context=test.targetContext
        val sourceOwner="backup-source-${System.currentTimeMillis()}"
        val targetOwner="$sourceOwner-target"
        val source="第一章 初见\n江边灯火。".toByteArray()
        val prefs=context.getSharedPreferences("app",0)
        val originalPrefs=prefs.all.toMap()
        val profileKey="models:${LocalProfile.ID}";val routeKey="route:${LocalProfile.ID}:deep"
        val secretName="model-key:${LocalProfile.ID}:restore-case"
        val secrets=Secrets(context);val originalSecret=secrets.get(secretName)
        prefs.edit().putString(profileKey,JSONArray().put(JSONObject().put("id","restore-case").put("name","测试连接").put("base","https://example.com/v1").put("models","sample").put("key","private-value")).toString())
            .putString(routeKey,"restore-case").putString("unexpected","private-value").apply()
        secrets.set(secretName,"existing-key")
        try{
        Library(context).use{library->
            val book=library.import(sourceOwner,Novel("迁移测试",listOf(Chapter("第一章 初见","江边灯火。")),"UTF-8"),source,"迁移测试.txt")
            book.notes=listOf(Note(chapter=0,start=0,end=2,quote="江边",body="喜欢",type="札记"))
            book.chronicles=listOf(Chronicle(kind="人物",title="林舟",content="初见",chapter=0,offset=0))
            library.save(sourceOwner,book)
            ShimoStore(context).use{it.save(sourceOwner,Excerpt(text="江边灯火。",book=book.title,source="阅读"))}
            val bytes=ByteArrayOutputStream().also{LocalBackup(context,library).export(sourceOwner,it)}.toByteArray()
            var settings=JSONObject()
            ZipInputStream(ByteArrayInputStream(bytes)).use{zip->while(true){val entry=zip.nextEntry?:break
                if(entry.name=="settings.json")settings=JSONObject(zip.readBytes().toString(Charsets.UTF_8))
            }}
            val appSettings=settings.getJSONObject("app")
            check(!appSettings.has("unexpected"))
            check(!appSettings.getJSONObject(profileKey).getString("value").contains("private-value"))
            val restored=LocalBackup(context,library).restore(targetOwner,ByteArrayInputStream(bytes))
            check(restored.books==1&&restored.excerpts==1&&restored.skipped==0)
            val copy=library.all(targetOwner).single()
            check(copy.title==book.title&&copy.chapters.single().text=="江边灯火。")
            check(copy.notes.single().body=="喜欢"&&copy.chronicles.single().title=="林舟")
            check(library.sourceFile(targetOwner,copy.id).readBytes().contentEquals(source))
            check(ShimoStore(context).use{it.all(targetOwner).single().text}=="江边灯火。")
            val importedId=JSONArray(prefs.getString(profileKey,"[]")).getJSONObject(0).getString("id")
            check(importedId!="restore-case"&&prefs.getString(routeKey,"")==importedId)
            check(secrets.get("model-key:${LocalProfile.ID}:$importedId").isEmpty())
            check(secrets.get(secretName)=="existing-key")
            val invalid=ByteArrayOutputStream().also{out->ZipOutputStream(out).use{zip->
                for(name in listOf("manifest.json","manifestXjson")){zip.putNextEntry(ZipEntry(name));zip.write("{}".toByteArray());zip.closeEntry()}
            }}.toByteArray()
            val old="manifestXjson".toByteArray();val replacement="manifest.json".toByteArray()
            for(i in 0..invalid.size-old.size)if(old.indices.all{invalid[i+it]==old[it]})replacement.copyInto(invalid,i)
            check(runCatching{LocalBackup(context,library).restore(targetOwner,ByteArrayInputStream(invalid))}.exceptionOrNull()?.message?.contains("重复文件")==true)
            library.deleteBook(sourceOwner,book);library.deleteBook(targetOwner,copy)
        }
        }finally{
            val edit=prefs.edit().clear()
            originalPrefs.forEach{(key,value)->when(value){is String->edit.putString(key,value);is Boolean->edit.putBoolean(key,value);is Int->edit.putInt(key,value);is Long->edit.putLong(key,value);is Float->edit.putFloat(key,value);is Set<*>->edit.putStringSet(key,value.filterIsInstance<String>().toSet())}}
            edit.commit();if(originalSecret.isBlank())secrets.remove(secretName)else secrets.set(secretName,originalSecret)
        }
        return "本地备份恢复、设置过滤、模型密钥隔离与重复条目拒绝通过"
    }
}
