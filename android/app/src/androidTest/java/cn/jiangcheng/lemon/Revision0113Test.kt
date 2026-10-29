package cn.jiangcheng.lemon

import android.app.Instrumentation
import android.app.Dialog
import android.graphics.Bitmap
import cn.jiangcheng.lemon.reader.*
import java.io.File

object Revision0113Test {
    fun run(test:Instrumentation,app:MainActivity):String {
        check(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"))
        var checks=0
        fun verify(ok:Boolean,message:String){check(ok){message};checks++}
        fun ui(block:()->Unit){test.runOnMainSync(block);test.waitForIdleSync()}
        fun shot(name:String){Thread.sleep(700);test.uiAutomation.takeScreenshot().let{b->File(app.filesDir,"qa/113-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
        val owner="revision0113"
        File(app.filesDir,"qa").mkdirs()
        ui{app.profileId=owner;app.prefs.edit().putBoolean("auto_chronicles:$owner",false).apply();app.getSharedPreferences("reading:$owner",0).edit().putBoolean("toc_drawer",true).apply()}
        for((file,title,count) in listOf(Triple("106","98水利一班武林大会",26),Triple("73","22世纪杀戮机器",96))){
            val bytes=File(app.filesDir,"qa-input/$file.txt").readBytes()
            var calls=0
            val agent=ImportAgent(AiConfig("","mock","test")){calls++;error("明确的导出结构不应调用模型")}
            val result=agent.process(bytes,"$title.txt",ImportAgent.Options())
            verify(calls==0,"本地识别没有 token 消耗")
            verify(result.chapters.size==count+1,"实际文件完整目录")
            verify(result.chapters.none{it.text.contains("www.ZaiHen.com")},"明确推广行已清理")
            val untouched=LocalPrepare.read(bytes,"$title.txt",ImportAgent.Options(ads=false))
            verify(untouched.chapters.first().text.contains("www.ZaiHen.com"),"广告清理可关闭")
            val original=LocalPrepare.read(bytes,"$title.txt",ImportAgent.Options(false,false,false,false))
            verify(original.chapters.single().text.contains("字数:"),"原文恢复保留来源文本")
            val book=app.library.import(owner,result,bytes,"$title.txt",app.library.duplicate(owner,bytes))
            ui{app.openBook(book)}
            val deadline=System.currentTimeMillis()+30000
            while(app.reader?.book?.id!=book.id&&System.currentTimeMillis()<deadline)Thread.sleep(100)
            verify(app.reader?.book?.id==book.id,"实际阅读界面加载完成")
            ui{(ReaderScreen::class.java.getDeclaredField("view").apply{isAccessible=true}.get(app.reader) as ReadingView).go(Position(1,0))}
            shot("$file-reading")
            ui{app.reader!!.showCollection("目录")};shot("$file-directory")
            ui{val field=ReaderScreen::class.java.getDeclaredField("collectionDialog").apply{isAccessible=true};(field.get(app.reader) as? Dialog)?.dismiss()}
        }
        ui{app.importSettings()};shot("import-options")
        return "$checks checks passed; actual TXT directories 26 and 96 sections; model calls 0"
    }
}
