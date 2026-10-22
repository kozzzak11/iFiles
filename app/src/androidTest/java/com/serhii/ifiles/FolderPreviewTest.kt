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
                if(i==0){val bounds=device.displayWidth;device.swipe(bounds*4/5,device.displayHeight/2,bounds/5,device.displayHeight/2,20)}
                else if(i<entries.lastIndex)device.findObject(By.desc("Следующий файл")).click()
            }
            lateinit var future:com.google.common.util.concurrent.ListenableFuture<MediaController>
            ins.runOnMainSync{future=MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}
            val player=future.get(15,TimeUnit.SECONDS)
            try{ins.runOnMainSync{assertFalse("Leaving media must pause it",player.playWhenReady)}}finally{ins.runOnMainSync{player.release()}}
            device.findObject(By.desc("Предыдущий файл")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Music.wav")),10000))
        }
    }
    @Test fun oldWordDocParsesOnAndroidWithoutDesktopJava() {
        // Apache POI test-data/document/simple.doc, Apache-2.0.
        val file=File(context.cacheDir,"simple.doc")
        file.writeBytes(Base64.decode(ins.context.assets.open("poi-simple.doc.base64").readBytes(),Base64.DEFAULT))
        try{val html=WordDocuments.html(file,true){};assertTrue(html.contains("This is a simple file"));assertFalse(html.contains("WordDocument"))}finally{file.delete()}
    }
}
