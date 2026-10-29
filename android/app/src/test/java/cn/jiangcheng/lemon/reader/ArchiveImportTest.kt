package cn.jiangcheng.lemon.reader

import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile

class ArchiveImportTest {
    @Test fun contentDetectionAndTraversal(){
        val root=Files.createTempDirectory("archive-test").toFile()
        try{
            val source=File(root,"小说.zip删")
            ZipOutputStream(source.outputStream()).use{it.putNextEntry(ZipEntry("../escape.txt"));it.write("bad".toByteArray());it.closeEntry()}
            assertEquals("zip",ArchiveImport.type(source));assertTrue(ArchiveImport.named(source.name))
            assertTrue(runCatching{ArchiveImport.extract(source,File(root,"out"))}.isFailure)
            assertFalse(File(root,"escape.txt").exists());assertFalse(File(root,"out").exists())
        }finally{root.deleteRecursively()}
    }
    @Test fun renamedSevenZipRoundtrip(){
        val root=Files.createTempDirectory("seven-test").toFile()
        try{
            val input=File(root,"章节.txt").apply{writeText("第一章 你好\n原始正文。")};val archive=File(root,"小说.7z删除我")
            SevenZOutputFile(archive).use{z->z.putArchiveEntry(z.createArchiveEntry(input,"书源/章节.txt"));z.write(input.readBytes());z.closeArchiveEntry()}
            assertEquals("7z",ArchiveImport.type(archive))
            assertEquals(input.readText(),ArchiveImport.extract(archive,File(root,"out")).single().readText())
        }finally{root.deleteRecursively()}
    }
    @Test fun actualDzipMatchesAlreadyExtractedFortyBooks(){
        val source=System.getenv("LEMON_DZIP")?.let{File(it)};val original=System.getenv("LEMON_DZIP_FOLDER")?.let{File(it)}
        assumeTrue(source?.isFile==true&&original?.isDirectory==true)
        val root=Files.createTempDirectory("actual-dzip").toFile()
        try{val files=ArchiveImport.extract(source!!,File(root,"out"));assertEquals(40,files.size)
            for(file in files){val expected=original!!.walkTopDown().first{it.isFile&&it.name==file.name};assertArrayEquals(file.name,expected.readBytes(),file.readBytes())}
        }finally{root.deleteRecursively()}
    }
    @Test fun crossSourceCorpusReport(){
        val dir=System.getenv("LEMON_CROSS_CORPUS")?.let{File(it)};assumeTrue(dir?.isDirectory==true)
        val report=StringBuilder("file\tchapters\tfirst_title\tshortest_body\tms\n")
        for(file in dir!!.listFiles()!!.filter{it.extension=="txt"}.sortedBy{it.name}){
            val started=System.nanoTime();val book=ReaderCore.parseTxt(file.readBytes(),file.name)
            assertTrue(file.name,book.chapters.size>1)
            report.append("${file.name}\t${book.chapters.size}\t${book.chapters.getOrNull(1)?.title}\t${book.chapters.minOf{it.text.length}}\t${(System.nanoTime()-started)/1000000}\n")
            if(file.name=="pg-24264.txt")assertEquals(121,book.chapters.size)
            if(file.name=="pg-23950.txt")assertEquals(121,book.chapters.size)
            if(file.name=="pg-1342.txt")assertEquals(62,book.chapters.size)
        };File(dir.parentFile,"cross-results.tsv").writeText(report.toString())
    }
    @Test fun punctuationWhitespaceAndBrackets(){
        val body="正文一句话。".repeat(50)
        for(headings in listOf(listOf("第 1 章 问号？","第 2 章 相遇！","第 3 章 回家"),listOf("☆、第1章 起点","☆、第2章 终点","☆、第3章 新篇"),listOf("[1]倒数计时","[2]相遇","[3]结束"),listOf("CHAPTER I.","CHAPTER II.","CHAPTER III."))){
            val book=ReaderCore.parseTxt(headings.joinToString("\n"){"$it\n$body"}.toByteArray(),"test")
            assertEquals(3,book.chapters.size)
        }
    }
}
