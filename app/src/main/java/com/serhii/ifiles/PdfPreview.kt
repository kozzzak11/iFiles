package com.serhii.ifiles

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.File

/** One renderer per open document. Only visible pages have bitmaps. */
private class PdfSession(file:File):AutoCloseable {
    private val renderer=PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY))
    val count=renderer.pageCount
    @Synchronized fun render(index:Int):Bitmap {
        renderer.openPage(index).use { page->
            val ratio=minOf(1440f/page.width,4096f/page.height)
            val bitmap=Bitmap.createBitmap(maxOf(1,(page.width*ratio).toInt()),maxOf(1,(page.height*ratio).toInt()),Bitmap.Config.ARGB_8888)
            try {bitmap.eraseColor(android.graphics.Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);return bitmap}
            catch(e:Exception){bitmap.recycle();throw e}
        }
    }
    @Synchronized override fun close(){renderer.close()}
}

@Composable fun PdfPreview(e:Entry,s:Storage) {
    var session by remember(e.ref){mutableStateOf<PdfSession?>(null)}
    var error by remember(e.ref){mutableStateOf<String?>(null)}
    var zoom by rememberSaveable(e.ref){mutableFloatStateOf(1f)}
    val list=rememberLazyListState()
    val horizontal=rememberScrollState()
    val scope=rememberCoroutineScope()
    LaunchedEffect(e.ref) {
        val file=File.createTempFile("pdf-",".pdf",s.context.cacheDir)
        var opened:PdfSession?=null
        try {
            withContext(Dispatchers.IO){
                PreviewFiles.copy(s.input(e.ref),file,e.size){ensureActive()}
                opened=PdfSession(file)
                require(opened!!.count>0){"В PDF нет страниц"}
            }
            session=opened
            awaitCancellation()
        }catch(ex:CancellationException){throw ex}catch(ex:Exception){error=ex.message ?: "Не удалось прочитать PDF"}
        finally {session=null;withContext(NonCancellable+Dispatchers.IO){opened?.close();file.delete()}}
    }
    val pdf=session
    if(error!=null){Text(error!!,Modifier.padding(24.dp));return}
    if(pdf==null){CircularProgressIndicator();return}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceEvenly) {
            TextButton(onClick={zoom=1f;scope.launch{horizontal.scrollTo(0)}}){Text("По ширине")}
            TextButton(onClick={zoom=(zoom/1.25f).coerceAtLeast(1f)}){Text("−")}
            Text("${(zoom*100).toInt()}%")
            TextButton(onClick={zoom=(zoom*1.25f).coerceAtMost(4f)}){Text("+")}
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().pointerInput(e.ref){
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed=false)
                do {
                    val event=awaitPointerEvent()
                    if(event.changes.count{it.pressed}>1){zoom=(zoom*event.calculateZoom()).coerceIn(1f,4f);event.changes.forEach{it.consume()}}
                }while(event.changes.any{it.pressed})
            }
        }) {
            val pageWidth=maxWidth*zoom
            Row(Modifier.fillMaxSize().horizontalScroll(horizontal)) {
                LazyColumn(state=list,modifier=Modifier.width(pageWidth).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    items(pdf.count,key={it}) { index->PdfPage(pdf,index,pageWidth.value) }
                }
            }
        }
        Text("${list.firstVisibleItemIndex+1} / ${pdf.count}",Modifier.align(Alignment.CenterHorizontally).padding(8.dp))
    }
}

@Composable private fun PdfPage(pdf:PdfSession,index:Int,widthDp:Float) {
    var bitmap by remember(pdf,index){mutableStateOf<Bitmap?>(null)}
    var error by remember(pdf,index){mutableStateOf<String?>(null)}
    LaunchedEffect(pdf,index){try{bitmap=withContext(Dispatchers.IO){pdf.render(index)}}catch(ex:CancellationException){throw ex}catch(ex:Exception){error=ex.message ?: "Не удалось прочитать страницу"}}
    val image=bitmap
    if(image!=null)Image(image.asImageBitmap(),"Страница PDF ${index+1}",Modifier.fillMaxWidth().aspectRatio(image.width.toFloat()/image.height))
    else Box(Modifier.fillMaxWidth().height((widthDp*1.414f).dp),contentAlignment=Alignment.Center){if(error!=null)Text(error!!) else CircularProgressIndicator()}
}
