package com.serhii.ifiles

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import java.io.File

object Thumbnails {
    private val cache=object: LruCache<String,Bitmap>(24*1024*1024) { override fun sizeOf(key: String,value: Bitmap)=value.allocationByteCount }
    fun load(s: Storage,e: Entry,size: Int=240): Bitmap? {
        val key="${e.ref}:${e.modified}:${e.size}:$size"
        synchronized(cache){cache.get(key)}?.let{return it}
        val b=runCatching {
            val provided=if(!s.isFile(e.ref) && size<=512)runCatching{s.resolver.loadThumbnail(s.uri(e.ref),Size(size,size),null)}.getOrNull() else null
            if(provided!=null)provided else if(e.mime.startsWith("image/")) {
                val source=if(s.isFile(e.ref))ImageDecoder.createSource(s.file(e.ref)) else ImageDecoder.createSource(s.resolver,s.uri(e.ref))
                ImageDecoder.decodeBitmap(source) { decoder,info,_ ->
                    decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                    val ratio=minOf(1f,size.toFloat()/maxOf(info.size.width,info.size.height))
                    decoder.setTargetSize(maxOf(1,(info.size.width*ratio).toInt()),maxOf(1,(info.size.height*ratio).toInt()))
                }
            } else if(e.mime.startsWith("video/")) {
                val retriever=MediaMetadataRetriever()
                try { if(s.isFile(e.ref))retriever.setDataSource(s.file(e.ref).absolutePath) else retriever.setDataSource(s.context,s.uri(e.ref)); retriever.getScaledFrameAtTime(-1,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,size,size) } finally {retriever.release()}
            } else null
        }.getOrNull()
        if(b!=null)synchronized(cache){cache.put(key,b)}
        return b
    }
}
@Composable fun FileGlyph(e: Entry,s: Storage,modifier: Modifier=Modifier) {
    if(e.directory) {
        androidx.compose.foundation.Canvas(modifier) {
            val w=size.width;val h=size.height
            val back=androidx.compose.ui.graphics.Path().apply {
                moveTo(w*.08f,h*.28f);cubicTo(w*.08f,h*.22f,w*.1f,h*.2f,w*.16f,h*.2f)
                lineTo(w*.41f,h*.2f);lineTo(w*.5f,h*.3f);lineTo(w*.84f,h*.3f)
                cubicTo(w*.9f,h*.3f,w*.92f,h*.33f,w*.92f,h*.4f)
                lineTo(w*.92f,h*.76f);lineTo(w*.08f,h*.76f);close()
            }
            drawPath(back,androidx.compose.ui.graphics.Color(0xFF7AC8FF))
            drawRoundRect(androidx.compose.ui.graphics.Color(0xFF269DF5),androidx.compose.ui.geometry.Offset(w*.08f,h*.37f),androidx.compose.ui.geometry.Size(w*.84f,h*.45f),androidx.compose.ui.geometry.CornerRadius(w*.055f))
        }
        return
    }
    var bitmap by remember(e.ref,e.modified){mutableStateOf<Bitmap?>(null)}
    LaunchedEffect(e.ref,e.modified) { if(e.mime.startsWith("image/") || e.mime.startsWith("video/")) bitmap=withContext(Dispatchers.IO){Thumbnails.load(s,e)} }
    if(bitmap!=null)Image(bitmap!!.asImageBitmap(),null,modifier)
    else Icon(when {
        e.directory -> Icons.Outlined.Folder
        e.mime.startsWith("image/") -> Icons.Outlined.Image
        e.mime.startsWith("video/") -> Icons.Outlined.Movie
        e.mime.startsWith("audio/") -> Icons.Outlined.MusicNote
        e.mime=="application/pdf" -> Icons.Outlined.PictureAsPdf
        e.name.endsWith(".zip",true) -> Icons.Outlined.FolderZip
        else -> Icons.Outlined.Description
    },null,modifier,tint=if(e.directory)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
}
@Composable fun Preview(e: Entry,s: Storage,onClose:()->Unit,onExternal:(Entry)->Unit,onShare:(Entry)->Unit) {
    var resolved by remember(e.ref){mutableStateOf<Entry?>(null)}
    var kind by remember(e.ref){mutableStateOf("")}
    var issue by remember(e.ref){mutableStateOf<String?>(null)}
    LaunchedEffect(e.ref) {
        try {
            val result=withContext(Dispatchers.IO) {
                val actual=PreviewFiles.resolve(s,e)
                actual to PreviewFiles.kind(actual,s.input(actual.ref).use { PreviewFiles.read(it,512) })
            }
            resolved=result.first;kind=result.second;s.recent(result.first.ref)
        } catch(ex: CancellationException) {throw ex} catch(ex: Exception) {issue=ex.message ?: "Файл недоступен. Проверьте подключение USB."}
    }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick=onClose){Text("Готово")}
                    Text(resolved?.name ?: e.name,Modifier.weight(1f),maxLines=1)
                    IconButton(onClick={onShare(resolved ?: e)}){Icon(Icons.Outlined.IosShare,"Поделиться")}
                    IconButton(onClick={onExternal(resolved ?: e)}){Icon(Icons.Outlined.OpenInNew,"Открыть в приложении")}
                }
                Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
                    val actual=resolved
                    when {
                        issue!=null -> Text(issue!!,Modifier.padding(24.dp))
                        actual==null -> CircularProgressIndicator()
                        kind=="appledouble" -> Text("Служебный файл macOS. Он не содержит сам документ.",Modifier.padding(24.dp))
                        kind=="image" -> ImagePreview(actual.copy(mime="image/preview"),s)
                        kind=="media" -> MediaPreview(actual,s)
                        kind=="pdf" -> PdfPreview(actual,s)
                        kind=="text" -> TextPreview(actual,s)
                        else -> DocumentPreview(actual,s,kind)
                    }
                }
            }
        }
    }
}
@Composable private fun ZoomImage(bitmap: Bitmap) {
    var scale by remember(bitmap){mutableFloatStateOf(1f)}; var x by remember(bitmap){mutableFloatStateOf(0f)};var y by remember(bitmap){mutableFloatStateOf(0f)}
    Box(Modifier.fillMaxSize().pointerInput(bitmap){detectTransformGestures { _,pan,zoom,_->scale=(scale*zoom).coerceIn(1f,5f);x=if(scale==1f)0f else (x+pan.x).coerceIn(-size.width*scale,size.width*scale);y=if(scale==1f)0f else(y+pan.y).coerceIn(-size.height*scale,size.height*scale) }},contentAlignment=Alignment.Center) {
        Image(bitmap.asImageBitmap(),"Просмотр",Modifier.fillMaxSize().graphicsLayer(scaleX=scale,scaleY=scale,translationX=x,translationY=y))
    }
}
@Composable private fun ImagePreview(e: Entry,s: Storage) {
    var b by remember(e.ref){mutableStateOf<Bitmap?>(null)};var error by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(e.ref){ b=withContext(Dispatchers.IO){Thumbnails.load(s,e,2048)};if(b==null)error="Не удалось декодировать изображение" }
    if(b!=null)ZoomImage(b!!) else if(error!=null)Text(error!!) else CircularProgressIndicator()
}
@Composable private fun TextPreview(e: Entry,s: Storage) {
    var text by remember(e.ref){mutableStateOf<String?>(null)}; var error by remember(e.ref){mutableStateOf<String?>(null)}
    LaunchedEffect(e.ref) {
        try { text=withContext(Dispatchers.IO){s.input(e.ref).use { PreviewFiles.text(PreviewFiles.read(it,PreviewFiles.TEXT_LIMIT+1)) }} }
        catch(ex: CancellationException){throw ex} catch(ex: Exception){error=ex.message ?: "Не удалось прочитать файл"}
    }
    PreviewText(text,error)
}
@Composable private fun PreviewText(text: String?,error: String?) {
    if(error!=null)Text(error,Modifier.padding(24.dp)) else if(text==null)CircularProgressIndicator() else SelectionContainer { Text(text,Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) }
}
@Composable private fun DocumentPreview(e: Entry,s: Storage,kind: String) {
    var text by remember(e.ref){mutableStateOf<String?>(null)};var error by remember(e.ref){mutableStateOf<String?>(null)}
    LaunchedEffect(e.ref) {
        try {text=withContext(Dispatchers.IO) {
            if(kind=="binary")s.input(e.ref).use { "${e.name}\n${e.size} байт\n\nДля этого формата доступен просмотр данных (первые 4 КБ):\n\n"+PreviewFiles.hex(PreviewFiles.read(it,4096)) }
            else {
                val f=File.createTempFile("document-",".zip",s.context.cacheDir)
                try {
                    PreviewFiles.copy(s.input(e.ref),f,e.size){ensureActive()}
                    (if(kind=="document")"Текст документа · без исходного оформления\n" else "Содержимое архива\n")+PreviewFiles.packageText(f,kind=="archive"){ensureActive()}
                } finally {f.delete()}
            }
        }} catch(ex: CancellationException){throw ex} catch(ex: Exception){error=ex.message ?: "Не удалось прочитать документ"}
    }
    PreviewText(text,error)
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun MediaPreview(e: Entry,s: Storage) {
    val context=LocalContext.current;var error by remember(e.ref){mutableStateOf<String?>(null)}
    val player=remember(e.ref){ExoPlayer.Builder(context).build()}
    val owner=LocalLifecycleOwner.current
    DisposableEffect(player,owner) {
        val listener=object: Player.Listener {override fun onPlayerError(e: PlaybackException){error=e.localizedMessage ?: "Ошибка воспроизведения"}}
        val observer=LifecycleEventObserver { _,event ->if(event==Lifecycle.Event.ON_STOP)player.pause() }
        player.addListener(listener);owner.lifecycle.addObserver(observer)
        onDispose {owner.lifecycle.removeObserver(observer);player.release()}
    }
    LaunchedEffect(player) {
        var copy:File?=null
        try {
            withContext(Dispatchers.IO){copy=PreviewFiles.mediaCopy(s,e){ensureActive()}}
            player.setMediaItem(MediaItem.fromUri(copy?.let { android.net.Uri.fromFile(it) } ?: s.uri(e.ref)))
            player.prepare();player.playWhenReady=true
            awaitCancellation()
        } catch(ex: CancellationException){throw ex} catch(ex: Exception){error=ex.message ?: "Не удалось открыть медиафайл"}
        finally {withContext(NonCancellable+Dispatchers.IO){copy?.delete()}}
    }
    if(error!=null)Text(error!!) else AndroidView(factory={PlayerView(it).apply { this.player=player;useController=true }},modifier=Modifier.fillMaxSize())
}
@Composable private fun PdfPreview(e: Entry,s: Storage) {
    var page by remember(e.ref){mutableIntStateOf(0)};var count by remember(e.ref){mutableIntStateOf(0)}
    var bitmap by remember(e.ref){mutableStateOf<Bitmap?>(null)};var error by remember(e.ref){mutableStateOf<String?>(null)}
    var local by remember(e.ref){mutableStateOf<File?>(null)}
    // Each session owns its file. NonCancellable finally waits for I/O before cleanup.
    LaunchedEffect(e.ref) {
        val f=File.createTempFile("pdf-",".pdf",s.context.cacheDir)
        try {
            withContext(Dispatchers.IO){PreviewFiles.copy(s.input(e.ref),f,e.size){ensureActive()}}
            local=f
            awaitCancellation()
        } catch(ex: CancellationException){throw ex} catch(ex: Exception){error=ex.message ?: "PDF недоступен. Проверьте USB."}
        finally {local=null;withContext(NonCancellable+Dispatchers.IO){f.delete()}}
    }
    LaunchedEffect(local,page) {
        val f=local ?: return@LaunchedEffect;bitmap=null;error=null
        try {
            val rendered=withContext(Dispatchers.IO) {
                ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { pdf ->
                    require(pdf.pageCount>0){"В PDF нет страниц"}
                    pdf.openPage(page.coerceIn(0,pdf.pageCount-1)).use { p ->
                        val ratio=minOf(1440f/p.width,4096f/p.height)
                        val b=Bitmap.createBitmap(maxOf(1,(p.width*ratio).toInt()),maxOf(1,(p.height*ratio).toInt()),Bitmap.Config.ARGB_8888)
                        try { b.eraseColor(android.graphics.Color.WHITE);p.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);pdf.pageCount to b }
                        catch(ex: Exception){b.recycle();throw ex}
                    }
                }}
            }
            count=rendered.first;bitmap=rendered.second
        } catch(ex: CancellationException){throw ex} catch(ex: Exception){error=ex.message ?: "PDF повреждён или защищён паролем"}
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f),contentAlignment=Alignment.Center) {if(error!=null)Text(error!!,Modifier.padding(24.dp)) else if(bitmap==null)CircularProgressIndicator() else ZoomImage(bitmap!!)}
        if(count>0)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={page--},enabled=page>0){Text("Назад")};Text("${page+1} / $count");TextButton(onClick={page++},enabled=page+1<count){Text("Далее")}
        }
    }
}
