package cn.jiangcheng.lemon.reader
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
class EpubTest {
    private fun epub(entries:Map<String,String>):ByteArray {
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use{zip->for((name,text) in entries){zip.putNextEntry(ZipEntry(name));zip.write(text.toByteArray());zip.closeEntry()}}
        return output.toByteArray()
    }
    @Test fun spineOrderAndEntitiesAreRespected() {
        val bytes=epub(mapOf(
            "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/book.opf"/></rootfiles></container>""",
            "OEBPS/book.opf" to """<package xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>夜航</dc:title></metadata><manifest><item id="a" href="a.xhtml"/><item id="b" href="b.xhtml"/></manifest><spine><itemref idref="b"/><itemref idref="a"/></spine></package>""",
            "OEBPS/a.xhtml" to "<html><body><h1>第二章</h1><p>后来。</p></body></html>",
            "OEBPS/b.xhtml" to "<html><body><h1>第一章</h1><p>江水&amp;灯火&#12290;</p><script>evil()</script></body></html>"
        ))
        val novel=NovelImporter.epub(bytes,"example.epub")
        assertEquals("夜航",novel.title)
        assertEquals("第一章",novel.chapters.first().title)
        assertTrue(novel.chapters.first().text.contains("江水&灯火。"))
        assertFalse(novel.chapters.first().text.contains("evil"))
    }
    @Test(expected=IllegalArgumentException::class) fun zipTraversalIsRejected(){NovelImporter.epub(epub(mapOf("../outside" to "bad")),"bad.epub")}
    @Test fun tocFragmentsSplitOneSpineDocument() {
        val bytes=epub(mapOf(
            "META-INF/container.xml" to """<container><rootfile full-path="book.opf"/></container>""",
            "book.opf" to """<package><manifest><item id="body" href="body.xhtml"/><item id="nav" href="nav.xhtml" properties="nav"/></manifest><spine><itemref idref="body"/></spine></package>""",
            "nav.xhtml" to """<html xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><a href="body.xhtml#a">江边初遇</a><a href="body.xhtml#b">雨中归来</a></nav></body></html>""",
            "body.xhtml" to """<html><body><h1 id="a">第一章</h1><p>旧灯火。</p><h1 id="b">第二章</h1><p>新雨声。</p></body></html>"""
        ))
        val novel=NovelImporter.epub(bytes,"toc.epub")
        assertEquals(listOf("江边初遇","雨中归来"),novel.chapters.map{it.title})
        assertFalse(novel.chapters.first().text.contains("新雨声"))
        assertTrue(novel.chapters.last().text.contains("新雨声"))
    }
}
