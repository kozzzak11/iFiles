package com.serhii.ifiles

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.workDataOf
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.UUID

/**
 * Large writes, bytes counted as they are handed to the stream.
 * fsync runs at the end of the file and at most every [SYNC_EVERY] bytes — not per chunk.
 * A per-chunk fsync on exFAT/FUSE (USB) stalls for minutes and freezes the progress ticker.
 */
object TransferCopy {
    const val BUFFER = 4 * 1024 * 1024
    const val SYNC_EVERY = 64L * 1024 * 1024
    fun pump(
        input: InputStream,
        out: OutputStream,
        checkpoint: () -> Unit,
        onBytes: (Long) -> Unit,
        beforeSync: () -> Unit = {},
        sync: (OutputStream) -> Unit = ::sync,
        syncEvery: Long = SYNC_EVERY,
        limit: Long = -1
    ) {
        val buf = ByteArray(BUFFER)
        var since = 0L
        var written = 0L
        while (true) {
            if (limit >= 0 && written >= limit) break
            checkpoint()
            val want = if (limit >= 0) minOf(buf.size.toLong(), limit - written).toInt() else buf.size
            val n = input.read(buf, 0, want)
            if (n < 0) break
            out.write(buf, 0, n)
            onBytes(n.toLong())
            written += n
            since += n
            if (since >= syncEvery) {
                beforeSync()
                sync(out)
                since = 0L
            }
        }
        beforeSync()
        sync(out)
    }
    fun sync(out: OutputStream) {
        out.flush()
        if (out is FileOutputStream) out.fd.sync()
    }
}

/** Posts at most once a second, and never repeats the same text. */
class NotifyGate(private val minMs: Long = 1000) {
    private var lastAt = -1L
    private var lastKey = ""
    fun allow(key: String, now: Long = System.currentTimeMillis()): Boolean {
        if (key == lastKey) return false
        if (lastAt >= 0L && now - lastAt < minMs) return false
        lastAt = now
        lastKey = key
        return true
    }
}

/** Byte progress shared by the copy thread and the notification ticker. */
class TransferTracker {
    private val lock = Any()
    private var kind = "copy"
    private var phase = "run"
    private var bytesDone = 0L
    private var bytesTotal = 0L
    private var fileIndex = 0
    private var fileCount = 0
    private var fileName = ""
    private var lastByteAt = 0L
    private var phaseAt = 0L
    private var shown = 0L
    private var shownPercent = -1
    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun setKind(value: String) = synchronized(lock) { kind = value }
    fun setTotal(bytes: Long, files: Int) = synchronized(lock) {
        bytesTotal += bytes.coerceAtLeast(0)
        fileCount += files.coerceAtLeast(0)
    }
    fun touchName(name: String) = synchronized(lock) { if (name.isNotEmpty()) fileName = name.take(150) }
    fun startFile(name: String, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (phase != "write") {
            lastByteAt = now
            samples.clear()
            samples.addLast(now to bytesDone)
        }
        phase = "write"
        fileIndex++
        if (name.isNotEmpty()) fileName = name.take(150)
    }
    fun addBytes(n: Long, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (n <= 0L) return
        bytesDone += n
        lastByteAt = now
        if (phase != "write" && phase != "sync") { samples.clear(); phaseAt = now }
        phase = "write"
        credit(now)
    }
    fun phase(value: String, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (value != phase) phaseAt = now
        // Sync is invisible: keep the copy clock and the speed window.
        if (value == "write" && phase != "write" && phase != "sync") {
            lastByteAt = now
            samples.clear()
            samples.addLast(now to shown)
        }
        phase = value
    }
    private fun credit(now: Long) {
        val capped = if (bytesTotal > 0L) bytesDone.coerceIn(0L, bytesTotal) else bytesDone.coerceAtLeast(0L)
        if (capped > shown) shown = capped
        remember(now, shown)
    }
    private fun remember(now: Long, value: Long) {
        samples.addLast(now to value)
        val cutoff = now - 20_000
        while (samples.size > 1 && samples.first().first < cutoff) samples.removeFirst()
    }
    fun snapshot(now: Long = System.currentTimeMillis()): TransferSnap = synchronized(lock) {
        credit(now)
        val speed = speedAt(now, shown)
        val stalled = (phase == "write" || phase == "sync") && lastByteAt > 0L && now - lastByteAt >= STALL_MS
        val pct = if (bytesTotal <= 0L) -1 else ((shown * 100) / bytesTotal).toInt().coerceIn(0, 100)
        if (pct > shownPercent) shownPercent = pct
        val percent = if (bytesTotal <= 0L) -1 else shownPercent.coerceAtLeast(0)
        val eta = if (!stalled && speed > 0L && bytesTotal > shown) (bytesTotal - shown) * 1000 / speed else -1L
        TransferSnap(
            kind, phase, shown, bytesTotal, fileIndex, fileCount, fileName,
            if (stalled) 0L else speed, eta, stalled, percent,
            if (stalled) ((now - lastByteAt) / 1000).toInt() else 0,
            if (phaseAt > 0L) ((now - phaseAt) / 1000).toInt().coerceAtLeast(0) else 0
        )
    }
    private fun speedAt(now: Long, current: Long): Long {
        if (samples.isEmpty()) return 0L
        val cutoff = now - 15_000
        val start = samples.lastOrNull { it.first <= cutoff } ?: samples.first()
        val dt = now - start.first
        if (dt < 500L) return 0L
        val delta = current - start.second
        return if (delta <= 0L) 0L else delta * 1000 / dt
    }
    companion object {
        const val STALL_MS = 15_000L
        const val HINT_MS = 120_000L
    }
}

data class TransferSnap(
    val kind: String,
    val phase: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    val fileIndex: Int,
    val fileCount: Int,
    val fileName: String,
    val speed: Long,
    val etaMs: Long,
    val stalled: Boolean,
    val percent: Int,
    val stallSec: Int,
    val phaseSec: Int = 0,
    val verifyDone: Long = 0,
    val verifyPlanned: Long = 0,
    val fileVerifyDone: Long = 0,
    val fileVerifyTotal: Long = 0,
    val fileEtaMs: Long = -1,
    val overallPercent: Int = -1
) {
    val indeterminate: Boolean get() = percent < 0 || phase == "prepare"
    val longStall: Boolean get() = stalled && stallSec >= TransferTracker.HINT_MS / 1000
    fun title(): String = when {
        stalled -> "Ожидание устройства…"
        percent >= 0 -> "${kindTitle(kind)} · $percent%"
        else -> kindTitle(kind)
    }
    /** One line for the whole job: copied out of total, and time left. Sync does not change it. */
    fun line(): String {
        val parts = ArrayList<String>(4)
        if (bytesTotal > 0L) parts += "${humanSize(bytesDone)} из ${humanSize(bytesTotal)}"
        else if (bytesDone > 0L) parts += humanSize(bytesDone)
        if (!stalled && etaMs >= 0L && bytesDone < bytesTotal) parts += remainText(etaMs)
        if (stalled) parts += "нет новых данных $stallSec с"
        if (longStall) parts += HINT
        return parts.joinToString(" · ").ifEmpty { if (phase == "prepare") "Подсчёт…" else "Подготовка…" }
    }
    fun sub(): String = fileName
    fun chip(): String = when {
        stalled -> "Ждём"
        percent >= 0 -> "$percent%"
        else -> "…"
    }.take(7)
    fun bar(): Int = when {
        indeterminate || bytesTotal <= 0L -> 0
        else -> ((bytesDone.coerceIn(0L, bytesTotal).toDouble() / bytesTotal.toDouble()) * BAR).toInt().coerceIn(0, BAR)
    }
    fun toData(): Data = workDataOf(
        "file" to fileName.take(150),
        "title" to title().take(80),
        "line" to line().take(180),
        "sub" to sub().take(220),
        "text" to listOf(title(), line()).filter { it.isNotEmpty() }.joinToString(" · ").take(200),
        "bytes" to bytesDone,
        "total" to bytesTotal,
        "index" to fileIndex,
        "count" to fileCount,
        "speed" to speed,
        "eta" to etaMs,
        "stalled" to stalled,
        "phase" to phase,
        "percent" to percent,
        "stallSec" to stallSec,
        "kind" to kind
    )
    companion object {
        const val BAR = 1000
        fun kindTitle(kind: String) = when (kind) {
            "copy" -> "Копирование"
            "move" -> "Перемещение"
            "trash" -> "В корзину"
            "restore" -> "Восстановление"
            "zip" -> "Сжатие"
            "unzip" -> "Распаковка"
            "purge" -> "Очистка"
            "mkdir" -> "Создание папки"
            "rename" -> "Переименование"
            else -> "Операция"
        }
        const val HINT = "Накопитель не отвечает — проверь кабель/питание"
        fun remainText(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(1)
            return when {
                s < 60 -> "осталось ~$s с"
                s < 3600 -> "осталось ~${s / 60} мин"
                else -> "осталось ~${s / 3600} ч ${(s % 3600) / 60} мин"
            }
        }
    }
}

/**
 * Ongoing data-sync notification. Android 16+ also requests a progress-centric Live Update;
 * older releases keep the standard determinate progress notification.
 */
class TransferNotifier(private val context: Context, workId: UUID) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val notificationId = workId.hashCode()
    private val open = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    private val cancel = PendingIntent.getBroadcast(
        context, workId.hashCode(),
        Intent(context, CancelOperationReceiver::class.java).putExtra("id", workId.toString()),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    fun foreground(snap: TransferSnap): ForegroundInfo {
        ensureChannel()
        return ForegroundInfo(notificationId, build(snap, ongoing = true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    /** Shade update that does not go through WorkManager, so a blocked fsync cannot freeze it. */
    fun update(snap: TransferSnap) {
        ensureChannel()
        nm.notify(notificationId, build(snap, ongoing = true))
    }
    fun finish(outcome: String, detail: String) {
        ensureChannel()
        val title = when (outcome) {
            "done" -> "Готово"
            "cancelled" -> "Отменено"
            else -> "Не выполнено"
        }
        val icon = if (outcome == "done") android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error
        val n = base(icon, title, detail.ifBlank { title }, ongoing = false)
            .setAutoCancel(true)
            .setProgress(0, 0, false)
            .build()
        nm.notify(notificationId xor DONE, n)
    }
    fun build(snap: TransferSnap, ongoing: Boolean): Notification {
        ensureChannel()
        val builder = base(android.R.drawable.stat_sys_download, snap.title(), snap.line(), ongoing)
            .setSubText(snap.sub().ifEmpty { null })
            .setProgress(TransferSnap.BAR, snap.bar(), snap.indeterminate)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setRequestPromotedOngoing(ongoing)
            .setShortCriticalText(snap.chip())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отменить", cancel)
        // ProgressStyle is the Android 16 progress-centric / Live Update template.
        // Below API 36 the compat style is ignored and setProgress() above is the bar.
        val style = NotificationCompat.ProgressStyle()
        if (snap.indeterminate) style.setProgressIndeterminate(true)
        else style.setProgress(snap.bar()).addProgressSegment(
            NotificationCompat.ProgressStyle.Segment(TransferSnap.BAR).setColor(0xFF0A84FF.toInt())
        )
        builder.setStyle(style)
        return builder.build()
    }
    private fun base(icon: Int, title: String, text: String, ongoing: Boolean) =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(0xFF0A84FF.toInt())
    private fun ensureChannel() {
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val channel = NotificationChannel(CHANNEL, "Передача файлов", NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "Прогресс копирования и перемещения, пока iFiles свёрнут"
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        channel.setShowBadge(false)
        nm.createNotificationChannel(channel)
    }
    companion object {
        /** New id: the previous "operations" channel is stuck at LOW importance on existing installs. */
        const val CHANNEL = "transfers"
        private const val DONE = 0x5A17C0DE
    }
}
