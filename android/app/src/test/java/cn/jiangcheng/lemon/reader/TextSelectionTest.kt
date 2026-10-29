package cn.jiangcheng.lemon.reader
import org.junit.Assert.*
import org.junit.Test

class TextSelectionTest {
    @Test fun selectsSentenceWithoutConsumingNeighbours(){
        val s="上句。  夜色落在江面上，远处有灯火。下一句！"
        val(a,b)=TextSelection.sentence(s,s.indexOf("江"))
        assertEquals("夜色落在江面上，远处有灯火。",s.substring(a,b))
    }
    @Test fun handlesEmptyAndUnpunctuatedParagraph(){
        assertEquals(0 to 0,TextSelection.sentence("",0))
        assertEquals(3 to 6,TextSelection.sentence("前段\n后三字\n末",4))
    }
    @Test fun emojiIsNeverSplit(){
        assertEquals(1,TextSelection.boundary("甲😀乙",2))
        assertEquals(3,TextSelection.boundary("甲😀乙",3))
    }
    @Test fun sentenceIsClippedToVisiblePage(){
        val text="上一句。跨页的这一整句话有一部分在上一页，也有一部分在下一页。下一句。"
        val sentence=TextSelection.sentence(text,text.indexOf("整句"))
        val visibleStart=text.indexOf("这一")
        val visibleEnd=text.indexOf("在下一页")
        assertEquals(visibleStart to visibleEnd,TextSelection.visibleSentence(text,text.indexOf("整句"),visibleStart,visibleEnd))
        assertTrue(sentence.first<visibleStart)
        assertTrue(sentence.second>visibleEnd)
    }
    @Test fun trimsAsciiAndFullWidthPaddingWithoutTouchingInnerSpacing(){
        val text="\u3000 \t「成功了吗！？」　 \n"
        val range=TextSelection.trimWhitespace(text,0,text.length)
        assertEquals("「成功了吗！？」",text.substring(range.first,range.second))
        assertEquals(3,range.first)
        assertEquals(text.indexOf('」')+1,range.second)
    }
    @Test fun trimsInvisiblePresentationPadding(){
        val text="\uFEFF\u200B\u2060正文\u00A0"
        val range=TextSelection.trimWhitespace(text,0,text.length)
        assertEquals("正文",text.substring(range.first,range.second))
    }
    @Test fun exactUnderlineTogglesOnlyTheSameVisibleRange(){
        val text="　正文划线。　下一句。"
        val start=text.indexOf('正');val end=text.indexOf('。')+1
        val same=Note(chapter=2,start=0,end=end+1,quote=text.substring(0,end+1),body="",type="划线")
        val note=Note(chapter=2,start=start,end=end,quote=text.substring(start,end),body="批注",type="札记")
        val other=Note(chapter=2,start=end+1,end=text.length,quote="下一句。",body="",type="划线")
        val changed=same.copy(id="changed",changed=true)
        assertEquals(listOf(same.id),TextSelection.exactUnderlines(listOf(same,note,other,changed),2,text,start,end).map{it.id})
        assertTrue(TextSelection.exactUnderlines(listOf(same),1,text,start,end).isEmpty())
    }
}
