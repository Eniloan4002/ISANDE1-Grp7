package ph.edu.ici.documents;

import java.io.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Embedded font loading follows https://pdfbox.apache.org/3.0/migration.html. */
final class OfficialDocumentPdf {
    private OfficialDocumentPdf(){}
    static byte[] render(String title,List<String> lines){
        if(lines.stream().mapToLong(String::length).sum()>250_000)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"The academic document exceeds 250,000 printable source characters. Select fewer terms or correct oversized source fields.");
        try(var document=new PDDocument();var bytes=new ByteArrayOutputStream()){
            PDFont font;
            try(var stream=OfficialDocumentPdf.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")){
                if(stream==null)throw new IOException("Bundled academic document font is unavailable");
                font=PDType0Font.load(document,stream);
            }
            var pages=new ArrayList<List<String>>();var pageLines=new ArrayList<String>();pages.add(pageLines);
            for(String raw:lines)for(String paragraph:raw.split("\\R",-1)){
                var wrapped=new ArrayList<String>();
                var line=new StringBuilder();float width=0;
                for(int cp:paragraph.codePoints().toArray()){
                    String glyph=new String(Character.toChars(cp));
                    if(Character.isISOControl(cp))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Academic records contain a nonprintable character. Correct the source record before printing.");
                    try{font.encode(glyph);}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"The configured font cannot represent U+"+Integer.toHexString(cp).toUpperCase(Locale.ROOT)+". Configure a suitable approved font/layout before printing.");}
                    float glyphWidth=font.getStringWidth(glyph)*9/1000;
                    if(width+glyphWidth>540&&!line.isEmpty()){wrapped.add(line.toString());line.setLength(0);width=0;}
                    line.append(glyph);width+=glyphWidth;
                }
                wrapped.add(line.toString());
                // A subject row may wrap but should not be split across pages when it fits a page.
                if(wrapped.size()<=56&&!pageLines.isEmpty()&&pageLines.size()+wrapped.size()>56){pageLines=new ArrayList<>();pages.add(pageLines);}
                for(String value:wrapped){if(pageLines.size()==56){pageLines=new ArrayList<>();pages.add(pageLines);}pageLines.add(value);}
            }
            int total=pages.size();
            for(int pageNumber=1;pageNumber<=total;pageNumber++){
                var page=new PDPage();document.addPage(page);
                try(var content=new PDPageContentStream(document,page)){
                    content.beginText();content.setFont(font,9);content.setLeading(12);content.newLineAtOffset(36,772);
                    content.showText(title);content.endText();
                    content.moveTo(36,761);content.lineTo(576,761);content.stroke();
                    content.beginText();content.setFont(font,9);content.setLeading(12);content.newLineAtOffset(36,744);
                    for(String value:pages.get(pageNumber-1)){content.showText(value);content.newLine();}content.endText();
                    content.beginText();content.setFont(font,8);content.newLineAtOffset(36,32);content.showText("Page "+pageNumber+" of "+total+" | "+title);content.endText();
                }
            }
            document.save(bytes);return bytes.toByteArray();
        }catch(IOException e){throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"The academic document PDF could not be generated.",e);}
    }
}
