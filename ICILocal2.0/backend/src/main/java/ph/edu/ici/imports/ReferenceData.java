package ph.edu.ici.imports;

import java.util.*;

/** Campuses, programs and vouchers as stored, keyed by lower-case code for case-insensitive canonical matching. */
public record ReferenceData(Map<String,Ref> campuses,Map<String,Ref> programs,Map<String,Ref> vouchers) {
    public record Ref(long id,String code,String category) {}
    public static Ref find(Map<String,Ref> refs,String code){return code==null?null:refs.get(code.trim().toLowerCase(Locale.ROOT));}
}
