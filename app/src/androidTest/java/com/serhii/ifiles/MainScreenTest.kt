package com.serhii.ifiles

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** iOS-style list, swipe actions, context menu, tabs and the in-app info screen; saves screenshots for review. */
@RunWith(AndroidJUnit4::class)
class MainScreenTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val device get()=UiDevice.getInstance(ins)
    private val shots get()=File(context.getExternalFilesDir(null),"preview-screenshots").apply{mkdirs()}
    private fun shell(command: String){android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(command)).use{it.readBytes()}}
    private fun shot(name: String){device.waitForIdle();Thread.sleep(400);device.takeScreenshot(File(shots,"$name.png"))}
    private fun find(text: String, timeout: Long=15000): UiObject2 {
        assertTrue("Missing \"$text\"",device.wait(Until.hasObject(By.text(text)),timeout))
        return device.findObject(By.text(text))
    }
    private fun desc(d: String, timeout: Long=10000): UiObject2 {
        assertTrue("Missing desc \"$d\"",device.wait(Until.hasObject(By.desc(d)),timeout))
        return device.findObject(By.desc(d))
    }
    private lateinit var dir: File
    @Before fun prepare() {
        shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val s=Storage(context)
        dir=File(java.net.URI(s.local))
        dir.listFiles()?.forEach { it.deleteRecursively() }
        File(dir,"Проекты").mkdirs();File(dir,"Проекты/Внутри проекта.txt").writeText("Файл внутри папки")
        File(dir,"Отчёт за октябрь 2026.txt").writeText("Итоги месяца: всё по плану.\n".repeat(20))
        File(dir,"Очень длинное название документа с пробелами и кириллицей — финальная версия (копия 2).txt").writeText("Длинное имя")
        val bmp=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888);Canvas(bmp).apply{drawColor(Color.rgb(90,160,230));drawCircle(320f,240f,140f,Paint().apply{color=Color.rgb(255,204,0)})}
        File(dir,"Фото с отпуска.png").outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)}
        ZipOutputStream(File(dir,"Презентация.docx").outputStream()).use{z->z.putNextEntry(ZipEntry("word/document.xml"));z.write("<document><p><r><t>Привет</t></r></p></document>".toByteArray());z.closeEntry()}
        File(dir,"data.bin").writeBytes(ByteArray(600){(it*7).toByte()})
        s.clearRecents()
        s.prefs.edit().remove("favorites").remove("clipboard").putString("tab","Обзор").putString("stack",JSONArray(listOf(s.local)).toString()).putBoolean("grid",false).apply()
    }
    @After fun cleanup(){ context.stopService(Intent(context,PlaybackService::class.java)) }

    @Test fun iosListSwipesContextMenuTabsAndInfoScreen() {
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use {
            val w=device.displayWidth
            find("Отчёт за октябрь 2026.txt")
            shot("ios-01-list")

            // Long-press: iOS-style context menu with icons.
            find("Отчёт за октябрь 2026.txt").longClick()
            find("Переименовать");find("Поделиться");find("В корзину");find("Открыть в другом приложении")
            shot("ios-02-context-menu")
            device.pressBack()
            assertTrue(device.wait(Until.gone(By.text("Переименовать")),5000))

            // Swipe left reveals Move / Delete; Move puts the item on the clipboard.
            val row=find("Презентация.docx").visibleBounds
            device.swipe(w*85/100,row.centerY(),w*55/100,row.centerY(),12)
            desc("Удалить");desc("Переместить")
            shot("ios-03-swipe-actions")
            desc("Переместить").click()
            find("Перемещение: 1")
            desc("Отменить вставку").click()

            // Swipe right adds to Favorites; folders can be starred from the context menu.
            val photo=find("Фото с отпуска.png").visibleBounds
            device.swipe(w*20/100,photo.centerY(),w*92/100,photo.centerY(),12)
            desc("В избранном")
            find("Проекты").longClick();find("В избранное").click()

            desc("Избранное").click()
            find("Фото с отпуска.png");find("Проекты")
            shot("ios-04-tab-favorites")
            find("Проекты").click()
            find("Внутри проекта.txt")
            device.pressBack()
            find("Фото с отпуска.png").click()
            desc("Просмотр",20000)
            shot("ios-05-preview-photo")
            device.pressBack()

            // Recent updates on every in-app preview.
            desc("Недавние").click()
            find("Фото с отпуска.png")
            shot("ios-06-tab-recent")

            desc("Обзор").click()
            find("Места")
            shot("ios-07-browse")
            find("iFiles").click()
            find("data.bin").click()
            find("HEX (первые 4 КБ)",20000)
            shot("ios-08-info-screen")
            device.pressBack()

            // Full swipe left moves the file to the trash.
            val bin=find("data.bin").visibleBounds
            device.swipe(w*85/100,bin.centerY(),w*3/100,bin.centerY(),15)
            assertTrue("Swipe-to-delete must trash the file",device.wait(Until.gone(By.text("data.bin")),20000))
            assertFalse(File(dir,"data.bin").exists())
        }
    }
}
