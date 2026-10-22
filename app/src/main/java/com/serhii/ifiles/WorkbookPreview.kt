package com.serhii.ifiles

import android.util.Xml
import org.xmlpull.v1.XmlPullParser as XP
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

data class SheetCell(val text:String, val bold:Boolean=false, val background:String="", val color:String="")
data class SheetPage(val name:String,val cells:Map<Pair<Int,Int>,SheetCell>,val rows:Int,val columns:Int,val merges:List<IntArray> = emptyList(),val limited:Boolean=false)
object Workbooks {
    private const val MAX_ROWS=10000
    private const val MAX_COLS=256
    private const val MAX_CELLS=100000
    fun columnName(index:Int):String {var n=index+1;var s="";while(n>0){n--;s=('A'+n%26)+s;n/=26};return s}
    fun address(value:String):Pair<Int,Int> {
        val letters=value.takeWhile{it.isLetter()};require(letters.isNotEmpty() && letters.length<=3){"Неверный адрес ячейки"}
        val c=letters.uppercase().fold(0){n,ch->n*26+(ch-'A'+1)}-1
        val r=value.drop(letters.length).toInt()-1;require(r>=0 && c>=0);return r to c
    }
    fun read(file:File,extension:String,check:()->Unit):List<SheetPage> {
        require(file.length()<=64L*1024*1024){"Таблица больше 64 МБ. Откройте её во внешнем приложении."}
        if(extension.lowercase()=="xls") {
            val workbook=jxl.Workbook.getWorkbook(file)
            try {return (0 until workbook.numberOfSheets).map {i->
                check();val s=workbook.getSheet(i);val cells=linkedMapOf<Pair<Int,Int>,SheetCell>()
                val rows=minOf(s.rows,MAX_ROWS);val cols=minOf(s.columns,MAX_COLS)
                for(r in 0 until rows)for(c in 0 until cols){check();val cell=s.getCell(c,r);if(cell.contents.isNotEmpty()){require(cells.size<MAX_CELLS){"Слишком большая таблица"};cells[r to c]=SheetCell(cell.contents,cell.cellFormat?.font?.boldWeight?.let{it>400}==true)}}
                SheetPage(s.name,cells,rows,cols,s.mergedCells.map{intArrayOf(it.topLeft.row,it.topLeft.column,it.bottomRight.row,it.bottomRight.column)},rows<s.rows || cols<s.columns)
            }}finally{workbook.close()}
        }
        ZipFile(file).use {zip->
            var total=0
            fun parse(path:String,visit:(XP)->Unit) {
                val entry=zip.getEntry(path) ?: return
                val bytes=zip.getInputStream(entry).use{PreviewFiles.read(it,16*1024*1024+1)}
                total+=bytes.size;require(bytes.size<=16*1024*1024 && total<=48*1024*1024){"Таблица слишком велика для просмотра"}
                val p=Xml.newPullParser();p.setFeature(XP.FEATURE_PROCESS_NAMESPACES,true);p.setInput(bytes.inputStream(),null)
                while(p.eventType!=XP.END_DOCUMENT){check();require(p.eventType!=XP.DOCDECL){"XML DTD не поддерживается"};visit(p);p.nextToken()}
            }
            fun XP.attr(name:String):String?=(0 until attributeCount).firstOrNull{getAttributeName(it)==name}?.let{getAttributeValue(it)}
            val strings=mutableListOf<String>();var rich=StringBuilder();var inText=false
            parse("xl/sharedStrings.xml"){p->when(p.eventType){XP.START_TAG->when(p.name){"si"->rich=StringBuilder();"t"->inText=true};XP.TEXT->if(inText)rich.append(p.text);XP.END_TAG->when(p.name){"t"->inText=false;"si"->{require(strings.size<MAX_CELLS);strings+=rich.toString()}}}}
            val rels=mutableMapOf<String,String>()
            parse("xl/_rels/workbook.xml.rels"){p->if(p.eventType==XP.START_TAG && p.name=="Relationship" && p.attr("TargetMode")!="External") {
                val target=p.attr("Target") ?: "";val path=if(target.startsWith('/'))target.drop(1) else "xl/$target"
                if(!path.contains(".."))rels[p.attr("Id") ?: ""]=path
            }}
            val names=mutableListOf<Pair<String,String>>();var date1904=false
            parse("xl/workbook.xml"){p->if(p.eventType==XP.START_TAG)when(p.name){"workbookPr"->date1904=p.attr("date1904") in setOf("1","true");"sheet"->{val path=rels[p.attr("id")];if(path!=null)names+=(p.attr("name") ?: "Лист") to path}}}
            require(names.isNotEmpty()){ "Не удалось прочитать листы Excel. Файл может быть защищён паролем или иметь другой формат." }
            data class Style(val format:Int,val font:Int,val fill:Int)
            val styles=mutableListOf<Style>();val formats=mutableMapOf<Int,String>();val fonts=mutableListOf<Pair<Boolean,String>>();val fills=mutableListOf<String>()
            var section="";var bold=false;var color="";var fill=""
            parse("xl/styles.xml"){p->when(p.eventType){XP.START_TAG->when(p.name){
                "numFmt"->formats[p.attr("numFmtId")?.toIntOrNull() ?: 0]=p.attr("formatCode") ?: ""
                "fonts","fills","cellXfs"->section=p.name
                "font"->{bold=false;color=""};"b"->if(section=="fonts")bold=p.attr("val")!="0"
                "color"->if(section=="fonts")color=p.attr("rgb")?.takeLast(6) ?: ""
                "fill"->fill="";"fgColor"->if(section=="fills")fill=p.attr("rgb")?.takeLast(6) ?: ""
                "xf"->if(section=="cellXfs")styles+=Style(p.attr("numFmtId")?.toIntOrNull() ?: 0,p.attr("fontId")?.toIntOrNull() ?: 0,p.attr("fillId")?.toIntOrNull() ?: 0)
            };XP.END_TAG->when(p.name){"font"->fonts+=bold to color;"fill"->fills+=fill;"fonts","fills","cellXfs"->section=""}}}
            return names.map{(name,path)->
                val cells=linkedMapOf<Pair<Int,Int>,SheetCell>();val merges=mutableListOf<IntArray>()
                var pos=0 to 0;var type="";var style=0;var field="";var value=StringBuilder();var formula=StringBuilder();var inline=StringBuilder();var rows=0;var cols=0;var limited=false
                parse(path){p->when(p.eventType){XP.START_TAG->when(p.name){
                    "c"->{pos=address(p.attr("r") ?: "A1");type=p.attr("t") ?: "";style=p.attr("s")?.toIntOrNull() ?: 0;value=StringBuilder();formula=StringBuilder();inline=StringBuilder()}
                    "v","f","t"->field=p.name
                    "mergeCell"->{val range=p.attr("ref")?.split(':');if(range?.size==2){val a=address(range[0]);val b=address(range[1]);merges+=intArrayOf(a.first,a.second,b.first,b.second)}}
                };XP.TEXT->when(field){"v"->value.append(p.text);"f"->formula.append(p.text);"t"->inline.append(p.text)}
                XP.END_TAG->when(p.name){"v","f","t"->field="";"c"->{
                    if(pos.first>=MAX_ROWS || pos.second>=MAX_COLS || cells.size>=MAX_CELLS)limited=true else {
                        val st=styles.getOrNull(style);val font=fonts.getOrNull(st?.font ?: 0)
                        val raw=value.toString();val text=when(type){"s"->strings.getOrNull(raw.toIntOrNull() ?: -1) ?: "";"inlineStr"->inline.toString();"b"->if(raw=="1")"TRUE" else "FALSE";"str","e"->raw;else->format(raw,st?.format ?: 0,formats[st?.format],date1904)}
                        cells[pos]=SheetCell(if(text.isEmpty() && formula.isNotEmpty())"Нет сохранённого результата" else text,font?.first==true,fills.getOrNull(st?.fill ?: 0) ?: "",font?.second ?: "")
                        rows=maxOf(rows,pos.first+1);cols=maxOf(cols,pos.second+1)
                    }
                }}}
                }
                SheetPage(name,cells,rows,cols,merges,limited)
            }
        }
    }
    private fun format(raw:String,id:Int,custom:String?,date1904:Boolean):String {
        val n=raw.toDoubleOrNull() ?: return raw
        val code=custom?.lowercase()?.replace(Regex("\"[^\"]*\""),"") ?: ""
        if(id in 14..22 || id in 45..47 || code.contains('y') || code.contains('d') || code.contains("h:")) {
            val base=if(date1904)-2082844800000L else -2209161600000L
            val timeOnly=id in 18..21 || id in 45..47 || (!code.contains('d') && !code.contains('y') && code.contains('h'))
            val pattern=if(timeOnly)"HH:mm:ss" else if(id==22 || code.contains('h'))"dd.MM.yyyy HH:mm" else "dd.MM.yyyy"
            return SimpleDateFormat(pattern,Locale.ROOT).apply{timeZone=TimeZone.getTimeZone("UTC")}.format(Date(base+(n*86400000).toLong()))
        }
        val pattern=when{id==9->"0%";id==10->"0.00%";id==2->"0.00";id==3->"#,##0";id==4->"#,##0.00";custom!=null && custom.matches(Regex("[0#,.%]+"))->custom;else->null}
        return if(pattern!=null)runCatching{DecimalFormat(pattern).format(n)}.getOrDefault(raw) else raw
    }
    fun html(sheet:SheetPage,dark:Boolean):String {
        fun esc(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
        fun color(s:String)=if(s.matches(Regex("[a-fA-F0-9]{6}")))"#$s" else ""
        val covered=HashSet<Pair<Int,Int>>();val spans=mutableMapOf<Pair<Int,Int>,Pair<Int,Int>>()
        for(m in sheet.merges){if(m[0]>=sheet.rows || m[1]>=sheet.columns)continue;val bottom=minOf(m[2],sheet.rows-1);val right=minOf(m[3],sheet.columns-1);if((bottom-m[0]+1L)*(right-m[1]+1)>MAX_CELLS)continue
            spans[m[0] to m[1]]=(bottom-m[0]+1) to (right-m[1]+1)
            for(r in m[0]..bottom)for(c in m[1]..right)if(r!=m[0] || c!=m[1])covered+=r to c
        }
        return buildString {
            append("<!doctype html><html><head><meta name='viewport' content='width=device-width, minimum-scale=0.01, maximum-scale=10, user-scalable=yes'><style>body{margin:0;font:14px sans-serif;background:${if(dark)"#151515" else "#fff"};color:${if(dark)"#eee" else "#222"}}table{border-collapse:separate;border-spacing:0}td,th{border-right:1px solid #8886;border-bottom:1px solid #8886;padding:9px;min-width:100px;max-width:480px;white-space:pre-wrap;overflow-wrap:anywhere}th{position:sticky;top:0;background:${if(dark)"#303030" else "#eef0f4"};z-index:2}th:first-child{left:0;min-width:36px}td:first-child{position:sticky;left:0;min-width:36px;background:${if(dark)"#303030" else "#eef0f4"}}</style></head><body>")
            if(sheet.limited)append("<p>Показана часть большой таблицы (до 10 000 строк, 256 столбцов и 100 000 ячеек).</p>")
            if(sheet.rows==0 || sheet.columns==0)append("<p>Пустой лист</p>") else {
                append("<table><tr><th></th>");for(c in 0 until sheet.columns)append("<th>${columnName(c)}</th>");append("</tr>")
                // Bound generated HTML as well as populated cell count for sparse workbooks.
                val displayRows=minOf(sheet.rows,MAX_CELLS/maxOf(1,sheet.columns))
                for(r in 0 until displayRows){append("<tr><td>${r+1}</td>");for(c in 0 until sheet.columns){val key=r to c;if(key in covered)continue;val cell=sheet.cells[key] ?: SheetCell("");val span=spans[key];append("<td");if(span!=null)append(" rowspan='${span.first}' colspan='${span.second}'");append(" style='");if(cell.bold)append("font-weight:bold;");val bg=color(cell.background);val fg=color(cell.color);if(bg.isNotEmpty())append("background:$bg;color:${if(fg.isEmpty())"#222" else fg};") else if(fg.isNotEmpty() && !dark)append("color:$fg;");append("'>${esc(cell.text)}</td>")};append("</tr>")}
                append("</table>");if(displayRows<sheet.rows)append("<p>Показаны первые $displayRows строк этого широкого листа.</p>")
            }
            append("</body></html>")
        }
    }
}
