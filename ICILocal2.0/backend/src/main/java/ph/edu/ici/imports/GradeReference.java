package ph.edu.ici.imports;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Existing subjects, academic years, terms and grading periods, keyed by lower-case code. The import only looks these up; it never creates them. */
record GradeReference(Map<String,Subject> subjects,Map<Long,Year> yearsById,Map<String,Year> yearsByLabel,Map<Long,Term> termsById,
                      Map<Long,Map<String,Term>> termsByYear,Map<Long,Map<String,Period>> periodsByTerm) {
    record Subject(long id,String code,boolean active) {}
    record Year(long id,String label) {}
    record Term(long id,long yearId,String code) {}
    record Period(long id,long termId,String code) {}

    static String key(String code){return code==null?"":code.trim().toLowerCase(Locale.ROOT);}

    static GradeReference load(JdbcTemplate db){
        Map<String,Subject> subjects=new HashMap<>();Map<Long,Year> yearsById=new HashMap<>();Map<String,Year> yearsByLabel=new HashMap<>();
        Map<Long,Term> termsById=new HashMap<>();Map<Long,Map<String,Term>> termsByYear=new HashMap<>();Map<Long,Map<String,Period>> periods=new HashMap<>();
        for(var r:db.queryForList("SELECT id,code,active FROM subjects"))subjects.put(key((String)r.get("code")),new Subject(((Number)r.get("id")).longValue(),(String)r.get("code"),(Boolean)r.get("active")));
        for(var r:db.queryForList("SELECT id,label FROM academic_years")){var y=new Year(((Number)r.get("id")).longValue(),(String)r.get("label"));yearsById.put(y.id(),y);yearsByLabel.put(key(y.label()),y);}
        for(var r:db.queryForList("SELECT id,academic_year_id,code FROM academic_terms")){
            var t=new Term(((Number)r.get("id")).longValue(),((Number)r.get("academic_year_id")).longValue(),(String)r.get("code"));
            termsById.put(t.id(),t);termsByYear.computeIfAbsent(t.yearId(),k->new HashMap<>()).put(key(t.code()),t);
        }
        for(var r:db.queryForList("SELECT id,term_id,code FROM grading_periods")){
            var p=new Period(((Number)r.get("id")).longValue(),((Number)r.get("term_id")).longValue(),(String)r.get("code"));
            periods.computeIfAbsent(p.termId(),k->new HashMap<>()).put(key(p.code()),p);
        }
        return new GradeReference(subjects,yearsById,yearsByLabel,termsById,termsByYear,periods);
    }
    /** Terms in which a period code may legitimately appear, given what the upload already fixes. */
    Collection<Term> candidateTerms(Long termId,Long yearId){
        if(termId!=null)return termsById.containsKey(termId)?List.of(termsById.get(termId)):List.of();
        if(yearId!=null)return termsByYear.getOrDefault(yearId,Map.of()).values();
        return termsById.values();
    }
    boolean periodExists(String periodKey,Collection<Term> terms){for(var t:terms)if(periodsByTerm.getOrDefault(t.id(),Map.of()).containsKey(periodKey))return true;return false;}
}
