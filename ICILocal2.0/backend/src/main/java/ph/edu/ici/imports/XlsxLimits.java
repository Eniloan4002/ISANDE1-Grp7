package ph.edu.ici.imports;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Limits for .xlsx files on top of {@link ImportLimits} (file size, rows, columns, cell length). The zip limits are enforced by Apache POI on
 * actually inflated bytes ({@code ZipSecureFile}); the per-part size is also what bounds the memory of the in-memory workbook model.
 *
 * @param maxEntries      parts in the package
 * @param maxEntryBytes   inflated bytes of one part
 * @param minInflateRatio smallest compressed/inflated ratio accepted (0.01 = 100:1)
 * @param maxTextChars    characters of one XML text part
 * @param maxCells        non-blank cells in the sheet
 */
public record XlsxLimits(int maxEntries,long maxEntryBytes,double minInflateRatio,long maxTextChars,int maxCells) {
    public static final XlsxLimits DEFAULTS=new XlsxLimits(200,10_000_000L,0.01,10_000_000L,250_000);
    @Configuration static class Config {
        @Bean XlsxLimits xlsxLimits(@Value("${ici.import.xlsx.max-entries:200}") int entries,@Value("${ici.import.xlsx.max-entry-bytes:10000000}") long entryBytes,
                                    @Value("${ici.import.xlsx.min-inflate-ratio:0.01}") double ratio,@Value("${ici.import.xlsx.max-text-chars:10000000}") long text,
                                    @Value("${ici.import.xlsx.max-cells:250000}") int cells){return new XlsxLimits(entries,entryBytes,ratio,text,cells);}
    }
}
