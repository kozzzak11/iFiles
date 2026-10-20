package com.serhii.ifiles

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
        val key="${e.ref}:${e.modified}:$size"
        synchronized(cache){cache.get(key)}?.let{return it}
        val b=runCatching {
            val provided=if(!s.isFile(e.ref) && size<=512)runCatching{s.resolver.loadThumbnail(s.uri(e.ref),Size(size,size),null)}.getOrNull() else null
            if(provided!=null)provided else if(e.mime.startsWith("image/")) {
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                s.input(e.ref).use { BitmapFactory.decodeStream(it,null,bounds) }
                val options=BitmapFactory.Options().apply {
                    inSampleSize=1
                    while(bounds.outWidth/inSampleSize>size*2 || bounds.outHeight/inSampleSize>size*2)inSampleSize*=2
                }
                s.input(e.ref).use { BitmapFactory.decodeStream(it,null,options) }
            } else if(e.mime.startsWith("video/")) {
                val retriever=MediaMetadataRetriever()
                try { retriever.setDataSource(s.context,s.shareUri(e.ref)); retriever.getScaledFrameAtTime(-1,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,size,size) } finally {retriever.release()}
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
@Composable fun Preview(e: Entry,s: Storage,onClose:()->Unit,onExternal:()->Unit,onShare:()->Unit) {
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick=onClose){Text("Готово")}
                    Text(e.name,Modifier.weight(1f),maxLines=1)
                    IconButton(onClick=onShare){Icon(Icons.Outlined.IosShare,"Поделиться")}
                    IconButton(onClick=onExternal){Icon(Icons.Outlined.OpenInNew,"Открыть в приложении")}
                }
                Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
                    when {
                        e.mime.startsWith("image/") -> ImagePreview(e,s)
                        e.mime.startsWith("audio/") || e.mime.startsWith("video/") -> MediaPreview(e,s)
                        e.mime=="application/pdf" || e.name.endsWith(".pdf",true) -> PdfPreview(e,s)
                        e.mime.startsWith("text/") || e.name.substringAfterLast('.').lowercase() in listOf("log","md","json","xml","kt","csv","yaml","yml","ini","conf") -> TextPreview(e,s)
                        else -> Column(horizontalAlignment=Alignment.CenterHorizontally) {
                            FileGlyph(e,s,Modifier.size(100.dp));Text("Для этого формата нужен внешний просмотрщик",Modifier.padding(24.dp))
                            Button(onClick=onExternal){Text("Открыть в приложении")}
                        }
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
    var text by remember{mutableStateOf<String?>(null)}; var error by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(e.ref) {try { text=withContext(Dispatchers.IO){s.input(e.ref).use { input ->
        val limit=1024*1024+1;val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(out.size()<limit){val n=input.read(buffer,0,minOf(buffer.size,limit-out.size()));if(n<0)break;out.write(buffer,0,n)}
        val bytes=out.toByteArray(); val truncated=bytes.size>1024*1024
        String(if(truncated)bytes.copyOf(1024*1024) else bytes,Charsets.UTF_8)+(if(truncated)"\n\n…Быстрый просмотр: первый 1 МБ. Полный файл — через «Открыть в приложении»." else "")
    }} }catch(e:Exception){error=e.message} }
    if(error!=null)Text(error!!) else if(text==null)CircularProgressIndicator() else SelectionContainer { Text(text!!,Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun MediaPreview(e: Entry,s: Storage) {
    val context=LocalContext.current;var error by remember{mutableStateOf<String?>(null)}
    val player=remember(e.ref){ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(s.shareUri(e.ref)));prepare();playWhenReady=true }}
    val owner=LocalLifecycleOwner.current
    DisposableEffect(player,owner) {
        val listener=object: Player.Listener {override fun onPlayerError(e: PlaybackException){error=e.localizedMessage ?: "Ошибка воспроизведения"}}
        val observer=LifecycleEventObserver { _,event ->if(event==Lifecycle.Event.ON_STOP)player.pause() }
        player.addListener(listener);owner.lifecycle.addObserver(observer)
        onDispose {owner.lifecycle.removeObserver(observer);player.release()}
    }
    if(error!=null)Text(error!!) else AndroidView(factory={PlayerView(it).apply { this.player=player;useController=true }},modifier=Modifier.fillMaxSize())
}
@Composable private fun PdfPreview(e: Entry,s: Storage) {
    var page by remember{mutableIntStateOf(0)};var count by remember{mutableIntStateOf(0)}
    var bitmap by remember{mutableStateOf<Bitmap?>(null)};var error by remember{mutableStateOf<String?>(null)}
    // One renderer per request: always closed on the I/O thread; no native object races on disposal.
    var local by remember{mutableStateOf<File?>(null)}
    DisposableEffect(e.ref) {onDispose {local?.delete()} }
    LaunchedEffect(e.ref) {
        val f=File.createTempFile("pdf-",".pdf",s.context.cacheDir)
        try {withContext(Dispatchers.IO){s.input(e.ref).use { input -> f.outputStream().use { out ->
            val b=ByteArray(256*1024);while(true){ensureActive();val n=input.read(b);if(n<0)break;out.write(b,0,n)}
        } }};local=f }catch(ex:Exception){f.delete();if(ex is CancellationException)throw ex;error=ex.message ?: "PDF недоступен"}
    }
    LaunchedEffect(local,page) {
        val f=local ?: return@LaunchedEffect;bitmap=null
        try { bitmap=withContext(Dispatchers.IO) {
            ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { pdf ->
                count=pdf.pageCount
                pdf.openPage(page.coerceAtMost(count-1)).use { p ->
                    val width=1440;val height=(p.height.toFloat()/p.width*width).toInt().coerceIn(1,10000)
                    Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also { b ->b.eraseColor(android.graphics.Color.WHITE);p.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                }
            }}
        }}catch(ex:Exception){if(ex is CancellationException)throw ex;error=ex.message ?: "PDF повреждён или защищён паролем"}
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f),contentAlignment=Alignment.Center) {if(error!=null)Text(error!!) else if(bitmap==null)CircularProgressIndicator() else ZoomImage(bitmap!!)}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={page--},enabled=page>0){Text("Назад")};Text("${page+1} / $count");TextButton(onClick={page++},enabled=page+1<count){Text("Далее")}
        }
    }
}
