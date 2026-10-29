package cn.jiangcheng.lemon.reader

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ScanCatalogTest {
    @Test fun versionedSourceHeadersRemainReadable(){
        fun source(header:String)=java.io.ByteArrayOutputStream().also{bytes->java.io.DataOutputStream(bytes).use{out->
            out.writeUTF(header);out.writeUTF("桥边来信");out.writeInt(1);out.writeUTF("第一章");val text="灯下读信。".toByteArray();out.writeInt(text.size);out.write(text)
        }}.toByteArray()
        assertEquals("灯下读信。",ScanCatalog.unpack(source("READER-TXT-1")).chapters.single().text)
        assertThrows(Exception::class.java){ScanCatalog.unpack(source("READER-TXT-2"))}
    }
    private fun fixture(action:(File)->Unit){val dir=Files.createTempDirectory("scan-catalog").toFile();try{action(dir)}finally{dir.deleteRecursively()}}
    private fun file(root:File,name:String,body:String="正文内容")=File(root,name).apply{parentFile!!.mkdirs();writeText(body)}
    @Test fun naturalOrderingAndGrouping()=fixture{root->
        val chapters=listOf("第十章 夜归.txt","第二章 江面.txt","第一章 雨夜.txt").map{file(root,"长夜/$it")}
        val book=ScanCatalog.classify(chapters).single();assertTrue(book.certain)
        assertEquals(listOf("第一章 雨夜.txt","第二章 江面.txt","第十章 夜归.txt"),book.parts.map{it.name})
        assertEquals(listOf("1.txt","2.txt","10.txt"),ScanCatalog.sort(listOf("10.txt","2.txt","1.txt").map{File(it)}).map{it.name})
    }
    @Test fun ambiguousThousandFilesStayOneCardAndCanSplit()=fixture{root->
        val files=(1..1200).map{file(root,"下载组/小说编号${it}.txt")}
        val group=ScanCatalog.classify(files).single();assertFalse(group.certain);assertEquals(1200,group.parts.size)
        assertTrue(group.matches("编号999"));assertEquals(1200,ScanCatalog.classify(files){"separate"}.size)
        assertTrue(ScanCatalog.classify(files){"book"}.single().certain)
    }
    @Test fun ordinaryBooksAndDifferentFoldersNeverMerge()=fixture{root->
        val files=listOf("书库/三体.txt","书库/活着.txt","书库/围城.epub","甲/第一章.txt","甲/第二章.txt","甲/第三章.txt","乙/第一章.txt","乙/第二章.txt","乙/第三章.txt").map{file(root,it)}
        val entries=ScanCatalog.classify(files);assertEquals(5,entries.size);assertEquals(2,entries.count{it.grouped})
    }
    @Test fun sourceRoundtripPreservesEncodingAndIdentity()=fixture{root->
        val a=file(root,"书名/1.txt","首章\n原有段落");val b=file(root,"书名/2.txt").apply{writeBytes("第二章繁體內容".toByteArray(charset("GB18030")))}
        val entry=ScanCatalog.Entry(a.parentFile!!,listOf(a,b),true)
        val bytes=ScanCatalog.pack(entry);assertArrayEquals(bytes,ScanCatalog.pack(entry))
        val novel=ScanCatalog.unpack(bytes);assertEquals("书名",novel.title);assertEquals(2,novel.chapters.size)
        assertEquals("首章\n原有段落",novel.chapters[0].text);assertEquals("第二章繁體內容",novel.chapters[1].text)
        assertThrows(Exception::class.java){ScanCatalog.unpack(bytes.copyOf(bytes.size-1))}
    }
    @Test fun volumeFoldersRetainTheirOrderAndTitle()=fixture{root->
        val files=listOf("第二卷/第三章.txt","第二卷/第一章.txt","第二卷/第二章.txt","第一卷/第三章.txt","第一卷/第一章.txt","第一卷/第二章.txt").map{file(root,"长篇/$it")}
        val entry=ScanCatalog.classify(files).single();assertEquals("长篇",entry.title)
        val novel=ScanCatalog.unpack(ScanCatalog.pack(entry));assertEquals(6,novel.chapters.size);assertEquals("第一卷/第一章",novel.chapters.first().title)
        assertEquals(2,ScanCatalog.classify(files){if(it.name=="长篇")"separate"else""}.size)
    }
}
