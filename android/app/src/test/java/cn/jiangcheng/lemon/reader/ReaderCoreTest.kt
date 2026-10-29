package cn.jiangcheng.lemon.reader
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.Charset

class ReaderCoreTest {
    @Test fun importOptionsControlDetectionCompletionAndDecoding(){
        val source="第一章 起点\n你好。\n第二章 夜航\n晚安。"
        assertEquals(1,ReaderCore.parseTxt(source.toByteArray(),"书.txt",recognize=false).chapters.size)
        assertEquals("正文",ReaderCore.parseTxt("无标题正文。".toByteArray(),"书.txt").chapters.single().title)
        val bytes=source.toByteArray(Charset.forName("GB18030"))
        assertEquals("GB18030",ReaderCore.parseTxt(bytes,"书.txt").encoding)
        assertEquals("UTF-8",ReaderCore.parseTxt(bytes,"书.txt",correctEncoding=false).encoding)
    }
    @Test fun untitledTextUsesNeutralSectionsWithoutLosingContent(){
        val source=("结局哨兵不应进入目录，江水依然平静。\n".repeat(800)).trim()
        val novel=ReaderCore.parseTxt(source.toByteArray(),"测试.txt",tidy=false,completeTitles=true)
        assertTrue(novel.chapters.size>1)
        assertEquals(source,novel.chapters.joinToString(""){it.text})
        assertTrue(novel.chapters.all{it.title.matches(Regex("第\\d+节"))})
        assertTrue(novel.warnings.any{it.contains("自动分节")})
    }
    @Test fun txtEncodingAndDirectory() {
        val text = "第一章 起点\r\n你好世界。\r\n\r\n第二章 相遇\r\n今天下雨。"
        for (encoding in listOf("UTF-8", "GB18030", "UTF-16")) {
            val novel = ReaderCore.parseTxt(text.toByteArray(Charset.forName(encoding)), "测试.txt")
            assertEquals(2, novel.chapters.size)
            assertEquals("你好世界。", novel.chapters[0].text)
        }
    }
    @Test fun antiSpoilerCutsBeforeRetrievalIncludingSameChapter() {
        val chapters = listOf(Chapter("一", "张三进城。"), Chapter("二", "李四来了。幕后凶手是李四。"), Chapter("三", "张三死了。"))
        val context = ReaderCore.context(chapters, Position(1, 5), "凶手是谁，张三死了？")
        assertTrue(context.contains("张三进城"))
        assertFalse(context.contains("凶手是李四"))
        assertFalse(context.contains("张三死了"))
    }
    @Test fun snapshotRetainsHandwrittenBodyAndFindsChangedQuote() {
        val old = listOf(Chapter("原名", "开头甲方原来的话结尾"))
        val newer = listOf(Chapter("改名", "开头甲方修改的话结尾"))
        val note = Note(chapter=0,start=4,end=8,quote="原来的话",body="我的理解",before="开头甲方",after="结尾")
        val mapped = ReaderCore.remap(note, old, newer)
        assertTrue(mapped.changed)
        assertEquals("修改的话", mapped.replacement)
        assertEquals("原来的话", mapped.quote)
        assertEquals("我的理解", mapped.body)
    }
    @Test fun ambiguousQuoteMustNotAttachToUnrelatedText() {
        val note = Note(chapter=0,start=0,end=2,quote="重复",body="想法")
        val mapped = ReaderCore.remap(note,listOf(Chapter("一","重复")),listOf(Chapter("一","重复之后又重复")))
        assertTrue(mapped.changed)
        assertNull(mapped.replacement)
    }
    @Test fun manualAndDeletedChroniclesAreNotOverwritten() {
        val manual = Chronicle(kind="人物",title="张三",content="我改过",chapter=0,offset=5,manual=true)
        val deleted = Chronicle(kind="线索",title="信件",content="隐藏",chapter=0,offset=2,deleted=true)
        val result = ReaderCore.mergeChronicles(listOf(manual,deleted),listOf(manual.copy(content="AI内容",manual=false),deleted.copy(deleted=false)))
        assertEquals(listOf(manual,deleted), result)
    }

    @org.junit.Test fun reorganizeKeepsTextPositionAndQuote(){
        val first="江边的灯火逐渐亮起来，小舟缓缓靠岸。";val second="林舟推开书店的门，柜台上放着一封还没有拆开的信。"
        val old=listOf(Chapter("正文",first+"\n"+second));val newer=listOf(Chapter("初见",first),Chapter("来信",second))
        org.junit.Assert.assertEquals(Position(1,12),ReaderCore.remapPosition(Position(0,first.length+1+12),old,newer))
        val quote=second.take(12);val mapped=ReaderCore.remap(Note(chapter=0,start=first.length+1,end=first.length+1+12,quote=quote,body="保留感想"),old,newer)
        org.junit.Assert.assertEquals(1,mapped.chapter);org.junit.Assert.assertEquals("保留感想",mapped.body);org.junit.Assert.assertFalse(mapped.changed)
        org.junit.Assert.assertEquals(Position(0,0),ReaderCore.remapPosition(Position(0,12),old,listOf(Chapter("另一本","完全不同的文本"),Chapter("后文","新文本"))))
    }
    @Test fun equalChapterCountIsNotProofForReconnect() {
        val note=Note(chapter=0,start=0,end=4,quote="重复摘录",body="保留批注")
        val mapped=ReaderCore.remap(note,listOf(Chapter("旧一","重复摘录"),Chapter("旧二","其他")),listOf(Chapter("新甲","重复摘录在此"),Chapter("新乙","另一处重复摘录")))
        assertTrue(mapped.changed)
        assertEquals("重复摘录",mapped.quote)
        assertEquals("保留批注",mapped.body)
        assertNull(mapped.replacement)
    }
}
