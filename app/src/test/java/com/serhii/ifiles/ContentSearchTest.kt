package com.serhii.ifiles

import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ContentSearchTest {
    private lateinit var s: Storage
    private lateinit var index: ContentIndex
    private lateinit var dir: File
    private val root get() = dir.toURI().toString()

    @Before fun setup() {
        s=Storage(ApplicationProvider.getApplicationContext())
        index=ContentIndex(s.context,null) // in-memory database
        dir=File(s.context.cacheDir,"idx-${System.nanoTime()}").apply { mkdirs() }
    }
    @After fun cleanup() { index.close(); dir.deleteRecursively() }

    private fun write(name: String, text: String)=File(dir,name).apply { parentFile!!.mkdirs(); writeText(text) }
    private fun indexer(pdf: ((File)->String)? = null)=Indexer(s,index,pdf)

    @Test fun decodesUtf8BomAndWindows1251() {
        assertEquals("Привет",TextExtract.decode("Привет".toByteArray(Charsets.UTF_8)))
        assertEquals("Привет",TextExtract.decode(byteArrayOf(0xEF.toByte(),0xBB.toByte(),0xBF.toByte())+"Привет".toByteArray()))
        assertEquals("Счёт оплачен",TextExtract.decode("Счёт оплачен".toByteArray(charset("windows-1251"))))
        // A UTF-8 sequence cut at the read cap is still UTF-8.
        val cut="Документ".toByteArray().let { it.copyOf(it.size-1) }
        assertTrue(TextExtract.decode(cut).startsWith("Докумен"))
    }

    @Test fun extractsPlainHtmlDocxXlsAndSkipsBinary() {
        assertEquals("fun main() = println(42)",TextExtract.extract(write("a.kt","fun main() = println(42)"),"a.kt"))
        val html=TextExtract.extract(write("p.html","<html><style>x{}</style><b>Важный</b>&nbsp;текст</html>"),"p.html")!!
        assertTrue(html.contains("Важный") && html.contains("текст") && !html.contains("<b>") && !html.contains("x{}"))
        val docx=File(dir,"r.docx"); ZipOutputStream(docx.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("word/document.xml")); z.write("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Квартальный отчёт</w:t></w:r></w:p></w:body></w:document>""".toByteArray()); z.closeEntry()
        }
        assertTrue(TextExtract.extract(docx,"r.docx")!!.contains("Квартальный отчёт"))
        val xls=File(dir,"t.xls"); jxl.Workbook.createWorkbook(xls).apply { createSheet("Лист",0).addCell(jxl.write.Label(0,0,"Бюджет 2026")); write(); close() }
        assertTrue(TextExtract.extract(xls,"t.xls")!!.contains("Бюджет 2026"))
        val bin=File(dir,"blob").apply { writeBytes(ByteArray(2048) { (it%7).toByte() }) }
        assertNull(TextExtract.extract(bin,"blob"))
        assertFalse(TextExtract.supported(Entry("x","photo.jpg",false,mime="image/jpeg")))
        assertEquals("from pdf",TextExtract.extract(write("d.pdf","%PDF"),"d.pdf",pdf={ "from pdf" }))
        assertNull(TextExtract.extract(write("e.pdf","%PDF"),"e.pdf",pdf=null))
    }

    @Test fun extractsPdfTextWithPdfbox() {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(s.context)
        val pdf=File(dir,"invoice.pdf")
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { doc ->
            val page=com.tom_roush.pdfbox.pdmodel.PDPage(); doc.addPage(page)
            com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc,page).use { cs ->
                cs.beginText(); cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA,12f); cs.newLineAtOffset(72f,700f)
                cs.showText("Invoice number 4711 for warehouse rent"); cs.endText()
            }
            doc.save(pdf)
        }
        val text=TextExtract.extract(pdf,"invoice.pdf",pdf={ PdfText.extract(s.context,it) })!!
        assertTrue(text,text.contains("Invoice number 4711"))
        indexer { PdfText.extract(s.context,it) }.run(root)
        assertEquals(listOf("invoice.pdf"),index.search("warehouse").map { it.name })
    }

    @Test fun indexesSearchesWithSnippetAndScope() {
        write("notes/meeting.txt","Обсудили договор аренды склада и сроки оплаты")
        write("notes/deep/plan.md","Roadmap: launch the indexer in October")
        write("other.txt","Ничего важного")
        val st=indexer().run(root)
        assertEquals(3,st.seen); assertEquals(3,st.indexed)
        val hits=index.search("договор аренд")
        assertEquals(listOf("meeting.txt"),hits.map { it.name })
        assertTrue(hits[0].inContent)
        val (plain,ranges)=Snippets.parse(hits[0].snippet)
        assertTrue(plain.contains("договор"))
        assertTrue(ranges.map { plain.substring(it.first,it.last+1).lowercase() }.containsAll(listOf("договор","аренды")))
        // Scope: only below the given folder, and "notes" must not match a sibling "notes-old".
        write("notes-old/x.txt","launch the indexer"); indexer().run(root)
        val scoped=index.search("launch",File(dir,"notes").toURI().toString())
        assertEquals(listOf("plan.md"),scoped.map { it.name })
        assertEquals(2,index.search("launch").size)
        // Name match without content.
        assertTrue(index.search("meeting").any { it.name=="meeting.txt" && !it.inContent })
    }

    @Test fun incrementalSkipsUnchangedReindexesChangedAndRemovesDeleted() {
        val a=write("a.txt","alpha first"); write("b.txt","beta")
        indexer().run(root)
        val second=indexer().run(root)
        assertEquals(2,second.skipped); assertEquals(0,second.indexed)
        a.writeText("alpha second version"); a.setLastModified(a.lastModified()+5000)
        val third=indexer().run(root)
        assertEquals(1,third.indexed)
        assertTrue(index.search("second").isNotEmpty()); assertTrue(index.search("first").isEmpty())
        File(dir,"b.txt").delete()
        assertEquals(1,indexer().run(root).removed)
        assertTrue(index.search("beta").isEmpty())
        assertEquals(1L,index.count(root))
    }

    @Test fun cancelledOrUnavailableWalkKeepsEarlierResults() {
        repeat(30) { write("f$it.txt","shared word number$it") }
        indexer().run(root)
        assertEquals(30L,index.count(root))
        File(dir,"f0.txt").delete()
        var calls=0
        try { indexer().run(root,check={ if(++calls>5) throw kotlinx.coroutines.CancellationException("stop") }); fail() } catch(_: kotlinx.coroutines.CancellationException) {}
        assertEquals("nothing is deleted after an incomplete walk",30L,index.count(root))
        val gone=File(dir,"missing").toURI().toString()
        index.begin(gone)
        try { indexer().run(gone); fail() } catch(_: IOException) {}
        assertEquals(30L,index.count(root))
        // The deleted file is dropped once a full walk completes.
        indexer().run(root)
        assertEquals(29L,index.count(root))
    }

    @Test fun queryIsSanitisedForFts() {
        assertEquals("\"foo*\" \"OR*\" \"bar*\"",ContentIndex.matchExpression("foo\" OR bar*"))
        assertNull(ContentIndex.matchExpression("  ** \"\" "))
        write("x.txt","NEAR AND OR quoted")
        indexer().run(root)
        // Operators in user input are plain words, never FTS syntax errors.
        assertEquals(1,index.search("OR\" NEAR(").size)
        assertTrue(index.search("\"; DROP TABLE files; --").isEmpty())
        assertEquals(1L,index.count())
    }

    @Test fun scopePrefixes() {
        assertEquals("file:/a/b/",ContentIndex.scopePrefix("file:/a/b"))
        assertEquals("file:/a/b/",ContentIndex.scopePrefix("file:/a/b/"))
        assertTrue(ContentIndex.scopePrefix("content://x/tree/primary%3ADocs/document/primary%3ADocs").endsWith("%2F"))
    }

    @Test fun forgetAndRemove() {
        write("k.txt","keyword"); indexer().run(root)
        assertEquals(listOf(root),index.rootInfo().map { it.root })
        assertTrue(index.rootInfo().single().complete)
        index.remove(File(dir,"k.txt").toURI().toString())
        assertTrue(index.search("keyword").isEmpty())
        index.forget(root)
        assertTrue(index.rootInfo().isEmpty())
    }

    @Test fun cancelledQueuedOperationIsSkippedWithoutFailingTheChain() = runBlocking {
        val id=UUID.randomUUID()
        val dirTasks=File(s.context.filesDir,"tasks").apply { mkdirs() }
        File(dirTasks,"$id.json").writeText("""{"kind":"mkdir","refs":[],"parent":"${s.local}","name":"never"}""")
        Operations.cancel(s.context,id)
        val worker=TestListenableWorkerBuilder<OperationWorker>(s.context).setId(id).build()
        val result=worker.doWork()
        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue((result as ListenableWorker.Result.Success).outputData.getBoolean("cancelled",false))
        assertFalse(File(s.file(s.local),"never").exists())
        assertFalse(Operations.cancelFlag(s.context,id).exists())
    }
}
