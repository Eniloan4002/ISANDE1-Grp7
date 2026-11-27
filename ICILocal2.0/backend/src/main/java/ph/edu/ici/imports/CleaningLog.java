package ph.edu.ici.imports;

import java.util.*;

/**
 * What cleaning did to one row. Trivial rules (lossless whitespace and Unicode canonicalization) are only counted; every other change
 * is also logged with the raw and cleaned value so review and audit can show it. Problems found while cleaning (never-guess errors and
 * warnings) are collected for the caller to merge into the row's problems. Not thread-safe; one instance per row.
 */
public final class CleaningLog {
    static final int MAX_ENTRIES=50,MAX_TEXT=200;
    public static final Set<String> TRIVIAL=Set.of("unicode_nfc","trim","collapse_spaces","unicode_spaces","strip_invisible");
    private final List<Map<String,Object>> entries=new ArrayList<>();
    private final Map<String,Integer> counts=new LinkedHashMap<>();
    private final List<RowProblem> problems=new ArrayList<>();
    private boolean truncated;

    /** A lossless change: counted, not written per cell. */
    void trivial(String rule){counts.merge(rule,1,Integer::sum);}
    /** A change worth showing: counted and logged. {@code after} is null when the value became blank. */
    void changed(String field,String column,String rule,String before,String after){
        counts.merge(rule,1,Integer::sum);
        if(entries.size()>=MAX_ENTRIES){if(!truncated){truncated=true;entries.add(new LinkedHashMap<>(Map.of("rule","MORE_CHANGES")));}return;}
        Map<String,Object> e=new LinkedHashMap<>();
        e.put("field",field);if(column!=null)e.put("column",column);e.put("rule",rule);
        e.put("before",cap(before,e,"beforeTruncated"));e.put("after",cap(after,e,"afterTruncated"));
        entries.add(e);
    }
    private static String cap(String value,Map<String,Object> entry,String flag){
        if(value==null||value.length()<=MAX_TEXT)return value;entry.put(flag,true);return value.substring(0,MAX_TEXT);
    }
    void error(String code,String field,String detail){problems.add(RowProblem.error(code,field,detail));}
    void warning(String code,String field,String detail){problems.add(RowProblem.warning(code,field,detail));}

    /** Logged (non-trivial) changes in application order; empty when nothing needs showing. */
    public List<Map<String,Object>> entries(){return entries;}
    /** Rule to number of cells it changed, trivial and non-trivial. */
    public Map<String,Integer> counts(){return counts;}
    public List<RowProblem> problems(){return problems;}
    public boolean hasEntries(){return !entries.isEmpty();}
    /** True when an error was already recorded for this field, so callers do not add a second, misleading one (e.g. REQUIRED_MISSING after ERROR_CELL). */
    boolean hasError(String field){return problems.stream().anyMatch(p->RowProblem.ERROR.equals(p.severity())&&Objects.equals(p.field(),field));}
}
