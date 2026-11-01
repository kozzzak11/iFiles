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
            assertEquals(0L, verified)
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
        assertTrue(moving.title().contains("Копирование · 25%"))
        assertTrue(moving.line().contains("осталось"))
        val stuck = t.snapshot(1_000 + TransferTracker.STALL_MS)
        assertTrue(stuck.stalled)
        assertEquals("Ожидание устройства…", stuck.title())
        assertTrue(stuck.line().contains("нет новых данных"))
        assertEquals("video.mp4", stuck.sub())
        assertEquals(0, stuck.speed)
        val quiet = t.snapshot(1_000 + 120_000)
        assertTrue(quiet.longStall)
        assertTrue(quiet.line().contains(TransferSnap.HINT))
        t.phase("verify", now = 200_000)
        assertFalse(t.snapshot(205_000).stalled)
    }

    @Test fun manyFilesStayInsideTotalAndPercentOnlyRises() {
        val t = TransferTracker()
        t.setKind("copy")
        t.setTotal(1_000, 4)
        t.phase("write", now = 0)
        var lastPercent = -1
        var lastShown = -1L
        var now = 0L
        for (n in listOf(100L, 150L, 250L, 400L, 5_000L)) {
            now += 1_000
            t.startFile("f$n", now)
            t.addBytes(n, now)
            val snap = t.snapshot(now)
            assertTrue(snap.bytesDone in 0..1_000)
            assertTrue(snap.percent in 0..100)
            assertTrue(snap.percent >= lastPercent)
            assertTrue(snap.bytesDone >= lastShown)
            assertFalse(snap.title().contains("Проверка"))
            lastPercent = snap.percent
            lastShown = snap.bytesDone
        }
        val done = t.snapshot(now)
        assertEquals(1_000L, done.bytesDone)
        assertEquals(100, done.percent)
        assertEquals("Копирование · 100%", done.title())
    }

    @Test fun copyAndMoveDoNotRereadAndMoveDeletesOnlyAfterSizeMatch() {
        val s = Storage(ApplicationProvider.getApplicationContext())
        val root = File(s.context.cacheDir, "nomatch-${System.nanoTime()}").apply { mkdirs() }
        try {
            val folder = File(root, "In").apply { mkdirs() }
            File(folder, "a.txt").writeText("alpha")
            File(folder, "nested").mkdirs()
            File(folder, "nested/b.txt").writeText("beta-beta")
            var verified = 0L
            val engine = FileEngine(s, onVerifyBytes = { verified += it })
            val copied = engine.copy(folder.toURI().toString(), File(root, "out").apply { mkdirs() }.toURI().toString())
            assertEquals(0L, verified)
            assertTrue(engine.sizesMatch(folder.toURI().toString(), copied))
            val bad = File(File(java.net.URI(copied)), "a.txt")
            bad.writeText("x")
            assertFalse(engine.sizesMatch(folder.toURI().toString(), copied))
            val loose = File(root, "loose.txt").apply { writeText("move-me") }
            val movedTo = File(root, "moved").apply { mkdirs() }
            engine.move(loose.toURI().toString(), movedTo.toURI().toString())
            assertFalse(loose.exists())
            assertEquals("move-me", File(movedTo, "loose.txt").readText())
            assertEquals(0L, verified)
        } finally { root.deleteRecursively() }
    }

    @Test fun cancelledCopyKeepsTheSource() {
        val s = Storage(ApplicationProvider.getApplicationContext())
        val root = File(s.context.cacheDir, "cancel-${System.nanoTime()}").apply { mkdirs() }
        try {
            val src = File(root, "big.bin").apply { writeBytes(ByteArray(200_000) { 3 }) }
            val dest = File(root, "dest").apply { mkdirs() }
            var n = 0
            assertThrows(java.io.InterruptedIOException::class.java) {
                FileEngine(s, { ++n > 1 }).copy(src.toURI().toString(), dest.toURI().toString())
            }
            assertEquals(200_000L, src.length())
        } finally { root.deleteRecursively() }
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
        assertEquals(100, t.snapshot(2_000).percent)
        assertEquals(1_000_000L, t.snapshot(2_000).bytesDone)
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
