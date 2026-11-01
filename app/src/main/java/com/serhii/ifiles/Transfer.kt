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
 * Copies [input] to [out] in 256 KB chunks. Each chunk is flushed and, for a real file
 * (including a SAF [FileOutputStream]), synced before its bytes are reported — so the
 * percent tracks data that has left the page cache, not a buffer that finishes instantly.
 */
object TransferCopy {
    const val CHUNK = 256 * 1024
    fun pump(
        input: InputStream,
        out: OutputStream,
        checkpoint: () -> Unit,
        onBytes: (Long) -> Unit,
        sync: (OutputStream) -> Unit = ::sync
    ) {
        val buf = ByteArray(CHUNK)
        var pending = 0L
        fun push(force: Boolean) {
            if (pending <= 0L || (!force && pending < CHUNK)) return
            sync(out)
            onBytes(pending)
            pending = 0L
        }
        while (true) {
            checkpoint()
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            pending += n
            push(false)
        }
        push(true)
    }
    fun sync(out: OutputStream) {
        out.flush()
        if (out is FileOutputStream) out.fd.sync()
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
        phase = "write"
        samples.addLast(now to bytesDone)
        val cutoff = now - 8_000
        while (samples.size > 1 && samples.first().first < cutoff) samples.removeFirst()
    }
    fun phase(value: String, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (value == "write" && phase != "write") {
            lastByteAt = now
            samples.clear()
            samples.addLast(now to bytesDone)
        }
        phase = value
    }
    fun snapshot(now: Long = System.currentTimeMillis()): TransferSnap = synchronized(lock) {
        val speed = speedAt(now)
        val stalled = phase == "write" && lastByteAt > 0L && now - lastByteAt >= STALL_MS &&
            (bytesTotal <= 0L || bytesDone < bytesTotal)
        val percent = when {
            bytesTotal <= 0L -> -1
            bytesDone <= 0L -> 0
            bytesDone >= bytesTotal -> if (phase == "done") 100 else 99
            else -> ((bytesDone * 100) / bytesTotal).toInt().coerceIn(0, 99)
        }
        val eta = if (!stalled && speed > 0L && bytesTotal > bytesDone) (bytesTotal - bytesDone) * 1000 / speed else -1L
        TransferSnap(
            kind, phase, bytesDone, bytesTotal, fileIndex, fileCount, fileName,
            if (stalled) 0L else speed, eta, stalled, percent,
            if (stalled) ((now - lastByteAt) / 1000).toInt() else 0
        )
    }
    private fun speedAt(now: Long): Long {
        if (samples.isEmpty()) return 0L
        val cutoff = now - 5_000
        val start = samples.lastOrNull { it.first <= cutoff } ?: samples.first()
        val dt = now - start.first
        if (dt < 500L) return 0L
        val delta = bytesDone - start.second
        return if (delta <= 0L) 0L else delta * 1000 / dt
    }
    companion object { const val STALL_MS = 15_000L }
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
    val stallSec: Int
) {
    val indeterminate: Boolean get() = percent < 0 || phase == "prepare"
    fun title(): String = when {
        stalled -> "Ожидание устройства…"
        phase == "verify" -> "Проверка копии"
        phase == "prepare" -> "Подсчёт…"
        percent >= 0 -> "${kindTitle(kind)} $percent%"
        else -> kindTitle(kind)
    }
    /** Collapsed notification line: file, X of Y, speed, ETA (or how long the port has been silent). */
    fun line(): String {
        val parts = ArrayList<String>(5)
        if (fileName.isNotEmpty()) parts += fileName
        if (bytesTotal > 0L) parts += "${humanSize(bytesDone)} из ${humanSize(bytesTotal)}"
        else if (bytesDone > 0L) parts += humanSize(bytesDone)
        if (!stalled && speed > 0L) parts += "${humanSize(speed)}/с"
        if (!stalled && etaMs >= 0L && speed > 0L) parts += etaText(etaMs)
        if (stalled) parts += "нет новых данных $stallSec с"
        return parts.joinToString(" · ").ifEmpty { "Подготовка…" }
    }
    fun sub(): String {
        val parts = ArrayList<String>(3)
        if (fileCount > 0 && fileIndex > 0) parts += "файл $fileIndex из $fileCount"
        if (stalled) parts += "передача остановилась"
        else if (phase == "verify") parts += "сверка содержимого"
        return parts.joinToString(" · ")
    }
    fun chip(): String = when {
        stalled -> "Ждём"
        percent >= 0 -> "$percent%"
        else -> "…"
    }.take(7)
    /** 0..1000 for the system progress bar. Full while the bytes are written and the hash is still running. */
    fun bar(): Int = when {
        indeterminate || bytesTotal <= 0L -> 0
        else -> ((bytesDone.coerceAtMost(bytesTotal).toDouble() / bytesTotal.toDouble()) * BAR).toInt().coerceIn(0, BAR)
    }
    fun toData(): Data = workDataOf(
        "file" to fileName.take(150),
        "title" to title().take(80),
        "line" to line().take(180),
        "sub" to sub().take(120),
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
        fun etaText(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(1)
            return when {
                s < 60 -> "≈ $s с"
                s < 3600 -> "≈ ${s / 60} мин"
                else -> "≈ ${s / 3600} ч ${(s % 3600) / 60} мин"
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
