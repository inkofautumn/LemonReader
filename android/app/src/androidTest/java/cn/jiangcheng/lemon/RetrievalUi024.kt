package cn.jiangcheng.lemon

import android.app.Dialog
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import cn.jiangcheng.lemon.data.Book
import cn.jiangcheng.lemon.reader.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object RetrievalUi024 {
    fun run(i:Instrumentation):String {
        val app=i.startActivitySync(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        var checks=0
        fun verify(ok:Boolean,msg:String){check(ok){msg};checks++}
        fun main(fn:()->Unit){var failure:Throwable?=null;i.runOnMainSync{try{fn()}catch(e:Throwable){failure=e}};failure?.let{throw it};i.waitForIdleSync();Thread.sleep(200)}
        fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
        val text="林舟交出铜钥匙，为了救出被困的妹妹。守卫于是打开了地牢。".repeat(8)
        val entry=JSONObject().put("conversationId","test").put("question","林舟为什么交出钥匙？").put("answer","已找到相关原文，可继续查找。").put("mode","伴读").put("revision",1).put("chapter",0).put("offset",text.length).put("incomplete",true).put("time",1)
            .put("sources",JSONArray().put(JSONObject().put("title","第一章 铜钥匙").put("chapter",0).put("start",0).put("end",text.length).put("text",text)))
        val book=Book(title="对话检索界面",chapters=listOf(Chapter("第一章 铜钥匙",text)),chats=JSONArray().put(entry))
        val menu=ReaderScreen::class.java.getDeclaredField("menu").apply{isAccessible=true}
        val active=ReaderScreen::class.java.getDeclaredField("activeConversation").apply{isAccessible=true}
        val chat=ReaderScreen::class.java.getDeclaredMethod("chat",Position::class.java,String::class.java,String::class.java).apply{isAccessible=true}
        lateinit var reader:ReaderScreen
        fun dialog()=menu.get(reader) as Dialog
        fun all()=views(dialog().window!!.decorView)
        fun clickText(value:String)=all().filterIsInstance<TextView>().first{it.text.toString()==value}.performClick()
        fun capture(name:String){val image=i.uiAutomation.takeScreenshot()!!;File(app.getExternalFilesDir(null),"audit024/$name.png").apply{parentFile!!.mkdirs()}.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
        main{
            app.profileId="qa-ui024";
            app.prefs.edit().putBoolean("auto_chronicles:${app.profileId}",false).putString("models:${app.profileId}",JSONArray().put(JSONObject().put("id","fixture").put("name","测试").put("provider","custom").put("base","https://127.0.0.1:1/v1").put("models","fixture")).toString()).commit()
            app.secrets.set("model-key:${app.profileId}:fixture","fixture-only")
            reader=ReaderScreen(app,book);app.reader=reader;reader.show();active.set(reader,"test");chat.invoke(reader,Position(0,text.length),"","")
        }
        main{verify(all().filterIsInstance<TextView>().any{it.text=="继续查找"},"重开对话丢失继续入口");clickText("查看原文 · 1 处")}
        main{verify(all().filterIsInstance<TextView>().any{it.text.toString()==text},"证据弹窗丢失正文");verify(all().filterIsInstance<TextView>().any{it.text.toString()=="第一章 铜钥匙"},"证据缺少原章名")};capture("source-view")
        main{all().first{it.contentDescription=="返回上一级"}.performClick()}
        main{verify(all().filterIsInstance<TextView>().any{it.text=="继续查找"},"查看证据后没回到原对话");clickText("继续查找");verify(all().any{it.contentDescription=="停止回答"},"发送后没有停止操作");all().first{it.contentDescription=="停止回答"}.performClick()}
        Thread.sleep(1200);i.waitForIdleSync()
        main{verify(all().any{it.contentDescription=="发送"},"停止后不能再次发送");verify(all().filterIsInstance<EditText>().first().text.toString()==entry.getString("question"),"停止后丢失问题草稿")};capture("cancelled-turn")
        main{dialog().dismiss();reader.stop();app.reader=null;app.secrets.remove("model-key:${app.profileId}:fixture");app.showHome()}
        return "$checks checks passed: persisted continuation, evidence viewer, back, cancellation and draft restoration"
    }
}
