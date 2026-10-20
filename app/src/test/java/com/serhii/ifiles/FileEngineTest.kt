package com.serhii.ifiles

import androidx.test.core.app.ApplicationProvider
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class FileEngineTest {
    private lateinit var s: Storage
    private lateinit var engine: FileEngine
    private lateinit var root: File
    private fun ref(f: File)=f.toURI().toString()
    @Before fun setup() {
        s=Storage(ApplicationProvider.getApplicationContext());engine=FileEngine(s)
        engine.trashRoot.deleteRecursively()
        root=File(s.context.cacheDir,"test-${System.nanoTime()}").apply{mkdirs()}
    }
    @After fun cleanup(){root.deleteRecursively();engine.trashRoot.deleteRecursively()}
    @Test fun copyLargeFileAndCollision() {
        val f=File(root,"source.bin").apply{outputStream().use{out->repeat(40){out.write(ByteArray(256*1024){n->(n%251).toByte()})}}}
        val dest=File(root,"dest").apply{mkdirs()};File(dest,"source.bin").writeText("original")
        val copy=engine.copy(ref(f),ref(dest))
        assertEquals("source (1).bin",s.stat(copy).name)
        assertEquals(engine.digest(ref(f)),engine.digest(copy))
        assertEquals("original",File(dest,"source.bin").readText())
    }
    @Test fun failedVerificationPreservesChangedOriginal() {
        val f=File(root,"file").apply{writeText("before")};val dest=File(root,"dest").apply{mkdirs()}
        val backup=engine.copy(ref(f),ref(dest));f.writeText("after")
        assertThrows(IllegalStateException::class.java){engine.removeVerified(ref(f),backup)}
        assertEquals("after",f.readText());assertEquals("before",s.input(backup).bufferedReader().readText())
    }
    @Test fun cancelledTrashNeverDeletesSource() {
        val f=File(root,"file").apply{writeBytes(ByteArray(1024*1024){42})};var n=0
        assertThrows(java.io.InterruptedIOException::class.java){FileEngine(s,{++n>4}).trash(ref(f),ref(root))}
        assertTrue(f.exists());assertEquals(1024*1024L,f.length())
        assertEquals("copying",engine.trashRecords().single().getString("state"))
    }
    @Test fun trashRestoreKeepsBackupAndNeverOverwrites() {
        val folder=File(root,"Folder").apply{mkdirs()};File(folder,"a.txt").writeText("precious")
        engine.trash(ref(folder),ref(root));assertFalse(folder.exists())
        val record=engine.trashRecords().single();assertEquals("trashed",record.getString("state"))
        folder.mkdirs();File(folder,"a.txt").writeText("new")
        engine.restore(record.getString("id"),null)
        assertEquals("new",File(folder,"a.txt").readText())
        assertEquals("precious",File(root,"Folder (1)/a.txt").readText())
        assertTrue(s.stat(record.getString("backup")).directory)
    }
    @Test fun renameNeverOverwritesEvenWithWrongCallerParent() {
        val source=File(root,"source.txt").apply{writeText("source")}
        val target=File(root,"taken.txt").apply{writeText("valuable")}
        val unrelated=File(root,"elsewhere").apply{mkdirs()}
        assertThrows(IllegalArgumentException::class.java){s.rename(ref(source),"taken.txt",ref(unrelated))}
        assertEquals("source",source.readText());assertEquals("valuable",target.readText())
    }
    @Test fun selfCopyRejected() {
        val folder=File(root,"Folder").apply{mkdirs()};val nested=File(folder,"inside").apply{mkdirs()}
        assertThrows(IllegalArgumentException::class.java){engine.copy(ref(folder),ref(nested))}
        assertTrue(nested.listFiles()!!.isEmpty())
    }
    @Test fun zipSlipCannotEscapeExtractionFolder() {
        val archive=File(root,"bad.zip")
        ZipOutputStream(archive.outputStream()).use{out->out.putNextEntry(ZipEntry("../escape.txt"));out.write("attack".toByteArray());out.closeEntry()}
        val dest=File(root,"dest").apply{mkdirs()}
        assertThrows(IllegalArgumentException::class.java){Archives(engine).unzip(ref(archive),ref(dest))}
        assertFalse(File(dest,"escape.txt").exists());assertFalse(File(root,"escape.txt").exists())
    }
    @Test fun zipRoundTrip() {
        val folder=File(root,"Folder").apply{mkdirs()};File(folder,"test.txt").writeText("Проверка ZIP")
        val dest=File(root,"dest").apply{mkdirs()}
        Archives(engine).zip(listOf(ref(folder)),ref(dest),"test.zip")
        Archives(engine).unzip(ref(File(dest,"test.zip")),ref(dest))
        assertEquals("Проверка ZIP",File(dest,"test/Folder/test.txt").readText())
    }
    @Test fun sameDirectoryViaSafAndFileRejected() {
        val phone=android.os.Environment.getExternalStorageDirectory()
        val folder=File(phone,"iFiles-test-folder")
        val tree=android.provider.DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents","primary:iFiles-test-folder/inside").toString()
        assertThrows(IllegalArgumentException::class.java){engine.assertOutside(ref(folder),tree)}
    }
    @Test fun unsafeNamesRejected() {
        listOf("../file","..",".","/file","a\\b","a\u0000b").forEach{v->assertThrows(IllegalArgumentException::class.java){Names.check(v)}}
        listOf("../file","/absolute","C:/file","a/../b","a\\b").forEach{v->assertThrows(IllegalArgumentException::class.java){Names.zipParts(v)}}
    }
    @Test fun purgeAllDeletesOnlyTrashBackups() {
        val untouched=File(root,"keep.txt").apply {writeText("keep me")}
        for(name in listOf("first.txt","second.txt")) {
            val f=File(root,name).apply {writeText("backup")}
            engine.trash(ref(f),ref(root))
        }
        val ids=engine.trashRecords().map {it.getString("id")}
        assertEquals(2,ids.size)
        engine.purgeMany(ids)
        assertTrue(engine.trashRecords().isEmpty())
        assertEquals("keep me",untouched.readText())
    }
    @Test fun purgeSelectionIsValidatedBeforeAnyDeletion() {
        val f=File(root,"saved.txt").apply {writeText("valuable")}
        engine.trash(ref(f),ref(root))
        val record=engine.trashRecords().single()
        assertThrows(IllegalArgumentException::class.java) {
            engine.purgeMany(listOf(record.getString("id"),"../outside"))
        }
        assertEquals(1,engine.trashRecords().size)
        assertEquals("valuable",s.input(record.getString("backup")).bufferedReader().readText())
    }
    @Test fun purgeSelectionCanBeRepeatedAndDeduplicated() {
        val f=File(root,"file.txt").apply {writeText("data")}
        engine.trash(ref(f),ref(root))
        val id=engine.trashRecords().single().getString("id")
        engine.purgeMany(listOf(id,id))
        engine.purgeMany(listOf(id))
        assertTrue(engine.trashRecords().isEmpty())
    }
    @Test fun purgeRestoredBackupLeavesRestoredFileUntouched() {
        val f=File(root,"restore.txt").apply {writeText("restored content")}
        engine.trash(ref(f),ref(root))
        val id=engine.trashRecords().single().getString("id")
        engine.restore(id,null)
        engine.purgeMany(listOf(id))
        assertEquals("restored content",f.readText())
        assertTrue(engine.trashRecords().isEmpty())
    }

}
