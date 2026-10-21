package com.serhii.ifiles

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract as DC
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

/** A stable reference is either file:///… or an Android document/content URI. */
data class Entry(val ref: String, val name: String, val directory: Boolean, val size: Long = -1,
                 val modified: Long = 0, val mime: String = "application/octet-stream",
                 val volumeId: String? = null, val requiresGrant: Boolean = false)

object Names {
    fun check(value: String): String {
        require(value.isNotBlank() && value != "." && value != ".." && value.length <= 240 &&
            value.none { it == '/' || it == '\\' || it == '\u0000' || it.code < 32 }) { "Недопустимое имя" }
        return value
    }
    fun zipParts(value: String): List<String> {
        require(!value.startsWith('/') && !value.contains('\\') && !Regex("^[A-Za-z]:").containsMatchIn(value)) { "Небезопасный путь ZIP" }
        return value.trimEnd('/').split('/').also { require(it.isNotEmpty()); it.forEach(::check) }
    }
    fun unique(name: String, occupied: (String) -> Boolean): String {
        check(name)
        if (!occupied(name)) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot); val ext = name.substring(dot)
        return (1..10000).asSequence().map { "$base ($it)$ext" }.firstOrNull { !occupied(it) }
            ?: error("Слишком много одинаковых имён")
    }
}

class Storage(val context: Context) {
    companion object { private val parents=android.util.LruCache<String,String>(50000) }
    fun parentOf(ref: String): String? = if(isFile(ref))file(ref).parentFile?.toURI()?.toString() else parents.get(ref) ?: prefs.getString("parent:$ref",null)
    fun rememberParent(ref: String) { parentOf(ref)?.let{prefs.edit().putString("parent:$ref",it).apply()} }
    val resolver = context.contentResolver
    val prefs = context.getSharedPreferences("ifiles", Context.MODE_PRIVATE)
    val local = File(context.filesDir, "Documents").apply { mkdirs() }.toURI().toString()
    val phone: String get() = Environment.getExternalStorageDirectory().toURI().toString()
    val downloads: String get() = File(Environment.getExternalStorageDirectory(), "Download").toURI().toString()
    fun uri(ref: String) = Uri.parse(ref)
    fun physical(ref: String): File? {
        if(isFile(ref))return file(ref).canonicalFile
        val u=uri(ref)
        if(u.authority!="com.android.externalstorage.documents")return null
        return runCatching {
            val id=if(ref.contains("/document/"))DC.getDocumentId(u) else DC.getTreeDocumentId(u)
            val volume=id.substringBefore(':');val relative=id.substringAfter(':',"")
            val base=if(volume.equals("primary",true))Environment.getExternalStorageDirectory() else
                context.getSystemService(android.os.storage.StorageManager::class.java).storageVolumes.firstOrNull{it.uuid.equals(volume,true)}?.directory
            base?.let{File(it,relative).canonicalFile}
        }.getOrNull()
    }
    fun isFile(ref: String) = uri(ref).scheme == "file"
    fun file(ref: String) = File(java.net.URI(ref))
    fun mime(name: String) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    fun stat(ref: String): Entry {
        if (isFile(ref)) {
            val f = file(ref)
            require(f.exists() && !Files.isSymbolicLink(f.toPath())) { "Файл недоступен или является ссылкой" }
            return Entry(ref, f.name.ifBlank { "На этом телефоне" }, f.isDirectory, if (f.isFile) f.length() else -1, f.lastModified(), if(f.isDirectory) DC.Document.MIME_TYPE_DIR else mime(f.name))
        }
        val u = uri(ref)
        if (DC.isDocumentUri(context,u) || DC.isTreeUri(u)) {
            val docUri=if(ref.contains("/document/"))u else DC.buildDocumentUriUsingTree(u,DC.getTreeDocumentId(u))
            val cols=arrayOf(DC.Document.COLUMN_DISPLAY_NAME,DC.Document.COLUMN_MIME_TYPE,DC.Document.COLUMN_SIZE,DC.Document.COLUMN_LAST_MODIFIED)
            resolver.query(docUri,cols,android.os.Bundle.EMPTY,null)?.use { c ->
                if(c.moveToFirst()) {
                    val type=c.getString(1) ?: "application/octet-stream";val dir=type==DC.Document.MIME_TYPE_DIR
                    return Entry(docUri.toString(),c.getString(0) ?: "Документ",dir,if(dir || c.isNull(2)) -1 else c.getLong(2),if(c.isNull(3))0 else c.getLong(3),type)
                }
            }
            error("Нет доступа к документу")
        }
        resolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),android.os.Bundle.EMPTY,null)?.use { c ->
            if(c.moveToFirst()) return Entry(ref,c.getString(0) ?: "Файл",false,if(c.isNull(1)) -1 else c.getLong(1),0,resolver.getType(u) ?: "application/octet-stream")
        }
        error("Файл не найден")
    }
    fun list(ref: String): List<Entry> {
        if(isFile(ref)) {
            val f = file(ref)
            require(f.canRead()) { "Нет доступа к папке. Выберите её повторно или разрешите доступ к памяти." }
            return (f.listFiles() ?: error("Папка недоступна")).asSequence().filter { !Files.isSymbolicLink(it.toPath()) }.map { stat(it.toURI().toString()) }.toList()
        }
        val root = uri(ref)
        val id = if(ref.contains("/document/")) DC.getDocumentId(root) else DC.getTreeDocumentId(root)
        val children = DC.buildChildDocumentsUriUsingTree(root,id)
        val result = ArrayList<Entry>()
        val cols = arrayOf(DC.Document.COLUMN_DOCUMENT_ID,DC.Document.COLUMN_DISPLAY_NAME,DC.Document.COLUMN_MIME_TYPE,DC.Document.COLUMN_SIZE,DC.Document.COLUMN_LAST_MODIFIED)
        resolver.query(children,cols,android.os.Bundle.EMPTY,null)?.use { c ->
            while(c.moveToNext()) {
                val type = c.getString(2) ?: "application/octet-stream"
                result += Entry(DC.buildDocumentUriUsingTree(root,c.getString(0)).toString(),c.getString(1) ?: "Файл", type==DC.Document.MIME_TYPE_DIR,if(c.isNull(3)) -1 else c.getLong(3), if(c.isNull(4)) 0 else c.getLong(4),type)
            }
        } ?: error("Не удалось прочитать папку")
        result.forEach{parents.put(it.ref,ref)}
        return result
    }
    fun input(ref: String): InputStream = if(isFile(ref)) FileInputStream(file(ref)) else resolver.openInputStream(uri(ref)) ?: error("Не удалось открыть файл")
    fun output(ref: String): OutputStream = if(isFile(ref)) FileOutputStream(file(ref)) else resolver.openOutputStream(uri(ref),"wt") ?: error("Нет доступа на запись")
    fun create(parent: String, name: String, directory: Boolean, type: String = mime(name)): String {
        Names.check(name)
        require(list(parent).none { it.name.equals(name,true) }) { "Файл с таким именем уже существует" }
        if(isFile(parent)) {
            val p=file(parent).canonicalFile
            val f=File(p,name)
            require(f.canonicalFile.parentFile == p) { "Недопустимый путь" }
            check(if(directory) f.mkdir() else f.createNewFile()) { "Не удалось создать $name" }
            return f.toURI().toString()
        }
        val root=uri(parent)
        val parentUri=if(parent.contains("/document/"))root else DC.buildDocumentUriUsingTree(root,DC.getTreeDocumentId(root))
        return DC.createDocument(resolver,parentUri,if(directory)DC.Document.MIME_TYPE_DIR else type,name)?.toString()
            ?: error("Не удалось создать $name")
    }
    fun unique(parent: String, name: String): String {
        val names = list(parent).map { it.name.lowercase() }.toHashSet()
        return Names.unique(name) { it.lowercase() in names }
    }
    fun rename(ref: String, name: String, parent: String): String {
        Names.check(name)
        return if(isFile(ref)) {
            val source=file(ref); val target=File(source.parentFile,name)
            require(!target.exists() || target.canonicalFile==source.canonicalFile) { "Имя занято" }
            Files.move(source.toPath(),target.toPath())
            target.toURI().toString()
        } else {
            require(list(parent).none { it.name.equals(name,true) && it.ref != ref }) { "Имя занято" }
            val renamed=DC.renameDocument(resolver,uri(ref),name)?.toString() ?: error("Переименование не поддерживается")
            parents.put(renamed,parent);rememberParent(renamed);renamed
        }
    }
    fun delete(ref: String) {
        check(if(isFile(ref)) file(ref).delete() else DC.deleteDocument(resolver,uri(ref))) { "Не удалось удалить исходник; проверенная копия сохранена" }
    }
    fun shareUri(ref: String): Uri = if(isFile(ref)) FileProvider.getUriForFile(context,"${context.packageName}.files",file(ref)) else uri(ref)
    // FileProvider external-path covers primary storage only. Stage USB files for external apps.
    // Internal decoders/player use the original URI and never need FileProvider.
    fun externalUri(ref: String,checkpoint: ()->Unit = {}): Uri {
        if(!isFile(ref))return uri(ref)
        return try { shareUri(ref) } catch(e: IllegalArgumentException) {
            val entry=stat(ref)
            val folder=File(context.cacheDir,"shared/${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val copy=File(folder,Names.check(entry.name))
                PreviewFiles.copy(input(ref),copy,entry.size,checkpoint)
                FileProvider.getUriForFile(context,"${context.packageName}.files",copy)
            } catch(e: Exception) { folder.deleteRecursively();throw e }
        }
    }
    fun scan(ref: String) {
        if(isFile(ref)) android.media.MediaScannerConnection.scanFile(context,arrayOf(file(ref).absolutePath),null,null)
    }
    fun pin(ref: String) = toggle("favorites",ref)
    fun strings(key: String): Set<String> = prefs.getStringSet(key,emptySet())!!.toSet()
    fun toggle(key: String, ref: String) { val set=strings(key).toMutableSet(); if(!set.add(ref))set.remove(ref); prefs.edit().putStringSet(key,set).apply() }
    fun addTree(u: Uri) {
        try { resolver.takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        catch(e: SecurityException) { resolver.takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        prefs.edit().putStringSet("roots",strings("roots")+u.toString()).apply()
    }
    fun recent(ref: String) {
        val a=JSONArray(prefs.getString("recent","[]")); val refs=(listOf(ref)+(0 until a.length()).map{a.getString(it)}).distinct().take(200)
        prefs.edit().putString("recent",JSONArray(refs).toString()).apply()
    }
    fun recents(): List<String> = JSONArray(prefs.getString("recent","[]")).let { a -> (0 until a.length()).map { a.getString(it) } }
    fun rewriteRef(old: String, new: String) {
        for(key in listOf("favorites","roots")) if(old in strings(key)) prefs.edit().putStringSet(key,strings(key)-old+new).apply()
        val refs=recents().map { if(it==old)new else it }; prefs.edit().putString("recent",JSONArray(refs).toString()).apply()
    }
    fun atomic(file: File, data: JSONObject) {
        file.parentFile?.mkdirs(); val a=AtomicFile(file); val out=a.startWrite()
        try { out.write(data.toString().toByteArray()); a.finishWrite(out) } catch(e: Throwable) { a.failWrite(out); throw e }
    }
    fun read(file: File): JSONObject = AtomicFile(file).openRead().use { JSONObject(it.bufferedReader().readText()) }
}

/** Streamed, bounded-memory verified operations. Never follows local symlinks. */
class FileEngine(val s: Storage, val stopped: () -> Boolean = {false}, val progress: (String) -> Unit = {}) {
    fun checkpoint() { if(stopped()) throw InterruptedIOException("Операция прервана; исходные файлы сохранены") }
    fun digest(ref: String): Pair<Long,String> = s.input(ref).use { input ->
        val md=MessageDigest.getInstance("SHA-256"); val buf=ByteArray(256*1024); var size=0L
        while(true) { checkpoint(); val n=input.read(buf); if(n<0)break; md.update(buf,0,n); size+=n }
        size to md.digest().joinToString("") { "%02x".format(it) }
    }
    fun transfer(source: String, dest: String) {
        s.input(source).use { input -> s.output(dest).use { out ->
            val buf=ByteArray(256*1024)
            while(true) { checkpoint(); val n=input.read(buf); if(n<0)break; out.write(buf,0,n) }
            out.flush(); if(out is FileOutputStream) out.fd.sync()
        } }
        check(digest(source)==digest(dest)) { "Проверка копии не пройдена; исходник сохранён" }
    }
    fun assertOutside(source: String, dest: String) {
        val physicalSource=s.physical(source);val physicalDest=s.physical(dest)
        if(physicalSource!=null && physicalDest!=null) {
            val a=physicalSource; val b=physicalDest
            require(a!=b && !b.toPath().startsWith(a.toPath())) { "Нельзя копировать папку в саму себя" }
        } else if(!s.isFile(source) && !s.isFile(dest)) {
            val a=s.uri(source); val b=s.uri(dest)
            if(a.authority==b.authority) {
                val ai=if(source.contains("/document/")) DC.getDocumentId(a) else DC.getTreeDocumentId(a)
                val bi=if(dest.contains("/document/")) DC.getDocumentId(b) else DC.getTreeDocumentId(b)
                require(ai!=bi && !bi.startsWith("$ai/")) { "Нельзя копировать папку в саму себя" }
                if(DC.isDocumentUri(s.context,a) && DC.isDocumentUri(s.context,b))
                    require(!DC.isChildDocument(s.resolver,a,b)) { "Папка назначения находится внутри исходной" }
            }
        }
    }
    fun copy(source: String, parent: String, name: String? = null, depth: Int = 0): String {
        checkpoint(); require(depth<128) { "Слишком глубокая вложенность" }
        assertOutside(source,parent)
        val e=s.stat(source); progress(e.name)
        if(e.directory && s.isFile(source))require(s.file(source).listFiles()?.none { Files.isSymbolicLink(it.toPath()) } == true) { "Папка содержит символические ссылки; операция отменена" }
        val children=if(e.directory)s.list(source) else emptyList()
        val target=s.create(parent,name ?: s.unique(parent,e.name),e.directory,e.mime)
        // On failure retain the partial output for recovery; never remove the source.
        if(e.directory) children.forEach { copy(it.ref,target,it.name,depth+1) } else transfer(source,target)
        s.scan(target); return target
    }
    fun equivalent(source: String, copy: String, depth: Int = 0): Boolean {
        checkpoint(); require(depth<128)
        val a=s.stat(source); val b=s.stat(copy)
        if(a.directory!=b.directory)return false
        if(!a.directory)return digest(source)==digest(copy)
        val ac=s.list(source); val bc=s.list(copy).associateBy { it.name }
        return ac.size==bc.size && ac.all { x -> bc[x.name]?.let{ equivalent(x.ref,it.ref,depth+1) } == true }
    }
    fun removeVerified(source: String, copy: String, depth: Int = 0) {
        checkpoint(); require(depth<128)
        val a=s.stat(source)
        if(a.directory) {
            val bc=s.list(copy).associateBy { it.name }
            for(child in s.list(source)) {
                val backup=bc[child.name] ?: error("Исходная папка изменилась; копия сохранена")
                removeVerified(child.ref,backup.ref,depth+1)
            }
            check(s.list(source).isEmpty()) { "Папка изменилась во время операции" }
        } else check(digest(source)==digest(copy)) { "Исходный файл изменился; он сохранён" }
        checkpoint(); s.delete(source); s.scan(source)
    }
    fun move(source: String, parent: String) {
        val target=copy(source,parent)
        check(equivalent(source,target)) { "Папка изменилась; исходник сохранён" }
        removeVerified(source,target)
    }
    val trashRoot: File get() = File(s.context.filesDir,"trash").apply { mkdirs() }
    fun trashRecords(): List<JSONObject> = trashRoot.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
        runCatching { s.read(File(dir,"record.json")) }.getOrNull()
    }.sortedByDescending { it.optLong("time") }
    fun trash(source: String, parent: String) {
        val e=s.stat(source); val id=UUID.randomUUID().toString(); val dir=File(trashRoot,id).apply { mkdirs() }
        val payload=File(dir,"payload").apply { mkdirs() }.toURI().toString()
        val rec=JSONObject().put("id",id).put("name",e.name).put("source",source).put("parent",parent).put("time",System.currentTimeMillis()).put("state","copying")
        val manifest=File(dir,"record.json"); s.atomic(manifest,rec)
        val target=copy(source,payload,e.name)
        check(equivalent(source,target)) { "Исходник изменился. Удаление отменено." }
        rec.put("backup",target).put("state","verified"); s.atomic(manifest,rec)
        // Manifest is committed before touching the original. An interrupted operation stays recoverable.
        try { removeVerified(source,target); rec.put("state","trashed") }
        catch(e: Exception) { rec.put("state","source_retained"); s.atomic(manifest,rec); throw e }
        s.atomic(manifest,rec)
    }
    fun restore(id: String, parent: String?) {
        require(Regex("[a-f0-9-]{36}").matches(id))
        val dir=File(trashRoot,id); val rec=s.read(File(dir,"record.json"))
        require(rec.optString("state")!="copying") { "Незавершённая копия: исходник не удалялся" }
        val backup=rec.getString("backup"); val target=copy(backup,parent ?: rec.getString("parent"))
        check(equivalent(backup,target)); rec.put("state","restored").put("restoredTo",target); s.atomic(File(dir,"record.json"),rec)
        // Keep verified backup until explicit purge, including after successful restore.
    }
    fun purgeMany(ids: List<String>) {
        // Validate the complete snapshot before deleting anything.
        require(ids.all { Regex("[a-f0-9-]{36}").matches(it) }) { "Недопустимый элемент корзины" }
        var failures=0
        for(id in ids.distinct()) {
            checkpoint()
            try { purge(id) } catch(e: java.io.IOException) { failures++ }
            catch(e: SecurityException) { failures++ }
            catch(e: IllegalStateException) { failures++ }
        }
        check(failures==0) { "Не удалось удалить элементов: " + failures + ". Остальные выбранные элементы удалены." }
    }
    fun purge(id: String) {
        require(Regex("[a-f0-9-]{36}").matches(id))
        val directory=File(trashRoot,id)
        if(!directory.exists())return
        check(directory.deleteRecursively()) { "Не удалось очистить корзину" }
    }
}
