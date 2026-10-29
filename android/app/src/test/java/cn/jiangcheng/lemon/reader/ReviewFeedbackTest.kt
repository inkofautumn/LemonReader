package cn.jiangcheng.lemon.reader
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class ReviewFeedbackTest {
    @Test fun frontMatterLabelAndExplicitPreface(){
        val body="故事开始。".repeat(80)
        val chapters=(1..3).joinToString("\n"){"第${it}章 相遇\n$body"}
        assertEquals("书前资料",ReaderCore.parseTxt("书名：样例\n作者：甲\n$chapters".toByteArray(),"样例").chapters.first().title)
        assertEquals("前言",ReaderCore.parseTxt("前言\n写给读者的话。\n$chapters".toByteArray(),"样例").chapters.first().title)
        assertEquals("正文",ReaderCore.parseTxt(body.toByteArray(),"样例").chapters.single().title)
        assertEquals("第一章 相遇",ReaderCore.parseTxt("第一章 相遇\n$body".toByteArray(),"样例").chapters.single().title)
    }
    @Test fun feedbackLinesStayInBody(){
        val lines=listOf("一节课晕晕乎乎上到一半，贺贞棠随手指一人，“你站起来解释，为何昆仑的万年无望崖是昆仑最高惩戒？”","[1]朱文婷.1例妊娠8月余合并敌敌畏中毒的护理[J].大家健康(学术版),2015,9(24):266.","84章修改之后放这里啦，怕影响大家连贯阅读的完整性所以改了改还是觉得放上来比较好~")
        val source="第一章 开始\n"+lines.joinToString("\n")+"\n第二章 相遇\n正文。\n第三章 结束\n结尾。"
        val book=ReaderCore.parseTxt(source.toByteArray(),"样例")
        assertEquals(3,book.chapters.size);lines.forEach{assertTrue(book.chapters.first().text.contains(it))}
        val bracket="[1]倒数计时\n正文。\n[2]相遇\n正文。\n[3]结束\n正文。"
        assertEquals(3,ReaderCore.parseTxt(bracket.toByteArray(),"样例").chapters.size)
        assertEquals(3,ReaderCore.parseTxt("Chapter 82\n正文\nChapter 83\n正文\nchapter 84\n正文".toByteArray(),"样例").chapters.size)
    }
    @Test fun originalReviewedFiles(){
        val dir=System.getenv("LEMON_CROSS_CORPUS")?.let{File(it)}
        org.junit.Assume.assumeTrue(dir?.isDirectory==true)
        val report=StringBuilder()
        for(name in listOf("collection-13.txt","collection-30.txt")){
            val book=LocalPrepare.read(File(dir!!,name).readBytes(),name,ImportAgent.Options())
            assertTrue(book.chapters.none{it.title.startsWith("一节课")||it.title.contains("[J]")||it.title.startsWith("84章修改")})
            report.append("$name\t${book.chapters.size}\n")
            if(name=="collection-30.txt")assertTrue(book.chapters.any{it.title=="chapter 84"})
        }
        File(dir!!.parentFile,"review-feedback-results.tsv").writeText(report.toString())
    }
}
