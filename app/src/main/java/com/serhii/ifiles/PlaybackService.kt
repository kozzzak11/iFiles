package com.serhii.ifiles

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionError
import androidx.media3.common.MediaItem
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.File
import java.io.InterruptedIOException
import java.util.concurrent.Executors

/** Owns playback independently from all activities, including the USB stream cache. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService:MediaSessionService() {
    private var session:MediaSession?=null
    private val io=Executors.newSingleThreadExecutor()
    private val copies=java.util.Collections.synchronizedList(mutableListOf<File>())
    @Volatile private var destroyed=false
    override fun onCreate() {
        super.onCreate()
        val player=ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT,true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
        }
        val reopen=PendingIntent.getActivity(this,10,Intent(this,MediaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session=MediaSession.Builder(this,player).setSessionActivity(reopen).setCallback(object:MediaSession.Callback {
            override fun onAddMediaItems(mediaSession:MediaSession,controller:MediaSession.ControllerInfo,mediaItems:List<MediaItem>):ListenableFuture<List<MediaItem>> {
                val result=SettableFuture.create<List<MediaItem>>()
                io.execute {
                    try {
                        val storage=Storage(this@PlaybackService)
                        val prepared=mediaItems.map { item ->
                            val ref=item.mediaId
                            require(storage.uri(ref).scheme in setOf("file","content")){"Неподдерживаемый источник"}
                            val metadata=item.mediaMetadata
                            val extras=metadata.extras
                            val entry=Entry(ref,metadata.title?.toString() ?: "Медиафайл",false,extras?.getLong("size",-1) ?: -1,mime=extras?.getString("mime") ?: "application/octet-stream")
                            val persistent=storage.resolver.persistedUriPermissions.any {it.isReadPermission && ref.startsWith(it.uri.toString())}
                            val copy=PreviewFiles.mediaCopy(storage,entry,!persistent && !storage.isFile(ref)) {
                                if(destroyed || result.isCancelled || Thread.currentThread().isInterrupted)throw InterruptedIOException("Подготовка отменена")
                            }
                            if(copy!=null) {
                                synchronized(copies) {
                                    if(destroyed){copy.delete();throw InterruptedIOException("Плеер закрыт")}
                                    copies.add(copy)
                                }
                            }
                            item.buildUpon().setUri(copy?.let{android.net.Uri.fromFile(it)} ?: storage.uri(ref)).build()
                        }
                        result.set(prepared)
                    } catch(e:Exception){
                        result.setException(e)
                        android.os.Handler(mainLooper).post {
                            if(!destroyed)mediaSession.sendError(controller,SessionError(SessionError.ERROR_UNKNOWN,e.message ?: "Не удалось подготовить файл. Проверьте USB.",android.os.Bundle.EMPTY))
                        }
                    }
                }
                return result
            }
        }).build()
    }
    override fun onGetSession(controllerInfo:MediaSession.ControllerInfo):MediaSession?=session
    override fun onDestroy() {
        destroyed=true;io.shutdownNow()
        session?.let {it.player.release();it.release()};session=null
        synchronized(copies){copies.forEach{it.delete()};copies.clear()}
        super.onDestroy()
    }
}

object PlaybackRules {
    fun video(e:Entry)=e.mime.startsWith("video/") || e.name.substringAfterLast('.',"").lowercase() in setOf("mp4","mov","mkv","webm","3gp","avi","m4v")
    fun pip(video:Boolean,playWhenReady:Boolean,state:Int)=video && playWhenReady && state!=androidx.media3.common.Player.STATE_ENDED && state!=androidx.media3.common.Player.STATE_IDLE
    fun ratio(width:Int,height:Int)=if(width>0 && height>0)(width.toFloat()/height).coerceIn(1f/2.39f,2.39f) else 16f/9f
}
