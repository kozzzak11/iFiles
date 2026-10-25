package com.serhii.ifiles

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** PDF text via PdfBox-Android (pure Java, no native code). Stops after [TextExtract.TEXT_CAP] characters. */
object PdfText {
    @Volatile private var ready=false
    fun extract(context: Context, file: File): String {
        if(!ready) synchronized(this) { if(!ready) { com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext); ready=true } }
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(file,com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val stripper=com.tom_roush.pdfbox.text.PDFTextStripper()
            val text=StringBuilder()
            for(page in 1..minOf(doc.numberOfPages,2000)) {
                if(Thread.currentThread().isInterrupted)throw InterruptedException()
                stripper.startPage=page; stripper.endPage=page
                text.append(stripper.getText(doc))
                if(text.length>=TextExtract.TEXT_CAP)break
            }
            return text.toString()
        }
    }
}

object Indexing {
    const val QUEUE="ifiles-index"
    const val AUTO="ifiles-index-auto"
    const val TAG="ifiles-index"
    private const val AUTO_KEY="indexAuto"
    fun enqueue(context: Context, roots: List<String>) {
        if(roots.isEmpty())return
        val work=OneTimeWorkRequestBuilder<IndexWorker>().addTag(TAG).setInputData(workDataOf("roots" to roots.distinct().toTypedArray())).build()
        // Requests queue up behind a running pass; cancel stops the whole indexing queue (never file operations).
        WorkManager.getInstance(context).enqueueUniqueWork(QUEUE,ExistingWorkPolicy.APPEND_OR_REPLACE,work)
    }
    fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(QUEUE) }
    fun auto(context: Context) = Storage(context).prefs.getBoolean(AUTO_KEY,false)
    /** Opt-in: index every available location now and then every 12 hours while the battery is not low. */
    fun setAuto(context: Context, enabled: Boolean) {
        Storage(context).prefs.edit().putBoolean(AUTO_KEY,enabled).apply()
        val wm=WorkManager.getInstance(context)
        if(enabled) {
            val periodic=PeriodicWorkRequestBuilder<IndexWorker>(12,TimeUnit.HOURS).addTag(TAG)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setInputData(workDataOf("auto" to true)).build()
            wm.enqueueUniquePeriodicWork(AUTO,ExistingPeriodicWorkPolicy.KEEP,periodic)
            enqueue(context,autoRoots(Storage(context)))
        } else wm.cancelUniqueWork(AUTO)
    }
    /** Internal iFiles storage, phone storage (all-files access), mounted USB/SD and granted folders. */
    fun autoRoots(s: Storage): List<String> = runCatching { DeviceLocations(s).roots() }.getOrDefault(emptyList())
        .filter { !it.requiresGrant && it.name!="Загрузки" && !it.ref.startsWith("ifiles-volume:") }.map { it.ref }
    fun forget(context: Context, root: String) { ContentIndex.get(context).forget(root) }
}

class IndexWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
    private var lastUpdate=0L
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val s=Storage(applicationContext); val index=ContentIndex.get(applicationContext)
        val roots=if(inputData.getBoolean("auto",false))Indexing.autoRoots(s) else inputData.getStringArray("roots")?.toList().orEmpty()
        // Background starts may be refused (Android 12+); the pass still runs within the normal worker window.
        runCatching { setForeground(info("Подготовка…",0)) }
        var indexed=0; var seen=0; var removed=0; var failed=0; var unavailable=0
        for((i,root) in roots.withIndex()) {
            if(isStopped)break
            val label=runCatching { s.stat(root).name }.getOrDefault("Папка")
            try {
                val stats=Indexer(s,index).run(root,check={ if(isStopped)throw kotlinx.coroutines.CancellationException("Индексация отменена") }) { st,name ->
                    val now=System.currentTimeMillis()
                    if(now-lastUpdate>500 || name.isEmpty()) {
                        lastUpdate=now
                        val text="$label: ${st.seen} файлов"+(if(name.isNotEmpty())" · $name" else "")
                        setProgressAsync(workDataOf("root" to label,"seen" to st.seen,"indexed" to st.indexed,"file" to name.take(150),"text" to text.take(200),"step" to i+1,"steps" to roots.size))
                        runCatching { setForegroundAsync(info(text,st.seen)) }
                    }
                }
                indexed+=stats.indexed; seen+=stats.seen; removed+=stats.removed; failed+=stats.failed
            } catch(e: IOException) { unavailable++ }
        }
        Result.success(workDataOf("seen" to seen,"indexed" to indexed,"removed" to removed,"failed" to failed,"unavailable" to unavailable))
    }
    private fun info(text: String, seen: Int): ForegroundInfo {
        val context=applicationContext
        val nm=context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("index","Индексация для поиска",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(context,1,Intent(context,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Cancelling the indexing queue never touches file operations (they use their own queue).
        val cancel=WorkManager.getInstance(context).createCancelPendingIntent(id)
        val n=NotificationCompat.Builder(context,"index").setSmallIcon(android.R.drawable.ic_menu_search).setContentTitle("Индексация для поиска")
            .setContentText(text).setProgress(0,0,true).setOnlyAlertOnce(true).setContentIntent(open).setOngoing(true).setSubText(if(seen>0)"$seen" else null)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"Отменить",cancel).build()
        return ForegroundInfo(0x1d3e,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
