package cn.jiangcheng.lemon

import android.app.*
import android.view.*
import android.widget.*
import android.graphics.Bitmap
import cn.jiangcheng.lemon.reader.*
import cn.jiangcheng.lemon.data.*
import java.io.File

object Revision0118Test {
 fun run(test:Instrumentation,app:MainActivity):String{
  var checks=0
  fun ui(f:()->Unit){test.runOnMainSync(f);test.waitForIdleSync()}
  fun checkThat(value:Boolean,why:String){check(value){why};checks++}
  fun tree(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{tree(v.getChildAt(it))}else emptyList()
  fun shot(name:String){Thread.sleep(350);val bmp=test.uiAutomation.takeScreenshot();File(app.filesDir,"qa").mkdirs();File(app.filesDir,"qa/118-$name.png").outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)};bmp.recycle()}
  val owner="revision0118";ui{app.profileId=owner;
   listOf("夜航","山海来信","长安旧事","风起青萍","竹间集","月下行舟").forEachIndexed{i,title->app.library.save(owner,Book(id="shelf118-$i",title=title,chapters=listOf(Chapter("第一章",title)),folder=if(i<2)"闲时再读"else ""))};app.prefs.edit().putBoolean("shelf-list:$owner",false).putBoolean("auto_chronicles:$owner",false).apply();app.showShelf(folder="")}
  fun main()=tree(app.window.decorView)
  val badge=main().filterIsInstance<TextView>().first{it.text.toString()=="2 本"}
  checkThat(badge.layout.getLineBottom(0)+badge.compoundPaddingTop+badge.compoundPaddingBottom<=badge.height,"文件夹数量行完整容纳，底部不裁切")
  checkThat(main().filterIsInstance<Button>().filter{it.text.toString() in listOf("最近阅读","列表")}.all{it.compoundDrawablesRelative.any{d->d!=null}},"书架排序和视图按钮含图标")
  shot("shelf")
  val chapters=listOf(Chapter("第一卷 鬼来访 第一章 上门","夜色降临。林舟打开书店。"),Chapter("第一卷 鬼来访 第二章 字门","苏晴来到店里，林舟向她问好。"))
  val b=Book(id="qa118",title="夜航 · 面板测试",chapters=chapters)
  app.library.save(owner,b)
  ui{app.getSharedPreferences("reading:$owner",0).edit().putFloat("panel_fraction",.68f).putBoolean("panels_full",false).apply();app.openBook(b)}
  val deadline=System.currentTimeMillis()+20000;while(app.reader==null&&System.currentTimeMillis()<deadline)Thread.sleep(50)
  val reader=app.reader!!
  fun dialog()=ReaderScreen::class.java.getDeclaredField("menu").apply{isAccessible=true}.get(reader) as Dialog
  fun nodes()=tree(dialog().window!!.decorView)
  ui{ReaderScreen::class.java.getDeclaredMethod("searchMenu").apply{isAccessible=true}.invoke(reader)}
  val input=nodes().filterIsInstance<EditText>().single();val searchButton=nodes().filterIsInstance<Button>().single{it.text.toString()=="开始搜索"}
  checkThat(searchButton.background!=null&&searchButton.compoundDrawablesRelative.any{it!=null},"搜索使用图标和实色主按钮")
  checkThat(nodes().none{(it as? TextView)?.text?.toString() in listOf("上一级","全屏","半屏","返回阅读")},"面板标题栏改为统一图标操作")
  shot("search")
  ui{input.setText("林舟")}
  val d=dialog();val initial=d.window!!.attributes.height;val grip=nodes().first{it.contentDescription=="拖动调整面板高度"}
  fun drag(delta:Float){ui{val t=android.os.SystemClock.uptimeMillis();listOf(MotionEvent.ACTION_DOWN to 500f,MotionEvent.ACTION_MOVE to (500f-delta),MotionEvent.ACTION_UP to (500f-delta)).forEach{(action,y)->val e=MotionEvent.obtain(t,t+10,action,50f,y,0);grip.dispatchTouchEvent(e);e.recycle()}}}
  drag(160f);val enlarged=d.window!!.attributes.height
  checkThat(enlarged>initial+100&&enlarged<app.resources.displayMetrics.heightPixels,"面板向上拖动连续增高，不跳到全屏")
  drag(-80f);checkThat(d.window!!.attributes.height<enlarged&&d.window!!.attributes.height>initial,"面板可向下连续缩小")
  checkThat(input.text.toString()=="林舟"&&d.isShowing,"调整高度保留输入与同一面板")
  shot("drag")
  ui{nodes().first{it.contentDescription=="返回阅读"}.performClick()}
  val saved=app.getSharedPreferences("reading:$owner",0).getFloat("panel_fraction",0f);checkThat(saved>.68f&&saved<1f,"保存中间高度")
  ui{reader.showCollection("目录")}
  ui{ReaderScreen::class.java.getDeclaredMethod("directoryDisplaySettings").apply{isAccessible=true}.invoke(reader)}
  checkThat(nodes().filterIsInstance<RadioButton>().map{it.text.toString()}==listOf("显示原章名","仅显示编号","两者都显示"),"目录显示三项单选")
  ui{nodes().filterIsInstance<RadioButton>().last().performClick()};checkThat(nodes().filterIsInstance<RadioButton>().count{it.isChecked}==1,"显示方式互斥")
  shot("display")
  ui{nodes().filterIsInstance<TextView>().first{it.text.toString()=="完成"}.performClick()}
  checkThat(nodes().filterIsInstance<TextView>().any{it.text.toString()=="（第1章）第一卷 鬼来访 第一章 上门"},"目录同时展示编号前缀与完整章名")
  shot("directory")
  val context=ReaderCore.context(chapters,Position(1,chapters[1].text.length),"林舟")
  checkThat(context.contains("【第一卷 鬼来访 第一章 上门】")&&!context.contains("[第1章·字"),"模型资料沿用卷章原名")
  checkThat(ReaderCore.citationTitles("依据[第2章·字3]",chapters)=="依据【第一卷 鬼来访 第二章 字门】","旧引用转换为原章名")
  ui{nodes().firstOrNull{it.contentDescription=="收起"}?.performClick();reader.stop();app.reader=null;app.showShelf(folder="")}
  return "$checks revision 0.1.18 checks passed"
 }
}
