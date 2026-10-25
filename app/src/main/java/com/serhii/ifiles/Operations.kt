package com.serhii.ifiles

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object Operations {
    const val QUEUE="ifiles-operations"
    fun enqueue(context: Context, kind: String, refs: List<String> = emptyList(), parent: String = "", name: String = "", sourceParent: String = ""): UUID {
        val id=UUID.randomUUID(); val s=Storage(context)
        sweep(context)
        val request=JSONObject().put("kind",kind).put("refs",JSONArray(refs)).put("parent",parent).put("name",name).put("sourceParent",sourceParent)
        s.atomic(File(context.filesDir,"tasks/$id.json"),request)
        val work=OneTimeWorkRequestBuilder<OperationWorker>().setId(id).setInputData(workDataOf("task" to id.toString())).build()
        WorkManager.getInstance(context).enqueueUniqueWork(QUEUE,ExistingWorkPolicy.APPEND_OR_REPLACE,work)
        return id
    }
    /** Task files of finished jobs are removed by the worker; this drops leftovers of killed/cancelled jobs. */
    fun sweep(context: Context, now: Long = System.currentTimeMillis()) {
        val week=7L*24*60*60*1000
        File(context.filesDir,"tasks").listFiles().orEmpty().filter { now-it.lastModified()>week }.forEach { it.delete() }
    }
    fun clean(context: Context, id: UUID) {
        listOf("json","started","result","cancel").forEach { File(context.filesDir,"tasks/$id.$it").delete() }
    }
    /**
     * Cancels one operation without WorkManager.cancelWorkById: cancelling a job inside an APPEND chain cancels
     * every job queued behind it. Instead the job is flagged; it stops at its next checkpoint (or is skipped if it
     * has not started yet) and finishes "successfully" with a cancelled result, so the rest of the queue runs.
     */
    fun cancel(context: Context, id: UUID) {
        val flag=cancelFlag(context,id); flag.parentFile?.mkdirs(); flag.writeText("cancel")
    }
    fun cancelFlag(context: Context, id: UUID)=File(context.filesDir,"tasks/$id.cancel")
    const val CANCELLED="Операция отменена"
}
/** Receives the notification's "Отменить" action (not exported: only our own PendingIntent can reach it). */
class CancelOperationReceiver: android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id=runCatching { UUID.fromString(intent.getStringExtra("id")) }.getOrNull() ?: return
        Operations.cancel(context,id)
    }
}
class OperationWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val s=Storage(applicationContext);val task=File(applicationContext.filesDir,"tasks/${id}.json")
        // WorkManager may restart after process death. Do not replay destructive jobs automatically.
        val started=File(applicationContext.filesDir,"tasks/${id}.started")
        // Never return failure: in an APPEND chain one failed job would fail every queued job after it.
        // Errors are reported through output data instead and the queue keeps going.
        if(started.exists()) { Operations.clean(applicationContext,id); return@withContext Result.success(workDataOf("error" to "Операция прервалась. Проверьте исходник, папку назначения и корзину перед повтором.")) }
        val flag=Operations.cancelFlag(applicationContext,id)
        if(flag.exists()) { Operations.clean(applicationContext,id); return@withContext Result.success(workDataOf("error" to Operations.CANCELLED,"cancelled" to true)) }
        var checked=0L; var flagged=false
        // Checked at every engine checkpoint (per 256 KB buffer); the file check is throttled.
        val stop={ if(!flagged) { val now=System.currentTimeMillis(); if(now-checked>250) { checked=now; flagged=flag.exists() } }; isStopped || flagged }
        try {
            val r=s.read(task);val kind=r.getString("kind")
            setForeground(notification("Подготовка…"))
            s.atomic(started,JSONObject().put("state","started"))
            var last=0L
            val engine=FileEngine(s,stop) { name ->
                val now=System.currentTimeMillis()
                if(now-last>400) { last=now;setProgressAsync(workDataOf("file" to name.take(150))) }
            }
            val array=r.getJSONArray("refs");val refs=(0 until array.length()).map { array.getString(it) }
            val parent=r.getString("parent")
            when(kind) {
                "copy" -> refs.forEach { engine.copy(it,parent) }
                "move" -> refs.forEach { engine.move(it,parent) }
                "trash" -> refs.forEach { engine.trash(it,r.getString("sourceParent")) }
                "mkdir" -> s.create(parent,r.getString("name"),true)
                "rename" -> { val n=s.rename(refs.single(),r.getString("name"),parent);s.rewriteRef(refs.single(),n) }
                "zip" -> Archives(engine).zip(refs,parent,r.getString("name"))
                "unzip" -> refs.forEach { Archives(engine).unzip(it,parent) }
                "restore" -> refs.forEach { engine.restore(it,parent.ifBlank { null }) }
                "purge" -> engine.purgeMany(refs)
                else -> error("Неизвестная операция")
            }
            Operations.clean(applicationContext,id)
            Result.success(workDataOf("message" to "Готово"))
        } catch(e: Exception) {
            if(e is kotlinx.coroutines.CancellationException)throw e
            val cancelled=flag.exists()
            Operations.clean(applicationContext,id)
            if(cancelled)Result.success(workDataOf("error" to (Operations.CANCELLED+". "+(e.message ?: "")).take(1000),"cancelled" to true))
            else Result.success(workDataOf("error" to (e.message ?: "Операция не выполнена").take(1000)))
        }
    }
    private fun notification(text: String): ForegroundInfo {
        val context=applicationContext
        val nm=context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("operations","Файловые операции",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel=PendingIntent.getBroadcast(context,id.hashCode(),Intent(context,CancelOperationReceiver::class.java).putExtra("id",id.toString()),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n=NotificationCompat.Builder(context,"operations").setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("iFiles").setContentText(text).setContentIntent(open).setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"Отменить",cancel).build()
        return ForegroundInfo(id.hashCode(),n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
