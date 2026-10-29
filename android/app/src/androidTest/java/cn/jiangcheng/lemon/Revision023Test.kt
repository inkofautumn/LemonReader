package cn.jiangcheng.lemon

import android.app.Dialog
import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import cn.jiangcheng.lemon.data.Book
import cn.jiangcheng.lemon.reader.*
import cn.jiangcheng.lemon.ui.Appearance
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Exercise quoted drafts with the real IME and touch coordinates, including a narrow viewport. */
object Revision023Test {
    fun run(i:Instrumentation,app:MainActivity):String {
        var checks=0
        fun verify(ok:Boolean,message:String){check(ok){message};checks++}
        fun main(run:()->Unit){i.runOnMainSync(run);i.waitForIdleSync();Thread.sleep(150)}
        fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
        fun capture(name:String){Thread.sleep(200);val image=i.uiAutomation.takeScreenshot()!!;File(app.getExternalFilesDir(null),"audit023/$name.png").apply{parentFile!!.mkdirs()}.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
        val owner="qa-layout-023"
        val profile=JSONObject().put("id","fixture023").put("name","示例模型").put("provider","custom").put("base","https://example.com/v1").put("models","qwen3.7-flash,very-long-model-name-for-layout-test")
        main{
            app.profileId=owner;
            app.prefs.edit().putBoolean("auto_chronicles:$owner",false).putString("models:$owner",JSONArray().put(profile).toString()).commit()
            app.secrets.set("model-key:$owner:fixture023","fixture-only")
            Appearance.setMode(app,owner,"默认主题")
            app.getSharedPreferences("reading:$owner",0).edit().putFloat("panel_fraction:说书人",.57f).putBoolean("panel_full:说书人",false).commit()
        }
        val text="流涌间，李从欣眉心微动。妖魔自有散修们应对，他想着眼前这番话，暂时没有开口。\n\n".repeat(12)
        val book=Book(title="引用输入与书志布局",chapters=listOf(Chapter("第203章",text)),chronicles=listOf(
            Chronicle(id="full",kind="人物",title="菜月昴",content="收到书信的旅人。",chapter=0,offset=8,evidence="菜月昴"),
            Chronicle(id="short",kind="人物",title="昴",content="带回书信。",chapter=0,offset=8,evidence="昴")))
        lateinit var reader:ReaderScreen
        val menu=ReaderScreen::class.java.getDeclaredField("menu").apply{isAccessible=true}
        val chat=ReaderScreen::class.java.getDeclaredMethod("chat",Position::class.java,String::class.java,String::class.java).apply{isAccessible=true}
        fun dialog()=menu.get(reader) as Dialog
        fun all()=views(dialog().window!!.decorView)
        fun input()=all().filterIsInstance<EditText>().first()
        fun control(name:String)=all().first{it.contentDescription==name}
        val imm=app.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        main{reader=ReaderScreen(app,book);app.reader=reader;reader.show();chat.invoke(reader,Position(0,text.length),"","")}
        var emptyHeight=0
        main{
            emptyHeight=input().height
            val model=control("选择对话模型") as TextView
            verify(model.width<=model.paint.measureText(model.text.toString())+app.dp(45),"短模型名仍被横向拉伸")
            val next=control("新对话");verify(next.left-model.right==app.dp(8),"新对话与模型没有紧凑相邻")
            verify(input().height>=app.dp(88),"输入区不足三行空间")
        };capture("01-compact-composer")
        val quote="妖魔自有散修们应对，散修们自有刑明亮安排着。他自有自己的来钱路子，还能顺便看看这里究竟发生了什么。".repeat(3)
        main{dialog().dismiss();chat.invoke(reader,Position(0,text.length),"这个是什么意思？",quote)}
        main{
            verify(input().height==emptyHeight,"引用挤压原有输入空间")
            verify(control("已引用原文").parent!=input().parent,"引用仍挤在同一个输入卡片")
            input().requestFocus();input().setSelection(input().length());imm.showSoftInput(input(),InputMethodManager.SHOW_IMPLICIT)
        }
        Thread.sleep(5000);i.waitForIdleSync();capture("ime-observed")
        main{
            val ime=dialog().window!!.decorView.rootWindowInsets
            verify(ime.isVisible(WindowInsets.Type.ime()),"实际软键盘未完整展开：${ime.getInsets(WindowInsets.Type.ime()).bottom}")
            verify(input().height==emptyHeight,"键盘出现时输入区被压缩")
            for(name in listOf("选择对话模型","新对话","发送")){
                val v=control(name);val r=Rect();verify(v.getGlobalVisibleRect(r)&&r.height()==v.height,"键盘遮挡底栏：$name")
            }
        };capture("02-quote-keyboard")
        val rect=Rect();main{input().getGlobalVisibleRect(rect)}
        val whenDown=SystemClock.uptimeMillis()
        i.sendPointerSync(MotionEvent.obtain(whenDown,whenDown,MotionEvent.ACTION_DOWN,rect.centerX().toFloat(),(rect.bottom-app.dp(14)).toFloat(),0))
        i.sendPointerSync(MotionEvent.obtain(whenDown,whenDown+50,MotionEvent.ACTION_UP,rect.centerX().toFloat(),(rect.bottom-app.dp(14)).toFloat(),0))
        i.waitForIdleSync()
        main{verify(input().text.toString()=="这个是什么意思？"&&control("已引用原文").visibility==View.VISIBLE,"点击输入区底部误触新对话或发送")}
        i.sendStringSync(" more");i.waitForIdleSync()
        main{verify(input().text.contains("more"),"输入区域不能继续编辑");input().setText("第一行问题\n第二行补充\n第三行说明\n第四行细节");input().setSelection(input().length())}
        main{val send=control("发送");val r=Rect();verify(send.getGlobalVisibleRect(r)&&r.height()==send.height,"多行输入挤出发送按钮")}
        capture("03-multiline-keyboard")
        main{control("移除引用").performClick();verify(input().text.startsWith("第一行问题"),"移除引用丢失草稿");verify(control("已引用原文").visibility==View.GONE,"引用未移除");imm.hideSoftInputFromWindow(input().windowToken,0)}
        Thread.sleep(700)
        main{input().setText("");control("选择对话模型").performClick()}
        val node=i.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("示例模型 · very-long-model-name-for-layout-test").first{it.text?.toString()=="示例模型 · very-long-model-name-for-layout-test"}
        node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);i.waitForIdleSync();Thread.sleep(250)
        main{val controls=listOf("选择对话模型","新对话","发送").map{control(it)};verify(controls.all{val r=Rect();it.getGlobalVisibleRect(r)&&r.width()==it.width},"长模型名挤出旁边操作");control("新对话").performClick();verify(input().text.isEmpty(),"新对话未清空")}
        capture("04-long-model")
        main{dialog().dismiss();reader.showCollection("书志")};Thread.sleep(500)
        main{val merge=control("检测人物别名并合并");val sort=control("书志排序");verify(merge.parent==sort.parent,"合并未进入排序工具栏");verify(merge.top==sort.top&&merge.height==sort.height,"书志工具未对齐")}
        capture("05-chronicle-toolbar")
        main{dialog().dismiss();reader.stop();app.reader=null;Appearance.setMode(app,owner,"深色模式");reader=ReaderScreen(app,book);app.reader=reader;reader.show();chat.invoke(reader,Position(0,text.length),"这个是什么意思？",quote);input().requestFocus();imm.showSoftInput(input(),InputMethodManager.SHOW_IMPLICIT)}
        Thread.sleep(800);capture("06-night-quote-keyboard")
        main{imm.hideSoftInputFromWindow(input().windowToken,0);dialog().dismiss();reader.stop();app.reader=null;Appearance.setMode(app,owner,"默认主题");app.showHome();app.secrets.remove("model-key:$owner:fixture023")}
        return "$checks checks passed: compact model, quoted draft, actual IME, touch editing, remove quote, multiline, long model, chronicle toolbar; 6 screenshots"
    }
}
