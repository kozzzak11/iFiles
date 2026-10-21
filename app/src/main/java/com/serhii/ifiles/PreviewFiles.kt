package com.serhii.ifiles

import java.io.*
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser
import android.util.Xml

/** Read-only preview helpers. No preview operation opens the source for writing. */
object PreviewFiles {
    const val TEXT_LIMIT=1024*1024
    fun hidden(name: String)=name.startsWith(".") || name in setOf("__MACOSX","System Volume Information", "$"+"RECYCLE.BIN")
    fun appleDouble(bytes: ByteArray)=bytes.size>=4 && bytes[0]==0.toByte() && bytes[1]==5.toByte() && bytes[2]==0x16.toByte() && bytes[3]==7.toByte()
    fun resolve(s: Storage,e: Entry): Entry {
        if(!e.name.startsWith("._"))return e
        val header=s.input(e.ref).use { read(it,4) }
        if(!appleDouble(header))return e
        val parent=s.parentOf(e.ref)
        val original=parent?.let { s.list(it).firstOrNull { x -> x.name==e.name.removePrefix("._") && !x.directory } }
        return original ?: throw IOException("Это служебный файл macOS, а не документ. Оригинал «${e.name.removePrefix("._")}» рядом не найден.")
    }
    fun kind(e: Entry,head: ByteArray): String {
        if(appleDouble(head))return "appledouble"
        val ext=e.name.substringAfterLast('.',"").lowercase()
        if(head.size>=5 && head.copyOfRange(0,5).contentEquals("%PDF-".toByteArray()))return "pdf"
        if(ext=="pdf" || e.mime=="application/pdf")return "pdf"
        if(ext in setOf("docx","xlsx","pptx","odt","ods","odp","epub"))return "document"
        if(ext=="zip" || e.mime=="application/zip")return "archive"
        if(e.mime.startsWith("image/") || ext in setOf("jpg","jpeg","png","gif","webp","bmp","heic","heif","avif"))return "image"
        if(e.mime.startsWith("audio/") || e.mime.startsWith("video/") || ext in setOf("mp3","m4a","aac","wav","flac","ogg","mp4","mov","mkv","webm","3gp"))return "media"
        if(ext in setOf("txt","md","csv","log","json","xml","kt","java","py","js","ts","html","htm","css","yaml","yml","ini","conf","rtz","usr","srt","vtt") || isText(head))return "text"
        return "binary"
    }
    fun isText(bytes: ByteArray): Boolean {
        if(bytes.isEmpty())return true
        if(bytes.size>=2 && ((bytes[0]==0xff.toByte() && bytes[1]==0xfe.toByte()) || (bytes[0]==0xfe.toByte() && bytes[1]==0xff.toByte())))return true
        if(bytes.any {it==0.toByte()})return false
        if(bytes.count { (it.toInt() and 255)<32 && it!=9.toByte() && it!=10.toByte() && it!=13.toByte() } * 20 > bytes.size)return false
        return try { Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes));true } catch(e: java.nio.charset.CharacterCodingException){false}
    }
    fun read(input: InputStream,limit: Int): ByteArray {
        val out=ByteArrayOutputStream();val buf=ByteArray(8192)
        while(out.size()<limit) {val n=input.read(buf,0,minOf(buf.size,limit-out.size()));if(n<0)break;if(n==0){val one=input.read();if(one<0)break;out.write(one)}else out.write(buf,0,n)}
        return out.toByteArray()
    }
    fun text(bytes: ByteArray): String {
        val charset=when {
            bytes.size>=2 && bytes[0]==0xff.toByte() && bytes[1]==0xfe.toByte() -> Charsets.UTF_16LE
            bytes.size>=2 && bytes[0]==0xfe.toByte() && bytes[1]==0xff.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(bytes.take(TEXT_LIMIT).toByteArray(),charset).removePrefix("\uFEFF") + if(bytes.size>TEXT_LIMIT)"\n\n…Показан первый 1 МБ." else ""
    }
    /** Deletes incomplete outputs on disconnect, truncated provider streams, cancellation, or full disk. */
    fun copy(input: InputStream,target: File,expected: Long,checkpoint: ()->Unit): File {
        try {
            input.use { source -> target.outputStream().use { out ->
                val buffer=ByteArray(256*1024);var total=0L
                while(true) {checkpoint();val n=source.read(buffer);if(n<0)break
                    if(n==0)continue
                    if(target.parentFile!!.usableSpace < n+8L*1024*1024)throw IOException("Недостаточно памяти для быстрого просмотра")
                    out.write(buffer,0,n);total+=n
                }
                if(expected>=0 && total!=expected)throw IOException("Файл прочитан не полностью. Проверьте подключение USB и повторите.")
            }}
            return target
        } catch(e: Throwable) {target.delete();throw e}
    }
    fun packageText(file: File,archive: Boolean,checkpoint: ()->Unit): String {
        val result=StringBuilder();var expanded=0L;var inspected=0
        ZipFile(file).use { zip ->
            fun xml(bytes: ByteArray)=Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true)
                setInput(ByteArrayInputStream(bytes),null)
            }
            fun entryBytes(name: String):ByteArray? {
                val entry=zip.getEntry(name) ?: return null
                val bytes=zip.getInputStream(entry).use {read(it,4*1024*1024+1)}
                expanded+=bytes.size
                require(bytes.size<=4*1024*1024 && expanded<=16*1024*1024){"Документ слишком велик для текстового просмотра"}
                return bytes
            }
            val strings=ArrayList<String>()
            if(!archive)entryBytes("xl/sharedStrings.xml")?.let { bytes ->
                val parser=xml(bytes);var value=StringBuilder()
                while(parser.eventType!=XmlPullParser.END_DOCUMENT) {
                    checkpoint()
                    when(parser.eventType) {
                        XmlPullParser.DOCDECL -> throw IOException("Определения XML не поддерживаются")
                        XmlPullParser.START_TAG -> if(parser.name=="si")value=StringBuilder()
                        XmlPullParser.TEXT -> value.append(parser.text)
                        XmlPullParser.END_TAG -> if(parser.name=="si") {require(strings.size<100000){"Слишком много строк в таблице"};strings+=value.toString()}
                    }
                    parser.nextToken()
                }
            }
            val entries=zip.entries()
            while(entries.hasMoreElements()) {
                checkpoint();require(++inspected<=10000){"В архиве больше 10 000 элементов"}
                val entry=entries.nextElement();val name=entry.name
                if(archive) {result.append(name).append(if(entry.isDirectory)"/" else " (${entry.size} байт)").append('\n')}
                else if(!entry.isDirectory && (name=="word/document.xml" || name.startsWith("xl/worksheets/sheet") || name.startsWith("ppt/slides/slide") || name=="content.xml" || name.endsWith(".xhtml") || name.endsWith(".html"))) {
                    val bytes=entryBytes(name) ?: continue
                    val label=when {
                        name.startsWith("xl/worksheets/sheet") -> "Лист " + name.substringAfterLast("sheet").substringBefore('.')
                        name.startsWith("ppt/slides/slide") -> "Слайд " + name.substringAfterLast("slide").substringBefore('.')
                        name.endsWith(".xhtml") || name.endsWith(".html") -> "Раздел " + (inspected)
                        else -> ""
                    }
                    if(label.isNotEmpty())result.append('\n').append(label).append('\n')
                    val parser=xml(bytes)
                    val sheet=name.startsWith("xl/worksheets/")
                    var shared=false;var inValue=false
                    while(parser.eventType!=XmlPullParser.END_DOCUMENT) {
                        checkpoint()
                        when(parser.eventType) {
                            XmlPullParser.DOCDECL -> throw IOException("Определения XML не поддерживаются")
                            XmlPullParser.START_TAG -> {
                                if(sheet && parser.name=="c")shared=parser.getAttributeValue(null,"t")=="s"
                                if(sheet && parser.name=="v")inValue=true
                            }
                            XmlPullParser.TEXT -> if(!parser.text.isNullOrBlank()) {
                                result.append(if(sheet && shared && inValue)strings.getOrNull(parser.text.toIntOrNull() ?: -1) ?: parser.text else parser.text)
                            }
                            XmlPullParser.END_TAG -> when(parser.name) {
                                "v" -> inValue=false
                                "c","tc","table-cell" -> result.append('\t')
                                "p","tr","row","table-row","si","h1","h2","li","br" -> result.append('\n')
                            }
                        }
                        if(result.length>TEXT_LIMIT)break
                        parser.nextToken()
                    }
                }
                if(result.length>TEXT_LIMIT)break
            }
        }
        return if(result.isBlank())"В документе нет доступного текста. Форматирование и встроенные изображения здесь не отображаются." else result.take(TEXT_LIMIT).toString() + if(result.length>TEXT_LIMIT)"\n…Просмотр ограничен 1 МБ." else ""
    }
    /** Media containers may seek; materialize pipe-only providers, keep normal USB files streaming. */
    fun mediaCopy(s: Storage,e: Entry,forceCopy:Boolean=false,checkpoint: ()->Unit):File? {
        if(s.isFile(e.ref))return null
        val seekable=if(forceCopy)false else s.resolver.openFileDescriptor(s.uri(e.ref),"r")?.use { fd ->
            try {android.system.Os.lseek(fd.fileDescriptor,0,android.system.OsConstants.SEEK_CUR);true}
            catch(ex: android.system.ErrnoException){false}
        } ?: throw IOException("Нет доступа к медиафайлу")
        if(seekable)return null
        val f=File.createTempFile("media-",".tmp",s.context.cacheDir)
        return copy(s.input(e.ref),f,e.size,checkpoint)
    }
    fun hex(bytes: ByteArray)=bytes.toList().chunked(16).mapIndexed { row, data ->
        "%08X  ".format(row*16)+data.joinToString(" "){"%02X".format(it.toInt() and 255)}
    }.joinToString("\n")
}
