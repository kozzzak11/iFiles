package com.serhii.ifiles

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
    private var video by mutableStateOf(false)
    private var title by mutableStateOf("Медиаплеер")
    private var error by mutableStateOf<String?>(null)
    private var loading by mutableStateOf(true)
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
                    Column(if(pip)Modifier.fillMaxSize() else Modifier.fillMaxSize().safeDrawingPadding()) {
                        if(!pip)Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                            TextButton(onClick={finish()}){Text("Готово")}
                            Text(title,Modifier.weight(1f),maxLines=1)
                            if(video)IconButton(onClick={enterPip()}){Icon(Icons.Outlined.PictureInPictureAlt,"Картинка в картинке")}
                            IconButton(onClick={share()}){Icon(Icons.Outlined.IosShare,"Поделиться")}
                            IconButton(onClick={controller?.stop();controller?.clearMediaItems();finish()}){Icon(Icons.Outlined.StopCircle,"Остановить воспроизведение")}
                        }
                        Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
                            val player=controller
                            if(player!=null)AndroidView(factory={PlayerView(it).apply{this.player=player}},update={it.player=player;it.useController=!pip},modifier=Modifier.fillMaxSize())
                            if(error!=null && !pip)Surface {Text(error!!,Modifier.padding(24.dp))}
                            else if(loading)CircularProgressIndicator()
                        }
                        if(!pip && !video)Text("Воспроизведение продолжается при сворачивании и блокировке экрана",Modifier.padding(16.dp))
                    }
                }
            }
        }
        val f=MediaController.Builder(this,SessionToken(this,ComponentName(this,PlaybackService::class.java))).setListener(object:MediaController.Listener {
            override fun onError(controller:MediaController,sessionError:SessionError){error=sessionError.message;loading=false}
            override fun onDisconnected(controller:MediaController){if(!isFinishing){error="Воспроизведение завершено";loading=false}}
        }).buildAsync()
        future=f
        f.addListener({try {controller=f.get();controller!!.addListener(listener);open(intent);sync()}catch(e:Exception){error=e.cause?.message ?: e.message ?: "Плеер недоступен"}},ContextCompat.getMainExecutor(this))
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);if(controller!=null)open(intent)}
    private fun open(intent:Intent) {
        val ref=intent.getStringExtra("ref") ?: return
        // Consume the request: rotations and notification taps resume the current position.
        intent.removeExtra("ref")
        val player=controller ?: return
        if(player.currentMediaItem?.mediaId==ref && player.playbackState!=Player.STATE_IDLE)return
        error=null;loading=true
        val metadata=MediaMetadata.Builder().setTitle(intent.getStringExtra("name") ?: "Медиафайл").setExtras(Bundle().apply {
            putLong("size",intent.getLongExtra("size",-1));putString("mime",intent.getStringExtra("mime"));putBoolean("video",intent.getBooleanExtra("video",false))
        }).build()
        player.setMediaItem(MediaItem.Builder().setMediaId(ref).setUri(ref).setMediaMetadata(metadata).build())
        player.prepare();player.play()
    }
    private fun sync() {
        val player=controller ?: return
        title=player.mediaMetadata.title?.toString() ?: "Медиаплеер"
        video=player.mediaMetadata.extras?.getBoolean("video",false)==true
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
    override fun onPictureInPictureModeChanged(inPip:Boolean,newConfig:Configuration){super.onPictureInPictureModeChanged(inPip,newConfig);pip=inPip}
    private fun share() {val item=controller?.currentMediaItem ?: return;lifecycleScope.launch {
        try {
            val s=Storage(this@MediaActivity);val uri=withContext(Dispatchers.IO){s.externalUri(item.mediaId){ensureActive()}}
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(item.mediaMetadata.extras?.getString("mime") ?: "*/*").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply{clipData=android.content.ClipData.newRawUri(title,uri)},"Поделиться"))
        }catch(e:CancellationException){throw e}catch(e:Exception){error=e.message}
    }}
    override fun onDestroy() {
        controller?.removeListener(listener)
        future?.let {MediaController.releaseFuture(it)}
        controller=null
        super.onDestroy()
    }
}
