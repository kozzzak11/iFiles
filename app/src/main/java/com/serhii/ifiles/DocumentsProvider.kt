package com.serhii.ifiles

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Maps document ids ("local:" + relative path) to files strictly inside the iFiles folder
 * (filesDir/Documents). Anything else in app storage (trash, task files, index, preferences)
 * can never be addressed: ids with "..", absolute parts or symlinks are rejected.
 */
object ProviderPaths {
    const val PREFIX="local:"
    fun resolve(base: File, documentId: String): File {
        if(!documentId.startsWith(PREFIX)) throw FileNotFoundException("Неизвестный документ")
        val relative=documentId.removePrefix(PREFIX)
        val root=base.canonicalFile
        if(relative.isEmpty()) return root
        val parts=relative.split('/')
        if(relative.startsWith('/') || parts.any { it.isEmpty() || it=="." || it==".." || it.contains('\u0000') || it.contains('\\') }) throw FileNotFoundException("Недопустимый путь")
        var f=root
        for(part in parts) {
            f=File(f,part)
            if(Files.isSymbolicLink(f.toPath())) throw FileNotFoundException("Ссылки не поддерживаются")
        }
        val canonical=f.canonicalFile
        if(canonical!=f.absoluteFile || !canonical.path.startsWith(root.path+File.separator)) throw FileNotFoundException("Вне папки iFiles")
        return canonical
    }
    fun id(base: File, file: File): String {
        val root=base.canonicalFile; val f=file.canonicalFile
        if(f==root) return PREFIX
        require(f.path.startsWith(root.path+File.separator)) { "Вне папки iFiles" }
        return PREFIX+f.path.removePrefix(root.path+File.separator)
    }
    fun isChild(parentId: String, documentId: String): Boolean {
        if(!parentId.startsWith(PREFIX) || !documentId.startsWith(PREFIX) || parentId==documentId) return false
        val p=parentId.removePrefix(PREFIX)
        return p.isEmpty() || documentId.removePrefix(PREFIX).startsWith("$p/")
    }
}

/** Shows the iFiles folder in Android's system file picker ("Files"/DocumentsUI) and in other apps' pickers. */
class IFilesDocumentsProvider: DocumentsProvider() {
    private val rootColumns=arrayOf(Root.COLUMN_ROOT_ID,Root.COLUMN_FLAGS,Root.COLUMN_TITLE,Root.COLUMN_DOCUMENT_ID,Root.COLUMN_ICON,Root.COLUMN_AVAILABLE_BYTES,Root.COLUMN_MIME_TYPES)
    private val documentColumns=arrayOf(Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_SIZE,Document.COLUMN_LAST_MODIFIED,Document.COLUMN_FLAGS)
    private val storage by lazy { Storage(context!!) }
    private val base: File get() = File(context!!.filesDir,"Documents").apply { mkdirs() }
    private val authority get() = "${context!!.packageName}.documents"

    override fun onCreate()=true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: rootColumns).apply {
        newRow().add(Root.COLUMN_ROOT_ID,ROOT).add(Root.COLUMN_TITLE,"iFiles").add(Root.COLUMN_DOCUMENT_ID,ProviderPaths.PREFIX)
            .add(Root.COLUMN_FLAGS,Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_SEARCH or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY)
            .add(Root.COLUMN_ICON,R.drawable.ic_app).add(Root.COLUMN_AVAILABLE_BYTES,base.usableSpace).add(Root.COLUMN_MIME_TYPES,"*/*")
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: documentColumns).apply { add(this,file(documentId)) }

    override fun queryChildDocuments(parentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val dir=file(parentId)
        if(!dir.isDirectory) throw FileNotFoundException("Не папка")
        return MatrixCursor(projection ?: documentColumns).apply {
            dir.listFiles().orEmpty().filterNot { Files.isSymbolicLink(it.toPath()) }.sortedBy { it.name.lowercase() }.forEach { add(this,it) }
            setNotificationUri(context!!.contentResolver,DocumentsContract.buildChildDocumentsUri(authority,parentId))
        }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val f=file(documentId)
        if(f.isDirectory) throw FileNotFoundException("Это папка")
        val pfd=ParcelFileDescriptor.parseMode(mode)
        val writing=mode.contains('w')
        if(!writing) return ParcelFileDescriptor.open(f,pfd)
        // Re-index and notify after the writer closes the file.
        return ParcelFileDescriptor.open(f,pfd,android.os.Handler(android.os.Looper.getMainLooper())) {
            runCatching { ContentIndex.get(context!!).remove(ref(f)) }
            notifyParent(f)
        }
    }

    override fun createDocument(parentId: String, mimeType: String, displayName: String): String {
        val parent=file(parentId)
        if(!parent.isDirectory) throw FileNotFoundException("Не папка")
        val name=runCatching { Names.unique(Names.fit(Names.check(displayName.trim()))) { File(parent,it).exists() } }.getOrElse { throw FileNotFoundException(it.message) }
        val f=File(parent,name)
        val ok=if(mimeType==Document.MIME_TYPE_DIR) f.mkdir() else f.createNewFile()
        if(!ok) throw FileNotFoundException("Не удалось создать")
        notifyParent(f)
        return ProviderPaths.id(base,f)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val f=file(documentId)
        if(f==base.canonicalFile) throw FileNotFoundException("Корень нельзя переименовать")
        val name=runCatching { Names.check(displayName.trim()) }.getOrElse { throw FileNotFoundException(it.message) }
        val target=File(f.parentFile,name)
        if(target.exists() || !f.renameTo(target)) throw FileNotFoundException("Не удалось переименовать")
        runCatching { ContentIndex.get(context!!).remove(ref(f)) }
        notifyParent(target)
        return ProviderPaths.id(base,target)
    }

    /** Deleting moves the item to iFiles' "Recently deleted", so it can still be restored in the app. */
    override fun deleteDocument(documentId: String) {
        val f=file(documentId)
        if(f==base.canonicalFile) throw FileNotFoundException("Корень нельзя удалить")
        try { FileEngine(storage).trash(ref(f),ref(f.parentFile!!)) }
        catch(e: Exception) { throw FileNotFoundException(e.message ?: "Не удалось удалить") }
        runCatching { ContentIndex.get(context!!).remove(ref(f)) }
        notifyParent(f)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String)=ProviderPaths.isChild(parentDocumentId,documentId)

    override fun getDocumentType(documentId: String): String = file(documentId).let { if(it.isDirectory) Document.MIME_TYPE_DIR else storage.mime(it.name) }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<out String>?): Cursor {
        val result=MatrixCursor(projection ?: documentColumns)
        if(rootId!=ROOT || query.isBlank()) return result
        val root=base.canonicalFile; val seen=HashSet<String>()
        fun emit(f: File) { if(seen.size<200 && seen.add(f.path) && f.exists()) add(result,f) }
        // Content index first (text matches), then a bounded name walk.
        runCatching { ContentIndex.get(context!!).search(query,storage.local,100) }.getOrDefault(emptyList()).forEach { h ->
            runCatching { java.io.File(java.net.URI(h.ref)) }.getOrNull()?.let { f -> runCatching { ProviderPaths.resolve(base,ProviderPaths.id(base,f)) }.getOrNull()?.let(::emit) }
        }
        val words=ContentIndex.words(query)
        val queue=ArrayDeque(listOf(root)); var visited=0
        while(queue.isNotEmpty() && seen.size<200 && visited<20000) {
            val dir=queue.removeFirst()
            dir.listFiles().orEmpty().forEach { f ->
                visited++
                if(Files.isSymbolicLink(f.toPath())) return@forEach
                if(words.all { f.name.contains(it,true) }) emit(f)
                if(f.isDirectory(LinkOption.NOFOLLOW_LINKS)) queue.addLast(f)
            }
        }
        return result
    }

    private fun java.io.File.isDirectory(vararg options: LinkOption)=Files.isDirectory(toPath(),*options)

    /** The app's own file:// reference (same form as Storage.local, used by the index and trash). */
    private fun ref(f: File): String = File(base,ProviderPaths.id(base,f).removePrefix(ProviderPaths.PREFIX)).toURI().toString()

    private fun file(documentId: String): File {
        val f=ProviderPaths.resolve(base,documentId)
        if(!f.exists()) throw FileNotFoundException("Документ не найден")
        return f
    }

    private fun add(cursor: MatrixCursor, f: File) {
        val dir=f.isDirectory; val root=f==base.canonicalFile
        var flags=0
        if(dir) flags=flags or Document.FLAG_DIR_SUPPORTS_CREATE
        else flags=flags or Document.FLAG_SUPPORTS_WRITE
        if(!root) flags=flags or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_DELETE
        cursor.newRow().add(Document.COLUMN_DOCUMENT_ID,ProviderPaths.id(base,f))
            .add(Document.COLUMN_DISPLAY_NAME,if(root)"iFiles" else f.name)
            .add(Document.COLUMN_MIME_TYPE,if(dir) Document.MIME_TYPE_DIR else storage.mime(f.name))
            .add(Document.COLUMN_SIZE,if(dir) null else f.length())
            .add(Document.COLUMN_LAST_MODIFIED,f.lastModified())
            .add(Document.COLUMN_FLAGS,flags)
    }

    private fun notifyParent(f: File) {
        val parent=f.parentFile ?: return
        runCatching { context!!.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority,ProviderPaths.id(base,parent)),null) }
    }

    companion object { const val ROOT="ifiles" }
}
