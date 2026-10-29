package cn.jiangcheng.lemon.reader

import java.text.Normalizer
import java.util.Locale

object ChronicleCatalog {
    fun name(value:String)=Normalizer.normalize(value.trim(),Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    /** Exact cleanup coordinates must move both the evidence and its knowledge boundary. */
    fun relocate(entries:List<Chronicle>,revision:Int,chapters:List<Chapter>,map:(Position)->Position):List<Chronicle> = entries.map{e->
        if(e.revision!=revision)return@map e
        val end=map(Position(e.chapter,e.offset))
        val cutoff=map(Position(e.chapter,maxOf(e.offset,e.contextEnd)))
        val start=map(Position(e.chapter,(e.offset-e.evidence.length).coerceAtLeast(0)))
        val text=chapters.getOrNull(end.chapter)?.text
        val evidenceMatches=e.evidence.isBlank()||(text!=null&&start.chapter==end.chapter&&start.offset>=0&&end.offset in start.offset..text.length&&text.substring(start.offset,end.offset)==e.evidence)
        if(!evidenceMatches||cutoff.chapter!=end.chapter)return@map e
        e.copy(chapter=end.chapter,offset=end.offset,contextEnd=if(e.contextEnd<0)-1 else cutoff.offset,revision=revision+1)
    }
    data class Item(val key:String,val records:List<Chronicle>,val frequency:Int=0,val first:Position,val last:Position){
        val entry get()=records.firstOrNull{it.manual}?:records.first()
        val names get()=records.map{it.title}.distinct()
        val title get()=if(entry.kind=="人物")CharacterAliases.canonical(records)else entry.title
        val content get()=records.map{it.content.trim()}.filter{it.isNotEmpty()}.distinct().joinToString("\n")
    }
    private fun key(e:Chronicle)="${e.revision}:${e.kind}:"+if(e.entity.isNotBlank())e.entity else if(e.kind=="时间线")e.id else name(e.title)
    fun visible(entries:List<Chronicle>,boundary:Position,revision:Int):List<Chronicle> = entries.filter{!it.deleted&&it.revision==revision&&(it.chapter<boundary.chapter||it.chapter==boundary.chapter&&maxOf(it.offset,it.contextEnd)<=boundary.offset)}
    fun items(entries:List<Chronicle>,chapters:List<Chapter>,boundary:Position,revision:Int):List<Item> {
        val records=CharacterAliases.resolveVisible(visible(entries,boundary,revision)).sortedWith(compareBy({it.chapter},{it.offset}))
        val items=records.groupBy(::key).map{(key,group)->Item(key,group,first=Position(group.first().chapter,group.first().offset),last=Position(group.last().chapter,group.last().offset))}
        // One local pass over allowed text for all names, rather than rescanning a novel for each card.
        class Node {val next=HashMap<Char,Int>();var fail=0;val out=mutableListOf<Pair<String,Int>>()}
        val trie=mutableListOf(Node())
        items.filter{it.entry.kind=="人物"}.forEach{item->item.names.forEach{name->if(name.isNotBlank()){var at=0;name.forEach{c->at=trie[at].next.getOrPut(c){trie.add(Node());trie.lastIndex}};trie[at].out.add(item.key to name.length)}}}
        val queue=java.util.ArrayDeque<Int>();trie[0].next.values.forEach{queue.add(it)}
        while(queue.isNotEmpty()){val r=queue.removeFirst();for((c,u) in trie[r].next){queue.add(u);var v=trie[r].fail;while(v!=0&&c !in trie[v].next)v=trie[v].fail;trie[u].fail=trie[v].next[c]?:0;trie[u].out.addAll(trie[trie[u].fail].out)}}
        val counts=HashMap<String,Int>();val first=HashMap<String,Position>();val last=HashMap<String,Position>();val lastEnd=HashMap<String,Int>()
        if(trie.size>1)for(ch in 0..minOf(boundary.chapter,chapters.lastIndex)){val text=chapters[ch].text;val end=if(ch==boundary.chapter)boundary.offset.coerceIn(0,text.length)else text.length;var node=0;lastEnd.clear()
            for(at in 0 until end){val c=text[at];while(node!=0&&c !in trie[node].next)node=trie[node].fail;node=trie[node].next[c]?:0
                for((id,length) in trie[node].out){val start=at-length+1;if(start<(lastEnd[id]?:-1))continue;lastEnd[id]=at+1;counts[id]=(counts[id]?:0)+1;first.putIfAbsent(id,Position(ch,start));last[id]=Position(ch,start)}
            }
        }
        return items.map{it.copy(frequency=counts[it.key]?:0,first=first[it.key]?:it.first,last=last[it.key]?:it.last)}
    }
    val orders=listOf("首次出现","最近提及","提及最多","名称")
    fun sort(items:List<Item>,order:String,query:String="",kind:String="全部"):List<Item> {
        val q=query.trim();val filtered=items.filter{(kind=="全部"||it.entry.kind==kind)&&(q.isBlank()||it.names.any{n->n.contains(q,true)}||it.content.contains(q,true))}
        return when(order){
            "最近提及"->filtered.sortedWith(compareByDescending<Item>{it.last.chapter}.thenByDescending{it.last.offset}.thenBy{it.key})
            "提及最多"->filtered.sortedWith(compareByDescending<Item>{it.frequency}.thenBy{it.first.chapter}.thenBy{it.first.offset}.thenBy{it.key})
            "名称"->filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER){it.title})
            else->filtered.sortedWith(compareBy({it.first.chapter},{it.first.offset},{it.key}))
        }
    }
    fun merge(entries:List<Chronicle>,first:Item,second:Item):List<Chronicle> {
        require(first.entry.kind=="人物"&&second.entry.kind=="人物"&&first.entry.revision==second.entry.revision)
        val keys=setOf(first.key,second.key);val ids=(first.records+second.records).map{it.id}.toSet();val entity=first.entry.entity.ifBlank{first.entry.id}
        return entries.map{if(key(it) in keys||it.id in ids)it.copy(entity=entity)else it}
    }
    fun delete(entries:List<Chronicle>,item:Item)=entries.map{if(key(it)==item.key||item.records.any{e->e.id==it.id})it.copy(deleted=true,manual=true)else it}
}
