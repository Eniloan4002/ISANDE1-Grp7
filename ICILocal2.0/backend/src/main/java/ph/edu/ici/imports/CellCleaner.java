package ph.edu.ici.imports;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Deterministic cleaning of one cell, driven by a {@link CleaningProfile}. Pure: no database access, no locale. With the default
 * profile and no {@link CellMeta} it returns exactly what {@code ImportText.clean} returned before ICI2-39 (NFC, trim, whitespace
 * collapse, blank to null), so ICI2-37/38 results do not change. Every change is recorded in the {@link CleaningLog}; nothing is
 * rewritten silently, and nothing is guessed: cells whose meaning a spreadsheet makes ambiguous become row errors.
 */
final class CellCleaner {
    private static final Pattern SPACES=Pattern.compile("\\s+"),UNICODE_SPACES=Pattern.compile("[\\u00A0\\u2000-\\u200A\\u202F\\u205F\\u3000]"),INVISIBLE=Pattern.compile("[\\u200B-\\u200D\\u2060\\uFEFF]");
    /** A grade cell after cleaning: {@code rejected} means an error was recorded and the cell must not be read as blank. */
    record Cell(String value,boolean rejected) {}
    private final CleaningProfile profile;
    CellCleaner(CleaningProfile profile){this.profile=profile;}
    CleaningProfile profile(){return profile;}

    /** A text or identifier cell. Returns null for blank (or after an error was recorded in {@code log}). */
    String clean(String raw,String field,String column,CellMeta meta,CleaningLog log){
        if(raw==null)return null;
        String s=raw;
        if(meta!=null){s=fromMeta(s,field,column,meta,log);if(s==null)return null;}
        s=hygiene(s,log);if(s==null)return null;
        if(profile.placeholder(field,s)){
            log.changed(field,column,"placeholder_to_blank",s,null);
            log.warning("VALUE_CLEANED",field,"A configured placeholder value was treated as blank.");return null;
        }
        if(profile.lrnStripSeparators()&&"lrn".equals(field)){
            String t=s.replaceAll("[ \\-]","");if(!t.equals(s)){log.changed(field,column,"lrn_strip_separators",s,t);s=t;}
        }
        Integer width=meta!=null&&meta.kind()==CellMeta.Kind.NUMBER?profile.padNumeric().get(field):null;
        if(width!=null&&s.matches("\\d+")&&s.length()<width){
            String t="0".repeat(width-s.length())+s;log.changed(field,column,"pad_numeric_id",s,t);
            log.warning("VALUE_PADDED",field,"A numeric identifier was left-padded with zeros to the configured width.");s=t;
        }
        return s;
    }

    /** A grade cell: like {@link #clean} plus the spreadsheet formats that would make a number mean something else. */
    Cell gradeCell(String raw,String column,CellMeta meta,CleaningLog log){
        if(raw==null)return new Cell(null,false);
        String s=raw;
        if(meta!=null){
            if(meta.kind()==CellMeta.Kind.ERROR||meta.formula()&&meta.cachedMissing()){fromMeta(s,column,column,meta,log);return new Cell(null,true);}
            if(meta.kind()==CellMeta.Kind.DATE||meta.format()==CellMeta.Format.DATE){
                log.error("DATE_IN_GRADE_CELL",column,"The cell is formatted as a date, so its number is not a mark. Reformat the cell as a number.");return new Cell(null,true);
            }
            if(meta.format()==CellMeta.Format.PERCENT){
                log.error("PERCENT_FORMAT",column,"The cell is formatted as a percentage, so the stored number is not the displayed mark. Reformat the cell as a number.");return new Cell(null,true);
            }
            s=fromMeta(s,column,column,meta,log);if(s==null)return new Cell(null,true);
        }
        s=hygiene(s,log);if(s==null)return new Cell(null,false);
        if(profile.placeholder(column,s)){
            log.changed(column,column,"placeholder_to_blank",s,null);
            log.warning("VALUE_CLEANED",column,"A configured placeholder value was treated as blank (no record).");return new Cell(null,false);
        }
        return new Cell(s,false);
    }

    /** Case rule for names (only called when the mapping turns it on). */
    String nameCase(String s,String field,String column,CleaningLog log){
        String cased=RowNormalizer.fixCase(s,Set.of());
        if(!cased.equals(s))log.changed(field,column,"name_case",s,cased);
        if(profile.nameParticles().isEmpty())return cased;
        String finalName=RowNormalizer.fixCase(s,profile.nameParticles());
        if(!finalName.equals(cased))log.changed(field,column,"name_particles",cased,finalName);
        return finalName;
    }

    /** Spreadsheet-typed cells: refuse what cannot be read safely, flag formulas, render numbers and dates as plain text. */
    private String fromMeta(String s,String field,String column,CellMeta meta,CleaningLog log){
        if(meta.kind()==CellMeta.Kind.ERROR){log.error("ERROR_CELL",field,"The cell holds a spreadsheet error value, not data.");return null;}
        if(meta.formula()){
            if(meta.cachedMissing()){log.error("FORMULA_NO_CACHED_VALUE",field,"The cell is a formula with no stored result, so there is no value to read.");return null;}
            log.warning("FORMULA_CACHED",field,"The value is the stored result of a formula; it was not recalculated.");
            log.changed(field,column,"formula_cached",s,s);
        }
        if(meta.kind()==CellMeta.Kind.DATE){
            if(!profile.nativeDatesAccepted()){log.error("NATIVE_DATE_REJECTED",field,"Spreadsheet date cells are not accepted by this mapping; enter the date as text in a configured format.");return null;}
            log.changed(field,column,"xlsx_native_date",s,s);
        }else if(meta.kind()==CellMeta.Kind.NUMBER&&CleaningProfile.ID_FIELDS.contains(field)){
            String plain;
            try{
                BigDecimal n=new BigDecimal(s.trim());
                if(n.signum()<0||n.stripTrailingZeros().scale()>0){log.error("NUMERIC_NOT_INTEGRAL",field,"An identifier stored as a number must be a whole number.");return null;}
                plain=n.toBigIntegerExact().toString();
            }catch(NumberFormatException|ArithmeticException e){log.error("NUMERIC_NOT_INTEGRAL",field,"An identifier stored as a number must be a whole number.");return null;}
            if(!plain.equals(s)){log.changed(field,column,"xlsx_number_to_text",s,plain);s=plain;}
        }
        return s;
    }

    /** Unicode and whitespace hygiene. Each rule that changed a non-blank result is counted (trivial), none is logged per cell. */
    private String hygiene(String raw,CleaningLog log){
        List<String> applied=new ArrayList<>(5);String v=raw;
        if(profile.unicodeSpaces()){String t=UNICODE_SPACES.matcher(v).replaceAll(" ");if(!t.equals(v)){applied.add("unicode_spaces");v=t;}}
        if(profile.stripInvisible()){String t=INVISIBLE.matcher(v).replaceAll("");if(!t.equals(v)){applied.add("strip_invisible");v=t;}}
        String nfc=Normalizer.normalize(v,Normalizer.Form.NFC);if(!nfc.equals(v)){applied.add("unicode_nfc");v=nfc;}
        String trimmed=v.trim();if(!trimmed.equals(v)){applied.add("trim");v=trimmed;}
        String collapsed=SPACES.matcher(v).replaceAll(" ");if(!collapsed.equals(v)){applied.add("collapse_spaces");v=collapsed;}
        if(v.isEmpty())return null;
        applied.forEach(log::trivial);
        return v;
    }
}
