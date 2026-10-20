package com.serhii.ifiles

import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

class Archives(private val engine: FileEngine) {
    private val s=engine.s
    fun zip(refs: List<String>, parent: String, requestedName: String) {
        require(refs.isNotEmpty())
        val name=Names.check(if(requestedName.endsWith(".zip",true)) requestedName else "$requestedName.zip")
        refs.forEach { engine.assertOutside(it,parent) }
        val out=s.create(parent,s.unique(parent,name),false,"application/zip")
        var completed=false
        try {
            ZipOutputStream(s.output(out).buffered()).use { zip ->
                fun add(ref: String, path: String, depth: Int) {
                    engine.checkpoint(); require(depth<128)
                    val e=s.stat(ref); Names.zipParts(path); engine.progress(e.name)
                    zip.putNextEntry(ZipEntry(if(e.directory) "$path/" else path))
                    if(!e.directory) s.input(ref).use { input ->
                        val buf=ByteArray(256*1024)
                        while(true) { engine.checkpoint(); val n=input.read(buf); if(n<0)break;zip.write(buf,0,n) }
                    }
                    zip.closeEntry()
                    if(e.directory) s.list(ref).forEach { add(it.ref,"$path/${it.name}",depth+1) }
                }
                val names=HashSet<String>()
                refs.forEach { val e=s.stat(it); val n=Names.unique(e.name) { candidate->candidate in names }; names+=n;add(it,n,0) }
            }
            // Fully read back CRCs before reporting success.
            ZipInputStream(s.input(out).buffered()).use { z -> val buf=ByteArray(256*1024);while(z.nextEntry!=null) { while(true) { engine.checkpoint();if(z.read(buf)<0)break };z.closeEntry() } }
            completed=true; s.scan(out)
        } finally { if(!completed)runCatching { s.delete(out) } }
    }
    fun unzip(ref: String, parent: String) {
        val entry=s.stat(ref); val root=s.create(parent,s.unique(parent,entry.name.substringBeforeLast('.',entry.name)),true)
        // Isolated new folder; existing user files cannot be overwritten by ZIP entries.
        val dirs=hashMapOf("" to root); val seen=hashSetOf<String>(); var total=0L;var count=0
        ZipInputStream(s.input(ref).buffered()).use { zip ->
            while(true) {
                engine.checkpoint();val item=zip.nextEntry ?: break
                require(++count<=100000) { "Слишком много элементов ZIP" }
                val parts=Names.zipParts(item.name); require(parts.size<128)
                val key=parts.joinToString("/")
                require(seen.add(key)) { "Повторяющийся путь ZIP: $key" }
                var dir=root;var path=""
                for(segment in parts.dropLast(1)) {
                    path=if(path.isEmpty())segment else "$path/$segment"
                    dir=dirs[path] ?: s.create(dir,segment,true).also { dirs[path]=it }
                }
                if(item.isDirectory) {
                    if(key !in dirs) dirs[key]=s.create(dir,parts.last(),true)
                } else {
                    engine.progress(key)
                    val target=s.create(dir,parts.last(),false)
                    s.output(target).use { out -> val buf=ByteArray(256*1024)
                        while(true) { engine.checkpoint(); val n=zip.read(buf);if(n<0)break;total+=n
                            require(total <= 512L*1024*1024*1024) { "Архив превышает безопасный предел 512 ГБ" }
                            out.write(buf,0,n)
                        }
                    }
                    s.scan(target)
                }
                zip.closeEntry()
            }
        }
    }
}
