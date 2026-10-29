package cn.jiangcheng.lemon.reader

import android.app.Dialog
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import cn.jiangcheng.lemon.MainActivity
import cn.jiangcheng.lemon.R
import cn.jiangcheng.lemon.data.Book
import cn.jiangcheng.lemon.ui.ReaderControls
import cn.jiangcheng.lemon.ui.ReaderDialog

/** The controls stay mounted while results refresh; typing is never interrupted by background work. */
class ChroniclePanel(private val app:MainActivity,private val book:Book,private val owner:String,
    private val host:Dialog,body:LinearLayout,private val boundary:()->Position,
    private val ink:Int,private val accent:Int,private val night:Boolean,initialFilter:String,
    private val save:()->Unit,private val edit:(Chronicle,()->Unit)->Unit,private val onFilter:(String)->Unit) {
    private var kind=initialFilter
    private var order=app.prefs.getString("chronicle_sort:$owner","首次出现")!!
    private var data=emptyList<ChronicleCatalog.Item>()
    private var generation=0
    private var limit=40
    private val search=app.field("搜索人物、别名或书志内容").apply{setTextColor(ink);setSingleLine(true);textSize=14f}
    private val status=label("正在整理列表…",12f)
    private val list=app.column(0).apply{background=null}
    private val tabs=mutableListOf<TextView>()
    private fun label(text:String,size:Float=15f)=app.text(text,size).apply{setTextColor(ink)}
    private fun action(text:String,run:()->Unit)=app.button(text,run).apply{setTextColor(accent);background=null;minHeight=app.dp(48)}
    init {
        val filters=app.row()
        for(value in listOf("全部","人物","时间线","线索")){
            val tab=ReaderControls.tab(app,value,value==kind,accent,ink){kind=value;limit=40;onFilter(value);tabs.forEach{ReaderControls.mark(it,it.text==kind,accent,ink)};render()}
            tabs.add(tab);filters.addView(tab,LinearLayout.LayoutParams(0,app.dp(48),1f))
        }
        body.addView(filters);body.addView(search)
        val options=app.row().apply{setPadding(0,app.dp(4),0,app.dp(4))}
        status.maxLines=1;status.ellipsize=android.text.TextUtils.TruncateAt.END
        options.addView(status,LinearLayout.LayoutParams(0,-2,1f))
        fun tool(title:String,icon:Int,leading:Boolean,run:()->Unit)=TextView(app).apply{
            text=title;textSize=13f;setTextColor(accent);gravity=android.view.Gravity.CENTER;maxLines=1
            setPadding(app.dp(6),0,app.dp(6),0);isClickable=true;isFocusable=true
            background=ReaderControls.ripple(android.graphics.Color.TRANSPARENT,accent,app.dp(8).toFloat())
            val image=app.getDrawable(icon)!!.mutate().apply{setTint(accent);setBounds(0,0,app.dp(if(leading)16 else 12),app.dp(if(leading)16 else 12))}
            setCompoundDrawablesRelative(if(leading)image else null,null,if(leading)null else image,null);compoundDrawablePadding=app.dp(4)
            setOnClickListener{run()}
        }
        val merging=tool("检测合并",R.drawable.ic_merge,true){detectAliases()}.apply{contentDescription="检测人物别名并合并"}
        options.addView(merging,LinearLayout.LayoutParams(-2,app.dp(44)).apply{marginEnd=app.dp(8)})
        val sorting=tool(order,R.drawable.ic_down,false){};sorting.contentDescription="书志排序"
        sorting.setOnClickListener{app.choose("书志排序",ChronicleCatalog.orders){order=it;sorting.text=it;app.prefs.edit().putString("chronicle_sort:$owner",it).apply();limit=40;render()}}
        options.addView(sorting,LinearLayout.LayoutParams(-2,app.dp(44)));body.addView(options);body.addView(list)
        search.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,a:Int,c:Int,f:Int){};override fun afterTextChanged(e:Editable?){};override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){limit=40;render()}})
        val old=book.chronicles.filter{!it.deleted&&it.revision!=book.revision}
        if(old.isNotEmpty())body.addView(action("旧版资料 · ${old.size} 条"){
            val col=app.column(0);old.forEach{e->col.addView(label(e.title,17f));col.addView(label(e.content,14f));if(e.evidence.isNotBlank())col.addView(label(e.evidence,13f).apply{alpha=.65f})}
            ReaderDialog.Builder(app).setTitle("旧版资料 · 位置待确认").setView(col).setPositiveButton("关闭",null).show()
        })
        refresh()
    }
    fun refresh(){
        val version=++generation;val records=book.chronicles.toList();val chapters=book.chapters.toList();val end=boundary();val revision=book.revision
        app.worker.execute{val result=runCatching{ChronicleCatalog.items(records,chapters,end,revision)}
            app.runOnUiThread{if(version==generation&&!app.isDestroyed&&host.isShowing){result.onSuccess{data=it;render()}.onFailure{status.text="列表读取失败，请重新打开"}}}}
    }
    private fun render(){
        val items=ChronicleCatalog.sort(data,order,search.text.toString(),kind)
        status.text="${items.size} 项";list.removeAllViews()
        if(items.isEmpty())list.addView(label(if(search.text.isNotBlank())"没有找到相关资料"else"故事里的发现，会留在这里",15f).apply{alpha=.6f;setPadding(0,app.dp(24),0,app.dp(24))})
        for(item in items.take(limit)){
            val e=item.entry;val card=app.column(14).apply{background=android.graphics.drawable.GradientDrawable().apply{setColor(if(night)0xff34362f.toInt()else 0xfff7f5ed.toInt());cornerRadius=app.dp(12).toFloat()}}
            list.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=app.dp(12)})
            val top=app.row();top.addView(label(e.kind+if(e.kind=="人物")" · 提及 ${item.frequency} 次"else"",12f).apply{alpha=.65f},LinearLayout.LayoutParams(0,-2,1f))
            top.addView(app.iconAction("",R.drawable.ic_more){actions(item)}.apply{contentDescription="${e.title} · 操作";compoundDrawablesRelative.filterNotNull().forEach{it.setTint(accent)}},LinearLayout.LayoutParams(app.dp(48),app.dp(48)));card.addView(top)
            card.addView(label(item.title,19f).apply{typeface=Typeface.DEFAULT_BOLD})
            if(item.names.size>1)card.addView(label("别名："+item.names.filter{it!=item.title}.joinToString("、"),12f).apply{alpha=.65f})
            card.addView(label(item.content,15f).apply{maxLines=5;ellipsize=android.text.TextUtils.TruncateAt.END;setLineSpacing(app.dp(3).toFloat(),1f)})
            card.addView(label("初见 第${item.first.chapter+1}章 · 最近 第${item.last.chapter+1}章",12f).apply{alpha=.6f})
            card.setOnClickListener{detail(item)}
        }
        if(items.size>limit)list.addView(action("继续显示（还有 ${items.size-limit} 项）"){limit+=40;render()})
    }
    private fun detail(item:ChronicleCatalog.Item){
        val col=app.column(0)
        item.records.forEach{e->col.addView(label("第${e.chapter+1}章 · ${e.title}",14f).apply{alpha=.65f});col.addView(label(e.content).apply{setTextIsSelectable(true)})
            if(e.evidence.isNotBlank())col.addView(label(e.evidence,13f).apply{alpha=.7f;setTextIsSelectable(true);setPadding(0,app.dp(8),0,app.dp(20))})}
        ReaderDialog.Builder(app).setTitle(item.title).setView(col).setPositiveButton("关闭",null).show()
    }
    private fun actions(item:ChronicleCatalog.Item){
        val options=mutableListOf("查看出处","编辑当前记录")
        if(item.entry.kind=="人物")options.add("合并同一人物")
        options.add("删除此项")
        app.choose(item.names.joinToString(" / "),options){when(it){
            "查看出处"->detail(item)
            "编辑当前记录"->edit(item.entry){save();refresh()}
            "合并同一人物"->mergePicker(item)
            "删除此项"->{book.chronicles=ChronicleCatalog.delete(book.chronicles,item);save();refresh()}
        }}
    }
    private fun detectAliases(){
        val matches=CharacterAliases.suggestions(data)
        if(matches.isEmpty()){app.message("检测合并","当前已读资料中没有发现新的别名候选。也可以从人物卡片的操作菜单手动合并。");return}
        val col=app.column(0)
        col.addView(label("选择同一人物的称呼，合并后保留各条原文和记录。",14f))
        val chosen=linkedSetOf<CharacterAliases.Suggestion>()
        for(match in matches){
            val check=CheckBox(app).apply{text="${match.full.title} ← ${match.short.title}";textSize=16f;setTextColor(ink);minHeight=app.dp(48)
                setOnCheckedChangeListener{_,value->if(value)chosen.add(match)else chosen.remove(match)}}
            col.addView(check);col.addView(label(match.reason,12f).apply{alpha=.65f})
            col.addView(label(match.full.content.take(140)+"\n"+match.short.content.take(140),13f).apply{setPadding(0,0,0,app.dp(14))})
        }
        val dialog=ReaderDialog.Builder(app).setTitle("检测人物别名 · ${matches.size} 组").setView(col).setPositiveButton("合并所选",null).setNegativeButton("取消",null).show()
        dialog.getButton(-1).setOnClickListener{
            if(chosen.isEmpty()){Toast.makeText(app,"请先选择要合并的人物",Toast.LENGTH_SHORT).show();return@setOnClickListener}
            var records=book.chronicles
            // Re-read groups after every merge so chains of aliases use one persistent identity.
            for(match in chosen){
                val groups=ChronicleCatalog.items(records,emptyList(),boundary(),book.revision)
                val first=groups.firstOrNull{g->g.records.any{e->match.full.records.any{it.id==e.id}}}
                val second=groups.firstOrNull{g->g.records.any{e->match.short.records.any{it.id==e.id}}}
                if(first!=null&&second!=null&&first.key!=second.key)records=ChronicleCatalog.merge(records,first,second)
            }
            book.chronicles=records;save();dialog.dismiss();refresh()
        }
    }
    private fun mergePicker(source:ChronicleCatalog.Item){
        val targets=data.filter{it.key!=source.key&&it.entry.kind=="人物"}
        if(targets.isEmpty()){app.message("合并人物","暂时没有其他可合并的人物。");return}
        val col=app.column(0);val query=app.field("搜索要合并的人物");col.addView(query);val rows=app.column(0);col.addView(rows)
        var dialog:Dialog?=null
        fun paint(){rows.removeAllViews();val found=targets.filter{it.names.any{n->n.contains(query.text.toString(),true)}};found.take(60).forEach{target->rows.addView(action(target.names.joinToString(" / ")){
            ReaderDialog.Builder(app).setTitle("确认是同一人物").setMessage("${source.names.joinToString(" / ")} 与 ${target.names.joinToString(" / ")}\n合并显示，保留双方的原文和记录。")
                .setPositiveButton("合并"){_,_->book.chronicles=ChronicleCatalog.merge(book.chronicles,source,target);save();dialog?.dismiss();refresh()}.setNegativeButton("取消",null).show()
        })};if(found.size>60)rows.addView(label("请搜索以缩小范围",12f))}
        query.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,a:Int,c:Int,f:Int){};override fun afterTextChanged(e:Editable?){};override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){paint()}});paint()
        dialog=ReaderDialog.Builder(app).setTitle("合并同一人物").setView(col).setNegativeButton("取消",null).show()
    }
}
