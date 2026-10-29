package cn.jiangcheng.lemon.reader

/** Inspired by txtFormat's published custreg examples; bounded rules, never invent punctuation. */
object OfflineProofread {
    private val end = "。！？!?；;：:…—”’）)]】」』"
    fun apply(text:String):String {
        val lines=text.lines()
        val headings=TxtDirectory.headings(lines).keys
        // Fixed-width Chinese exports are evidence of hard wrapping. Free verse and short dialogue are not.
        val prose=lines.map{it.trim()}.filter{it.length in 18..100 && it.count{c->c in '\u4e00'..'\u9fff'}*2>it.length}
        val widths=prose.groupingBy{it.length}.eachCount()
        val width=widths.maxByOrNull{it.value}?.key?:0
        val wrapped=prose.size>=8&&prose.count{kotlin.math.abs(it.length-width)<=2}*5>=prose.size*3
        val out=mutableListOf<String>();var previousHeading:String?=null
        for((i,line) in lines.withIndex()){
            val value=line.trim()
            if(i in headings){
                // Only an immediately repeated, identical heading; never delete intervening prose.
                val canonical=value.filterNot{it.isWhitespace()}
                if(previousHeading==canonical)continue
                out.add(line);previousHeading=canonical;continue
            }
            if(value.isNotEmpty())previousHeading=null
            val previous=out.lastOrNull().orEmpty()
            val prev=previous.trim()
            val continuation=i>0&&i-1 !in headings&&value.isNotEmpty()&&prev.isNotEmpty()
            val quoteBreak=prev.endsWith("：“")&&value.indexOf('”')>=0&&
                (value.indexOf('“')<0||value.indexOf('”')<value.indexOf('“'))
            val hardBreak=wrapped&&kotlin.math.abs(lines.getOrNull(i-1).orEmpty().trim().length-width)<=2&&
                prev.isNotEmpty()&&prev.last() !in end&&value.isNotEmpty()&&value.first() !in "“「『◆☆★"&&
                value.count{it in '\u4e00'..'\u9fff'}*2>value.length
            if(continuation&&(quoteBreak||hardBreak))out[out.lastIndex]=previous.trimEnd()+line.trimStart()
            else out.add(line)
        }
        return out.joinToString("\n")
    }
}
