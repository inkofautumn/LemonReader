package cn.jiangcheng.lemon.reader

/** Conservative, offline normalization. Uncertain paragraph boundaries stay untouched. */
object LocalPrepare {
    fun read(bytes:ByteArray,name:String,options:ImportAgent.Options,existing:Novel?=null):Novel {
        val structured=name.substringAfterLast('.').lowercase() in listOf("epub","mobi","azw3","tgtxt")
        val raw=when(name.substringAfterLast('.').lowercase()){
            "epub"->NovelImporter.epub(bytes,name,false)
            "mobi","azw3"->MobiImporter.read(bytes,name)
            "tgtxt"->ScanCatalog.unpack(bytes)
            else->{val decoded=ReaderCore.decode(bytes);Novel(name.substringBeforeLast('.'),listOf(Chapter("正文",decoded.first)),decoded.second)}
        }
        // Encoding repair starts from original bytes. Formatting alone retains the current structure.
        val base=if(existing!=null&&!options.chapters&&!options.encoding)existing else raw
        val chapters=base.chapters.map{it.copy(text=clean(if(options.ads)AdCleaner.clean(it.text)else it.text,options.tidy))}
        if(structured||!options.chapters){
            if(existing!=null&&options.encoding&&!options.chapters&&!structured){
                val parsed=ReaderCore.parseTxt(chapters.single().text.toByteArray(),base.title,tidy=false)
                if(parsed.chapters.map{it.title}==existing.chapters.map{it.title})return base.copy(chapters=parsed.chapters)
                // No reliable mapping to existing boundaries: retain them rather than guessing.
                return existing.copy(chapters=existing.chapters.map{it.copy(text=clean(if(options.ads)AdCleaner.clean(it.text)else it.text,options.tidy))},warnings=listOf("保留已有章节结构"))
            }
            return base.copy(chapters=chapters)
        }
        val text=chapters.joinToString("\n"){it.text}
        val parsed=ReaderCore.parseTxt(text.toByteArray(),base.title,tidy=false)
        return base.copy(chapters=parsed.chapters,warnings=parsed.warnings+"本地编码、格式与目录处理完成")
    }
    fun clean(text:String,tidy:Boolean):String {
        val normalized=text.removePrefix("\uFEFF").replace("\r\n","\n").replace('\r','\n')
        if(!tidy)return normalized
        val safe=normalized.filter{it=='\n'||it=='\t'||it.code>=32}.replace("\u0000","")
        val lines=safe.lines().map{it.trimEnd(' ','\t','　')}
        val result=StringBuilder();var blanks=0
        for(line in lines){if(line.isBlank()){blanks++;if(blanks>1)continue}else blanks=0;result.append(line).append('\n')}
        return OfflineProofread.apply(result.toString().trim('\n'))
    }
}
