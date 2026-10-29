package cn.jiangcheng.lemon.reader

import java.io.*
import java.nio.charset.Charset
import java.util.zip.ZipFile
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/** Content signatures, bounded extraction and private destinations; original archives are never edited. */
object ArchiveImport {
    fun named(name:String)=name.substringAfterLast('.').lowercase().let{it=="d"||it=="zi"||listOf("zip","dzip","7z","tar","gz","tgz","rar").any{kind->it.startsWith(kind)}}
    fun kind(bytes:ByteArray):String?=when{
        bytes.size>=4&&bytes[0]==80.toByte()&&bytes[1]==75.toByte()&&((bytes[2]==3.toByte()&&bytes[3]==4.toByte())||(bytes[2]==5.toByte()&&bytes[3]==6.toByte()))->"zip"
        bytes.size>=4&&bytes[0]==85.toByte()&&bytes[1]==65.toByte()&&bytes[2]==3.toByte()&&bytes[3]==4.toByte()->"dzip"
        bytes.take(6)==listOf(0x37,0x7a,0xbc,0xaf,0x27,0x1c).map{it.toByte()}->"7z"
        bytes.size>=2&&bytes[0]==0x1f.toByte()&&bytes[1]==0x8b.toByte()->"gzip"
        bytes.size>=262&&String(bytes,257,5,Charsets.US_ASCII)=="ustar"->"tar"
        bytes.size>=4&&String(bytes,0,4,Charsets.US_ASCII)=="Rar!"->"rar"
        else->null
    }
    fun type(file:File)=file.inputStream().use{val bytes=ByteArray(512);val n=it.read(bytes);kind(bytes.copyOf(n.coerceAtLeast(0)))}
    fun normalizeDzip(source:File,dest:File){
        source.copyTo(dest,overwrite=true)
        RandomAccessFile(dest,"rw").use{r->
            fun u16():Int=java.lang.Short.toUnsignedInt(java.lang.Short.reverseBytes(r.readShort()))
            fun u32():Long=Integer.toUnsignedLong(Integer.reverseBytes(r.readInt()))
            fun signature(at:Long,third:Int,fourth:Int):Boolean{if(at<0||at+4>r.length())return false;r.seek(at);return r.readUnsignedByte()==85&&r.readUnsignedByte()==65&&r.readUnsignedByte()==third&&r.readUnsignedByte()==fourth}
            fun patch(at:Long){r.seek(at);r.write(byteArrayOf(80,75))}
            var end=r.length()-22
            while(end>=(r.length()-66000).coerceAtLeast(0)&&!signature(end,5,6))end--
            require(end>=0&&signature(end,5,6)){"DZIP 目录记录损坏或版本不受支持"}
            r.seek(end+4);require(u16()==0&&u16()==0){"暂不支持分卷 DZIP"};val diskCount=u16();val count=u16();require(count==diskCount&&count in 1..10000){"压缩包文件数量超出限制"}
            val size=u32();var at=u32();require(at+size<=end){"DZIP 目录越界"}
            repeat(count){
                require(signature(at,1,2)){"DZIP 目录记录无效"};r.seek(at+8);val flags=u16();u16();r.seek(at+20);val packed=u32();r.seek(at+28);val n=u16();val e=u16();val c=u16();r.seek(at+42);val local=u32()
                require(flags and 1==0){"当前支持未加密 DZIP；此文件需要解压专家输入密码解压后导入"}
                require(signature(local,3,4)){"DZIP 文件记录无效"};r.seek(local+26);val localName=u16();val extra=u16();val descriptor=local+30+localName+extra+packed
                if(signature(descriptor,7,8))patch(descriptor)
                patch(local);patch(at);at+=46+n+e+c
            };patch(end)
        }
    }
    internal data class Limits(val depth:Int=5,val entries:Int=10000,val total:Long=512_000_000,val file:Long=80_000_000)
    private class Budget(val limits:Limits){var total=0L;var count=0}
    fun extract(source:File,destination:File,progress:(Int)->Unit={}):List<File> = extract(source,destination,Limits(),progress)
    internal fun extract(source:File,destination:File,limits:Limits,progress:(Int)->Unit={}):List<File> = extractNested(source,destination,Budget(limits),1,progress)
    private fun extractNested(source:File,destination:File,budget:Budget,depth:Int,progress:(Int)->Unit):List<File>{
        require(depth<=budget.limits.depth){"压缩包嵌套超过 ${budget.limits.depth} 层，请先拆分后导入"}
        require(!destination.exists()){ "解压目录已存在" };destination.mkdirs()
        val root=destination.canonicalFile;val paths=mutableSetOf<String>()
        fun write(name:String,directory:Boolean,input:InputStream){
            require(++budget.count<=budget.limits.entries){"压缩包文件过多"}
            val normalized=name.replace('\\','/');require(!normalized.startsWith('/')&&!normalized.contains(':')&&normalized.split('/').none{it==".."}){"压缩包包含非法路径"}
            val target=File(root,normalized).canonicalFile;require(target.toPath().startsWith(root.toPath())&&target!=root){"压缩路径越界"}
            require(paths.add(target.path)){"压缩包包含重复路径"}
            if(directory){target.mkdirs();return};target.parentFile!!.mkdirs();var size=0L
            target.outputStream().use{out->val buffer=ByteArray(32768);while(true){if(Thread.currentThread().isInterrupted)throw InterruptedIOException();val n=input.read(buffer);if(n<0)break;size+=n;budget.total+=n;require(size<=budget.limits.file&&budget.total<=budget.limits.total){"解压内容超过限制（单文件 80 MB、合计 512 MB）"};out.write(buffer,0,n)}};progress(budget.count)
        }
        try{
            when(type(source)){
                "zip","dzip"->{val converted=if(type(source)=="dzip")File(source.parentFile,"normalized-${java.util.UUID.randomUUID()}.zip").also{try{normalizeDzip(source,it)}catch(e:Exception){it.delete();throw e}}else source
                    try{val zip=try{ZipFile(converted,Charsets.UTF_8)}catch(e:IllegalArgumentException){ZipFile(converted,Charset.forName("GB18030"))}
                        zip.use{z->z.entries().asSequence().forEach{entry->z.getInputStream(entry).use{write(entry.name,entry.isDirectory,it)}}}
                    }finally{if(converted!=source)converted.delete()}}
                "7z"->SevenZFile.builder().setFile(source).setMaxMemoryLimitKiB(65536).get().use{z->while(true){val e=z.nextEntry?:break;require(!e.isAntiItem){"不支持反删除记录"};write(e.name,e.isDirectory,object:InputStream(){override fun read()=z.read();override fun read(b:ByteArray,off:Int,len:Int)=z.read(b,off,len)})}}
                "tar","gzip"->{val raw=source.inputStream();val input=if(type(source)=="gzip")GZIPInputStream(raw)else raw;TarArchiveInputStream(input).use{tar->while(true){val e=tar.nextTarEntry?:break;require(e.isDirectory||e.isFile){"不解压链接或特殊文件"};write(e.name,e.isDirectory,tar)}}}
                "rar"->error("此版本尚不支持 RAR 解压，请先解压后选择小说文件")
                else->error("无法识别压缩格式，请检查文件是否下载完整")
            }
            val ebooks=setOf("txt","epub","mobi","azw3")
            val files=root.walkTopDown().filter{it.isFile}.toList()
            val result=files.filter{it.extension.lowercase() in ebooks}.toMutableList()
            for(file in files){
                // EPUB is itself ZIP, but remains a book. Only containers are recursively expanded.
                if(file.extension.lowercase() in ebooks)continue
                if(type(file)!=null){
                    val folder=File(file.parentFile,file.name+"·解压-"+java.util.UUID.randomUUID().toString().take(8))
                    result.addAll(extractNested(file,folder,budget,depth+1,progress))
                }
            }
            return result
        }catch(e:Exception){root.deleteRecursively();throw e}
    }
}
