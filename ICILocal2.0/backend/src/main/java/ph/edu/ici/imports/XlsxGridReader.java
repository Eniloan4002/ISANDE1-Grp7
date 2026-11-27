package ph.edu.ici.imports;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTCol;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTCols;
import org.springframework.stereotype.Component;
import ph.edu.ici.imports.ImportFileException.FileError;

/**
 * {@link GridReader} for .xlsx files on Apache POI (ICI2-39). It produces the same grid as the CSV reader (header row, then one record per
 * non-blank row with the sheet's own row number) plus {@link CellMeta} for cells that were not plain text, and fails closed on anything it
 * will not interpret: legacy .xls or encrypted files, macros, external links and embedded objects, more than one sheet without a configured
 * sheet name, hidden sheets, rows or columns, and merged cells. Formulas are never evaluated (the stored result is read and flagged) and the
 * formula text is never kept. Numbers are read from the stored value, never from locale-dependent display text. Zip limits are POI's own
 * {@code ZipSecureFile} guards, counted on actually inflated bytes. Messages never echo cell values.
 */
@Component
public final class XlsxGridReader implements GridReader {
    private final ImportLimits limits;private final XlsxLimits xlsx;

    public XlsxGridReader(ImportLimits limits,XlsxLimits xlsx){this.limits=limits;this.xlsx=xlsx;}

    /**
     * ZipSecureFile's limits are process-wide statics; nothing else in this backend uses POI. They are applied before every read so a reader
     * always opens files under its own limits.
     */
    private static void applyZipLimits(XlsxLimits x){
        ZipSecureFile.setMinInflateRatio(x.minInflateRatio());ZipSecureFile.setMaxEntrySize(x.maxEntryBytes());ZipSecureFile.setMaxTextSize(x.maxTextChars());ZipSecureFile.setMaxFileCount(x.maxEntries());
    }

    /** What the bytes are, by magic number only (never the file name or the client's content type). */
    public enum Kind {XLSX,LEGACY_OR_ENCRYPTED,OTHER}
    public static Kind sniff(byte[] bytes){
        if(bytes==null||bytes.length<8)return Kind.OTHER;
        try{
            FileMagic magic=FileMagic.valueOf(bytes);
            return magic==FileMagic.OOXML?Kind.XLSX:magic==FileMagic.OLE2?Kind.LEGACY_OR_ENCRYPTED:Kind.OTHER;
        }catch(RuntimeException e){return Kind.OTHER;}
    }

    /**
     * The bytes of an uploaded .xlsx file. Anything that is neither a zip-based workbook nor a legacy/encrypted Office file is 415, decided by
     * magic bytes; a legacy or encrypted file goes on to staging, which records it as a failed batch with UNSUPPORTED_FORMAT.
     */
    static byte[] upload(org.springframework.web.multipart.MultipartFile file){
        byte[] bytes;
        try{bytes=file.getBytes();}catch(java.io.IOException e){throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"The file could not be read.");}
        if(bytes.length>0&&sniff(bytes)==Kind.OTHER)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE,"Upload an .xlsx workbook.");
        return bytes;
    }

    @Override public String format(){return "xlsx";}
    @Override public CsvParser.Parsed read(byte[] bytes,ImportMapping.Settings settings){return read(bytes,settings,null);}

    @Override public CsvParser.Parsed read(byte[] bytes,ImportMapping.Settings settings,String sheetName){
        if(bytes.length>limits.maxFileBytes())throw new ImportFileException(FileError.FILE_TOO_LARGE,"The file is larger than "+limits.maxFileBytes()+" bytes.");
        if(bytes.length==0)throw new ImportFileException(FileError.EMPTY_FILE,"The file is empty.");
        switch(sniff(bytes)){
            case LEGACY_OR_ENCRYPTED->throw new ImportFileException(FileError.UNSUPPORTED_FORMAT,"The file is a legacy .xls workbook or a password-protected workbook. Save it as an unprotected .xlsx workbook.");
            case OTHER->throw new ImportFileException(FileError.MALFORMED_XLSX,"The file is not an .xlsx workbook.");
            default->{}
        }
        synchronized(XlsxGridReader.class){return open(bytes,settings,sheetName);}
    }

    private CsvParser.Parsed open(byte[] bytes,ImportMapping.Settings settings,String sheetName){
        applyZipLimits(xlsx);
        OPCPackage pkg=null;
        try{
            pkg=OPCPackage.open(new ByteArrayInputStream(bytes));
            checkParts(pkg);
            XSSFWorkbook workbook=new XSSFWorkbook(pkg);
            return grid(workbook,selectSheet(workbook,sheetName),settings);
        }catch(ImportFileException e){throw e;}
        catch(Exception e){
            if(limitExceeded(e))throw new ImportFileException(FileError.XLSX_LIMIT_EXCEEDED,"The workbook expands beyond the allowed size or number of parts. Nothing was stored.");
            throw new ImportFileException(FileError.MALFORMED_XLSX,"The workbook could not be read as a valid .xlsx file.");
        }finally{
            if(pkg!=null)try{pkg.revert();}catch(RuntimeException ignored){}   // read-only use: discard, never save back
        }
    }

    /** POI reports its zip guards as IOExceptions (sometimes wrapped); recognised by message because they have no type of their own. */
    private static boolean limitExceeded(Throwable e){
        for(Throwable t=e;t!=null;t=t.getCause()){
            String m=String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
            if(m.contains("zip bomb")||m.contains("max entry size")||m.contains("max text size")||m.contains("max file count")||m.contains("maxfilecount")||m.contains("exceeds the max"))return true;
        }
        return false;
    }

    /** Macros, external links, data connections, query tables, embedded objects and ActiveX fail the file before any cell is read. */
    private void checkParts(OPCPackage pkg)throws Exception{
        var parts=pkg.getParts();
        if(parts.size()>xlsx.maxEntries())throw new ImportFileException(FileError.XLSX_LIMIT_EXCEEDED,"The workbook has more than "+xlsx.maxEntries()+" parts. Nothing was stored.");
        for(PackagePart part:parts){
            String name=part.getPartName().getName().toLowerCase(Locale.ROOT);String type=String.valueOf(part.getContentType()).toLowerCase(Locale.ROOT);
            if(name.endsWith("vbaproject.bin")||type.contains("macroenabled")||type.contains("vbaproject"))
                throw new ImportFileException(FileError.MACRO_ENABLED,"The workbook contains macros. Save it as a plain .xlsx workbook without macros.");
            if(name.startsWith("/xl/externallinks/")||name.equals("/xl/connections.xml")||name.startsWith("/xl/querytables/")||name.startsWith("/xl/embeddings/")||name.startsWith("/xl/activex/"))
                throw new ImportFileException(FileError.EXTERNAL_CONTENT,"The workbook contains external links, data connections or embedded objects. Copy the values into a plain workbook.");
        }
    }

    private XSSFSheet selectSheet(XSSFWorkbook workbook,String sheetName){
        int count=workbook.getNumberOfSheets();List<String> names=new ArrayList<>();
        for(int i=0;i<count;i++)names.add(workbook.getSheetName(i));
        if(count==0)throw new ImportFileException(FileError.EMPTY_FILE,"The workbook has no sheets.");
        for(int i=0;i<count;i++)if(workbook.isSheetHidden(i)||workbook.isSheetVeryHidden(i))
            throw new ImportFileException(FileError.HIDDEN_CONTENT,"The workbook has a hidden sheet ('"+ImportText.excerpt(names.get(i))+"'). Unhide or delete it; hidden content is never imported.");
        if(sheetName==null||sheetName.isBlank()){
            if(count>1)throw new ImportFileException(FileError.MULTIPLE_SHEETS,"The workbook has "+count+" sheets "+excerpts(names)+". Keep one sheet, or name the sheet in the mapping's settings.sheet.");
            return workbook.getSheetAt(0);
        }
        int index=names.indexOf(sheetName.trim());
        if(index<0)throw new ImportFileException(FileError.SHEET_NOT_FOUND,"The mapping names a sheet the workbook does not have. Sheets: "+excerpts(names)+".");
        return workbook.getSheetAt(index);
    }
    private static List<String> excerpts(List<String> names){return names.stream().map(ImportText::excerpt).toList();}

    private CsvParser.Parsed grid(XSSFWorkbook workbook,XSSFSheet sheet,ImportMapping.Settings settings){
        if(sheet.getNumMergedRegions()>0)throw new ImportFileException(FileError.MERGED_CELLS,"The sheet has merged cells. Unmerge them so every value sits in its own cell.");
        for(CTCols cols:sheet.getCTWorksheet().getColsList())for(CTCol col:cols.getColList())
            if(col.isSetHidden()&&col.getHidden())throw new ImportFileException(FileError.HIDDEN_CONTENT,"The sheet has hidden columns. Unhide or delete them; hidden content is never imported.");
        boolean date1904=workbook.isDate1904();
        List<String> headers=null;List<CsvParser.Record> rows=new ArrayList<>();int cells=0;
        for(Row row:sheet){
            int number=row.getRowNum()+1;
            if(row.getZeroHeight())throw new ImportFileException(FileError.HIDDEN_CONTENT,"Row "+number+" is hidden. Unhide or delete it; hidden content is never imported.");
            TreeMap<Integer,String> values=new TreeMap<>();Map<Integer,CellMeta> meta=new HashMap<>();
            for(Cell cell:row){
                var value=value((XSSFCell)cell,date1904);
                if(value==null)continue;
                int column=cell.getColumnIndex();
                if(column>=limits.maxColumns())throw new ImportFileException(FileError.TOO_MANY_COLUMNS,"Row "+number+" has a value beyond column "+limits.maxColumns()+".");
                if(value.text().length()>limits.maxCellChars())throw new ImportFileException(FileError.CELL_TOO_LONG,"A value in row "+number+" is longer than "+limits.maxCellChars()+" characters.");
                if(++cells>xlsx.maxCells())throw new ImportFileException(FileError.TOO_MANY_CELLS,"The sheet has more than "+xlsx.maxCells()+" non-empty cells.");
                values.put(column,value.text());if(value.meta()!=null)meta.put(column,value.meta());
            }
            if(values.isEmpty()||number<settings.headerRow())continue;   // blank rows are skipped but keep their number, as in CSV
            int width=Math.max(values.lastKey()+1,headers==null?0:headers.size());
            List<String> line=new ArrayList<>(width);for(int i=0;i<width;i++)line.add(values.getOrDefault(i,""));
            if(number==settings.headerRow()){headers=List.copyOf(line);continue;}
            if(headers==null)throw new ImportFileException(FileError.EMPTY_FILE,"The header row ("+settings.headerRow()+") is empty or missing.");
            if(rows.size()>=limits.maxRows())throw new ImportFileException(FileError.TOO_MANY_ROWS,"The sheet has more than "+limits.maxRows()+" data rows.");
            rows.add(new CsvParser.Record(number,List.copyOf(line),Map.copyOf(meta)));
        }
        if(headers==null)throw new ImportFileException(FileError.EMPTY_FILE,"The header row ("+settings.headerRow()+") is empty or missing.");
        if(rows.isEmpty())throw new ImportFileException(FileError.EMPTY_FILE,"The sheet has a header but no data rows.");
        return new CsvParser.Parsed(headers,rows);
    }

    private record Value(String text,CellMeta meta) {}

    /** The grid text of one cell and its metadata (null meta for plain text); null for a blank cell. */
    private static Value value(XSSFCell cell,boolean date1904){
        CellType type=cell.getCellType();boolean formula=type==CellType.FORMULA;
        if(formula){
            if(!cell.getCTCell().isSetV())return new Value("",new CellMeta(CellMeta.Kind.TEXT,CellMeta.Format.NONE,false,false).asFormula(true));
            type=cell.getCachedFormulaResultType();
        }
        Value v=switch(type){
            case STRING->{String s=cell.getStringCellValue();yield s==null||s.isEmpty()?null:new Value(s,null);}
            case BOOLEAN->new Value(cell.getBooleanCellValue()?"TRUE":"FALSE",new CellMeta(CellMeta.Kind.BOOLEAN,CellMeta.Format.NONE,false,false));
            case ERROR->new Value(errorText(cell),CellMeta.error());
            case NUMERIC->numeric(cell,date1904);
            default->null;
        };
        if(v==null)return formula?new Value("",new CellMeta(CellMeta.Kind.TEXT,CellMeta.Format.NONE,false,false).asFormula(false)):null;
        if(!formula)return v;
        CellMeta m=v.meta()==null?new CellMeta(CellMeta.Kind.TEXT,CellMeta.Format.NONE,false,false):v.meta();
        return new Value(v.text(),m.asFormula(false));
    }
    private static String errorText(XSSFCell cell){try{return cell.getErrorCellString();}catch(RuntimeException e){return "#ERROR";}}

    /**
     * A number from its stored value, written as a plain decimal (no exponent, no trailing zeros; 1.2345E11 becomes 123450000000). A cell whose
     * format is a date is converted to ISO yyyy-MM-dd with the workbook's date system; 1900-system serials below 61 (the leap-year bug range) are
     * not converted and stay a number formatted as a date, so date fields refuse them instead of guessing.
     */
    private static Value numeric(XSSFCell cell,boolean date1904){
        String raw=cell.getRawValue();
        BigDecimal number;
        try{number=new BigDecimal(raw==null?Double.toString(cell.getNumericCellValue()):raw.trim());}
        catch(NumberFormatException e){return new Value(raw==null?"":raw,CellMeta.error());}
        String plain=number.signum()==0?"0":number.stripTrailingZeros().toPlainString();
        String format=String.valueOf(cell.getCellStyle().getDataFormatString());
        if(DateUtil.isCellDateFormatted(cell)){
            double serial=number.doubleValue();
            if(!date1904&&serial<61||serial<0)return new Value(plain,CellMeta.number(CellMeta.Format.DATE));
            LocalDateTime at=DateUtil.getLocalDateTime(serial,date1904);
            return at==null?new Value(plain,CellMeta.number(CellMeta.Format.DATE)):new Value(at.toLocalDate().toString(),CellMeta.date());
        }
        if(format.contains("%"))return new Value(plain,CellMeta.number(CellMeta.Format.PERCENT));
        return new Value(plain,CellMeta.number("General".equalsIgnoreCase(format)?CellMeta.Format.NONE:CellMeta.Format.OTHER));
    }
}
