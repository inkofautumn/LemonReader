package cn.jiangcheng.lemon.reader

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import cn.jiangcheng.lemon.MainActivity
import cn.jiangcheng.lemon.R
import cn.jiangcheng.lemon.data.ModelHttp
import cn.jiangcheng.lemon.data.Secrets
import cn.jiangcheng.lemon.ui.ReaderDialog
import org.json.*
import java.util.UUID

object ModelProfiles {
    fun profiles(context:Context,owner:String):JSONArray {
        val prefs=context.getSharedPreferences("app",0)
        val saved=prefs.getString("models:$owner",null)
        if(saved!=null)return runCatching{JSONArray(saved)}.getOrDefault(JSONArray())
        val secrets=Secrets(context);val key=secrets.get("ai_key:$owner")
        if(key.isBlank())return JSONArray()
        val base=prefs.getString("ai_base:$owner","https://api.deepseek.com")!!
        val list=JSONArray().put(JSONObject().put("id","legacy").put("name","已有模型").put("provider",ModelProtocol.provider(base)).put("base",base).put("models",prefs.getString("ai_model:$owner","").orEmpty()))
        secrets.set("model-key:$owner:legacy",key);prefs.edit().putString("models:$owner",list.toString()).apply();return list
    }
    fun config(context:Context,owner:String,p:JSONObject,model:String,task:String="日常处理",thinking:String="off"):AiConfig {
        val provider=p.optString("provider","custom")
        return AiConfig(p.optString("base"),model,Secrets(context).get("model-key:$owner:${p.getString("id")}"),provider,thinking,context.applicationContext,owner,task,
            budget=context.getSharedPreferences("app",0).getInt("ai_budget:$owner",100000),
            reasoningProtocol=p.optString("reasoning_protocol",if(provider in listOf("deepseek","qwen"))provider else "none"),
            jsonOutput=p.optBoolean("json_output",provider in listOf("deepseek","qwen")),
            streamUsage=p.optBoolean("stream_usage",provider in listOf("deepseek","qwen")),
            tokenField=p.optString("token_field","max_tokens"))
    }
    fun resolve(context:Context,owner:String,group:String="daily",task:String="日常处理"):AiConfig {
        val prefs=context.getSharedPreferences("app",0);val list=profiles(context,owner)
        val actualGroup=if(!prefs.contains("route:$owner:$group"))when(group){"record"->"daily";"helper"->"deep";else->group}else group
        val id=prefs.getString("route:$owner:$actualGroup","")
        val p=(0 until list.length()).map{list.getJSONObject(it)}.firstOrNull{it.optString("id")==id}?:list.optJSONObject(0)
            ?:return AiConfig("","","",context=context,owner=owner,task=task)
        val names=ModelProtocol.models(p.optString("models"))
        val model=prefs.getString("route-model:$owner:$actualGroup",null)?.takeIf{it in names}?:names.firstOrNull().orEmpty()
        return config(context,owner,p,model,task,prefs.getString("thinking:$owner:$actualGroup","off")!!)
    }
    fun available(context:Context,owner:String)=resolve(context,owner).let{it.key.isNotBlank()&&it.model.isNotBlank()}
    fun assignQuickRoutes(context:Context,owner:String){
        val list=profiles(context,owner);val p=list.optJSONObject(0)?:return
        val prefs=context.getSharedPreferences("app",0);val edit=prefs.edit()
        for(group in listOf("daily","deep","record"))if(!prefs.contains("route:$owner:$group"))edit.putString("route:$owner:$group",p.getString("id")).putString("route-model:$owner:$group",ModelProtocol.models(p.optString("models")).firstOrNull().orEmpty())
        edit.apply()
    }
    fun quickSetup(app:MainActivity,onDone:()->Unit):android.app.Dialog =
        ReaderDialog.Builder(app).setTitle("配置 AI 模型").setItems(ModelProtocol.presets.map{it.name}.toTypedArray()){_,i->edit(app,newProfile(ModelProtocol.presets[i]),onDone)}.setNegativeButton("稍后配置",null).show()
    private fun newProfile(p:ModelProtocol.Preset)=JSONObject().put("id",UUID.randomUUID().toString()).put("name",p.name).put("provider",p.id).put("base",p.base).put("models",p.models)
    private fun section(app:MainActivity,body:LinearLayout,title:String):LinearLayout {
        body.addView(app.text(title,15f).apply{alpha=.7f;setPadding(0,app.dp(20),0,app.dp(8))})
        return app.column(12).apply{background=app.button(""){}.background;body.addView(this,LinearLayout.LayoutParams(-1,-2))}
    }
    private fun link(app:MainActivity,box:LinearLayout,title:String,detail:String="",action:()->Unit) {
        val row=app.row().apply{minimumHeight=app.dp(56);setPadding(app.dp(4),app.dp(5),app.dp(4),app.dp(5));isClickable=true;isFocusable=true;contentDescription=title;setOnClickListener{action()}}
        val col=LinearLayout(app).apply{orientation=LinearLayout.VERTICAL}
        col.addView(app.text(title,16f).apply{setPadding(0,app.dp(3),0,app.dp(3))})
        if(detail.isNotBlank())col.addView(app.text(detail,13f).apply{alpha=.68f;maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,app.dp(2),0,app.dp(4))})
        row.addView(col,LinearLayout.LayoutParams(0,-2,1f));row.addView(app.iconAction("",R.drawable.ic_down,action).apply{rotation=-90f;contentDescription=title},LinearLayout.LayoutParams(app.dp(36),app.dp(44)));box.addView(row)
    }
    fun show(app:MainActivity,afterSave:(()->Unit)?=null) {
        val owner=app.profileId;val body=app.surface("模型与 AI",back={app.showSettings()});val list=profiles(app,owner)
        val connections=section(app,body,"模型连接")
        for(i in 0 until list.length()){val p=list.getJSONObject(i);link(app,connections,p.optString("name"),ModelProtocol.models(p.optString("models")).joinToString(" · ")){edit(app,p,afterSave)}}
        connections.addView(app.iconAction("添加模型 API",R.drawable.ic_add){app.choose("选择服务商",ModelProtocol.presets.map{it.name}){name->edit(app,newProfile(ModelProtocol.presets.first{it.name==name}),afterSave)}})
        if(list.length()>0){
            val routes=section(app,body,"任务分配")
            for((group,label) in listOf("daily" to "目录与标题","deep" to "说书人与检索","helper" to "对话检索辅助","record" to "自动书志")){
                val cfg=resolve(app,owner,group)
                link(app,routes,label,if(group=="helper"&&!app.prefs.contains("route:$owner:helper"))"随对话模型"else cfg.model.ifBlank{"选择模型"}){
                    val options=(0 until list.length()).flatMap{i->val p=list.getJSONObject(i);ModelProtocol.models(p.optString("models")).map{Triple(p.getString("id"),it,p.optString("name"))}}
                    app.choose("选择任务模型",options.mapIndexed{i,v->"${i+1}. ${v.third} · ${v.second}"}){v->val selected=options[v.substringBefore('.').toInt()-1];app.prefs.edit().putString("route:$owner:$group",selected.first).putString("route-model:$owner:$group",selected.second).putString("thinking:$owner:$group","off").apply();show(app,afterSave)}
                }
                if(cfg.reasoningProtocol!="none")link(app,routes,"$label · 思考",when(cfg.thinking){"low"->"低";"high"->"高";else->"关闭"}){
                    app.choose("思考强度",listOf("关闭","低","高")){v->app.prefs.edit().putString("thinking:$owner:$group",mapOf("关闭" to "off","低" to "low","高" to "high").getValue(v)).apply();show(app,afterSave)}
                }
            }
        }else connections.addView(app.text("添加连接后，可为对话和自动整理分别选择模型。",13f).apply{alpha=.7f})
        val budget=section(app,body,"用量与预算")
        link(app,budget,"每任务预算","${app.prefs.getInt("ai_budget:$owner",100000)} token"){app.choose("任务预算",listOf("30000","100000","300000")){app.prefs.edit().putInt("ai_budget:$owner",it.toInt()).apply();show(app,afterSave)}}
        link(app,budget,"查看 AI 用量"){usage(app)}
    }
    private fun edit(app:MainActivity,original:JSONObject,afterSave:(()->Unit)?) {
        val p=JSONObject(original.toString());val body=app.surface("模型连接",back={show(app,afterSave)});val id=p.getString("id");val owner=app.profileId;val screen=app.screenToken()
        fun input(title:String,value:String,secret:Boolean=false)=app.field(title,secret).apply{body.addView(app.text(title,14f));setText(value);body.addView(this)}
        val name=input("连接名称",p.optString("name"));val base=input("接口地址",p.optString("base"))
        val key=input("API Key（留空保留已有密钥）","",true)
        val models=input("模型 ID，可用逗号或换行分隔",p.optString("models")).apply{setSingleLine(false);minLines=1;maxLines=4}
        val status=app.text("",13f);body.addView(status)
        fun secret()=key.text.toString().trim().ifBlank{app.secrets.get("model-key:$owner:$id")}
        fun value():AiConfig {val cfg=config(app,owner,p,ModelProtocol.models(models.text.toString()).firstOrNull().orEmpty(),"连接测试");return cfg.copy(base=ModelProtocol.normalizeBase(base.text.toString()),key=secret(),budget=6000)}
        val actions=app.row();body.addView(actions)
        val fetch=app.button("获取模型列表"){};val test=app.button("测试连接"){}
        app.weighted(actions,fetch);app.weighted(actions,test)
        var busy=false
        fun work(job:()->JSONObject,done:(JSONObject)->Unit){if(busy)return;busy=true;fetch.isEnabled=false;test.isEnabled=false;status.text="正在连接…";app.worker.execute{val result=runCatching(job);app.runOnUiThread{busy=false;fetch.isEnabled=true;test.isEnabled=true;if(!app.isDestroyed&&app.screenToken()==screen){result.onSuccess(done).onFailure{status.text=it.message?:"连接失败，请检查配置"}}}}}
        fetch.setOnClickListener{work({val endpoint=ModelProtocol.normalizeBase(base.text.toString());require(secret().isNotBlank()){"请填写 API Key"};ModelHttp.request(endpoint,"/models",token=secret(),timeout=20000)}){result->
            val data=result.optJSONArray("data")?:JSONArray();val names=(0 until data.length()).mapNotNull{data.optJSONObject(it)?.optString("id")?.takeIf{v->v.isNotBlank()}}.distinct().sorted()
            if(names.isEmpty()){status.text="接口未返回模型列表，请手动填写模型 ID";return@work}
            val col=app.column(0);val query=app.field("搜索模型");col.addView(query)
            val selected=ModelProtocol.models(models.text.toString()).toMutableSet();val rows=LinearLayout(app).apply{orientation=LinearLayout.VERTICAL};col.addView(rows)
            fun render(){rows.removeAllViews();names.filter{it.contains(query.text.toString(),true)}.take(150).forEach{n->rows.addView(CheckBox(app).apply{text=n;isChecked=n in selected;minHeight=app.dp(48);setOnCheckedChangeListener{_,on->if(on)selected.add(n)else selected.remove(n)}})}}
            query.addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,a:Int,c:Int,f:Int){};override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){render()};override fun afterTextChanged(s:android.text.Editable?){}});render()
            ReaderDialog.Builder(app).setTitle("选择模型 · ${names.size} 个").setView(col).setPositiveButton("应用"){_,_->models.setText(selected.joinToString(", "));status.text="已选择 ${selected.size} 个模型"}.setNegativeButton("取消",null).show()
        }}
        test.setOnClickListener{work({AiGateway.request(value(),JSONObject().put("messages",JSONArray().put(JSONObject().put("role","user").put("content","仅回复：连接正常"))).put("max_tokens",64))}){status.text="连接正常"}}
        val toggle=app.iconAction("高级兼容设置",R.drawable.ic_down){};body.addView(toggle)
        val advanced=app.column(10).apply{visibility=View.GONE};body.addView(advanced)
        toggle.setOnClickListener{advanced.visibility=if(advanced.visibility==View.GONE)View.VISIBLE else View.GONE}
        var protocol=p.optString("reasoning_protocol",if(p.optString("provider") in listOf("qwen","deepseek"))p.getString("provider")else"none")
        val protocolNames=linkedMapOf("不附加思考参数" to "none","千问 enable_thinking" to "qwen","DeepSeek thinking" to "deepseek","标准 reasoning_effort" to "effort")
        val protocolButton=app.button(protocolNames.entries.firstOrNull{it.value==protocol}?.key?:"不附加思考参数"){}
        protocolButton.setOnClickListener{app.choose("思考参数格式",protocolNames.keys.toList()){protocol=protocolNames.getValue(it);protocolButton.text=it;p.put("reasoning_protocol",protocol)}};advanced.addView(protocolButton)
        val json=Switch(app).apply{text="发送 JSON 模式参数";isChecked=p.optBoolean("json_output",p.optString("provider") in listOf("qwen","deepseek"));minHeight=app.dp(48)};json.setOnCheckedChangeListener{_,on->p.put("json_output",on)};advanced.addView(json)
        val usage=Switch(app).apply{text="请求流式用量统计";isChecked=p.optBoolean("stream_usage",p.optString("provider") in listOf("qwen","deepseek"));minHeight=app.dp(48)};usage.setOnCheckedChangeListener{_,on->p.put("stream_usage",on)};advanced.addView(usage)
        val modern=Switch(app).apply{text="使用 max_completion_tokens";isChecked=p.optString("token_field")=="max_completion_tokens";minHeight=app.dp(48)};modern.setOnCheckedChangeListener{_,on->p.put("token_field",if(on)"max_completion_tokens"else"max_tokens")};advanced.addView(modern)
        body.addView(app.button("保存连接"){
            val cfg=runCatching{value()}.getOrElse{status.text=it.message;return@button}
            if(cfg.key.isBlank()||cfg.model.isBlank()){status.text="请填写密钥和至少一个模型 ID";return@button}
            p.put("name",name.text.toString().trim().ifBlank{p.optString("provider")}).put("base",cfg.base).put("models",ModelProtocol.models(models.text.toString()).joinToString(","))
                .put("reasoning_protocol",protocol).put("json_output",json.isChecked).put("stream_usage",usage.isChecked).put("token_field",if(modern.isChecked)"max_completion_tokens"else"max_tokens")
            val list=profiles(app,owner);val values=(0 until list.length()).map{list.getJSONObject(it)}.toMutableList();val at=values.indexOfFirst{it.optString("id")==id};if(at<0)values.add(p)else values[at]=p
            app.secrets.set("model-key:$owner:$id",cfg.key);app.prefs.edit().putString("models:$owner",JSONArray(values).toString()).apply();assignQuickRoutes(app,owner)
            if(afterSave!=null)afterSave()else show(app)
        })
        if((0 until profiles(app,owner).length()).any{profiles(app,owner).getJSONObject(it).optString("id")==id})body.addView(app.iconAction("删除此连接",R.drawable.ic_clean){app.choose("删除连接",listOf("取消","删除")){if(it=="删除"){
            val list=profiles(app,owner);app.prefs.edit().putString("models:$owner",JSONArray((0 until list.length()).map{list.getJSONObject(it)}.filter{it.optString("id")!=id}).toString()).apply();app.secrets.remove("model-key:$owner:$id");show(app,afterSave)
        }}})
    }
    private fun usage(app:MainActivity) {
        val body=app.surface("AI 用量",back={show(app)});val prefs=app.getSharedPreferences("ai-usage:${app.profileId}",0)
        body.addView(app.text("累计 ${prefs.getLong("total",0)} token",24f));body.addView(app.text("${prefs.getInt("unknown",0)} 次用量待确认 · 最近 500 次记录",13f))
        val turns=runCatching{JSONArray(app.getSharedPreferences("reading-turns:${app.profileId}",0).getString("entries","[]"))}.getOrDefault(JSONArray())
        if(turns.length()>0){
            body.addView(app.text("最近对话合计",18f))
            for(i in turns.length()-1 downTo maxOf(0,turns.length()-10)){val e=turns.getJSONObject(i);val u=e.getJSONObject("usage")
                body.addView(app.text("${e.optString("mode")} · ${u.optInt("calls")} 次调用 · 输入 ${u.optInt("input")} / 输出 ${u.optInt("output")}${if(u.optBoolean("estimated"))"（估算）"else""}",13f))}
        }
        val entries=runCatching{JSONArray(prefs.getString("entries","[]"))}.getOrDefault(JSONArray())
        for(i in entries.length()-1 downTo 0){val e=entries.getJSONObject(i);body.addView(app.text("${e.getString("task")} · ${e.getString("model")}\n输入 ${e.getInt("input")}（缓存命中 ${e.getInt("hit")}） · 输出 ${e.getInt("output")}\n${e.getLong("ms")/1000.0} 秒${if(e.optBoolean("unknown"))" · 用量待确认"else""}",14f))}
    }
}
