package com.serhii.ifiles

import android.net.Uri
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Path/URI handling for spaces, Cyrillic, special characters and long names; Recent/Favorites rules. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PathsAndLibraryTest {
    private lateinit var s: Storage
    private lateinit var root: File
    private val tricky=listOf("Мой отчёт 2026.pdf","a b  c.txt","100% #1 & (копия) [v2]?.docx","Ёжик в тумане — финал.MP3","emoji 😀 файл.txt","plus+sign;semi=.csv")
    @Before fun setup() { s=Storage(ApplicationProvider.getApplicationContext()); root=File(s.context.cacheDir,"paths ${System.nanoTime()} Папка").apply{mkdirs()}; s.clearRecents(); s.prefs.edit().remove("favorites").apply() }
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun fileRefsRoundTripWithSpacesCyrillicAndSymbols() {
        for(name in tricky) {
            val f=File(root,name).apply{writeText("x")}
            val ref=Storage.fileRef(f.path)
            assertTrue(name,s.isFile(ref))
            assertEquals(name,f.canonicalPath,s.file(ref).canonicalPath)
            assertEquals(name,name,s.stat(ref).name)
            // Media3/FileProvider read the decoded android.net.Uri path: it must be the real path.
            assertEquals(name,f.absolutePath,Uri.parse(ref).path)
            assertEquals(name,Storage.fileRef(root.path),s.parentOf(ref))
            assertEquals(name,"x",s.input(ref).bufferedReader().readText())
        }
    }
    @Test fun uriFromFileFormIsNormalisedToTheSameRef() {
        val f=File(root,"Документ с пробелами.txt").apply{writeText("x")}
        val viaAndroid=Uri.fromFile(f) // percent-encodes Cyrillic, unlike java.net.URI
        assertEquals(Storage.fileRef(f.path),Storage.fileRef(viaAndroid.path!!))
    }
    @Test fun longCyrillicNamesFitTheByteLimitAndKeepExtension() {
        val long="Очень длинное имя документа ".repeat(10)+".pdf"
        val fitted=Names.fit(long)
        assertTrue(Names.bytes(fitted)<=Names.MAX_BYTES)
        assertTrue(fitted.endsWith("….pdf"))
        assertTrue(long.startsWith(fitted.removeSuffix("….pdf")))
        assertEquals("short.txt",Names.fit("short.txt"))
        val emoji=Names.fit("😀".repeat(100)+".txt")
        assertTrue(Names.bytes(emoji)<=Names.MAX_BYTES)
        assertEquals(emoji,String(emoji.toByteArray(Charsets.UTF_8),Charsets.UTF_8)) // no broken surrogate pair
    }
    @Test fun creatingLongCyrillicFileOnDiskSucceeds() {
        val long="Протокол совещания по проекту ".repeat(7)+".txt"
        assertTrue(Names.bytes(long)>255)
        Names.check(long)
        val ref=s.create(Storage.fileRef(root.path),long,false)
        val created=s.file(ref)
        assertTrue(created.exists())
        assertTrue(Names.bytes(created.name)<=255)
        val second=s.unique(Storage.fileRef(root.path),long)
        assertNotEquals(created.name,second)
        assertTrue(Names.bytes(second)<=255)
    }
    @Test fun namesUpTo255CharactersAreAccepted() {
        Names.check("я".repeat(255))
        assertThrows(IllegalArgumentException::class.java){Names.check("я".repeat(256))}
        assertThrows(IllegalArgumentException::class.java){Names.check("a/b")}
    }
    @Test fun trashKeepsOriginalCyrillicNameOnRestore() {
        val engine=FileEngine(s)
        val f=File(root,"Отчёт за май — итог.txt").apply{writeText("данные")}
        engine.trash(Storage.fileRef(f.path),Storage.fileRef(root.path))
        assertFalse(f.exists())
        val rec=engine.trashRecords().first { it.getString("name")==f.name }
        engine.restore(rec.getString("id"),null)
        assertEquals("данные",f.readText())
        engine.trashRoot.deleteRecursively()
    }
    @Test fun persistedTreeGrantMatchesExactTreeNotPrefix() {
        val grant=Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")
        val inTree=Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2F%D0%9F%D0%B5%D1%81%D0%BD%D1%8F%201.mp3")
        val sibling=Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic%20Old/document/primary%3AMusic%20Old%2Fa.mp3")
        assertTrue(Storage.covers(grant,inTree))
        assertTrue(sibling.toString().startsWith(grant.toString())) // the old prefix check accepted this
        assertFalse(Storage.covers(grant,sibling))
        assertFalse(Storage.covers(grant,Uri.parse("content://other.provider/tree/primary%3AMusic/document/x")))
        assertEquals("primary:Music",Storage.treeId(inTree))
    }
    @Test fun physicalPathOfSafDocumentWithCyrillicAndSpaces() {
        val u="content://com.android.externalstorage.documents/tree/primary%3ADocuments/document/primary%3ADocuments%2F%D0%9C%D0%BE%D0%B8%20%D1%84%D0%B0%D0%B9%D0%BB%D1%8B%2Fa%20b.txt"
        assertEquals(File(Environment.getExternalStorageDirectory(),"Documents/Мои файлы/a b.txt").path,s.physical(u)?.path)
    }
    @Test fun previewKindIgnoresExtensionCase() {
        fun kind(n: String)=PreviewFiles.kind(Entry("x",n,false),byteArrayOf(1,2,3,0))
        assertEquals("image",kind("ФОТО.JPG"));assertEquals("media",kind("Песня.MP3"));assertEquals("pdf",kind("Счёт.PDF"));assertEquals("text",kind("заметки.TXT"));assertEquals("binary",kind("data.bin"))
    }
    @Test fun unknownFilePeekShowsTextEvenWhenCutMidLetter() {
        val bytes="Привет мир ".repeat(400).toByteArray().copyOf(4095)
        val (isText,text)=PreviewFiles.peek(bytes)
        assertTrue(isText);assertTrue(text.startsWith("Привет мир"))
        val (bin,hex)=PreviewFiles.peek(byteArrayOf(0,1,2,3,(-1).toByte()))
        assertFalse(bin);assertTrue(hex.lowercase().contains("00 01 02 03"))
    }
    @Test fun recentIsNewestFirstDistinctAndCapped() {
        var list=emptyList<String>()
        repeat(150){ list=Library.push(list,"r$it") }
        assertEquals(Library.RECENT_LIMIT,list.size)
        assertEquals("r149",list.first());assertEquals("r50",list.last())
        list=Library.push(list,"r100")
        assertEquals("r100",list.first());assertEquals(1,list.count{it=="r100"});assertEquals(100,list.size)
    }
    @Test fun recentStoragePrunesMissingAndClears() {
        val a=File(root,"а.txt").apply{writeText("1")};val b=File(root,"б.txt").apply{writeText("2")}
        s.recent(Storage.fileRef(a.path));s.recent(Storage.fileRef(b.path))
        assertEquals(listOf(Storage.fileRef(b.path),Storage.fileRef(a.path)),s.recents())
        b.delete()
        assertEquals(listOf(Storage.fileRef(a.path)),s.pruneRecents { s.file(it).exists() })
        assertEquals(listOf(Storage.fileRef(a.path)),s.recents())
        s.clearRecents();assertTrue(s.recents().isEmpty())
        s.prefs.edit().putString("recent","not json").apply();assertTrue(s.recents().isEmpty())
    }
    @Test fun favoritesToggleFilesAndFoldersAndAreBounded() {
        val folder=Storage.fileRef(File(root,"Папка").apply{mkdirs()}.path);val file=Storage.fileRef(File(root,"ф.txt").apply{writeText("x")}.path)
        s.pin(folder);s.pin(file)
        assertTrue(s.isFavorite(folder));assertTrue(s.isFavorite(file))
        s.pin(file);assertFalse(s.isFavorite(file))
        s.prefs.edit().putStringSet("favorites",(1..Library.FAVORITES_LIMIT).map{"f$it"}.toSet()).apply()
        assertThrows(IllegalArgumentException::class.java){s.pin("one-more")}
        s.pin("f1");assertFalse(s.isFavorite("f1"))
        assertEquals(Library.FAVORITES_LIMIT-1,s.strings("favorites").size)
        assertEquals(10,s.unpinMissing { it !in (2..11).map{n->"f$n"} })
        assertEquals(Library.FAVORITES_LIMIT-11,s.strings("favorites").size)
    }
}
