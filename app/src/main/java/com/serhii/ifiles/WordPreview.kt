package com.serhii.ifiles

import android.util.Xml
import android.util.Base64
import org.xmlpull.v1.XmlPullParser as XP
import java.io.File
import java.util.zip.ZipFile

object WordDocuments {
    fun escape(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    fun html(file:File,legacy:Boolean,check:()->Unit):String {
        require(file.length()<=32L*1024*1024){"Документ больше 32 МБ"}
        val body=StringBuilder()
        if(legacy) {
            file.inputStream().use{input->org.apache.poi.hwpf.HWPFDocument(input).use{doc->
                val range=doc.range
                for(i in 0 until range.numParagraphs()) {
                    check();require(body.length<4*1024*1024){"Документ слишком велик"}
                    val p=range.getParagraph(i);body.append("<p>")
                    for(j in 0 until p.numCharacterRuns()) {
                        val run=p.getCharacterRun(j)
                        body.append("<span style='${if(run.isBold)"font-weight:bold;" else ""}${if(run.isItalic)"font-style:italic;" else ""}'>")
                        body.append(escape(run.text().replace("\r","").replace("\u0007","\t").filter{it=='\t' || it=='\n' || it.code>=32})).append("</span>")
                    }
                    body.append("</p>")
                }
            }}
        } else ZipFile(file).use{zip->
            fun parse(name:String,visit:(XP)->Unit) {
                val entry=zip.getEntry(name) ?: return
                val bytes=zip.getInputStream(entry).use{PreviewFiles.read(it,8*1024*1024+1)};require(bytes.size<=8*1024*1024){"Документ слишком велик"}
                val p=Xml.newPullParser();p.setFeature(XP.FEATURE_PROCESS_NAMESPACES,true);p.setInput(bytes.inputStream(),null)
                while(p.eventType!=XP.END_DOCUMENT){check();require(p.eventType!=XP.DOCDECL){"XML DTD не поддерживается"};require(body.length<=16*1024*1024){"Документ слишком велик"};visit(p);p.nextToken()}
            }
            fun XP.attr(name:String)=(0 until attributeCount).firstOrNull{getAttributeName(it)==name}?.let{getAttributeValue(it)}
            val rels=mutableMapOf<String,String>()
            parse("word/_rels/document.xml.rels"){p->if(p.eventType==XP.START_TAG && p.name=="Relationship" && p.attr("TargetMode")!="External"){val target=p.attr("Target") ?: "";if(!target.contains(".."))rels[p.attr("Id") ?: ""]="word/$target"}}
            var bold=false;var italic=false;var text=false;var pictureBytes=0
            parse("word/document.xml"){p->when(p.eventType){
                XP.START_TAG->when(p.name){
                    "tbl"->body.append("<table>");"tr"->body.append("<tr>");"tc"->body.append("<td>");"p"->body.append("<p>")
                    "r"->{bold=false;italic=false};"b"->bold=p.attr("val")!="0";"i"->italic=p.attr("val")!="0"
                    "t"->{text=true;body.append("<span style='${if(bold)"font-weight:bold;" else ""}${if(italic)"font-style:italic;" else ""}'>")}
                    "tab"->body.append("&emsp;");"br"->body.append("<br>")
                    "blip"->{val name=rels[p.attr("embed")];val entry=name?.let{zip.getEntry(it)};if(entry!=null && pictureBytes<8*1024*1024){val bytes=zip.getInputStream(entry).use{PreviewFiles.read(it,2*1024*1024+1)};pictureBytes+=bytes.size;val ext=name.substringAfterLast('.').lowercase();if(bytes.size<=2*1024*1024 && ext in setOf("png","jpg","jpeg","gif","webp"))body.append("<img src='data:image/${if(ext=="jpg")"jpeg" else ext};base64,${Base64.encodeToString(bytes,Base64.NO_WRAP)}'>")}}
                }
                XP.TEXT->if(text)body.append(escape(p.text))
                XP.END_TAG->when(p.name){"t"->{text=false;body.append("</span>")};"p"->body.append("</p>");"tc"->body.append("</td>");"tr"->body.append("</tr>");"tbl"->body.append("</table>")}
            }}
        }
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width, initial-scale=1, maximum-scale=5'><style>body{background:#eee;color:#222;margin:0;padding:12px;font:16px/1.5 sans-serif}article{background:white;padding:24px;overflow-wrap:anywhere}p{white-space:pre-wrap;min-height:1em}table{border-collapse:collapse;max-width:100%}td{border:1px solid #bbb;padding:6px;min-width:30px}img{max-width:100%;height:auto}</style></head><body><article>$body</article></body></html>"
    }
}
