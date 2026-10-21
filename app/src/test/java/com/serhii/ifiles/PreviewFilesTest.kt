package com.serhii.ifiles

import androidx.test.core.app.ApplicationProvider
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.*
import java.util.zip.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PreviewFilesTest {
    private lateinit var s: Storage
    private lateinit var root: File
    @Before fun setup(){s=Storage(ApplicationProvider.getApplicationContext());root=File(s.context.cacheDir,"preview-${System.nanoTime()}").apply{mkdirs()}}
    @After fun cleanup(){root.deleteRecursively()}
    private fun file(name: String,bytes: ByteArray)=File(root,name).apply {writeBytes(bytes)}
    @Test fun pipOnlyAutoEntersForActiveVideo() {
        assertTrue(PlaybackRules.pip(true,true,androidx.media3.common.Player.STATE_READY))
        assertFalse(PlaybackRules.pip(false,true,androidx.media3.common.Player.STATE_READY))
        assertFalse(PlaybackRules.pip(true,false,androidx.media3.common.Player.STATE_READY))
        assertFalse(PlaybackRules.pip(true,true,androidx.media3.common.Player.STATE_ENDED))
    }
    @Test fun pipAspectRatioRespectsAndroidLimits() {
        assertEquals(2.39f,PlaybackRules.ratio(10000,10),0.001f)
        assertEquals(1f/2.39f,PlaybackRules.ratio(10,10000),0.001f)
        assertEquals(16f/9f,PlaybackRules.ratio(0,0),0.001f)
    }
    @Test fun genericUsbVideoMimeStillEnablesPip() {
        assertTrue(PlaybackRules.video(Entry("x","Clip.MOV",false)))
        assertFalse(PlaybackRules.video(Entry("x","Music.mp3",false,mime="audio/mpeg")))
    }
    @Test fun appleDoubleResolvesOriginalWithoutChangingEitherFile() {
        val data="%PDF-1.7 original".toByteArray();val metadata=byteArrayOf(0,5,22,7,0,2,0,0)
        val original=file("Резюме.pdf",data);val sidecar=file("._Резюме.pdf",metadata)
        assertEquals(original.toURI().toString(),PreviewFiles.resolve(s,s.stat(sidecar.toURI().toString())).ref)
        assertArrayEquals(data,original.readBytes());assertArrayEquals(metadata,sidecar.readBytes())
    }
    @Test fun orphanSidecarExplainsMissingOriginal() {
        val sidecar=file("._missing.jpg",byteArrayOf(0,5,22,7))
        assertThrows(IOException::class.java){PreviewFiles.resolve(s,s.stat(sidecar.toURI().toString()))}
    }
    @Test fun legitimateDotUnderscoreFileIsNotRedirected() {
        val f=file("._notes.txt","actual text".toByteArray());assertEquals(f.toURI().toString(),PreviewFiles.resolve(s,s.stat(f.toURI().toString())).ref)
    }
    @Test fun signatureAndExtensionOverrideWrongProviderMime() {
        assertEquals("pdf",PreviewFiles.kind(Entry("content://x/1","x",false,mime="text/plain"),"%PDF-1.4".toByteArray()))
        assertEquals("image",PreviewFiles.kind(Entry("content://x/2","Фото.HEIC",false,mime="application/octet-stream"),byteArrayOf()))
        assertEquals("document",PreviewFiles.kind(Entry("x","Plan.DOCX",false),byteArrayOf()))
        assertEquals("appledouble",PreviewFiles.kind(Entry("x","x.pdf",false),byteArrayOf(0,5,22,7)))
    }
    @Test fun textSupportsUtf16AndUtf8Bom() {
        assertEquals("Привет",PreviewFiles.text(byteArrayOf(-1,-2)+"Привет".toByteArray(Charsets.UTF_16LE)))
        assertEquals("Hello",PreviewFiles.text(byteArrayOf(-17,-69,-65)+"Hello".toByteArray()))
        assertFalse(PreviewFiles.isText(byteArrayOf(0,1,2,0)))
    }
    @Test fun boundedReaderHandlesShortReads() {
        val input=object:ByteArrayInputStream(ByteArray(100){it.toByte()}) {override fun read(b:ByteArray,off:Int,len:Int)=super.read(b,off,minOf(3,len))}
        assertEquals(17,PreviewFiles.read(input,17).size)
    }
    @Test fun previewCopyIsByteExactAndReadOnly() {
        val bytes=ByteArray(2*1024*1024){(it*17).toByte()};val source=file("source",bytes);val dest=File(root,"cache")
        PreviewFiles.copy(source.inputStream(),dest,bytes.size.toLong()){}
        assertArrayEquals(bytes,dest.readBytes());assertArrayEquals(bytes,source.readBytes())
    }
    @Test fun truncatedUsbStreamDoesNotLeaveApparentlyCompleteCache() {
        val dest=File(root,"cache")
        assertThrows(IOException::class.java){PreviewFiles.copy(ByteArrayInputStream(ByteArray(12)),dest,50){}}
        assertFalse(dest.exists())
    }
    @Test fun disconnectRemovesPartialCacheAndClosesStream() {
        var closed=false;var reads=0;val dest=File(root,"cache")
        val input=object:InputStream() {
            override fun read():Int=throw IOException("USB disconnected")
            override fun read(b:ByteArray,off:Int,len:Int):Int {if(reads++>0)throw IOException("USB disconnected");b[off]=42;return 1}
            override fun close(){closed=true}
        }
        assertThrows(IOException::class.java){PreviewFiles.copy(input,dest,-1){}}
        assertTrue(closed);assertFalse(dest.exists())
    }
    @Test fun cancellationCleansPartialCopy() {
        val dest=File(root,"cache");var calls=0
        assertThrows(java.util.concurrent.CancellationException::class.java){PreviewFiles.copy(ByteArrayInputStream(ByteArray(900000)),dest,-1){if(calls++>0)throw java.util.concurrent.CancellationException()}}
        assertFalse(dest.exists())
    }
    @Test fun unknownLengthStreamsAreSupported() {
        val dest=File(root,"cache");PreviewFiles.copy(ByteArrayInputStream("pipe".toByteArray()),dest,-1){};assertEquals("pipe",dest.readText())
    }
    private fun archive(vararg entries:Pair<String,String>):File {
        val f=File(root,"document.zip");ZipOutputStream(f.outputStream()).use{z->entries.forEach{(name,text)->z.putNextEntry(ZipEntry(name));z.write(text.toByteArray());z.closeEntry()}};return f
    }
    @Test fun docxTextExtractionAndZipListing() {
        val f=archive("word/document.xml" to "<w:document xmlns:w=\"urn:test\"><w:p><w:t>Привет USB</w:t></w:p></w:document>")
        assertTrue(PreviewFiles.packageText(f,false){}.contains("Привет USB"));assertTrue(PreviewFiles.packageText(f,true){}.contains("word/document.xml"))
    }
    @Test fun spreadsheetResolvesSharedStringValues() {
        val f=archive("xl/sharedStrings.xml" to "<sst><si><t>Имя</t></si><si><t>Сергей</t></si></sst>","xl/worksheets/sheet1.xml" to "<worksheet><row><c t=\"s\"><v>0</v></c><c t=\"s\"><v>1</v></c><c><v>42</v></c></row></worksheet>")
        val text=PreviewFiles.packageText(f,false){}
        assertTrue(text.contains("Имя"));assertTrue(text.contains("Сергей"));assertTrue(text.contains("42"))
    }
    @Test fun xmlDtdIsRejected() {
        val f=archive("word/document.xml" to "<!DOCTYPE doc [<!ENTITY x \"not allowed\">]><doc>&x;</doc>")
        assertThrows(IOException::class.java){PreviewFiles.packageText(f,false){}}
    }
    @Test fun compressedXmlIsBounded() {
        val f=archive("word/document.xml" to "<doc>${"x".repeat(4*1024*1024+2)}</doc>")
        assertThrows(IllegalArgumentException::class.java){PreviewFiles.packageText(f,false){}}
    }
    @Test fun hiddenFilterDoesNotDeleteAnything() {
        val names=listOf("._Фото.jpg",".DS_Store","__MACOSX","Фото.jpg","resume.pdf");names.forEach{file(it,byteArrayOf(1))}
        assertEquals(listOf("Фото.jpg","resume.pdf"),names.filterNot(PreviewFiles::hidden));assertEquals(5,root.listFiles()!!.size)
    }
    @Test fun thousandsOfEntriesRemainAvailableToEngine() {
        repeat(2000){file(if(it%2==0)"._$it.txt" else "$it.txt",byteArrayOf(1))}
        val listed=s.list(root.toURI().toString());assertEquals(2000,listed.size);assertEquals(1000,listed.count{!PreviewFiles.hidden(it.name)})
    }
}
