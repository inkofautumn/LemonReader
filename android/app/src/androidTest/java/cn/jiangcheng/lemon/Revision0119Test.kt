package cn.jiangcheng.lemon
import android.app.Instrumentation
import android.graphics.Bitmap
import android.text.StaticLayout
import cn.jiangcheng.lemon.reader.*
import java.io.File
object Revision0119Test {
 fun run(test:Instrumentation,app:MainActivity):String{
  var checks=0
  lateinit var view:ReadingView;test.runOnMainSync{app.profileId="indent0119";view=ReadingView(app)};val style=ReadingStyle()
  val method=ReadingView::class.java.getDeclaredMethod("makeLayout",String::class.java,ReadingStyle::class.java,Int::class.javaPrimitiveType,String::class.java).apply{isAccessible=true}
  val width=app.resources.displayMetrics.widthPixels
  val prefixes=listOf("","  ","    ","\u3000\u3000","\t","\t  ","\u00a0\u00a0","\u2003\u2003","\uFEFF\u3000")
  var reference=0f
  for((i,prefix) in prefixes.withIndex()){
   val source=prefix+"林舟走进书店，望向窗外。".repeat(12)+"\n"+prefix+"苏晴轻声问好。"
   val layout=method.invoke(view,source,style,width,"") as StaticLayout
   val x=layout.getPrimaryHorizontal(prefix.length)
   if(i==0)reference=x
   check(kotlin.math.abs(x-reference)<1f){"段首不一致: $i x=$x expected=$reference"};checks++
   check(layout.text.toString()==source){"原文字位发生改变"};checks++
   check(kotlin.math.abs(layout.getPrimaryHorizontal(layout.getLineStart(1)))<1f){"续行不应缩进"};checks++
   val second=source.indexOf("苏晴");check(kotlin.math.abs(layout.getPrimaryHorizontal(second)-reference)<1f);checks++
  }
  val source="\u3000\u3000林舟  望向窗外。\n\t苏晴轻声问好。"
  val match=TextSearch.find(listOf(Chapter("章",source)),"苏晴").single()
  check(source.substring(match.start,match.end)=="苏晴");checks++
  test.runOnMainSync{app.window.decorView.systemUiVisibility=android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;view.style=style;app.setContentView(view);view.setBook(listOf(Chapter("首行缩进测试","\u3000\u3000夜色落在江面上，远处的灯火随水波轻轻摇晃。林舟收起雨伞，沿着青石路走向书店。\n    苏晴推开店门，看见柜台上放着一封信。她停下脚步，轻声向店主问好。\n\t窗外的雨渐渐停了。街角的行人撑着伞，沿着青石路走向远方。\n没有原文空格的段落，也保持相同的两个字缩进。")),Position(0,0))}
  test.waitForIdleSync();Thread.sleep(500);val bmp=test.uiAutomation.takeScreenshot();File(app.filesDir,"qa").mkdirs();File(app.filesDir,"qa/119-indent.png").outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)};bmp.recycle()
  return "$checks indentation checks passed"
 }
}
