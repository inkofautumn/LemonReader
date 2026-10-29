package cn.jiangcheng.lemon.reader

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object MobiImporter {
    init { System.loadLibrary("lemon_mobi") }
    private external fun unpack(bytes:ByteArray):Array<ByteArray>
    fun read(bytes:ByteArray,name:String):Novel {
        val parts=unpack(bytes)
        require(parts.indices.step(2).any{String(parts[it],Charsets.UTF_8)=="content.opf"}){"MOBI 缺少阅读顺序信息"}
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use{zip->
            fun entry(path:String,data:ByteArray){zip.putNextEntry(ZipEntry(path));zip.write(data);zip.closeEntry()}
            entry("META-INF/container.xml","""<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""".toByteArray())
            for(i in parts.indices.step(2))entry(String(parts[i],Charsets.UTF_8),parts[i+1])
        }
        return NovelImporter.epub(output.toByteArray(),name).copy(encoding="MOBI",warnings=listOf("按文件阅读顺序导入"))
    }
}
