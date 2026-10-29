package cn.jiangcheng.lemon.reader

/** Only standalone, explicit distribution promotions. Quotes, credits and uncertain prose survive. */
object AdCleaner {
    private val promotion=Regex("^(?:本书|本小说|本电子书)(?:由|来自).{1,70}(?:阅读网|小说网|中文网|中文|书屋|书库).{0,25}(?:提供|整理|下载|发布)[。.!！]?$|^欢迎(?:(?:光临|访问)本站|来本网站)下载更多小说[。.!！]?$|^(?:更多|最新|免费).{0,12}小说.{0,10}(?:请访问|请到|下载地址)[:： ]*.{1,90}$")
    private val site=Regex("^[\\p{IsHan}A-Za-z0-9·]{2,24}\\s+(?:https?://|www\\.)[A-Za-z0-9./_-]+\\s*$",RegexOption.IGNORE_CASE)
    fun clean(text:String):String {
        val lines=text.split('\n')
        fun normalize(line:String)=java.text.Normalizer.normalize(line,java.text.Normalizer.Form.NFKC).replace('。','.').lowercase()
        val host=Regex("(?:https?://)?(?:www\\.)?([a-z0-9-]+\\.(?:com|net|org|cn))(?:/[^ ]*)?")
        val origins=lines.take(8).filter{site.matches(it.trim())}.mapNotNull{host.find(normalize(it))?.groupValues?.get(1)}.toSet()
        val watermark=Regex("^[\\p{IsHan}]{2,16}\\s+[a-z0-9.]+\\s+[\\p{IsHan}]{2,20}$")
        val repeated=lines.map{normalize(it.trim())}.filter{watermark.matches(it)}.mapNotNull{host.find(it)?.groupValues?.get(1)}.groupingBy{it}.eachCount().filterValues{it>=2}.keys.intersect(origins)
        val footer=mutableSetOf<Int>()
        val download=Regex("^.{1,90}的最新章节已更新[,，]请登[录陆].{1,30}(?:小说下载网|阅读网)[。.!！]?$")
        for(index in (lines.size-20).coerceAtLeast(0) until lines.size){
            if(download.matches(lines[index].trim())&&lines.getOrNull(index+1)?.let{host.find(normalize(it))?.groupValues?.get(1) in origins}==true){
                footer.add(index)
                for(next in index+1..(index+5).coerceAtMost(lines.lastIndex)){
                    val value=normalize(lines[next].trim())
                    if(value.matches(Regex("(?:https?://)?(?:www\\.|wap\\.)?[a-z0-9.-]+(?:/)?"))&&host.find(value)?.groupValues?.get(1) in origins||value in listOf("进行下载.","用手机下载小说资源请访问"))footer.add(next)else if(value.isNotBlank())break
                }
            }
        }
        return lines.filterIndexed{index,line->
        val value=line.trim()
        val canonical=normalize(value)
        val repeatedWatermark=watermark.matches(canonical)&&host.find(canonical)?.groupValues?.get(1) in repeated&&(index==0||lines[index-1].isBlank())&&(index==lines.lastIndex||lines[index+1].isBlank())
        !(index in footer||value.length<=160&&(promotion.matches(value)||(index<8&&site.matches(value))||repeatedWatermark))
    }.joinToString("\n")
    }
}
