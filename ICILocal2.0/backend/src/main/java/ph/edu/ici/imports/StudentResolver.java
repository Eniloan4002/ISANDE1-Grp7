package ph.edu.ici.imports;

import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import ph.edu.ici.enrollment.IdentityMatcher;

/**
 * Campus-scoped resolution of a spreadsheet row to an existing student, shared by every import that links rows to students
 * (extracted unchanged from the ICI2-38 grade matrix staging). Exact identifiers only: student number, else LRN; both present must
 * agree; a clear name mismatch is flagged, never used to find a student. A student at another campus (or none) is "not found",
 * with no disclosure. Read-only; it records nothing, it only reports what each row found.
 */
final class StudentResolver {
    private static final int CHUNK=5_000;
    record Student(long id,String number,String lrn,String first,String last,boolean active) {}
    /** The identity columns of one row (any may be null). */
    record Query(String studentNumber,String lrn,String lastName,String firstName) {}
    /**
     * @param student  the matched student, or null when the row has no consistent match
     * @param matchedBy {@code studentNumber} or {@code lrn} when matched
     * @param evidence identity-review evidence ({@code kind: student}) for rows that need review
     * @param problems errors (not found) and warnings (ambiguous, name mismatch, inactive) in the order they were found
     */
    record Outcome(Student student,String matchedBy,List<Map<String,Object>> evidence,List<RowProblem> problems) {}

    private final NamedParameterJdbcTemplate db;
    StudentResolver(NamedParameterJdbcTemplate db){this.db=db;}

    private static <T> List<List<T>> chunks(Collection<T> values){List<T> all=new ArrayList<>(values);List<List<T>> out=new ArrayList<>();for(int i=0;i<all.size();i+=CHUNK)out.add(all.subList(i,Math.min(all.size(),i+CHUNK)));return out;}

    /** One outcome per query, in the same order. */
    List<Outcome> resolve(long campusId,List<Query> queries){
        Set<String> numbers=new LinkedHashSet<>(),lrns=new LinkedHashSet<>();
        for(Query q:queries){if(q.studentNumber()!=null)numbers.add(q.studentNumber());if(q.lrn()!=null)lrns.add(q.lrn());}
        Map<String,Student> byNumber=new HashMap<>(),byLrn=new HashMap<>();
        String select="SELECT id,student_number,lrn,first_name,last_name,active FROM students WHERE campus_id=:campus AND ";
        for(var part:chunks(numbers))for(var m:db.queryForList(select+"student_number IN (:v)",Map.of("campus",campusId,"v",part))){var s=student(m);byNumber.put(s.number(),s);}
        for(var part:chunks(lrns))for(var m:db.queryForList(select+"lrn IN (:v)",Map.of("campus",campusId,"v",part))){var s=student(m);byLrn.put(s.lrn(),s);}
        List<Outcome> out=new ArrayList<>();
        for(Query q:queries)out.add(one(q,byNumber,byLrn));
        return out;
    }

    private Outcome one(Query q,Map<String,Student> byNumber,Map<String,Student> byLrn){
        List<Map<String,Object>> evidence=new ArrayList<>();List<RowProblem> problems=new ArrayList<>();
        if(q.studentNumber()==null&&q.lrn()==null)return new Outcome(null,null,evidence,problems);
        Student a=q.studentNumber()==null?null:byNumber.get(q.studentNumber()),b=q.lrn()==null?null:byLrn.get(q.lrn());
        if(a==null&&b==null){
            problems.add(RowProblem.error("STUDENT_NOT_FOUND",q.studentNumber()!=null?"studentNumber":"lrn","No student with this student number or LRN exists at this campus. Import the student first (ICI2-37), then revalidate."));
            return new Outcome(null,null,evidence,problems);
        }
        if(a!=null&&b!=null&&a.id()!=b.id()){
            evidence.add(evidence(a,"STUDENT_NUMBER",null));evidence.add(evidence(b,"LRN",null));
            problems.add(RowProblem.warning("AMBIGUOUS_STUDENT",null,"The student number and the LRN belong to two different students. Review before importing."));
            return new Outcome(null,null,evidence,problems);
        }
        Student s=a!=null?a:b;
        if(a!=null&&q.lrn()!=null&&b==null&&a.lrn()!=null){
            evidence.add(evidence(a,"LRN_MISMATCH",null));
            problems.add(RowProblem.warning("AMBIGUOUS_STUDENT","lrn","The LRN in the file differs from the LRN on record for this student number. Review before importing."));
            return new Outcome(null,null,evidence,problems);
        }
        Double score=nameScore(q,s);
        if(score!=null&&score<IdentityMatcher.NAME_THRESHOLD){
            evidence.add(evidence(s,"NAME_MISMATCH",score));
            problems.add(RowProblem.warning("NAME_MISMATCH",null,"The name in the file does not match the student found by identifier. Review before importing."));
            return new Outcome(null,null,evidence,problems);
        }
        if(!s.active())problems.add(RowProblem.warning("STUDENT_INACTIVE",null,"The matched student is inactive (archived); the grades will still be recorded."));
        return new Outcome(s,a!=null?"studentNumber":"lrn",evidence,problems);
    }
    private static Student student(Map<String,Object> m){return new Student(((Number)m.get("id")).longValue(),(String)m.get("student_number"),(String)m.get("lrn"),(String)m.get("first_name"),(String)m.get("last_name"),(Boolean)m.get("active"));}
    /** The lowest available name similarity, or null when the file gives no name to compare. */
    private static Double nameScore(Query q,Student s){
        Double score=null;
        if(q.lastName()!=null)score=IdentityMatcher.similarity(q.lastName(),s.last());
        if(q.firstName()!=null){double f=IdentityMatcher.similarity(IdentityMatcher.firstToken(q.firstName()),IdentityMatcher.firstToken(s.first()));score=score==null?f:Math.min(score,f);}
        return score;
    }
    private static Map<String,Object> evidence(Student s,String rule,Double score){
        Map<String,Object> e=new LinkedHashMap<>();e.put("kind","student");e.put("rule",rule);e.put("id",s.id());e.put("campusVisible",true);if(score!=null)e.put("nameScore",Math.round(score*100)/100.0);return e;
    }
}
