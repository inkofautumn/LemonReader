package cn.jiangcheng.lemon.shimo

import android.app.Activity
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.text.*
import android.text.style.BackgroundColorSpan
import android.view.*
import android.widget.*
import androidx.core.content.FileProvider
import cn.jiangcheng.lemon.R
import cn.jiangcheng.lemon.data.Library
import cn.jiangcheng.lemon.data.LocalProfile
import cn.jiangcheng.lemon.ui.Appearance
import cn.jiangcheng.lemon.ui.ReaderDialog
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

/** Local excerpt collection and editing screen. */
class ShimoActivity:Activity() {
    lateinit var store:ShimoStore
    private lateinit var owner:String
    private val handler=Handler(Looper.getMainLooper())
    private val io=Executors.newSingleThreadExecutor()
    private val night get()=Appearance.dark(this)
    private val paper get()=if(night)0xff1d1f1b.toInt()else 0xffe9e4cd.toInt()
    private val card get()=if(night)0xff292c25.toInt()else 0xfffaf9f4.toInt()
    private val ink get()=if(night)0xffe6e3d8.toInt()else 0xff2c2b23.toInt()
    private val muted get()=if(night)0xffaaa99f.toInt()else 0xff7c7866.toInt()
    private val accent get()=if(night)0xffc9bc94.toInt()else 0xff69593c.toInt()
    private val chip get()=if(night)0xff383c30.toInt()else 0xffe3decb.toInt()
    private val prefs get()=getSharedPreferences("shimo-ui:$owner",0)
    private var folder="*";private var query="";private var source="全部";private var book="全部"
    private var labels=emptySet<String>();private var anyTag=false;private var sort="最近收藏";private var viewMode="标准卡片"
    private var trash=false;private var scrollY=0;private var listLimit=60
    private val selected=mutableSetOf<String>()
    private var current:Excerpt?=null
    private var stage="list"
    private var capture:String?=null
    private var imagesBox:LinearLayout?=null
    private var saveAction:(()->Unit)?=null
    private var listScroll:ScrollView?=null
    private var listBox:LinearLayout?=null
    private var countLabel:TextView?=null
    private var savePending:Runnable?=null
    private var recognizer:com.google.mlkit.vision.text.TextRecognizer?=null
    private var busy=false
    private var saveError:String?=null
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun shape(color:Int=card,r:Int=16)=GradientDrawable().apply{setColor(color);cornerRadius=dp(r).toFloat()}
    private fun text(v:String,size:Float=16f,color:Int=ink)=TextView(this).apply{text=v;textSize=size;setTextColor(color);setLineSpacing(dp(3).toFloat(),1f)}
    private fun col(p:Int=0)=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(p),dp(p),dp(p),dp(p))}
    private fun row()=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    private fun button(v:String,action:()->Unit)=Button(this).apply{text=v;textSize=14f;isAllCaps=false;stateListAnimator=null;elevation=0f;setTextColor(accent);backgroundTintList=null;background=shape(chip,12);minWidth=0;minimumWidth=0;minHeight=dp(46);setPadding(dp(12),dp(7),dp(12),dp(7));setOnClickListener{guard(action)}}
    private fun icon(desc:String,res:Int,action:()->Unit)=ImageButton(this).apply{contentDescription=desc;setImageResource(res);imageTintList=android.content.res.ColorStateList.valueOf(accent);background=shape(Color.TRANSPARENT);setPadding(dp(12),dp(12),dp(12),dp(12));setOnClickListener{guard(action)}}
    private fun addIcon(row:LinearLayout,desc:String,res:Int,action:()->Unit){row.addView(icon(desc,res,action),LinearLayout.LayoutParams(dp(44),dp(48)))}
    private fun guard(action:()->Unit){try{action()}catch(e:Exception){message("暂时无法完成",e.message?:"请重试")}}
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
    private fun message(title:String,msg:String){if(!isFinishing)ReaderDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("知道了",null).show()}
    private fun choose(title:String,options:List<String>,action:(Int)->Unit){ReaderDialog.Builder(this).setTitle(title).setItems(options.toTypedArray()){_,i->guard{action(i)}}.setNegativeButton("取消",null).show()}
    private fun input(title:String,initial:String="",done:(String)->Unit){val field=EditText(this).apply{setText(initial);setSingleLine();contentDescription=title};val d=ReaderDialog.Builder(this).setTitle(title).setView(field).setPositiveButton("确定",null).setNegativeButton("取消",null).show();d.getButton(-1).setOnClickListener{guard{done(field.text.toString().trim());d.dismiss()}}}
    private fun confirm(title:String,msg:String,done:()->Unit){ReaderDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("确定"){_,_->guard(done)}.setNegativeButton("取消",null).show()}
    override fun onCreate(state:Bundle?){setTheme(if(night)R.style.AppThemeDark else R.style.AppTheme);super.onCreate(state);owner=LocalProfile.ID;store=ShimoStore(this)
        if(Build.VERSION.SDK_INT>=33)onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT){goBack()}
        viewMode=prefs.getString("view","标准卡片")!!;sort=prefs.getString("sort","最近收藏")!!
        folder=state?.getString("folder")?:prefs.getString("folder","*")!!;query=state?.getString("query")?:prefs.getString("query","")!!;book=state?.getString("book")?:prefs.getString("book","全部")!!;source=state?.getString("source")?:prefs.getString("source","全部")!!;labels=state?.getStringArrayList("tags")?.toSet()?:prefs.getStringSet("tags",emptySet())!!.toSet();anyTag=state?.getBoolean("any")?:prefs.getBoolean("any",false);trash=state?.getBoolean("trash")?:false;scrollY=state?.getInt("scroll")?:prefs.getInt("scroll",0);capture=state?.getString("capture")
        current=state?.getString("entry")?.let{Excerpt.parse(JSONObject(it))};if(current!=null)edit(current!!)else showList()
    }
    override fun onResume(){super.onResume()}
    override fun onPause(){saveAction?.invoke();if(::owner.isInitialized&&owner.isNotBlank()){prefs.edit().putString("folder",folder).putString("query",query).putString("book",book).putString("source",source).putStringSet("tags",labels).putBoolean("any",anyTag).putInt("scroll",if(stage=="list")listScroll?.scrollY?:scrollY else scrollY).apply()};super.onPause()}
    override fun onSaveInstanceState(out:Bundle){saveAction?.invoke();out.putString("folder",folder);out.putString("query",query);out.putString("book",book);out.putString("source",source);out.putStringArrayList("tags",ArrayList(labels));out.putBoolean("any",anyTag);out.putBoolean("trash",trash);out.putInt("scroll",listScroll?.scrollY?:scrollY);out.putString("entry",current?.json()?.toString());out.putString("capture",capture);super.onSaveInstanceState(out)}
    override fun onDestroy(){savePending?.let{handler.removeCallbacks(it)};recognizer?.close();handler.removeCallbacks(searchRun);if(::store.isInitialized)io.execute{store.close()};io.shutdown();super.onDestroy()}
    @Deprecated("Platform callback")override fun onBackPressed(){goBack()}
    private fun goBack(){if(busy){toast("正在处理，请稍候");return};if(stage=="edit"){saveAction?.invoke();if(saveError!=null){message("保存未完成",saveError!!);return}};if(stage=="edit"&&current?.nonempty==false&&store.get(owner,current!!.id)!=null){message("摘抄内容不能为空","如需移除这条收藏，请使用右上角菜单中的删除。");return};when(stage){"source"->{current?.let{edit(it)}?:showList()};"edit"->{saveAction?.invoke();showList()};else->if(selected.isNotEmpty()){selected.clear();showList()}else if(trash){trash=false;showList()}else if(folder!="*"){folder="*";scrollY=0;showList()}else finish()}}
    private fun root(title:String,back:()->Unit):Pair<LinearLayout,LinearLayout>{val body=col(16).apply{setBackgroundColor(paper)};val bar=row();addIcon(bar,"返回上一级",R.drawable.ic_back,back);bar.addView(text(title,23f).apply{typeface=Typeface.DEFAULT_BOLD},LinearLayout.LayoutParams(0,-2,1f));body.addView(bar)
        window.setDecorFitsSystemWindows(false);window.statusBarColor=paper;window.navigationBarColor=paper;window.decorView.systemUiVisibility=if(night)0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val safe=FrameLayout(this).apply{setBackgroundColor(paper);addView(body,FrameLayout.LayoutParams(-1,-1));setOnApplyWindowInsetsListener{v,ins->val bars=ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout());val ime=ins.getInsets(WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,maxOf(bars.bottom,ime.bottom));ins}}
        setContentView(safe);safe.requestApplyInsets();return body to bar
    }
    private fun date(time:Long)=if(time==0L)"历史摘抄"else SimpleDateFormat("yyyy.MM.dd",Locale.CHINA).format(Date(time))
    private fun newExcerpt(){choose("新增摘抄",listOf("手动输入","粘贴文字","拍照","选择图片")){if(it==1)paste()else{edit(Excerpt(folder=folder.takeUnless{it=="*"}.orEmpty()));when(it){2->camera();3->gallery()}}}}
    private fun preserveScroll(){scrollY=listScroll?.scrollY?:scrollY}
    private fun showList(){savePending?.let{handler.removeCallbacks(it)};saveAction=null;current=null;stage="list";val title=if(trash)"拾墨 · 回收站"else store.folders(owner).firstOrNull{it.first==folder}?.second?:if(folder=="")"拾墨 · 未分类"else"拾墨"
        val(body,bar)=root(title){goBack()};addIcon(bar,"切换展示方式",R.drawable.ic_grid){choose("展示方式",listOf("标准卡片","紧凑列表","双列宫格")){viewMode=listOf("标准卡片","紧凑列表","双列宫格")[it];prefs.edit().putString("view",viewMode).apply();preserveScroll();showList()}};addIcon(bar,"更多操作",R.drawable.ic_more){more()}
        val searchRow=row().apply{background=shape(card,12);setPadding(dp(8),0,dp(4),0)}
        searchRow.addView(ImageView(this).apply{setImageResource(R.drawable.ic_search);imageTintList=android.content.res.ColorStateList.valueOf(muted)},LinearLayout.LayoutParams(dp(22),dp(22)))
        val search=EditText(this).apply{hint="搜索摘抄";contentDescription="搜索摘抄";setTextColor(ink);setHintTextColor(muted);textSize=15f;setSingleLine();background=null;setPadding(dp(8),0,dp(8),0);setText(query)}
        searchRow.addView(search,LinearLayout.LayoutParams(0,dp(44),1f));addIcon(searchRow,"筛选",R.drawable.ic_filter){filters()};addIcon(searchRow,"排序",R.drawable.ic_sort){choose("排序",listOf("最近收藏","最早收藏","最近修改")){sort=listOf("最近收藏","最早收藏","最近修改")[it];prefs.edit().putString("sort",sort).apply();scrollY=0;renderCards()}}
        body.addView(searchRow,LinearLayout.LayoutParams(-1,dp(44)).apply{topMargin=dp(12);bottomMargin=dp(12)})
        val folders=row();fun folderChip(id:String,name:String){val b=button(name){folder=id;scrollY=0;selected.clear();showList()};cn.jiangcheng.lemon.ui.ReaderControls.mark(b,folder==id,accent,ink);b.setOnDragListener{v,event->when(event.action){DragEvent.ACTION_DRAG_STARTED->event.localState is Set<*>;DragEvent.ACTION_DRAG_ENTERED->{v.alpha=.5f;true};DragEvent.ACTION_DRAG_EXITED,DragEvent.ACTION_DRAG_ENDED->{v.alpha=1f;true};DragEvent.ACTION_DROP->{v.alpha=1f;val ids=(event.localState as? Set<*>)?.filterIsInstance<String>().orEmpty();move(ids,id);true};else->true}};folders.addView(b,LinearLayout.LayoutParams(-2,dp(42)).apply{rightMargin=dp(6)})}
        folderChip("*","全部");folderChip("","未分类");store.folders(owner).forEach{folderChip(it.first,it.second)}
        val foldersBar=row();foldersBar.addView(HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;addView(folders)},LinearLayout.LayoutParams(0,dp(44),1f));addIcon(foldersBar,"新建文件夹",R.drawable.ic_add){input("新建文件夹"){store.folder(owner,it);showList()}};body.addView(foldersBar)
        if(book!="全部"||source!="全部"||labels.isNotEmpty()){val active=row();active.addView(text(listOfNotNull(book.takeUnless{it=="全部"},source.takeUnless{it=="全部"},labels.takeIf{it.isNotEmpty()}?.joinToString("、")).joinToString(" · "),12f,muted),LinearLayout.LayoutParams(0,-2,1f));addIcon(active,"清除筛选",R.drawable.ic_close){book="全部";source="全部";labels=emptySet();showList()};body.addView(active)}
        countLabel=text("",12f,muted);body.addView(countLabel)
        listBox=col();listScroll=ScrollView(this).apply{isFillViewport=false;isVerticalScrollBarEnabled=true;addView(listBox);setOnDragListener{_,event->if(event.action==DragEvent.ACTION_DRAG_ENDED)handler.post{if(stage=="list"&&selected.isNotEmpty()){preserveScroll();showList()}};true}};body.addView(listScroll,LinearLayout.LayoutParams(-1,0,1f))
        val actions=row()
        if(selected.isNotEmpty()){actions.addView(button("移动 (${selected.size})"){pickFolder{move(selected.toList(),it)}},LinearLayout.LayoutParams(0,dp(48),1f));actions.addView(button("标签"){tagPicker(selected.toList())},LinearLayout.LayoutParams(0,dp(48),1f));actions.addView(button(if(trash)"恢复"else"删除"){selected.toList().forEach{id->store.get(owner,id)?.let{it.deleted=!trash;store.save(owner,it)}};selected.clear();showList()},LinearLayout.LayoutParams(0,dp(48),1f));addIcon(actions,"取消选择",R.drawable.ic_close){selected.clear();showList()}}
        else if(!trash){actions.addView(button("新增摘抄"){newExcerpt()}.apply{val plus=getDrawable(R.drawable.ic_add)!!.mutate();plus.setTint(accent);plus.setBounds(0,0,dp(19),dp(19));setCompoundDrawablesRelative(plus,null,null,null);gravity=Gravity.START or Gravity.CENTER_VERTICAL;compoundDrawablePadding=dp(8)},LinearLayout.LayoutParams(-1,dp(48)))}
        body.addView(actions,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10)})
        search.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,a:Int,c:Int,f:Int){};override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){query=s.toString();listLimit=60;scrollY=0;handler.removeCallbacks(searchRun);handler.postDelayed(searchRun,220)};override fun afterTextChanged(e:Editable?) {}})
        renderCards();listScroll?.post{listScroll?.scrollTo(0,scrollY)}
    }
    private val searchRun=Runnable{if(stage=="list")renderCards()}
    private fun renderCards(){val box=listBox?:return;box.removeAllViews();val all=store.all(owner,trash).filter{e->(folder=="*"||e.folder==folder)&&(source=="全部"||e.source==source)&&(book=="全部"||if(book=="随笔散记")e.book.isBlank()else e.book==book)&&(labels.isEmpty()||if(anyTag)labels.any{it in e.tags}else labels.all{it in e.tags})&&(query.isBlank()||listOf(e.title,e.text,e.thought,e.book,e.author,e.ocr,e.tags.joinToString(" ")).any{it.contains(query,true)})}.let{items->when(sort){"最早收藏"->items.sortedBy{it.created};"最近修改"->items.sortedByDescending{it.updated};else->items.sortedByDescending{it.created}}}
        countLabel?.text="${all.size} 条${if(selected.isNotEmpty())" · 已选 ${selected.size}"else""}"
        if(all.isEmpty()){box.addView(text(if(trash)"回收站是空的"else"还没有摘抄",17f,muted).apply{gravity=Gravity.CENTER;setPadding(dp(10),dp(50),dp(10),dp(50))});return}
        val grid=viewMode=="双列宫格";var left=box;var right=box
        if(grid){val r=row().apply{gravity=Gravity.TOP};left=col();right=col();r.addView(left,LinearLayout.LayoutParams(0,-2,1f).apply{rightMargin=dp(5)});r.addView(right,LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(5)});box.addView(r)}
        all.take(listLimit).forEachIndexed{i,e->val target=if(grid&&i%2==1)right else left;val c=col(if(grid)12 else 16).apply{background=shape(if(e.id in selected)chip else card);isClickable=true;contentDescription=e.heading;setOnClickListener{if(selected.isNotEmpty()){if(!selected.add(e.id))selected.remove(e.id);preserveScroll();showList()}else{preserveScroll();edit(e)}}
                setOnLongClickListener{selected.add(e.id);startDragAndDrop(ClipData.newPlainText("摘抄",""),View.DragShadowBuilder(this),selected.toSet(),0);handler.post{if(stage=="list"){countLabel?.text="已选 ${selected.size} 条 · 拖到文件夹，或点击卡片菜单移动"}};true}}
            val heading=row().apply{gravity=Gravity.TOP};heading.addView(CheckBox(this).apply{contentDescription="选择摘抄";buttonTintList=android.content.res.ColorStateList.valueOf(accent);isChecked=e.id in selected;minWidth=0;minimumWidth=0;setPadding(0,0,0,0);setOnCheckedChangeListener{_,checked->if(checked)selected.add(e.id)else selected.remove(e.id);preserveScroll();showList()}},LinearLayout.LayoutParams(dp(32),dp(32)));heading.addView(text(if(e.title.isBlank()&&e.text.isNotBlank())e.text else e.heading,if(grid)16f else 18f).apply{typeface=if(e.title.isBlank())Typeface.DEFAULT else Typeface.DEFAULT_BOLD;maxLines=if(e.title.isNotBlank()||viewMode=="紧凑列表")2 else 5;ellipsize=TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,-2,1f));heading.addView(icon("摘抄操作",R.drawable.ic_more){cardMenu(e)},LinearLayout.LayoutParams(dp(36),dp(36)));c.addView(heading)
            if(e.images.isNotEmpty()&&viewMode!="紧凑列表")c.addView(image(e.images.first(),if(grid)120 else 150),LinearLayout.LayoutParams(-1,dp(if(grid)120 else 150)).apply{topMargin=dp(8);bottomMargin=dp(6)})
            val excerpt=if(e.title.isBlank())e.thought else e.text
            if(excerpt.isNotBlank())c.addView(text(excerpt,if(grid)14f else 16f,muted).apply{maxLines=if(viewMode=="紧凑列表")2 else if(grid)6 else 5;ellipsize=TextUtils.TruncateAt.END;setPadding(0,dp(6),0,dp(8))})
            if(e.tags.isNotEmpty())c.addView(text(e.tags.take(3).joinToString("  "){"#$it"}+if(e.tags.size>3)" +${e.tags.size-3}"else"",12f,accent).apply{maxLines=2;setOnClickListener{labels=setOf(e.tags.first());showList()}})
            c.addView(text(listOf(date(e.created),e.book.ifBlank{"随笔散记"},e.source).joinToString(" · "),11f,muted).apply{maxLines=2;setPadding(0,dp(10),0,0)})
            target.addView(c,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(9);bottomMargin=dp(3)})}
        if(all.size>listLimit)box.addView(button("加载更多"){val y=listScroll?.scrollY?:0;listLimit+=60;renderCards();listScroll?.post{listScroll?.scrollTo(0,y)}})
    }
    private fun paste(){val clip=getSystemService(android.content.ClipboardManager::class.java).primaryClip;if(clip==null||clip.itemCount==0){toast("剪贴板中没有文字");return};val value=clip.getItemAt(0).text?.toString()?.trim().orEmpty();if(value.isBlank()){toast("剪贴板中没有可摘抄的文字");return}
        fun add(){val e=Excerpt(text=value,source="粘贴",folder=folder.takeUnless{it=="*"}.orEmpty());store.save(owner,e);showList();val bar=SnackbarFallback(this,e);bar.show()}
        if(store.all(owner).any{it.source=="粘贴"&&it.text==value})choose("这段文字已经收藏",listOf("查看已有摘抄","仍然另存一条")){if(it==0)edit(store.all(owner).first{it.source=="粘贴"&&it.text==value})else add()}else add()
    }
    private inner class SnackbarFallback(val a:Activity,val e:Excerpt){fun show(){val content=findViewById<ViewGroup>(android.R.id.content);val hint=button("已收藏 · 撤销"){e.deleted=true;store.save(owner,e);showList()};val popup=PopupWindow(hint,dp(190),dp(48),false).apply{elevation=dp(6).toFloat()};content.post{if(!isFinishing){popup.showAtLocation(content,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,0,dp(100));handler.postDelayed({popup.dismiss()},4000)}}}}
    private fun move(ids:List<String>,to:String){if(to=="*"){toast("请拖到具体文件夹或未分类");return};ids.forEach{id->store.get(owner,id)?.let{it.folder=to;store.save(owner,it)}};selected.clear();showList();toast("已移动 ${ids.size} 条")}
    private fun pickFolder(done:(String)->Unit){val options=listOf("" to "未分类")+store.folders(owner);choose("移动到文件夹",options.map{it.second}){done(options[it].first)}}
    private fun cardMenu(e:Excerpt){val options=if(trash)listOf("恢复","彻底删除")else listOf("移动到…","删除")
        choose("摘抄操作",options){i->when(options[i]){"编辑"->{preserveScroll();edit(e)};"选择／取消选择"->{if(!selected.add(e.id))selected.remove(e.id);preserveScroll();showList()};"移动到…"->pickFolder{move(listOf(e.id),it)};"设置标签"->tagPicker(listOf(e.id));"复制文字"->copy(e.text);"删除"->{e.deleted=true;store.save(owner,e);showList()};"恢复"->{e.deleted=false;store.save(owner,e);showList()};"彻底删除"->confirm("彻底删除摘抄？","删除后无法恢复。"){store.purge(owner,e);showList()}}}}
    private fun copy(value:String){getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("拾墨",value));toast("已复制")}
    private fun filters(){val body=col();val books=listOf("全部","随笔散记")+store.all(owner).map{it.book}.filter{it.isNotBlank()}.distinct().sorted();val sources=listOf("全部","阅读","粘贴","拍照","相册","手动")
        fun spinner(label:String,values:List<String>,value:String):Spinner{body.addView(text(label));return Spinner(this).apply{adapter=ArrayAdapter(this@ShimoActivity,android.R.layout.simple_spinner_dropdown_item,values);setSelection(values.indexOf(value).coerceAtLeast(0));body.addView(this)}}
        val b=spinner("书籍来源",books,book);val s=spinner("导入方式",sources,source);body.addView(text("标签（可多选）"));val checks=store.tags(owner).map{tag->CheckBox(this).apply{text=tag;isChecked=tag in labels;body.addView(this)}};val any=Switch(this).apply{text="匹配任意一个标签";isChecked=anyTag;body.addView(this)}
        ReaderDialog.Builder(this).setTitle("筛选摘抄").setView(body).setPositiveButton("应用"){_,_->book=b.selectedItem.toString();source=s.selectedItem.toString();labels=checks.filter{it.isChecked}.map{it.text.toString()}.toSet();anyTag=any.isChecked;scrollY=0;showList()}.setNegativeButton("重置"){_,_->book="全部";source="全部";labels=emptySet();anyTag=false;query="";showList()}.show()
    }
    private fun tagPicker(ids:List<String>){val values=ids.mapNotNull{store.get(owner,it)};val body=col();val checks=store.tags(owner).map{tag->CheckBox(this).apply{text=tag;isChecked=values.all{tag in it.tags};body.addView(this)}};val fresh=EditText(this).apply{hint="新标签，以逗号分隔";body.addView(this)}
        ReaderDialog.Builder(this).setTitle("设置标签").setView(body).setPositiveButton("保存"){_,_->val tags=(checks.filter{it.isChecked}.map{it.text.toString()}+fresh.text.toString().split(Regex("[,，、\n]+"))).map{it.trim().take(30)}.filter{it.isNotBlank()}.distinct().take(30);values.forEach{it.tags=tags;store.save(owner,it)};current?.let{e->edit(store.get(owner,e.id)?:e)}?:showList()}.setNegativeButton("取消",null).show()
    }
    private fun more(){choose("拾墨",listOf("管理文件夹","管理标签","收录已有摘抄","自动收录设置","回收站","导出拾墨备份","导入拾墨备份")){when(it){0->manageFolders();1->manageTags();2->collectHistory();3->{val enabled=prefs.getBoolean("auto",true);choose("自动收录划线和札记 · ${if(enabled)"已开启"else"已关闭"}",listOf("开启","关闭")){prefs.edit().putBoolean("auto",it==0).apply()}};4->{trash=true;selected.clear();showList()};5->startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{type="application/zip";addCategory(Intent.CATEGORY_OPENABLE);putExtra(Intent.EXTRA_TITLE,"拾墨备份-${SimpleDateFormat("yyyyMMdd-HHmm",Locale.CHINA).format(Date())}.zip")},613);6->startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="*/*";addCategory(Intent.CATEGORY_OPENABLE)},614)}}}
    private fun manageFolders(){val fs=store.folders(owner);choose("管理文件夹",listOf("＋ 新建文件夹")+fs.map{it.second}){if(it==0)input("新建文件夹"){name->store.folder(owner,name);showList()}else{val f=fs[it-1];choose(f.second,listOf("重命名","删除文件夹")){if(it==0)input("重命名文件夹",f.second){name->store.folder(owner,name,f.first);showList()}else confirm("删除文件夹？","摘抄将移回未分类，内容会保留。"){store.removeFolder(owner,f.first);if(folder==f.first)folder="";showList()}}}}}
    private fun manageTags(){val tags=store.tags(owner);if(tags.isEmpty()){toast("在摘抄中添加标签后，可在这里管理");return};choose("管理标签",tags){i->val tag=tags[i];choose(tag,listOf("重命名","删除标签")){if(it==0)input("重命名标签",tag){store.renameTag(owner,tag,it);labels=emptySet();showList()}else confirm("删除标签？","只移除标签，摘抄内容会保留。"){store.renameTag(owner,tag,"");labels=labels-tag;showList()}}}}
    private fun collectHistory(){val library=Library(this);val books=library.all(owner,false).filter{b->b.notes.any{it.quote.isNotBlank()&&it.type in listOf("划线","札记")}};if(books.isEmpty()){library.close();toast("暂时没有可收录的划线或札记");return};val body=col();val checks=books.map{b->CheckBox(this).apply{text=b.title;isChecked=true;body.addView(this)}}
        ReaderDialog.Builder(this).setTitle("收录已有摘抄").setView(body).setPositiveButton("收录"){_,_->val chosen=books.filterIndexed{i,_->checks[i].isChecked};work("正在收录…",{var n=0;chosen.forEach{b->b.notes.forEach{if(store.collect(owner,b,it,true))n++}};n}){n->showList();toast("已收录 $n 条，重复内容已跳过")}}.setNegativeButton("取消",null).show();library.close()
    }
    fun edit(e:Excerpt){savePending?.let{handler.removeCallbacks(it)};current=e;stage="edit";saveAction=null
        val(body,bar)=root("摘抄"){goBack()}
        if(e.bookId.isNotBlank())addIcon(bar,"定位原文",R.drawable.ic_locate){saveAction?.invoke();locate(e)}
        addIcon(bar,"移动到文件夹",R.drawable.ic_folder){saveAction?.invoke();pickFolder{e.folder=it;if(e.nonempty)store.save(owner,e);edit(e)}}
        addIcon(bar,"更多",R.drawable.ic_more){saveAction?.invoke();choose("摘抄",listOf("复制","分享","删除")){when(it){0->copy(e.text);1->startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,e.text+if(e.book.isBlank())""else"\n——《${e.book}》")},"分享摘抄"));2->{if(e.nonempty){e.deleted=true;store.save(owner,e)};showList()}}}}
        val content=col();val scroll=ScrollView(this).apply{addView(content)};body.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        fun field(hint:String,value:String,multi:Boolean=false,size:Float=16f):EditText=EditText(this).apply{this.hint=hint;contentDescription=hint;textSize=size;setTextColor(ink);setHintTextColor(muted);background=null;setPadding(dp(4),dp(14),dp(4),dp(14));inputType=android.text.InputType.TYPE_CLASS_TEXT or if(multi)android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0;setSingleLine(!multi);if(multi){minLines=6;gravity=Gravity.TOP};setText(value)}
        val meta=row();val bookField=field("书名",e.book);val page=field("页数",e.page);meta.addView(bookField,LinearLayout.LayoutParams(0,-2,3f));meta.addView(page,LinearLayout.LayoutParams(0,-2,1f));content.addView(meta)
        content.addView(View(this).apply{setBackgroundColor(chip)},LinearLayout.LayoutParams(-1,dp(1)))
        val value=field("摘抄内容",e.text,true,18f);content.addView(value,LinearLayout.LayoutParams(-1,-2))
        if(e.thought.isNotBlank())content.addView(text(e.thought,14f,muted).apply{setPadding(dp(4),dp(8),dp(4),dp(12))})
        val tags=col();content.addView(tags)
        fun paintTags(){tags.removeAllViews();val line=row();e.tags.forEach{t->line.addView(button("#$t"){choose(t,listOf("移除此标签")){e.tags=e.tags-t;saveAction?.invoke();paintTags()}},LinearLayout.LayoutParams(-2,dp(36)).apply{rightMargin=dp(5)})};addIcon(line,"添加标签",R.drawable.ic_add){input("添加标签"){t->if(t.isNotBlank()){e.tags=(e.tags+t.take(30)).distinct().take(30);saveAction?.invoke();paintTags()}}};tags.addView(HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;addView(line)})};paintTags()
        imagesBox=col();content.addView(imagesBox);renderImages(e)
        val actions=row();addIcon(actions,"添加图片",R.drawable.ic_image){saveAction?.invoke();choose("添加图片",listOf("拍照","相册")){if(it==0)camera()else gallery()}}
        actions.addView(button("完成"){saveAction?.invoke();if(!e.nonempty)toast("请输入内容或添加图片")else if(saveError!=null)message("保存未完成",saveError!!)else showList()},LinearLayout.LayoutParams(0,dp(48),1f));body.addView(actions)
        saveAction={saveError=null;e.text=value.text.toString();e.book=bookField.text.toString().take(250);e.page=page.text.toString().take(60);if(e.nonempty){try{val previous=store.get(owner,e.id);if(previous==null||previous.json().toString()!=e.json().toString())store.save(owner,e)}catch(ex:Exception){saveError=ex.message?:"请重试"}}}
        fun schedule(){savePending?.let{handler.removeCallbacks(it)};savePending=Runnable{saveAction?.invoke()};handler.postDelayed(savePending!!,350)}
        listOf(value,bookField,page).forEach{it.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,a:Int,c:Int,f:Int){};override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){schedule()};override fun afterTextChanged(e:Editable?) {}})}
    }
    private fun thumbnail(name:String,maxSize:Int=600):Bitmap?{val file=store.media(name);val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,bounds);val options=BitmapFactory.Options().apply{inSampleSize=1;while(bounds.outWidth/inSampleSize>maxSize||bounds.outHeight/inSampleSize>maxSize)inSampleSize*=2};val b=BitmapFactory.decodeFile(file.path,options)?:return null
        val orientation=runCatching{android.media.ExifInterface(file.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1)}.getOrDefault(1);val matrix=Matrix();when(orientation){2->matrix.setScale(-1f,1f);3->matrix.setRotate(180f);4->matrix.setScale(1f,-1f);5->{matrix.setRotate(90f);matrix.postScale(-1f,1f)};6->matrix.setRotate(90f);7->{matrix.setRotate(270f);matrix.postScale(-1f,1f)};8->matrix.setRotate(270f)};return if(matrix.isIdentity)b else Bitmap.createBitmap(b,0,0,b.width,b.height,matrix,true)
    }
    private fun image(name:String,height:Int)=ImageView(this).apply{contentDescription="摘抄图片";scaleType=ImageView.ScaleType.FIT_CENTER;adjustViewBounds=true;background=shape(chip);setImageBitmap(runCatching{thumbnail(name)}.getOrNull());minimumHeight=dp(height)}
    private fun renderImages(e:Excerpt){val box=imagesBox?:return;box.removeAllViews();e.images.forEach{name->val img=image(name,190);img.setOnClickListener{showImage(name)};box.addView(img,LinearLayout.LayoutParams(-1,dp(190)).apply{topMargin=dp(12)});val r=row();r.addView(button("识别并摘录文字"){saveAction?.invoke();ocr(e,name)},LinearLayout.LayoutParams(0,dp(44),1f));addIcon(r,"移除图片",R.drawable.ic_close){confirm("移除这张图片？","已摘录的文字会保留。"){if(e.images.size==1&&e.text.isBlank()){message("需要保留摘抄内容","这条摘抄只有一张图片，可通过笔记菜单删除整条摘抄。")}else{e.images=e.images-name;store.save(owner,e);edit(e)}}};box.addView(r)}}
    private fun showImage(name:String){val d=android.app.Dialog(this);val body=col(12).apply{setBackgroundColor(paper)};val scroll=ScrollView(this);val img=image(name,200).apply{setImageBitmap(thumbnail(name,2000))};scroll.addView(img,ViewGroup.LayoutParams(-1,-2));body.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));body.addView(button("关闭图片"){d.dismiss()});d.setContentView(body);d.show();d.window?.setLayout(-1,(resources.displayMetrics.heightPixels*.85).toInt())}
    private fun gallery(){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="image/*";addCategory(Intent.CATEGORY_OPENABLE);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},611)}
    private fun camera(){val dir=File(cacheDir,"shimo-capture").apply{mkdirs()};val file=File(dir,"${UUID.randomUUID()}.jpg");capture=file.name;val uri=FileProvider.getUriForFile(this,"$packageName.readerlogs",file)
        try{startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply{putExtra(MediaStore.EXTRA_OUTPUT,uri);clipData=ClipData.newRawUri("拍照",uri);addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)},610)}catch(e:ActivityNotFoundException){capture=null;toast("没有可用相机，请从相册选择")}}
    @Deprecated("Platform callback")override fun onActivityResult(request:Int,result:Int,data:Intent?){super.onActivityResult(request,result,data);if(result!=RESULT_OK){if(request==610){capture?.let{File(cacheDir,"shimo-capture/$it").delete()};capture=null};return}
        when(request){610->{val file=capture?.let{File(cacheDir,"shimo-capture/$it")};capture=null;if(file!=null&&file.isFile&&file.length()>0)addImage(Uri.fromFile(file),"拍照",file)};611->data?.data?.let{addImage(it,"相册")};613->data?.data?.let{uri->work("正在导出…",{contentResolver.openOutputStream(uri)!!.use{store.export(owner,it)};true}){toast("拾墨备份已导出")}};614->data?.data?.let{uri->work("正在导入…",{contentResolver.openInputStream(uri)!!.use{store.import(owner,it)}}){n->showList();toast("已导入 $n 条，已有条目保留")}}}
    }
    private fun addImage(uri:Uri,origin:String,captureFile:File?=null){val e=current?:Excerpt();work("正在准备图片…",{
        val temp=File(cacheDir,"shimo-${UUID.randomUUID()}.image");try{contentResolver.openInputStream(uri)!!.use{input->temp.outputStream().use{out->var total=0;val b=ByteArray(32768);while(true){val n=input.read(b);if(n<0)break;total+=n;require(total<=30_000_000){"图片超过30MB"};out.write(b,0,n)}}};val opts=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(temp.path,opts);require(opts.outWidth>0&&opts.outHeight>0){"无法读取这张图片"};val ext=when(opts.outMimeType){"image/png"->"png";"image/webp"->"webp";"image/jpeg"->"jpg";else->throw IllegalArgumentException("请使用 JPG、PNG 或 WebP 图片")};val name="${UUID.randomUUID()}.$ext";temp.copyTo(store.media(name));name}finally{temp.delete();captureFile?.delete()}
    }){result->val name=result as String;val body=col();body.addView(image(name,210),LinearLayout.LayoutParams(-1,dp(210)));var kept=false
        fun keep(recognize:Boolean){if(e.images.size>=20){store.media(name).delete();toast("每条最多20张图片");return};kept=true;e.images=e.images+name;if(e.source=="手动")e.source=origin;store.save(owner,e);edit(e);if(recognize)ocr(e,name)}
        val dialog=ReaderDialog.Builder(this).setTitle("保存这张书页").setView(body).setPositiveButton("保存图片",null).setNegativeButton("识字并摘录",null).show();dialog.getButton(-1).setOnClickListener{guard{keep(false);dialog.dismiss()}};dialog.getButton(-2).setOnClickListener{guard{keep(true);dialog.dismiss()}};dialog.setOnDismissListener{if(!kept)store.media(name).delete()}
    }}
    private fun ocr(e:Excerpt,name:String){if(busy){toast("正在处理，请稍候");return};busy=true;toast("正在本地识字…");val client=runCatching{com.google.mlkit.common.MlKit.initialize(applicationContext);recognizer?:TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()).also{recognizer=it}}.getOrElse{busy=false;message("识字暂未完成",it.message.orEmpty());return}
        val input=runCatching{InputImage.fromFilePath(this,Uri.fromFile(store.media(name)))}.getOrElse{busy=false;message("识字失败",it.message.orEmpty());return}
        client.process(input).addOnSuccessListener{result->busy=false;if(isFinishing||isDestroyed)return@addOnSuccessListener;val words=result.text;if(words.isBlank()){message("未识别到文字","图片已保留，可以换一张更清晰的书页重试。");return@addOnSuccessListener}
            saveAction?.invoke();val fresh=store.get(owner,e.id)?:e;fresh.ocr=(fresh.ocr+"\n"+words).trim().take(100000);store.save(owner,fresh);e.ocr=fresh.ocr
            val body=col();body.addView(text("长按选择需要的段落，可修改错字；不选择则保存全部文字。",14f,muted));val field=EditText(this).apply{setText(words);inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE;minLines=5;maxLines=12;gravity=Gravity.TOP;setTextIsSelectable(true);body.addView(this)}
            ReaderDialog.Builder(this).setTitle("选择摘抄文字").setView(body).setPositiveButton("保存摘录"){_,_->val a=field.selectionStart.coerceAtLeast(0);val b=field.selectionEnd.coerceAtLeast(0);val chosen=if(a!=b)field.text.substring(minOf(a,b),maxOf(a,b))else field.text.toString();saveAction?.invoke();val latest=store.get(owner,e.id)?:fresh;latest.text=(latest.text+"\n"+chosen).trim();store.save(owner,latest);edit(latest)}.setNegativeButton("仅保留图片",null).show()
        }.addOnFailureListener{busy=false;if(!isFinishing)message("识字暂未完成","图片已保留。${it.message.orEmpty()}")}
    }
    private fun <T> work(label:String,task:()->T,done:(T)->Unit){if(busy){toast("正在处理，请稍候");return};busy=true;toast(label);io.execute{val result=runCatching(task);runOnUiThread{busy=false;if(!isFinishing&&!isDestroyed)result.fold({guard{done(it)}},{message("操作未完成",it.message?:"请重试")})}}}
    private fun locate(e:Excerpt){val library=Library(this);val b=library.get(owner,e.bookId);library.close();if(b==null||b.localRemoved){message("原书暂不可用","摘抄仍保存在拾墨中。请先在书架恢复原书。");return};val quote=e.original.ifBlank{e.text};if(quote.isBlank())return
        var ch=e.chapter;var at=e.start
        if(b.chapters.getOrNull(ch)?.text?.let{at>=0&&at+quote.length<=it.length&&it.regionMatches(at,quote,0,quote.length)}!=true){val hits=mutableListOf<Pair<Int,Int>>();b.chapters.forEachIndexed{i,c->var p=c.text.indexOf(quote);while(p>=0&&hits.size<2){hits.add(i to p);p=c.text.indexOf(quote,p+1)}};if(hits.size!=1){message("原文位置待确认","原书内容已变化或出现多处相同文字，摘抄快照已保留。");return};ch=hits[0].first;at=hits[0].second}
        stage="source";saveAction=null;val(body,_)=root(b.chapters[ch].title){edit(e)};val scroll=ScrollView(this);val full=SpannableString(b.chapters[ch].text).apply{setSpan(BackgroundColorSpan(0x66c3a15d),at,at+quote.length,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)};val t=text("",20f).apply{text=full;setTextIsSelectable(true);setPadding(dp(12),dp(18),dp(12),dp(18));setLineSpacing(dp(10).toFloat(),1.15f)};scroll.addView(t);body.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));body.addView(button("返回摘抄"){edit(e)});t.post{val layout=t.layout?:return@post;scroll.scrollTo(0,(layout.getLineTop(layout.getLineForOffset(at))-dp(50)).coerceAtLeast(0))}
    }
}
