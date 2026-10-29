package cn.jiangcheng.lemon.reader

/** Detect a document's heading convention before splitting; never mix exported headings with body lists. */
object TxtDirectory {
    private const val NUM = "[0-9０-９零〇○壹贰叁肆伍陆柒捌玖一二三四五六七八九十百千万两]+"
    private val metadata = Regex("^(.{1,140}?)\\s+字数[:：]\\s*[0-9０-９]+\\s*字\\s*$")
    private val prefix = Regex("^(?:正文|作品正文)\\s+")
    private val numbered = Regex("^第\\s*$NUM\\s*[章节節卷回部篇集](?:.{0,65})$")
    private val missingFirst = Regex("^$NUM\\s*(?:[章节節]\\s*.{0,60}|回\\s+.{1,60})$")
    private val missingUnit = Regex("^第\\s*$NUM\\s+[^章节節卷回部篇集].{0,45}$")
    private val special = Regex("^(?:序章|序言|前言|楔子|尾声|后记|番外|引子|前传)(?:.{0,45})$")
    private val english = Regex("^Chapter\\s+(?:\\d+|[IVXLCDM]+)(?:[.\\] :：].{0,60})?$",RegexOption.IGNORE_CASE)
    private val bracket=Regex("^[\\[【（(]\\s*\\d{1,5}\\s*[\\]】）)].{0,60}$")
    private val weak = Regex("^($NUM)(?:[、.．）)]\\s*|\\s+)(.{1,45})$")
    private val sentence = Regex("[。！？!?；;“”\"]")
    private val domain = Regex("[a-z0-9]\\.(?:com|net|org|cn)(?:\\b|/)",RegexOption.IGNORE_CASE)
    private val navigationLabel=Regex("(?:今日(?:最新)?(?:更新|章节|链接)|上一章回顾)[:：]")
    private val proseReference=Regex("^第\\s*$NUM\\s*[章节卷回](?:中既|中曾|里曾|尺度|有点|了[．。，,]|的内容|已经|所述).*$")
    private val bibliography=Regex("\\[(?:J|M|D|C|R|N|S|EB/OL)\\]",RegexOption.IGNORE_CASE)
    private val authorRevision=Regex("^(?:第\\s*)?$NUM\\s*[章节節](?:修改|改完|修订|修訂|重写|重寫|重修).{3,}$")
    private val preface=Regex("^(?:前言|序言|序章|楔子|引子)(?:$|[\\s：:].{1,40}$)")
    private fun excluded(title:String)=bibliography.containsMatchIn(title)||authorRevision.matches(title)||
        (missingFirst.matches(title)&&(title.contains("节课")||title.contains('“')||title.contains('”')))
    private val fullStop=Regex("[。．；;]")
    fun cleanTitle(value:String):String = prefix.replace(metadata.matchEntire(value.trim())?.groupValues?.get(1)?:value.trim(), "").replace(Regex("^[☆★◆◇]\\s*[、.]?\\s*"),"").trim()
    fun exported(lines:List<String>):Map<Int,String> {
        val matches=lines.mapIndexedNotNull { index,line ->
            val match=metadata.matchEntire(line.trim())
            if(match!=null&&(index==0||lines[index-1].isBlank())&&(index==lines.lastIndex||lines[index+1].isBlank()))index to cleanTitle(line) else null
        }
        // A lone export section is meaningful only at the document opening.
        return if(matches.size>=2 || (matches.size==1&&matches.first().first<=20))matches.toMap() else emptyMap()
    }
    fun headings(lines:List<String>):Map<Int,String> {
        exported(lines).takeIf{it.isNotEmpty()}?.let{return it}
        val strong=linkedMapOf<Int,String>()
        lines.forEachIndexed{index,line->
            val title=cleanTitle(line)
            if(excluded(title))return@forEachIndexed
            if(!(numbered.matches(title)||missingFirst.matches(title)||missingUnit.matches(title)||english.matches(title)||bracket.matches(title)))return@forEachIndexed
            val navigationHeading=lines.subList((index-7).coerceAtLeast(0),index).asReversed().takeWhile{it.trim().length<100}.any{navigationLabel.containsMatchIn(it)}
            val proseContinuation=proseReference.matches(title)
            if(!navigationHeading&&!proseContinuation&&!title.contains(fullStop))strong[index]=title
        }
        if(strong.size>=3){
            // Quoted/repeated headings directly below an actual heading are body text, not another chapter.
            var previous=-100;var previousNumber=""
            val ordinal=Regex("^(?:第\\s*)?([0-9０-９零〇○壹贰叁肆伍陆柒捌玖一二三四五六七八九十百千万两]+)\\s*[章节回]")
            val intro=lines.take(strong.keys.first()).mapIndexedNotNull{i,l->cleanTitle(l).takeIf{preface.matches(it)}?.let{i to it}}.toMap()
            return intro+strong.filter{(index,title)->val number=ordinal.find(title)?.groupValues?.get(1).orEmpty();val duplicate=number.isNotEmpty()&&number==previousNumber&&index-previous<8;if(!duplicate){previous=index;previousNumber=number};!duplicate}
        }
        // Layout is independent of site names: consistently indented prose and isolated flush-left headings.
        val prose=lines.filter{it.trim().length>100}
        if(prose.size>=8&&prose.count{it.firstOrNull()?.isWhitespace()==true}*10>=prose.size*9){
            val layout=lines.mapIndexedNotNull{index,line->
                val canonical=java.text.Normalizer.normalize(line,java.text.Normalizer.Form.NFKC).replace('。','.')
                if(line.length in 1..100&&!line.first().isWhitespace()&&(index==0||lines[index-1].isBlank())&&(index==lines.lastIndex||lines[index+1].isBlank())&&!domain.containsMatchIn(canonical)&&!excluded(cleanTitle(line)))index to cleanTitle(line)else null
            }
            if(layout.size>=2)return layout.toMap()
        }
        val prefixed=lines.mapIndexedNotNull{index,line->if(!excluded(cleanTitle(line))&&prefix.containsMatchIn(line.trim())&&line.trim().length<=100)index to cleanTitle(line)else null}
        if(prefixed.size>=2)return prefixed.toMap()
        val result=linkedMapOf<Int,String>();val numberedLists=mutableListOf<Pair<Int,String>>()
        lines.forEachIndexed { index,line ->
            val title=cleanTitle(line)
            if(title.length>100||excluded(title))return@forEachIndexed
            if(strong.containsKey(index)||special.matches(title))result[index]=title
            else if(sentence.containsMatchIn(title))return@forEachIndexed
            else if(weak.matches(title))numberedLists.add(index to title)
        }
        // Bare numbers are weaker evidence: require repeated, spaced-out sections, not a compact list.
        if(result.isEmpty()&&numberedLists.size>=2){
            val offsets=IntArray(lines.size);for(i in 1 until lines.size)offsets[i]=offsets[i-1]+lines[i-1].length+1
            numberedLists.forEachIndexed{i,pair->
                val previous=numberedLists.getOrNull(i-1);val next=numberedLists.getOrNull(i+1)
                if((previous!=null&&offsets[pair.first]-offsets[previous.first]>=200)||(next!=null&&offsets[next.first]-offsets[pair.first]>=200))result[pair.first]=pair.second
            }
        }
        return result
    }
}
