package ph.edu.ici.imports;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Synthetic .xlsx workbooks written with Apache POI in test code (no binary fixtures are checked in). The few cases POI cannot write
 * (macro parts, external links, zip bombs, DOCTYPE parts) are produced by {@link #rezip}, which rewrites the zip of a POI-written workbook.
 */
final class XlsxFixtures {
    private XlsxFixtures(){}

    /** A workbook built by {@code body}, as bytes. */
    static byte[] workbook(Consumer<XSSFWorkbook> body){
        try(var wb=new XSSFWorkbook();var out=new ByteArrayOutputStream()){body.accept(wb);wb.write(out);return out.toByteArray();}
        catch(IOException e){throw new UncheckedIOException(e);}
    }

    /** One sheet named "Sheet1" with these rows from row 1. Cells: String, Number, Boolean, LocalDate (date-formatted), or null (no cell). */
    static byte[] sheet(List<List<Object>> rows){return workbook(wb->fill(wb,wb.createSheet("Sheet1"),rows));}
    static byte[] sheet(Object[]... rows){List<List<Object>> list=new ArrayList<>();for(Object[] r:rows)list.add(r==null?null:Arrays.asList(r));return sheet(list);}

    static void fill(XSSFWorkbook wb,XSSFSheet sheet,List<List<Object>> rows){
        CellStyle date=wb.createCellStyle();date.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
        for(int r=0;r<rows.size();r++){
            var values=rows.get(r);if(values==null)continue;Row row=sheet.createRow(r);
            for(int c=0;c<values.size();c++){
                Object v=values.get(c);if(v==null)continue;Cell cell=row.createCell(c);
                if(v instanceof String s)cell.setCellValue(s);
                else if(v instanceof Number n)cell.setCellValue(n.doubleValue());
                else if(v instanceof Boolean b)cell.setCellValue(b);
                else if(v instanceof LocalDate d){cell.setCellValue(d);cell.setCellStyle(date);}
                else throw new IllegalArgumentException(String.valueOf(v));
            }
        }
    }

    /** A copy of {@code xlsx} with entries added or replaced, and its [Content_Types].xml rewritten by {@code contentTypes}. */
    static byte[] rezip(byte[] xlsx,Map<String,byte[]> add,UnaryOperator<String> contentTypes){
        try(var in=new ZipInputStream(new ByteArrayInputStream(xlsx));var buffer=new ByteArrayOutputStream();var out=new ZipOutputStream(buffer)){
            Set<String> written=new HashSet<>();
            for(ZipEntry e;(e=in.getNextEntry())!=null;){
                byte[] data=in.readAllBytes();String name=e.getName();
                if(add.containsKey(name))data=add.get(name);
                if(name.equals("[Content_Types].xml"))data=contentTypes.apply(new String(data,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                out.putNextEntry(new ZipEntry(name));out.write(data);out.closeEntry();written.add(name);
            }
            for(var e:add.entrySet())if(!written.contains(e.getKey())){out.putNextEntry(new ZipEntry(e.getKey()));out.write(e.getValue());out.closeEntry();}
            out.finish();return buffer.toByteArray();
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    /** Adds an Override content type for a part. */
    static UnaryOperator<String> override(String part,String type){return xml->xml.replace("</Types>","<Override PartName=\""+part+"\" ContentType=\""+type+"\"/></Types>");}
    /** The text of one zip entry. */
    static String entry(byte[] xlsx,String name){
        try(var in=new ZipInputStream(new ByteArrayInputStream(xlsx))){
            for(ZipEntry e;(e=in.getNextEntry())!=null;)if(e.getName().equals(name))return new String(in.readAllBytes(),StandardCharsets.UTF_8);
            throw new IllegalArgumentException(name);
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
}
