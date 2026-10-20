# WorkManager persists the worker class name across app/process restarts.
-keep class com.serhii.ifiles.OperationWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }
