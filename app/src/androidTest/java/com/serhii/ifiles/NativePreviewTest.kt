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
        val bytes=if(uri.lastPathSegment=="pdf")PdfDocument().use { document ->
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
