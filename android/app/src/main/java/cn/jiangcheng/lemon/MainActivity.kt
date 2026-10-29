package cn.jiangcheng.lemon

import android.app.*
import cn.jiangcheng.lemon.ui.ReaderDialog as AlertDialog
import cn.jiangcheng.lemon.ui.Appearance
import android.os.*
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import cn.jiangcheng.lemon.data.*
import cn.jiangcheng.lemon.reader.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

open class MainActivity : Activity() {
    lateinit var library: Library
    lateinit var secrets: Secrets
    val worker = Executors.newFixedThreadPool(3)
    val prefs by lazy { getSharedPreferences("app",MODE_PRIVATE) }
    var profileId = ""
    var reader: ReaderScreen? = null
    var pickCallback: ((Uri) -> Unit)? = null
    private var updateBook: Book? = null
    private var importAgent: ImportAgent? = null
    private var screenGeneration=0
    private val sharedNovels=java.util.ArrayDeque<Uri>()
    private var activeSharedNovel:Uri?=null
    private var invalidSharedInput=false
    private val heldVolumeKeys=mutableSetOf<Int>()
    @Suppress("DEPRECATION")
    private fun receiveNovels(incoming:Intent){
        if(incoming.action !in listOf(Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE))return
        val streams=runCatching{if(incoming.action==Intent.ACTION_SEND_MULTIPLE)incoming.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM).orEmpty().filterIsInstance<Uri>()else listOfNotNull(incoming.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)}.getOrDefault(emptyList())
        val uris=(streams+listOfNotNull(incoming.clipData).flatMap{clip->(0 until clip.itemCount).mapNotNull{clip.getItemAt(it).uri}}).filter{it.scheme=="content"}.distinct().take(50)
        sharedNovels.addAll(uris)
        invalidSharedInput=uris.isEmpty()
    }
    private fun nextSharedNovel(){
        if(profileId.isBlank()||sharedNovels.isEmpty())return
        reader?.stop();reader=null
        val uri=sharedNovels.removeFirst();activeSharedNovel=uri
        readImport(uri,after={activeSharedNovel=null;nextSharedNovel()})
    }
    private fun showRejectedShare(){
        surface("导入小说",back={finish()})
        AlertDialog.Builder(this).setTitle("请选择小说文件")
            .setMessage("收到的是链接或非文件内容。请先下载小说，再分享文件导入。")
            .setPositiveButton("返回原应用",null).show().setOnDismissListener{finish()}
    }
    override fun onNewIntent(incoming:Intent){super.onNewIntent(incoming);setIntent(incoming);receiveNovels(incoming)
        if(invalidSharedInput)showRejectedShare()else nextSharedNovel()}
    override fun dispatchKeyEvent(event:KeyEvent):Boolean{
        if(event.keyCode in listOf(KeyEvent.KEYCODE_VOLUME_UP,KeyEvent.KEYCODE_VOLUME_DOWN)){
            if(event.action==KeyEvent.ACTION_UP&&heldVolumeKeys.remove(event.keyCode))return true
            if(event.action==KeyEvent.ACTION_DOWN){
                if(event.keyCode in heldVolumeKeys)return true
                if(reader?.volumeTurn(if(event.keyCode==KeyEvent.KEYCODE_VOLUME_DOWN)1 else -1)==true){heldVolumeKeys.add(event.keyCode);return true}
            }
        }
        return super.dispatchKeyEvent(event)
    }
    fun screenToken()=screenGeneration
    private var currentPage=""
    private var pageBack:(()->Unit)?=null
    private var exitPressedAt:Long?=null
    val dark get()=Appearance.dark(this)
    private val paper get()=if(dark)0xff1d1f1b.toInt()else Color.rgb(247,247,249)
    val ink get()=if(dark)0xffe6e3d8.toInt()else Color.rgb(44,43,35)
    val accent get()=if(dark)0xffc9bc94.toInt()else Color.rgb(105,89,60)
    private val cardColor get()=if(dark)0xff292c25.toInt()else Color.WHITE
    private val muted get()=if(dark)0xffaaa99f.toInt()else Color.GRAY
    private val controlColor get()=if(dark)0xff383c30.toInt()else Color.rgb(227,222,203)
    override fun onCreate(state:Bundle?) {
        setTheme(if(dark)R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(state)
        if(Build.VERSION.SDK_INT>=33)onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT){onBackPressed()}
        CrashLog.install(applicationContext)
        library=Library(this);secrets=Secrets(this)
        profileId=LocalProfile.ID
        if(state==null)receiveNovels(intent)else {invalidSharedInput=state.getBoolean("invalid_shared_input");state.getStringArrayList("shared_novels")?.forEach{sharedNovels.add(Uri.parse(it))}}
        if(invalidSharedInput){showRejectedShare();return}
        val book=state?.getString("open_book")?.let{library.get(profileId,it)}
        when {
            book!=null&&!book.localRemoved -> openBook(book)
            sharedNovels.isNotEmpty() -> showHome()
            state==null -> openConfiguredStartPage()
            else -> when(state.getString("page")){"设置"->showSettings();"书架"->showShelf();"导入与整理"->importSettings();else->showHome()}
        }
        resumeReadingTasks()
    }
    private fun homeStartPage():String=prefs.getString("home_start_page:$profileId","default") ?: "default"
    private fun homeStartLabel():String=when(homeStartPage()){"shelf"->"书架";"shimo"->"拾墨";else->"默认主页"}
    private fun openConfiguredStartPage(){
        when(homeStartPage()){
            "shelf"->showShelf(folder="")
            "shimo"->{showHome();startActivity(Intent(this,cn.jiangcheng.lemon.shimo.ShimoActivity::class.java))}
            else->showHome()
        }
    }
    fun openRecentBook(){val recent=library.all(profileId,includeText=false).firstOrNull{!it.localRemoved};if(recent!=null)openBook(recent)else showShelf()}
    override fun onSaveInstanceState(outState:Bundle){outState.putBoolean("invalid_shared_input",invalidSharedInput);reader?.save();outState.putStringArrayList("shared_novels",ArrayList((listOfNotNull(activeSharedNovel)+sharedNovels).map{it.toString()}));outState.putString("open_book",reader?.book?.id);outState.putString("page",currentPage);super.onSaveInstanceState(outState)}
    private fun recordFailure(error:Throwable)=CrashLog.write(applicationContext,error)
    fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    fun text(value:String,size:Float=16f):TextView=TextView(this).apply { text=value;textSize=size;setTextColor(ink);setPadding(0,dp(7),0,dp(7)) }
    fun column(padding:Int=22)=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(padding),dp(padding),dp(padding),dp(padding));setBackgroundColor(paper) }
    fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
    fun button(label:String, action:()->Unit)=Button(this).apply {
        text=label;isAllCaps=false;setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(muted,accent)));textSize=14f;minHeight=dp(48);minWidth=0;minimumWidth=0
        backgroundTintList=null;stateListAnimator=null;elevation=0f;setPadding(dp(12),dp(8),dp(12),dp(8))
        background=android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed),GradientDrawable().apply{setColor(if(dark)0xff484c3c.toInt()else Color.rgb(213,205,181));cornerRadius=dp(10).toFloat()})
            addState(intArrayOf(),GradientDrawable().apply{setColor(controlColor);cornerRadius=dp(10).toFloat()})
        }
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(7);bottomMargin=dp(7)}
        setOnClickListener{action()}
    }
    fun iconAction(label:String,resource:Int,action:()->Unit):Button=button(label,action).apply{
        background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x2269593c),null,GradientDrawable().apply{setColor(Color.WHITE);cornerRadius=dp(8).toFloat()})
        val image=getDrawable(resource)!!.mutate();image.setTint(accent);image.setBounds(0,0,dp(21),dp(21))
        setCompoundDrawablesRelative(image,null,null,null);compoundDrawablePadding=dp(8);textSize=15f;gravity=if(label.isBlank())Gravity.CENTER else Gravity.START or Gravity.CENTER_VERTICAL;minHeight=dp(48)
    }
    fun field(hintValue:String,password:Boolean=false)=EditText(this).apply {
        backgroundTintList=null;background=GradientDrawable().apply{setColor(if(dark)0xff30332b.toInt()else 0xffeeece3.toInt());cornerRadius=dp(10).toFloat()};minimumHeight=dp(48);hint=hintValue;textSize=16f;setTextColor(ink);setHintTextColor(muted);setSingleLine();setPadding(dp(12),dp(14),dp(12),dp(14))
        if(password) inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    fun weighted(row:LinearLayout,view:View) { row.addView(view,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f).apply{setMargins(dp(3),dp(4),dp(3),dp(4))}) }
    fun surface(title:String,subtitle:String="",back:(()->Unit)?=null,fixed:Boolean=false):LinearLayout {
        currentPage=title;pageBack=back;exitPressedAt=null
        screenGeneration++;importAgent?.cancelled=true
        reader?.stop();reader=null
        window.decorView.systemUiVisibility=if(dark)0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor=paper;window.navigationBarColor=paper
        val body=column()
        if(back!=null){
            val toolbar=row().apply{minimumHeight=dp(52)}
            toolbar.addView(iconAction("",R.drawable.ic_back,back).apply{contentDescription="返回";setPadding(dp(8),0,dp(8),0)},LinearLayout.LayoutParams(dp(44),dp(48)))
            if(title.isNotEmpty())toolbar.addView(text(title,21f).apply{typeface=Typeface.DEFAULT_BOLD;setPadding(dp(10),0,0,0)},LinearLayout.LayoutParams(0,-2,1f))
            body.addView(toolbar)
        }else if(title.isNotEmpty())body.addView(text(title,28f).apply{typeface=Typeface.DEFAULT_BOLD})
        if(subtitle.isNotEmpty()) body.addView(text(subtitle,13f).apply { alpha=.6f })
        val content:View=if(fixed)body else ScrollView(this).apply { isFillViewport=true;addView(body) }
        val safe=android.widget.FrameLayout(this).apply{setBackgroundColor(paper);addView(content,FrameLayout.LayoutParams(-1,-1))}
        safe.setOnApplyWindowInsetsListener { view,insets ->
            val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val keyboard=insets.getInsets(WindowInsets.Type.ime())
            view.setPadding(bars.left,bars.top,bars.right,maxOf(bars.bottom,keyboard.bottom));insets
        }
        setContentView(safe);safe.requestApplyInsets()
        return body
    }
    fun message(title:String,body:String) { if(!isFinishing) AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("知道了",null).show() }
    fun async(status:TextView?=null, work:()->Any?, failureEvent:CrashLog.Event?=null, done:(Any?)->Unit={}) {
        status?.text="正在处理…"
        worker.execute {
            try { val result=work();runOnUiThread { if(!isDestroyed){status?.text="";done(result)} } }
            catch(e:Exception) { if(failureEvent!=null)CrashLog.event(applicationContext,failureEvent,error=e) else if(e is android.database.SQLException)recordFailure(e);runOnUiThread { if(!isDestroyed){if(status!=null)status.text=e.message ?: "操作暂时失败" else message("暂时无法完成",e.message ?: "请稍后重试")} } }
        }
    }
    private fun labelled(body:LinearLayout,label:String,input:EditText):TextView {
        body.addView(text(label,14f).apply{setPadding(0,dp(12),0,dp(3))})
        input.contentDescription=label
        input.background=GradientDrawable().apply{setColor(cardColor);cornerRadius=dp(10).toFloat();setStroke(dp(1),if(dark)0xff42463a.toInt()else Color.rgb(223,224,228))}
        body.addView(input)
        return text("",12f).apply{setTextColor(if(dark)0xffe6a095.toInt()else Color.rgb(171,55,48));visibility=View.GONE;body.addView(this)}
    }
    private fun changed(input:EditText, action:()->Unit) { input.addTextChangedListener(object:TextWatcher{
        override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
        override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){action()}
        override fun afterTextChanged(s:Editable?){}
    }) }
    fun resumeReadingTasks() { ImportTasks.attach(this) }
    private fun icon(resource:Int,label:String,action:()->Unit)=ImageButton(this).apply {
        setImageResource(resource);imageTintList=android.content.res.ColorStateList.valueOf(accent)
        contentDescription=label;background=null;setPadding(dp(12),dp(12),dp(12),dp(12));setOnClickListener{action()}
    }
    fun showHome() {
        if(sharedNovels.isNotEmpty()&&profileId.isNotBlank()){nextSharedNovel();return}
        val body=surface("");currentPage="首页"
        val header=row();header.addView(text("灵墨阅读",29f).apply{typeface=Typeface.DEFAULT_BOLD},LinearLayout.LayoutParams(0,-2,1f))
        header.addView(icon(R.drawable.ic_settings,"设置"){showSettings()},LinearLayout.LayoutParams(dp(48),dp(48)));body.addView(header)
        body.addView(text("置顶",15f).apply{setPadding(0,dp(28),0,dp(12))})
        val pinned=column(20).apply{background=GradientDrawable().apply{setColor(if(dark)0xff303529.toInt()else Color.rgb(238,234,222));cornerRadius=dp(18).toFloat()}}
        val title=row();title.addView(ImageView(this).apply{setImageResource(R.drawable.ic_book);imageTintList=android.content.res.ColorStateList.valueOf(accent)},LinearLayout.LayoutParams(dp(28),dp(28)))
        title.addView(text("小说阅读器",20f).apply{setPadding(dp(10),0,0,0)});pinned.addView(title)
        val recent=library.all(profileId,includeText=false).firstOrNull{!it.localRemoved}
        val hideNovelName=prefs.getBoolean("home_hide_novel_name:$profileId",true)
        if(!hideNovelName&&recent!=null){pinned.addView(text(recent.title,23f).apply{setPadding(0,dp(25),0,dp(10))});pinned.addView(text(recent.chapters.getOrNull(recent.position.chapter)?.title.orEmpty(),14f))}
        val links=row();weighted(links,button("继续阅读"){recent?.let{openBook(it)}?:showShelf()});weighted(links,button("打开书架"){showShelf()});pinned.addView(links);body.addView(pinned)
        body.addView(text("小工具",18f).apply{setPadding(0,dp(30),0,dp(16))})
        val tools=row()
        fun tile(label:String,resource:Int,action:()->Unit){
            val tile=column(16).apply{gravity=Gravity.CENTER;background=GradientDrawable().apply{setColor(cardColor);cornerRadius=dp(15).toFloat()};setOnClickListener{action()}}
            tile.contentDescription=if(label=="资料导出")"阅读资料导出"else label
            tile.isClickable=true;tile.isFocusable=true;tile.minimumHeight=dp(120)
            tile.addView(ImageView(this).apply{setImageResource(resource);imageTintList=android.content.res.ColorStateList.valueOf(accent)},LinearLayout.LayoutParams(dp(28),dp(28)).apply{bottomMargin=dp(10)})
            tile.addView(text(label,14f).apply{gravity=Gravity.CENTER;minLines=2;maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,0,0,0)},LinearLayout.LayoutParams(-1,-2));weighted(tools,tile)
        }
        tile("导入小说",R.drawable.ic_add){showImport()}
        tile("资料导出",R.drawable.ic_share){exportLibrary()}
        tile("拾墨",R.drawable.ic_write){startActivity(Intent(this,cn.jiangcheng.lemon.shimo.ShimoActivity::class.java))}
        body.addView(tools)
    }
    private var shelfFolder=""
    private var shelfOwner=""
    private fun shelfFolders():List<String>{
        val saved=org.json.JSONArray(prefs.getString("shelf-folders:$profileId","[]"));val paths=(0 until saved.length()).map{saved.getString(it)}+library.all(profileId,false).map{it.folder}.filter{it.isNotBlank()}
        return paths.flatMap{path->val parts=path.split('/');parts.indices.map{parts.take(it+1).joinToString("/")}}.distinct().sorted()
    }
    private fun saveShelfFolders(folders:List<String>){prefs.edit().putString("shelf-folders:$profileId",org.json.JSONArray(folders.distinct()).toString()).apply()}
    private fun moveShelfBooks(ids:Set<String>){
        val folders=listOf("")+shelfFolders()
        choose("移入文件夹",folders.map{if(it.isBlank())"书架首页"else"文件夹 · $it"}){name->val target=if(name=="书架首页")""else name.removePrefix("文件夹 · ")
            ids.forEach{id->library.get(profileId,id)?.let{it.folder=target;it.folderUpdated=System.currentTimeMillis();library.save(profileId,it)}};showShelf()
        }
    }
    private fun newShelfFolder(){edit("新建文件夹"){value->val name=value.trim();if(name.isEmpty()||name.length>40||name.contains('/')||name.contains('\\')){message("文件夹名称不合适","请输入40字以内的名称，不含斜杠。");return@edit};val path=listOf(shelfFolder,name).filter{it.isNotBlank()}.joinToString("/");saveShelfFolders(shelfFolders()+path);showShelf()}}
    private fun shelfFolderActions(path:String){choose(path.substringAfterLast('/'),listOf("重命名","解散文件夹，书籍移至上级")){action->
        val parent=path.substringBeforeLast('/',"")
        if(action=="重命名")edit("文件夹名称",path.substringAfterLast('/')){value->val name=value.trim();if(name.isBlank()||name.length>40||name.contains('/')||name.contains('\\'))return@edit
            val target=listOf(parent,name).filter{it.isNotBlank()}.joinToString("/");if(target!=path&&target in shelfFolders()){message("名称已存在","请换一个文件夹名称。");return@edit}
            val folders=shelfFolders().map{if(it==path||it.startsWith("$path/"))target+it.removePrefix(path)else it}
            library.all(profileId,false).filter{it.folder==path||it.folder.startsWith("$path/")}.forEach{summary->library.get(profileId,summary.id)?.let{it.folder=target+it.folder.removePrefix(path);it.folderUpdated=System.currentTimeMillis();library.save(profileId,it)}}
            saveShelfFolders(folders);showShelf()
        }else{
            val folders=shelfFolders().filter{it!=path&&!it.startsWith("$path/")}
            library.all(profileId,false).filter{it.folder==path||it.folder.startsWith("$path/")}.forEach{summary->library.get(profileId,summary.id)?.let{it.folder=parent;it.folderUpdated=System.currentTimeMillis();library.save(profileId,it)}}
            saveShelfFolders(folders);showShelf()
        }
    }}
    fun showShelf(query:String="",category:String="全部",sort:String="最近阅读",folder:String?=null) {
        if(shelfOwner!=profileId){shelfOwner=profileId;shelfFolder=""};if(folder!=null)shelfFolder=folder
        val body=surface("");currentPage="书架"
        val search=field("搜索书名").apply{setText(query);background=GradientDrawable().apply{setColor(if(dark)0xff30332b.toInt()else 0xffeeeef0.toInt());cornerRadius=dp(10).toFloat()}}
        val searchBar=row();searchBar.addView(iconAction("",R.drawable.ic_back){if(shelfFolder.isNotBlank())showShelf(folder=shelfFolder.substringBeforeLast('/',""))else showHome()}.apply{contentDescription=if(shelfFolder.isBlank())"返回首页"else"上一级书架";setPadding(0,0,dp(10),0)},LinearLayout.LayoutParams(dp(40),dp(48)))
        searchBar.addView(search,LinearLayout.LayoutParams(0,-2,1f));body.addView(searchBar)
        val bar=row();bar.addView(text(shelfFolder.substringAfterLast('/').ifBlank{"书架"},25f).apply{typeface=Typeface.DEFAULT_BOLD;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,-2,1f))
        bar.addView(iconAction("导入",R.drawable.ic_add){showImport()},LinearLayout.LayoutParams(-2,-2))
        var selecting=false;val selected=mutableSetOf<String>();val select=iconAction("选择",R.drawable.ic_check){};bar.addView(select,LinearLayout.LayoutParams(-2,-2));body.addView(bar)
        var listMode=prefs.getBoolean("shelf-list:$profileId",false)
        val filters=row();val categories=listOf("全部")+library.all(profileId,false).map{it.category}.distinct()
        filters.addView(iconAction(sort,R.drawable.ic_sort){choose("排序",listOf("最近阅读","书名","阅读时长")){showShelf(search.text.toString(),category,it)}}.apply{textSize=14f;setPadding(dp(12),0,dp(12),0);background=GradientDrawable().apply{setColor(if(dark)0xff34372f.toInt()else 0xffeceadf.toInt());cornerRadius=dp(12).toFloat()}},LinearLayout.LayoutParams(0,dp(44),1f).apply{marginEnd=dp(8)})
        filters.addView(iconAction(if(listMode)"网格"else"列表",if(listMode)R.drawable.ic_grid else R.drawable.ic_list){prefs.edit().putBoolean("shelf-list:$profileId",!listMode).apply();showShelf(search.text.toString(),category,sort)}.apply{textSize=14f;setPadding(dp(12),0,dp(12),0);background=GradientDrawable().apply{setColor(if(dark)0xff34372f.toInt()else 0xffeceadf.toInt());cornerRadius=dp(12).toFloat()}},LinearLayout.LayoutParams(0,dp(44),1f).apply{marginEnd=dp(8)})
        filters.addView(iconAction("",R.drawable.ic_more){choose("书架管理",listOf("新建文件夹","分类：$category")){if(it=="新建文件夹")newShelfFolder()else choose("分类",categories){value->showShelf(search.text.toString(),value,sort)}}}.apply{contentDescription="书架管理"},LinearLayout.LayoutParams(dp(42),dp(44)));body.addView(filters)
        val booksBody=column(0);body.addView(booksBody)
        val batchRow=row();val move=button("移入文件夹"){};val batch=button("删除本书"){};batchRow.addView(move,LinearLayout.LayoutParams(0,-2,1f));batchRow.addView(batch,LinearLayout.LayoutParams(0,-2,1f));body.addView(batchRow);batchRow.visibility=View.GONE
        fun cover(book:Book,small:Boolean=false):FrameLayout{
            val art=cn.jiangcheng.lemon.ui.CoverArt(book.id,book.coverColor.takeIf{it != -1909038})
            val frame=FrameLayout(this).apply{background=art;clipToOutline=true}
            val bitmap=if(book.cover.isNotEmpty())android.graphics.BitmapFactory.decodeFile(book.cover,android.graphics.BitmapFactory.Options().apply{inSampleSize=3})else null
            if(bitmap!=null)frame.addView(ImageView(this).apply{setImageBitmap(bitmap);scaleType=ImageView.ScaleType.CENTER_CROP},FrameLayout.LayoutParams(-1,-1))
            else frame.addView(text(book.coverText.ifBlank{book.title},if(small)11f else 17f).apply{setTextColor(if(book.coverColor == -1909038)art.ink else book.coverInk);gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL;setPadding(dp(if(small)6 else 10),dp(if(small)13 else 25),dp(if(small)5 else 8),dp(8));maxLines=4;ellipsize=android.text.TextUtils.TruncateAt.END;typeface=Typeface.DEFAULT_BOLD},FrameLayout.LayoutParams(-1,-1))
            return frame
        }
        data class Item(val book:Book?=null,val path:String="")
        fun render(){
            booksBody.removeAllViews();val all=library.all(profileId,false);val q=search.text.toString().trim()
            val filtered=all.filter{it.title.contains(q,true)&&(q.isNotBlank()||it.folder==shelfFolder)&&(category=="全部"||it.category==category)}
            val books=when(sort){"书名"->filtered.sortedBy{it.title};"阅读时长"->filtered.sortedByDescending{it.readSeconds};else->filtered}
            val folders=shelfFolders().filter{it.substringBeforeLast('/',"")==shelfFolder&&it.substringAfterLast('/').contains(q,true)}
            val items=folders.map{Item(path=it)}+books.map{Item(book=it)}
            if(items.isEmpty())booksBody.addView(text(if(shelfFolder.isEmpty())"暂无小说"else"文件夹还是空的，可以从书架选择书籍移入。",16f))
            val width=(resources.displayMetrics.widthPixels/resources.displayMetrics.density-64)/3
            for(group in items.chunked(if(listMode)1 else 3)){
                val shelfRow=row().apply{gravity=Gravity.TOP}
                for(value in group){val book=value.book;val title=book?.title?:value.path.substringAfterLast('/')
                    val item=LinearLayout(this).apply{orientation=if(listMode)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL;setPadding(dp(4),dp(12),dp(4),dp(14));contentDescription=if(book==null)"文件夹：$title"else"书籍：$title"}
                    val image=if(book!=null)cover(book,listMode)else FrameLayout(this).apply{
                        background=GradientDrawable().apply{setColor(if(dark)0xff42453a.toInt()else 0xffe4e1d3.toInt());cornerRadius=dp(8).toFloat()}
                        val children=all.filter{it.folder==value.path||it.folder.startsWith("${value.path}/")};val grid=LinearLayout(this@MainActivity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(10),dp(8),dp(24))}
                        children.take(4).chunked(2).forEach{pair->val r=row();pair.forEach{r.addView(cover(it,true),LinearLayout.LayoutParams(0,0,1f).apply{height=dp(if(listMode)25 else 48);setMargins(dp(2),dp(2),dp(2),dp(2))})};grid.addView(r)}
                        addView(grid,FrameLayout.LayoutParams(-1,-1));addView(text("${children.size} 本",if(listMode)10f else 12f).apply{gravity=Gravity.CENTER;setPadding(0,0,0,0);includeFontPadding=false;setTextColor(muted)},FrameLayout.LayoutParams(-1,dp(28),Gravity.BOTTOM))
                    }
                    item.addView(image,LinearLayout.LayoutParams(if(listMode)dp(64)else -1,dp(if(listMode)90 else (width*1.43).toInt())))
                    val words=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;if(listMode)setPadding(dp(14),0,0,0)}
                    words.addView(text(title,if(listMode)17f else 14f).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;minHeight=dp(42)})
                    if(listMode)words.addView(text(if(book==null)"文件夹 · 点击打开"else"${book.category} · 共 ${book.chapters.size} 章",12f).apply{setTextColor(muted)})
                    if(selecting&&book!=null)words.addView(CheckBox(this).apply{text="选择";isChecked=book.id in selected;setOnCheckedChangeListener{_,checked->if(checked)selected.add(book.id)else selected.remove(book.id);batch.text="删除本书（${selected.size}）";move.text="移入文件夹（${selected.size}）"}})
                    item.addView(words,if(listMode)LinearLayout.LayoutParams(0,-2,1f)else LinearLayout.LayoutParams(-1,-2))
                    item.setOnClickListener{if(book==null)showShelf(folder=value.path)else if(selecting){if(!selected.add(book.id))selected.remove(book.id);render()}else openBook(book)}
                    item.setOnLongClickListener{if(book==null)shelfFolderActions(value.path)else bookActions(book);true}
                    shelfRow.addView(item,LinearLayout.LayoutParams(0,-2,1f))
                }
                if(!listMode)repeat(3-group.size){shelfRow.addView(View(this),LinearLayout.LayoutParams(0,1,1f))};booksBody.addView(shelfRow)
            }
            batch.text="删除本书（${selected.size}）";move.text="移入文件夹（${selected.size}）"
        }
        select.setOnClickListener{selecting=!selecting;select.text=if(selecting)"完成"else"选择";selected.clear();batchRow.visibility=if(selecting)View.VISIBLE else View.GONE;render()}
        move.setOnClickListener{if(selected.isNotEmpty())moveShelfBooks(selected)}
        batch.setOnClickListener{if(selected.isNotEmpty())AlertDialog.Builder(this).setTitle("删除所选书籍").setMessage("将删除所选 ${selected.size} 本书及其本地阅读资料，此操作无法撤销。")
            .setPositiveButton("删除"){_,_->selected.forEach{id->library.get(profileId,id)?.let{library.deleteBook(profileId,it)}};showShelf()}.setNegativeButton("取消",null).show()}
        changed(search){render()};render()
    }
    fun choose(title:String,items:List<String>,done:(String)->Unit) { AlertDialog.Builder(this).setTitle(title).setItems(items.toTypedArray()){_,i->done(items[i])}.setNegativeButton("取消",null).show() }
    fun edit(title:String,value:String="",multiline:Boolean=false,confirm:String="保存",done:(String)->Unit) {
        val input=field(title).apply{setText(value);if(multiline){setSingleLine(false);minLines=3;maxLines=8;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE}}
        AlertDialog.Builder(this).setTitle(title).setView(input).setPositiveButton(confirm){_,_->done(input.text.toString())}.setNegativeButton("取消",null).show()
    }
    private fun bookActions(summary:Book) { val book=library.get(profileId,summary.id) ?: return;choose(book.title,listOf("书名与封面","智能整理","移入文件夹","设置分类","更新本书文件","阅读统计","查看札记与书志","删除本书")){action->
        when(action) {
            "移入文件夹"->moveShelfBooks(setOf(book.id))
            "书名与封面"->editBook(book)
            "智能整理"->organizeBook(book)
            "设置分类"->edit("分类",book.category){book.category=it.ifBlank{"未分类"};library.save(profileId,book);showShelf()}
            "更新本书文件"->{updateBook=book;pickFile("*/*"){readImport(it,book)}}
            "阅读统计"->message("阅读统计","${book.title}\n累计 ${book.readSeconds/60} 分钟\n翻阅约 ${book.readChars} 字\n${if(book.readSeconds>0)book.readChars*60/book.readSeconds else 0} 字/分钟（翻阅估算）")
            "查看札记与书志"->{val screen=ReaderScreen(this,book);screen.showCollection("札记")}
            "删除本书"->AlertDialog.Builder(this).setTitle("删除本书").setMessage("将删除本机中的小说正文、阅读进度、札记、划线和书志。此操作无法撤销。")
                .setPositiveButton("删除"){_,_->library.deleteBook(profileId,book);showShelf()}.setNegativeButton("取消",null).show()
        }
    }}
    fun openBook(summary:Book) {
        CrashLog.event(this,CrashLog.Event.READ_OPEN)
        val generation=screenGeneration
        async(null,{
            val existing=library.get(profileId,summary.id)
            if(existing!=null&&existing.chapters.any{it==ImportTasks.waiting}){
                val source=library.sourceFile(profileId,existing.id)
                if(source.isFile){val raw=ImportAgent(AiConfig("","","")).process(source.readBytes(),existing.sourceName,ImportAgent.Options(false,false,false,false));library.import(profileId,raw,source.readBytes(),existing.sourceName,existing)}else existing
            }else existing
        },CrashLog.Event.READ_FAILED){value->
            if(generation!=screenGeneration)return@async
            val book=value as? Book ?: return@async
            if(book.localRemoved||book.chapters.isEmpty()){message("本机正文已移除","请通过更新本书文件重新导入正文");return@async}
            try {reader?.stop();reader=ReaderScreen(this,book);reader!!.show();CrashLog.event(this,CrashLog.Event.READ_READY,chapters=book.chapters.size);resumeReadingTasks()}
            catch(e:Exception){CrashLog.event(this,CrashLog.Event.READ_FAILED,error=e);reader=null;showShelf();message("无法打开小说",e.message ?: "正文读取失败")}
        }
    }
    fun editBook(book:Book) {
        val body=surface("书名与封面",back={showShelf()})
        val title=field("书名").apply{setText(book.title)};labelled(body,"书名",title)
        val words=field("跟随书名").apply{setText(book.coverText)};labelled(body,"封面文字",words)
        var color=book.coverColor;var ink=book.coverInk;var coverPath=book.cover
        val preview=FrameLayout(this);body.addView(preview,LinearLayout.LayoutParams(dp(156),dp(222)).apply{gravity=Gravity.CENTER_HORIZONTAL;topMargin=dp(20);bottomMargin=dp(16)})
        fun render(){
            preview.removeAllViews();preview.setBackgroundColor(color)
            val bitmap=if(coverPath.isNotEmpty())android.graphics.BitmapFactory.decodeFile(coverPath,android.graphics.BitmapFactory.Options().apply{inSampleSize=3})else null
            if(bitmap!=null)preview.addView(ImageView(this).apply{setImageBitmap(bitmap);scaleType=ImageView.ScaleType.CENTER_CROP},FrameLayout.LayoutParams(-1,-1))
            else preview.addView(text(words.text.toString().ifBlank{title.text.toString()},23f).apply{gravity=Gravity.CENTER;setTextColor(ink);setPadding(dp(15),dp(15),dp(15),dp(15));typeface=Typeface.DEFAULT_BOLD;maxLines=7},FrameLayout.LayoutParams(-1,-1))
        }
        changed(title){render()};changed(words){coverPath="";render()}
        body.addView(button("选择默认封面"){
            val styles=listOf("素纸","青黛","松绿","胭脂","夜色")
            choose("默认封面",styles){name->val pairs=listOf(0xffe9e4cd.toInt() to 0xff34332b.toInt(),0xffdbe3ed.toInt() to 0xff33475e.toInt(),0xffdae5da.toInt() to 0xff354d3d.toInt(),0xffeedddb.toInt() to 0xff754944.toInt(),0xff30363f.toInt() to 0xffe9dec5.toInt());val pair=pairs[styles.indexOf(name)];color=pair.first;ink=pair.second;coverPath="";render()}
        })
        body.addView(button("封面颜色"){
            val values=column();val bg=field("#RRGGBB").apply{setText(String.format("#%06X",color and 0xffffff))};labelled(values,"背景颜色",bg)
            val fg=field("#RRGGBB").apply{setText(String.format("#%06X",ink and 0xffffff))};labelled(values,"文字颜色",fg)
            val error=text("",14f);values.addView(error)
            val dialog=AlertDialog.Builder(this).setTitle("封面颜色").setView(values).setPositiveButton("确定",null).setNegativeButton("取消",null).create()
            dialog.setOnShowListener{dialog.getButton(-1).setOnClickListener{try{val nextColor=Color.parseColor(bg.text.toString());val nextInk=Color.parseColor(fg.text.toString());color=nextColor;ink=nextInk;coverPath="";render();dialog.dismiss()}catch(e:Exception){error.text="请输入有效颜色，如 #E9E4CD"}}};dialog.show()
        })
        body.addView(button("导入封面图片"){pickFile("image/*"){uri->
            async(null,{
                val bounds=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
                contentResolver.openInputStream(uri)!!.use{android.graphics.BitmapFactory.decodeStream(it,null,bounds)}
                require(bounds.outWidth>0&&bounds.outHeight>0){"无法读取这张图片"}
                var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1200)sample*=2
                val bitmap=contentResolver.openInputStream(uri)!!.use{android.graphics.BitmapFactory.decodeStream(it,null,android.graphics.BitmapFactory.Options().apply{inSampleSize=sample})} ?: error("无法读取这张图片")
                val target=File(library.sourceFile(profileId,book.id).parentFile,"cover-${System.currentTimeMillis()}.jpg");target.parentFile!!.mkdirs()
                target.outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,it)};bitmap.recycle();target.absolutePath
            }){result->coverPath=result as String;render()}
        }})
        body.addView(button("保存"){if(title.text.isBlank()){title.error="请输入书名"}else{book.title=title.text.toString().trim();book.coverText=words.text.toString();book.coverColor=color;book.coverInk=ink;book.cover=coverPath;library.save(profileId,book);CrashLog.event(this,CrashLog.Event.COVER_SAVED);showShelf()}})
        render()
    }
    private fun showImport() {
        val body=surface("导入小说",back={showShelf()},fixed=true)
        body.addView(button("从手机选择文件"){pickFile("*/*"){readImport(it)}})
        body.addView(button("已解压书源"){showExtractedSources()})
        body.addView(text("支持 TXT、EPUB、MOBI、AZW3 及常见压缩包。文件由系统选择器授权，不需要访问手机上的全部文件。",14f).apply{setTextColor(muted)})
    }
    private fun showExtractedSources(){
        val body=surface("已解压书源",back={showImport()},fixed=true);val generation=screenGeneration;val status=text("正在读取书源…",14f);body.addView(status)
        async(status,{File(filesDir,"import-archives/$profileId").walkTopDown().filter{it.isFile&&it.extension.lowercase() in listOf("txt","epub","mobi","azw3")}.toList()}){value->if(generation==screenGeneration){@Suppress("UNCHECKED_CAST") val files=value as List<File>;body.removeView(status);cn.jiangcheng.lemon.ui.ScanScreen(this,body,files,{generation==screenGeneration}).show()}}
    }
    private fun importSequence(files:List<File>) { if(files.isEmpty())return;readImport(Uri.fromFile(files.first()),after={importSequence(files.drop(1))}) }
    fun pickFile(mime:String,callback:(Uri)->Unit) {
        pickCallback=callback;startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type=mime;addCategory(Intent.CATEGORY_OPENABLE);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},50)
    }
    @Deprecated("Platform activity callback") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) { super.onActivityResult(requestCode,resultCode,data);if(requestCode in listOf(50,51)&&resultCode==RESULT_OK)data?.data?.let{pickCallback?.invoke(it)} }
    private fun importOptions()=ImportAgent.Options(prefs.getBoolean("import_encoding:$profileId",true),prefs.getBoolean("import_chapters:$profileId",true),prefs.getBoolean("import_titles:$profileId",false),prefs.getBoolean("import_tidy:$profileId",true),ads=prefs.getBoolean("import_ads:$profileId",true))
    fun organizeBook(book:Book){
        if(ImportTasks.state(this,profileId,book.id)!=null){showImportJob(book.id);return}
        if(!library.sourceFile(profileId,book.id).isFile){message("需要原文件","请重新选择小说文件。");return}
        organizeOptions(book)
    }
    private fun organizeOptions(book:Book){
        val defaults=importOptions();val structured=book.sourceName.substringAfterLast('.').lowercase() in listOf("epub","mobi","azw3","tgtxt")
        val content=column();content.setPadding(dp(24),dp(8),dp(24),dp(8))
        fun option(label:String,checked:Boolean)=CheckBox(this).apply{text=label;isChecked=checked;setTextColor(ink);textSize=16f;minHeight=dp(46);content.addView(this)}
        val chapters=option("章节识别",defaults.chapters&&!structured)
        val encoding=option("乱码纠正",defaults.encoding)
        val tidy=option("格式优化",defaults.tidy);val ads=option("清理广告",defaults.ads)
        val titles=option("标题补全请在目录中操作",false).apply{visibility=View.GONE}
        val feedback=EditText(this).apply{hint="补充整理意见（可选）";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE;minLines=3;setTextColor(ink)};content.addView(feedback)
        val dialog=AlertDialog.Builder(this).setTitle("整理选项").setView(ScrollView(this).apply{addView(content)}).setPositiveButton("开始整理",null).setNegativeButton("取消",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val options=ImportAgent.Options(encoding.isChecked,chapters.isChecked,titles.isChecked,tidy.isChecked,preserveStructure=!chapters.isChecked,ads=ads.isChecked)
            if(!options.enabled){message("请选择整理项目","至少选择一项。");return@setOnClickListener}
            dialog.dismiss();if(ImportTasks.start(this,book,options,feedback.text.toString().take(4000)))showImportJob(book.id)
        }};dialog.show()
    }
    fun showImportJob(id:String){
        val body=surface("智能整理",back={showShelf()});val generation=screenGeneration;val owner=profileId
        val status=text("",15f);body.addView(status)
        val progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;progressTintList=android.content.res.ColorStateList.valueOf(accent)};body.addView(progress,LinearLayout.LayoutParams(-1,dp(12)).apply{topMargin=dp(16);bottomMargin=dp(8)})
        val stage=text("",13f);body.addView(stage)
        body.addView(button("继续阅读"){library.get(owner,id)?.let{openBook(it)}})
        val apply=button("更新本书"){async(status,{ImportTasks.apply(this,id)}){showImportJob(id)}};body.addView(apply)
        val retry=button("继续整理"){ImportTasks.retry(this,id)};body.addView(retry)
        val secondary=row();body.addView(secondary)
        val redo=button("重新整理"){
            library.get(owner,id)?.let{organizeOptions(it)}
        }.apply{background=null};weighted(secondary,redo)
        weighted(secondary,button("恢复正文"){choose("恢复正文",listOf("撤销本次整理","恢复导入原文")){choice->async(status,{ImportTasks.restore(this,id,choice=="恢复导入原文")}){showImportJob(id)}}}.apply{background=null})
        val directory=text("",16f);body.addView(directory)
        val handler=android.os.Handler(android.os.Looper.getMainLooper())
        val refresh=object:Runnable{override fun run(){if(screenGeneration!=generation||isDestroyed)return
            val job=ImportTasks.state(this@MainActivity,owner,id)
            if(job!=null){val phase=job.optString("status");progress.progress=job.optInt("percent");stage.text=if(job.optBoolean("noDirectory"))"处理完成 · 未识别到章节目录"else"全书进度 ${job.optInt("percent")}% · 目录 ${job.optInt("ready")} 项";status.text=(if(job.optInt("ready")<20&&phase=="running")"优先准备前 20 章 · "else"")+job.optString("message");apply.visibility=if(phase=="ready")View.VISIBLE else View.GONE;retry.visibility=if(phase=="failed")View.VISIBLE else View.GONE;redo.isEnabled=phase !in listOf("queued","running")
                val titles=job.optJSONArray("directory")?:org.json.JSONArray();directory.text=(0 until titles.length()).joinToString("\n\n"){titles.getString(it)}
                if(phase=="restored"){progress.visibility=View.GONE;stage.text="当前为恢复后的正文，可重新选择整理项目"}else progress.visibility=View.VISIBLE
                redo.alpha=if(redo.isEnabled)1f else .4f
            };handler.postDelayed(this,1500)
        }};refresh.run()
    }
    fun readImport(uri:Uri,update:Book?=null,sourceName:String?=null,after:()->Unit={},suppliedBytes:ByteArray?=null,quick:Boolean=false) {
        if(suppliedBytes!=null||update!=null){readNovel(uri,update,sourceName,after,suppliedBytes,quick);return}
        val body=surface("读取文件",back={showImport()});val status=text("正在识别文件格式…",14f);body.addView(status);val generation=screenGeneration
        async(status,{
            val name=sourceName ?: if(uri.scheme=="file")File(uri.path!!).name else contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null} ?: "小说.txt"
            val header=contentResolver.openInputStream(uri)!!.use{input->val b=ByteArray(512);val n=input.read(b);b.copyOf(n.coerceAtLeast(0))}
            name to (if(name.substringAfterLast('.').lowercase()=="epub")null else ArchiveImport.kind(header))
        }){value->if(generation==screenGeneration){val(name,format)=value as Pair<*,*>;if(format!=null||ArchiveImport.named(name as String))readArchive(uri,name as String,after)else readNovel(uri,null,name as String,after,quick=quick)}}
    }
    private fun readArchive(uri:Uri,name:String,after:()->Unit){
        val body=surface("解压小说",back={showImport()},fixed=true);val generation=screenGeneration
        val status=text("正在解压 $name…",14f);body.addView(status)
        async(status,{
            val staging=File(cacheDir,"archive-${java.util.UUID.randomUUID()}")
            try{contentResolver.openInputStream(uri)!!.use{input->staging.outputStream().use{out->val b=ByteArray(32768);var total=0L;while(true){val n=input.read(b);if(n<0)break;total+=n;require(total<=256_000_000){"压缩包超过 256 MB，请分批导入"};out.write(b,0,n)}}}
                val dest=File(filesDir,"import-archives/$profileId/${java.util.UUID.randomUUID()}")
                ArchiveImport.extract(staging,dest)
            }finally{staging.delete()}
        }){value->if(generation==screenGeneration){@Suppress("UNCHECKED_CAST") val files=value as List<File>;status.text="已解压，找到 ${files.size} 个小说文件。勾选即可按默认设置导入。";if(files.isNotEmpty())cn.jiangcheng.lemon.ui.ScanScreen(this,body,files,{generation==screenGeneration}).show()else body.addView(button("继续选择文件"){showImport();after()})}}
    }
    private fun readNovel(uri:Uri,update:Book?=null,sourceName:String?=null,after:()->Unit={},suppliedBytes:ByteArray?=null,quick:Boolean=false) {
        CrashLog.event(this,CrashLog.Event.IMPORT_BEGIN)
        val body=surface("导入预览",back={activeSharedNovel=null;sharedNovels.clear();showShelf()});val generation=screenGeneration;val owner=profileId
        val status=text("正在读取文件…",14f);body.addView(status)
        async(status,{
            val name=sourceName ?: (if(uri.scheme=="file")File(uri.path!!).name else contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else"小说.txt"} ?: "小说.txt")
            require(name.substringAfterLast('.').lowercase() in listOf("txt","epub","mobi","azw3")||(suppliedBytes!=null&&name.endsWith(".tgtxt"))){"请选择 TXT、EPUB、MOBI 或 AZW3 文件"}
            val bytes=suppliedBytes ?: contentResolver.openInputStream(uri)!!.use{input->val out=java.io.ByteArrayOutputStream();val block=ByteArray(8192);while(true){val n=input.read(block);if(n<0)break;require(out.size()+n<=80_000_000){"当前导入上限为 80 MB"};out.write(block,0,n)};out.toByteArray()}
            Triple(name,bytes,if(update==null)library.duplicate(owner,bytes)else null)
        },CrashLog.Event.IMPORT_FAILED){result->
            if(generation!=screenGeneration)return@async
            val data=result as Triple<*,*,*>;val name=data.first as String;val bytes=data.second as ByteArray;val duplicate=data.third as? Book
            if(duplicate!=null){if(quick){showShelf();after();return@async};status.text="这本小说已在书架中：${duplicate.title}";body.addView(button("打开已有小说"){openBook(duplicate)});body.addView(button("继续导入其他小说"){showShelf();after()});return@async}
            val defaults=importOptions();val structured=name.substringAfterLast('.').lowercase() in listOf("epub","mobi","azw3","tgtxt")
            val advanced=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE}
            body.addView(button("整理选项 · 按默认设置处理"){advanced.visibility=if(advanced.visibility==View.VISIBLE)View.GONE else View.VISIBLE});body.addView(advanced)
            fun option(label:String,checked:Boolean)=CheckBox(this).apply{text=label;isChecked=checked;setTextColor(ink);minHeight=dp(44);advanced.addView(this)}
            val chapters=option("章节与目录识别",defaults.chapters&&!structured);val encoding=option("乱码纠正",defaults.encoding);val tidy=option("格式优化",defaults.tidy);val ads=option("清理广告",defaults.ads);val titles=option("标题补全请在目录中操作",false).apply{visibility=View.GONE}
            val feedback=field("补充整理意见（可选）");advanced.addView(feedback)
            fun options()=ImportAgent.Options(encoding.isChecked,chapters.isChecked,titles.isChecked,tidy.isChecked,preserveStructure=!chapters.isChecked,ads=ads.isChecked)
            body.addView(text(if(ModelProfiles.available(this,owner))"编码、格式和基础目录在本地处理，AI 按设置在后台优化目录；不确定的内容保留原状。"else"当前未配置模型，可直接完成本地编码、格式和基础目录整理；之后仍可在书架继续优化。",13f))
            var busy=false
            val start=button("导入并整理"){};body.addView(start)
            val raw=button("仅本地整理"){}.apply{visibility=if(ModelProfiles.available(this@MainActivity,owner))View.VISIBLE else View.GONE};body.addView(raw)
            fun direct(){if(busy)return;busy=true;start.isEnabled=false;raw.isEnabled=false
                async(status,{
                    val novel=LocalPrepare.read(bytes,name,options())
                    library.import(owner,novel,bytes,name,update)
                },CrashLog.Event.IMPORT_FAILED){if(generation==screenGeneration){showShelf();after()}}
            }
            fun begin(){
                val selectedOptions=options()
                if(!selectedOptions.enabled){direct();return}
                if(busy)return
                busy=true;start.isEnabled=false;raw.isEnabled=false
                async(status,{
                    library.import(owner,LocalPrepare.read(bytes,name,selectedOptions),bytes,name,update)
                },CrashLog.Event.IMPORT_FAILED){result->val book=result as Book
                    if(prefs.getBoolean("ai_directory:$owner",true)&&ModelProfiles.available(this,owner)&&selectedOptions.chapters)ImportTasks.start(this,book,selectedOptions,feedback.text.toString().take(2000),fresh=true)
                    if(generation==screenGeneration){showShelf();after()}
                }
            }
            start.setOnClickListener{begin()}
            raw.setOnClickListener{direct()}
            status.text=if(name.endsWith(".tgtxt"))name.removeSuffix(".tgtxt")+" · 分章 TXT"else name
            if(quick)begin()

        }
    }
    private fun exportLibrary() {
        val body=surface("导出阅读资料","包含书籍、进度、札记、划线、书志、拾墨和阅读设置。AI 密钥不会写入备份。",{showSettings()})
        val status=text("",14f);body.addView(status)
        body.addView(button("选择保存位置"){pickCallback={uri->async(status,{contentResolver.openOutputStream(uri,"w")!!.use{LocalBackup(this,library).export(profileId,it)}}){status.text="阅读资料已导出"}};startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{type="application/zip";addCategory(Intent.CATEGORY_OPENABLE);putExtra(Intent.EXTRA_TITLE,"灵墨阅读-完整备份-${System.currentTimeMillis()}.zip")},51)})
    }
    private fun importLibrary(){
        val body=surface("导入阅读资料","恢复灵墨阅读完整备份。已有相同编号的书籍会保留，不会被覆盖。",{showSettings()})
        val status=text("",14f);body.addView(status)
        body.addView(button("选择备份文件"){pickCallback={uri->async(status,{contentResolver.openInputStream(uri)!!.use{LocalBackup(this,library).restore(profileId,it)}}){value->val result=value as LocalBackup.RestoreResult;status.text="已恢复 ${result.books} 本书、${result.excerpts} 条拾墨；跳过 ${result.skipped} 本已有书籍";resumeReadingTasks()}};startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="application/zip";addCategory(Intent.CATEGORY_OPENABLE);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},50)})
    }
    fun showSettings() {
        val body=surface("设置",back={showHome()})
        val appearance=settingsGroup(body,"外观")
        settingLink(appearance,"主题 · ${Appearance.mode(this)}"){choose("主题",Appearance.modes){Appearance.setMode(this,profileId,it);setTheme(if(dark)R.style.AppThemeDark else R.style.AppTheme);showSettings()}}
        val home=settingsGroup(body,"首页")
        settingSwitch(home,"隐藏小说名称","home_hide_novel_name",true)
        settingLink(home,"启动主页 · ${homeStartLabel()}"){choose("启动主页",listOf("默认主页","书架","拾墨")){value->
            val code=when(value){"书架"->"shelf";"拾墨"->"shimo";else->"default"}
            prefs.edit().putString("home_start_page:$profileId",code).apply();showSettings()
        }}
        val reading=settingsGroup(body,"阅读")
        settingLink(reading,"模型与 AI"){aiSettings()}
        settingSwitch(reading,"自动整理书志","auto_chronicles",true)
        settingLink(reading,"自动书志每日预算 · ${prefs.getInt("auto_chronicles_budget:$profileId",30000)} token"){choose("每日自动书志预算",listOf("10000","30000","100000")){prefs.edit().putInt("auto_chronicles_budget:$profileId",it.toInt()).apply();showSettings()}}
        settingSwitch(reading,"音量键翻页","volume_turn",true)
        settingLink(reading,"恢复各面板默认大小"){val panelPrefs=getSharedPreferences("reading:$profileId",0);val edit=panelPrefs.edit();panelPrefs.all.keys.filter{it.startsWith("panel_fraction:")||it.startsWith("panel_full:")||it in listOf("panels_full","panel_fraction")}.forEach{edit.remove(it)};edit.apply();Toast.makeText(this,"已恢复各面板默认大小",Toast.LENGTH_SHORT).show()}
        settingSwitch(reading,"编码纠正","import_encoding",true)
        settingSwitch(reading,"自动识别章节","import_chapters",true)
        settingSwitch(reading,"AI 目录优化","ai_directory",true)
        settingSwitch(reading,"整理空行与缩进","import_tidy",true)
        settingSwitch(reading,"清理明确的网站广告","import_ads",true)
        val data=settingsGroup(body,"数据管理")
        settingLink(data,"导出阅读资料"){exportLibrary()}
        settingLink(data,"导入阅读资料"){importLibrary()}
        val about=settingsGroup(body,"关于")
        val version=runCatching{packageManager.getPackageInfo(packageName,0).versionName}.getOrNull().orEmpty()
        settingLink(about,"灵墨阅读 · $version"){message("灵墨阅读","本地优先的阅读 Agent。书籍和阅读资料默认只保存在当前设备。")}
        settingLink(about,"阅读日志"){showReaderLogs()}
        settingLink(about,"第三方许可"){showThirdPartyLicenses()}
    }
    fun showThirdPartyLicenses(){
        val names=assets.list("licenses").orEmpty().sorted()
        choose("第三方许可",names){name->
            val notice=assets.open("licenses/$name").bufferedReader().use{it.readText()}
            message(name,notice)
        }
    }
    fun showReaderLogs(){
        val body=surface("阅读日志",back={showSettings()});val status=text("",14f);body.addView(status)
        var exported:File?=null
        val share=button("分享日志"){exported?.let{startActivity(Intent.createChooser(CrashLog.shareIntent(this,it),"分享阅读日志"))}}.apply{visibility=View.GONE}
        val save=button("保存到手机"){}.apply{visibility=View.GONE}
        body.addView(button("导出日志"){async(status,{CrashLog.export(applicationContext)}){file->exported=file as File;status.text="日志已导出";share.visibility=View.VISIBLE;save.visibility=View.VISIBLE}})
        body.addView(share);body.addView(save)
        save.setOnClickListener{val file=exported?:return@setOnClickListener;pickCallback={uri->async(status,{contentResolver.openOutputStream(uri)!!.use{out->file.inputStream().use{it.copyTo(out)}}}){status.text="日志已保存"}};startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{type="application/zip";addCategory(Intent.CATEGORY_OPENABLE);putExtra(Intent.EXTRA_TITLE,file.name)},51)}
    }
    private fun settingsGroup(body:LinearLayout,title:String):LinearLayout{
        body.addView(text(title,13f).apply{setTextColor(muted);setPadding(dp(4),dp(24),0,dp(10))})
        return column(14).apply{background=GradientDrawable().apply{setColor(cardColor);cornerRadius=dp(14).toFloat()};body.addView(this)}
    }
    private fun settingLink(group:LinearLayout,label:String,action:()->Unit){
        val line=row().apply{minimumHeight=dp(55);setOnClickListener{action()}}
        line.addView(text(label,16f),LinearLayout.LayoutParams(0,-2,1f));line.addView(text("›",22f).apply{setTextColor(muted)},LinearLayout.LayoutParams(-2,-2));group.addView(line)
    }
    private fun settingSwitch(group:LinearLayout,label:String,key:String,default:Boolean,onChange:()->Unit={}){
        group.addView(Switch(this).apply{text=label;textSize=16f;setTextColor(ink);minimumHeight=dp(55);isChecked=prefs.getBoolean("$key:$profileId",default);setOnCheckedChangeListener{_,value->prefs.edit().putBoolean("$key:$profileId",value).apply();onChange()}},LinearLayout.LayoutParams(-1,-2))
    }
    fun importSettings(){
        val body=surface("导入与整理",back={showSettings()})
        val recognition=settingsGroup(body,"文本与目录")
        settingLink(recognition,"模型与 AI"){aiSettings()}
        settingSwitch(recognition,"编码纠正","import_encoding",true)
        settingSwitch(recognition,"自动识别章节","import_chapters",true)
        settingSwitch(recognition,"AI 目录优化","ai_directory",true)
        val format=settingsGroup(body,"排版整理")
        settingSwitch(format,"整理空行与缩进","import_tidy",true)
        settingSwitch(format,"清理明确的网站广告","import_ads",true)
    }
    fun aiSettings(afterSave:(()->Unit)?=null) { ModelProfiles.show(this,afterSave) }
    override fun onStart(){super.onStart()}
    override fun onStop(){exitPressedAt=null;super.onStop()}
    override fun onPause(){reader?.pause();super.onPause()}
    override fun onResume(){super.onResume();reader?.resume();if(::library.isInitialized&&profileId.isNotEmpty())resumeReadingTasks()}
    private fun handleHomeBack(){
        val now=SystemClock.elapsedRealtime()
        if(exitPressedAt?.let{now-it in 0..2000}==true){exitPressedAt=null;moveTaskToBack(true)}
        else {exitPressedAt=now;Toast.makeText(this,"再按一次退出灵墨阅读",Toast.LENGTH_SHORT).show()}
    }
    @Deprecated("Platform back callback") override fun onBackPressed(){
        if(invalidSharedInput){finish();return}
        if(reader?.returnFromSource()==true||reader?.clearSelectedText()==true)return
        when {
            currentPage=="导入预览"->{activeSharedNovel=null;sharedNovels.clear();showShelf()}
            reader!=null->{reader?.stop();reader=null;showShelf()}
            currentPage=="书架"&&shelfFolder.isNotBlank()->showShelf(folder=shelfFolder.substringBeforeLast('/',""))
            currentPage=="书架"&&shelfFolder.isBlank()->handleHomeBack()
            pageBack!=null->pageBack?.invoke()
            currentPage=="首页"->handleHomeBack()
            profileId.isNotEmpty()->showHome()
            else->super.onBackPressed()
        }
    }
    override fun onDestroy(){screenGeneration++;importAgent?.cancelled=true;reader?.stop();worker.shutdown();super.onDestroy()}
}
