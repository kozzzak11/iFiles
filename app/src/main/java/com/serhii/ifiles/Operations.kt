package com.serhii.ifiles

import android.content.Context
import android.content.Intent
import androidx.work.*
import kotlinx.coroutines.*
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
        val tracker=TransferTracker()
        var notifier: TransferNotifier?=null
        val alive=java.util.concurrent.atomic.AtomicBoolean(true)
        try {
            val r=s.read(task);val kind=r.getString("kind")
            tracker.setKind(kind)
            notifier=TransferNotifier(applicationContext,id)
            val notes=notifier
            val gate=NotifyGate()
            setForeground(notes.foreground(tracker.snapshot()))
            s.atomic(started,JSONObject().put("state","started"))
            setProgressAsync(tracker.snapshot().toData())
            // Own thread: not Dispatchers.IO, and it never waits on setForeground/fsync.
            val ticker=Thread({
                while(alive.get() && !Thread.currentThread().isInterrupted) {
                    try {
                        val snap=tracker.snapshot()
                        val key=snap.phase+"|"+snap.percent+"|"+snap.bar()+"|"+snap.title()+"|"+snap.line()+"|"+snap.sub()
                        if(alive.get() && gate.allow(key)) {
                            notes.update(snap)
                            setProgressAsync(snap.toData())
                        }
                    } catch(_: Throwable) {}
                    try { Thread.sleep(250) } catch(_: InterruptedException) { break }
                }
            }, "ifiles-transfer-ui")
            ticker.isDaemon=true
            ticker.start()
            val engine=FileEngine(s,stop,
                onBytes={ tracker.addBytes(it) },
                onFile={ tracker.startFile(it) },
                onPhase={ tracker.phase(it) },
                onMeasured={ bytes,files -> tracker.setTotal(bytes,files) },
                onVerifyBytes={ tracker.addVerified(it) },
                onVerifyFile={ name,size -> tracker.startVerify(name, size) },
                progress={ tracker.touchName(it) }
            )
            val array=r.getJSONArray("refs");val refs=(0 until array.length()).map { array.getString(it) }
            val parent=r.getString("parent")
            try {
                    if(kind in setOf("copy","move","trash","zip")) {
                        tracker.phase("prepare")
                        val measured=engine.tally(refs)
                        tracker.setTotal(measured.first, measured.second)
                        tracker.phase("write")
                    }
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
            } finally {
                alive.set(false)
                ticker.interrupt()
                runCatching { ticker.join(1000) }
            }
            Operations.clean(applicationContext,id)
            val done=tracker.snapshot()
            val detail=if(done.bytesDone>0) "${TransferSnap.kindTitle(kind)} · ${humanSize(done.bytesDone)}" else "Операция завершена"
            notes.finish("done", detail)
            Result.success(workDataOf("message" to "Готово"))
        } catch(e: Exception) {
            alive.set(false)
            if(e is CancellationException) {
                runCatching { notifier?.finish("failed", "Операция прервалась") }
                throw e
            }
            val cancelled=flag.exists()
            Operations.clean(applicationContext,id)
            val message=if(cancelled) (Operations.CANCELLED+". "+(e.message ?: "")).take(1000) else (e.message ?: "Операция не выполнена").take(1000)
            runCatching { notifier?.finish(if(cancelled) "cancelled" else "failed", message) }
            if(cancelled) Result.success(workDataOf("error" to message,"cancelled" to true))
            else Result.success(workDataOf("error" to message))
        }
    }
}
