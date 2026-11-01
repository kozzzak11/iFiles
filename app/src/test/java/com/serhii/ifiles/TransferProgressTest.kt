package com.serhii.ifiles

import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransferProgressTest {
    @Test fun pumpSyncsEachChunkBeforeCountingIt() {
        val size = TransferCopy.CHUNK + 10
        val order = ArrayList<String>()
        var reported = 0L
        TransferCopy.pump(ByteArrayInputStream(ByteArray(size)), ByteArrayOutputStream(), {}, {
            reported += it
            order += "bytes:$it"
        }, {
            order += "sync"
        })
        assertEquals(size.toLong(), reported)
        assertEquals(listOf("sync", "bytes:${TransferCopy.CHUNK}", "sync", "bytes:10"), order)
    }

    @Test fun copyReportsEveryWrittenByteIncludingNestedFiles() {
        val s = Storage(ApplicationProvider.getApplicationContext())
        val root = File(s.context.cacheDir, "progress-${System.nanoTime()}").apply { mkdirs() }
        try {
            val folder = File(root, "Folder").apply { mkdirs() }
            File(folder, "a.bin").writeBytes(ByteArray(10) { 1 })
            val sub = File(folder, "sub").apply { mkdirs() }
            File(sub, "b.bin").writeBytes(ByteArray(TransferCopy.CHUNK + 20) { 2 })
            val engine = FileEngine(s)
            val (bytes, files) = engine.tally(listOf(folder.toURI().toString()))
            assertEquals(10L + TransferCopy.CHUNK + 20, bytes)
            assertEquals(2, files)
            var got = 0L
            var calls = 0
            val copying = FileEngine(s, onBytes = { got += it; calls++ })
            val dest = File(root, "dest").apply { mkdirs() }
            copying.copy(folder.toURI().toString(), dest.toURI().toString())
            assertEquals(bytes, got)
            assertTrue(calls >= 2)
            assertEquals(10L, File(dest, "Folder/a.bin").length())
            assertEquals(TransferCopy.CHUNK + 20L, File(dest, "Folder/sub/b.bin").length())
        } finally { root.deleteRecursively() }
    }

    @Test fun stallAfterFifteenSecondsOfSilenceAndNotDuringVerify() {
        val t = TransferTracker()
        t.setKind("copy")
        t.setTotal(1_000_000, 2)
        t.phase("write", now = 0)
        t.startFile("video.mp4", now = 0)
        t.addBytes(250_000, now = 1_000)
        val moving = t.snapshot(2_000)
        assertFalse(moving.stalled)
        assertEquals(25, moving.percent)
        assertEquals("video.mp4", moving.fileName)
        assertEquals(1, moving.fileIndex)
        assertTrue(moving.speed > 0)
        assertTrue(moving.line().contains("из"))
        assertTrue(moving.title().contains("25%"))
        val stuck = t.snapshot(1_000 + TransferTracker.STALL_MS)
        assertTrue(stuck.stalled)
        assertEquals("Ожидание устройства…", stuck.title())
        assertTrue(stuck.line().contains("нет новых данных"))
        assertTrue(stuck.sub().contains("файл 1 из 2"))
        assertTrue(stuck.sub().contains("передача остановилась"))
        assertEquals(0, stuck.speed)
        t.phase("verify", now = 20_000)
        assertFalse(t.snapshot(40_000).stalled)
        assertEquals("Проверка копии", t.snapshot(40_000).title())
    }

    @Test fun speedAndEtaUseTheRecentWindow() {
        val t = TransferTracker()
        t.setTotal(1_000_000, 1)
        t.phase("write", now = 0)
        t.addBytes(200_000, now = 1_000)
        val snap = t.snapshot(1_000)
        assertEquals(200_000, snap.speed)
        assertEquals(4_000, snap.etaMs)
        assertEquals(20, snap.percent)
        t.addBytes(800_000, now = 2_000)
        assertEquals(99, t.snapshot(2_000).percent)
    }

    @Test fun notificationIsOngoingDeterminateAndCancellable() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val t = TransferTracker()
        t.setKind("move")
        t.setTotal(8L * 1024 * 1024 * 1024, 3)
        t.phase("write", now = 0)
        t.startFile("archive.bin", now = 0)
        t.addBytes(2L * 1024 * 1024 * 1024, now = 1_000)
        val snap = t.snapshot(1_000)
        val notes = TransferNotifier(context, UUID.randomUUID())
        val n = notes.build(snap, ongoing = true)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(TransferSnap.BAR, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertTrue(n.extras.getInt(Notification.EXTRA_PROGRESS) in 1 until TransferSnap.BAR)
        assertFalse(n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("%"))
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("из"))
        assertEquals("Отменить", n.actions.single().title)
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(TransferNotifier.CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        val stalled = t.snapshot(1_000 + TransferTracker.STALL_MS)
        val waiting = notes.build(stalled, ongoing = true)
        assertEquals("Ожидание устройства…", waiting.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        notes.finish("failed", "Диск отключён")
        val done = context.getSystemService(NotificationManager::class.java).activeNotifications
        assertTrue(done.any { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) == "Не выполнено" && it.notification.extras.getCharSequence(Notification.EXTRA_TEXT) == "Диск отключён" })
    }
}
