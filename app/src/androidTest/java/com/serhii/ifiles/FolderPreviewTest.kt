package com.serhii.ifiles

import android.content.ComponentName
import android.os.SystemClock
import android.util.Base64
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FolderPreviewTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val device get()=UiDevice.getInstance(ins)
    private fun zip(name:String,vararg files:Pair<String,String>):Entry {
        val f=File(context.cacheDir,name)
        ZipOutputStream(f.outputStream()).use{z->files.forEach{(path,text)->z.putNextEntry(ZipEntry(path));z.write(text.toByteArray());z.closeEntry()}}
        return Entry(f.toURI().toString(),name,false,f.length())
    }
    @After fun cleanup(){context.stopService(android.content.Intent(context,PlaybackService::class.java))}
    @Test fun mixedFolderNavigatesWithoutClosingAndStopsDepartedVideo() {
        val sheet=zip("Grid.xlsx","xl/workbook.xml" to """<workbook xmlns:r="urn:r"><sheets><sheet name="Проверка Excel" r:id="one"/></sheets></workbook>""","xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="one" Target="worksheets/sheet1.xml"/></Relationships>""","xl/worksheets/sheet1.xml" to """<worksheet><sheetData><row><c r="A1" t="inlineStr"><is><t>Excel cell</t></is></c></row></sheetData></worksheet>""")
        val word=zip("Report.docx","word/document.xml" to """<document><p><r><t>Readable Word document</t></r></p></document>""")
        val entries=listOf(Entry("content://com.serhii.ifiles.test.preview/png","Photo.png",false,mime="image/png"),Entry("content://com.serhii.ifiles.test.preview/pdf","Report.pdf",false,mime="application/pdf"),sheet,word,Entry("content://com.serhii.ifiles.test.preview/mp4","Movie.mp4",false,mime="video/mp4"),Entry("content://com.serhii.ifiles.test.preview/wav","Music.wav",false,mime="audio/wav"),Entry("content://com.serhii.ifiles.test.preview/png?second","Last.png",false,mime="image/png"))
        val queue=PreviewQueue.save(context,entries.first(),entries)
        ActivityScenario.launch<MediaActivity>(MediaActivity.intent(context,entries.first()).putExtra("queue",queue.name)).use {
            for((i,e) in entries.withIndex()) {
                assertTrue("Missing title ${e.name}",device.wait(Until.hasObject(By.text(e.name)),15000))
                assertTrue(device.wait(Until.hasObject(By.text("${i+1} / ${entries.size}")),10000))
                if(i==0 || i==6)assertTrue("Photo must decode",device.wait(Until.hasObject(By.desc("Просмотр")),15000))
                if(i==1)assertTrue("PDF must render a page",device.wait(Until.hasObject(By.text("1 / 1")),15000))
                if(i==2){assertTrue("Workbook sheet selector must render",device.wait(Until.hasObject(By.text("Проверка Excel")),15000));assertTrue("Excel cells must render",device.wait(Until.hasObject(By.text("Excel cell")),15000))}
                if(i==3)assertTrue("Word body must render",device.wait(Until.hasObject(By.text("Readable Word document")),15000))
                val screenshots=File(context.getExternalFilesDir(null),"preview-screenshots").apply{mkdirs()}
                device.takeScreenshot(File(screenshots,"preview-$i.png"))
                // Swipes in any preview and in the footer must never navigate to another file.
                val w=device.displayWidth;val h=device.displayHeight
                device.swipe(w*4/5,h/2,w/5,h/2,20)
                device.waitForIdle()
                assertNotNull("Swipe changed the selected file",device.findObject(By.text(e.name)))
                val footer=device.findObject(By.text("${i+1} / ${entries.size}")).visibleBounds
                device.swipe(w*3/4,footer.centerY(),w/4,footer.centerY(),20)
                device.waitForIdle()
                assertNotNull("Footer swipe changed the selected file",device.findObject(By.text(e.name)))
                if(i<entries.lastIndex)device.findObject(By.desc("Следующий файл")).click()
            }
            lateinit var future:com.google.common.util.concurrent.ListenableFuture<MediaController>
            ins.runOnMainSync{future=MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}
            val player=future.get(15,TimeUnit.SECONDS)
            try{ins.runOnMainSync{assertFalse("Leaving media must pause it",player.playWhenReady)}}finally{ins.runOnMainSync{player.release()}}
            device.findObject(By.desc("Предыдущий файл")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Music.wav")),10000))
        }
    }
    @Test fun wideWorkbookFitsAndZoomControlsWork() {
        val cells=(0..11).joinToString(""){i->"<c r='${('A'.code+i).toChar()}1' t='inlineStr'><is><t>Column $i content</t></is></c>"}
        val sheet=zip("Wide.xlsx","xl/workbook.xml" to "<workbook xmlns:r='urn:r'><sheets><sheet name='Wide sheet' r:id='one'/></sheets></workbook>","xl/_rels/workbook.xml.rels" to "<Relationships><Relationship Id='one' Target='worksheets/sheet1.xml'/></Relationships>","xl/worksheets/sheet1.xml" to "<worksheet><sheetData><row>$cells</row></sheetData></worksheet>")
        fun findWeb(v:android.view.View):OfficeWebView? {
            if(v is OfficeWebView)return v
            if(v is android.view.ViewGroup)for(i in 0 until v.childCount)findWeb(v.getChildAt(i))?.let{return it}
            return null
        }
        ActivityScenario.launch<MediaActivity>(MediaActivity.intent(context,sheet)).use { scenario->
            assertTrue(device.wait(Until.hasObject(By.text("Column 0 content")),15000))
            device.waitForIdle()
            var initial=0f
            scenario.onActivity { activity->initial=findWeb(activity.window.decorView)!!.actualZoom();assertTrue("Wide sheet should initially fit",initial<0.8f) }
            device.findObject(By.text("100%")).click()
            val zoomDeadline=SystemClock.elapsedRealtime()+5000
            var zoomed=false
            while(SystemClock.elapsedRealtime()<zoomDeadline && !zoomed) {
                scenario.onActivity { activity->val web=findWeb(activity.window.decorView)!!;zoomed=web.actualZoom()>initial*1.5f && web.canScrollHorizontally(1) }
                if(!zoomed)SystemClock.sleep(100)
            }
            assertTrue("100% must enlarge the sheet and allow panning",zoomed)
            device.findObject(By.text("По ширине")).click()
            val fitDeadline=SystemClock.elapsedRealtime()+5000
            var fitted=false
            while(SystemClock.elapsedRealtime()<fitDeadline && !fitted) {
                scenario.onActivity { activity->fitted=findWeb(activity.window.decorView)!!.actualZoom()<=initial*1.1f }
                if(!fitted)SystemClock.sleep(100)
            }
            assertTrue("Fit must restore overview",fitted)
        }
    }
    @Test fun multiPagePdfScrollsVerticallyAndStaysInSameFile() {
        val file=File(context.cacheDir,"Thirty pages.pdf")
        val pdf=android.graphics.pdf.PdfDocument()
        try {
            for(i in 1..30){val page=pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595,842,i).create());page.canvas.drawText("Page $i",40f,80f,android.graphics.Paint().apply{textSize=30f});pdf.finishPage(page)}
            file.outputStream().use{pdf.writeTo(it)}
        }finally{pdf.close()}
        val entry=Entry(file.toURI().toString(),file.name,false,file.length(),mime="application/pdf")
        val next=Entry("content://com.serhii.ifiles.test.preview/png","Next.png",false,mime="image/png")
        val queue=PreviewQueue.save(context,entry,listOf(entry,next))
        ActivityScenario.launch<MediaActivity>(MediaActivity.intent(context,entry).putExtra("queue",queue.name)).use {
            assertTrue(device.wait(Until.hasObject(By.desc("Страница PDF 1")),15000))
            val w=device.displayWidth;val h=device.displayHeight
            repeat(5){device.swipe(w/2,h*3/4,w/2,h/4,30);device.waitForIdle()}
            assertNotNull(device.findObject(By.text(file.name)))
            assertFalse("PDF stayed on first page",device.hasObject(By.text("1 / 30")))
            device.findObject(By.text("+")).click()
            device.waitForIdle()
            assertTrue(device.hasObject(By.text("125%")))
            device.swipe(w*4/5,h/2,w/5,h/2,20)
            assertNotNull("Panning PDF changed file",device.findObject(By.text(file.name)))
            device.findObject(By.text("По ширине")).click()
            repeat(10){device.swipe(w/2,h/4,w/2,h*3/4,20);device.waitForIdle()}
            assertTrue(device.wait(Until.hasObject(By.text("1 / 30")),10000))
            device.findObject(By.desc("Следующий файл")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Next.png")),10000))
        }
    }
    @Test fun singleFileKeepsArrowsAndExternalOpenLivesInMenu() {
        val file=File(context.cacheDir,"Single.txt").apply{writeText("Single document body")}
        val entry=Entry(file.toURI().toString(),file.name,false,file.length(),mime="text/plain")
        ActivityScenario.launch<MediaActivity>(MediaActivity.intent(context,entry)).use {
            assertTrue(device.wait(Until.hasObject(By.text("Single document body")),15000))
            assertTrue(device.hasObject(By.text("1 / 1")))
            assertFalse(device.findObject(By.desc("Предыдущий файл")).isEnabled)
            assertFalse(device.findObject(By.desc("Следующий файл")).isEnabled)
            assertFalse(device.hasObject(By.text("Открыть в другом приложении")))
            device.findObject(By.desc("Ещё")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Открыть в другом приложении")),5000))
            device.pressBack()
        }
    }
    @Test fun tappingFileInBrowserOpensInternallyWithFolderArrows() {
        val storage=Storage(context)
        val oldStack=storage.prefs.getString("stack","[]")
        val oldTab=storage.prefs.getString("tab","Обзор")
        val directory=File(context.cacheDir,"browse-test").apply{mkdirs()}
        File(directory,"A.txt").writeText("First browser document")
        File(directory,"B.txt").writeText("Second browser document")
        storage.prefs.edit().putString("stack",org.json.JSONArray(listOf(directory.toURI().toString())).toString()).putString("tab","Обзор").commit()
        device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        try {
            ActivityScenario.launch<MainActivity>(android.content.Intent(context,MainActivity::class.java)).use {
                assertTrue(device.wait(Until.hasObject(By.text("A.txt")),15000))
                device.findObject(By.text("A.txt")).click()
                assertTrue(device.wait(Until.hasObject(By.text("First browser document")),15000))
                assertTrue(device.hasObject(By.text("1 / 2")))
                assertEquals(context.packageName,device.currentPackageName)
                device.findObject(By.desc("Следующий файл")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Second browser document")),15000))
                assertTrue(device.hasObject(By.text("2 / 2")))
                device.findObject(By.text("Готово")).click()
            }
        }finally{storage.prefs.edit().putString("stack",oldStack).putString("tab",oldTab).commit();directory.deleteRecursively()}
    }
    @Test fun oldWordDocParsesOnAndroidWithoutDesktopJava() {
        // Apache POI test-data/document/simple.doc, Apache-2.0.
        val file=File(context.cacheDir,"simple.doc")
        file.writeBytes(Base64.decode(ins.context.assets.open("poi-simple.doc.base64").readBytes(),Base64.DEFAULT))
        try{val html=WordDocuments.html(file,true){};assertTrue(html.contains("This is a simple file"));assertFalse(html.contains("WordDocument"))}finally{file.delete()}
    }
}
