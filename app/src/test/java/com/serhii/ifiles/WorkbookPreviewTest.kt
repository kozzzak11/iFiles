package com.serhii.ifiles

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class WorkbookPreviewTest {
    private fun book(vararg entries:Pair<String,String>):File {
        val f=File.createTempFile("workbook-test-",".xlsx")
        ZipOutputStream(f.outputStream()).use{z->entries.forEach{(name,text)->z.putNextEntry(ZipEntry(name));z.write(text.toByteArray());z.closeEntry()}}
        return f
    }
    @Test fun xlsxPreservesColumnsSheetsCachedFormulaAndStyles() {
        val f=book(
            "xl/workbook.xml" to """<workbook xmlns:r="urn:rel"><sheets><sheet name="Расчёт" r:id="rId7"/><sheet name="Пустой" r:id="rId8"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="rId7" Target="worksheets/sheet3.xml"/><Relationship Id="rId8" Target="worksheets/sheet9.xml"/></Relationships>""",
            "xl/sharedStrings.xml" to "<sst><si><r><t>Привет </t></r><r><t>USB</t></r></si></sst>",
            "xl/styles.xml" to """<styleSheet><fonts><font/><font><b/></font></fonts><fills><fill/></fills><cellXfs><xf numFmtId="0"/><xf numFmtId="10" fontId="1"/><xf numFmtId="14"/></cellXfs></styleSheet>""",
            "xl/worksheets/sheet3.xml" to """<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1" s="1"><f>A2/100</f><v>0.25</v></c></row><row r="2"><c r="A2" s="2"><v>45292</v></c></row></sheetData><mergeCells><mergeCell ref="A3:C3"/></mergeCells></worksheet>""",
            "xl/worksheets/sheet9.xml" to "<worksheet><sheetData/></worksheet>")
        try {val sheets=Workbooks.read(f,"xlsx"){};assertEquals(listOf("Расчёт","Пустой"),sheets.map{it.name});val s=sheets[0];assertEquals("Привет USB",s.cells[0 to 0]?.text);assertNull(s.cells[0 to 1]);assertEquals("25.00%",s.cells[0 to 2]?.text);assertTrue(s.cells[0 to 2]!!.bold);assertEquals("01.01.2024",s.cells[1 to 0]?.text);assertEquals(3,s.columns);assertFalse(Workbooks.html(s,false).contains("A2/100"))}finally{f.delete()}
    }
    @Test fun legacyXlsIsRenderedAsCells() {
        val f=File.createTempFile("legacy-",".xls")
        try {val w=jxl.Workbook.createWorkbook(f);val s=w.createSheet("Лист",0);s.addCell(jxl.write.Label(2,1,"Настоящий XLS"));w.write();w.close();val result=Workbooks.read(f,"xls"){}.single();assertEquals("Настоящий XLS",result.cells[1 to 2]?.text);assertEquals(3,result.columns)}finally{f.delete()}
    }
    @Test fun generatedHtmlEscapesCellContent() {
        val html=Workbooks.html(SheetPage("x",mapOf((0 to 0) to SheetCell("<script>alert(1)</script>")),1,1),false)
        assertFalse(html.contains("<script>"));assertTrue(html.contains("&lt;script&gt;"))
    }
    @Test fun queuePreservesMixedOrderAndRemovesFolders() {
        val image=Entry("file:///a","a.jpg",false);val pdf=Entry("file:///b","b.pdf",false);val video=Entry("file:///c","c.mp4",false)
        assertEquals(listOf(image,pdf,video),PreviewQueue.entries(pdf,listOf(image,Entry("file:///folder","folder",true),pdf,video)))
        assertEquals(listOf(pdf),PreviewQueue.entries(pdf,listOf(image)))
    }
    @Test fun docxKeepsParagraphTableAndFormattingWithoutXml() {
        val f=book("word/document.xml" to """<w:document xmlns:w="urn:word"><w:body><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Заголовок</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>Ячейка</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>""")
        try{val html=WordDocuments.html(f,false){};assertTrue(html.contains("<table>"));assertTrue(html.contains("font-weight:bold"));assertTrue(html.contains("Ячейка"));assertFalse(html.contains("w:document"))}finally{f.delete()}
    }
    @Test fun commonTypesNeverFallBackToBinary() {
        for(ext in listOf("doc","docx","xls","xlsx","pdf","jpg","heic","mp3","flac","opus","mp4","mkv","avi"))assertNotEquals(ext,"binary",PreviewFiles.kind(Entry("x","file.$ext",false),byteArrayOf(0,0,0,0)))
    }
}
