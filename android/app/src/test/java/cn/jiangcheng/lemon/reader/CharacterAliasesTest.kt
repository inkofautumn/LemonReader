package cn.jiangcheng.lemon.reader

import org.junit.Assert.*
import org.junit.Test

class CharacterAliasesTest {
    private fun person(id:String,name:String,content:String="记录",offset:Int=10,evidence:String=name)=Chronicle(id=id,kind="人物",title=name,content=content,chapter=0,offset=offset,evidence=evidence)
    private fun items(records:List<Chronicle>,end:Int=100)=ChronicleCatalog.items(records,emptyList(),Position(0,end),1)
    @Test fun uniqueSuffixIsSuggestedButNeverSilentlyMerged(){
        val list=items(listOf(person("a","菜月昴"),person("b","昴")))
        assertEquals(2,list.size);val match=CharacterAliases.suggestions(list).single()
        assertEquals("菜月昴",match.full.title);assertEquals("昴",match.short.title);assertFalse(match.ambiguous)
    }
    @Test fun ambiguousSuffixOffersBothIdentities(){
        val list=items(listOf(person("a","白绨雅"),person("b","黑绨雅"),person("c","绨雅")))
        val matches=CharacterAliases.suggestions(list);assertEquals(2,matches.size);assertTrue(matches.all{it.ambiguous});assertEquals(3,list.size)
    }
    @Test fun explicitAliasEvidenceGroupsOldRecordsWithoutChangingSnapshots(){
        val a=person("a","菜月昴","别名：昴",30,"菜月昴，简称昴。");val b=person("b","昴")
        val result=items(listOf(a,b)).single();assertEquals("菜月昴",result.title);assertEquals(2,result.records.size)
        assertEquals(listOf(a,b).map{it.content},result.records.sortedBy{it.id}.map{it.content});assertTrue(a.entity.isEmpty())
    }
    @Test fun modelAliasClaimWithoutProofOnlySuggests(){
        val result=items(listOf(person("a","江宁","别名：小舟",30,"江宁走出城门。"),person("b","小舟")))
        assertEquals(2,result.size);assertEquals(1,CharacterAliases.suggestions(result).size)
    }
    @Test fun aliasesLearnedLaterAreNotAppliedToEarlierReading(){
        val records=listOf(person("a","菜月昴","别名：昴",60,"菜月昴，简称昴。"),person("b","昴",offset=10))
        assertEquals("昴",items(records,20).single().title);assertTrue(CharacterAliases.suggestions(items(records,20)).isEmpty())
    }
    @Test fun deletedAndOtherRevisionRecordsNeverBecomeCandidates(){
        val records=listOf(person("a","菜月昴").copy(deleted=true),person("b","昴"),person("c","新月昴").copy(revision=2))
        assertEquals(1,items(records).size);assertTrue(CharacterAliases.suggestions(items(records)).isEmpty())
    }
    @Test fun confirmedMergeKeepsFullNameAndFutureShortNameObservations(){
        val records=listOf(person("a","昴"),person("b","菜月昴",offset=20));val match=CharacterAliases.suggestions(items(records)).single()
        val merged=ChronicleCatalog.merge(records,match.full,match.short)
        val next=ReaderCore.mergeChronicles(merged,listOf(person("c","昴",offset=40,evidence="昴走回家。")))
        val card=items(next).single();assertEquals("菜月昴",card.title);assertEquals(3,card.records.size)
        assertEquals(1,ChronicleCatalog.sort(listOf(card),"名称","昴").size)
    }
    @Test fun inferredGroupDeleteAndManualMergeReachAllOriginalIds(){
        val records=listOf(person("a","菜月昴","别名：昴",30,"菜月昴，简称昴。"),person("b","昴"),person("c","小昴",offset=40))
        val groups=items(records);val full=groups.first{it.title=="菜月昴"};val short=groups.first{it.title=="小昴"}
        assertEquals(2,ChronicleCatalog.delete(records,full).count{it.deleted})
        assertEquals(1,items(ChronicleCatalog.merge(records,full,short)).size)
    }
    @Test fun ambiguousIdentityWordingIsNotProof(){
        for(quote in listOf("不是菜月昴就是昴。", "菜月昴又名昴的说法并不正确。")){
            assertEquals(2,items(listOf(person("a","菜月昴","别名：昴",30,quote),person("b","昴"))).size)
        }
    }
    @Test fun mergedAliasesCountEachMentionOnce(){
        val text="菜月昴遇见昴。菜月昴又走开。";val records=listOf(person("a","菜月昴").copy(entity="x"),person("b","昴").copy(entity="x"))
        val card=ChronicleCatalog.items(records,listOf(Chapter("章",text)),Position(0,text.length),1).single()
        assertEquals(3,card.frequency)
    }
}
