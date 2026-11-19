package ph.edu.ici.academic;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.*;
import static ph.edu.ici.academic.GradeResolutionController.*;

@Service
public class GradeResolutionService {
    private final NamedParameterJdbcTemplate db;
    private final AccessPolicy access;
    public GradeResolutionService(NamedParameterJdbcTemplate db,AccessPolicy access){this.db=db;this.access=access;}
    private ResponseStatusException error(HttpStatus s,String message){return new ResponseStatusException(s,message);}
    private long actor(){return ((StaffIdentity)SecurityContextHolder.getContext().getAuthentication().getPrincipal()).userId();}
    private long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private void audit(){db.queryForObject("SELECT set_config('app.actor_id',:actor,true)",Map.of("actor",Long.toString(actor())),String.class);}
    private List<Long> campuses(String permission){var result=access.campuses(permission);return result.isEmpty()?List.of(-1L):result;}
    private MapSqlParameterSource params(){return new MapSqlParameterSource("actor",actor()).addValue("review",campuses("grades.review")).addValue("encode",campuses("grades.encode"));}
    private static final String SCOPE="(s.campus_id IN (:review) OR (s.campus_id IN (:encode) AND co.teacher_id=:actor))";
    private static final String LOCKED="(g.state='finalized' OR cw.locked_at IS NOT NULL OR sw.locked_at IS NOT NULL OR cw.deadline<=clock_timestamp() OR sw.deadline<=clock_timestamp())";
    private static final String FROM="""
        FROM grades g JOIN class_offerings co ON co.id=g.class_id JOIN sections s ON s.id=co.section_id
        JOIN enrollments e ON e.id=g.enrollment_id JOIN students st ON st.id=e.student_id
        JOIN subjects sub ON sub.id=co.subject_id JOIN users teacher ON teacher.id=co.teacher_id
        JOIN academic_terms t ON t.id=g.term_id JOIN academic_years y ON y.id=t.academic_year_id
        JOIN grading_periods gp ON gp.id=g.grading_period_id
        LEFT JOIN grading_windows cw ON cw.class_id=co.id AND cw.grading_period_id=gp.id
        LEFT JOIN section_grading_windows sw ON sw.section_id=s.id AND sw.grading_period_id=gp.id
        """;
    private static final String FIELDS="""
        g.id AS "gradeId",g.version AS "gradeVersion",g.class_id AS "classId",g.state AS "gradeState",
        g.value,g.mark,s.campus_id AS "campusId",st.id AS "studentId",st.student_number AS "studentNumber",
        concat_ws(' ',st.first_name,st.middle_name,st.last_name,st.suffix) AS "studentName",
        s.name AS section,s.grade_level AS "gradeLevel",sub.code AS "subjectCode",sub.name AS subject,
        co.teacher_id AS "teacherId",teacher.full_name AS teacher,teacher.username AS "teacherUsername",
        y.label AS "academicYear",gp.code AS "gradingPeriod"
        """;
    private Map<String,Object> grade(long id,boolean lock,boolean reviewOnly,boolean teacherOnly){
        var p=params().addValue("id",id);
        String scope=reviewOnly?"s.campus_id IN (:review)":teacherOnly?"s.campus_id IN (:encode) AND co.teacher_id=:actor":SCOPE;
        String sql="SELECT "+FIELDS+",coalesce("+LOCKED+",false) AS \"periodLocked\" "+FROM+" WHERE g.id=:id AND "+scope;
        var rows=db.queryForList(sql,p);
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Assigned grade not found.");
        if(lock){
            db.queryForList("SELECT id FROM class_offerings WHERE id=:class FOR UPDATE",Map.of("class",rows.getFirst().get("classId")));
            rows=db.queryForList(sql+" FOR UPDATE OF g",p);
            if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Assigned grade not found.");
        }
        return rows.getFirst();
    }
    private void expected(Map<String,Object> row,String key,int version){if(number(row,key)!=version)throw error(HttpStatus.CONFLICT,"This record changed. Reload before saving.");}
    private void requireInc(Map<String,Object> grade){if(!"INC".equals(grade.get("mark"))||"draft".equals(grade.get("gradeState")))throw error(HttpStatus.CONFLICT,"This case no longer has a submitted incomplete grade.");}
    private void checkRange(BigDecimal value){if(value==null||value.compareTo(BigDecimal.ZERO)<0||value.compareTo(new BigDecimal("100"))>0||value.stripTrailingZeros().scale()>2)throw error(HttpStatus.BAD_REQUEST,"Enter a grade from 0 to 100 with at most two decimal places.");}

    @Transactional
    public Map<String,Object> createCorrection(CorrectionInput input,MultipartFile file){
        var grade=grade(input.gradeId(),true,false,true);expected(grade,"gradeVersion",input.gradeVersion());
        if("draft".equals(grade.get("gradeState"))||!Boolean.TRUE.equals(grade.get("periodLocked")))throw error(HttpStatus.CONFLICT,"Formal corrections require a submitted grade in a locked period.");
        byte[] bytes;try{bytes=file.getBytes();}catch(IOException exception){throw error(HttpStatus.BAD_REQUEST,"Evidence could not be read.");}
        if(bytes.length==0||bytes.length>1000000)throw error(HttpStatus.BAD_REQUEST,"Evidence must contain between 1 and 1,000,000 bytes.");
        String filename=Optional.ofNullable(file.getOriginalFilename()).orElse("");
        if(filename.isBlank()||filename.length()>200||filename.contains("/")||filename.contains("\\")||filename.chars().anyMatch(c->c<32))throw error(HttpStatus.BAD_REQUEST,"Use a plain evidence filename without paths or control characters.");
        String type=mediaType(bytes);
        if(type==null||!type.equals(file.getContentType()))throw error(HttpStatus.BAD_REQUEST,"Upload a PDF, PNG or JPEG whose content matches its media type.");
        String hash;try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception exception){throw new IllegalStateException(exception);}
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM grade_corrections WHERE grade_id=:grade AND status='pending')",Map.of("grade",input.gradeId()),Boolean.class)))throw error(HttpStatus.CONFLICT,"This grade already has a pending correction.");
        audit();String key=UUID.randomUUID().toString();
        var p=new MapSqlParameterSource("grade",input.gradeId()).addValue("value",grade.get("value")).addValue("mark",grade.get("mark"))
            .addValue("proposed",input.proposedValue()).addValue("reason",input.justification().trim()).addValue("key",key).addValue("actor",actor()).addValue("version",input.gradeVersion());
        long id=db.queryForObject("""
            INSERT INTO grade_corrections(grade_id,original_value,original_mark,proposed_value,justification,evidence_storage_key,requested_by,grade_version)
            VALUES(:grade,:value,:mark,:proposed,:reason,:key,:actor,:version) RETURNING id
            """,p,Long.class);
        db.update("INSERT INTO grade_correction_evidence(correction_id,storage_key,filename,media_type,content,sha256) VALUES(:id,:key,:filename,:type,:content,:hash)",
            new MapSqlParameterSource("id",id).addValue("key",key).addValue("filename",filename).addValue("type",type).addValue("content",bytes).addValue("hash",hash));
        return correction(id);
    }
    private String mediaType(byte[] bytes){
        if(bytes.length>=5&&new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))return "application/pdf";
        if(bytes.length>=8&&Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10}))return "image/png";
        if(bytes.length>=3&&(bytes[0]&255)==255&&(bytes[1]&255)==216&&(bytes[2]&255)==255)return "image/jpeg";
        return null;
    }
    private long correctionGrade(long id){
        var rows=db.queryForList("SELECT c.grade_id "+FROM+" JOIN grade_corrections c ON c.grade_id=g.id WHERE c.id=:id AND "+SCOPE,params().addValue("id",id));
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Correction request not found.");return number(rows.getFirst(),"grade_id");
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> correction(long id){
        var grade=grade(correctionGrade(id),false,false,false);
        var result=new LinkedHashMap<String,Object>(db.queryForMap("""
            SELECT c.id,c.grade_id AS "gradeId",c.original_value AS "originalValue",c.original_mark AS "originalMark",
                   c.proposed_value AS "proposedValue",c.grade_version AS "requestedGradeVersion",c.version,c.justification,c.status,
                   c.requested_by AS "requestedBy",u.full_name AS "requestedByName",c.requested_at AS "requestedAt",
                   c.reviewed_by AS "reviewedBy",c.reviewed_at AS "reviewedAt",c.review_reason AS "reviewReason"
            FROM grade_corrections c JOIN users u ON u.id=c.requested_by WHERE c.id=:id
            """,Map.of("id",id)));
        result.put("grade",grade);result.put("evidenceUrl","/api/academic/grade-corrections/"+id+"/evidence");return result;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> corrections(String status,int page,int size){
        bounds(page,size);if(!Set.of("pending","applied","rejected","all").contains(status))throw error(HttpStatus.BAD_REQUEST,"Unknown correction status.");
        var p=params().addValue("status",status).addValue("size",size).addValue("offset",(long)page*size);
        String from=FROM+" JOIN grade_corrections c ON c.grade_id=g.id WHERE "+SCOPE+(status.equals("all")?"":" AND c.status=:status");
        long total=db.queryForObject("SELECT count(*) "+from,p,Long.class);
        var items=db.queryForList("SELECT "+FIELDS+",c.id,c.status,c.version,c.proposed_value AS \"proposedValue\",c.justification,c.requested_at AS \"requestedAt\" "+from+" ORDER BY c.requested_at DESC,c.id DESC LIMIT :size OFFSET :offset",p);
        return Map.of("items",items,"total",total,"page",page,"size",size);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> evidence(long id){grade(correctionGrade(id),false,false,false);var rows=db.queryForList("SELECT filename,media_type,content FROM grade_correction_evidence WHERE correction_id=:id",Map.of("id",id));if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Evidence is unavailable.");return rows.getFirst();}
    @Transactional
    public Map<String,Object> reviewCorrection(long id,ReviewInput input){
        var grade=grade(correctionGrade(id),true,true,false);
        var request=db.queryForMap("SELECT * FROM grade_corrections WHERE id=:id FOR UPDATE",Map.of("id",id));
        expected(request,"version",input.version());
        if(!"pending".equals(request.get("status")))throw error(HttpStatus.CONFLICT,"This correction has already been decided.");
        if(input.decision().equals("approve")){
            if(number(request,"requested_by")==actor())throw error(HttpStatus.FORBIDDEN,"A different authorized Registrar must approve the teacher request.");
            expected(grade,"gradeVersion",input.gradeVersion());expected(grade,"gradeVersion",((Number)request.get("grade_version")).intValue());
            if(!Objects.equals(grade.get("value"),request.get("original_value"))||!Objects.equals(grade.get("mark"),request.get("original_mark")))throw error(HttpStatus.CONFLICT,"The original grade no longer matches this request.");
            if("draft".equals(grade.get("gradeState")))throw error(HttpStatus.CONFLICT,"Draft grades cannot be corrected through approval.");
        }
        audit();
        if(input.decision().equals("approve")){
            db.update("UPDATE grades SET value=:value,mark=NULL,version=version+1 WHERE id=:grade",Map.of("value",request.get("proposed_value"),"grade",grade.get("gradeId")));
            closeCases(number(grade,"gradeId"));
        }
        db.update("UPDATE grade_corrections SET status=:status,reviewed_by=:actor,reviewed_at=clock_timestamp(),review_reason=:reason,version=version+1 WHERE id=:id",Map.of("status",input.decision().equals("approve")?"applied":"rejected","actor",actor(),"reason",input.reason().trim(),"id",id));
        return correction(id);
    }
    private void closeCases(long gradeId){db.update("UPDATE incomplete_cases SET status='resolved',resolved_at=clock_timestamp(),resolved_by=:actor,version=version+1 WHERE grade_id=:grade AND status<>'resolved'",Map.of("actor",actor(),"grade",gradeId));}
    private void bounds(int page,int size){if(page<0||size<1||size>100)throw error(HttpStatus.BAD_REQUEST,"Use page >= 0 and size between 1 and 100.");}
    private static final String CASE_STATUS="CASE WHEN ic.status='resolved' THEN 'completed' WHEN ic.proposed_value IS NULL THEN 'pending_teacher_submission' WHEN coalesce("+LOCKED+",false) THEN 'grade_correction' ELSE 'registrar_review' END";
    private static final String CASE_FIELDS=FIELDS+",ic.id,ic.version,ic.status AS \"caseState\",ic.proposed_value AS \"proposedGrade\",ic.proposed_grade_version AS \"proposedGradeVersion\",ic.updated_at AS \"updatedAt\",coalesce("+LOCKED+",false) AS \"periodLocked\","+CASE_STATUS+" AS status";
    private Map<String,Object> displayCase(Map<String,Object> source){var row=new LinkedHashMap<String,Object>(source);row.put("id",row.get("id").toString());row.put("canComplete",access.campuses("grades.review").contains(number(row,"campusId")));row.put("canPropose",access.campuses("grades.encode").contains(number(row,"campusId"))&&number(row,"teacherId")==actor());row.put("currentMark",row.get("mark")!=null?row.get("mark"):row.get("value")!=null?row.get("value").toString():"Not recorded");return row;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> cases(int page,int size,String search,String status){
        bounds(page,size);var p=params().addValue("size",size).addValue("offset",(long)page*size);
        String from=FROM+" JOIN incomplete_cases ic ON ic.grade_id=g.id WHERE "+SCOPE;
        if(search!=null&&!search.isBlank()){from+=" AND (concat_ws(' ',st.first_name,st.last_name,st.student_number,sub.code,sub.name) ILIKE :search)";p.addValue("search","%"+search.trim()+"%");}
        if(status!=null&&!status.isBlank()){if(!Set.of("pending_teacher_submission","registrar_review","grade_correction","completed").contains(status))throw error(HttpStatus.BAD_REQUEST,"Unknown incomplete-case status.");from+=" AND ("+CASE_STATUS+")=:status";p.addValue("status",status);}
        long total=db.queryForObject("SELECT count(*) "+from,p,Long.class);
        var items=db.queryForList("SELECT "+CASE_FIELDS+" "+from+" ORDER BY ic.id LIMIT :size OFFSET :offset",p).stream().map(this::displayCase).toList();
        var filterRows=db.queryForList("SELECT DISTINCT "+"s.grade_level AS \"gradeLevel\",s.name AS section,sub.name AS subject,teacher.full_name AS teacher,y.label AS \"academicYear\",gp.code AS \"gradingPeriod\","+CASE_STATUS+" AS status "+FROM+" JOIN incomplete_cases ic ON ic.grade_id=g.id WHERE "+SCOPE,p);
        var filters=new LinkedHashMap<String,Object>();for(String key:List.of("gradeLevel","section","subject","teacher","academicYear","gradingPeriod","status"))filters.put(key,filterRows.stream().map(r->r.get(key)).filter(Objects::nonNull).map(Object::toString).distinct().sorted().toList());
        return Map.of("items",items,"filters",filters,"total",total,"page",page,"size",size);
    }
    private long caseGrade(long id){var rows=db.queryForList("SELECT ic.grade_id "+FROM+" JOIN incomplete_cases ic ON ic.grade_id=g.id WHERE ic.id=:id AND "+SCOPE,params().addValue("id",id));if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Incomplete case not found.");return number(rows.getFirst(),"grade_id");}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> incomplete(long id){
        grade(caseGrade(id),false,false,false);
        var row=displayCase(db.queryForMap("SELECT "+CASE_FIELDS+" "+FROM+" JOIN incomplete_cases ic ON ic.grade_id=g.id WHERE ic.id=:id AND "+SCOPE,params().addValue("id",id)));
        row.put("followUps",db.queryForList("SELECT f.id,f.note,f.method,u.full_name AS by,f.created_at AS at FROM incomplete_follow_ups f JOIN users u ON u.id=f.author_id WHERE f.case_id=:id ORDER BY f.created_at DESC,f.id DESC",Map.of("id",id)));
        return row;
    }
    private Map<String,Object> lockCase(long id,int version){
        var row=db.queryForMap("SELECT * FROM incomplete_cases WHERE id=:id FOR UPDATE",Map.of("id",id));expected(row,"version",version);
        if("resolved".equals(row.get("status")))throw error(HttpStatus.CONFLICT,"This incomplete case is already completed.");return row;
    }
    private void note(long id,String text,String method){db.update("INSERT INTO incomplete_follow_ups(case_id,note,method,author_id) VALUES(:id,:note,:method,:actor)",Map.of("id",id,"note",text.trim(),"method",method,"actor",actor()));}
    @Transactional
    public Map<String,Object> follow(long id,FollowUpInput input){
        var grade=grade(caseGrade(id),true,false,false);lockCase(id,input.version());requireInc(grade);audit();note(id,input.note(),input.method());
        db.update("UPDATE incomplete_cases SET status='following_up',version=version+1 WHERE id=:id",Map.of("id",id));return incomplete(id);
    }
    @Transactional
    public Map<String,Object> propose(long id,CompletionInput input){
        var grade=grade(caseGrade(id),true,false,true);lockCase(id,input.version());requireInc(grade);expected(grade,"gradeVersion",input.gradeVersion());checkRange(input.grade());audit();
        db.update("UPDATE incomplete_cases SET proposed_value=:value,proposed_by=:actor,proposed_at=clock_timestamp(),proposed_grade_version=:version,version=version+1 WHERE id=:id",Map.of("value",input.grade(),"actor",actor(),"version",input.gradeVersion(),"id",id));
        note(id,input.note(),"System note");return incomplete(id);
    }
    @Transactional
    public Map<String,Object> complete(long id,CompletionInput input){
        long gradeId=caseGrade(id);var grade=grade(gradeId,true,true,false);var current=lockCase(id,input.version());requireInc(grade);expected(grade,"gradeVersion",input.gradeVersion());
        if(Boolean.TRUE.equals(grade.get("periodLocked")))throw error(HttpStatus.FORBIDDEN,"Locked periods require formal correction evidence and approval.");
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM grade_corrections WHERE grade_id=:grade AND status='pending')",Map.of("grade",gradeId),Boolean.class)))throw error(HttpStatus.CONFLICT,"Resolve the pending formal correction first.");
        BigDecimal value=input.grade()!=null?input.grade():(BigDecimal)current.get("proposed_value");checkRange(value);
        if(input.grade()==null&&(current.get("proposed_grade_version")==null||((Number)current.get("proposed_grade_version")).intValue()!=input.gradeVersion()))throw error(HttpStatus.CONFLICT,"The proposal references an older grade version.");
        audit();db.update("UPDATE grades SET value=:value,mark=NULL,version=version+1 WHERE id=:grade",Map.of("value",value,"grade",gradeId));closeCases(gradeId);note(id,input.note(),"System note");
        if(Boolean.TRUE.equals(grade(gradeId,false,true,false).get("periodLocked")))throw error(HttpStatus.FORBIDDEN,"The submission deadline passed; use formal correction approval.");
        return incomplete(id);
    }
}
