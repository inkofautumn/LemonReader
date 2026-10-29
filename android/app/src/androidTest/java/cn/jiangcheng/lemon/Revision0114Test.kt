package cn.jiangcheng.lemon

import android.app.Instrumentation
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import cn.jiangcheng.lemon.reader.*
import java.io.File
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile

object Revision0114Test {
    fun run(test:Instrumentation,app:MainActivity):String {
        check(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"))
        var count=0
        fun verify(ok:Boolean,message:String){check(ok){message};count++}
        fun ui(block:()->Unit){test.runOnMainSync(block);test.waitForIdleSync()}
        fun waitFor(block:()->Boolean){val end=System.currentTimeMillis()+45000;while(!block()&&System.currentTimeMillis()<end)Thread.sleep(100);check(block())}
        fun shot(name:String){Thread.sleep(700);val b=test.uiAutomation.takeScreenshot();File(app.filesDir,"qa/114-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        val owner="revision0114";File(app.filesDir,"qa").mkdirs()
        ui{app.profileId=owner;app.prefs.edit().putBoolean("ai_directory:$owner",false).putBoolean("auto_chronicles:$owner",false).apply()}
        for(mime in listOf("application/zip","application/x-7z-compressed","application/x-dzip"))verify(app.packageManager.queryIntentActivities(Intent(Intent.ACTION_SEND).setType(mime),0).any{it.activityInfo.packageName==app.packageName},"分享入口 $mime")
        val root=File(app.cacheDir,"qa114-${System.nanoTime()}").apply{mkdirs()}
        try{
            val dzip=File(app.filesDir,"qa-input/sample.dzip")
            verify(ArchiveImport.extract(dzip,File(root,"dzip")).size==40,"手机解压实际 DZIP 40 本")
            val input=File(root,"样例.txt").apply{writeText("第一章 夜色\n小说正文。")};val seven=File(root,"样例.7z删除我")
            SevenZOutputFile(seven).use{it.putArchiveEntry(it.createArchiveEntry(input,input.name));it.write(input.readBytes());it.closeArchiveEntry()}
            verify(ArchiveImport.extract(seven,File(root,"seven")).single().readText()==input.readText(),"手机 7z 改后缀解压")
            val zip=File(root,"套娃.zip删")
            java.util.zip.ZipOutputStream(zip.outputStream()).use{z->z.putNextEntry(java.util.zip.ZipEntry("内层.7z删除我"));z.write(seven.readBytes());z.closeEntry()}
            verify(ArchiveImport.extract(zip,File(root,"nested")).single().readText()==input.readText(),"手机 ZIP 内嵌 7z 自动展开")
            ui{app.readImport(Uri.fromFile(zip))}
            waitFor{test.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("导入所选")?.isNotEmpty()==true}
            shot("archive-source")
            ui{app.readImport(Uri.fromFile(input))}
            waitFor{test.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("导入并整理")?.isNotEmpty()==true}
            shot("simple-import")
            val batchA=File(root,"批量甲.txt").apply{writeText("第一章 甲\n批量测试甲。"+System.nanoTime())}
            val batchB=File(root,"批量乙.txt").apply{writeText("第一章 乙\n批量测试乙。"+System.nanoTime())}
            var batchDone=false
            ui{app.readImport(Uri.fromFile(batchA),quick=true,after={app.readImport(Uri.fromFile(batchB),quick=true,after={batchDone=true})})}
            waitFor{batchDone}
            verify(app.library.duplicate(owner,batchA.readBytes())!=null&&app.library.duplicate(owner,batchB.readBytes())!=null,"批量连续导入无需逐本确认")
            val prose="夜色落在江面上，远处的灯火随水波轻轻摇晃。\n这是一段明确的广告垃圾。\n林舟收起雨伞，沿着青石路走向书店。\n"+"店里的灯还亮着，故事才刚刚开始。\n".repeat(18)
            val bytes=("第一章 夜航\n$prose\n第二章 天明\n新的故事。").toByteArray()
            val book=app.library.import(owner,LocalPrepare.read(bytes,"清理演示.txt",ImportAgent.Options(ads=false)),bytes,"清理演示.txt",app.library.duplicate(owner,bytes))
            val original=book.chapters[0].text;val quote="这是一段明确的广告垃圾。";val start=original.indexOf(quote)
            ui{app.openBook(book)};waitFor{app.reader?.book?.id==book.id}
            val screen=app.reader!!
            val readerView=ReaderScreen::class.java.getDeclaredField("view").apply{isAccessible=true}.get(screen) as ReadingView
            ui{readerView.go(Position(0,0));ReadingView::class.java.getDeclaredField("selectionStart").apply{isAccessible=true}.setInt(readerView,start);ReadingView::class.java.getDeclaredField("selectionEnd").apply{isAccessible=true}.setInt(readerView,start+quote.length);readerView.invalidate()}
            ui{ReaderScreen::class.java.getDeclaredMethod("selection",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).apply{isAccessible=true}.invoke(screen,start,start+quote.length)}
            shot("selection-clean")
            val popup=ReaderScreen::class.java.getDeclaredField("selectionPopup").apply{isAccessible=true}.get(screen) as android.widget.PopupWindow
            ui{verify((popup.contentView as android.widget.LinearLayout).getChildAt(4).performClick(),"点击选区清理按钮")}
            test.waitForIdleSync()
            verify(screen.book.chapters[0].text==original.removeRange(start,start+quote.length),"只清理选中文字")
            verify(app.library.get(owner,book.id)!!.chapters[0].text==screen.book.chapters[0].text,"清理持久保存")
            ui{ReaderScreen::class.java.getDeclaredMethod("undoCleanup").apply{isAccessible=true}.invoke(screen)}
            verify(screen.book.chapters[0].text==original,"撤销恢复正文")
            ui{screen.showCollection("目录")};shot("directory")
            ui{(ReaderScreen::class.java.getDeclaredField("collectionDialog").apply{isAccessible=true}.get(screen) as? Dialog)?.dismiss()}
        }finally{root.deleteRecursively()}
        val secret=File(app.filesDir,"qwen-test-key")
        if(secret.isFile){
            try{
                val data=ReaderCore.parseTxt(File(app.filesDir,"qa-input/brackets.txt").readBytes(),"章节样本")
                val sample=data.chapters.filter{it.title.startsWith("[")}.take(4).joinToString("\n"){it.title+"\n"+it.text.take(800)+"\n"}.toByteArray()
                val cfg=AiConfig("https://dashscope.aliyuncs.com/compatible-mode/v1","qwen3.7-flash",secret.readText().trim(),context=app,owner=owner,task="跨来源目录样本",budget=10000)
                verify(sample.isNotEmpty()&&cfg.key.isNotBlank(),"真实模型样本与密钥有效")
                val local=LocalPrepare.read(sample,"样本.txt",ImportAgent.Options(ads=false))
                val actual=ImportAgent(cfg).process(sample,"样本.txt",ImportAgent.Options(ads=false))
                verify(actual.chapters.size==local.chapters.size,"Qwen 保留实际括号章节")
                verify(actual.chapters.joinToString(""){it.text}==local.chapters.joinToString(""){it.text},"Qwen 目录处理不改正文")
                File(app.filesDir,"qa/114-model-usage.json").writeText(app.getSharedPreferences("ai-usage:$owner",0).getString("entries","[]")!!)
            }finally{secret.delete()}
        }
        return "$count checks passed; native DZIP/7z, share registration, cleanup persistence and undo"
    }
}
