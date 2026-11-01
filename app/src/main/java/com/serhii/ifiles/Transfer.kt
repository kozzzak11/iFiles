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
        syncEvery: Long = SYNC_EVERY
    ) {
        val buf = ByteArray(BUFFER)
        var since = 0L
        while (true) {
            checkpoint()
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            onBytes(n.toLong())
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
    private var verifyDone = 0L
    private var verifyPlanned = 0L
    private var fileVerifyDone = 0L
    private var fileVerifyTotal = 0L
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
        if (phase != "write") { samples.clear(); phaseAt = now }
        phase = "write"
        remember(now, bytesDone)
    }
    /** One hash pass of [name]. Resets the current-file bar; overall verified bytes keep accumulating. */
    fun startVerify(name: String, size: Long, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (phase != "verify") {
            lastByteAt = now
            phaseAt = now
            samples.clear()
            samples.addLast(now to verifyDone)
            if (verifyPlanned == 0L && bytesTotal > 0L) verifyPlanned = bytesTotal * 2
        }
        phase = "verify"
        fileVerifyDone = 0
        fileVerifyTotal = size.coerceAtLeast(0)
        if (name.isNotEmpty()) fileName = name.take(150)
    }
    fun addVerified(n: Long, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (n <= 0L) return
        verifyDone += n
        fileVerifyDone += n
        lastByteAt = now
        if (phase != "verify") { samples.clear(); phaseAt = now }
        phase = "verify"
        remember(now, verifyDone)
    }
    fun phase(value: String, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (value != phase) phaseAt = now
        if (value == "write" && phase != "write") {
            lastByteAt = now
            samples.clear()
            samples.addLast(now to bytesDone)
        }
        if (value == "verify" && phase != "verify") {
            lastByteAt = now
            samples.clear()
            samples.addLast(now to verifyDone)
            if (verifyPlanned == 0L && bytesTotal > 0L) verifyPlanned = bytesTotal * 2
        }
        phase = value
    }
    private fun remember(now: Long, value: Long) {
        samples.addLast(now to value)
        val cutoff = now - 8_000
        while (samples.size > 1 && samples.first().first < cutoff) samples.removeFirst()
    }
    fun snapshot(now: Long = System.currentTimeMillis()): TransferSnap = synchronized(lock) {
        val writing = phase != "verify"
        val cursor = if (writing) bytesDone else verifyDone
        val speed = speedAt(now, cursor)
        val stallClock = phase == "write" || phase == "verify"
        val stalled = stallClock && lastByteAt > 0L && now - lastByteAt >= STALL_MS &&
            (if (phase == "verify") fileVerifyTotal <= 0L || fileVerifyDone < fileVerifyTotal
             else bytesTotal <= 0L || bytesDone < bytesTotal)
        val writePercent = when {
            bytesTotal <= 0L -> -1
            bytesDone <= 0L -> 0
            bytesDone >= bytesTotal -> if (phase == "done") 100 else 99
            else -> ((bytesDone * 100) / bytesTotal).toInt().coerceIn(0, 99)
        }
        val filePercent = if (fileVerifyTotal > 0L) ((fileVerifyDone * 100) / fileVerifyTotal).toInt().coerceIn(0, 100) else -1
        val overallPercent = if (verifyPlanned > 0L) ((verifyDone.coerceAtMost(verifyPlanned) * 100) / verifyPlanned).toInt().coerceIn(0, 99) else filePercent
        val percent = when (phase) {
            "verify" -> if (filePercent >= 0) filePercent else overallPercent
            "sync" -> writePercent
            else -> writePercent
        }
        val fileEta = if (!stalled && speed > 0L && fileVerifyTotal > fileVerifyDone) (fileVerifyTotal - fileVerifyDone) * 1000 / speed else -1L
        val overallRemain = if (phase == "verify") verifyPlanned - verifyDone else bytesTotal - bytesDone
        val eta = if (!stalled && speed > 0L && overallRemain > 0L) overallRemain * 1000 / speed else -1L
        TransferSnap(
            kind, phase, bytesDone, bytesTotal, fileIndex, fileCount, fileName,
            if (stalled) 0L else speed, eta, stalled, percent,
            if (stalled) ((now - lastByteAt) / 1000).toInt() else 0,
            if (phaseAt > 0L) ((now - phaseAt) / 1000).toInt().coerceAtLeast(0) else 0,
            verifyDone, verifyPlanned, fileVerifyDone, fileVerifyTotal, fileEta, overallPercent
        )
    }
    private fun speedAt(now: Long, current: Long): Long {
        if (samples.isEmpty()) return 0L
        val cutoff = now - 5_000
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
        phase == "sync" -> "Сохранение на устройство…"
        phase == "verify" && percent >= 0 -> "Проверка копии $percent%"
        phase == "verify" -> "Проверка копии"
        phase == "prepare" -> "Подсчёт…"
        percent >= 0 -> "${kindTitle(kind)} $percent%"
        else -> kindTitle(kind)
    }
    /** Collapsed notification line: file, X of Y, speed, ETA (or how long the port has been silent). */
    fun line(): String {
        val parts = ArrayList<String>(6)
        if (fileName.isNotEmpty()) parts += fileName
        when {
            phase == "verify" && fileVerifyTotal > 0L -> parts += "${humanSize(fileVerifyDone)} из ${humanSize(fileVerifyTotal)}"
            phase == "verify" && verifyDone > 0L -> parts += humanSize(verifyDone)
            bytesTotal > 0L -> parts += "${humanSize(bytesDone)} из ${humanSize(bytesTotal)}"
            bytesDone > 0L -> parts += humanSize(bytesDone)
        }
        if (!stalled && phase != "sync" && phase != "prepare" && speed > 0L) parts += "${humanSize(speed)}/с"
        val eta = if (phase == "verify") fileEtaMs else etaMs
        if (!stalled && phase != "sync" && phase != "prepare" && eta >= 0L && speed > 0L) parts += etaText(eta)
        if (phase == "sync" || phase == "prepare") parts += "$phaseSec с"
        if (stalled) parts += "нет новых данных $stallSec с"
        if (longStall) parts += HINT
        return parts.joinToString(" · ").ifEmpty { "Подготовка…" }
    }
    fun sub(): String {
        val parts = ArrayList<String>(4)
        if (fileCount > 0 && fileIndex > 0) parts += "файл $fileIndex из $fileCount"
        if (phase == "verify" && verifyPlanned > 0L) {
            parts += "всего ${humanSize(verifyDone)} из ${humanSize(verifyPlanned)}"
            if (!stalled && etaMs >= 0L && speed > 0L) parts += etaText(etaMs)
            if (overallPercent >= 0) parts += "общая $overallPercent%"
        }
        if (stalled) parts += "передача остановилась"
        if (longStall) parts += HINT
        return parts.joinToString(" · ")
    }
    fun chip(): String = when {
        stalled -> "Ждём"
        percent >= 0 -> "$percent%"
        else -> "…"
    }.take(7)
    /** 0..1000. During verification this is the current file, so the bar moves instead of sitting full. */
    fun bar(): Int = when {
        indeterminate -> 0
        phase == "verify" && percent >= 0 -> (percent.toLong() * BAR / 100).toInt().coerceIn(0, BAR)
        bytesTotal <= 0L -> 0
        else -> ((bytesDone.coerceAtMost(bytesTotal).toDouble() / bytesTotal.toDouble()) * BAR).toInt().coerceIn(0, BAR)
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
