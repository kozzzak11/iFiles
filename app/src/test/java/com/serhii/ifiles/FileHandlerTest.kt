package com.serhii.ifiles

import android.net.Uri
import android.provider.DocumentsContract as DC
import androidx.test.core.app.ApplicationProvider
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class FileHandlerTest {
    private val own="com.serhii.ifiles"

    @Test fun incomingUrisMustBeForeignContent() {
        val foreign: (String)->String? = { a -> if(a=="com.google.android.gm.sapi") "com.google.android.gm" else if(a.startsWith(own)) own else null }
        assertTrue(IncomingUris.accepted(Uri.parse("content://com.google.android.gm.sapi/a/att/1"),foreign,own))
        assertTrue(IncomingUris.accepted(Uri.parse("content://unknown.provider/x"),foreign,own))
        assertFalse(IncomingUris.accepted(Uri.parse("file:///data/data/com.serhii.ifiles/files/prefs.xml"),foreign,own))
        assertFalse(IncomingUris.accepted(Uri.parse("content://com.serhii.ifiles.files/local/secret.txt"),foreign,own))
        assertFalse(IncomingUris.accepted(Uri.parse("content://com.serhii.ifiles.documents/document/local%3A"),foreign,own))
        assertFalse("any provider owned by iFiles",IncomingUris.accepted(Uri.parse("content://com.serhii.ifiles.other/x"),foreign,own))
        assertFalse(IncomingUris.accepted(Uri.parse("content:///no-authority"),foreign,own))
        assertFalse(IncomingUris.accepted(null,foreign,own))
        assertFalse(IncomingUris.accepted(Uri.parse("https://example.com/a.pdf"),foreign,own))
    }

    @Test fun mimeFilter() {
        assertEquals(listOf("*/*"),MimeFilter.of(null,null))
        assertEquals(listOf("image/*","application/pdf"),MimeFilter.of("*/*",arrayOf("image/*","application/pdf")))
        val f=MimeFilter.of("image/*",null)
        assertTrue(MimeFilter.matches("image/png",f)); assertTrue(MimeFilter.matches("IMAGE/JPEG",f))
        assertFalse(MimeFilter.matches("video/mp4",f))
        assertTrue(MimeFilter.matches("application/pdf",listOf("application/pdf")))
        assertFalse(MimeFilter.matches("application/pdfx",listOf("application/pdf")))
        assertTrue(MimeFilter.matches("text/plain",listOf("*/*")))
    }

    @Test fun providerPathsStayInsideTheIfilesFolder() {
        val app=File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,"pp-${System.nanoTime()}")
        val base=File(app,"Documents").apply { mkdirs() }
        File(app,"trash").mkdirs(); File(app,"secret.db").writeText("x")
        File(base,"a/b").mkdirs(); File(base,"a/b/c.txt").writeText("c")
        try {
            assertEquals(base.canonicalFile,ProviderPaths.resolve(base,"local:"))
            assertEquals(File(base,"a/b/c.txt").canonicalFile,ProviderPaths.resolve(base,"local:a/b/c.txt"))
            assertEquals("local:a/b/c.txt",ProviderPaths.id(base,File(base,"a/b/c.txt")))
            for(bad in listOf("local:../secret.db","local:a/../../trash","local:/etc/passwd","local:a//b","local:./a","other:a","secret.db","local:a\\..\\x"))
                assertThrows(bad,FileNotFoundException::class.java) { ProviderPaths.resolve(base,bad) }
            Files.createSymbolicLink(File(base,"link").toPath(),app.toPath())
            assertThrows(FileNotFoundException::class.java) { ProviderPaths.resolve(base,"local:link/secret.db") }
            assertThrows(FileNotFoundException::class.java) { ProviderPaths.resolve(base,"local:link") }
            assertTrue(ProviderPaths.isChild("local:","local:a/b"))
            assertTrue(ProviderPaths.isChild("local:a","local:a/b/c.txt"))
            assertFalse(ProviderPaths.isChild("local:a","local:ab/c"))
            assertFalse(ProviderPaths.isChild("local:a","local:a"))
        } finally { app.deleteRecursively() }
    }

    @Test fun documentsProviderListsCreatesRenamesAndTrashes() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val base=File(context.filesDir,"Documents").apply { mkdirs() }
        base.listFiles()?.forEach { it.deleteRecursively() }
        File(base,"Отчёт.txt").writeText("hello")
        val p=IFilesDocumentsProvider()
        p.attachInfo(context,android.content.pm.ProviderInfo().apply { authority="${context.packageName}.documents";exported=true;grantUriPermissions=true;readPermission="android.permission.MANAGE_DOCUMENTS";writePermission="android.permission.MANAGE_DOCUMENTS" })
        p.queryRoots(null).use { c -> assertEquals(1,c.count); c.moveToFirst(); assertEquals("local:",c.getString(c.getColumnIndexOrThrow(DC.Root.COLUMN_DOCUMENT_ID))) }
        p.queryChildDocuments("local:",null,null as String?).use { c ->
            val names=buildList { while(c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow(DC.Document.COLUMN_DISPLAY_NAME))) }
            assertEquals(listOf("Отчёт.txt"),names)
        }
        val folder=p.createDocument("local:",DC.Document.MIME_TYPE_DIR,"Папка")
        val doc=p.createDocument(folder,"text/plain","new.txt")
        val dup=p.createDocument(folder,"text/plain","new.txt")
        assertEquals("local:Папка/new.txt",doc); assertEquals("local:Папка/new (1).txt",dup)
        p.openDocument(doc,"w",null).use { android.os.ParcelFileDescriptor.AutoCloseOutputStream(it).write("written".toByteArray()) }
        assertEquals("written",File(base,"Папка/new.txt").readText())
        assertThrows(FileNotFoundException::class.java) { p.createDocument("local:","text/plain","../escape.txt") }
        val renamed=p.renameDocument(doc,"renamed.txt")
        assertEquals("local:Папка/renamed.txt",renamed)
        p.deleteDocument(renamed)
        assertFalse(File(base,"Папка/renamed.txt").exists())
        assertTrue("deleted item is restorable from iFiles trash",FileEngine(Storage(context)).trashRecords().any { it.optString("name")=="renamed.txt" })
        assertThrows(FileNotFoundException::class.java) { p.queryDocument("local:../trash",null) }
        assertThrows(FileNotFoundException::class.java) { p.deleteDocument("local:") }
        p.querySearchDocuments(IFilesDocumentsProvider.ROOT,"отчёт",null).use { c -> assertEquals(1,c.count) }
    }
}
