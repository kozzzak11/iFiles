package com.serhii.ifiles

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** File-backed queue avoids Binder limits for folders containing thousands of files. */
object PreviewQueue {
    fun entries(start:Entry,visible:List<Entry>):List<Entry> {
        val files=visible.filter{!it.directory}.distinctBy{it.ref}
        return if(files.any{it.ref==start.ref})files else listOf(start)
    }
    fun save(context:Context,start:Entry,visible:List<Entry>):File {
        val dir=File(context.cacheDir,"preview-queues").apply{mkdirs()}
        dir.listFiles()?.filter{System.currentTimeMillis()-it.lastModified()>7L*24*60*60*1000}?.forEach{it.delete()}
        val array=JSONArray()
        entries(start,visible).forEach {e->array.put(JSONObject().put("ref",e.ref).put("name",e.name).put("mime",e.mime).put("size",e.size).put("modified",e.modified))}
        return File.createTempFile("queue-",".json",dir).apply{writeText(array.toString())}
    }
    fun load(context:Context,name:String):List<Entry> {
        require(name==File(name).name && name.startsWith("queue-"))
        val f=File(File(context.cacheDir,"preview-queues"),name);require(f.length()<=16*1024*1024)
        val a=JSONArray(f.readText());return (0 until a.length()).map{i->val o=a.getJSONObject(i);Entry(o.getString("ref"),o.getString("name"),false,o.optLong("size",-1),o.optLong("modified",0),o.optString("mime","application/octet-stream"))}
    }
}
