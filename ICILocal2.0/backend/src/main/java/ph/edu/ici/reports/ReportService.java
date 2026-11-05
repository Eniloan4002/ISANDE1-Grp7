package ph.edu.ici.reports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.AccessScope;
import ph.edu.ici.auth.StaffIdentity;

@Service
public class ReportService {
    private final JdbcTemplate jdbc;private final NamedParameterJdbcTemplate db;private final ObjectMapper json;private final AccessScope access;
    public ReportService(JdbcTemplate jdbc,ObjectMapper json,AccessScope access){this.jdbc=jdbc;this.db=new NamedParameterJdbcTemplate(jdbc);this.json=json;this.access=access;}
    private List<Long> scope(StaffIdentity actor,String permission,Long campus){
        List<Long> ids=access.campuses(actor,permission);
        if(campus!=null){if(!ids.contains(campus))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Your account cannot access this campus.");return List.of(campus);}return ids;
    }
    private MapSqlParameterSource params(List<Long> campuses,Long term){var p=new MapSqlParameterSource().addValue("campuses",campuses);if(term!=null)p.addValue("term",term);return p;}
    private void log(StaffIdentity actor,String report,Map<String,Object> filters){
        String payload;try{payload=json.writeValueAsString(filters);}catch(JsonProcessingException exception){payload="{}";}
        jdbc.update("INSERT INTO audit_logs(table_name,record_id,operation,new_data,actor_id) VALUES ('reports',?,'GENERATE',CAST(? AS jsonb),?)",report,payload,actor.userId());
    }
    private Map<String,Object> reportFilters(Long campus,Long term){var filters=new LinkedHashMap<String,Object>();filters.put("campusId",campus==null?"all":campus);filters.put("termId",term==null?"all":term);return filters;}
    @Transactional public List<Map<String,Object>> enrollment(StaffIdentity actor,Long campus,Long term){
        List<Long> scopes=scope(actor,"reports.read",campus);if(scopes.isEmpty())return List.of();var p=params(scopes,term);String filter=term==null?"":" AND e.term_id=:term";
        var rows=db.queryForList("SELECT e.campus_id,c.name AS campus,e.term_id,t.name AS term,e.program_id,p.code AS program,e.status,count(*) AS students " +
            "FROM enrollments e JOIN campuses c ON c.id=e.campus_id JOIN academic_terms t ON t.id=e.term_id JOIN programs p ON p.id=e.program_id " +
            "WHERE e.campus_id IN (:campuses)"+filter+" GROUP BY e.campus_id,c.name,e.term_id,t.name,e.program_id,p.code,e.status ORDER BY c.name,t.name,p.code,e.status",p);
        log(actor,"enrollment",reportFilters(campus,term));return rows;
    }
    @Transactional public List<Map<String,Object>> studentRecords(StaffIdentity actor,Long campus,Long term){
        List<Long> scopes=scope(actor,"reports.read",campus);if(scopes.isEmpty())return List.of();var p=params(scopes,term);String filter=term==null?"":" AND e.term_id=:term";
        var rows=db.queryForList("SELECT st.id AS student_id,st.student_number,st.first_name,st.middle_name,st.last_name,e.campus_id,e.term_id,e.program_id,e.section_id,e.status AS enrollment_status " +
            "FROM students st JOIN enrollments e ON e.student_id=st.id WHERE e.campus_id IN (:campuses)"+filter+" ORDER BY st.last_name,st.first_name,st.id",p);
        log(actor,"student-records",reportFilters(campus,term));return rows;
    }
    @Transactional public List<Map<String,Object>> missingRequirements(StaffIdentity actor,Long campus,Long term){
        List<Long> scopes=scope(actor,"reports.read",campus);if(scopes.isEmpty())return List.of();var p=params(scopes,term);String filter=term==null?"":" AND a.term_id=:term";
        var rows=db.queryForList("SELECT a.id AS application_id,a.application_number,a.campus_id,a.term_id,st.id AS student_id,st.student_number,st.first_name,st.last_name,t.code AS requirement_code,t.name AS requirement_name,coalesce(r.status,'missing') AS status " +
            "FROM applications a JOIN students st ON st.id=a.student_id JOIN requirement_types t ON t.mandatory " +
            "LEFT JOIN application_requirements r ON r.application_id=a.id AND r.requirement_code=t.code " +
            "WHERE a.campus_id IN (:campuses) AND a.status NOT IN ('rejected','withdrawn') AND coalesce(r.status,'missing')<>'submitted'"+filter+" ORDER BY st.last_name,st.first_name,t.code",p);
        log(actor,"missing-requirements",reportFilters(campus,term));return rows;
    }
    @Transactional public List<Map<String,Object>> incompleteGrades(StaffIdentity actor,Long campus,Long term){
        List<Long> scopes=scope(actor,"reports.read",campus);if(scopes.isEmpty())return List.of();var p=params(scopes,term);String filter=term==null?"":" AND g.term_id=:term";
        var rows=db.queryForList("SELECT g.id AS grade_id,g.enrollment_id,g.class_id,g.grading_period_id,g.value,g.mark,g.state,e.campus_id,e.term_id,st.student_number,st.first_name,st.last_name,sub.code AS subject_code,sub.name AS subject_name " +
            "FROM grades g JOIN enrollments e ON e.id=g.enrollment_id JOIN students st ON st.id=e.student_id JOIN class_offerings co ON co.id=g.class_id JOIN subjects sub ON sub.id=co.subject_id " +
            "WHERE e.campus_id IN (:campuses) AND (g.mark='INC' OR g.state<>'finalized')"+filter+" ORDER BY st.last_name,st.first_name,sub.code",p);
        log(actor,"incomplete-grades",reportFilters(campus,term));return rows;
    }
    @Transactional public List<Map<String,Object>> documentRequests(StaffIdentity actor,Long campus,Long term){
        List<Long> scopes=scope(actor,"reports.read",campus);if(scopes.isEmpty())return List.of();var p=params(scopes,term);String filter=term==null?"":" AND e.term_id=:term";
        var rows=db.queryForList("SELECT DISTINCT ON (d.id) d.id,d.student_id,d.document_type,d.status,d.requested_at,d.issued_at,d.issued_by,st.campus_id,e.term_id,st.student_number,st.first_name,st.last_name " +
            "FROM document_requests d JOIN students st ON st.id=d.student_id JOIN enrollments e ON e.student_id=st.id WHERE st.campus_id IN (:campuses)"+filter+" ORDER BY d.id,e.term_id DESC",p);
        log(actor,"document-requests",reportFilters(campus,term));return rows;
    }
    @Transactional public List<Map<String,Object>> atRisk(StaffIdentity actor,long termId,Long periodId,BigDecimal gradeBelow,Long campusId){
        List<Long> scopes=scope(actor,"guidance.manage",campusId);if(scopes.isEmpty())return List.of();
        var p=new MapSqlParameterSource().addValue("campuses",scopes).addValue("term",termId).addValue("period",periodId).addValue("below",gradeBelow);
        List<Map<String,Object>> rows=db.queryForList("WITH grade_flags AS ("+
            " SELECT e.id AS enrollment_id,bool_or(g.mark='INC' OR (CAST(:below AS numeric) IS NOT NULL AND g.value<CAST(:below AS numeric))) AS academic_risk " +
            " FROM enrollments e LEFT JOIN grades g ON g.enrollment_id=e.id AND g.term_id=e.term_id AND (CAST(:period AS bigint) IS NULL OR g.grading_period_id=CAST(:period AS bigint)) " +
            " WHERE e.term_id=:term AND e.campus_id IN (:campuses) AND e.status='enrolled' GROUP BY e.id"+
            "), requirement_flags AS ("+
            " SELECT e.id AS enrollment_id,count(DISTINCT t.code) FILTER(WHERE coalesce(ar.status,'missing')<>'submitted') AS incomplete_requirements " +
            " FROM enrollments e JOIN applications a ON a.id=e.application_id JOIN requirement_types t ON t.mandatory " +
            " LEFT JOIN application_requirements ar ON ar.application_id=a.id AND ar.requirement_code=t.code " +
            " WHERE e.term_id=:term AND e.campus_id IN (:campuses) AND e.status='enrolled' GROUP BY e.id"+
            "), orientation_flags AS ("+
            " SELECT DISTINCT ON (e.id) e.id AS enrollment_id,oa.status AS latest_orientation_status FROM enrollments e " +
            " LEFT JOIN orientation_attendance oa ON oa.application_id=e.application_id " +
            " WHERE e.term_id=:term AND e.campus_id IN (:campuses) AND e.status='enrolled' ORDER BY e.id,oa.attendance_date DESC"+
            ") SELECT e.id AS enrollment_id,st.id AS student_id,st.student_number,st.first_name,st.last_name,e.campus_id,e.term_id,e.section_id, " +
            "coalesce(gf.academic_risk,false) AS academic_risk,coalesce(rf.incomplete_requirements,0) AS incomplete_requirements, " +
            "(ofl.latest_orientation_status='absent') AS orientation_absent,ofl.latest_orientation_status " +
            "FROM enrollments e JOIN students st ON st.id=e.student_id LEFT JOIN grade_flags gf ON gf.enrollment_id=e.id " +
            "LEFT JOIN requirement_flags rf ON rf.enrollment_id=e.id LEFT JOIN orientation_flags ofl ON ofl.enrollment_id=e.id " +
            "WHERE e.term_id=:term AND e.campus_id IN (:campuses) AND e.status='enrolled' AND (coalesce(gf.academic_risk,false) OR coalesce(rf.incomplete_requirements,0)>0 OR ofl.latest_orientation_status='absent') " +
            "ORDER BY st.last_name,st.first_name,st.id",p);
        log(actor,"at-risk",Map.of("termId",termId,"gradingPeriodId",periodId==null?"all":periodId,"gradeBelow",gradeBelow==null?"unset":gradeBelow,"campusIds",scopes));
        return rows;
    }
}
