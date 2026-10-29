package cn.jiangcheng.lemon.reader
import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.nio.file.Files
import java.util.zip.*
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile

class NestedArchiveTest {
    private fun zip(vararg entries:Pair<String,ByteArray>):ByteArray=ByteArrayOutputStream().also{buffer->ZipOutputStream(buffer).use{z->entries.forEach{(name,bytes)->z.putNextEntry(ZipEntry(name));z.write(bytes);z.closeEntry()}}}.toByteArray()
    @Test fun nestedMixedArchivesAndEpubStayBooks(){
        val root=Files.createTempDirectory("nested-archive").toFile()
        try{
            val txt="第一章 相遇\n这是原始正文。".toByteArray()
            val inner=File(root,"inner.7z");val input=File(root,"book.txt").apply{writeBytes(txt)}
            SevenZOutputFile(inner).use{z->z.putArchiveEntry(z.createArchiveEntry(input,"深层书.txt"));z.write(txt);z.closeArchiveEntry()}
            val epub=zip("mimetype" to "application/epub+zip".toByteArray())
            val source=File(root,"outer.zip删").apply{writeBytes(zip("一层.txt" to txt,"套娃.zip" to zip("再一层.7z删除我" to inner.readBytes()),"书.epub" to epub))}
            val result=ArchiveImport.extract(source,File(root,"out"));assertEquals(3,result.size)
            assertArrayEquals(epub,result.first{it.extension=="epub"}.readBytes())
            result.filter{it.extension=="txt"}.forEach{assertArrayEquals(txt,it.readBytes())}
        }finally{root.deleteRecursively()}
    }
    @Test fun limitsApplyAcrossLayersAndFailureRollsBack(){
        val root=Files.createTempDirectory("nested-limits").toFile()
        try{
            val source=File(root,"outer.zip").apply{writeBytes(zip("inner.zip" to zip("book.txt" to ByteArray(300){65})))}
            for(limits in listOf(ArchiveImport.Limits(depth=1),ArchiveImport.Limits(entries=1),ArchiveImport.Limits(total=350))){
                val out=File(root,"out");assertTrue(runCatching{ArchiveImport.extract(source,out,limits)}.isFailure);assertFalse(out.exists());assertTrue(source.isFile)
            }
            source.writeBytes(zip("inner.zip" to zip("../escape.txt" to byteArrayOf(1))))
            assertTrue(runCatching{ArchiveImport.extract(source,File(root,"out"))}.isFailure)
            assertFalse(File(root,"escape.txt").exists());assertFalse(File(root,"out").exists())
        }finally{root.deleteRecursively()}
    }
}
