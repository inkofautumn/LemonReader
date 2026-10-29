package cn.jiangcheng.lemon

import android.app.Dialog
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.widget.*
import cn.jiangcheng.lemon.reader.*
import cn.jiangcheng.lemon.ui.ScanScreen
import cn.jiangcheng.lemon.data.*
import org.json.*
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object Revision0110Test {
    fun run(test:Instrumentation,app:MainActivity):Int {
        check(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"))
        var checks=0
        fun verify(value:Boolean,label:String){check(value){label};checks++}
        fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
        fun ui(block:()->Unit){test.runOnMainSync(block);test.waitForIdleSync()}
        fun waitFor(label:String,condition:()->Boolean){val end=System.currentTimeMillis()+45000;while(System.currentTimeMillis()<end){test.waitForIdleSync();if(condition())return;Thread.sleep(80)};error(label)}
        fun root()=views(app.window.decorView)
        fun dialog():Dialog=ReaderScreen::class.java.getDeclaredField("menu").apply{isAccessible=true}.get(app.reader) as Dialog
        fun reading()=root().filterIsInstance<ReadingView>().single()
        fun invoke(name:String){ui{ReaderScreen::class.java.getDeclaredMethod(name).apply{isAccessible=true}.invoke(app.reader)}}
        fun click(label:String){ui{views(dialog().window!!.decorView).filterIsInstance<TextView>().first{it.text.toString()==label}.performClick()}}
        fun close(){ui{dialog().dismiss()};Thread.sleep(280)}
        fun windowClick(label:String){waitFor("缺少弹窗选项 $label"){test.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)?.firstOrNull{it.text?.toString()==label&&it.isClickable}?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)==true};test.waitForIdleSync()}
        fun shot(name:String){test.waitForIdleSync();Thread.sleep(450);val dir=File(app.filesDir,"qa").apply{mkdirs()};test.uiAutomation.takeScreenshot().let{b->File(dir,name+".png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
        val owner="reading0110-${System.currentTimeMillis()}"
        ui{app.profileId=owner;app.prefs.edit().putString("appearance","默认主题").apply();app.showShelf()}

        // Exact failure reasons, smaller retry, and a full readable tail for every snapshot.
        val cfg=AiConfig("http://127.0.0.1:18767","fixture","fixture")
        fun response(value:JSONObject,finish:String="stop")=JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason",finish).put("message",JSONObject().put("content",value.toString()))))
        fun empty()=JSONObject().put("chapters",JSONArray()).put("edits",JSONArray()).put("trim",JSONArray())
        val text=(1..40).joinToString("\n"){"第${it}章 夜航\n"+"江面上的灯火渐渐明亮，林舟收起雨伞。".repeat(30)}
        var calls=0;var firstSize=0;var retrySize=0;val codes=mutableListOf<String>();var snapshots=0
        val agent=ImportAgent(cfg){request->
            calls++;val input=JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"));val fragments=input.getJSONArray("fragments")
            if(calls==1){firstSize=fragments.length();response(empty(),"length")}else{
                if(calls==2)retrySize=fragments.length()
                val headings=JSONArray();for(i in 0 until fragments.length()){val f=fragments.getJSONObject(i);if(f.getString("text").startsWith("第"))headings.put(JSONObject().put("id",f.getInt("id")).put("title",f.getString("text").trim()))}
                response(empty().put("chapters",headings))
            }
        }
        agent.onDiagnostic={codes.add(it)}
        agent.onSnapshot={n,_,_,_->verify(n.chapters.joinToString("\n"){it.text}.replace("\n","")==text.replace("\n",""),"每批快照完整保留正文");snapshots++}
        val novel=agent.process(text.toByteArray(),"夜航.txt",ImportAgent.Options(false,true,false,false))
        verify(codes==listOf("OUTPUT_LIMIT")&&retrySize<firstSize,"输出截断自动缩小请求重试")
        verify(novel.chapters.size==40&&snapshots>2,"识别40章并分段提供阅读快照")
        var rejects=0
        val bad=ImportAgent(cfg){rejects++;response(empty().put("edits",JSONArray().put(JSONObject().put("id",0).put("old","不存在").put("new","改写"))))}
        verify(runCatching{bad.process("原文内容".toByteArray(),"bad.txt",ImportAgent.Options(false,false,false,true))}.exceptionOrNull() is ImportAgent.OutputFailure&&rejects==3,"非法修改重试有限且不应用")

        // Actual HTTP + persistent task + foreground service. Hold the last batches to inspect prefix.
        val gate=CountDownLatch(1);val count=AtomicInteger();val socket=ServerSocket(18767)
        val server=Thread{
            try{while(!socket.isClosed){val client=socket.accept();client.use{
                val input=it.getInputStream().buffered();val header=StringBuilder()
                while(!header.endsWith("\r\n\r\n")){val b=input.read();if(b<0)break;header.append(b.toChar())}
                val length=header.lines().firstOrNull{it.startsWith("Content-Length:",true)}?.substringAfter(':')?.trim()?.toInt()?:0
                val bytesIn=ByteArray(length);var got=0;while(got<length){val n=input.read(bytesIn,got,length-got);if(n<0)break;got+=n}
                val n=count.incrementAndGet();if(n>=23)gate.await(35,TimeUnit.SECONDS)
                val bytes=response(empty()).toString().toByteArray()
                it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());it.getOutputStream().write(bytes);it.getOutputStream().flush()
            }}}catch(_:Exception){}
        }.apply{isDaemon=true;start()}
        val folder=File(app.cacheDir,"分章夜航").apply{mkdirs()}
        val parts=(1..30).map{File(folder,"第${it}章 夜航.txt").apply{writeText("这是第${it}章。\n"+"夜色落在江面上，远处的灯火随着水波轻轻摇晃。林舟收起雨伞，沿着青石路走向书店。\n\n".repeat(15))}}
        val packed=ScanCatalog.pack(ScanCatalog.Entry(folder,parts,true))
        val original=ScanCatalog.unpack(packed);val book=app.library.import(owner,original,packed,"夜航.tgtxt")
        try{
            ui{app.prefs.edit().putString("ai_base:$owner",cfg.base).putString("ai_model:$owner",cfg.model).apply();app.secrets.set("ai_key:$owner",cfg.key);ImportTasks.start(app,book,ImportAgent.Options(false,false,false,true),fresh=true);app.showImportJob(book.id)}
            waitFor("没有首20章进度"){ImportTasks.state(app,owner,book.id)?.optInt("ready",0)!!>=20}
            val live=app.library.get(owner,book.id)!!
            verify(live.chapters.size==30&&live.chapters.none{it==ImportTasks.waiting},"首20章准备后仍可阅读全部30章")
            verify(ImportTasks.isRunning()&&count.get()<31,"完成全书前任务保持后台运行")
            verify(app.getSystemService(android.app.ActivityManager::class.java).getRunningServices(30).any{it.service.className.endsWith("ImportService")&&it.foreground},"整理任务拥有前台服务")
            waitFor("界面进度未刷新"){root().filterIsInstance<ProgressBar>().firstOrNull()?.progress?:0>0}
            shot("70-organize-progress")
            ui{app.openBook(live)};waitFor("无法打开正文"){app.reader?.book?.id==book.id}
            val visible=app.reader!!.book.chapters.toList()
            test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);Thread.sleep(350);gate.countDown()
            waitFor("整理未完成"){ImportTasks.state(app,owner,book.id)?.optString("status")=="complete"}
            ui{app.startActivity(android.content.Intent(app,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))}
            verify(app.reader!!.book.chapters==visible,"后台整理不会突然替换眼前正文")
            ui{app.reader!!.stop();app.reader=null;app.showShelf()}
            verify(ImportTasks.state(app,owner,book.id)!!.optInt("percent")==100,"完成进度100%")
            verify(app.library.get(owner,book.id)!!.chapters.size==30,"离开阅读后应用完整整理稿")
            ImportTasks.restore(app,book.id,true)
            verify(app.library.get(owner,book.id)!!.chapters==original.chapters,"恢复首次导入原文")
            verify(ImportTasks.state(app,owner,book.id)!!.optString("status")=="restored","恢复状态不会显示整理中")
        }finally{gate.countDown();socket.close();server.join(1000)}

        ui{app.openBook(app.library.get(owner,book.id)!!)};waitFor("阅读页未打开"){app.reader!=null}
        waitFor("阅读正文未完成排版"){root().filterIsInstance<ReadingView>().firstOrNull()?.let{it.width>0&&it.boundary().offset>it.position().offset}==true}
        invoke("showMenu");click("添加书签")
        verify(app.reader!!.book.notes.count{it.type=="书签"}==1,"添加书签持久化")
        waitFor("书签丝带未更新"){reading().pageBookmarks().size==1};verify(reading().pageBookmarks().size==1,"当前页可显示书签丝带");shot("71-bookmark-ribbon")
        invoke("bookmarks");verify(views(dialog().window!!.decorView).filterIsInstance<TextView>().any{it.text.contains("第1章")},"书签列表含章节与摘录");shot("72-bookmark-list");close()
        invoke("showMenu");click("移除书签");verify(app.reader!!.book.notes.none{it.type=="书签"},"重复操作移除而非重复新增")
        invoke("settings")
        ui{val items=views(dialog().window!!.decorView);val toggle=items.filterIsInstance<Switch>().first{it.text=="跟随系统"};toggle.isChecked=true;val slider=items.filterIsInstance<SeekBar>().first{it.contentDescription=="亮度"};verify(!slider.isEnabled,"跟随系统禁用手动亮度");toggle.isChecked=false;verify(slider.isEnabled,"关闭跟随恢复手动亮度");items.filterIsInstance<SeekBar>().first{it.contentDescription=="翻页灵敏度"}.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,android.os.Bundle().apply{putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,85f)})}
        verify(app.getSharedPreferences("reading:$owner",0).getFloat("swipe_sensitivity",0f)==.85f,"翻页灵敏度保存")
        shot("73-reading-settings");close()
        ui{val b=app.reader!!.book;b.notes=b.notes+Note(chapter=0,start=0,end=7,quote="这是第1章。",body="雨夜与书店，是故事展开的两个线索。",type="札记");b.chronicles=b.chronicles+Chronicle(kind="线索",title="雨夜里的书店",content="林舟收起雨伞，沿着青石路走向书店。",chapter=0,offset=0,revision=b.revision,manual=true);app.reader!!.save()}
        ui{app.getSharedPreferences("reading:$owner",0).edit().putBoolean("toc_drawer",true).apply();app.reader!!.showCollection("目录")};shot("74-directory-drawer")
        ui{app.reader!!.showCollection("札记")};verify(dialog().window!!.attributes.gravity and android.view.Gravity.LEFT!=0,"札记继承侧栏");shot("75-notes-drawer")
        ui{app.reader!!.showCollection("书志")};verify(dialog().window!!.attributes.width<app.windowManager.currentWindowMetrics.bounds.width(),"书志继承侧栏宽度");shot("76-chronicle-drawer");close()
        invoke("showMenu");click("说书人")
        ui{views(dialog().window!!.decorView).filterIsInstance<EditText>().single().setText("这段对话草稿需要保留")};click("温故");shot("77-chat-selected")
        ui{views(dialog().window!!.decorView).first{it.contentDescription=="历史对话"}.performClick()};close()
        verify(views(dialog().window!!.decorView).filterIsInstance<EditText>().single().text.toString()=="这段对话草稿需要保留","关闭历史返回对话并保留草稿")
        close()
        repeat(2){invoke("showMenu");Thread.sleep(500);close()}

        val scanRoot=File(Environment.getExternalStorageDirectory(),"Download/QuarkDownloads/导入0110测试")
        val scanParts=(1..1200).map{File(scanRoot,"长夜行/第${it}章 夜航.txt").apply{parentFile!!.mkdirs();writeText("第${it}章正文")}}
        val singles=(1..220).map{File(scanRoot,"独立小说/江上故事${it}.txt").apply{parentFile!!.mkdirs();writeText("独立小说正文$it")}}
        ui{app.reader!!.stop();app.reader=null;app.prefs.edit().putString("scan_mode:$owner:${File(scanRoot,"独立小说").absolutePath}","separate").apply();val body=app.surface("导入小说",fixed=true);ScanScreen(app,body,scanParts+singles,{true}).show()}
        waitFor("扫描列表未完成"){root().filterIsInstance<ListView>().firstOrNull()?.count==221}
        fun search(value:String){ui{root().filterIsInstance<EditText>().first{it.contentDescription=="搜索本地小说"}.setText(value)}}
        search("长夜行");verify(root().filterIsInstance<ListView>().single().count==1,"1200个TXT折叠一张文件夹卡")
        ui{root().filterIsInstance<CheckBox>().single().performClick()};windowClick("按多个文件分别导入（默认全选）")
        verify(root().filterIsInstance<ListView>().single().count==1201,"选择分别导入自动展开")
        verify(root().filterIsInstance<Button>().any{it.text=="导入所选（1200）"},"分别导入默认全选")
        ui{root().filterIsInstance<CheckBox>().first{it.contentDescription=="选择 第1章 夜航"}.performClick()}
        verify(root().filterIsInstance<Button>().any{it.text=="导入所选（1199）"},"子文件可独立取消且父卡显示部分选择")
        ui{verify(root().filterIsInstance<CheckBox>().filter{it.isChecked}.all{it.buttonDrawable?.state?.contains(android.R.attr.state_checked)==true},"复用条目的选中绘制状态同步")}
        shot("78-folder-expanded")
        ui{root().filterIsInstance<TextView>().first{it.text=="长夜行"}.let{(it.parent as View).performClick()}}
        verify(root().filterIsInstance<ListView>().single().count==1,"再次点击收起文件夹")
        ui{root().filterIsInstance<CheckBox>().single().performClick()};windowClick("按整本小说导入")
        verify(root().filterIsInstance<Button>().any{it.text=="导入所选（1）"},"整本模式清除子文件选择，避免重复导入")
        search("江上故事");verify(root().filterIsInstance<ListView>().single().count==220,"搜索独立文件")
        var bottom=0;ui{val r=Rect();root().filterIsInstance<Button>().first{it.text=="导入所选（1）"}.getGlobalVisibleRect(r);bottom=r.bottom;root().filterIsInstance<ListView>().single().setSelection(219)}
        ui{val r=Rect();val list=root().filterIsInstance<ListView>().single();root().filterIsInstance<Button>().first{it.text=="导入所选（1）"}.getGlobalVisibleRect(r);verify(r.bottom==bottom&&list.childCount<30&&list.isVerticalScrollBarEnabled,"长列表滚动保留底栏、回收条目和滚动条")};shot("79-search-fixed-footer")
        ui{app.readImport(android.net.Uri.EMPTY,sourceName="导入选项.txt",suppliedBytes="新的未导入小说正文".toByteArray())}
        waitFor("初次导入选项未展示"){root().filterIsInstance<CheckBox>().size==4}
        verify(root().filterIsInstance<CheckBox>().map{it.text.toString()}.containsAll(listOf("章节与目录识别","乱码纠正","格式优化","标题补全")),"首次导入可独立勾选四个项目")
        shot("80-import-options")
        ui{app.showImportJob(book.id)};shot("81-restore-original")
        val provided=File("/data/local/tmp/lemon0110-sample.txt")
        if(provided.isFile){
            val bytes=provided.readBytes();val sourceName="007系列小说＿谍海恋情_再痕中文.txt"
            val raw=ImportAgent(AiConfig("","","")).process(bytes,sourceName,ImportAgent.Options(false,false,false,false))
            verify(raw.encoding=="GB18030"&&raw.chapters.single().text.contains("六月的傍晚景色迷人"),"用户实际TXT编码与正文解码")
            val before=app.library.all(owner,false).size;val sample=app.library.import(owner,raw,bytes,sourceName)
            app.library.import(owner,raw,bytes,"改名后的同一本小说.txt")
            verify(app.library.all(owner,false).size==before+1,"用户TXT改名后重复导入仍去重")
            // Fixture boundaries validate data integrity, not the quality of a live model.
            val fixture=ImportAgent(cfg){request->val input=JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"));val fragments=input.getJSONArray("fragments");val headings=JSONArray()
                for(i in 0 until fragments.length()){val f=fragments.getJSONObject(i);val line=f.getString("text").trim();if(line.startsWith("第一卷 第")&&line.contains("章   字数:"))headings.put(JSONObject().put("id",f.getInt("id")).put("title",line.substringBefore("   字数:")))}
                response(empty().put("chapters",headings))
            }
            var completeTail=true
            fixture.onSnapshot={snapshot,_,_,_->if(snapshot.chapters.joinToString(""){it.text}.filterNot{it.isWhitespace()}!=raw.chapters.single().text.filterNot{it.isWhitespace()})completeTail=false}
            val organized=fixture.process(bytes,sourceName,ImportAgent.Options(false,true,false,false))
            verify(organized.chapters.size==29&&completeTail,"用户TXT的28章和前置信息跨批完整保留")
            app.library.import(owner,organized,bytes,sourceName,sample);ImportTasks.restore(app,sample.id,true)
            verify(app.library.get(owner,sample.id)!!.chapters==raw.chapters,"用户TXT整理后可恢复原文")
            val restored=app.library.get(owner,sample.id)!!;restored.position=Position(0,restored.chapters[0].text.indexOf("六月的傍晚景色迷人"));app.library.save(owner,restored)
            ui{app.openBook(restored)};waitFor("用户TXT无法打开"){app.reader?.book?.id==sample.id};verify(app.reader!!.book.chapters[0].text.length>120000,"用户TXT实际打开阅读")
            shot("82-user-txt-reading")
        }
        return checks
    }
}
