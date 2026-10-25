package com.serhii.ifiles

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Checks for URIs handed to iFiles by other apps. */
object IncomingUris {
    /**
     * Only content:// URIs from another app's provider are accepted. A URI that points at one of
     * iFiles' own providers is refused: an outside app could otherwise make iFiles open (and later
     * re-share) private files it never had access to (confused deputy).
     */
    fun accepted(uri: Uri?, providerPackage: (String)->String?, ownPackage: String): Boolean {
        if(uri==null || uri.scheme!="content")return false
        val authority=uri.authority?.takeIf { it.isNotBlank() } ?: return false
        if(authority in setOf("$ownPackage.files","$ownPackage.documents"))return false
        val owner=providerPackage(authority) ?: return true // unknown provider: the open itself will fail safely
        return owner!=ownPackage
    }
    fun accepted(context: Context, uri: Uri?): Boolean = accepted(uri,{ a ->
        runCatching {
            val pm=context.packageManager
            if(android.os.Build.VERSION.SDK_INT>=33) pm.resolveContentProvider(a,PackageManager.ComponentInfoFlags.of(0))?.packageName
            else @Suppress("DEPRECATION") pm.resolveContentProvider(a,0)?.packageName
        }.getOrNull()
    },context.packageName)
}

/** MIME filtering for pick requests: "image/ *", "*\/ *", exact types, and EXTRA_MIME_TYPES lists. */
object MimeFilter {
    fun of(type: String?, extra: Array<String>?): List<String> {
        val list=extra?.filter { it.isNotBlank() }.orEmpty().ifEmpty { listOfNotNull(type?.takeIf { it.isNotBlank() }) }
        return list.map { it.lowercase().trim() }.ifEmpty { listOf("*/*") }
    }
    fun matches(mime: String, filters: List<String>): Boolean {
        val m=mime.lowercase()
        return filters.any { f ->
            f=="*/*" || f=="*" || f==m || (f.endsWith("/*") && m.substringBefore('/')==f.substringBefore('/'))
        }
    }
}

/**
 * ACTION_GET_CONTENT / ACTION_OPEN_DOCUMENT handler. Returns content:// URIs (FileProvider or the
 * already-granted SAF URI) with a read grant; multi-select when EXTRA_ALLOW_MULTIPLE is set.
 */
class PickerActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request=intent
        val multiple=request.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false)
        val filters=MimeFilter.of(request.type,request.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        setResult(Activity.RESULT_CANCELED)
        setContent {
            val dark=isSystemInDarkTheme()
            MaterialTheme(colorScheme=if(dark)darkColorScheme(primary=Color(0xFF0A84FF)) else lightColorScheme(primary=Color(0xFF007AFF))) {
                Picker(Storage(this),multiple,filters,onCancel={finish()},onPick={deliver(it)})
            }
        }
    }
    private fun deliver(refs: List<String>) {
        lifecycleScope.launch {
            val s=Storage(this@PickerActivity)
            val result=withContext(Dispatchers.IO) { runCatching { refs.map { s.externalUri(it) } } }
            result.onSuccess { uris ->
                if(uris.isEmpty()) return@onSuccess
                val data=Intent().setData(uris.first()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val clip=ClipData.newRawUri("",uris.first()); uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                data.clipData=clip
                setResult(Activity.RESULT_OK,data); finish()
            }.onFailure { Toast.makeText(this@PickerActivity,it.message ?: "Файл недоступен",Toast.LENGTH_LONG).show() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Picker(s: Storage, multiple: Boolean, filters: List<String>, onCancel: ()->Unit, onPick: (List<String>)->Unit) {
    var stack by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var entries by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var selected by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val current=stack.lastOrNull()
    LaunchedEffect(current?.ref) {
        loading=true; error=null
        val r=withContext(Dispatchers.IO) { runCatching {
            if(current==null) DeviceLocations(s).roots().filterNot { it.requiresGrant }
            else s.list(current.ref).filter { !PreviewFiles.hidden(it.name) && (it.directory || MimeFilter.matches(it.mime,filters)) }
                .sortedWith(compareByDescending<Entry> { it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        } }
        entries=r.getOrDefault(emptyList()); error=r.exceptionOrNull()?.message; loading=false
    }
    BackHandler { if(stack.isEmpty()) onCancel() else stack=stack.dropLast(1) }
    Scaffold(topBar={
        TopAppBar(title={Text(current?.name ?: if(multiple)"Выберите файлы" else "Выберите файл",maxLines=1,overflow=TextOverflow.Ellipsis)},
            navigationIcon={IconButton(onClick={ if(stack.isEmpty()) onCancel() else stack=stack.dropLast(1) }){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Назад")}},
            actions={ if(multiple)TextButton(enabled=selected.isNotEmpty(),onClick={onPick(selected)}){Text("Готово"+if(selected.isNotEmpty())" (${selected.size})" else "")} else TextButton(onClick=onCancel){Text("Отмена")} })
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it,Modifier.align(Alignment.Center).padding(24.dp)) }
            if(!loading && error==null && entries.isEmpty()) Text("Нет подходящих файлов",Modifier.align(Alignment.Center))
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries,key={it.ref}) { e ->
                    val chosen=e.ref in selected
                    Row(Modifier.fillMaxWidth().clickable {
                        when {
                            e.directory -> stack=stack+e
                            multiple -> selected=if(chosen) selected-e.ref else selected+e.ref
                            else -> onPick(listOf(e.ref))
                        }
                    }.background(if(chosen)MaterialTheme.colorScheme.primary.copy(alpha=.12f) else Color.Transparent).padding(horizontal=16.dp,vertical=10.dp).semantics{contentDescription="Выбор: "+e.name},verticalAlignment=Alignment.CenterVertically) {
                        if(multiple && !e.directory) Icon(if(chosen)Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,null,Modifier.padding(end=10.dp),tint=MaterialTheme.colorScheme.primary)
                        FileGlyph(e,s,Modifier.size(40.dp))
                        Column(Modifier.weight(1f).padding(start=12.dp)) {
                            Text(e.name,maxLines=1,overflow=TextOverflow.MiddleEllipsis)
                            if(!e.directory) Text(listOf(humanSize(e.size),dateText(e.modified)).filter { it.isNotBlank() }.joinToString(" – "),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/**
 * "Open with" target for common file types. Validates the URI, then shows it in the in-app viewer
 * (MediaActivity) inside the caller's task. Nothing is copied or added to recents.
 */
class ViewerActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri=intent.data
        if(intent.action!=Intent.ACTION_VIEW || !IncomingUris.accepted(this,uri)) {
            Toast.makeText(this,"Этот файл нельзя открыть",Toast.LENGTH_LONG).show(); finish(); return
        }
        uri!!
        val type=intent.type
        lifecycleScope.launch {
            val s=Storage(this@ViewerActivity)
            val entry=withContext(Dispatchers.IO) { runCatching { s.stat(uri.toString()) }.getOrNull() }
                ?: Entry(uri.toString(),uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "Файл",false,mime=type ?: "application/octet-stream")
            val e=if(type!=null && entry.mime=="application/octet-stream") entry.copy(mime=type) else entry
            val open=MediaActivity.intent(this@ViewerActivity,e).putExtra(MediaActivity.EXTERNAL,true)
                .setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            open.clipData=ClipData.newRawUri(e.name,uri)
            runCatching { startActivity(open) }.onFailure { Toast.makeText(this@ViewerActivity,it.message,Toast.LENGTH_LONG).show() }
            finish()
        }
    }
}
