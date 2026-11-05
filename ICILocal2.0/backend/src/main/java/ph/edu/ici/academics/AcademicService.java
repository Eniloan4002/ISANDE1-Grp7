package ph.edu.ici.academics;

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
public class AcademicService {
    private final JdbcTemplate jdbc;private final NamedParameterJdbcTemplate db;private final AccessScope access;
    public AcademicService(JdbcTemplate jdbc,AccessScope access){this.jdbc=jdbc;this.db=new NamedParameterJdbcTemplate(jdbc);this.access=access;}
    private void actor(StaffIdentity identity){access.actor(identity);}
    private ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private void require(boolean value,String message){if(!value)throw bad(message);}
    private List<Long> scopes(StaffIdentity actor,String permission){return access.campuses(actor,permission);}
    public List<Map<String,Object>> classes(StaffIdentity actor){
        List<Long> scope=new ArrayList<>();scope.addAll(scopes(actor,"grades.encode"));scope.addAll(scopes(actor,"grades.review"));scope=scope.stream().distinct().toList();if(scope.isEmpty())return List.of();
        return db.queryForList("SELECT co.id,co.section_id,co.term_id,co.subject_id,co.teacher_id,s.campus_id,s.name AS section_name,s.grade_level,sub.code AS subject_code,sub.name AS subject_name " +
            "FROM class_offerings co JOIN sections s ON s.id=co.section_id JOIN subjects sub ON sub.id=co.subject_id " +
            "WHERE s.campus_id IN (:campuses) AND (co.teacher_id=:actor OR EXISTS(SELECT 1 FROM user_role_grants g JOIN role_permissions rp ON rp.role_code=g.role_code " +
            "WHERE g.user_id=:actor AND g.campus_id=s.campus_id AND rp.permission_code='grades.review')) ORDER BY co.term_id,s.name,sub.code",
            new MapSqlParameterSource().addValue("campuses",scope).addValue("actor",actor.userId()));
    }
    public List<Map<String,Object>> advisory(StaffIdentity actor,Long termId){
        List<Long> scope=access.campuses(actor,"advisory.read");if(scope.isEmpty())return List.of();var p=new MapSqlParameterSource().addValue("campuses",scope).addValue("actor",actor.userId());String termFilter="";
        if(termId!=null){p.addValue("term",termId);termFilter=" AND e.term_id=:term";}
        return db.queryForList("SELECT s.id AS section_id,s.name AS section_name,s.grade_level,s.term_id,e.id AS enrollment_id,e.status AS enrollment_status, " +
            "st.id AS student_id,st.student_number,st.first_name,st.middle_name,st.last_name, " +
            "(SELECT count(*) FROM application_requirements ar WHERE ar.application_id=e.application_id AND ar.status<>'submitted') AS incomplete_requirements, " +
            "EXISTS(SELECT 1 FROM grades g WHERE g.enrollment_id=e.id AND (g.mark='INC' OR g.state<>'finalized')) AS has_incomplete_grade " +
            "FROM sections s JOIN enrollments e ON e.section_id=s.id JOIN students st ON st.id=e.student_id " +
            "WHERE s.adviser_id=:actor AND s.campus_id IN (:campuses)"+termFilter+" ORDER BY s.name,st.last_name,st.first_name,st.id",p);
    }
    public List<Map<String,Object>> roster(StaffIdentity actor,long classId){
        Map<String,Object> cls=accessibleClass(actor,classId,true);
        return db.queryForList("SELECT e.id AS enrollment_id,e.student_id,st.student_number,st.first_name,st.middle_name,st.last_name,e.status " +
            "FROM class_memberships cm JOIN enrollments e ON e.id=cm.enrollment_id JOIN students st ON st.id=e.student_id " +
            "WHERE cm.class_id=:class AND e.campus_id=:campus ORDER BY st.last_name,st.first_name,st.id",
            new MapSqlParameterSource().addValue("class",classId).addValue("campus",cls.get("campus_id")));
    }
    private Map<String,Object> accessibleClass(StaffIdentity actor,long classId,boolean allowReview){
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT co.id,co.section_id,co.term_id,co.teacher_id,s.campus_id FROM class_offerings co JOIN sections s ON s.id=co.section_id WHERE co.id=?",classId);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Class not found.");var cls=rows.getFirst();long campus=((Number)cls.get("campus_id")).longValue();boolean allowed=false;
        if(allowReview&&access.campuses(actor,"grades.review").contains(campus))allowed=true;
        if(!allowed&&access.campuses(actor,"grades.encode").contains(campus)&&((Number)cls.get("teacher_id")).longValue()==actor.userId())allowed=true;
        if(!allowed)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Class not found.");return cls;
    }
    @Transactional public Map<String,Object> createClass(StaffIdentity actor,AcademicController.ClassInput input){
        List<Long> scope=access.campuses(actor,"enrollment.manage");var sections=db.queryForList("SELECT id,campus_id,term_id FROM sections WHERE id=:id AND campus_id IN (:campuses)",
            new MapSqlParameterSource().addValue("id",input.sectionId()).addValue("campuses",scope));
        if(sections.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Section not found.");var section=sections.getFirst();long campus=((Number)section.get("campus_id")).longValue();
        boolean teacher=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM user_role_grants WHERE user_id=? AND role_code='ROLE_TEACHER' AND campus_id=?)",Boolean.class,input.teacherId(),campus));
        if(!teacher)throw bad("Select a teacher assigned to this campus.");
        actor(actor);
        try{
            long id=jdbc.queryForObject("INSERT INTO class_offerings(section_id,term_id,subject_id,teacher_id) VALUES (?,?,?,?) RETURNING id",Long.class,
                input.sectionId(),section.get("term_id"),input.subjectId(),input.teacherId());
            jdbc.update("INSERT INTO class_memberships(class_id,enrollment_id,section_id,term_id) SELECT ?,e.id,e.section_id,e.term_id FROM enrollments e WHERE e.section_id=? AND e.status IN ('provisional','enrolled') ON CONFLICT DO NOTHING",id,input.sectionId());
            return jdbc.queryForMap("SELECT co.id,co.section_id,co.term_id,co.subject_id,co.teacher_id,s.campus_id FROM class_offerings co JOIN sections s ON s.id=co.section_id WHERE co.id=?",id);
        }catch(org.springframework.dao.DuplicateKeyException exception){throw new ResponseStatusException(HttpStatus.CONFLICT,"This section already has an offering for the selected subject.");}
    }
    @Transactional public Map<String,Object> assignTeacher(StaffIdentity actor,long classId,long teacherId){
        Map<String,Object> cls=accessibleEnrollmentClass(actor,classId);long campus=((Number)cls.get("campus_id")).longValue();
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM user_role_grants WHERE user_id=? AND role_code='ROLE_TEACHER' AND campus_id=?)",Boolean.class,teacherId,campus)))
            throw bad("Select a teacher assigned to this campus.");actor(actor);
        int changed=jdbc.update("UPDATE class_offerings SET teacher_id=? WHERE id=? AND section_id=?",teacherId,classId,cls.get("section_id"));
        if(changed==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Class assignment changed. Reload before saving.");
        return jdbc.queryForMap("SELECT id,section_id,term_id,subject_id,teacher_id FROM class_offerings WHERE id=?",classId);
    }
    private Map<String,Object> accessibleEnrollmentClass(StaffIdentity actor,long id){
        List<Long> scope=access.campuses(actor,"enrollment.manage");var rows=db.queryForList("SELECT co.id,co.section_id,co.term_id,co.teacher_id,s.campus_id FROM class_offerings co JOIN sections s ON s.id=co.section_id WHERE co.id=:id AND s.campus_id IN (:campuses)",new MapSqlParameterSource().addValue("id",id).addValue("campuses",scope));
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Class not found.");return rows.getFirst();
    }
    public List<Map<String,Object>> grades(StaffIdentity actor,long classId,long periodId){
        Map<String,Object> cls=accessibleClass(actor,classId,true);
        return jdbc.queryForList("SELECT g.id,g.class_id,g.enrollment_id,g.grading_period_id,g.value,g.mark,g.state,g.version,g.updated_at,g.updated_by " +
            "FROM grades g WHERE g.class_id=? AND g.grading_period_id=? ORDER BY g.enrollment_id",classId,periodId);
    }
    @Transactional public Map<String,Object> addGrade(StaffIdentity actor,long classId,AcademicController.GradeInput input){
        validateGrade(input.value(),input.mark(),input.state());Map<String,Object> cls=accessibleClass(actor,classId,false);long campus=((Number)cls.get("campus_id")).longValue();
        access.requireCampus(actor,"grades.encode",campus);lockWindow(classId,input.gradingPeriodId(),true);actor(actor);
        int inserted;
        try{inserted=jdbc.update("INSERT INTO grades(class_id,enrollment_id,term_id,grading_period_id,value,mark,state,updated_by) " +
            "SELECT co.id,cm.enrollment_id,co.term_id,gw.grading_period_id,?,?,?,? FROM class_offerings co JOIN sections s ON s.id=co.section_id " +
            "JOIN class_memberships cm ON cm.class_id=co.id JOIN grading_windows gw ON gw.class_id=co.id AND gw.grading_period_id=? " +
            "WHERE co.id=? AND co.teacher_id=? AND s.campus_id=? AND cm.enrollment_id=? AND gw.locked_at IS NULL AND gw.deadline>current_timestamp",
            input.value(),input.mark(),input.state(),actor.userId(),input.gradingPeriodId(),classId,actor.userId(),campus,input.enrollmentId());}
        catch(org.springframework.dao.DuplicateKeyException exception){throw new ResponseStatusException(HttpStatus.CONFLICT,"A grade already exists for this class, student, and period.");}
        if(inserted==0)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"The student is not in this class or the grading window is closed.");
        return jdbc.queryForMap("SELECT id,class_id,enrollment_id,grading_period_id,value,mark,state,version,updated_at,updated_by FROM grades WHERE class_id=? AND enrollment_id=? AND grading_period_id=?",classId,input.enrollmentId(),input.gradingPeriodId());
    }
    @Transactional public Map<String,Object> updateGrade(StaffIdentity actor,long id,AcademicController.GradeUpdate input){
        validateGrade(input.value(),input.mark(),input.state());
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT g.id,g.class_id,g.enrollment_id,g.grading_period_id,g.version,co.teacher_id,s.campus_id " +
            "FROM grades g JOIN class_offerings co ON co.id=g.class_id JOIN sections s ON s.id=co.section_id WHERE g.id=?",id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Grade not found.");var grade=rows.getFirst();long classId=((Number)grade.get("class_id")).longValue();
        if(((Number)grade.get("teacher_id")).longValue()!=actor.userId()||!access.campuses(actor,"grades.encode").contains(((Number)grade.get("campus_id")).longValue()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Grade not found.");
        lockWindow(classId,((Number)grade.get("grading_period_id")).longValue(),true);actor(actor);
        int updated=jdbc.update("UPDATE grades g SET value=?,mark=?,state=?,version=version+1,updated_at=current_timestamp,updated_by=? " +
            "FROM class_offerings co,sections s,grading_windows gw " +
            "WHERE g.id=? AND g.version=? AND g.class_id=co.id AND s.id=co.section_id AND gw.class_id=co.id AND gw.grading_period_id=g.grading_period_id " +
            "AND co.teacher_id=? AND s.campus_id=? AND g.state IN ('draft','submitted') AND gw.locked_at IS NULL AND gw.deadline>current_timestamp",
            input.value(),input.mark(),input.state(),actor.userId(),id,input.version(),actor.userId(),grade.get("campus_id"));
        if(updated==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Grade is no longer editable or changed in another session.");
        return jdbc.queryForMap("SELECT id,class_id,enrollment_id,grading_period_id,value,mark,state,version,updated_at,updated_by FROM grades WHERE id=?",id);
    }
    private void validateGrade(java.math.BigDecimal value,String mark,String state){
        require(Set.of("draft","submitted").contains(state),"State must be draft or submitted.");
        if(value!=null&&mark!=null)bad("Provide a numeric grade or a mark, not both.");
        if(mark!=null&&!Set.of("INC","NG","DRP").contains(mark))bad("Select a supported nonnumeric grade mark.");
        if("submitted".equals(state)&&value==null&&mark==null)bad("Submitted grades require a numeric value or mark.");
    }
    private Map<String,Object> lockWindow(long classId,long periodId,boolean requireOpen){
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT class_id,grading_period_id,deadline,locked_at,(deadline IS NOT NULL AND deadline>current_timestamp AND locked_at IS NULL) AS open_for_submission " +
            "FROM grading_windows WHERE class_id=? AND grading_period_id=? FOR UPDATE",classId,periodId);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"No grading window exists for this class and period.");var window=rows.getFirst();
        if(requireOpen&&!Boolean.TRUE.equals(window.get("open_for_submission")))throw new ResponseStatusException(HttpStatus.CONFLICT,"The grading window is closed.");
        if(!requireOpen&&window.get("locked_at")!=null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Grading window is already finalized.");return window;
    }
    @Transactional public Map<String,Object> finalizeGrades(StaffIdentity actor,long classId,long periodId){
        Map<String,Object> cls=accessibleReviewClass(actor,classId);long campus=((Number)cls.get("campus_id")).longValue();access.requireCampus(actor,"grades.review",campus);
        lockWindow(classId,periodId,false);actor(actor);
        jdbc.update("UPDATE grades SET state='finalized',version=version+1,updated_at=current_timestamp,updated_by=? WHERE class_id=? AND grading_period_id=? AND state='submitted'",actor.userId(),classId,periodId);
        jdbc.update("UPDATE grading_windows SET locked_at=current_timestamp,locked_by=? WHERE class_id=? AND grading_period_id=?",actor.userId(),classId,periodId);
        return jdbc.queryForMap("SELECT class_id,grading_period_id,deadline,locked_at,locked_by FROM grading_windows WHERE class_id=? AND grading_period_id=?",classId,periodId);
    }
    private Map<String,Object> accessibleReviewClass(StaffIdentity actor,long id){
        var rows=jdbc.queryForList("SELECT co.id,s.campus_id FROM class_offerings co JOIN sections s ON s.id=co.section_id WHERE co.id=?",id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Class not found.");var row=rows.getFirst();
        if(!access.campuses(actor,"grades.review").contains(((Number)row.get("campus_id")).longValue()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Class not found.");return row;
    }
    @Transactional public Map<String,Object> correction(StaffIdentity actor,long gradeId,AcademicController.CorrectionInput input){
        var rows=jdbc.queryForList("SELECT g.id,g.value,g.mark,g.state,g.class_id,co.teacher_id,s.campus_id FROM grades g JOIN class_offerings co ON co.id=g.class_id JOIN sections s ON s.id=co.section_id WHERE g.id=?",gradeId);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Grade not found.");var grade=rows.getFirst();
        if(((Number)grade.get("teacher_id")).longValue()!=actor.userId()||!access.campuses(actor,"grades.encode").contains(((Number)grade.get("campus_id")).longValue()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Grade not found.");
        if(!"finalized".equals(grade.get("state")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Correction requests are only available for finalized grades.");
        actor(actor);long id=jdbc.queryForObject("INSERT INTO grade_corrections(grade_id,original_value,original_mark,proposed_value,justification,evidence_storage_key,requested_by) VALUES (?,?,?,?,?,?,?) RETURNING id",Long.class,
            gradeId,grade.get("value"),grade.get("mark"),input.proposedValue(),input.justification().trim(),input.evidenceStorageKey().trim(),actor.userId());
        return jdbc.queryForMap("SELECT id,grade_id,original_value,original_mark,proposed_value,justification,evidence_storage_key,status,requested_by,requested_at FROM grade_corrections WHERE id=?",id);
    }
    public List<Map<String,Object>> corrections(StaffIdentity actor){
        List<Long> scope=new ArrayList<>();scope.addAll(access.campuses(actor,"grades.review"));scope.addAll(access.campuses(actor,"grades.encode"));scope=scope.stream().distinct().toList();if(scope.isEmpty())return List.of();
        return db.queryForList("SELECT gc.id,gc.grade_id,gc.original_value,gc.original_mark,gc.proposed_value,gc.justification,gc.evidence_storage_key,gc.status,gc.requested_by,gc.requested_at,gc.reviewed_by,gc.reviewed_at " +
            "FROM grade_corrections gc JOIN grades g ON g.id=gc.grade_id JOIN class_offerings co ON co.id=g.class_id JOIN sections s ON s.id=co.section_id " +
            "WHERE s.campus_id IN (:campuses) AND (co.teacher_id=:actor OR EXISTS(SELECT 1 FROM user_role_grants x JOIN role_permissions rp ON rp.role_code=x.role_code WHERE x.user_id=:actor AND x.campus_id=s.campus_id AND rp.permission_code='grades.review')) ORDER BY gc.requested_at DESC",
            new MapSqlParameterSource().addValue("campuses",scope).addValue("actor",actor.userId()));
    }
    @Transactional public Map<String,Object> decideCorrection(StaffIdentity actor,long id,AcademicController.CorrectionDecision input){
        if(!Set.of("approved","rejected").contains(input.status()))bad("Status must be approved or rejected.");
        var rows=jdbc.queryForList("SELECT gc.id,gc.grade_id,gc.proposed_value,gc.requested_by,gc.status,g.class_id,co.teacher_id,s.campus_id " +
            "FROM grade_corrections gc JOIN grades g ON g.id=gc.grade_id JOIN class_offerings co ON co.id=g.class_id JOIN sections s ON s.id=co.section_id WHERE gc.id=? FOR UPDATE",id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Correction request not found.");var correction=rows.getFirst();
        if(!access.campuses(actor,"grades.review").contains(((Number)correction.get("campus_id")).longValue()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Correction request not found.");
        if(!"pending".equals(correction.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Correction request has already been reviewed.");
        if("approved".equals(input.status())&&((Number)correction.get("requested_by")).longValue()==actor.userId())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"A reviewer cannot approve their own correction request.");
        actor(actor);
        if("approved".equals(input.status())){
            int changed=jdbc.update("UPDATE grades SET value=?,mark=NULL,state='finalized',version=version+1,updated_at=current_timestamp,updated_by=? WHERE id=? AND state='finalized'",correction.get("proposed_value"),actor.userId(),correction.get("grade_id"));
            if(changed==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Grade is not finalized or has changed.");
            jdbc.update("UPDATE grade_corrections SET status='applied',reviewed_by=?,reviewed_at=current_timestamp,review_reason=? WHERE id=?",actor.userId(),input.reason(),id);
        }else jdbc.update("UPDATE grade_corrections SET status='rejected',reviewed_by=?,reviewed_at=current_timestamp,review_reason=? WHERE id=?",actor.userId(),input.reason(),id);
        return jdbc.queryForMap("SELECT id,grade_id,proposed_value,status,requested_by,reviewed_by,reviewed_at,review_reason FROM grade_corrections WHERE id=?",id);
    }
    public List<Map<String,Object>> incomplete(StaffIdentity actor,String status){
        List<Long> scope=access.campuses(actor,"grades.review");if(scope.isEmpty())return List.of();var p=new MapSqlParameterSource().addValue("campuses",scope);String filter="";if(status!=null){p.addValue("status",status);filter=" AND ic.status=:status";}
        return db.queryForList("SELECT ic.id,ic.grade_id,ic.description,ic.status,ic.resolved_at,ic.resolved_by,g.class_id,g.enrollment_id,g.grading_period_id,co.subject_id,co.teacher_id,s.campus_id " +
            "FROM incomplete_cases ic JOIN grades g ON g.id=ic.grade_id JOIN class_offerings co ON co.id=g.class_id JOIN sections s ON s.id=co.section_id " +
            "WHERE s.campus_id IN (:campuses)"+filter+" ORDER BY ic.id DESC",p);
    }
    @Transactional public Map<String,Object> addIncomplete(StaffIdentity actor,AcademicController.IncompleteInput input){
        var rows=jdbc.queryForList("SELECT g.id,g.mark,s.campus_id FROM grades g JOIN class_offerings co ON co.id=g.class_id JOIN sections s ON s.id=co.section_id WHERE g.id=?",input.gradeId());
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Grade not found.");var grade=rows.getFirst();
        if(!access.campuses(actor,"grades.review").contains(((Number)grade.get("campus_id")).longValue()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Grade not found.");
        if(!"INC".equals(grade.get("mark")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Only INC grades can have an incomplete case.");actor(actor);
        long id=jdbc.queryForObject("INSERT INTO incomplete_cases(grade_id,description) VALUES (?,?) RETURNING id",Long.class,input.gradeId(),input.description().trim());
        return jdbc.queryForMap("SELECT id,grade_id,description,status,resolved_at,resolved_by FROM incomplete_cases WHERE id=?",id);
    }
    @Transactional public Map<String,Object> updateIncomplete(StaffIdentity actor,long id,AcademicController.IncompleteUpdate input){
        if(!Set.of("open","following_up","resolved").contains(input.status()))bad("Select a supported incomplete-case status.");
        var rows=jdbc.queryForList("SELECT ic.id,s.campus_id FROM incomplete_cases ic JOIN grades g ON g.id=ic.grade_id JOIN class_offerings co ON co.id=g.class_id JOIN sections s ON s.id=co.section_id WHERE ic.id=?",id);
        if(rows.isEmpty()||!access.campuses(actor,"grades.review").contains(((Number)rows.getFirst().get("campus_id")).longValue()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Incomplete case not found.");actor(actor);
        jdbc.update("UPDATE incomplete_cases SET status=?,resolved_at=CASE WHEN ?='resolved' THEN current_timestamp ELSE NULL END,resolved_by=CASE WHEN ?='resolved' THEN ? ELSE NULL END WHERE id=?",input.status(),input.status(),input.status(),actor.userId(),id);
        return jdbc.queryForMap("SELECT id,grade_id,description,status,resolved_at,resolved_by FROM incomplete_cases WHERE id=?",id);
    }
}
