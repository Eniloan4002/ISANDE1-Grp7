package ph.edu.ici.academic;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.AccessPolicy;
import ph.edu.ici.requirements.RequirementService;

/** Read-only consolidated academic history for one student (ICI2-29). */
@Service
public class AcademicHistoryService {
    private final NamedParameterJdbcTemplate db;
    private final AccessPolicy accessPolicy;
    private final RequirementService requirements;

    public AcademicHistoryService(NamedParameterJdbcTemplate db,AccessPolicy accessPolicy,RequirementService requirements){
        this.db=db;this.accessPolicy=accessPolicy;this.requirements=requirements;
    }

    private long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> history(long studentId){
        var scope=accessPolicy.campuses("enrollment.manage");
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
            SELECT e.id AS "enrollmentId",e.status,y.label AS "academicYear",t.name AS term,
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
            var checklist=requirements.checklistForApplication(applications.getFirst());
            var summary=checklist.summary();
            credentials=new LinkedHashMap<>();
            credentials.put("applicationNumber",checklist.applicationNumber());
            credentials.put("overallStatus",summary.overallStatus());
            credentials.put("mandatoryTotal",summary.mandatoryTotal());
            credentials.put("submitted",summary.submitted());
            credentials.put("complete",summary.credentialsComplete());
            credentials.put("items",checklist.items().stream().map(item->{
                var entry=new LinkedHashMap<String,Object>();
                entry.put("code",item.code());entry.put("name",item.name());
                entry.put("mandatory",item.mandatory());entry.put("status",item.status());
                return entry;
            }).toList());
        }

        var result=new LinkedHashMap<String,Object>();
        result.put("student",students.getFirst());result.put("terms",terms);
        result.put("sectionChanges",sectionChanges);result.put("credentials",credentials);
        return result;
    }
}