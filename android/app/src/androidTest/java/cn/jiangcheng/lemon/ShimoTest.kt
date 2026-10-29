package cn.jiangcheng.lemon

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import cn.jiangcheng.lemon.data.Book
import cn.jiangcheng.lemon.data.LocalProfile
import cn.jiangcheng.lemon.reader.Chapter
import cn.jiangcheng.lemon.reader.Note
import cn.jiangcheng.lemon.shimo.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ShimoTest {
    fun run(i:Instrumentation):String {
        val context=i.targetContext;val owner="shimo-qa-${UUID.randomUUID()}";val other="$owner-other";val store=ShimoStore(context);var checks=0
        fun verify(value:Boolean,label:String){check(value){label};checks++}
        val folder=store.folder(owner,"写作素材");val imageName="${UUID.randomUUID()}.png"
        val bitmap=Bitmap.createBitmap(1000,600,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE);val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=62f};canvas.drawText("拾墨，收藏读到的好文字。",48f,140f,paint);canvas.drawText("他将旧信放回了书里。",48f,250f,paint);store.media(imageName).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        val e=Excerpt(title="旧信与告别",text="他将旧信放回了书里。",thought="这封信是谁寄来的？",folder=folder,tags=listOf("伏笔","喜欢"),images=listOf(imageName),source="拍照")
        store.save(owner,e);verify(store.get(other,e.id)==null,"本地资料隔离")
        e.text="修改后的摘录";store.save(owner,e);verify(store.get(owner,e.id)?.text==e.text,"本地编辑")
        val book=Book(title="测试书",chapters=listOf(Chapter("第一卷 第一章", "窗外的雨停了。")))
        cn.jiangcheng.lemon.data.Library(context).use{it.save(owner,book)}
        val note=Note(chapter=0,start=0,end=7,quote="窗外的雨停了。",body="",type="划线")
        verify(store.collect(owner,book,note),"划线收录");verify(!store.collect(owner,book,note),"稳定ID去重")
        val note2=note.copy(id=UUID.randomUUID().toString(),body="雨停之后呢？",type="札记")
        verify(!store.collect(owner,book,note2),"同位置合并");val linked=store.all(owner).first{it.bookId==book.id};verify(linked.thought==note2.body,"札记感想补齐")
        linked.deleted=true;store.save(owner,linked);verify(!store.collect(owner,book,note2),"删除后不重现");verify(store.all(owner,true).size==1,"回收站")
        linked.deleted=false;store.save(owner,linked);verify(store.all(owner).size==2,"恢复")
        store.renameTag(owner,"伏笔","线索");verify(store.get(owner,e.id)!!.tags==listOf("线索","喜欢"),"标签改名")
        store.removeFolder(owner,folder);verify(store.get(owner,e.id)!!.folder==""&&store.all(owner).size==2,"删文件夹保留内容")
        val out=ByteArrayOutputStream();store.export(owner,out);verify(store.import(other,ByteArrayInputStream(out.toByteArray()))==2,"备份导入")
        verify(store.import(other,ByteArrayInputStream(out.toByteArray()))==0,"重复导入幂等")
        verify(store.media(store.get(other,e.id)!!.images.first()).isFile,"图片恢复")
        val malicious=ByteArrayOutputStream();ZipOutputStream(malicious).use{it.putNextEntry(ZipEntry("../escape.txt"));it.write(byteArrayOf(1));it.closeEntry()}
        verify(runCatching{store.import(other,ByteArrayInputStream(malicious.toByteArray()))}.isFailure,"路径穿越拒绝")
        verify(store.all(other).size==2,"失败导入不破坏资料")
        val recognizer=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try{val result=Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap,0)),60,TimeUnit.SECONDS);verify(result.text.contains("收藏")&&result.text.contains("旧信"),"中文本地OCR：${result.text}")}finally{recognizer.close()}
        // Lemon uses a fixed local profile; keep this fixture isolated without restoring
        // the old account-based production path just to satisfy a legacy test.
        fun useFixture(activity:ShimoActivity){
            i.runOnMainSync{
                val field=ShimoActivity::class.java.getDeclaredField("owner").apply{isAccessible=true}
                verify(field.get(activity)==LocalProfile.ID,"公开版使用本地资料域")
                field.set(activity,owner)
                ShimoActivity::class.java.getDeclaredField("viewMode").apply{isAccessible=true}.set(activity,context.getSharedPreferences("shimo-ui:$owner",0).getString("view","标准卡片"))
                ShimoActivity::class.java.getDeclaredMethod("showList").apply{isAccessible=true}.invoke(activity)
            }
        }
        var activity:ShimoActivity?=null
        try {
            activity=i.startActivitySync(Intent(context,ShimoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ShimoActivity
            useFixture(activity)
            i.waitForIdleSync();Thread.sleep(400)
            fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
            var all=emptyList<View>();i.runOnMainSync{all=views(activity!!.window.decorView)}
            verify(all.filterIsInstance<TextView>().any{it.text.toString().contains("旧信与告别")},"卡片列表")
            i.runOnMainSync{val root=activity!!.window.decorView;val screenshot=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888);root.draw(Canvas(screenshot));File(context.filesDir,"shimo-list.png").outputStream().use{screenshot.compress(Bitmap.CompressFormat.PNG,100,it)}}
            i.runOnMainSync{activity!!.edit(store.get(owner,e.id)!!)};i.waitForIdleSync()
            i.runOnMainSync{val root=activity!!.window.decorView;val screenshot=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888);root.draw(Canvas(screenshot));File(context.filesDir,"shimo-edit.png").outputStream().use{screenshot.compress(Bitmap.CompressFormat.PNG,100,it)}}
            verify(store.get(other,e.id)?.text=="修改后的摘录","查看不混用本地资料")
            i.runOnMainSync{views(activity!!.window.decorView).filterIsInstance<android.widget.EditText>().first{it.contentDescription=="摘抄内容"}.setText("返回前自动保存的文字")}
            i.runOnMainSync{activity!!.onBackPressed()};i.waitForIdleSync()
            verify(store.get(owner,e.id)?.text=="返回前自动保存的文字","返回前提交编辑")
            i.runOnMainSync{activity!!.edit(store.get(owner,linked.id)!!)};i.waitForIdleSync()
            i.runOnMainSync{views(activity!!.window.decorView).filterIsInstance<android.widget.EditText>().first{it.contentDescription=="摘抄内容"}.setText("修改摘抄不改原文")}
            Thread.sleep(600);i.waitForIdleSync()
            verify(store.get(owner,linked.id)?.original==note.quote,"原文快照保留")
            verify(store.get(owner,linked.id)?.text=="修改摘抄不改原文","延迟自动保存")
            i.runOnMainSync{views(activity!!.window.decorView).first{it.contentDescription=="定位原文"}.performClick()};i.waitForIdleSync()
            var highlighted=false;i.runOnMainSync{highlighted=views(activity!!.window.decorView).filterIsInstance<TextView>().any{v->val t=v.text;t is android.text.Spanned&&t.getSpans(0,t.length,android.text.style.BackgroundColorSpan::class.java).isNotEmpty()}}
            verify(highlighted,"定位原文准确高亮")
            i.runOnMainSync{views(activity!!.window.decorView).filterIsInstance<TextView>().first{it.text=="返回摘抄"}.performClick()};i.waitForIdleSync()
            var detail=false;i.runOnMainSync{detail=views(activity!!.window.decorView).filterIsInstance<TextView>().any{it.text=="摘抄"}}
            verify(detail,"原文返回摘抄详情")
            i.runOnMainSync{activity!!.finish()};i.waitForIdleSync()
            context.getSharedPreferences("shimo-ui:$owner",0).edit().putString("view","双列宫格").commit()
            activity=i.startActivitySync(Intent(context,ShimoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ShimoActivity
            useFixture(activity)
            i.waitForIdleSync();Thread.sleep(250)
            i.runOnMainSync{val root=activity!!.window.decorView;val shot=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888);root.draw(Canvas(shot));File(context.filesDir,"shimo-grid.png").outputStream().use{shot.compress(Bitmap.CompressFormat.PNG,100,it)}}
            verify(store.all(owner).size==2,"重开列表保留全部内容")
            val photo=Excerpt()
            i.runOnMainSync{activity!!.edit(photo);val method=ShimoActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java);method.isAccessible=true;method.invoke(activity,611,Activity.RESULT_OK,Intent().setData(android.net.Uri.fromFile(store.media(imageName))))}
            var savedPhoto=false
            repeat(50){if(!savedPhoto){Thread.sleep(100);i.runOnMainSync{val b=android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap{views(it)}.filterIsInstance<android.widget.Button>().firstOrNull{it.text=="保存图片"};if(b!=null){b.performClick();savedPhoto=true}}}}
            verify(savedPhoto,"相册预览确认入口")
            i.waitForIdleSync()
            val storedPhoto=store.get(owner,photo.id)
            verify(storedPhoto!=null&&storedPhoto.source=="相册"&&store.media(storedPhoto.images.single()).isFile,"预览确认保留原图")
        }finally{i.runOnMainSync{activity?.finish()};store.close()}
        return "$checks Shimo local-data, OCR and UI checks passed"
    }
}
