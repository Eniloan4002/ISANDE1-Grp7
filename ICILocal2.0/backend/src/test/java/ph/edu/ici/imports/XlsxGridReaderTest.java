package ph.edu.ici.imports;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.junit.jupiter.api.Test;
import ph.edu.ici.imports.ImportFileException.FileError;
import static org.assertj.core.api.Assertions.*;
import static ph.edu.ici.imports.XlsxFixtures.*;

/** The Apache POI .xlsx reader (ICI2-39 phase 5): grid conventions, cell typing and every fail-closed rule. No database. */
class XlsxGridReaderTest {
    static final ImportMapping.Settings SETTINGS=new ImportMapping.Settings("UTF-8",',',1,List.of(),false);
    final XlsxGridReader reader=new XlsxGridReader(ImportLimits.DEFAULTS,XlsxLimits.DEFAULTS);

    CsvParser.Parsed read(byte[] bytes){return reader.read(bytes,SETTINGS,null);}
    FileError error(byte[] bytes){return error(reader,bytes,null);}
    static FileError error(XlsxGridReader r,byte[] bytes,String sheet){
        var e=catchThrowableOfType(ImportFileException.class,()->r.read(bytes,SETTINGS,sheet));
        assertThat(e).as("expected a file error").isNotNull();
        return e.error();
    }

    @Test void readsHeadersAndRowsWithSheetRowNumbersPaddingAndBlankRowsSkipped(){
        var parsed=read(sheet(new Object[]{"Student No","Section","Remarks"},new Object[]{"S-1","Alpha"},null,new Object[]{"S-2","Beta","ok"},new Object[]{}));
        assertThat(parsed.headers()).containsExactly("Student No","Section","Remarks");
        assertThat(parsed.rows()).extracting(CsvParser.Record::number).containsExactly(2,4);
        assertThat(parsed.rows().get(0).cells()).containsExactly("S-1","Alpha","");   // padded to the header width
        assertThat(parsed.rows().get(1).cells()).containsExactly("S-2","Beta","ok");
        assertThat(parsed.rows().get(0).meta()).isEmpty();                              // plain text carries no metadata
        assertThat(reader.format()).isEqualTo("xlsx");
    }

    @Test void aValueBeyondTheHeaderWidthKeepsTheLongerRowSoStagingReportsAColumnMismatch(){
        var parsed=read(sheet(new Object[]{"A","B"},new Object[]{"1","2","extra"}));
        assertThat(parsed.rows().getFirst().cells()).hasSize(3);
    }

    @Test void numbersAreThePlainStoredValueAndCarryTheirFormat(){
        byte[] bytes=workbook(wb->{
            var s=wb.createSheet("Sheet1");var h=s.createRow(0);h.createCell(0).setCellValue("Id");h.createCell(1).setCellValue("Pct");h.createCell(2).setCellValue("Fixed");
            var r=s.createRow(1);r.createCell(0).setCellValue(123456789012d);
            var pct=wb.createCellStyle();pct.setDataFormat(wb.createDataFormat().getFormat("0%"));var c=r.createCell(1);c.setCellValue(0.85);c.setCellStyle(pct);
            var fixed=wb.createCellStyle();fixed.setDataFormat(wb.createDataFormat().getFormat("0.00"));var d=r.createCell(2);d.setCellValue(11);d.setCellStyle(fixed);
        });
        var row=read(bytes).rows().getFirst();
        assertThat(row.cells()).containsExactly("123456789012","0.85","11");
        assertThat(row.meta().get(0)).isEqualTo(CellMeta.number(CellMeta.Format.NONE));
        assertThat(row.meta().get(1)).isEqualTo(CellMeta.number(CellMeta.Format.PERCENT));
        assertThat(row.meta().get(2)).isEqualTo(CellMeta.number(CellMeta.Format.OTHER));
    }

    @Test void dateCellsBecomeIsoDatesInBothDateSystemsAndEarlySerialsAreNotGuessed(){
        var row=read(sheet(new Object[]{"Date"},new Object[]{LocalDate.of(2026,6,15)})).rows().getFirst();
        assertThat(row.cells()).containsExactly("2026-06-15");assertThat(row.meta().get(0)).isEqualTo(CellMeta.date());
        byte[] early=workbook(wb->{
            var s=wb.createSheet("Sheet1");s.createRow(0).createCell(0).setCellValue("Date");
            var style=wb.createCellStyle();style.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd"));var c=s.createRow(1).createCell(0);c.setCellValue(45);c.setCellStyle(style);
        });
        var e=read(early).rows().getFirst();
        assertThat(e.cells()).containsExactly("45");assertThat(e.meta().get(0)).isEqualTo(CellMeta.number(CellMeta.Format.DATE));
        byte[] system1904=workbook(wb->{
            var ct=wb.getCTWorkbook();(ct.isSetWorkbookPr()?ct.getWorkbookPr():ct.addNewWorkbookPr()).setDate1904(true);
            var s=wb.createSheet("Sheet1");s.createRow(0).createCell(0).setCellValue("Date");
            var style=wb.createCellStyle();style.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd"));var c=s.createRow(1).createCell(0);c.setCellValue(0);c.setCellStyle(style);
        });
        assertThat(read(system1904).rows().getFirst().cells()).containsExactly("1904-01-01");
    }

    @Test void formulasAreReadFromTheirStoredResultFlaggedAndNeverKept(){
        byte[] bytes=workbook(wb->{
            var s=wb.createSheet("Sheet1");var h=s.createRow(0);for(int i=0;i<4;i++)h.createCell(i).setCellValue("H"+i);
            var r=s.createRow(1);
            var cached=r.createCell(0);cached.setCellFormula("40+2");cached.setCellValue(42);
            var text=r.createCell(1);text.setCellFormula("\"A\"&\"B\"");text.setCellValue("AB");
            r.createCell(2).setCellFormula("1/0");                 // no stored result
            r.createCell(3).setCellErrorValue(FormulaError.NA);
        });
        var row=read(bytes).rows().getFirst();
        assertThat(row.cells()).containsExactly("42","AB","","#N/A");
        assertThat(row.meta().get(0)).isEqualTo(CellMeta.number().asFormula(false));
        assertThat(row.meta().get(1).formula()).isTrue();assertThat(row.meta().get(1).cachedMissing()).isFalse();
        assertThat(row.meta().get(2).formula()).isTrue();assertThat(row.meta().get(2).cachedMissing()).isTrue();
        assertThat(row.meta().get(3).kind()).isEqualTo(CellMeta.Kind.ERROR);
        assertThat(String.join("|",row.cells())).doesNotContain("40+2").doesNotContain("1/0");
    }

    @Test void richTextJoinsItsRunsAndBooleansReadAsWords(){
        byte[] bytes=workbook(wb->{
            var s=wb.createSheet("Sheet1");s.createRow(0).createCell(0).setCellValue("Name");s.getRow(0).createCell(1).setCellValue("Flag");
            var rich=new XSSFRichTextString("DELA CRUZ");XSSFFont bold=wb.createFont();bold.setBold(true);rich.applyFont(0,4,bold);
            var r=s.createRow(1);r.createCell(0).setCellValue(rich);r.createCell(1).setCellValue(true);
        });
        var row=read(bytes).rows().getFirst();
        assertThat(row.cells()).containsExactly("DELA CRUZ","TRUE");
        assertThat(row.meta().get(1).kind()).isEqualTo(CellMeta.Kind.BOOLEAN);
    }

    @Test void theSheetIsChosenByExactNameAndOtherwiseThereMustBeOnlyOne(){
        byte[] two=workbook(wb->{fill(wb,wb.createSheet("Notes"),List.of(List.of("X"),List.of("1")));fill(wb,wb.createSheet("Students"),List.of(List.of("Id"),List.of("S-9")));});
        assertThat(error(two)).isEqualTo(FileError.MULTIPLE_SHEETS);
        assertThat(reader.read(two,SETTINGS,"Students").rows().getFirst().cells()).containsExactly("S-9");
        assertThat(error(reader,two,"Missing")).isEqualTo(FileError.SHEET_NOT_FOUND);
    }

    @Test void hiddenSheetsRowsAndColumnsFailTheFile(){
        byte[] sheet=workbook(wb->{fill(wb,wb.createSheet("Data"),List.of(List.of("Id"),List.of("S-1")));fill(wb,wb.createSheet("Old"),List.of(List.of("Id")));wb.setSheetHidden(1,true);});
        assertThat(error(reader,sheet,"Data")).isEqualTo(FileError.HIDDEN_CONTENT);
        byte[] row=workbook(wb->{var s=wb.createSheet("Sheet1");fill(wb,s,List.of(List.of("Id"),List.of("S-1"),List.of("S-2")));s.getRow(2).setZeroHeight(true);});
        assertThat(error(row)).isEqualTo(FileError.HIDDEN_CONTENT);
        byte[] column=workbook(wb->{var s=wb.createSheet("Sheet1");fill(wb,s,List.of(List.of("Id","Secret"),List.of("S-1","x")));s.setColumnHidden(1,true);});
        assertThat(error(column)).isEqualTo(FileError.HIDDEN_CONTENT);
    }

    @Test void mergedCellsFailTheFile(){
        byte[] bytes=workbook(wb->{var s=wb.createSheet("Sheet1");fill(wb,s,List.of(List.of("Id","Section"),List.of("S-1","Alpha")));s.addMergedRegion(new CellRangeAddress(1,1,0,1));});
        assertThat(error(bytes)).isEqualTo(FileError.MERGED_CELLS);
    }

    @Test void macrosExternalLinksAndEmbeddedObjectsFailTheFile(){
        byte[] plain=sheet(new Object[]{"Id"},new Object[]{"S-1"});
        byte[] macro=rezip(plain,Map.of("xl/vbaProject.bin",new byte[]{1,2,3}),override("/xl/vbaProject.bin","application/vnd.ms-office.vbaProject"));
        assertThat(error(macro)).isEqualTo(FileError.MACRO_ENABLED);
        byte[] xlsm=rezip(plain,Map.of(),xml->xml.replace("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml","application/vnd.ms-excel.sheet.macroEnabled.main+xml"));
        assertThat(error(xlsm)).isIn(FileError.MACRO_ENABLED,FileError.MALFORMED_XLSX);   // either way it is refused
        byte[] link=rezip(plain,Map.of("xl/externalLinks/externalLink1.xml","<externalLink xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"/>".getBytes(StandardCharsets.UTF_8)),
            override("/xl/externalLinks/externalLink1.xml","application/vnd.openxmlformats-officedocument.spreadsheetml.externalLink+xml"));
        assertThat(error(link)).isEqualTo(FileError.EXTERNAL_CONTENT);
        byte[] embedded=rezip(plain,Map.of("xl/embeddings/oleObject1.bin",new byte[]{9}),xml->xml.replace("</Types>","<Default Extension=\"bin\" ContentType=\"application/vnd.openxmlformats-officedocument.oleObject\"/></Types>"));
        assertThat(error(embedded)).isEqualTo(FileError.EXTERNAL_CONTENT);
    }

    @Test void legacyEncryptedTruncatedAndNonWorkbookFilesAreRefused(){
        byte[] ole2=new byte[512];byte[] magic={(byte)0xD0,(byte)0xCF,0x11,(byte)0xE0,(byte)0xA1,(byte)0xB1,0x1A,(byte)0xE1};System.arraycopy(magic,0,ole2,0,8);
        assertThat(error(ole2)).isEqualTo(FileError.UNSUPPORTED_FORMAT);
        assertThat(XlsxGridReader.sniff(ole2)).isEqualTo(XlsxGridReader.Kind.LEGACY_OR_ENCRYPTED);
        assertThat(error("Student No,Section\r\n".getBytes(StandardCharsets.UTF_8))).isEqualTo(FileError.MALFORMED_XLSX);
        byte[] plain=sheet(new Object[]{"Id"},new Object[]{"S-1"});
        assertThat(error(Arrays.copyOf(plain,plain.length/2))).isEqualTo(FileError.MALFORMED_XLSX);
        assertThat(error(new byte[0])).isEqualTo(FileError.EMPTY_FILE);
    }

    @Test void aDoctypeInAnyPartIsRefusedAndNoEntityIsExpanded(){
        byte[] plain=sheet(new Object[]{"Id"},new Object[]{"S-1"});
        String workbook=entry(plain,"xl/workbook.xml");
        String evil=workbook.replaceFirst("\\?>","?><!DOCTYPE workbook [<!ENTITY boom \"boom\">]>");
        assertThat(error(rezip(plain,Map.of("xl/workbook.xml",evil.getBytes(StandardCharsets.UTF_8)),x->x))).isEqualTo(FileError.MALFORMED_XLSX);
        String sheet=entry(plain,"xl/worksheets/sheet1.xml");
        String evilSheet=sheet.replaceFirst("\\?>","?><!DOCTYPE worksheet [<!ENTITY boom SYSTEM \"file:///etc/passwd\">]>");
        assertThat(error(rezip(plain,Map.of("xl/worksheets/sheet1.xml",evilSheet.getBytes(StandardCharsets.UTF_8)),x->x))).isEqualTo(FileError.MALFORMED_XLSX);
    }

    @Test void zipBombsAndOversizedPartsAreRefusedBeforeAnythingIsStored(){
        byte[] plain=sheet(new Object[]{"Id"},new Object[]{"S-1"});
        byte[] bomb=rezip(plain,Map.of("xl/media/padding.bin",new byte[20_000_000]),x->x);   // 20 MB of zeros compresses about 1000:1
        assertThat(error(bomb)).isEqualTo(FileError.XLSX_LIMIT_EXCEEDED);
        var small=new XlsxGridReader(ImportLimits.DEFAULTS,new XlsxLimits(200,50_000,0.0001,10_000_000,250_000));
        List<List<Object>> rows=new ArrayList<>();rows.add(List.of("Id","Name"));for(int i=0;i<3_000;i++)rows.add(List.of("S-"+i,"Student "+i));
        assertThat(error(small,sheet(rows),null)).isEqualTo(FileError.XLSX_LIMIT_EXCEEDED);
        assertThat(read(sheet(rows)).rows()).hasSize(3_000);   // the default limits accept the same file
        var fewParts=new XlsxGridReader(ImportLimits.DEFAULTS,new XlsxLimits(3,10_000_000,0.01,10_000_000,250_000));
        assertThat(error(fewParts,plain,null)).isEqualTo(FileError.XLSX_LIMIT_EXCEEDED);
    }

    @Test void gridLimitsCountCellsActuallyPresent(){
        byte[] far=workbook(wb->{var s=wb.createSheet("Sheet1");s.createRow(0).createCell(0).setCellValue("Id");s.createRow(1).createCell(16_383).setCellValue("x");});   // column XFD
        assertThat(error(far)).isEqualTo(FileError.TOO_MANY_COLUMNS);
        var tiny=new XlsxGridReader(new ImportLimits(5_000_000,2,64,10),new XlsxLimits(200,10_000_000,0.01,10_000_000,5));
        assertThat(error(tiny,sheet(new Object[]{"Id"},new Object[]{"1"},new Object[]{"2"},new Object[]{"3"}),null)).isEqualTo(FileError.TOO_MANY_ROWS);
        assertThat(error(tiny,sheet(new Object[]{"Id"},new Object[]{"far too long value"}),null)).isEqualTo(FileError.CELL_TOO_LONG);
        assertThat(error(tiny,sheet(new Object[]{"A","B","C"},new Object[]{"1","2","3"}),null)).isEqualTo(FileError.TOO_MANY_CELLS);
        assertThat(error(sheet(new Object[]{"Id"}))).isEqualTo(FileError.EMPTY_FILE);
    }
}
