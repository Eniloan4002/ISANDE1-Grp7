package ph.edu.ici.enrollment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
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
public class EnrollmentService {
    private static final Set<String> CATEGORIES=Set.of("new","transferee","returnee","continuing");
    private static final Set<String> REQUIREMENT_STATES=Set.of("submitted","missing","pending","invalid");
    private final JdbcTemplate jdbc;private final NamedParameterJdbcTemplate db;private final ObjectMapper json;private final AccessScope access;
    public EnrollmentService(JdbcTemplate jdbc,ObjectMapper json,AccessScope access){this.jdbc=jdbc;this.db=new NamedParameterJdbcTemplate(jdbc);this.json=json;this.access=access;}
    private List<Long> campuses(StaffIdentity actor){return access.campuses(actor,"enrollment.manage");}
    private void actor(StaffIdentity who){access.actor(who);}
    private void bad(String message){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private void found(boolean exists,String type){if(!exists)throw new ResponseStatusException(HttpStatus.NOT_FOUND,type+" not found.");}
    private Map<String,Object> applicationRow(StaffIdentity actor,long id){
        List<Long> scope=campuses(actor);if(scope.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Application not found.");
        List<Map<String,Object>> rows=db.queryForList("SELECT a.*,c.code AS campus_code,p.code AS program_code " +
            "FROM applications a JOIN campuses c ON c.id=a.campus_id JOIN programs p ON p.id=a.program_id " +
            "WHERE a.id=:id AND a.campus_id IN (:campuses)",new MapSqlParameterSource().addValue("id",id).addValue("campuses",scope));
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Application not found.");return rows.getFirst();
    }
    public Map<String,Object> applications(StaffIdentity actor,Long campusId,String status,int page,int size){
        if(page<0||size<1||size>100)bad("Use a nonnegative page and a size of 1–100.");
        List<Long> scope=campuses(actor);if(campusId!=null){access.requireCampus(actor,"enrollment.manage",campusId);scope=List.of(campusId);}
        if(scope.isEmpty())return Map.of("items",List.of(),"total",0,"page",page,"size",size);
        var p=new MapSqlParameterSource().addValue("campuses",scope).addValue("limit",size).addValue("offset",(long)page*size);
        String where=" WHERE a.campus_id IN (:campuses)";if(status!=null){p.addValue("status",status);where+=" AND a.status=:status";}
        long total=db.queryForObject("SELECT count(*) FROM applications a"+where,p,Long.class);
        var items=db.queryForList("SELECT a.id,a.application_number,a.student_id,a.campus_id,a.term_id,a.program_id,a.category,a.status,a.submitted_at,a.updated_at " +
            "FROM applications a"+where+" ORDER BY a.submitted_at DESC,a.id DESC LIMIT :limit OFFSET :offset",p);
        return Map.of("items",items,"total",total,"page",page,"size",size);
    }
    @Transactional public Map<String,Object> createApplication(StaffIdentity actor,EnrollmentController.ApplicationInput input){
        access.requireCampus(actor,"enrollment.manage",input.campusId());
        if(!CATEGORIES.contains(input.category()))bad("Select a supported application category.");
        Map<String,Object> student;
        try{student=jdbc.queryForMap("SELECT id,campus_id,program_id,first_name,middle_name,last_name,birth_date,lrn,phone,email,previous_school_name,address,voucher_code,track " +
            "FROM students WHERE id=? AND active",input.studentId());}
        catch(org.springframework.dao.EmptyResultDataAccessException exception){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Student not found.");}
        if(student.get("campus_id")==null||((Number)student.get("campus_id")).longValue()!=input.campusId())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Student campus does not match this application.");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM academic_terms WHERE id=?)",Boolean.class,input.termId())))bad("Select an existing academic term.");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM programs WHERE id=?)",Boolean.class,input.programId())))bad("Select an existing program.");
        actor(actor);String number="APP-"+UUID.randomUUID().toString().substring(0,18).toUpperCase(Locale.ROOT);
        String profile;try{profile=json.writeValueAsString(Map.of("address",student.get("address")==null?Map.of():student.get("address"),"track",Objects.toString(student.get("track"),"")));}
        catch(JsonProcessingException exception){throw new IllegalStateException("Could not encode application profile",exception);}
        long id=jdbc.queryForObject("INSERT INTO applications(application_number,campus_id,term_id,program_id,student_id,category,first_name,middle_name,last_name,birth_date,lrn,contact_phone,email,previous_school,profile,voucher_code,status,created_by,updated_by) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,'submitted',?,?) RETURNING id",Long.class,number,input.campusId(),input.termId(),input.programId(),input.studentId(),input.category(),
            student.get("first_name"),student.get("middle_name"),student.get("last_name"),student.get("birth_date"),student.get("lrn"),student.get("phone"),student.get("email"),student.get("previous_school_name"),profile,student.get("voucher_code"),actor.userId(),actor.userId());
        jdbc.update("INSERT INTO application_requirements(application_id,requirement_code,status) SELECT ?,code,'missing' FROM requirement_types ON CONFLICT DO NOTHING",id);
        detectDuplicate(id,input.studentId(),input.campusId(),student);
        return application(actor,id);
    }
    private void detectDuplicate(long applicationId,long studentId,long campusId,Map<String,Object> student){
        List<Map<String,Object>> matches=jdbc.queryForList("SELECT id AS application_id,student_id FROM applications WHERE id<>? AND campus_id=? AND " +
            "((CAST(? AS varchar) IS NOT NULL AND lrn=?) OR (lower(btrim(first_name))=lower(btrim(?)) AND lower(btrim(last_name))=lower(btrim(?)) AND birth_date=?)) " +
            "ORDER BY CASE WHEN CAST(? AS varchar) IS NOT NULL AND lrn=? THEN 0 ELSE 1 END,id LIMIT 1",applicationId,campusId,student.get("lrn"),student.get("lrn"),student.get("first_name"),student.get("last_name"),student.get("birth_date"),student.get("lrn"),student.get("lrn"));
        if(matches.isEmpty())return;var match=matches.getFirst();Long matchedApplication=(Long)match.get("application_id");
        Map<String,Object> evidence=Map.of("matchType",student.get("lrn")!=null?"lrn-or-identity":"name-and-birth-date");String encoded;
        try{encoded=json.writeValueAsString(evidence);}catch(JsonProcessingException exception){encoded="{}";}
        jdbc.update("INSERT INTO duplicate_cases(application_id,matched_application_id,matched_student_id,evidence) VALUES (?,?,?,CAST(? AS jsonb))",
            applicationId,matchedApplication,matchedApplication==null?studentId:null,encoded);
        jdbc.update("UPDATE applications SET status='duplicate_flag' WHERE id=?",applicationId);
    }
    public Map<String,Object> application(StaffIdentity actor,long id){
        var row=applicationRow(actor,id);row.put("requirements",jdbc.queryForList("SELECT r.requirement_code,t.name,r.status,r.storage_key,r.original_filename,r.verified_by,r.updated_at " +
            "FROM application_requirements r JOIN requirement_types t ON t.code=r.requirement_code WHERE r.application_id=? ORDER BY r.requirement_code",id));
        row.put("orientation",jdbc.queryForList("SELECT attendance_date,status,recorded_by,recorded_at FROM orientation_attendance WHERE application_id=? ORDER BY attendance_date",id));
        row.put("duplicate_cases",jdbc.queryForList("SELECT id,matched_application_id,matched_student_id,evidence,status,reason FROM duplicate_cases WHERE application_id=? ORDER BY id",id));
        return row;
    }
    @Transactional public Map<String,Object> status(StaffIdentity actor,long id,EnrollmentController.ApplicationStatusInput input){
        Map<String,Object> row=applicationRow(actor,id);String from=Objects.toString(row.get("status"),"");String to=input.status();
        boolean allowed=switch(from){case "submitted"->Set.of("under_review","rejected").contains(to);case "under_review"->Set.of("pre_enlisted","rejected").contains(to);case "duplicate_flag"->"under_review".equals(to);default->false;};
        if(!allowed)throw new ResponseStatusException(HttpStatus.CONFLICT,"Application status transition is not allowed.");
        if("under_review".equals(to)&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM duplicate_cases WHERE application_id=? AND status='open')",Boolean.class,id)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Resolve open duplicate cases before returning the application to review.");
        actor(actor);jdbc.update("UPDATE applications SET status=? WHERE id=?",to,id);return applicationRow(actor,id);
    }
    public List<Map<String,Object>> duplicates(StaffIdentity actor,Long campusId){
        List<Long> scope=campuses(actor);if(campusId!=null){access.requireCampus(actor,"enrollment.manage",campusId);scope=List.of(campusId);}
        if(scope.isEmpty())return List.of();
        return db.queryForList("SELECT d.id,d.application_id,d.matched_application_id,d.matched_student_id,d.evidence,d.status,d.reason,a.application_number,a.campus_id " +
            "FROM duplicate_cases d JOIN applications a ON a.id=d.application_id WHERE a.campus_id IN (:campuses) ORDER BY d.id DESC",
            new MapSqlParameterSource().addValue("campuses",scope));
    }
    @Transactional public Map<String,Object> duplicate(StaffIdentity actor,long id,EnrollmentController.DuplicateInput input){
        var app=applicationRow(actor,id);Long appId=input.matchedApplicationId(),studentId=input.matchedStudentId();
        if((appId==null)==(studentId==null))bad("Provide exactly one matched application or student.");
        if(appId!=null){
            boolean exists=Boolean.TRUE.equals(new NamedParameterJdbcTemplate(jdbc).queryForObject("SELECT EXISTS(SELECT 1 FROM applications WHERE id=:target AND id<>:id AND campus_id IN (:campuses))",
                new MapSqlParameterSource().addValue("target",appId).addValue("id",id).addValue("campuses",campuses(actor)),Boolean.class));
            found(exists,"Matched application");
        }else found(Boolean.TRUE.equals(new NamedParameterJdbcTemplate(jdbc).queryForObject("SELECT EXISTS(SELECT 1 FROM students WHERE id=:id AND campus_id IN (:campuses))",
            new MapSqlParameterSource().addValue("id",studentId).addValue("campuses",campuses(actor)),Boolean.class)),"Matched student");
        actor(actor);String evidence;try{evidence=json.writeValueAsString(input.evidence()==null?Map.of():input.evidence());}catch(JsonProcessingException exception){bad("Invalid duplicate evidence.");return Map.of();}
        Long duplicateId=jdbc.queryForObject("INSERT INTO duplicate_cases(application_id,matched_application_id,matched_student_id,evidence) VALUES (?,?,?,CAST(? AS jsonb)) RETURNING id",Long.class,id,appId,studentId,evidence);
        jdbc.update("UPDATE applications SET status='duplicate_flag' WHERE id=?",id);
        return jdbc.queryForMap("SELECT id,application_id,matched_application_id,matched_student_id,evidence,status FROM duplicate_cases WHERE id=?",duplicateId);
    }
    @Transactional public Map<String,Object> resolveDuplicate(StaffIdentity actor,long id,EnrollmentController.DuplicateResolution input){
        if(!Set.of("distinct","rejected","merged").contains(input.status()))bad("Status must be distinct, rejected, or merged.");
        List<Long> scope=campuses(actor);var params=new MapSqlParameterSource().addValue("id",id).addValue("campuses",scope);
        List<Map<String,Object>> cases=db.queryForList("SELECT d.*,a.status AS application_status,a.student_id AS application_student_id " +
            "FROM duplicate_cases d JOIN applications a ON a.id=d.application_id WHERE d.id=:id AND a.campus_id IN (:campuses) FOR UPDATE",params);
        if(cases.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Duplicate case not found.");
        var row=cases.getFirst();if(!"open".equals(row.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Duplicate case has already been resolved.");
        actor(actor);
        if("merged".equals(input.status())){
            Object matchedStudent=row.get("matched_student_id");
            if(matchedStudent==null&&row.get("matched_application_id")!=null)matchedStudent=jdbc.queryForObject("SELECT student_id FROM applications WHERE id=?",Long.class,row.get("matched_application_id"));
            if(matchedStudent==null)bad("A duplicate application without a linked student cannot be merged.");
            if("enrolled".equals(row.get("application_status")))bad("An enrolled application requires the controlled record-merge workflow.");
            jdbc.update("UPDATE applications SET student_id=? WHERE id=?",matchedStudent,row.get("application_id"));
        }
        jdbc.update("UPDATE duplicate_cases SET status=?,resolved_by=?,resolved_at=?,reason=? WHERE id=?",input.status(),actor.userId(),Instant.now(),input.reason().trim(),id);
        if("duplicate_flag".equals(row.get("application_status")))jdbc.update("UPDATE applications SET status='under_review' WHERE id=? AND NOT EXISTS(SELECT 1 FROM duplicate_cases WHERE application_id=? AND status='open')",
            row.get("application_id"),row.get("application_id"));
        return jdbc.queryForMap("SELECT id,application_id,matched_application_id,matched_student_id,evidence,status,reason,resolved_by,resolved_at FROM duplicate_cases WHERE id=?",id);
    }
    @Transactional public Map<String,Object> requirement(StaffIdentity actor,long id,String code,EnrollmentController.RequirementInput input){
        applicationRow(actor,id);if(!REQUIREMENT_STATES.contains(input.status()))bad("Select a supported requirement status.");
        found(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM requirement_types WHERE code=?)",Boolean.class,code)),"Requirement type");actor(actor);
        jdbc.update("INSERT INTO application_requirements(application_id,requirement_code,status,storage_key,original_filename,verified_by) VALUES (?,?,?,?,?,?) " +
            "ON CONFLICT(application_id,requirement_code) DO UPDATE SET status=EXCLUDED.status,storage_key=EXCLUDED.storage_key,original_filename=EXCLUDED.original_filename,verified_by=EXCLUDED.verified_by,updated_at=current_timestamp",
            id,code,input.status(),input.storageKey(),input.originalFilename(),"submitted".equals(input.status())?actor.userId():null);
        return jdbc.queryForMap("SELECT application_id,requirement_code,status,storage_key,original_filename,verified_by,updated_at FROM application_requirements WHERE application_id=? AND requirement_code=?",id,code);
    }
    @Transactional public Map<String,Object> confirm(StaffIdentity actor,long id){
        var app=applicationRow(actor,id);Long student=(Long)app.get("student_id");if(student==null)bad("Link the application to a student profile before confirmation.");
        if(!"pre_enlisted".equals(app.get("status"))&& !"under_review".equals(app.get("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Only pre-enlisted applications can be confirmed.");
        boolean enrolled=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM enrollments WHERE application_id=?)",Boolean.class,id));
        if(enrolled)throw new ResponseStatusException(HttpStatus.CONFLICT,"Application already has an enrollment.");
        actor(actor);long enrollment=jdbc.queryForObject("INSERT INTO enrollments(student_id,application_id,campus_id,term_id,program_id,status,attendance_verified_at,attendance_verified_by,confirmed_at,confirmed_by) " +
            "VALUES (?,?,?,?,?,'enrolled',current_timestamp,?,current_timestamp,?) RETURNING id",Long.class,student,id,app.get("campus_id"),app.get("term_id"),app.get("program_id"),actor.userId(),actor.userId());
        jdbc.update("UPDATE applications SET status='enrolled' WHERE id=?",id);
        return jdbc.queryForMap("SELECT id,student_id,application_id,campus_id,term_id,program_id,section_id,status,attendance_verified_at,confirmed_at FROM enrollments WHERE id=?",enrollment);
    }
    public List<Map<String,Object>> sections(StaffIdentity actor,Long campusId,Long termId,Long programId){
        List<Long> scope=campuses(actor);if(campusId!=null){access.requireCampus(actor,"enrollment.manage",campusId);scope=List.of(campusId);}if(scope.isEmpty())return List.of();
        var p=new MapSqlParameterSource().addValue("campuses",scope);String filter="";if(termId!=null){p.addValue("term",termId);filter+=" AND s.term_id=:term";}if(programId!=null){p.addValue("program",programId);filter+=" AND s.program_id=:program";}
        return db.queryForList("SELECT s.id,s.campus_id,s.term_id,s.program_id,s.name,s.grade_level,s.room,s.capacity,s.adviser_id,"+
            "(SELECT count(*) FROM enrollments e WHERE e.section_id=s.id AND e.status IN ('provisional','enrolled')) AS occupied " +
            "FROM sections s WHERE s.campus_id IN (:campuses)"+filter+" ORDER BY s.term_id,s.grade_level,s.name",p);
    }
    @Transactional public Map<String,Object> createSection(StaffIdentity actor,EnrollmentController.SectionInput input){
        access.requireCampus(actor,"enrollment.manage",input.campusId());validateAdviser(input.adviserId(),input.campusId());actor(actor);
        try{long id=jdbc.queryForObject("INSERT INTO sections(campus_id,term_id,program_id,name,grade_level,room,capacity,adviser_id) VALUES (?,?,?,?,?,?,?,?) RETURNING id",Long.class,
            input.campusId(),input.termId(),input.programId(),input.name().trim(),input.gradeLevel().trim(),input.room(),input.capacity(),input.adviserId());
            return section(actor,id);
        }catch(org.springframework.dao.DuplicateKeyException exception){throw new ResponseStatusException(HttpStatus.CONFLICT,"A section with this name already exists for the term and campus.");}
    }
    @Transactional public Map<String,Object> updateSection(StaffIdentity actor,long id,EnrollmentController.SectionUpdate input){
        Map<String,Object> current=section(actor,id);long campus=((Number)current.get("campus_id")).longValue();
        validateAdviser(input.adviserId(),campus);actor(actor);
        try{jdbc.update("UPDATE sections SET capacity=?,room=?,adviser_id=? WHERE id=?",input.capacity(),input.room(),input.adviserId(),id);}
        catch(org.springframework.dao.DataIntegrityViolationException exception){throw new ResponseStatusException(HttpStatus.CONFLICT,"Section capacity cannot be lower than current enrollment.");}
        return section(actor,id);
    }
    private void validateAdviser(Long adviserId,long campus){if(adviserId==null)return;
        boolean valid=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM user_role_grants WHERE user_id=? AND role_code='ROLE_ADVISER' AND campus_id=?)",Boolean.class,adviserId,campus));
        if(!valid)bad("Choose an adviser assigned to this campus.");}
    private Map<String,Object> section(StaffIdentity actor,long id){
        List<Long> scope=campuses(actor);if(scope.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Section not found.");
        List<Map<String,Object>> rows=db.queryForList("SELECT s.id,s.campus_id,s.term_id,s.program_id,s.name,s.grade_level,s.room,s.capacity,s.adviser_id,"+
            "(SELECT count(*) FROM enrollments e WHERE e.section_id=s.id AND e.status IN ('provisional','enrolled')) AS occupied FROM sections s WHERE s.id=:id AND s.campus_id IN (:campuses)",
            new MapSqlParameterSource().addValue("id",id).addValue("campuses",scope));
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Section not found.");return rows.getFirst();
    }
    @Transactional public Map<String,Object> assignSection(StaffIdentity actor,long enrollmentId,EnrollmentController.SectionAssignment input){
        List<Long> scope=campuses(actor);var rows=db.queryForList("SELECT id,campus_id,status FROM enrollments WHERE id=:id AND campus_id IN (:campuses) FOR UPDATE",
            new MapSqlParameterSource().addValue("id",enrollmentId).addValue("campuses",scope));
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Enrollment not found.");
        var enrollment=rows.getFirst();var targetSection=section(actor,input.sectionId());
        if(!Objects.equals(enrollment.get("campus_id"),targetSection.get("campus_id")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Section and enrollment must belong to the same campus.");
        actor(actor);
        try{
            jdbc.update("DELETE FROM class_memberships WHERE enrollment_id=?",enrollmentId);
            jdbc.update("UPDATE enrollments SET section_id=? WHERE id=? AND campus_id IN ("+String.join(",",Collections.nCopies(scope.size(),"?"))+")",params(input.sectionId(),enrollmentId,scope));
            jdbc.update("INSERT INTO class_memberships(class_id,enrollment_id,section_id,term_id) SELECT c.id,e.id,e.section_id,e.term_id " +
                "FROM enrollments e JOIN class_offerings c ON c.section_id=e.section_id AND c.term_id=e.term_id WHERE e.id=? AND e.section_id IS NOT NULL",enrollmentId);
        }
        catch(org.springframework.dao.DataIntegrityViolationException exception){throw new ResponseStatusException(HttpStatus.CONFLICT,"Section capacity or term constraints prevented assignment.");}
        return jdbc.queryForMap("SELECT id,student_id,application_id,campus_id,term_id,program_id,section_id,status FROM enrollments WHERE id=?",enrollmentId);
    }
    private Object[] params(Long section,long enrollment,List<Long> campuses){List<Object> values=new ArrayList<>();values.add(section);values.add(enrollment);values.addAll(campuses);return values.toArray();}
}
