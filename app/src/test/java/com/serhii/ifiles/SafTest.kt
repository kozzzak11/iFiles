package com.serhii.ifiles

import android.database.Cursor
import android.database.MatrixCursor
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract as DC
import android.provider.DocumentsProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

/** Test DocumentsProvider backed by a real temporary filesystem, not mocked Storage methods. */
class TestDocumentsProvider: DocumentsProvider() {
    lateinit var base: File
    var denyDelete=false
    override fun onCreate()=true
    private val columns=arrayOf(DC.Document.COLUMN_DOCUMENT_ID,DC.Document.COLUMN_DISPLAY_NAME,DC.Document.COLUMN_MIME_TYPE,DC.Document.COLUMN_SIZE,DC.Document.COLUMN_LAST_MODIFIED,DC.Document.COLUMN_FLAGS)
    private fun file(id: String)=if(id=="root")base else File(base,id.removePrefix("root/"))
    private fun id(f: File)=if(f==base)"root" else "root/"+f.relativeTo(base).path
    private fun row(c: MatrixCursor,f: File) {
        val values=mapOf<String,Any>(DC.Document.COLUMN_DOCUMENT_ID to id(f),DC.Document.COLUMN_DISPLAY_NAME to f.name,
            DC.Document.COLUMN_MIME_TYPE to if(f.isDirectory)DC.Document.MIME_TYPE_DIR else "text/plain",
            DC.Document.COLUMN_SIZE to f.length(),DC.Document.COLUMN_LAST_MODIFIED to f.lastModified(),
            DC.Document.COLUMN_FLAGS to (DC.Document.FLAG_SUPPORTS_WRITE or DC.Document.FLAG_SUPPORTS_DELETE or DC.Document.FLAG_SUPPORTS_RENAME or DC.Document.FLAG_DIR_SUPPORTS_CREATE))
        c.addRow(c.columnNames.map{values[it]}.toTypedArray())
    }
    override fun queryRoots(projection: Array<out String>?): Cursor=MatrixCursor(arrayOf(DC.Root.COLUMN_ROOT_ID,DC.Root.COLUMN_DOCUMENT_ID)).apply{addRow(arrayOf("root","root"))}
    override fun queryDocument(documentId: String,projection: Array<out String>?): Cursor=MatrixCursor(projection ?: columns).apply {val f=file(documentId);if(f.exists())row(this,f)}
    override fun queryChildDocuments(parentDocumentId: String,projection: Array<out String>?,sortOrder: String?): Cursor=MatrixCursor(projection ?: columns).apply{file(parentDocumentId).listFiles().orEmpty().forEach{row(this,it)}}
    override fun openDocument(documentId: String,mode: String,signal: android.os.CancellationSignal?): ParcelFileDescriptor=ParcelFileDescriptor.open(file(documentId),ParcelFileDescriptor.parseMode(mode))
    override fun createDocument(parentDocumentId: String,mimeType: String,displayName: String): String {
        val f=File(file(parentDocumentId),displayName)
        check(if(mimeType==DC.Document.MIME_TYPE_DIR)f.mkdir() else f.createNewFile())
        return id(f)
    }
    override fun deleteDocument(documentId: String){if(denyDelete)throw SecurityException("Read-only USB");check(file(documentId).deleteRecursively())}
    override fun renameDocument(documentId: String,displayName: String): String {val from=file(documentId);val to=File(from.parentFile,displayName);check(from.renameTo(to));return id(to)}
    override fun isChildDocument(parentDocumentId: String,documentId: String)=documentId.startsWith("$parentDocumentId/")
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class SafTest {
    private lateinit var s: Storage
    private lateinit var base: File
    private lateinit var provider: TestDocumentsProvider
    @Before fun setup() {
        s=Storage(ApplicationProvider.getApplicationContext())
        base=File(s.context.cacheDir,"saf-${System.nanoTime()}").apply{mkdirs()}
        provider=TestDocumentsProvider().apply{this.base=this@SafTest.base}
        provider.attachInfo(s.context,android.content.pm.ProviderInfo().apply{authority="ifiles.test.documents";grantUriPermissions=true;exported=true;readPermission="android.permission.MANAGE_DOCUMENTS";writePermission="android.permission.MANAGE_DOCUMENTS"})
        ShadowContentResolver.registerProviderInternal("ifiles.test.documents",provider)
    }
    @After fun cleanup(){base.deleteRecursively()}
    @Test fun createAndCopyInsideNestedSafFolder() {
        val tree=DC.buildTreeDocumentUri("ifiles.test.documents","root").toString()
        val nested=s.create(tree,"Nested",true)
        val dest=s.create(nested,"Destination",true)
        val from=s.create(nested,"source.txt",false,"text/plain")
        s.output(from).use{it.write("SAF works".toByteArray())}
        val copied=FileEngine(s).copy(from,dest)
        assertEquals("SAF works",s.input(copied).bufferedReader().readText())
        assertEquals(listOf("source.txt"),s.list(dest).map{it.name})
        val renamed=s.rename(copied,"renamed.txt",dest)
        assertEquals("renamed.txt",s.stat(renamed).name)
        s.delete(renamed);assertTrue(s.list(dest).isEmpty())
        assertEquals("SAF works",s.input(from).bufferedReader().readText())
    }
    @Test fun safPreviewIsByteExactAndDoesNotWriteOriginal() {
        val tree=DC.buildTreeDocumentUri("ifiles.test.documents","root").toString()
        val ref=s.create(tree,"Фото USB.pdf",false,"application/pdf")
        val bytes="%PDF-1.7\npreview content".toByteArray();s.output(ref).use{it.write(bytes)}
        val copy=File(base,"preview.tmp")
        PreviewFiles.copy(s.input(ref),copy,s.stat(ref).size){}
        assertArrayEquals(bytes,copy.readBytes());assertArrayEquals(bytes,s.input(ref).use{it.readBytes()})
        assertEquals("pdf",PreviewFiles.kind(s.stat(ref),bytes))
    }
    @Test fun safAppleDoubleResolvesSiblingInNestedFolder() {
        val tree=DC.buildTreeDocumentUri("ifiles.test.documents","root").toString()
        val nested=s.create(tree,"Nested",true)
        val original=s.create(nested,"Фото.jpg",false)
        val sidecar=s.create(nested,"._Фото.jpg",false)
        s.output(sidecar).use{it.write(byteArrayOf(0,5,22,7))}
        val listed=s.list(nested)
        assertEquals(original,PreviewFiles.resolve(s,listed.first{it.name.startsWith("._")}).ref)
    }
    @Test fun safTrashRestoreRoundTripKeepsVerifiedBytes() {
        val tree=DC.buildTreeDocumentUri("ifiles.test.documents","root").toString()
        val ref=s.create(tree,"USB.txt",false)
        val bytes=ByteArray(300000){(it*31).toByte()};s.output(ref).use{it.write(bytes)}
        val engine=FileEngine(s)
        engine.trash(ref,tree)
        val record=engine.trashRecords().first{it.getString("source")==ref}
        assertFalse(File(base,"USB.txt").exists())
        engine.restore(record.getString("id"),tree)
        assertArrayEquals(bytes,File(base,"USB.txt").readBytes())
        engine.purge(record.getString("id"));assertArrayEquals(bytes,File(base,"USB.txt").readBytes())
    }
    @Test fun deniedUsbDeleteKeepsOriginalAndVerifiedTrashBackup() {
        val tree=DC.buildTreeDocumentUri("ifiles.test.documents","root").toString()
        val ref=s.create(tree,"USB protected.txt",false);val bytes="keep my original".toByteArray()
        s.output(ref).use{it.write(bytes)};provider.denyDelete=true
        val engine=FileEngine(s)
        assertThrows(SecurityException::class.java){engine.trash(ref,tree)}
        assertArrayEquals(bytes,s.input(ref).use{it.readBytes()})
        val record=engine.trashRecords().first{it.getString("source")==ref}
        assertEquals("source_retained",record.getString("state"))
        assertArrayEquals(bytes,s.input(record.getString("backup")).use{it.readBytes()})
        engine.purge(record.getString("id"))
    }
    @Test fun rejectSafFolderIntoItself() {
        val tree=DC.buildTreeDocumentUri("ifiles.test.documents","root").toString()
        val nested=s.create(tree,"Nested",true)
        val inside=s.create(nested,"Inside",true)
        assertThrows(IllegalArgumentException::class.java){FileEngine(s).copy(nested,inside)}
        assertTrue(s.list(inside).isEmpty())
    }
}
