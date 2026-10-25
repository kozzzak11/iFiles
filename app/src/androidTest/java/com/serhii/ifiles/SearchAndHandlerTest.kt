package com.serhii.ifiles

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import androidx.work.WorkManager
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Content search (index, progress, notification, results), the pick flow, "Open with" and the documents provider. */
@RunWith(AndroidJUnit4::class)
class SearchAndHandlerTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val device get()=UiDevice.getInstance(ins)
    private val shots get()=File(context.getExternalFilesDir(null),"preview-screenshots").apply{mkdirs()}
    private fun shell(command: String){android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(command)).use{it.readBytes()}}
    private fun shot(name: String){device.waitForIdle();Thread.sleep(400);device.takeScreenshot(File(shots,"$name.png"));shell("mkdir -p /data/local/tmp/ifiles-screens");shell("screencap -p /data/local/tmp/ifiles-screens/$name.png")}
    private fun await(selector: androidx.test.uiautomator.BySelector, what: String, timeout: Long): UiObject2 {
        val end=System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<end){ device.wait(Until.findObject(selector),500)?.let{return it} }
        throw AssertionError("Missing $what")
    }
    private fun find(text: String, timeout: Long=15000)=await(By.text(text),"\"$text\"",timeout)
    private fun desc(d: String, timeout: Long=15000)=await(By.desc(d),"desc \"$d\"",timeout)
    private fun quickShot(name: String){device.takeScreenshot(File(shots,"$name.png"));shell("mkdir -p /data/local/tmp/ifiles-screens");shell("screencap -p /data/local/tmp/ifiles-screens/$name.png")}
    private fun waitIndexed() {
        val end=System.currentTimeMillis()+240_000
        while(System.currentTimeMillis()<end) {
            val jobs=WorkManager.getInstance(context).getWorkInfosForUniqueWork(Indexing.QUEUE).get()
            if(jobs.isNotEmpty() && jobs.all { it.state.isFinished }) return
            Thread.sleep(500)
        }
        throw AssertionError("Indexing did not finish")
    }
    private fun hit(name: String, timeout: Long=20000)=await(By.descStartsWith("Результат: $name"),"hit $name",timeout)
    private lateinit var s: Storage
    private lateinit var dir: File

    @Before fun prepare() {
        shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        s=Storage(context)
        dir=File(java.net.URI(s.local))
        dir.listFiles()?.forEach { it.deleteRecursively() }
        WorkManager.getInstance(context).cancelUniqueWork(Indexing.QUEUE).result.get()
        ContentIndex.get(context).let { idx -> idx.roots().forEach(idx::forget) }
        s.clearRecents()
        s.prefs.edit().remove("favorites").remove("clipboard").putString("tab","Обзор").putString("stack",JSONArray().toString()).putBoolean("grid",false).apply()
    }
    @After fun cleanup() {
        WorkManager.getInstance(context).cancelUniqueWork(Indexing.QUEUE)
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun pdf(file: File, vararg lines: String) {
        val doc=PdfDocument()
        try {
            val page=doc.startPage(PdfDocument.PageInfo.Builder(400,300,1).create())
            page.canvas.drawColor(Color.WHITE)
            val paint=Paint().apply { color=Color.BLACK; textSize=16f }
            lines.forEachIndexed { i,l -> page.canvas.drawText(l,24f,40f+24f*i,paint) }
            doc.finishPage(page); file.outputStream().use { doc.writeTo(it) }
        } finally { doc.close() }
    }

    @Test fun contentSearchWithIndexingProgressAndNotification() {
        pdf(File(dir,"scan-0042.pdf"),"Договор аренды склада № 17","warehouse lease agreement")
        File(dir,"Заметки").mkdirs()
        File(dir,"Заметки/notes.txt").writeText("Позвонить арендодателю: продление аренды склада до декабря.")
        File(dir,"Заметки/old-1251.txt").writeBytes("Счёт за аренду склада, оплата до 5 числа".toByteArray(charset("windows-1251")))
        File(dir,"Отчёт.txt").writeText("Ничего общего")
        // Enough files that the progress row and the notification are visible for a while.
        for(f in 0 until 60) { val sub=File(dir,"Архив/папка-$f").apply { mkdirs() }; for(i in 0 until 100) File(sub,"запись-$i.txt").writeText("Запись $f/$i: инвентаризация, накладная, приход и расход.\n".repeat(30)) }
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { scenario ->
            // Typing Cyrillic through UiAutomator is unreliable on the emulator IME; drive the same ViewModel the search field uses.
            fun type(q: String)=scenario.onActivity { androidx.lifecycle.ViewModelProvider(it)[FilesViewModel::class.java].query(q) }
            desc("Поиск")
            Indexing.enqueue(context,listOf(s.local))
            desc("Индексация",30000)
            shot("search-02-index-progress")
            waitIndexed()
            // Second full pass (index cleared) with the notification shade open.
            ContentIndex.get(context).forget(s.local)
            device.openNotification(); device.wait(Until.hasObject(By.res("com.android.systemui","notification_stack_scroller")),3000)
            Indexing.enqueue(context,listOf(s.local))
            find("Индексация для поиска",20000)
            quickShot("search-03-index-notification")
            device.pressBack(); device.wait(Until.gone(By.text("Индексация для поиска")),3000)
            waitIndexed()
            val index=ContentIndex.get(context)
            assertEquals(6004L,index.count(s.local))
            val pdfText=PdfText.extract(context,File(dir,"scan-0042.pdf"))
            assertTrue("PDF text: $pdfText",pdfText.contains("warehouse"))
            val hits=index.search("аренды склада")
            assertEquals("hits: $hits",setOf("scan-0042.pdf","notes.txt"),hits.map { it.name }.toSet())
            type("аренды склада")
            try { find("В тексте файлов · 2",20000) } catch(e: AssertionError) { shot("search-debug-failure"); throw e }
            hit("scan-0042.pdf")
            hit("notes.txt")
            find("В тексте файлов · 2")
            shot("search-01-results")
            type("аренду")
            hit("old-1251.txt")
            type("аренды склада")
            find("В тексте файлов · 2")
            hit("scan-0042.pdf").click()
            find("Закрыть",15000)
            shot("search-04-open-hit")
        }
    }

    @Test fun pickerReturnsGrantedContentUris() {
        File(dir,"Договор.txt").writeText("один"); File(dir,"Счёт.txt").writeText("два")
        File(dir,"Фото.png").writeBytes(ByteArray(10))
        val request=Intent(Intent.ACTION_GET_CONTENT).setClass(context,PickerActivity::class.java).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
        ActivityScenario.launchActivityForResult<PickerActivity>(request).use { scenario ->
            desc("Выбор: iFiles").click()
            desc("Выбор: Договор.txt").click(); desc("Выбор: Счёт.txt").click()
            assertNull("filtered by MIME type",device.findObject(By.desc("Выбор: Фото.png")))
            shot("picker-01-multi-select")
            find("Готово (2)").click()
            val result=scenario.result
            assertEquals(Activity.RESULT_OK,result.resultCode)
            val data=result.resultData
            assertTrue(data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
            val uris=(0 until data.clipData!!.itemCount).map { data.clipData!!.getItemAt(it).uri }
            assertEquals(2,uris.size)
            uris.forEach { assertEquals("content",it.scheme); assertEquals("${context.packageName}.files",it.authority) }
            assertEquals(setOf("один","два"),uris.map { u -> context.contentResolver.openInputStream(u)!!.use { it.readBytes().decodeToString() } }.toSet())
        }
    }

    @Test fun openWithShowsPreviewAndRejectsOwnProviderUris() {
        val pm=context.packageManager
        fun handles(type: String)=pm.queryIntentActivities(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://x.y/file"),type),0).any { it.activityInfo.packageName==context.packageName }
        for(type in listOf("image/jpeg","video/mp4","audio/mpeg","application/pdf","text/plain","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.ms-excel","application/zip")) assertTrue(type,handles(type))
        assertFalse(handles("application/x-unknown-thing"))
        assertTrue(pm.queryIntentActivities(Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),0).any { it.activityInfo.packageName==context.packageName })

        val monitor=ins.addMonitor(MediaActivity::class.java.name,null,false)
        try {
            val bad=Intent(Intent.ACTION_VIEW).setClass(context,ViewerActivity::class.java).setDataAndType(Uri.parse("content://${context.packageName}.files/local/x.txt"),"text/plain").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(bad)
            assertNull("own provider URIs are refused",monitor.waitForActivityWithTimeout(3000))
            val uri=Uri.parse("content://com.serhii.ifiles.test.preview/pdf")
            context.startActivity(Intent(Intent.ACTION_VIEW).setClass(context,ViewerActivity::class.java).setDataAndType(uri,"application/pdf").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            val media=monitor.waitForActivityWithTimeout(15000)
            assertNotNull(media)
            assertEquals(uri.toString(),media.intent.getStringExtra("ref"))
            assertTrue(media.intent.getBooleanExtra(MediaActivity.EXTERNAL,false))
            Thread.sleep(2000)
            shot("viewer-01-open-with-pdf")
            assertFalse("not added to recents",uri.toString() in s.recents())
            media.finish()
        } finally { ins.removeMonitor(monitor) }
    }

    @Test fun documentsProviderExposesOnlyTheIfilesFolder() {
        File(dir,"Общий.txt").writeText("видно")
        val authority="${context.packageName}.documents"
        assertTrue(context.packageManager.queryIntentContentProviders(Intent(DocumentsContract.PROVIDER_INTERFACE),0).any { it.providerInfo.authority==authority })
        context.contentResolver.query(DocumentsContract.buildRootsUri(authority),null,null,null,null)!!.use { c -> assertEquals(1,c.count) }
        val children=DocumentsContract.buildChildDocumentsUri(authority,"local:")
        context.contentResolver.query(children,null,null,null,null)!!.use { c ->
            val names=buildList { while(c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))) }
            assertEquals(listOf("Общий.txt"),names)
        }
        val doc=DocumentsContract.buildDocumentUri(authority,"local:Общий.txt")
        assertEquals("видно",context.contentResolver.openInputStream(doc)!!.use { it.readBytes().decodeToString() })
        val escape=DocumentsContract.buildDocumentUri(authority,"local:../shared_prefs/ifiles.xml")
        assertThrows(java.io.FileNotFoundException::class.java) { context.contentResolver.openInputStream(escape)!!.close() }
    }
}
