package cn.jiangcheng.lemon.reader

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

object NovelImporter {
    private fun xml(bytes: ByteArray):org.w3c.dom.Document {
        val charset=when {
            bytes.size>=2&&bytes[0]==0xff.toByte()&&bytes[1]==0xfe.toByte()->Charsets.UTF_16LE
            bytes.size>=2&&bytes[0]==0xfe.toByte()&&bytes[1]==0xff.toByte()->Charsets.UTF_16BE
            bytes.size>=4&&bytes[0]==0.toByte()&&bytes[1]==60.toByte()->Charsets.UTF_16BE
            bytes.size>=4&&bytes[0]==60.toByte()&&bytes[1]==0.toByte()->Charsets.UTF_16LE
            else->Charsets.UTF_8
        }
        val source=String(bytes,charset).removePrefix("\uFEFF")
        // EPUB 2 NCX often declares an external DTD. Remove that declaration offline;
        // reject internal subsets/entities independently of platform parser features.
        val clean=source.replace(Regex("<!DOCTYPE\\s+[^>\\[]*>",RegexOption.IGNORE_CASE),"")
        require(!Regex("<!\\s*(?:DOCTYPE|ENTITY)",RegexOption.IGNORE_CASE).containsMatchIn(clean)){"EPUB 含不支持的实体声明"}
        return DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching{setFeature("http://xml.org/sax/features/external-general-entities", false)}
        runCatching{setFeature("http://xml.org/sax/features/external-parameter-entities", false)}
        runCatching{setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)}
        runCatching{isXIncludeAware = false}
        isExpandEntityReferences = false
        }.newDocumentBuilder().apply{setEntityResolver { _,_->org.xml.sax.InputSource(java.io.StringReader("")) }}.parse(org.xml.sax.InputSource(java.io.StringReader(clean)))
    }

    fun epub(bytes: ByteArray, filename: String, completeTitles:Boolean=false): Novel {
        val files = mutableMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    require(!entry.name.startsWith('/') && entry.name.split('/').none { it == ".." }) { "EPUB 包含异常路径" }
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 150_000_000) { "EPUB 解压内容超过当前内测的 150 MB 保护阈值" }
                        output.write(buffer, 0, count)
                    }
                    files[entry.name] = output.toByteArray()
                }
                entry = zip.nextEntry
            }
        }
        require(!files.containsKey("META-INF/encryption.xml")) { "此 EPUB 包含加密资源，当前版本支持无加密 EPUB" }
        val container = xml(files["META-INF/container.xml"] ?: error("EPUB 缺少 container.xml"))
        val root = container.getElementsByTagNameNS("*", "rootfile").item(0) as? Element ?: error("EPUB 缺少书籍描述")
        val opfPath = root.getAttribute("full-path")
        val opf = xml(files[opfPath] ?: error("EPUB 描述文件不存在"))
        val base = opfPath.substringBeforeLast('/', "")
        fun resolve(href: String): String {
            val path = if (base.isEmpty()) href else "$base/$href"
            return java.net.URI(path.replace(" ", "%20")).normalize().path
        }
        val manifest = mutableMapOf<String, String>()
        var navPath:String?=null
        var ncxPath:String?=null
        var coverPath:String?=null
        val items = opf.getElementsByTagNameNS("*", "item")
        for (i in 0 until items.length) {
            val item = items.item(i) as Element
            val path=resolve(item.getAttribute("href").substringBefore('#'))
            manifest[item.getAttribute("id")] = path
            if(item.getAttribute("properties").split(' ').contains("nav"))navPath=path
            if(item.getAttribute("media-type")=="application/x-dtbncx+xml")ncxPath=path
            if(item.getAttribute("properties").split(' ').contains("cover-image"))coverPath=path
        }
        val metadata=opf.getElementsByTagNameNS("*","meta")
        for(i in 0 until metadata.length) {val meta=metadata.item(i) as Element;if(meta.getAttribute("name")=="cover")coverPath=manifest[meta.getAttribute("content")]}
        val titles = opf.getElementsByTagNameNS("*", "title")
        val title = titles.item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: filename.substringBeforeLast('.')
        val chapters = mutableListOf<Chapter>()
        // A single spine document may contain multiple TOC anchors.
        val entries=mutableListOf<Triple<String,String,String>>()
        fun addEntry(document:String,href:String,label:String) {
            if(href.isBlank()||label.isBlank())return
            val uri=runCatching{java.net.URI(document.replace(" ","%20")).resolve(href.replace(" ","%20")).normalize()}.getOrNull() ?: return
            if(uri.isAbsolute||uri.path.startsWith("../"))return
            val entry=Triple(uri.path,uri.fragment.orEmpty(),label.trim())
            if(entries.none{it.first==entry.first&&it.second==entry.second})entries.add(entry)
        }
        navPath?.let{path->files[path]?.let{raw->runCatching{
            val doc=xml(raw);val navs=doc.getElementsByTagNameNS("*","nav")
            for(n in 0 until navs.length) {
                val nav=navs.item(n) as Element
                if(!nav.getAttributeNS("http://www.idpf.org/2007/ops","type").split(' ').contains("toc"))continue
                val links=nav.getElementsByTagNameNS("*","a")
                for(j in 0 until links.length){val a=links.item(j) as Element;addEntry(path,a.getAttribute("href"),a.textContent)}
            }
        }}}
        if(entries.isEmpty())ncxPath?.let{path->files[path]?.let{raw->runCatching{
            val points=xml(raw).getElementsByTagNameNS("*","navPoint")
            for(n in 0 until points.length){val point=points.item(n) as Element;val content=point.getElementsByTagNameNS("*","content").item(0) as? Element ?: continue;val label=point.getElementsByTagNameNS("*","navLabel").item(0)?.textContent.orEmpty();addEntry(path,content.getAttribute("src"),label)}
        }}}
        val spine = opf.getElementsByTagNameNS("*", "itemref")
        for (i in 0 until spine.length) {
            val item = spine.item(i) as Element
            if (item.getAttribute("linear") == "no") continue
            val path = manifest[item.getAttribute("idref")] ?: continue
            val raw = files[path] ?: continue
            val html = String(raw, Charsets.UTF_8)
            val heading = Regex("<(?:h[1-3]|title)(?:\\s[^>]*)?>(.*?)</(?:h[1-3]|title)>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(html).map { text(it.groupValues[1]) }.firstOrNull { it.isNotBlank() }
            val body = Regex("<body(?:\\s[^>]*)?>(.*?)</body>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1) ?: html
            val plain = text(body)
            val positions=entries.filter{it.first==path}.mapNotNull{entry->
                val offset=if(entry.second.isEmpty())0 else Regex("<[^>]+\\b(?:id|name)\\s*=\\s*([\"'])"+Regex.escape(entry.second)+"\\1[^>]*>",RegexOption.IGNORE_CASE).find(body)?.range?.first
                offset?.let{it to entry.third}
            }.distinctBy{it.first}.sortedBy{it.first}
            if(positions.isEmpty()) {
                if(plain.isNotBlank())chapters.add(Chapter(heading ?: if(completeTitles)"第${chapters.size+1}章"else"未命名章节",plain))
            } else {
                val cuts=positions.toMutableList()
                if(cuts.first().first>0&&text(body.substring(0,cuts.first().first)).isNotBlank())cuts.add(0,0 to (heading ?: "卷首"))
                for(j in cuts.indices){val content=text(body.substring(cuts[j].first,cuts.getOrNull(j+1)?.first ?: body.length));if(content.isNotBlank())chapters.add(Chapter(cuts[j].second,content))}
            }
        }
        require(chapters.isNotEmpty()) { "EPUB 中没有可阅读正文" }
        return Novel(title, chapters, "EPUB", listOf("按 EPUB 阅读顺序导入；复杂图文采用纯文字排版"),coverPath?.let{files[it]})
    }

    private fun text(html: String): String = html
        .replace(Regex("<(script|style)[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        .replace(Regex("</(?:p|div|h[1-6]|li|section)>|<br\\s*/?>", RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        .replace(Regex("&#(x[0-9a-fA-F]+|[0-9]+);")) { m ->
            runCatching { val s = m.groupValues[1]; String(Character.toChars(if (s.startsWith('x')) s.drop(1).toInt(16) else s.toInt())) }.getOrDefault(m.value)
        }.lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim()
}
