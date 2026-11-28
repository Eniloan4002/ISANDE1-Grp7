package ph.edu.ici.imports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Batch-level cleaning counts (trivial and non-trivial rules) stored in {@code import_batches.cleaning_summary}. */
final class CleaningSummary {
    private CleaningSummary(){}
    /** Rule to number of cells changed across the rows, as JSON text, or null when nothing was changed. */
    static String json(Collection<CleaningLog> logs,ObjectMapper json){
        Map<String,Long> total=new TreeMap<>();
        for(CleaningLog l:logs)l.counts().forEach((rule,n)->total.merge(rule,(long)n,Long::sum));
        if(total.isEmpty())return null;
        try{return json.writeValueAsString(total);}catch(JsonProcessingException e){throw new IllegalStateException(e);}
    }
}
