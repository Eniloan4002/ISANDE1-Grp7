package ph.edu.ici.reporting;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** PDFBox 3 font construction follows https://pdfbox.apache.org/3.0/migration.html. */
public final class ReportDocuments {
    private ReportDocuments(){}
    public static String csvCell(Object value){
        String s=value==null?"":value.toString();
        String trimmed=s;
        while(!trimmed.isEmpty()&&(Character.isWhitespace(trimmed.codePointAt(0))||Character.isISOControl(trimmed.codePointAt(0))||Character.getType(trimmed.codePointAt(0))==Character.FORMAT))trimmed=trimmed.substring(Character.charCount(trimmed.codePointAt(0)));
        if((!trimmed.isEmpty()&&"=+@-".indexOf(trimmed.charAt(0))>=0)||s.startsWith("\t")||s.startsWith("\r")||s.startsWith("\n"))s="'"+s;
        return "\""+s.replace("\"","\"\"")+"\"";
    }
    public static byte[] csv(List<String> headers,List<List<Object>> rows){
        var out=new StringBuilder();out.append(String.join(",",headers.stream().map(ReportDocuments::csvCell).toList())).append("\r\n");
        for(var row:rows)out.append(String.join(",",row.stream().map(ReportDocuments::csvCell).toList())).append("\r\n");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
    static String printable(String text,PDFont font)throws IOException{
        var out=new StringBuilder();
        for(int cp:text.codePoints().toArray()){
            if(cp=='\n'||cp=='\r'||cp=='\t'){out.append(' ');continue;}
            String s=new String(Character.toChars(cp));
            try{font.encode(s);out.append(s);}catch(IllegalArgumentException e){out.append("[U+").append(Integer.toHexString(cp).toUpperCase(Locale.ROOT)).append(']');}
        }
        return out.toString();
    }
    public static byte[] pdf(String title,List<String> lines){
        try(var document=new PDDocument();var bytes=new ByteArrayOutputStream()){
            PDFont font;
            try(var stream=ReportDocuments.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")){
                if(stream==null)throw new IOException("Bundled report font is unavailable");
                font=PDType0Font.load(document,stream);
            }
            var source=new ArrayList<String>();source.add(title);source.add("Generated at: "+java.time.Instant.now()+" (UTC)");source.add("");source.addAll(lines);
            var wrapped=new ArrayList<String>();
            float usableWidth=new PDPage().getMediaBox().getWidth()-72;
            for(String raw:source)for(String paragraph:raw.split("\\R",-1)){
                var line=new StringBuilder();float width=0;
                for(int cp:printable(paragraph,font).codePoints().toArray()){
                    String glyph=new String(Character.toChars(cp));float glyphWidth=font.getStringWidth(glyph)*9/1000;
                    if(width+glyphWidth>usableWidth&&!line.isEmpty()){wrapped.add(line.toString());line.setLength(0);width=0;}
                    line.append(glyph);width+=glyphWidth;
                }
                wrapped.add(line.toString());
            }
            for(int offset=0;offset<wrapped.size();offset+=58){
                var page=new PDPage();document.addPage(page);
                try(var content=new PDPageContentStream(document,page)){
                    content.beginText();content.setFont(font,9);content.setLeading(12);content.newLineAtOffset(36,750);
                    for(int i=offset;i<Math.min(offset+58,wrapped.size());i++){content.showText(wrapped.get(i));content.newLine();}content.endText();
                }
            }
            document.save(bytes);return bytes.toByteArray();
        }catch(IOException e){throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"The PDF report could not be generated.",e);}
    }
}
