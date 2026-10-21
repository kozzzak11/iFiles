package com.serhii.ifiles;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Runs in the test APK process: depends only on Android, never on target APK libraries. */
public class PipePreviewProvider extends ContentProvider {
    @Override public boolean onCreate() {return true;}
    @Override public String getType(Uri uri) {return "application/octet-stream";}
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o) {return null;}
    @Override public Uri insert(Uri u,ContentValues v) {return null;}
    @Override public int delete(Uri u,String s,String[] a) {return 0;}
    @Override public int update(Uri u,ContentValues v,String s,String[] a) {return 0;}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws java.io.FileNotFoundException {
        if(!"r".equals(mode)) throw new java.io.FileNotFoundException("Read only");
        try {
            final byte[] bytes;
            if("mp4".equals(uri.getLastPathSegment())) bytes=android.util.Base64.decode("AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAMzbW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAD6AAAAZAAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAl10cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAAZAAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAACAAAAAYAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAGQAAAAAAABAAAAAAHVbWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAAAoAAAAEABVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAABgG1pbmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAAUBzdGJsAAAAwHN0c2QAAAAAAAAAAQAAALBhdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAACAAGABIAAAASAAAAAAAAAABFUxhdmM2MC4zMS4xMDIgbGlieDI2NAAAAAAAAAAAAAAAGP//AAAANmF2Y0MBZAAK/+EAGWdkAAqs2Ul+XARAAAADAEAAAAMCg8SJZYABAAZo6+PLIsD9+PgAAAAAEHBhc3AAAAABAAAAAQAAABRidHJ0AAAAAAAAOTAAADkwAAAAGHN0dHMAAAAAAAAAAQAAAAIAAAgAAAAAFHN0c3MAAAAAAAAAAQAAAAEAAAAcc3RzYwAAAAAAAAABAAAAAQAAAAIAAAABAAAAHHN0c3oAAAAAAAAAAAAAAAIAAALPAAAADQAAABRzdGNvAAAAAAAAAAEAAANjAAAAYnVkdGEAAABabWV0YQAAAAAAAAAhaGRscgAAAAAAAAAAbWRpcmFwcGwAAAAAAAAAAAAAAAAtaWxzdAAAACWpdG9vAAAAHWRhdGEAAAABAAAAAExhdmY2MC4xNi4xMDAAAAAIZnJlZQAAAuRtZGF0AAACrQYF//+p3EXpvebZSLeWLNgg2SPu73gyNjQgLSBjb3JlIDE2NCByMzEwOCAzMWUxOWY5IC0gSC4yNjQvTVBFRy00IEFWQyBjb2RlYyAtIENvcHlsZWZ0IDIwMDMtMjAyMyAtIGh0dHA6Ly93d3cudmlkZW9sYW4ub3JnL3gyNjQuaHRtbCAtIG9wdGlvbnM6IGNhYmFjPTEgcmVmPTMgZGVibG9jaz0xOjA6MCBhbmFseXNlPTB4MzoweDExMyBtZT1oZXggc3VibWU9NyBwc3k9MSBwc3lfcmQ9MS4wMDowLjAwIG1peGVkX3JlZj0xIG1lX3JhbmdlPTE2IGNocm9tYV9tZT0xIHRyZWxsaXM9MSA4eDhkY3Q9MSBjcW09MCBkZWFkem9uZT0yMSwxMSBmYXN0X3Bza2lwPTEgY2hyb21hX3FwX29mZnNldD0tMiB0aHJlYWRzPTEgbG9va2FoZWFkX3RocmVhZHM9MSBzbGljZWRfdGhyZWFkcz0wIG5yPTAgZGVjaW1hdGU9MSBpbnRlcmxhY2VkPTAgYmx1cmF5X2NvbXBhdD0wIGNvbnN0cmFpbmVkX2ludHJhPTAgYmZyYW1lcz0zIGJfcHlyYW1pZD0yIGJfYWRhcHQ9MSBiX2JpYXM9MCBkaXJlY3Q9MSB3ZWlnaHRiPTEgb3Blbl9nb3A9MCB3ZWlnaHRwPTIga2V5aW50PTI1MCBrZXlpbnRfbWluPTUgc2NlbmVjdXQ9NDAgaW50cmFfcmVmcmVzaD0wIHJjX2xvb2thaGVhZD00MCByYz1jcmYgbWJ0cmVlPTEgY3JmPTIzLjAgcWNvbXA9MC42MCBxcG1pbj0wIHFwbWF4PTY5IHFwc3RlcD00IGlwX3JhdGlvPTEuNDAgYXE9MToxLjAwAIAAAAAaZYiEABD//ubA+ZZaIeOAUIdrhk60i2Ofd30AAAAJQZohbEP//quA",android.util.Base64.DEFAULT);
            else if("wav".equals(uri.getLastPathSegment())) {
                ByteBuffer buffer=ByteBuffer.allocate(44+16000).order(ByteOrder.LITTLE_ENDIAN);
                buffer.put("RIFF".getBytes());buffer.putInt(36+16000);buffer.put("WAVEfmt ".getBytes());buffer.putInt(16);buffer.putShort((short)1);buffer.putShort((short)1);buffer.putInt(8000);buffer.putInt(16000);buffer.putShort((short)2);buffer.putShort((short)16);buffer.put("data".getBytes());buffer.putInt(16000);
                for(int i=0;i<8000;i++) buffer.putShort((short)(Math.sin(i*2*Math.PI*440/8000)*8000));
                bytes=buffer.array();
            } else if("pdf".equals(uri.getLastPathSegment())) {
                PdfDocument doc=new PdfDocument();
                try {
                    PdfDocument.Page page=doc.startPage(new PdfDocument.PageInfo.Builder(300,400,1).create());
                    page.getCanvas().drawColor(Color.WHITE);
                    Paint paint=new Paint();paint.setColor(Color.BLACK);paint.setTextSize(20);
                    page.getCanvas().drawText("USB PDF",30,50,paint);
                    doc.finishPage(page);
                    ByteArrayOutputStream out=new ByteArrayOutputStream();doc.writeTo(out);bytes=out.toByteArray();
                } finally {doc.close();}
            } else {
                Bitmap b=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888);
                try {b.eraseColor(Color.RED);ByteArrayOutputStream out=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.PNG,100,out);bytes=out.toByteArray();} finally {b.recycle();}
            }
            final ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
            new Thread(() -> {try(ParcelFileDescriptor.AutoCloseOutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])){out.write(bytes);}catch(IOException ignored){}}).start();
            return pipe[0];
        } catch(IOException e) {throw new java.io.FileNotFoundException(e.getMessage());}
    }
}
