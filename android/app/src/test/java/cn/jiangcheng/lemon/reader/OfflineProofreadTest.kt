package cn.jiangcheng.lemon.reader
import org.junit.Test
import org.junit.Assert.*

class OfflineProofreadTest {
    @Test fun preservesWordsWhileJoiningExportWraps(){
        val line="他走过漫长的街道看见远处的灯光依然照着回家的路"
        val input="第一章 夜色\n"+List(9){line}.joinToString("\n")+"\n终于到家了。\n\n第二章 天明\n新的故事。"
        val result=OfflineProofread.apply(input)
        assertTrue(result.lines().size<input.lines().size)
        assertEquals(input.filterNot{it.isWhitespace()},result.filterNot{it.isWhitespace()})
        assertTrue(result.contains("\n第二章 天明\n"))
    }
    @Test fun ambiguousPunctuationPoemsAndDialogueStay(){
        for(input in listOf("他看了一眼。转身离开。\n她说：明天再来", "床前明月光\n疑是地上霜\n举头望明月\n低头思故乡", "他说：“你好”\n她答：“你好”"))assertEquals(input,OfflineProofread.apply(input))
        assertEquals("他说：“明天见。”",OfflineProofread.apply("他说：“\n　　明天见。”"))
    }
    @Test fun incompleteHeadingsRecognizedWithoutRewritingBody(){
        for(headings in listOf(listOf("第一○○回 夜色","第一○一回 天明","第一○二回 相遇"),listOf("第一百 起点","第一百零一 转折","第一百零二 终点"),listOf("一百章 起点","一百零一章 转折","一百零二章 终点"))){
            val input=headings.joinToString("\n"){it+"\n"+"正文。".repeat(100)}
            assertEquals(3,TxtDirectory.headings(input.lines()).size)
        }
    }
}
