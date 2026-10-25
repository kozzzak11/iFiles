package com.serhii.ifiles

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionError
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaActivity:ComponentActivity() {
    companion object {
        fun intent(context:android.content.Context,e:Entry)=Intent(context,MediaActivity::class.java)
            .putExtra("ref",e.ref).putExtra("name",e.name).putExtra("size",e.size)
            .putExtra("mime",e.mime).putExtra("video",PlaybackRules.video(e))
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    }
    private var controller by mutableStateOf<MediaController?>(null)
    private var future:ListenableFuture<MediaController>?=null
    private var pip by mutableStateOf(false)
    private var imageZoomed by mutableStateOf(false)
    private var pipExitPending=false
    private var fullscreen by mutableStateOf(false)
    private var resizeMode by mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    private var playbackSpeed by mutableFloatStateOf(1f)
    private var video by mutableStateOf(false)
    private var title by mutableStateOf("Медиаплеер")
    private var error by mutableStateOf<String?>(null)
    private var loading by mutableStateOf(true)
    private var queue by mutableStateOf<List<Entry>>(emptyList())
    private var current by mutableStateOf<Entry?>(null)
    private var currentKind by mutableStateOf("")
    private var queueName:String?=null
    private var initialPage by mutableIntStateOf(0)
    private var queueVersion by mutableIntStateOf(0)
    private var pageIndex=0
    private var resolveJob:Job?=null
    private var canPip=false
    private var pipParams=PictureInPictureParams.Builder().build()
    private val listener=object:Player.Listener {
        override fun onEvents(player:Player,events:Player.Events) {sync()}
        override fun onPlayerError(e:PlaybackException){error="Не удалось воспроизвести файл. Проверьте доступ к USB или формат: ${e.errorCodeName}"}
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        setContent {
            val dark=when(Storage(this).prefs.getString("theme","Системная")){"Тёмная"->true;"Светлая"->false;else->isSystemInDarkTheme()}
            MaterialTheme(colorScheme=if(dark)darkColorScheme() else lightColorScheme(primary=Color(0xFF007AFF))) {
                Surface(Modifier.fillMaxSize()) {
                    BackHandler(fullscreen && !pip) { fullscreen=false }
                    SideEffect {
                        val bars=WindowCompat.getInsetsController(window,window.decorView)
                        if(fullscreen && !pip) {
                            bars.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            bars.hide(WindowInsetsCompat.Type.systemBars())
                        } else bars.show(WindowInsetsCompat.Type.systemBars())
                    }
                    key(queueVersion) {
                        val pager=rememberPagerState(initialPage=initialPage.coerceIn(0,maxOf(0,queue.size-1)),pageCount={maxOf(1,queue.size)})
                        val scope=rememberCoroutineScope()
                        LaunchedEffect(pager.settledPage,queue) {if(queue.isNotEmpty()){pageIndex=pager.settledPage;select(queue[pageIndex])}}
                        Column(if(pip || fullscreen)Modifier.fillMaxSize() else Modifier.fillMaxSize().safeDrawingPadding()) {
                            if(!pip && !fullscreen)Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                                TextButton(onClick={finish()}){Text("Готово")}
                                Text(current?.name ?: title,Modifier.weight(1f),maxLines=1)
                                if(video && currentKind=="media")IconButton(onClick={enterPip()}){Icon(Icons.Outlined.PictureInPictureAlt,"Картинка в картинке")}
                                if(video && currentKind=="media") {
                                    VideoSettingsMenu(resizeMode,playbackSpeed,{resizeMode=it},{playbackSpeed=it;controller?.setPlaybackParameters(PlaybackParameters(it,1f))})
                                    IconButton(onClick={fullscreen=true}){Icon(Icons.Outlined.Fullscreen,"На весь экран")}
                                }
                                IconButton(onClick={share()}){Icon(Icons.Outlined.IosShare,"Поделиться")}
                                Box {
                                    var menu by remember{mutableStateOf(false)}
                                    IconButton(onClick={menu=true}){Icon(Icons.Outlined.MoreVert,"Ещё")}
                                    DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                                        DropdownMenuItem(text={Text("Открыть в другом приложении")},onClick={menu=false;share(true)})
                                    }
                                }
                            }
                            HorizontalPager(state=pager,userScrollEnabled=currentKind=="image" && !imageZoomed && !pip && queue.size>1,modifier=Modifier.weight(1f).fillMaxWidth(),key={queue.getOrNull(it)?.ref ?: "media"}) {index->
                                Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                                    if(index==pager.settledPage) {
                                        if(currentKind=="media" || queue.isEmpty()) {
                                            val player=controller
                                            if(player!=null)AndroidView(factory={PlayerView(it).apply{this.player=player;resizeMode=this@MediaActivity.resizeMode}},update={it.player=player;it.useController=!pip;it.resizeMode=resizeMode},onRelease={it.player=null},modifier=Modifier.fillMaxSize())
                                            if(fullscreen && video && !pip)Row(Modifier.align(Alignment.TopEnd).padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                                                VideoSettingsMenu(resizeMode,playbackSpeed,{resizeMode=it},{playbackSpeed=it;controller?.setPlaybackParameters(PlaybackParameters(it,1f))})
                                                IconButton(onClick={fullscreen=false}){Icon(Icons.Outlined.FullscreenExit,"Выйти из полноэкранного режима",tint=Color.White)}
                                            }
                                            if(error!=null && !pip)Surface{Text(error!!,Modifier.padding(24.dp))} else if(loading)CircularProgressIndicator()
                                        } else if(error!=null)Text(error!!,Modifier.padding(24.dp))
                                        else if(current==null || currentKind.isEmpty())CircularProgressIndicator()
                                        else key(current!!.ref){FilePreviewContent(current!!,Storage(this@MediaActivity),currentKind){imageZoomed=it}}
                                    }
                                }
                            }
                            if(!pip && !fullscreen) {
                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                                    IconButton(modifier=Modifier.semantics{contentDescription="Предыдущий файл"},enabled=pager.currentPage>0,onClick={scope.launch{pager.animateScrollToPage(pager.currentPage-1)}}){Icon(Icons.Outlined.ChevronLeft,null)}
                                    Text("${if(queue.isEmpty())1 else pager.currentPage+1} / ${queue.size.coerceAtLeast(1)}")
                                    IconButton(modifier=Modifier.semantics{contentDescription="Следующий файл"},enabled=pager.currentPage<queue.lastIndex,onClick={scope.launch{pager.animateScrollToPage(pager.currentPage+1)}}){Icon(Icons.Outlined.ChevronRight,null)}
                                }
                                if(currentKind=="media")TextButton(onClick={controller?.stop();controller?.clearMediaItems();loading=false}){Text("Остановить воспроизведение")}
                            }
                        }
                    }
                }
            }
        }
        val f=MediaController.Builder(this,SessionToken(this,ComponentName(this,PlaybackService::class.java))).setListener(object:MediaController.Listener {
            override fun onError(controller:MediaController,sessionError:SessionError){error=sessionError.message;loading=false}
            override fun onDisconnected(controller:MediaController){if(!isFinishing){error="Воспроизведение завершено";loading=false}}
        }).buildAsync()
        future=f
        f.addListener({try {controller=f.get();controller!!.addListener(listener);loadQueue(intent,savedInstanceState);sync()}catch(e:Exception){error=e.cause?.message ?: e.message ?: "Плеер недоступен"}},ContextCompat.getMainExecutor(this))
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);if(controller!=null)loadQueue(intent,null)}
    private fun loadQueue(request:Intent,saved:Bundle?) {
        val name=saved?.getString("queue") ?: request.getStringExtra("queue")
        val ref=request.getStringExtra("ref")
        if(name==null && ref==null) { // Notification: keep the service's current playback.
            val item=controller?.currentMediaItem
            if(item!=null){current=Entry(item.mediaId,item.mediaMetadata.title?.toString() ?: "Медиафайл",false,mime=item.mediaMetadata.extras?.getString("mime") ?: "application/octet-stream");currentKind="media"}
            return
        }
        lifecycleScope.launch {
            try {
                val fallback=ref?.let{Entry(it,request.getStringExtra("name") ?: "Файл",false,request.getLongExtra("size",-1),mime=request.getStringExtra("mime") ?: "application/octet-stream")}
                val items=withContext(Dispatchers.IO){if(name!=null)PreviewQueue.load(this@MediaActivity,name) else listOfNotNull(fallback)}
                queueName=name;initialPage=(saved?.getInt("page") ?: items.indexOfFirst{it.ref==ref}.coerceAtLeast(0)).coerceIn(0,maxOf(0,items.lastIndex));queue=items;queueVersion++
            }catch(ex:CancellationException){throw ex}catch(ex:Exception){error=ex.message;loading=false}
        }
    }
    private fun select(entry:Entry) {
        resolveJob?.cancel()
        if(current?.ref!=entry.ref){if(controller?.currentMediaItem?.mediaId!=entry.ref)controller?.pause();current=entry;currentKind="";video=false;error=null;loading=true;sync()}
        resolveJob=lifecycleScope.launch {
            try {
                val s=Storage(this@MediaActivity)
                val result=withContext(Dispatchers.IO){val actual=PreviewFiles.resolve(s,entry);actual to s.input(actual.ref).use{PreviewFiles.kind(actual,PreviewFiles.read(it,512))}}
                current=result.first;currentKind=result.second;s.recent(result.first.ref)
                if(currentKind=="media")play(result.first) else {controller?.pause();loading=false;sync()}
            }catch(ex:CancellationException){throw ex}catch(ex:Exception){error=ex.message ?: "Файл недоступен. Проверьте USB.";loading=false}
        }
    }
    private fun play(e:Entry) {
        val player=controller ?: return
        if(player.currentMediaItem?.mediaId==e.ref && player.playbackState!=Player.STATE_IDLE){player.play();sync();return}
        error=null;loading=true
        val metadata=MediaMetadata.Builder().setTitle(e.name).setExtras(Bundle().apply{putLong("size",e.size);putString("mime",e.mime);putBoolean("video",PlaybackRules.video(e))}).build()
        player.setMediaItem(MediaItem.Builder().setMediaId(e.ref).setUri(e.ref).setMediaMetadata(metadata).build())
        player.prepare();player.play()
    }
    override fun onSaveInstanceState(outState:Bundle){super.onSaveInstanceState(outState);outState.putString("queue",queueName);outState.putInt("page",pageIndex)}
    private fun sync() {
        val player=controller ?: return
        title=player.mediaMetadata.title?.toString() ?: "Медиаплеер"
        video=currentKind=="media" && player.mediaMetadata.extras?.getBoolean("video",false)==true
        loading=player.playbackState==Player.STATE_IDLE || player.playbackState==Player.STATE_BUFFERING
        canPip=PlaybackRules.pip(video,player.playWhenReady,player.playbackState)
        val aspect=PlaybackRules.ratio(player.videoSize.width,player.videoSize.height)
        val params=PictureInPictureParams.Builder().setAspectRatio(Rational((aspect*1000).toInt(),1000))
        if(Build.VERSION.SDK_INT>=31)params.setAutoEnterEnabled(canPip).setSeamlessResizeEnabled(true)
        pipParams=params.build()
        if(packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE))setPictureInPictureParams(pipParams)
    }
    fun enterPip() {
        if(!video || !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE))return
        runCatching {enterPictureInPictureMode(pipParams)}.onSuccess{if(!it)error="Android не разрешил режим «картинка в картинке»"}.onFailure{error="Режим «картинка в картинке» недоступен"}
    }
    override fun onUserLeaveHint(){super.onUserLeaveHint();if(Build.VERSION.SDK_INT<31 && canPip)enterPip()}
    override fun onPictureInPictureModeChanged(inPip:Boolean,newConfig:Configuration){
        super.onPictureInPictureModeChanged(inPip,newConfig)
        pipExitPending = pipExitPending || PlaybackRules.stopAfterPipDismiss(pip,inPip,video)
        pip=inPip
    }
    override fun onStart(){super.onStart();if(!pip)pipExitPending=false}
    override fun onStop(){
        super.onStop()
        // Expanding the PiP returns this activity to the foreground. Closing the PiP
        // leaves it stopped; stop the service player so audio cannot continue invisibly.
        if(pipExitPending){
            pipExitPending=false
            controller?.pause()
            controller?.stop()
            controller?.clearMediaItems()
        }
    }
    private fun share(external:Boolean=false) {val entry=current ?: return;lifecycleScope.launch {
        try {
            val s=Storage(this@MediaActivity);val uri=withContext(Dispatchers.IO){s.externalUri(entry.ref){ensureActive()}}
            val action=if(external)Intent(Intent.ACTION_VIEW).setDataAndType(uri,entry.mime) else Intent(Intent.ACTION_SEND).setType(entry.mime).putExtra(Intent.EXTRA_STREAM,uri)
            action.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);action.clipData=android.content.ClipData.newRawUri(entry.name,uri)
            startActivity(Intent.createChooser(action,if(external)"Открыть в приложении" else "Поделиться"))
        }catch(e:CancellationException){throw e}catch(e:Exception){error=e.message}
    }}
    override fun onDestroy() {
        WindowCompat.getInsetsController(window,window.decorView).show(WindowInsetsCompat.Type.systemBars())
        controller?.removeListener(listener)
        future?.let {MediaController.releaseFuture(it)}
        controller=null
        super.onDestroy()
    }
}

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun VideoSettingsMenu(
    resizeMode:Int,
    speed:Float,
    onResize:(Int)->Unit,
    onSpeed:(Float)->Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick={expanded=true}) { Icon(Icons.Outlined.Tune,"Настройки видео",tint=if(resizeMode==AspectRatioFrameLayout.RESIZE_MODE_FIT) LocalContentColor.current else MaterialTheme.colorScheme.primary) }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
            Text("Размер кадра",Modifier.padding(horizontal=16.dp,vertical=8.dp),style=MaterialTheme.typography.labelMedium)
            listOf("Вписать" to AspectRatioFrameLayout.RESIZE_MODE_FIT,"Заполнить" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM,"Растянуть" to AspectRatioFrameLayout.RESIZE_MODE_FILL).forEach { (label,mode) ->
                DropdownMenuItem(text={Text((if(resizeMode==mode)"✓ " else "")+label)},onClick={onResize(mode);expanded=false})
            }
            HorizontalDivider()
            Text("Скорость",Modifier.padding(horizontal=16.dp,vertical=8.dp),style=MaterialTheme.typography.labelMedium)
            listOf(.5f,.75f,1f,1.25f,1.5f,2f).forEach { value ->
                DropdownMenuItem(text={Text((if(speed==value)"✓ " else "")+"${value}×")},onClick={onSpeed(value);expanded=false})
            }
        }
    }
}
