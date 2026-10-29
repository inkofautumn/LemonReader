package cn.jiangcheng.lemon

import android.app.Instrumentation
import android.app.Dialog
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import cn.jiangcheng.lemon.data.Book
import cn.jiangcheng.lemon.reader.*
import java.io.File

/** Fresh, isolated fixture screens; never calls a live model or reads user books. */
object AuditScreens {
    fun run(i:Instrumentation,app:MainActivity):String {
        fun main(action:()->Unit){i.runOnMainSync(action);i.waitForIdleSync();Thread.sleep(250)}
        fun capture(name:String){Thread.sleep(200);val b=i.uiAutomation.takeScreenshot()?:error("screenshot unavailable");File(app.getExternalFilesDir(null),"audit/$name.png").apply{parentFile!!.mkdirs()}.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        fun descendants(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{descendants(v.getChildAt(it))}else emptyList()
        val owner="qa-lemon-audit"
        main{app.profileId=owner;app.prefs.edit().putBoolean("auto_chronicles:$owner",false).commit();app.showHome()}
        capture("01-home")
        main{app.showSettings()};capture("02-settings")
        main{ModelProfiles.show(app)};capture("03-models")
        main{descendants(app.window.decorView).filterIsInstance<TextView>().first{it.text.toString()=="添加模型 API"}.performClick()};capture("04-providers")
        i.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);i.waitForIdleSync()
        val text=("　　江宁在渡口遇见了沈青。沈青交给他一封信，请他到城南寻找旧友。\n\n"+
            "　　次日清晨，江宁独自到了城南。信上留着一枚青色印记，却没有落款。\n\n").repeat(35)
        val b=Book(title="渡口来信 · 界面检查",chapters=listOf(Chapter("第一章 渡口",text)))
        b.chronicles=listOf(Chronicle(kind="人物",title="江宁",content="在渡口收到一封信，前往城南寻找旧友。",chapter=0,offset=24,evidence="江宁在渡口遇见了沈青。"),Chronicle(kind="人物",title="沈青",content="在渡口交出书信，旧友的身份尚待了解。",chapter=0,offset=28),Chronicle(kind="线索",title="青色印记",content="书信没有落款，留有青色印记。",chapter=0,offset=30))
        lateinit var r:ReaderScreen
        main{r=ReaderScreen(app,b);app.reader=r;r.show()};capture("05-reader")
        main{r.showCollection("书志")};capture("06-chronicles")
        val menu=ReaderScreen::class.java.getDeclaredField("menu").apply{isAccessible=true}
        main{(menu.get(r) as? Dialog)?.dismiss();ReaderScreen::class.java.getDeclaredMethod("chat",Position::class.java,String::class.java,String::class.java).apply{isAccessible=true}.invoke(r,Position(0,120),"","")};capture("07-storyteller")
        main{(menu.get(r) as? Dialog)?.dismiss();ReaderScreen::class.java.getDeclaredMethod("settings").apply{isAccessible=true}.invoke(r)};capture("08-reader-settings")
        main{(menu.get(r) as? Dialog)?.dismiss();r.showCollection("札记")};capture("09-notes")
        main{(menu.get(r) as? Dialog)?.dismiss();r.stop();app.reader=null;app.showHome()}
        main{app.library.save(owner,b);app.showShelf()};capture("10-shelf")
        main{app.importSettings()};capture("11-import-settings")
        main{app.showSettings()};capture("12-settings")
        val profile=org.json.JSONObject().put("id","audit-placeholder").put("name","我的模型连接").put("provider","custom").put("base","https://example.com/v1").put("models","model-chat,model-reasoning")
        main{app.prefs.edit().putString("models:$owner",org.json.JSONArray().put(profile).toString()).commit();ModelProfiles.show(app)};capture("13-model-routes")
        main{descendants(app.window.decorView).filterIsInstance<TextView>().first{it.text.toString()=="我的模型连接"}.let{(it.parent.parent as View).performClick()};check(descendants(app.window.decorView).filterIsInstance<android.widget.EditText>().any{it.hint.toString()=="接口地址"})};capture("14-model-edit")
        main{app.library.deleteBook(owner,b);app.showHome()}
        val shimo=i.startActivitySync(android.content.Intent(app,cn.jiangcheng.lemon.shimo.ShimoActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        i.waitForIdleSync();capture("15-shimo")
        main{shimo.finish()}
        main{cn.jiangcheng.lemon.ui.Appearance.setMode(app,owner,"深色模式");app.showSettings()};capture("16-night-settings")
        main{ModelProfiles.show(app)};capture("17-night-models")
        main{r=ReaderScreen(app,b);app.reader=r;r.show()}
        main{r.showCollection("书志")};capture("18-night-chronicles")
        main{(menu.get(r) as? Dialog)?.dismiss();ReaderScreen::class.java.getDeclaredMethod("showMenu").apply{isAccessible=true}.invoke(r)}
        main{val button=descendants((menu.get(r) as Dialog).window!!.decorView).filterIsInstance<TextView>().first{it.text=="返回书架"};val rect=android.graphics.Rect();check(button.getGlobalVisibleRect(rect)&&rect.height()>=button.height-2){"阅读工具栏底部按钮被截断"}}
        capture("19-reader-toolbar")
        main{(menu.get(r) as? Dialog)?.dismiss();ReaderScreen::class.java.getDeclaredMethod("searchMenu").apply{isAccessible=true}.invoke(r)};capture("20-search")
        main{(menu.get(r) as? Dialog)?.dismiss();r.showCollection("目录")};capture("21-directory")
        main{(menu.get(r) as? Dialog)?.dismiss();r.stop();app.reader=null;cn.jiangcheng.lemon.ui.Appearance.setMode(app,owner,"默认主题");app.showHome()}
        return "21 fresh UI screenshots captured"
    }
}
