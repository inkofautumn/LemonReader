package cn.jiangcheng.lemon.data

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Reader diagnostics use a fixed schema of event codes and source positions. */
object CrashLog {
    @Synchronized fun importDiagnostic(context:Context,code:String){
        if(code !in setOf("OUTPUT_LIMIT","OUTPUT_INCOMPLETE","INVALID_JSON","NETWORK","AUTH","RATE_LIMIT","HTTP_ERROR","SOURCE_VALIDATION"))return
        val current=File(directory(context),"reader.jsonl")
        if(current.length()>524288){val previous=File(directory(context),"reader-previous.jsonl");previous.delete();current.renameTo(previous)}
        runCatching{current.appendText(JSONObject().put("time",System.currentTimeMillis()).put("event","IMPORT_DIAGNOSTIC").put("code",code).toString()+"\n")}
    }
    enum class Event { CRASH, READ_OPEN, READ_READY, READ_FAILED, IMPORT_BEGIN, IMPORT_READY, IMPORT_FAILED, IMPORT_CANCELLED, COVER_SAVED, AI_FAILED }
    private var installed=false
    @Synchronized fun install(context:Context){
        if(installed)return
        installed=true
        // Old unrestricted stack traces must never enter the new export.
        File(context.filesDir,"diagnostics/reader-error.txt").delete()
        val app=context.applicationContext;val previous=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler{thread,error->write(app,error);previous?.uncaughtException(thread,error)}
    }
    private fun directory(context:Context)=File(context.filesDir,"reader-diagnostics").apply{mkdirs()}
    fun write(context:Context,error:Throwable)=event(context,Event.CRASH,error=error)
    @Synchronized fun event(context:Context,event:Event,chapters:Int?=null,characters:Int?=null,error:Throwable?=null){runCatching{
        val chain=generateSequence(error){it.cause}.take(6).toList()
        val value=JSONObject().put("time",System.currentTimeMillis()).put("event",event.name)
        chapters?.let{value.put("chapters",it.coerceAtLeast(0))};characters?.let{value.put("characters",it.coerceAtLeast(0))}
        if(error!=null){
            value.put("errors",org.json.JSONArray(chain.map{cause->JSONObject().put("type",cause.javaClass.name).put("frames",org.json.JSONArray(cause.stackTrace.filter{it.className.startsWith("cn.jiangcheng.lemon.")&&!it.className.contains("backup")}.take(30).map{"${it.className}.${it.methodName}:${it.lineNumber}"}))}))
        }
        val dir=directory(context);val current=File(dir,"reader.jsonl")
        if(current.length()>524288){val previous=File(dir,"reader-previous.jsonl");previous.delete();current.renameTo(previous)}
        current.appendText(value.toString()+"\n")
    }}
    @Synchronized fun export(context:Context):File {
        val dir=File(context.cacheDir,"reader-log-exports").apply{mkdirs()}
        dir.listFiles()?.filter{it.isFile&&System.currentTimeMillis()-it.lastModified()>7*86400000L}?.forEach{it.delete()}
        val file=File(dir,"lemon-reader-logs-${System.currentTimeMillis()}.zip")
        ZipOutputStream(file.outputStream()).use{zip->
            zip.putNextEntry(ZipEntry("environment.json"));zip.write(JSONObject().put("version",context.packageManager.getPackageInfo(context.packageName,0).versionName).put("sdk",Build.VERSION.SDK_INT).put("model",Build.MODEL).toString(2).toByteArray());zip.closeEntry()
            for(name in listOf("reader-previous.jsonl","reader.jsonl")){val log=File(directory(context),name);if(log.isFile){zip.putNextEntry(ZipEntry(name));log.inputStream().use{it.copyTo(zip)};zip.closeEntry()}}
        }
        return file
    }
    fun shareIntent(context:Context,file:File):Intent {
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.readerlogs",file)
        return Intent(Intent.ACTION_SEND).apply{type="application/zip";putExtra(Intent.EXTRA_STREAM,uri);clipData=ClipData.newRawUri("阅读日志",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}
    }
}
