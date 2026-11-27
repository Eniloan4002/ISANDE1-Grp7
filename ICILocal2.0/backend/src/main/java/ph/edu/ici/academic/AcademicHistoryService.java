package ph.edu.ici.academic;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.AccessPolicy;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** Read-only consolidated academic history for one student (ICI2-29). */
@Service
public class AcademicHistoryService {
    private final NamedParameterJdbcTemplate db;
    private final AccessPolicy accessPolicy;

    public AcademicHistoryService(NamedParameterJdbcTemplate db,AccessPolicy accessPolicy){
        this.db=db;this.accessPolicy=accessPolicy;
    }

    private long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}

    private List<Long> scope(){
        var allowed=new TreeSet<Long>(accessPolicy.campuses("enrollment.manage"));
        allowed.addAll(accessPolicy.campuses("reports.read"));
        allowed.addAll(accessPolicy.campuses("guidance.manage"));
        if(allowed.isEmpty())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Academic history access is required.");
        return List.copyOf(allowed);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> students(String search,int page,int size){
        if(page<0||page>1000000||size<1||size>100||search.length()>150)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use a search up to 150 characters, page >= 0 and size 1 to 100.");
        var params=new MapSqlParameterSource("scope",scope()).addValue("search","%"+search.trim().replace("!","!!").replace("%","!%").replace("_","!_")+"%")
                .addValue("limit",size).addValue("offset",(long)page*size);
        String where=" WHERE st.campus_id IN (:scope) AND (concat_ws(' ',st.first_name,st.middle_name,st.last_name) ILIKE :search ESCAPE '!' OR st.student_number ILIKE :search ESCAPE '!' OR st.lrn ILIKE :search ESCAPE '!')";
        long total=db.queryForObject("SELECT count(*) FROM students st"+where,params,Long.class);
        var items=db.queryForList("SELECT st.id,st.first_name,st.middle_name,st.last_name,st.student_number,st.lrn,st.active FROM students st"+where+" ORDER BY st.last_name,st.first_name,st.id LIMIT :limit OFFSET :offset",params);
        return Map.of("items",items,"total",total,"page",page,"size",size);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> history(long studentId){
        var scope=scope();
        var students=scope.isEmpty()?List.<Map<String,Object>>of():db.queryForList("""
            SELECT st.id,st.student_number AS "studentNumber",
                   concat_ws(' ',st.first_name,st.middle_name,st.last_name,st.suffix) AS name,
                   st.lrn,st.birth_date AS "birthDate",st.active,c.name AS campus,
                   p.code AS program,p.name AS "programName"
            FROM students st JOIN campuses c ON c.id=st.campus_id JOIN programs p ON p.id=st.program_id
            WHERE st.id=:id AND st.campus_id IN (:scope)
            """,Map.of("id",studentId,"scope",scope));
        if(students.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Student not found.");
        var params=Map.<String,Object>of("student",studentId,"scope",scope);

        // One row per enrolled term, newest first.
        var enrollments=db.queryForList("""
            SELECT e.id AS "enrollmentId",e.section_id AS "sectionId",e.term_id AS "termId",e.status,y.label AS "academicYear",t.name AS term,
                   p.code AS program,s.name AS section,s.grade_level AS "gradeLevel",s.room,u.full_name AS adviser
            FROM enrollments e
            JOIN academic_terms t ON t.id=e.term_id JOIN academic_years y ON y.id=t.academic_year_id
            JOIN programs p ON p.id=e.program_id
            LEFT JOIN sections s ON s.id=e.section_id LEFT JOIN users u ON u.id=s.adviser_id
            WHERE e.student_id=:student AND e.campus_id IN (:scope)
            ORDER BY t.starts_on DESC,e.id DESC
            """,params);

        // Every class the student belongs to, with whatever grades have been submitted or finalized.
        var rows=db.queryForList("""
            SELECT e.id AS "enrollmentId",co.id AS "classId",sub.code AS "subjectCode",sub.name AS subject,sub.units,
                   gp.code AS "periodCode",gp.sequence,g.value,g.mark,g.state
            FROM enrollments e
            JOIN class_memberships cm ON cm.enrollment_id=e.id
            JOIN class_offerings co ON co.id=cm.class_id
            JOIN subjects sub ON sub.id=co.subject_id
            LEFT JOIN grades g ON g.class_id=cm.class_id AND g.enrollment_id=e.id AND g.state IN ('submitted','finalized')
            LEFT JOIN grading_periods gp ON gp.id=g.grading_period_id
            WHERE e.student_id=:student AND e.campus_id IN (:scope)
            ORDER BY sub.code,co.id,gp.sequence
            """,params);
        var byEnrollment=new HashMap<Long,Map<Long,Map<String,Object>>>();
        var gradesByClass=new HashMap<Long,List<Map<String,Object>>>();
        for(var row:rows){
            long classId=number(row,"classId");
            var subjects=byEnrollment.computeIfAbsent(number(row,"enrollmentId"),key->new LinkedHashMap<>());
            var grades=gradesByClass.computeIfAbsent(classId,key->new ArrayList<>());
            subjects.computeIfAbsent(classId,key->{
                var subject=new LinkedHashMap<String,Object>();
                subject.put("code",row.get("subjectCode"));subject.put("name",row.get("subject"));
                subject.put("units",row.get("units"));subject.put("grades",grades);
                return subject;
            });
            if(row.get("periodCode")!=null){
                var grade=new LinkedHashMap<String,Object>();
                grade.put("period",row.get("periodCode"));grade.put("sequence",row.get("sequence"));
                grade.put("value",row.get("value"));grade.put("mark",row.get("mark"));grade.put("state",row.get("state"));
                grades.add(grade);
            }
        }

        var terms=new ArrayList<Map<String,Object>>();
        var enrollmentIds=new ArrayList<String>();
        for(var enrollment:enrollments){
            long id=number(enrollment,"enrollmentId");
            enrollmentIds.add(Long.toString(id));
            var term=new LinkedHashMap<String,Object>(enrollment);
            term.put("subjects",new ArrayList<>(byEnrollment.getOrDefault(id,Map.of()).values()));
            terms.add(term);
        }

        // Mid-term section moves, taken from the audit trail (empty if enrollments are not audited).
        var sectionChanges=enrollmentIds.isEmpty()?List.<Map<String,Object>>of():db.queryForList("""
            SELECT a.occurred_at AS "occurredAt",coalesce(a.new_data->>'id',a.old_data->>'id') AS "enrollmentId",
                   fs.name AS "fromSection",ts.name AS "toSection",u.full_name AS actor
            FROM audit_logs a
            LEFT JOIN sections fs ON fs.id=CAST(a.old_data->>'section_id' AS bigint)
            LEFT JOIN sections ts ON ts.id=CAST(a.new_data->>'section_id' AS bigint)
            LEFT JOIN users u ON u.id=a.actor_id
            WHERE a.table_name='enrollments'
              AND coalesce(a.new_data->>'id',a.old_data->>'id') IN (:ids)
              AND a.old_data->>'section_id' IS DISTINCT FROM a.new_data->>'section_id'
            ORDER BY a.occurred_at DESC,a.id DESC
            """,Map.of("ids",enrollmentIds));

        Map<String,Object> credentials=null;
        var applications=db.queryForList("""
            SELECT id,application_number,campus_id,student_id FROM applications
            WHERE student_id=:student AND campus_id IN (:scope)
            ORDER BY submitted_at DESC NULLS LAST,id DESC LIMIT 1
            """,params);
        if(!applications.isEmpty()){
            var application=applications.getFirst();
            var items=db.queryForList("""
                SELECT rt.code,rt.name,rt.mandatory,coalesce(ar.status,'pending') AS status
                FROM requirement_types rt LEFT JOIN application_requirements ar
                  ON ar.requirement_code=rt.code AND ar.application_id=:application
                ORDER BY rt.code
                """,Map.of("application",application.get("id")));
            long mandatory=items.stream().filter(item->Boolean.TRUE.equals(item.get("mandatory"))).count();
            long submitted=items.stream().filter(item->Boolean.TRUE.equals(item.get("mandatory"))&&"submitted".equals(item.get("status"))).count();
            boolean incomplete=items.stream().anyMatch(item->Boolean.TRUE.equals(item.get("mandatory"))&&Set.of("missing","invalid").contains(item.get("status")));
            boolean complete=mandatory>0&&mandatory==submitted;
            credentials=new LinkedHashMap<>();credentials.put("applicationNumber",application.get("application_number"));
            credentials.put("overallStatus",complete?"Complete":incomplete?"Incomplete":"Pending");
            credentials.put("mandatoryTotal",mandatory);credentials.put("submitted",submitted);credentials.put("complete",complete);credentials.put("items",items);
        }
        var incompleteCases=db.queryForList("""
            SELECT ic.id,ic.status,ic.resolved_at AS "resolvedAt",g.id AS "gradeId",g.mark,g.value,g.state,
                   sub.code AS "subjectCode",gp.code AS period,e.id AS "enrollmentId"
            FROM incomplete_cases ic JOIN grades g ON g.id=ic.grade_id
            JOIN enrollments e ON e.id=g.enrollment_id JOIN class_offerings co ON co.id=g.class_id
            JOIN subjects sub ON sub.id=co.subject_id JOIN grading_periods gp ON gp.id=g.grading_period_id
            WHERE e.student_id=:student AND e.campus_id IN (:scope) ORDER BY gp.sequence,ic.id
            """,params);
        var withdrawals=db.queryForList("""
            SELECT w.id,w.enrollment_id AS "enrollmentId",w.status,w.requested_at AS "requestedAt"
            FROM withdrawals w JOIN enrollments e ON e.id=w.enrollment_id
            WHERE e.student_id=:student AND e.campus_id IN (:scope) ORDER BY w.requested_at,w.id
            """,params);
        // Release is a student-wide gate, while the displayed details remain campus scoped.
        boolean withdrawalReleaseEligible=Boolean.TRUE.equals(db.queryForObject("""
            SELECT NOT EXISTS(SELECT 1 FROM withdrawals w JOIN enrollments e ON e.id=w.enrollment_id
                              WHERE e.student_id=:student AND w.status<>'completed')
            """,params,Boolean.class));
        // Explicit history only: never expose adviser/guidance/incomplete counseling note bodies here.
        var adviserChanges=enrollmentIds.isEmpty()?List.<Map<String,Object>>of():db.queryForList("""
            SELECT a.occurred_at AS "occurredAt",s.id AS "sectionId",s.name AS section,
                   previous.full_name AS "previousAdviser",current_staff.full_name AS "newAdviser",u.full_name AS actor
            FROM audit_logs a JOIN sections s ON s.id=CAST(coalesce(a.new_data->>'id',a.old_data->>'id') AS bigint)
            LEFT JOIN users previous ON previous.id=CAST(a.old_data->>'adviser_id' AS bigint)
            LEFT JOIN users current_staff ON current_staff.id=CAST(a.new_data->>'adviser_id' AS bigint)
            LEFT JOIN users u ON u.id=a.actor_id
            WHERE a.table_name='sections' AND s.campus_id IN (:scope)
              AND s.id IN (SELECT section_id FROM enrollments WHERE student_id=:student AND campus_id IN (:scope))
              AND a.old_data->>'adviser_id' IS DISTINCT FROM a.new_data->>'adviser_id'
            ORDER BY a.occurred_at,a.id
            """,params);

        var result=new LinkedHashMap<String,Object>();
        result.put("student",students.getFirst());result.put("terms",terms);
        result.put("sectionChanges",sectionChanges);result.put("credentials",credentials);
        result.put("adviserChanges",adviserChanges);result.put("incompleteCases",incompleteCases);result.put("withdrawals",withdrawals);
        result.put("withdrawalReleaseEligible",withdrawalReleaseEligible);
        result.put("finalRatingPolicy","Only recorded submitted/finalized marks are returned; no unapproved weighted rating is computed.");
        return result;
    }
}