# WorkManager persists the worker class name across app/process restarts.
-keep class com.serhii.ifiles.OperationWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }
-keep class com.serhii.ifiles.IndexWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }
# PdfBox-Android: optional JPEG2000 decoder is not bundled (text extraction only).
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**

# HWPF text runs do not use POI desktop image rendering/diagnostic JSON output.
# The Android DOC instrumentation fixture exercises the supported parsing path.
-dontwarn java.awt.**
# Optional OSGi discovery and compile-time annotations in Log4j API.
-dontwarn org.osgi.framework.**
-dontwarn aQute.bnd.annotation.**
-dontwarn edu.umd.cs.findbugs.annotations.**
