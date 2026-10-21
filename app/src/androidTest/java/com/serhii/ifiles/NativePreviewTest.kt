package com.serhii.ifiles

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** A pipe, not a seekable disk descriptor: reproduces streaming USB/document providers. */
class PipePreviewProvider:ContentProvider() {
    override fun onCreate()=true
    override fun getType(uri:Uri)="application/octet-stream"
    override fun query(u:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?):Cursor?=null
    override fun insert(u:Uri,v:ContentValues?):Uri?=null
    override fun delete(u:Uri,s:String?,a:Array<out String>?)=0
    override fun update(u:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=0
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
        require(mode=="r")
        val bytes=if(uri.lastPathSegment=="mp4")android.util.Base64.decode("AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAMzbW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAD6AAAAZAAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAl10cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAAZAAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAACAAAAAYAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAGQAAAAAAABAAAAAAHVbWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAAAoAAAAEABVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAABgG1pbmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAAUBzdGJsAAAAwHN0c2QAAAAAAAAAAQAAALBhdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAACAAGABIAAAASAAAAAAAAAABFUxhdmM2MC4zMS4xMDIgbGlieDI2NAAAAAAAAAAAAAAAGP//AAAANmF2Y0MBZAAK/+EAGWdkAAqs2Ul+XARAAAADAEAAAAMCg8SJZYABAAZo6+PLIsD9+PgAAAAAEHBhc3AAAAABAAAAAQAAABRidHJ0AAAAAAAAOTAAADkwAAAAGHN0dHMAAAAAAAAAAQAAAAIAAAgAAAAAFHN0c3MAAAAAAAAAAQAAAAEAAAAcc3RzYwAAAAAAAAABAAAAAQAAAAIAAAABAAAAHHN0c3oAAAAAAAAAAAAAAAIAAALPAAAADQAAABRzdGNvAAAAAAAAAAEAAANjAAAAYnVkdGEAAABabWV0YQAAAAAAAAAhaGRscgAAAAAAAAAAbWRpcmFwcGwAAAAAAAAAAAAAAAAtaWxzdAAAACWpdG9vAAAAHWRhdGEAAAABAAAAAExhdmY2MC4xNi4xMDAAAAAIZnJlZQAAAuRtZGF0AAACrQYF//+p3EXpvebZSLeWLNgg2SPu73gyNjQgLSBjb3JlIDE2NCByMzEwOCAzMWUxOWY5IC0gSC4yNjQvTVBFRy00IEFWQyBjb2RlYyAtIENvcHlsZWZ0IDIwMDMtMjAyMyAtIGh0dHA6Ly93d3cudmlkZW9sYW4ub3JnL3gyNjQuaHRtbCAtIG9wdGlvbnM6IGNhYmFjPTEgcmVmPTMgZGVibG9jaz0xOjA6MCBhbmFseXNlPTB4MzoweDExMyBtZT1oZXggc3VibWU9NyBwc3k9MSBwc3lfcmQ9MS4wMDowLjAwIG1peGVkX3JlZj0xIG1lX3JhbmdlPTE2IGNocm9tYV9tZT0xIHRyZWxsaXM9MSA4eDhkY3Q9MSBjcW09MCBkZWFkem9uZT0yMSwxMSBmYXN0X3Bza2lwPTEgY2hyb21hX3FwX29mZnNldD0tMiB0aHJlYWRzPTEgbG9va2FoZWFkX3RocmVhZHM9MSBzbGljZWRfdGhyZWFkcz0wIG5yPTAgZGVjaW1hdGU9MSBpbnRlcmxhY2VkPTAgYmx1cmF5X2NvbXBhdD0wIGNvbnN0cmFpbmVkX2ludHJhPTAgYmZyYW1lcz0zIGJfcHlyYW1pZD0yIGJfYWRhcHQ9MSBiX2JpYXM9MCBkaXJlY3Q9MSB3ZWlnaHRiPTEgb3Blbl9nb3A9MCB3ZWlnaHRwPTIga2V5aW50PTI1MCBrZXlpbnRfbWluPTUgc2NlbmVjdXQ9NDAgaW50cmFfcmVmcmVzaD0wIHJjX2xvb2thaGVhZD00MCByYz1jcmYgbWJ0cmVlPTEgY3JmPTIzLjAgcWNvbXA9MC42MCBxcG1pbj0wIHFwbWF4PTY5IHFwc3RlcD00IGlwX3JhdGlvPTEuNDAgYXE9MToxLjAwAIAAAAAaZYiEABD//ubA+ZZaIeOAUIdrhk60i2Ofd30AAAAJQZohbEP//quA",android.util.Base64.DEFAULT) else if(uri.lastPathSegment=="wav") {
            java.nio.ByteBuffer.allocate(44+16000).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray());putInt(36+16000);put("WAVEfmt ".toByteArray());putInt(16);putShort(1.toShort());putShort(1.toShort());putInt(8000);putInt(16000);putShort(2.toShort());putShort(16.toShort());put("data".toByteArray());putInt(16000)
                repeat(8000){putShort((kotlin.math.sin(it*2*Math.PI*440/8000)*8000).toInt().toShort())}
            }.array()
        } else if(uri.lastPathSegment=="pdf")PdfDocument().use { document ->
            val page=document.startPage(PdfDocument.PageInfo.Builder(300,400,1).create())
            page.canvas.drawColor(Color.WHITE);page.canvas.drawText("USB PDF",30f,50f,android.graphics.Paint().apply{color=Color.BLACK;textSize=20f})
            document.finishPage(page)
            ByteArrayOutputStream().also{document.writeTo(it)}.toByteArray()
        } else ByteArrayOutputStream().also {out->Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888).also{b->b.eraseColor(Color.RED);b.compress(Bitmap.CompressFormat.PNG,100,out);b.recycle()}}.toByteArray()
        val pipe=ParcelFileDescriptor.createPipe()
        Thread {try{ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use{it.write(bytes)}}catch(_:Exception){}}.start()
        return pipe[0]
    }
}
@RunWith(AndroidJUnit4::class)
class NativePreviewTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun realPdfRendererReadsLocalAndPipeContentWithoutChangingSource() {
        val s=Storage(context)
        val uri="content://com.serhii.ifiles.test.preview/pdf"
        val f=File.createTempFile("native-pdf-",".pdf",context.cacheDir)
        try {
            PreviewFiles.copy(s.input(uri),f,-1){}
            val before=f.readBytes()
            ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY).use{fd->PdfRenderer(fd).use{pdf->
                assertEquals(1,pdf.pageCount)
                pdf.openPage(0).use{page->val b=Bitmap.createBitmap(300,400,Bitmap.Config.ARGB_8888);page.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);assertEquals(300,b.width);b.recycle()}
            }}
            assertArrayEquals(before,f.readBytes())
            assertEquals("pdf",PreviewFiles.kind(s.stat(f.toURI().toString()),before.take(512).toByteArray()))
        } finally {f.delete()}
    }
    @Test fun playerPreparesLocalAndStreamingProviderAudio() {
        val s=Storage(context);val ref="content://com.serhii.ifiles.test.preview/wav"
        val e=Entry(ref,"Sound.wav",false,mime="audio/wav")
        val staged=PreviewFiles.mediaCopy(s,e){}
        assertNotNull("Pipe sources must become seekable",staged)
        try {
            for(uri in listOf(Uri.fromFile(staged),s.uri(staged!!.toURI().toString()))) {
                val ready=java.util.concurrent.CountDownLatch(1)
                val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
                var player:androidx.media3.exoplayer.ExoPlayer?=null
                try {
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        player=androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
                            addListener(object:androidx.media3.common.Player.Listener {
                                override fun onPlaybackStateChanged(state:Int){if(state==androidx.media3.common.Player.STATE_READY)ready.countDown()}
                                override fun onPlayerError(error:androidx.media3.common.PlaybackException){failure.set(error);ready.countDown()}
                            })
                            setMediaItem(androidx.media3.common.MediaItem.fromUri(uri));prepare()
                        }
                    }
                    assertTrue("Audio preparation timed out",ready.await(20,java.util.concurrent.TimeUnit.SECONDS))
                    assertNull("Player error: ${failure.get()}",failure.get())
                } finally {InstrumentationRegistry.getInstrumentation().runOnMainSync{player?.release()}}
            }
        } finally {staged?.delete()}
    }
    @Test fun videoFrameIsDecodedFromStagedProviderAndLocalFile() {
        val s=Storage(context)
        val e=Entry("content://com.serhii.ifiles.test.preview/mp4","Video.mp4",false,mime="video/mp4")
        val staged=PreviewFiles.mediaCopy(s,e){}
        assertNotNull(staged)
        try {
            val thumb=Thumbnails.load(s,s.stat(staged!!.toURI().toString()).copy(mime="video/mp4"))
            assertNotNull("Video frame must decode",thumb)
        } finally {staged?.delete()}
    }
    @Test fun realImageDecoderAndThumbnailReadLocalAndPipe() {
        val s=Storage(context);val uri=Uri.parse("content://com.serhii.ifiles.test.preview/png")
        val f=File.createTempFile("native-image-",".png",context.cacheDir)
        try {
            PreviewFiles.copy(s.input(uri.toString()),f,-1){}
            for(source in listOf(ImageDecoder.createSource(s.resolver,uri),ImageDecoder.createSource(f))) {
                val b=ImageDecoder.decodeBitmap(source){decoder,_,_->decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE}
                assertEquals(32,b.width);assertEquals(Color.RED,b.getPixel(0,0));b.recycle()
            }
            assertNotNull(Thumbnails.load(s,s.stat(f.toURI().toString())))
        } finally {f.delete()}
    }
}
