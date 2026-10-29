package cn.jiangcheng.lemon

import android.app.Instrumentation
import android.app.Dialog
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.text.Spanned
import cn.jiangcheng.lemon.reader.*
import cn.jiangcheng.lemon.data.*
import org.json.*
import java.io.File
import java.net.ServerSocket

object Revision0117Test {
    fun run(test:Instrumentation,app:MainActivity):String {
        check(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"))
        var count=0
        fun verify(ok:Boolean,message:String){check(ok){message};count++;println("CHECK $count $message")}
        fun ui(block:()->Unit){test.runOnMainSync(block);test.waitForIdleSync()}
        fun waitFor(block:()->Boolean){val end=System.currentTimeMillis()+20000;while(!block()&&System.currentTimeMillis()<end)Thread.sleep(70);check(block()){"等待超时"}}
        fun tree(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{tree(v.getChildAt(it))}else emptyList()
        val owner="revision0117";File(app.filesDir,"qa").mkdirs()
        val server=ServerSocket(0);val requests=mutableListOf<JSONObject>();var serverError:Throwable?=null
        val answer="## 夜航的伏笔\n\n**灯火**连接了人物与场景。\n\n- 雨伞暗示刚刚结束的旅程。\n- 书店是故事的新起点。\n\n> 故事才刚刚开始。\n\n这只是根据已读原文作出的推测。"
        val thread=Thread{
            try{repeat(2){server.accept().use{socket->
                val reader=socket.getInputStream().bufferedReader();var size=0
                while(true){val line=reader.readLine();if(line.isEmpty())break;if(line.startsWith("Content-Length:",true))size=line.substringAfter(':').trim().toInt()}
                // Content-Length counts bytes, decode body independently of Reader buffering.
                val body=StringBuilder();while(body.toString().toByteArray().size<size){val c=reader.read();if(c<0)break;body.append(c.toChar())};synchronized(requests){requests.add(JSONObject(body.toString()))}
                val out=socket.getOutputStream();out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray());out.flush()
                answer.chunked(12).forEach{chunk->val event=JSONObject().put("choices",JSONArray().put(JSONObject().put("delta",JSONObject().put("content",chunk))));out.write("data: $event\n\n".toByteArray());out.flush();Thread.sleep(100)}
                out.write("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: {\"choices\":[],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":80,\"total_tokens\":200}}\n\ndata: [DONE]\n\n".toByteArray());out.flush()
            }}}catch(e:Throwable){serverError=e}
        }.apply{isDaemon=true;start()}
        ui{app.profileId=owner;app.prefs.edit().putBoolean("auto_chronicles:$owner",false).putBoolean("ai_directory:$owner",false).putString("models:$owner",JSONArray().put(JSONObject().put("id","test").put("name","Qwen").put("provider","qwen").put("base","http://127.0.0.1:${server.localPort}").put("models","qwen3.7-flash")).toString()).apply();app.secrets.set("model-key:$owner:test","local-fixture");app.getSharedPreferences("reading:$owner",0).edit().putBoolean("panels_full",true).apply()}
        val bytes=("第一章 夜航\n夜色落在江面上，远处的灯火随水波轻轻摇晃。林舟收起雨伞，沿着青石路走向书店。故事才刚刚开始。\n第二章 天明\n新的旅程开始。").toByteArray()
        var book=app.library.import(owner,LocalPrepare.read(bytes,"夜航.txt",ImportAgent.Options()),bytes,"夜航.txt",app.library.duplicate(owner,bytes));book.chats=JSONArray();app.library.save(owner,book)
        ui{app.openBook(book)};waitFor{app.reader?.book?.id==book.id};val screen=app.reader!!;book=screen.book
        fun dialog()=ReaderScreen::class.java.getDeclaredField("menu").apply{isAccessible=true}.get(screen) as Dialog
        fun nodes()=tree(dialog().window!!.decorView)
        fun click(text:String){ui{(nodes().firstOrNull{(it as? TextView)?.text?.toString()==text||it.contentDescription==text}?:error("找不到 $text")).performClick()}}
        fun shot(name:String){Thread.sleep(400);val b=test.uiAutomation.takeScreenshot();File(app.filesDir,"qa/117-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        try{
            ui{ReaderScreen::class.java.getDeclaredMethod("chat",Position::class.java,String::class.java,String::class.java).apply{isAccessible=true}.invoke(screen,Position(0,book.chapters[0].text.length),"","林舟收起雨伞")}
            val original=dialog();verify(nodes().filterIsInstance<EditText>().single().text.isBlank(),"引用不填入输入框")
            verify(nodes().filterIsInstance<TextView>().any{it.text.contains("引用原文 · 林舟收起雨伞")},"独立引用条")
            shot("quote")
            click("qwen3.7-flash");verify(dialog()!=original&&nodes().filterIsInstance<TextView>().any{it.text.toString()=="✓ Qwen · qwen3.7-flash"},"单模型原地列表")
            shot("models");click("✓ Qwen · qwen3.7-flash");verify(dialog()===original&&app.reader===screen,"模型选择回到同一阅读面板")
            click("发送")
            waitFor{nodes().filterIsInstance<TextView>().any{it.text.contains("夜航的伏笔")&&!it.text.contains("这只是")}}
            verify(book.chats.length()==0,"完成前已有增量可见")
            waitFor{book.chats.length()==1};verify(book.chats.getJSONObject(0).getString("quote")=="林舟收起雨伞"&&book.chats.getJSONObject(0).getString("question")=="解读这段原文","引用和问题分别持久化")
            val rendered=nodes().filterIsInstance<TextView>().first{it.text.contains("夜航的伏笔")};verify(rendered.text is Spanned&&!rendered.text.contains("**")&&!rendered.text.contains("##"),"Markdown 原生富文本")
            shot("answer")
            ui{nodes().filterIsInstance<EditText>().single().setText("再谈谈雨伞")};click("发送");waitFor{book.chats.length()==2}
            verify(book.chats.getJSONObject(0).getString("conversationId")==book.chats.getJSONObject(1).getString("conversationId"),"连续提问同一会话")
            verify(requests[1].toString().contains("解读这段原文"),"同一会话携带上下文")
            click("新对话");verify(nodes().filterIsInstance<TextView>().none{it.text.contains("夜航的伏笔")},"新对话清空当前展示")
            verify(book.chats.length()==2,"新对话保留历史")
            click("历史对话");verify(nodes().filterIsInstance<TextView>().any{it.text.contains("2 轮")},"历史按会话聚合")
            verify(nodes().filterIsInstance<TextView>().none{it.text.length>160},"列表只有标题简介")
            shot("history")
            ui{val t=nodes().filterIsInstance<TextView>().first{it.text.toString()=="解读这段原文"};(t.parent as View).performClick()}
            verify(nodes().filterIsInstance<TextView>().count{it.text.contains("夜航的伏笔")&&it.text is Spanned}==2,"历史详情渲染所有轮次")
            shot("detail");click("返回上一级");verify(nodes().filterIsInstance<TextView>().any{it.text.toString()=="历史对话"},"详情关闭返回历史")
            click("返回上一级");verify(dialog()===original,"历史关闭返回聊天")
            verify(app.getSharedPreferences("ai-usage:$owner",0).getLong("total",0)>=400,"流式 usage 计入用量")
            val updates=mutableListOf<String>();val fixture="data: {\"choices\":[{\"delta\":{\"content\":\"甲\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"乙\"}}]}\n\ndata: [DONE]\n\n"
            val parsed=ChatStream.read(fixture.reader().buffered()){updates.add(it)}
            verify(updates==listOf("甲","甲乙"),"SSE 增量拼接")
            verify(runCatching{ChatStream.read("data: {\"choices\":[{\"delta\":{\"content\":\"未完成\"}}]}\n\n".reader().buffered()){} }.isFailure,"断流不伪装成功")
            val spoilerId="spoiler-test"
            repeat(2){book.chats.put(JSONObject().put("conversationId",spoilerId).put("question",if(it==0)"结局中的秘密"else"再说一点").put("answer","**后文回答**，只在确认后查看。").put("mode","天机").put("chapter",1).put("offset",1).put("revision",book.revision).put("time",System.currentTimeMillis()+it))}
            click("历史对话");verify(nodes().filterIsInstance<TextView>().none{it.text.contains("结局中的秘密")},"普通历史不泄漏剧透会话")
            click("查看剧透记录")
            val confirm=test.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("查看").first{it.text?.toString()=="查看"}
            confirm.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);test.waitForIdleSync()
            verify(nodes().filterIsInstance<TextView>().any{it.text.contains("2 轮")},"剧透记录按会话聚合")
            shot("spoilers")
            ui{val t=nodes().filterIsInstance<TextView>().first{it.text.toString()=="结局中的秘密"};(t.parent as View).performClick()}
            verify(nodes().filterIsInstance<TextView>().count{it.text.toString()=="后文回答，只在确认后查看。"&&it.text is Spanned}==2,"剧透详情 Markdown 渲染")
            click("继续此对话");verify(dialog()===original,"继续剧透会话关闭两级记录面板并回到聊天")
            book.summaries.put("${book.revision}:0","## 本章总结\n\n**林舟**来到书店。")
            ui{ReaderScreen::class.java.getDeclaredMethod("showSummary",Int::class.javaPrimitiveType).apply{isAccessible=true}.invoke(screen,0)}
            verify(nodes().filterIsInstance<TextView>().any{it.text.contains("林舟来到书店")&&it.text is Spanned},"缓存章节总结 Markdown 渲染")
            val characterNames=listOf("林舟","苏晴","陈青","顾宁","方远","赵平","周安")
            val peopleBytes=("第一章 初见\n"+characterNames.joinToString("。") {"$it 来到书店"}+"。\n第二章 客人\n叶青推门走进来。余下内容尚未阅读。\n第三章 后文\n未读人物不应出现。").toByteArray()
            var peopleBook=app.library.import(owner,LocalPrepare.read(peopleBytes,"人物测试.txt",ImportAgent.Options()),peopleBytes,"人物测试.txt",app.library.duplicate(owner,peopleBytes))
            peopleBook.chronicles=emptyList();app.library.save(owner,peopleBook);app.getSharedPreferences("chronicle-progress:$owner",0).edit().clear().commit()
            ui{dialog().dismiss();app.openBook(peopleBook)};waitFor{app.reader?.book?.id==peopleBook.id};peopleBook=app.reader!!.book
            var autoCalls=0
            val extractCall:(JSONObject)->JSONObject={request->
                autoCalls++;val data=JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"));val text=data.getString("newText")
                check(!text.contains("余下内容")&&!text.contains("未读人物")){"自动人物不能读取后文"}
                val entries=JSONArray();(characterNames+"叶青").filter{text.contains(it)}.forEach{entries.put(JSONObject().put("kind","人物").put("title",it).put("content","**新出场人物**").put("evidence",it))}
                JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","stop").put("message",JSONObject().put("content",JSONObject().put("entries",entries).toString()))))
            }
            ui{app.prefs.edit().putBoolean("auto_chronicles:$owner",true).apply();ChronicleTasks.maybe(app,peopleBook,Position(1,2),true,extractCall)}
            waitFor{peopleBook.chronicles.size==8}
            verify(autoCalls==2&&peopleBook.chronicles.count{it.chapter==0}==7,"首次从已读前章补齐且短章节超过6个人物均记录")
            verify(peopleBook.chronicles.single{it.title=="叶青"}.offset==2,"记录当前已读片段且不读后文")
            ui{ChronicleTasks.maybe(app,peopleBook,Position(1,2),true,extractCall)};Thread.sleep(500)
            verify(autoCalls==2,"已处理片段不重复调用")
            ui{app.prefs.edit().putBoolean("auto_chronicles:$owner",false).apply();app.reader!!.showCollection("书志")}
            fun currentNodes():List<View>{val d=ReaderScreen::class.java.getDeclaredField("menu").apply{isAccessible=true}.get(app.reader) as Dialog;return tree(d.window!!.decorView)}
            verify(currentNodes().filterIsInstance<TextView>().any{it.text.toString()=="新出场人物"&&it.text is Spanned},"人物书志内容 Markdown 渲染")
            Thread.sleep(350);val peopleShot=test.uiAutomation.takeScreenshot();File(app.filesDir,"qa/117-people.png").outputStream().use{peopleShot.compress(Bitmap.CompressFormat.PNG,100,it)};peopleShot.recycle()
            val peopleScreen=app.reader!!;val reading=ReaderScreen::class.java.getDeclaredField("view").apply{isAccessible=true}.get(peopleScreen) as ReadingView
            var resultDialog:Dialog?=null;var resultBody:LinearLayout?=null
            val hits=listOf(ResearchAgent.Hit(0,0,"林舟","林舟","**书店主人**","人物"),ResearchAgent.Hit(1,0,"叶青","叶青","**新来的客人**","人物"))
            ui{
                reading.go(Position(0,0))
                val panel=ReaderScreen::class.java.getDeclaredMethod("panel",String::class.java,Float::class.javaPrimitiveType).apply{isAccessible=true}.invoke(peopleScreen,"司卷",.8f) as Pair<*,*>
                resultDialog=panel.first as Dialog;resultBody=panel.second as LinearLayout
                ReaderScreen::class.java.getDeclaredMethod("renderResearchHits",LinearLayout::class.java,List::class.java,List::class.java,Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,Dialog::class.java).apply{isAccessible=true}.invoke(peopleScreen,resultBody,hits,peopleBook.chapters,peopleBook.revision,true,resultDialog)
            }
            fun resultNodes()=tree(resultDialog!!.window!!.decorView)
            verify(resultNodes().filterIsInstance<TextView>().any{it.text.toString()=="书店主人"&&it.text is Spanned},"司卷结果 Markdown 渲染")
            ui{resultNodes().filterIsInstance<TextView>().first{it.text.toString()=="全部加入书志（2）"}.performClick()}
            verify(peopleBook.chronicles.count{it.title=="林舟"}==1&&peopleBook.chronicles.single{it.title=="林舟"}.manual,"批量加入更新自动人物且不重复")
            val beforePosition=peopleBook.position.copy();val beforeCount=resultNodes().size
            ui{resultNodes().filterIsInstance<TextView>().first{it.text.toString()=="第2章 · 定位原文"}.performClick()}
            verify(reading.locatedText?.start==0&&reading.locatedText?.end==2&&reading.locatedText?.chapter==1,"原文定位精确高亮证据范围")
            verify(peopleBook.position==beforePosition,"临时原文查看保持阅读进度")
            Thread.sleep(2200);val locationShot=test.uiAutomation.takeScreenshot();File(app.filesDir,"qa/117-locate.png").outputStream().use{locationShot.compress(Bitmap.CompressFormat.PNG,100,it)};locationShot.recycle()
            ui{verify(peopleScreen.returnFromSource(),"返回原文查看入口")}
            verify(resultDialog!!.isShowing&&resultNodes().size==beforeCount&&reading.locatedText==null,"返回司卷完整保留结果和操作状态")
            Thread.sleep(300);val resultShot=test.uiAutomation.takeScreenshot();File(app.filesDir,"qa/117-research.png").outputStream().use{resultShot.compress(Bitmap.CompressFormat.PNG,100,it)};resultShot.recycle()
            ui{ReaderScreen::class.java.getDeclaredMethod("search",String::class.java).apply{isAccessible=true}.invoke(peopleScreen,"叶青")}
            val searchDialog=ReaderScreen::class.java.getDeclaredField("menu").apply{isAccessible=true}.get(peopleScreen) as Dialog
            waitFor{tree(searchDialog.window!!.decorView).filterIsInstance<TextView>().any{it.text.toString()=="第2章 · 定位原文"}}
            ui{tree(searchDialog.window!!.decorView).filterIsInstance<TextView>().first{it.text.toString()=="第2章 · 定位原文"}.performClick()}
            verify(reading.locatedText?.end==2,"精准搜索标记关键词")
            ui{peopleScreen.returnFromSource()};verify(searchDialog.isShowing,"精准搜索可返回原结果")
            ui{searchDialog.dismiss()};ui{peopleScreen.book.notes=listOf(Note(chapter=0,start=0,end=2,quote="林舟",body="对书店的第一印象。",type="札记"));peopleScreen.showCollection("札记")}
            val noteAction=currentNodes().filterIsInstance<ImageButton>().single{it.contentDescription=="札记操作"}
            verify(noteAction.width==app.dp(40)&&noteAction.height==app.dp(40),"札记使用小图标和独立40dp触控区域")
            Thread.sleep(350);val noteShot=test.uiAutomation.takeScreenshot();File(app.filesDir,"qa/117-notes.png").outputStream().use{noteShot.compress(Bitmap.CompressFormat.PNG,100,it)};noteShot.recycle()
            ui{peopleScreen.showCollection("书志")}
            verify(currentNodes().filterIsInstance<ImageButton>().count{it.contentDescription=="书志操作"}==7,"所有可见人物卡片使用右上角三点图标")
            Thread.sleep(350);val finalPeopleShot=test.uiAutomation.takeScreenshot();File(app.filesDir,"qa/117-people.png").outputStream().use{finalPeopleShot.compress(Bitmap.CompressFormat.PNG,100,it)};finalPeopleShot.recycle()
            // Exercise the actual search input and preserved parent navigation.
            ui{reading.go(Position(0,0));ReaderScreen::class.java.getDeclaredMethod("searchMenu").apply{isAccessible=true}.invoke(peopleScreen)}
            val form=ReaderScreen::class.java.getDeclaredField("menu").apply{isAccessible=true}.get(peopleScreen) as Dialog
            ui{tree(form.window!!.decorView).filterIsInstance<EditText>().single().setText("  林舟  ");tree(form.window!!.decorView).filterIsInstance<TextView>().first{it.text.toString()=="开始搜索"}.performClick()}
            waitFor{currentNodes().filterIsInstance<TextView>().any{it.text.toString().contains("找到 1 处")}}
            verify(currentNodes().filterIsInstance<ListView>().single().adapter.count==1,"精准搜索实际输入流程按出现位置生成卡片")
            Thread.sleep(300);val searchShot=test.uiAutomation.takeScreenshot();File(app.filesDir,"qa/117-search.png").outputStream().use{searchShot.compress(Bitmap.CompressFormat.PNG,100,it)};searchShot.recycle()
            ui{currentNodes().first{it.contentDescription=="返回上一级"}.performClick()}
            verify(form.isShowing&&tree(form.window!!.decorView).filterIsInstance<EditText>().single().text.toString()=="  林舟  ","搜索结果上一级保留搜索词")
            ui{currentNodes().first{it.contentDescription=="返回阅读"}.performClick()}
            Thread.sleep(200);verify(ReaderScreen::class.java.getDeclaredField("menu").apply{isAccessible=true}.get(peopleScreen)==null,"返回阅读关闭整组面板")
            val deathText="林舟说：我不会死。他关上了门。";val deathText2="翌日，林舟身受重伤，终于咽气了。"
            val deathAgent=ResearchAgent(AiConfig("fixture","fixture","fixture")){request->
                val source=JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content")).getString("source")
                JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","stop").put("message",JSONObject().put("content",JSONObject().put("matches",JSONArray().put(JSONObject().put("answersQuestion",source.contains("咽气")).put("evidence",source).put("title","林舟之死").put("reason","直接事件依据"))).toString()))))
            }
            val deathHits=deathAgent.search(listOf(Chapter("一",deathText),Chapter("二",deathText2)),Position(1,deathText2.length),"林舟死的时候的章节")
            verify(deathHits.size==1&&deathHits.single().chapter==1,"事件检索不填充无关依据并保留直接死亡证据")
            val shelfOwner="shelf0117v18"
            ui{peopleScreen.stop();app.reader=null;app.profileId=shelfOwner;app.prefs.edit().putBoolean("shelf-list:$shelfOwner",false).apply()}
            val shelfBooks=listOf("夜航","山海来信","长安旧事","风起青萍","竹间集","月下行舟").mapIndexed{i,title->Book(id="shelf-117-$i",title=title,chapters=listOf(Chapter("第一章",title+"正文")),folder=if(i<2)"闲时再读"else "",folderUpdated=100L).also{app.library.save(shelfOwner,it)}}
            val merged=shelfBooks[0].copy(folder="古典",folderUpdated=200L)
            verify(merged.folder=="古典"&&Library.fromJson(Library.toJson(merged)).folderUpdated==200L,"文件夹归属可持久化")
            ui{app.showShelf(folder="")}
            fun shelfNodes()=tree(app.window.decorView)
            verify(shelfNodes().count{it.contentDescription?.toString()?.startsWith("书籍：")==true}==4&&shelfNodes().any{it.contentDescription=="文件夹：闲时再读"},"书架文件夹占一个位置并收纳两本书")
            verify(shelfNodes().filterIsInstance<TextView>().none{Regex("整理完成|整理待重试|整理中|整理.*%").containsMatchIn(it.text)},"书架不显示任何整理状态")
            Thread.sleep(400);val shelfShot=test.uiAutomation.takeScreenshot();File(app.filesDir,"qa/117-shelf.png").outputStream().use{shelfShot.compress(Bitmap.CompressFormat.PNG,100,it)};shelfShot.recycle()
            ui{shelfNodes().first{it.contentDescription=="文件夹：闲时再读"}.performClick()}
            verify(shelfNodes().count{it.contentDescription?.toString()?.startsWith("书籍：")==true}==2,"点击文件夹展示内部书架")
            Thread.sleep(350);val folderShot=test.uiAutomation.takeScreenshot();File(app.filesDir,"qa/117-folder.png").outputStream().use{folderShot.compress(Bitmap.CompressFormat.PNG,100,it)};folderShot.recycle()
            ui{shelfNodes().first{it.contentDescription=="上一级书架"}.performClick();shelfNodes().filterIsInstance<TextView>().first{it.text.toString()=="列表"}.performClick()}
            verify(app.prefs.getBoolean("shelf-list:$shelfOwner",false)&&shelfNodes().filterIsInstance<TextView>().any{it.text.toString()=="网格"},"书架列表切换记住设置")
            Thread.sleep(350);val listShot=test.uiAutomation.takeScreenshot();File(app.filesDir,"qa/117-shelf-list.png").outputStream().use{listShot.compress(Bitmap.CompressFormat.PNG,100,it)};listShot.recycle()
            ui{app.showShelf()};verify(shelfNodes().filterIsInstance<TextView>().any{it.text.toString()=="网格"},"再次打开书架保持列表模式")
            serverError?.let{throw it}
        }finally{server.close();app.secrets.remove("model-key:$owner:test")}
        val secret=File(app.filesDir,"qwen-test-key")
        if(secret.isFile){try{
            val chunks=mutableListOf<String>();val started=System.currentTimeMillis();var first=0L
            val cfg=AiConfig("https://dashscope.aliyuncs.com/compatible-mode/v1","qwen3.7-flash",secret.readText().trim(),context=app,owner=owner,task="说书人流式验证",budget=5000)
            val result=Storyteller.answer(cfg,book.chapters,Position(0,book.chapters[0].text.length),"用一句不超过60字的话解读这段原文，重点用 Markdown 加粗。","伴读","简洁",quote="林舟收起雨伞",onText={if(first==0L)first=System.currentTimeMillis();chunks.add(it)})
            verify(chunks.size>1&&chunks.last()==result,"真实 Qwen 多段流式回答")
            val people=ChronicleTasks.extract(cfg.copy(session=java.util.UUID.randomUUID().toString(),task="新人物提取验证"),0,0,"林舟是这家书店的主人。苏晴推门进来，自我介绍是一名记者。陈青是苏晴的同事，他正在门口等候。",1)
            verify(people.map{it.title}.toSet()==setOf("林舟","苏晴","陈青"),"真实 Qwen 记录三个新人物并核对原文证据")
            File(app.filesDir,"qa/117-live.json").writeText(JSONObject().put("chunks",chunks.size).put("firstMs",first-started).put("totalMs",System.currentTimeMillis()-started).put("answer",result).put("usage",JSONArray(app.getSharedPreferences("ai-usage:$owner",0).getString("entries","[]"))).toString())
        }finally{secret.delete()}}
        return "$count storyteller checks passed"
    }
}
