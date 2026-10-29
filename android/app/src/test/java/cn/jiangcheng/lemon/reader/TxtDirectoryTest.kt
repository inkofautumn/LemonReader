package cn.jiangcheng.lemon.reader

import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File

class TxtDirectoryTest {
    @Test fun exportedHeadingsWinOverDuplicatedBodyTitlesAndLists(){
        val text="书名:测试\n\n正文 第一节   字数: 1000字\n\n第一节\n故事\n1、物品\n2、行李\n\n正文 第二节   字数: 2000字\n\n第二节\n结尾"
        val book=ReaderCore.parseTxt(text.toByteArray(),"测试")
        assertEquals(listOf("书前资料","第一节","第二节"),book.chapters.map{it.title})
        assertTrue(book.chapters[1].text.contains("1、物品\n2、行李"))
        assertTrue(book.chapters[1].text.startsWith("第一节"))
    }
    @Test fun genericConventionsAndProseFalsePositives(){
        val body="这是正文中的一句话。".repeat(35)
        for(pair in listOf("正文 第一节" to "正文 第二节","1、初见" to "2、相遇","一、初见" to "二、相遇","1. 初见" to "2. 相遇")){
            val book=ReaderCore.parseTxt("${pair.first}\n$body\n${pair.second}\n$body".toByteArray(),"测试")
            assertEquals(pair.toString(),2,book.chapters.size)
        }
        val prose="人物说：\n第一章的事情已经讲过了。\n1、苹果\n2、梨\n接着他们离开了。"
        assertEquals(1,ReaderCore.parseTxt(prose.toByteArray(),"测试").chapters.size)
    }
    @Test fun advertisingIsConservativeAndOptional(){
        val text="再痕中文 www.ZaiHen.com\n书名:测试\n作者:作者甲\n欢迎光临本站下载更多小说.\n\n正文 第一节   字数: 10字\n\n他看见广告，说：欢迎光临本站下载更多小说。\n本书由某某阅读网整理提供。\n这是普通故事。"
        val cleaned=AdCleaner.clean(text)
        assertFalse(cleaned.contains("www.ZaiHen.com"))
        assertFalse(cleaned.contains("本书由某某阅读网整理提供。"))
        assertTrue(cleaned.contains("作者:作者甲"))
        assertTrue(cleaned.contains("他看见广告，说：欢迎光临本站下载更多小说。"))
        assertEquals("正文中的小说网站 www.example.com 是个线索。",AdCleaner.clean("正文中的小说网站 www.example.com 是个线索。"))
        val watermarks="示例中文 www.example.com\n\n示例衶文 Ｅxample。ＣＯＭ 全汶字板阅读\n\n故事\n\n示例中文 example.com 全文字版阅读\n\n作者:保留"
        assertEquals("\n\n故事\n\n\n作者:保留",AdCleaner.clean(watermarks))
    }
    @Test fun adOnlyCleanupKeepsCustomChapterStructure(){
        val text="本书由某某阅读网整理提供。\n保留的故事。"
        val existing=Novel("书",listOf(Chapter("我的标题",text)),"UTF-8")
        val cleaned=LocalPrepare.read(text.toByteArray(),"书.txt",ImportAgent.Options(encoding=true,chapters=false,tidy=false,ads=true),existing)
        assertEquals("我的标题",cleaned.chapters.single().title)
        assertEquals("保留的故事。",cleaned.chapters.single().text)
        val untouched=LocalPrepare.read(text.toByteArray(),"书.txt",ImportAgent.Options(false,false,false,false),existing)
        assertEquals(existing.chapters,untouched.chapters)
    }
    @Test fun suppliedCorpusHasExactExportBoundariesAndUnmodifiedBodies(){
        val directory=System.getenv("LEMON_CORPUS")?.let{File(it)}
        assumeTrue("Optional private corpus is not packaged in the app",directory?.isDirectory==true)
        val expected=File(directory,"expected.tsv").readLines().associate{line->line.split('\t').let{it[0] to it[1].toInt()}}
        var total=0;val report=StringBuilder("file\tsections\tparse_ms\twithout_count_exact\twithout_prefix_exact\tads_removed\n")
        for((name,count)in expected){
            val bytes=File(directory,name).readBytes();val text=ReaderCore.decode(bytes).first.replace("\r\n","\n").replace('\r','\n')
            // Independently enumerate exporter boundaries, then compare every complete body slice.
            val pattern=Regex("(?m)^([^\\n]{1,140}?)\\s+字数[:：]\\s*\\d+\\s*字[ \\t]*$")
            val boundaries=pattern.findAll(text).toList()
            assertEquals(name,count,boundaries.size)
            val start=System.nanoTime();val result=ReaderCore.parseTxt(bytes,name,tidy=false)
            val duration=(System.nanoTime()-start)/1_000_000.0
            assertEquals(name,count+1,result.chapters.size)
            assertEquals(name,text.substring(0,boundaries.first().range.first).trim(),result.chapters.first().text)
            boundaries.forEachIndexed{i,boundary->
                val title=boundary.groupValues[1].trim().removePrefix("正文 ").trim()
                assertEquals("$name title $i",title,result.chapters[i+1].title)
                val end=boundaries.getOrNull(i+1)?.range?.first?:text.length
                assertEquals("$name body $i",text.substring(boundary.range.last+1,end).trim(),result.chapters[i+1].text)
            }
            val textLines=text.lines();val exportLine=Regex(".*\\s+字数[:：]\\s*\\d+\\s*字\\s*$")
            val expectedLines=textLines.indices.filter{exportLine.matches(textLines[it])}.toSet()
            val noCount=text.lines().map{line->line.replace(Regex("\\s+字数[:：]\\s*\\d+\\s*字\\s*$"),"")}
            val noPrefix=noCount.map{it.removePrefix("正文 ")}
            val countExact=TxtDirectory.headings(noCount).keys==expectedLines
            val prefixExact=TxtDirectory.headings(noPrefix).keys==expectedLines
            val removed=text.lines().size-AdCleaner.clean(text).lines().size
            total+=count;report.append("$name\t$count\t$duration\t$countExact\t$prefixExact\t$removed\n")
        }
        assertEquals(107,expected.size);assertEquals(8014,total)
        File(directory!!.parentFile,"corpus-results.tsv").writeText(report.toString())
    }
}
