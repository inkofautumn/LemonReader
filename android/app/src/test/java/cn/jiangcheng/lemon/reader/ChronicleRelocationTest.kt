package cn.jiangcheng.lemon.reader

import org.junit.Assert.*
import org.junit.Test

class ChronicleRelocationTest {
    @Test fun insertionMovesBothKnowledgeBoundaryAndExcerpt(){
        val e=Chronicle(kind="人物",title="甲",content="知道结局",chapter=0,offset=2,evidence="甲乙",contextEnd=4)
        val result=ChronicleCatalog.relocate(listOf(e),1,listOf(Chapter("一","前言甲乙丙丁"))){Position(0,it.offset+2)}.single()
        assertEquals(4,result.offset);assertEquals(6,result.contextEnd);assertEquals(2,result.revision)
        assertTrue(ChronicleCatalog.visible(listOf(result),Position(0,4),2).isEmpty())
    }
    @Test fun removedEvidenceStaysHistorical(){
        val e=Chronicle(kind="线索",title="甲",content="旧摘录",chapter=0,offset=2,evidence="甲乙",contextEnd=4)
        val result=ChronicleCatalog.relocate(listOf(e),1,listOf(Chapter("一","丙丁"))){Position(0,(it.offset-2).coerceAtLeast(0))}.single()
        assertEquals(e,result);assertEquals("甲乙",result.evidence)
    }
    @Test fun unrelatedOldRevisionIsNotReattached(){
        val old=Chronicle(kind="人物",title="甲",content="旧版资料",chapter=0,offset=2,revision=1)
        assertEquals(listOf(old),ChronicleCatalog.relocate(listOf(old),2,listOf(Chapter("一","新正文"))){it})
    }
}
