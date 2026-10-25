package com.serhii.ifiles

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Plain-text extraction for the content index. Binaries and media return null (name-only indexing). */
object TextExtract {
    const val TEXT_CAP=1_000_000          // characters stored per file
    const val READ_CAP=2*1024*1024        // bytes read from plain-text files
    const val DOCUMENT_MAX=50L*1024*1024  // larger office/PDF files are indexed by name only
    val plain=setOf("txt","md","markdown","csv","tsv","log","json","xml","html","htm","css","js","ts","kt","kts","java","py","c","h","cpp","hpp","cs","go","rs","swift","rb","php","sh","bat","yaml","yml","ini","conf","cfg","properties","gradle","sql","srt","vtt","tex","rtf")
    val packaged=setOf("docx","docm","xlsx","xlsm","pptx","odt","ods","odp","epub")
    private val skip=setOf("jpg","jpeg","png","gif","webp","bmp","heic","heif","avif","mp3","m4a","aac","wav","flac","ogg","opus","mp4","m4v","mov","mkv","webm","3gp","avi","apk","zip","rar","7z","gz","tar","iso","bin","exe","dll","so","db","sqlite")
    fun ext(name: String)=name.substringAfterLast('.',"").lowercase()
    /** Can this file contain extractable text (decided by name/mime only)? */
    fun supported(e: Entry): Boolean {
        val x=ext(e.name)
        if(x in skip || e.mime.startsWith("image/") || e.mime.startsWith("audio/") || e.mime.startsWith("video/"))return false
        return x in plain || x in packaged || x in setOf("doc","xls","pdf") || e.mime.startsWith("text/") || x.isEmpty()
    }
    /** Decodes text bytes: BOMs, strict UTF-8, else Windows-1251 (common for Cyrillic files from Windows). */
    fun decode(bytes: ByteArray): String {
        if(bytes.size>=3 && bytes[0]==0xEF.toByte() && bytes[1]==0xBB.toByte() && bytes[2]==0xBF.toByte())return String(bytes,3,bytes.size-3,Charsets.UTF_8)
        if(bytes.size>=2 && bytes[0]==0xFF.toByte() && bytes[1]==0xFE.toByte())return String(bytes,2,bytes.size-2,Charsets.UTF_16LE)
        if(bytes.size>=2 && bytes[0]==0xFE.toByte() && bytes[1]==0xFF.toByte())return String(bytes,2,bytes.size-2,Charsets.UTF_16BE)
        // A cut at READ_CAP may split a UTF-8 sequence: ignore up to 3 trailing bytes when validating.
        for(trim in 0..minOf(3,bytes.size)) {
            val ok=runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,0,bytes.size-trim)).toString() }.getOrNull()
            if(ok!=null)return ok
        }
        return String(bytes,Charset.forName("windows-1251"))
    }
    fun stripMarkup(text: String)=text.replace(Regex("(?s)<(script|style)[^>]*>.*?</\\1>")," ").replace(Regex("<[^>]{0,2000}>")," ")
        .replace("&nbsp;"," ").replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&#39;","'").replace("&amp;","&")
    private fun clean(text: String)=text.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]")," ").take(TEXT_CAP)
    /** Extracts text from a local [file] named [name]. Returns null for unsupported or binary content. */
    fun extract(file: File, name: String, mime: String="application/octet-stream", pdf: ((File)->String)?=null, check: ()->Unit={}): String? {
        val x=ext(name)
        val text=when {
            x in packaged -> PreviewFiles.packageText(file,false,check).let { if(it.startsWith("В документе нет доступного текста"))"" else it.removeSuffix("\n…Просмотр ограничен 1 МБ.") }
            x=="doc" -> file.inputStream().use { org.apache.poi.hwpf.extractor.WordExtractor(it).use { w -> w.text } }
            x=="xls" -> Workbooks.read(file,"xls",check).joinToString("\n") { sheet -> sheet.name+"\n"+sheet.cells.entries.sortedWith(compareBy({it.key.first},{it.key.second})).joinToString("\t") { it.value.text } }
            x=="pdf" -> pdf?.invoke(file) ?: return null
            x in plain || mime.startsWith("text/") || x.isEmpty() -> {
                val bytes=file.inputStream().use { PreviewFiles.read(it,READ_CAP) }
                if(!PreviewFiles.isText(bytes.copyOf(minOf(bytes.size,4096))))return null
                decode(bytes).let { if(x in setOf("html","htm","xml","rtf"))stripMarkup(it) else it }
            }
            else -> return null
        }
        return clean(text)
    }
}

data class SearchHit(val ref: String, val name: String, val snippet: String, val inContent: Boolean, val size: Long = -1, val modified: Long = 0) {
    fun entry(s: Storage)=Entry(ref,name,false,size,modified,s.mime(name))
}

/** Own full-text index (SQLite FTS4, bundled with Android) over every indexed location. */
class ContentIndex(context: Context, name: String? = "content-index.db"): SQLiteOpenHelper(context,name,null,1) {
    // One helper per process: the worker writes while the UI reads (WAL allows both).
    override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE files(id INTEGER PRIMARY KEY, ref TEXT UNIQUE NOT NULL, root TEXT NOT NULL, name TEXT NOT NULL, size INTEGER, mtime INTEGER, gen INTEGER, has_text INTEGER)")
        db.execSQL("CREATE INDEX files_root ON files(root, gen)")
        db.execSQL("CREATE VIRTUAL TABLE fts USING fts4(name, body, tokenize=unicode61)")
        db.execSQL("CREATE TABLE roots(root TEXT PRIMARY KEY, gen INTEGER, complete INTEGER, files INTEGER, updated INTEGER)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    data class Known(val id: Long, val size: Long, val mtime: Long)
    fun known(ref: String): Known? = readableDatabase.rawQuery("SELECT id,size,mtime FROM files WHERE ref=?",arrayOf(ref)).use { c -> if(c.moveToFirst())Known(c.getLong(0),c.getLong(1),c.getLong(2)) else null }
    /** Starts a pass over [root]; returns its generation number. */
    fun begin(root: String): Long = writableDatabase.let { db ->
        val gen=(db.rawQuery("SELECT gen FROM roots WHERE root=?",arrayOf(root)).use { if(it.moveToFirst())it.getLong(0) else 0L })+1
        db.insertWithOnConflict("roots",null,ContentValues().apply { put("root",root);put("gen",gen);put("complete",0);put("updated",System.currentTimeMillis()) },SQLiteDatabase.CONFLICT_REPLACE)
        gen
    }
    /** Marks an unchanged file as still present. */
    fun touch(id: Long, gen: Long) { writableDatabase.execSQL("UPDATE files SET gen=? WHERE id=?",arrayOf(gen,id)) }
    fun put(root: String, e: Entry, text: String?, gen: Long) {
        val db=writableDatabase
        db.beginTransaction()
        try {
            val old=known(e.ref)
            val v=ContentValues().apply { put("ref",e.ref);put("root",root);put("name",e.name);put("size",e.size);put("mtime",e.modified);put("gen",gen);put("has_text",if(text.isNullOrEmpty())0 else 1) }
            val id=if(old==null)db.insertOrThrow("files",null,v) else { db.update("files",v,"id=?",arrayOf(old.id.toString()));db.delete("fts","docid=?",arrayOf(old.id.toString()));old.id }
            db.insertOrThrow("fts",null,ContentValues().apply { put("docid",id);put("name",e.name);put("body",text ?: "") })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    /** Ends a completed pass: removes rows of files that disappeared. Returns how many were removed. */
    fun finish(root: String, gen: Long): Int {
        val db=writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM fts WHERE docid IN (SELECT id FROM files WHERE root=? AND gen<>?)",arrayOf(root,gen))
            val removed=db.delete("files","root=? AND gen<>?",arrayOf(root,gen.toString()))
            val count=db.rawQuery("SELECT COUNT(*) FROM files WHERE root=?",arrayOf(root)).use { it.moveToFirst();it.getLong(0) }
            db.update("roots",ContentValues().apply { put("complete",1);put("files",count);put("updated",System.currentTimeMillis()) },"root=?",arrayOf(root))
            db.setTransactionSuccessful()
            return removed
        } finally { db.endTransaction() }
    }
    fun forget(root: String) {
        val db=writableDatabase
        db.execSQL("DELETE FROM fts WHERE docid IN (SELECT id FROM files WHERE root=?)",arrayOf(root))
        db.delete("files","root=?",arrayOf(root)); db.delete("roots","root=?",arrayOf(root))
    }
    fun count(root: String? = null): Long = readableDatabase.rawQuery(if(root==null)"SELECT COUNT(*) FROM files" else "SELECT COUNT(*) FROM files WHERE root=?",if(root==null)null else arrayOf(root)).use { it.moveToFirst();it.getLong(0) }
    fun roots(): List<String> = readableDatabase.rawQuery("SELECT root FROM roots",null).use { c -> buildList { while(c.moveToNext())add(c.getString(0)) } }

    /**
     * Name and content matches for [query], each word as a prefix match. Snippets mark matches with
     * [MARK_START]/[MARK_END]. [scope] limits results to refs below a folder ref.
     */
    fun search(query: String, scope: String? = null, limit: Int = 100): List<SearchHit> {
        val match=matchExpression(query) ?: return emptyList()
        val prefix=scope?.let(::scopePrefix)
        val sql="SELECT f.ref,f.name,snippet(fts,'$MARK_START','$MARK_END','…',1,16),f.size,f.mtime FROM fts JOIN files f ON f.id=fts.docid WHERE fts MATCH ?"+
            (if(prefix!=null)" AND substr(f.ref,1,?)=?" else "")+" LIMIT ?"
        val args=if(prefix!=null)arrayOf(match,prefix.length.toString(),prefix,limit.toString()) else arrayOf(match,limit.toString())
        val words=words(query)
        fun nameHit(name: String)=words.all { w -> name.contains(w,true) }
        return readableDatabase.rawQuery(sql,args).use { c -> buildList {
            while(c.moveToNext()) {
                val snippet=c.getString(2).orEmpty()
                val inBody=snippet.contains(MARK_START)
                add(SearchHit(c.getString(0),c.getString(1),if(inBody)snippet else "",inBody,c.getLong(3),c.getLong(4)))
            }
        }}.sortedWith(compareByDescending<SearchHit>{ nameHit(it.name) }.thenBy { it.name.lowercase() })
    }
    /** Drops one file (e.g. it was deleted in the app and the next pass has not run yet). */
    fun remove(ref: String) {
        val db=writableDatabase
        db.execSQL("DELETE FROM fts WHERE docid IN (SELECT id FROM files WHERE ref=?)",arrayOf(ref))
        db.delete("files","ref=?",arrayOf(ref))
    }
    data class RootInfo(val root: String, val files: Long, val complete: Boolean, val updated: Long)
    fun rootInfo(): List<RootInfo> = readableDatabase.rawQuery("SELECT root,(SELECT COUNT(*) FROM files f WHERE f.root=r.root),complete,updated FROM roots r ORDER BY root",null).use { c ->
        buildList { while(c.moveToNext())add(RootInfo(c.getString(0),c.getLong(1),c.getInt(2)==1,c.getLong(3))) }
    }
    companion object {
        const val MARK_START="\u0001"; const val MARK_END="\u0002"
        fun words(query: String)=query.split(Regex("[^\\p{L}\\p{N}_]+")).filter { it.isNotBlank() }.take(8)
        /** FTS4 MATCH expression: every word as a quoted prefix term (no operator injection). */
        fun matchExpression(query: String): String? = words(query).takeIf { it.isNotEmpty() }?.joinToString(" ") { "\"${it.replace("\"","")}*\"" }
        /**
         * Ref prefix of everything inside folder [scope]: "file:/a/b/" for files, "…document/primary%3Ab%2F" for
         * SAF documents, so a sibling "b2" never matches "b".
         */
        fun scopePrefix(scope: String): String = when {
            scope.startsWith("file:") -> if(scope.endsWith("/"))scope else "$scope/"
            scope.contains("/document/") -> "$scope%2F"
            else -> scope
        }
        @Volatile private var shared: ContentIndex?=null
        fun get(context: Context): ContentIndex = shared ?: synchronized(this) { shared ?: ContentIndex(context.applicationContext).also { shared=it } }
    }
}

/** Walks a location and updates the index incrementally (unchanged size+mtime are skipped). */
class Indexer(private val s: Storage, private val index: ContentIndex, private val pdf: ((File)->String)? = { PdfText.extract(s.context,it) }) {
    data class Stats(var seen: Int=0, var indexed: Int=0, var skipped: Int=0, var failed: Int=0, var removed: Int=0)
    /**
     * Indexes [root]. Missing files are removed only after a complete walk, so an unplugged USB drive or a
     * cancelled run never wipes earlier results; a rerun resumes cheaply because unchanged files are skipped.
     */
    fun run(root: String, check: ()->Unit = {}, progress: (Stats, String)->Unit = { _, _ -> }): Stats {
        val stats=Stats(); val gen=index.begin(root)
        val queue=ArrayDeque<String>().apply { add(root) }; var folders=0
        while(queue.isNotEmpty()) {
            check()
            val folder=queue.removeFirst()
            val children=try { s.list(folder) } catch(e: Exception) {
                if(folder==root)throw IOException("Расположение недоступно: ${e.message}",e)
                stats.failed++; continue
            }
            require(++folders<=200_000) { "Слишком много папок" }
            for(e in children) {
                check()
                if(PreviewFiles.hidden(e.name) || e.name==".ifiles-trash")continue
                if(e.directory) { queue.add(e.ref); continue }
                stats.seen++
                val old=index.known(e.ref)
                if(old!=null && old.size==e.size && old.mtime==e.modified) { index.touch(old.id,gen); stats.skipped++ }
                else {
                    val text=try { if(TextExtract.supported(e) && (e.size<=TextExtract.DOCUMENT_MAX || TextExtract.ext(e.name) in TextExtract.plain)) extractRef(e,check) else null }
                        catch(ex: kotlinx.coroutines.CancellationException) { throw ex } catch(ex: InterruptedException) { throw ex }
                        catch(ex: Exception) { stats.failed++; null } catch(ex: OutOfMemoryError) { stats.failed++; null }
                    index.put(root,e,text,gen); stats.indexed++
                }
                if(stats.seen%25==0)progress(stats,e.name)
            }
        }
        stats.removed=index.finish(root,gen)
        progress(stats,"")
        return stats
    }
    private fun extractRef(e: Entry, check: ()->Unit): String? {
        if(s.isFile(e.ref))return TextExtract.extract(s.file(e.ref),e.name,e.mime,pdf,check)
        // SAF/USB: copy at most what the extractor needs into a private temp file.
        val temp=File.createTempFile("index-",".tmp",s.context.cacheDir)
        try {
            if(TextExtract.ext(e.name) in TextExtract.plain || e.mime.startsWith("text/")) s.input(e.ref).use { input -> temp.writeBytes(PreviewFiles.read(input,TextExtract.READ_CAP)) }
            else PreviewFiles.copy(s.input(e.ref),temp,e.size,check)
            return TextExtract.extract(temp,e.name,e.mime,pdf,check)
        } finally { temp.delete() }
    }
}

/** Turns an FTS snippet with [ContentIndex.MARK_START]/[ContentIndex.MARK_END] marks into plain text plus highlight ranges. */
object Snippets {
    fun parse(snippet: String): Pair<String,List<IntRange>> {
        val text=StringBuilder(); val ranges=ArrayList<IntRange>(); var start=-1
        for(ch in snippet) when {
            ch==ContentIndex.MARK_START[0] -> start=text.length
            ch==ContentIndex.MARK_END[0] -> { if(start>=0 && text.length>start)ranges+=start until text.length; start=-1 }
            ch.isWhitespace() -> if(text.isNotEmpty() && text.last()!=' ')text.append(' ')
            else -> text.append(ch)
        }
        return text.toString() to ranges
    }
}
