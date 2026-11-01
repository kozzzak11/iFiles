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
    @Test fun pumpCountsBytesBeforeARareSync() {
        val data = ByteArray(250) { 1 }
        val input = object : java.io.InputStream() {
            var pos = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= data.size) return -1
                val n = minOf(40, data.size - pos, len)
                System.arraycopy(data, pos, b, off, n)
                pos += n
                return n
            }
            override fun read(): Int = throw UnsupportedOperationException()
        }
        val order = ArrayList<String>()
        var reported = 0L
        TransferCopy.pump(input, ByteArrayOutputStream(), {}, {
            reported += it
            order += "b"
        }, { order += "sync" }, { order += "fsync" }, syncEvery = 100)
        assertEquals(250L, reported)
        assertEquals(7, order.count { it == "b" })
        // 100-byte boundaries plus the final sync, not once per 40-byte read.
        assertEquals(3, order.count { it == "fsync" })
        assertTrue(order.indexOf("b") < order.indexOf("sync"))
    }

    @Test fun copyReportsEveryWrittenByteIncludingNestedFiles() {
        val s = Storage(ApplicationProvider.getApplicationContext())
        val root = File(s.context.cacheDir, "progress-${System.nanoTime()}").apply { mkdirs() }
        try {
            val folder = File(root, "Folder").apply { mkdirs() }
            File(folder, "a.bin").writeBytes(ByteArray(10) { 1 })
            val sub = File(folder, "sub").apply { mkdirs() }
            File(sub, "b.bin").writeBytes(ByteArray(300_000) { 2 })
            val engine = FileEngine(s)
            val (bytes, files) = engine.tally(listOf(folder.toURI().toString()))
            assertEquals(10L + 300_000, bytes)
            assertEquals(2, files)
            var got = 0L
            var verified = 0L
            var syncs = 0
            val copying = FileEngine(s, onBytes = { got += it }, onPhase = { if (it == "sync") syncs++ }, onVerifyBytes = { verified += it })
            val dest = File(root, "dest").apply { mkdirs() }
            copying.copy(folder.toURI().toString(), dest.toURI().toString())
            assertEquals(bytes, got)
            assertEquals(bytes * 2, verified)
            assertTrue(syncs in 1..4)
            assertEquals(10L, File(dest, "Folder/a.bin").length())
            assertEquals(300_000L, File(dest, "Folder/sub/b.bin").length())
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
        val quiet = t.snapshot(1_000 + 120_000)
        assertTrue(quiet.longStall)
        assertTrue(quiet.line().contains(TransferSnap.HINT))
        t.phase("verify", now = 200_000)
        assertFalse(t.snapshot(205_000).stalled)
    }

    @Test fun verifyPhaseHasItsOwnPercentSpeedAndEta() {
        val t = TransferTracker()
        t.setKind("copy")
        t.setTotal(1_000, 1)
        t.phase("write", now = 0)
        t.addBytes(1_000, now = 1_000)
        t.startVerify("show.mkv", 1_000, now = 2_000)
        t.addVerified(250, now = 3_000)
        val snap = t.snapshot(3_000)
        assertEquals("verify", snap.phase)
        assertEquals(25, snap.percent)
        assertEquals(250, snap.speed)
        assertEquals(3_000, snap.fileEtaMs)
        assertTrue(snap.title().startsWith("Проверка копии 25%"))
        assertTrue(snap.line().contains("из"))
        assertTrue(snap.sub().contains("всего"))
        assertEquals(2_000, snap.verifyPlanned)
    }

    @Test fun notifyGateSkipsRepeatsAndPostsAtMostOnceASecond() {
        val gate = NotifyGate(1000)
        assertTrue(gate.allow("a", 0))
        assertFalse(gate.allow("a", 500))
        assertFalse(gate.allow("b", 500))
        assertTrue(gate.allow("b", 1000))
        assertFalse(gate.allow("b", 2500))
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
