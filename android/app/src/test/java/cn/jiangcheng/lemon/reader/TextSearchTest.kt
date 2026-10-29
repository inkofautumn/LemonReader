package cn.jiangcheng.lemon.reader

import org.junit.Assert.*
import org.junit.Test

class TextSearchTest {
    @Test fun normalizedSearchPreservesOriginalOffsets(){
        val text="林\n舟走进书店。ＡＢＣ又出现。林舟回头。"
        val chapters=listOf(Chapter("第一章",text))
        val matches=TextSearch.find(chapters,"  林舟\u200b  ")
        assertEquals(2,matches.size);assertEquals("林\n舟",text.substring(matches[0].start,matches[0].end))
        val latin=TextSearch.find(chapters,"abc").single();assertEquals("ＡＢＣ",text.substring(latin.start,latin.end))
    }
    @Test fun scopeAndNearestOrdering(){
        val chapters=listOf(Chapter("一","甲乙甲"),Chapter("二","甲丙甲"))
        assertEquals(4,TextSearch.find(chapters,"甲").size)
        assertEquals(3,TextSearch.find(chapters,"甲",Position(1,1)).size)
        val matches=TextSearch.find(chapters,"甲")
        assertEquals(TextSearch.Match(1,2,3),TextSearch.order(matches,chapters,Position(1,2),"离当前阅读位置最近").first())
        assertEquals(TextSearch.Match(0,0,1),TextSearch.order(matches,chapters,Position(1,2),"从头到尾").first())
    }
    @Test fun firstOccurrenceIsLocalAndChronological(){
        val chapters=listOf(Chapter("一","夜色里林舟走进书店。"),Chapter("二","林舟再次出现。"))
        val result=ResearchAgent(AiConfig("","","")).search(chapters,Position(1,9),"林舟首次出现的位置")
        assertEquals(1,result.size);assertEquals(0,result.single().chapter);assertEquals(3,result.single().offset)
        assertTrue(TextSearch.find(chapters,"   ").isEmpty())
    }
}
