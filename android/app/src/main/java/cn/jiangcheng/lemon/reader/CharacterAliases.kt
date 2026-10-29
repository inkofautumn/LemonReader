package cn.jiangcheng.lemon.reader

/** Local alias suggestions. A shared last character alone is never identity proof. */
object CharacterAliases {
    data class Suggestion(val full:ChronicleCatalog.Item,val short:ChronicleCatalog.Item,val ambiguous:Boolean,val reason:String)
    fun canonical(records:List<Chronicle>):String = records.map{it.title.trim()}.distinct()
        .sortedWith(compareByDescending<String>{it.length}.thenBy{it}).firstOrNull().orEmpty()
    fun declared(record:Chronicle):List<String> = record.content.lineSequence()
        .filter{it.startsWith("别名：")}.flatMap{it.removePrefix("别名：").split('、','，',',','；',';').asSequence()}
        .map{it.trim()}.filter{it.isNotEmpty()&&it.length<=40}.toList()
    private fun explicitlySame(a:String,b:String,evidence:String):Boolean {
        val x=Regex.escape(a);val y=Regex.escape(b)
        // Require an identity phrase between two separate name occurrences, not substring presence.
        return Regex("(?:$x[，,（(\\s]*(?:也叫|又名|本名(?:是|为)?|原名(?:是|为)?|全名(?:是|为)?|简称(?:为)?|昵称(?:是|为)?|即|就是|正是)[：:、\\s]*$y|$y[，,（(\\s]*(?:也叫|又名|本名(?:是|为)?|原名(?:是|为)?|全名(?:是|为)?|简称(?:为)?|昵称(?:是|为)?|即|就是|正是)[：:、\\s]*$x)")
            .findAll(evidence).any{hit->
                val before=evidence.substring(0,hit.range.first).takeLast(4)
                val end=hit.range.last+1
                before.none{it=='不'||it=='非'}&&(end==evidence.length||evidence[end] in "，。；！、：\n\r ）)」』\"")
            }
    }
    /** Called only after filtering by revision and knowledge cutoff. Source snapshots stay intact. */
    fun resolveVisible(records:List<Chronicle>):List<Chronicle> {
        val people=records.filter{it.kind=="人物"};val names=people.map{it.title}.distinct()
        val parent=mutableMapOf<String,String>()
        fun root(n:String):String {var p=n;while(parent[p]!=null&&parent[p]!=p)p=parent.getValue(p);return p}
        fun join(a:String,b:String){val x=root(a);val y=root(b);if(x!=y)parent[maxOf(x,y)]=minOf(x,y)}
        people.filter{it.entity.isNotBlank()}.groupBy{it.entity}.values.forEach{g->g.forEach{join(g.first().title,it.title)}}
        for(e in people) for(alias in declared(e)) if(alias in names&&alias!=e.title&&explicitlySame(e.title,alias,e.evidence))join(e.title,alias)
        val groups=people.groupBy{root(it.title)}
        val identities=groups.mapValues{(_,g)->g.map{it.entity}.filter{it.isNotBlank()}.minOrNull()?:g.minOf{it.id}}
        return records.map{if(it.kind=="人物"&&groups.getValue(root(it.title)).map{e->e.title}.distinct().size>1)it.copy(entity=identities.getValue(root(it.title)))else it}
    }
    fun suggestions(items:List<ChronicleCatalog.Item>):List<Suggestion> {
        val people=items.filter{it.entry.kind=="人物"}
        val byName=mutableMapOf<String,MutableList<ChronicleCatalog.Item>>()
        people.forEach{item->item.names.forEach{byName.getOrPut(ChronicleCatalog.name(it)){mutableListOf()}.add(item)}}
        val pairs=linkedMapOf<Pair<String,String>,Pair<ChronicleCatalog.Item,ChronicleCatalog.Item>>()
        fun offer(a:ChronicleCatalog.Item,b:ChronicleCatalog.Item){
            if(a.key==b.key)return
            val full=if(a.title.length>b.title.length||a.title.length==b.title.length&&a.title<b.title)a else b
            val short=if(full===a)b else a
            pairs[full.key to short.key]=full to short
        }
        for(full in people){
            for(title in full.names){val name=ChronicleCatalog.name(title)
                for(length in 1 until name.length)byName[name.takeLast(length)]?.forEach{offer(full,it)}
            }
            full.records.flatMap(::declared).forEach{alias->byName[ChronicleCatalog.name(alias)]?.forEach{offer(full,it)}}
        }
        val alternatives=pairs.values.groupingBy{it.second.key}.eachCount()
        return pairs.values.map{(full,short)->val ambiguous=alternatives.getValue(short.key)>1
            Suggestion(full,short,ambiguous,if(ambiguous)"简称可能对应多位人物，请核对"else"全名与简称／别名可能指向同一人物")
        }.sortedWith(compareBy({it.ambiguous},{it.full.title},{it.short.title}))
    }
}
