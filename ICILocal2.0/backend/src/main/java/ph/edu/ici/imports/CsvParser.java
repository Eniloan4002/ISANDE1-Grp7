package ph.edu.ici.imports;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import ph.edu.ici.imports.ImportFileException.FileError;

/** RFC 4180 reader driven by stored settings. Decoding is strict: bytes invalid for the configured encoding fail the file. */
public final class CsvParser {
    /** Row numbers are 1-based record positions in the file (header and blank records included). */
    /** @param meta optional per-cell metadata keyed by zero-based column (empty for CSV); the two-argument constructor keeps existing callers compiling */
    public record Record(int number,List<String> cells,java.util.Map<Integer,CellMeta> meta) {
        public Record(int number,List<String> cells){this(number,cells,java.util.Map.of());}
    }
    public record Parsed(List<String> headers,List<Record> rows) {}
    private final ImportLimits limits;
    public CsvParser(ImportLimits limits){this.limits=limits;}

    public Parsed parse(byte[] bytes,ImportMapping.Settings settings){
        if(bytes.length>limits.maxFileBytes())throw new ImportFileException(FileError.FILE_TOO_LARGE,"The file is larger than "+limits.maxFileBytes()+" bytes.");
        if(bytes.length==0)throw new ImportFileException(FileError.EMPTY_FILE,"The file is empty.");
        int offset=0;
        if(bytes.length>=2&&((bytes[0]&0xFF)==0xFF&&(bytes[1]&0xFF)==0xFE||(bytes[0]&0xFF)==0xFE&&(bytes[1]&0xFF)==0xFF))
            throw new ImportFileException(FileError.INVALID_ENCODING,"UTF-16 files are not supported. Export as "+settings.encoding()+" or change the mapping's encoding.");
        if(bytes.length>=3&&(bytes[0]&0xFF)==0xEF&&(bytes[1]&0xFF)==0xBB&&(bytes[2]&0xFF)==0xBF)offset=3;
        String text;
        try{
            text=Charset.forName(settings.encoding()).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes,offset,bytes.length-offset)).toString();
        }catch(CharacterCodingException e){throw new ImportFileException(FileError.INVALID_ENCODING,"The file contains bytes that are not valid "+settings.encoding()+". Check the mapping's encoding.");}
        catch(IllegalArgumentException e){throw new ImportFileException(FileError.INVALID_ENCODING,"Unsupported encoding in the mapping.");}
        return read(text,settings);
    }

    private Parsed read(String text,ImportMapping.Settings settings){
        char delimiter=settings.delimiter();int n=text.length();
        List<String> headers=null;List<Record> rows=new ArrayList<>();
        List<String> cells=new ArrayList<>();StringBuilder cell=new StringBuilder();
        boolean quoted=false,afterQuote=false,atStart=true;int number=1;
        for(int i=0;i<=n;i++){
            boolean eof=i==n;char c=eof?'\n':text.charAt(i);
            if(quoted){
                if(eof)throw new ImportFileException(FileError.MALFORMED_CSV,"A quoted value in row "+number+" is never closed.");
                if(c=='"'){if(i+1<n&&text.charAt(i+1)=='"'){cell.append('"');cap(cell);i++;}else{quoted=false;afterQuote=true;}}
                else{cell.append(c);cap(cell);}
                continue;
            }
            if(c==delimiter&&!eof){cells.add(cell.toString());cell.setLength(0);atStart=true;afterQuote=false;continue;}
            if(c=='\r'||c=='\n'){
                if(c=='\r'&&i+1<n&&text.charAt(i+1)=='\n')i++;
                boolean blank=cells.isEmpty()&&atStart&&!afterQuote;
                if(!blank){
                    cells.add(cell.toString());
                    if(cells.size()>limits.maxColumns())throw new ImportFileException(FileError.TOO_MANY_COLUMNS,"Row "+number+" has more than "+limits.maxColumns()+" columns.");
                    if(number==settings.headerRow())headers=List.copyOf(cells);
                    else if(number>settings.headerRow()){
                        if(rows.size()>=limits.maxRows())throw new ImportFileException(FileError.TOO_MANY_ROWS,"The file has more than "+limits.maxRows()+" data rows.");
                        rows.add(new Record(number,List.copyOf(cells)));
                    }
                }
                if(!eof)number++;
                cells=new ArrayList<>();cell.setLength(0);atStart=true;afterQuote=false;continue;
            }
            if(afterQuote)throw new ImportFileException(FileError.MALFORMED_CSV,"Unexpected text after a closing quote in row "+number+".");
            if(c=='"'&&atStart){quoted=true;atStart=false;continue;}
            cell.append(c);cap(cell);atStart=false;
        }
        if(headers==null)throw new ImportFileException(FileError.EMPTY_FILE,"The header row was not found.");
        if(rows.isEmpty())throw new ImportFileException(FileError.EMPTY_FILE,"The file has a header but no data rows.");
        return new Parsed(headers,rows);
    }
    private void cap(StringBuilder cell){if(cell.length()>limits.maxCellChars())throw new ImportFileException(FileError.CELL_TOO_LONG,"A value is longer than "+limits.maxCellChars()+" characters; the delimiter or quoting is probably wrong.");}
}
