package ph.edu.ici.imports;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Application-level limits; these, not the servlet container's, are authoritative. */
public record ImportLimits(long maxFileBytes,int maxRows,int maxColumns,int maxCellChars) {
    public static final ImportLimits DEFAULTS=new ImportLimits(5_000_000L,20_000,64,1_000);
    @Configuration static class Config {
        @Bean ImportLimits importLimits(@Value("${ici.import.max-file-bytes:5000000}") long bytes,@Value("${ici.import.max-rows:20000}") int rows,
            @Value("${ici.import.max-columns:64}") int columns,@Value("${ici.import.max-cell-chars:1000}") int cell){return new ImportLimits(bytes,rows,columns,cell);}
    }
}
