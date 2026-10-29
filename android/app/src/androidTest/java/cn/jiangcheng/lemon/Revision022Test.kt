package cn.jiangcheng.lemon

import android.app.Dialog
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import cn.jiangcheng.lemon.data.Book
import cn.jiangcheng.lemon.reader.*
import cn.jiangcheng.lemon.ui.Appearance
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Revision022Test {
    fun run(i:Instrumentation,app:MainActivity):String {
        var checks=0
        fun verify(ok:Boolean,message:String){check(ok){message};checks++}
        fun main(run:()->Unit){i.runOnMainSync(run);i.waitForIdleSync();Thread.sleep(180)}
        fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
        fun capture(name:String){Thread.sleep(180);val image=i.uiAutomation.takeScreenshot()!!;File(app.getExternalFilesDir(null),"audit022/$name.png").apply{parentFile!!.mkdirs()}.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
        fun clickText(text:String){val node=i.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(text).first{it.text?.toString()==text};check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));i.waitForIdleSync();Thread.sleep(300)}
        val owner=app.profileId
        main{app.prefs.edit().putBoolean("auto_chronicles:$owner",false).commit();Appearance.setMode(app,owner,"默认主题");app.showHome()}
        main{
            val home=views(app.window.decorView)
            val tiles=listOf("导入小说","阅读资料导出","拾墨").map{title->home.first{it.contentDescription==title&&it is LinearLayout}}
            verify(tiles.map{it.height}.distinct().size==1,"主页卡片高度不同")
            verify(tiles.map{val xy=IntArray(2);it.getLocationOnScreen(xy);xy[1]}.distinct().size==1,"主页卡片顶部未对齐")
            verify(tiles.all{views(it).filterIsInstance<ImageView>().size==1},"主页图标未统一")
        };capture("01-home")
        val profile=JSONObject().put("id","fixture022").put("name","示例模型").put("provider","custom").put("base","https://example.com/v1").put("models","qwen3.7-flash,very-long-model-name-for-layout-test")
        main{app.prefs.edit().putString("models:$owner",JSONArray().put(profile).toString()).commit();app.secrets.set("model-key:$owner:fixture022","fixture-only")}
        val text="菜月昴走出门，昴带回一封信。白绨雅和黑绨雅分别站在道路两侧。绨雅的身份暂未说明。\n\n".repeat(6)
        fun person(id:String,name:String,description:String)=Chronicle(id=id,kind="人物",title=name,content=description,chapter=0,offset=8,evidence=name)
        val book=Book(title="人物别名核对",chapters=listOf(Chapter("第五章",text)),chronicles=listOf(person("full","菜月昴","收到书信的旅人。"),person("short","昴","带回书信。"),person("white","白绨雅","站在路左侧。"),person("black","黑绨雅","站在路右侧。"),person("alias","绨雅","身份暂未说明。")))
        lateinit var reader:ReaderScreen
        val menu=ReaderScreen::class.java.getDeclaredField("menu").apply{isAccessible=true}
        val chat=ReaderScreen::class.java.getDeclaredMethod("chat",Position::class.java,String::class.java,String::class.java).apply{isAccessible=true}
        main{reader=ReaderScreen(app,book);app.reader=reader;reader.show();chat.invoke(reader,Position(0,text.length),"","")}
        fun checkComposer(){
            val dialog=menu.get(reader) as Dialog;val all=views(dialog.window!!.decorView)
            val controls=listOf("选择对话模型","新对话","发送").map{d->all.first{it.contentDescription==d}}
            verify(controls.map{it.height}.distinct().size==1,"说书人底栏按钮高度不一致")
            verify(controls.map{val xy=IntArray(2);it.getLocationOnScreen(xy);xy[1]}.distinct().size==1,"说书人底栏按钮垂直错位")
            verify(controls.all{val r=Rect();it.getGlobalVisibleRect(r)&&r.height()==it.height},"底栏按钮被截断")
            verify((controls[0] as TextView).gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK==android.view.Gravity.LEFT,"模型名称未左对齐")
        }
        main{checkComposer()};capture("02-composer")
        main{val dialog=menu.get(reader) as Dialog;val all=views(dialog.window!!.decorView);all.filterIsInstance<EditText>().first().setText("待发送内容");all.first{it.contentDescription=="新对话"}.performClick();verify(all.filterIsInstance<EditText>().first().text.isEmpty(),"新对话未清空输入");all.first{it.contentDescription=="选择对话模型"}.performClick()}
        // Nested reader panel is tracked separately; accessibility exercises the actual selector.
        clickText("示例模型 · very-long-model-name-for-layout-test")
        main{checkComposer()};capture("03-long-model")
        main{(menu.get(reader) as Dialog).dismiss();reader.showCollection("书志")}
        Thread.sleep(500);i.waitForIdleSync()
        main{val dialog=menu.get(reader) as Dialog;views(dialog.window!!.decorView).filterIsInstance<TextView>().first{it.text=="检测合并"}.performClick()}
        capture("04-alias-suggestions")
        clickText("菜月昴 ← 昴");clickText("合并所选")
        Thread.sleep(450)
        main{val cards=ChronicleCatalog.items(book.chronicles,book.chapters,Position(0,text.length),book.revision);verify(cards.count{it.title=="菜月昴"}==1&&cards.none{it.title=="昴"},"合并后仍有独立简称卡片");verify(cards.first{it.title=="菜月昴"}.records.size==2,"合并丢失原文记录");verify(cards.count{it.title in listOf("白绨雅","黑绨雅","绨雅")}==3,"误合并不同人物");verify(app.library.get(owner,book.id)!!.chronicles==book.chronicles,"合并结果未持久化")}
        capture("05-merged-cards")
        main{(menu.get(reader) as Dialog).dismiss();reader.stop();app.reader=null;Appearance.setMode(app,owner,"深色模式");reader=ReaderScreen(app,book);app.reader=reader;reader.show();chat.invoke(reader,Position(0,text.length),"","")}
        main{checkComposer()};capture("06-night-composer")
        main{(menu.get(reader) as Dialog).dismiss();reader.stop();app.reader=null;Appearance.setMode(app,owner,"默认主题");app.showHome();app.secrets.remove("model-key:$owner:fixture022")}
        return "$checks checks passed: aligned home/composer, long model, new chat, alias review, persistence, ambiguity; 6 screenshots"
    }
}
