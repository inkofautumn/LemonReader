package cn.jiangcheng.lemon.reader

import java.io.*
import java.util.Locale

/** A folder is a candidate book, never an automatic merge of an arbitrary tree. */
object ScanCatalog {
    val formats=setOf("txt","epub","mobi","azw3")
    data class Entry(val file:File,val parts:List<File> = emptyList(),val certain:Boolean=false) {
        val id get()=file.absolutePath
        val grouped get()=parts.isNotEmpty()
        val title get()=if(grouped)file.name else file.nameWithoutExtension
        val bytes=if(grouped)parts.sumOf{it.length()}else file.length()
        fun matches(query:String)=query.isBlank()||title.contains(query,true)||file.name.contains(query,true)||parts.any{it.name.contains(query,true)}
    }
    private val number=Regex("[0-9０-９零〇一二三四五六七八九十百千万两]+")
    private val chapter=Regex("^(?:第([0-9０-９零〇一二三四五六七八九十百千万两]+)[章回节]|(?:chapter\\s*)?([0-9０-９]+)(?:[ ._、-]|$))",RegexOption.IGNORE_CASE)
    fun ordinal(name:String):Long?=chapter.find(name)?.let{m->numeric(m.groupValues[1].ifEmpty{m.groupValues[2]})}
    private fun numeric(raw:String):Long {
        val s=raw.map{if(it in '０'..'９')('0'.code+it.code-'０'.code).toChar()else it}.joinToString("")
        s.toLongOrNull()?.let{return it}
        var total=0L;var section=0L;var digit=0L
        for(c in s){val n="零一二三四五六七八九".indexOf(c);if(n>=0)digit=n.toLong() else when(c){'〇'->digit=0;'两'->digit=2;'十','百','千'->{val unit=when(c){'十'->10;'百'->100;else->1000};section+=(if(digit==0L)1 else digit)*unit;digit=0};'万'->{total+=(section+digit)*10000;section=0;digit=0}}}
        return total+section+digit
    }
    fun sort(files:List<File>):List<File> = files.map{file->
        file to number.replace(file.name.lowercase(Locale.ROOT)){numeric(it.value).toString().padStart(16,'0')}
    }.sortedWith(compareBy<Pair<File,String>>{it.second}.thenBy{it.first.name}).map{it.first}
    fun classify(files:List<File>,mode:(File)->String={""}):List<Entry> {
        val result=mutableListOf<Entry>()
        val modified=files.associateWith{it.lastModified()}
        for((folder,items) in files.distinctBy{it.absolutePath}.groupBy{it.parentFile!!}) {
            val txt=sort(items.filter{it.extension.lowercase() == "txt"})
            val indices=txt.mapNotNull{ordinal(it.nameWithoutExtension)}
            val certain=txt.size>=3&&indices.size==txt.size&&indices.distinct().size==indices.size&&txt.all{it.length()<=256_000}
            val preference=mode(folder)
            if(txt.size>=2&&preference!="separate"&&(certain||txt.size>=100||preference=="book")) {
                result.add(Entry(folder,txt,certain||preference=="book"))
                val members=txt.toHashSet();result.addAll(items.filter{it !in members}.map{Entry(it)})
            }else result.addAll(items.map{Entry(it)})
        }
        val volume=Regex("^第[0-9０-９零〇一二三四五六七八九十百千万两]+卷.*")
        val volumes=result.filter{it.grouped&&volume.matches(it.file.name)}.groupBy{it.file.parentFile}
        for((parent,children) in volumes){
            if(parent==null||mode(parent)=="separate"||children.size<2)continue
            val members=children.flatMap{it.parts}.toSet()
            if(files.any{it.toPath().startsWith(parent.toPath())&&it !in members})continue
            val ordered=sort(children.map{it.file}).flatMap{dir->children.first{it.file==dir}.parts}
            result.removeAll(children.toSet());result.add(Entry(parent,ordered,children.all{it.certain}||mode(parent)=="book"))
        }
        return result.sortedByDescending{if(it.grouped)it.parts.maxOf{f->modified[f]?:0L}else modified[it.file]?:0L}
    }
    /** Deterministic source container retains each original TXT, including its encoding. */
    fun pack(entry:Entry,title:String=entry.title,parts:List<File> = entry.parts):ByteArray {
        require(parts.isNotEmpty()&&parts.size<=20000){"请选择 1～20,000 个章节文件"}
        require(parts.sumOf{it.length()}<=70_000_000){"分章小说合计超过 70 MB，请分卷导入"}
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use{out->
            out.writeUTF("LEMON-TXT-1");out.writeUTF(title);out.writeInt(parts.size)
            for(file in parts){
                require(file.isFile&&file.length()<=70_000_000){"章节文件不可读取：${file.name}"}
                val expected=file.length();val changed=file.lastModified();val label=file.relativeTo(entry.file).invariantSeparatorsPath.substringBeforeLast('.')
                out.writeUTF(if(label.all{it.isDigit()})"第${label.toLongOrNull()?:label}章"else label);out.writeInt(expected.toInt())
                file.inputStream().use{input->val block=ByteArray(8192);var read=0L;while(true){val n=input.read(block);if(n<0)break;read+=n;require(read<=expected&&bytes.size()+n<=80_000_000){"文件在导入期间发生变化或超过大小限制"};out.write(block,0,n)};require(read==expected&&file.lastModified()==changed){"文件在导入期间发生变化，请重试"}}
            }
        };return bytes.toByteArray()
    }
    fun unpack(bytes:ByteArray):Novel=DataInputStream(ByteArrayInputStream(bytes)).use{input->
        require(input.readUTF().let{it.length<=40&&it.matches(Regex("[A-Z]+-TXT-1"))}){"分章小说格式不正确"}
        val title=input.readUTF();val count=input.readInt();require(count in 1..20000)
        val chapters=(0 until count).map{index->val name=input.readUTF();val length=input.readInt();require(length>=0&&length<=input.available());val raw=ByteArray(length);input.readFully(raw);Chapter(name.ifBlank{"第${index+1}章"},ReaderCore.decode(raw).first.replace("\r\n","\n").replace('\r','\n'))}
        require(input.available()==0);require(chapters.any{it.text.isNotBlank()}){"所选章节没有正文"}
        Novel(title,chapters,"分章 TXT")
    }
}
