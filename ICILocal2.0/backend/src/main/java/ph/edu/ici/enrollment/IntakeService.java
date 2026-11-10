package ph.edu.ici.enrollment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.Normalizer;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.StaffIdentity;
import ph.edu.ici.auth.AccessPolicy;
import ph.edu.ici.students.*;

@Service
public class IntakeService {
    private final NamedParameterJdbcTemplate db;
    private final AccessPolicy accessPolicy;
    private final ObjectMapper json;
    private final StudentService students;
    private final EnrollmentService enrollment;
    private final DuplicateService duplicates;
    public IntakeService(NamedParameterJdbcTemplate db,ObjectMapper json,StudentService students,EnrollmentService enrollment,DuplicateService duplicates,AccessPolicy accessPolicy){this.accessPolicy=accessPolicy;this.db=db;this.json=json;this.students=students;this.enrollment=enrollment;this.duplicates=duplicates;}
    private StaffIdentity identity(){return (StaffIdentity)SecurityContextHolder.getContext().getAuthentication().getPrincipal();}
    private List<Long> scope(){return accessPolicy.campuses("enrollment.manage");}
    private ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    private String text(String value){return value==null||value.isBlank()?null:value.trim();}
    private void actor(){db.queryForObject("SELECT set_config('app.actor_id',:actor,true)",Map.of("actor",Long.toString(identity().userId())),String.class);}
    private Map<String,Object> row(long id,boolean lock){
        var rows=db.queryForList("SELECT * FROM applications WHERE id=:id AND campus_id IN (:scope)"+(lock?" FOR UPDATE":""),Map.of("id",id,"scope",scope()));
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Application not found.");return rows.getFirst();
    }
    private Map<String,Object> profile(Map<String,Object> row){try{return json.readValue(row.get("profile").toString(),new TypeReference<Map<String,Object>>(){});}catch(Exception e){throw new IllegalStateException("Invalid stored application profile",e);}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw error(HttpStatus.BAD_REQUEST,"Invalid application profile.");}}
    @Transactional(readOnly=true)
    public Map<String,Object> reference(){return enrollment.reference(true);}
    @Transactional(readOnly=true)
    public Map<String,Object> statistics(){
        return db.queryForMap("""
            SELECT count(*) AS total,count(*) FILTER(WHERE status='submitted') AS submitted,
              count(*) FILTER(WHERE status='pre_enlisted') AS "preEnlisted",
              count(*) FILTER(WHERE status='duplicate_flag' OR EXISTS(SELECT 1 FROM duplicate_cases d WHERE (d.application_id=a.id OR d.matched_application_id=a.id) AND d.status='open')) AS duplicates,
              count(*) FILTER(WHERE status='enrolled') AS enrolled
            FROM applications a WHERE campus_id IN (:scope)
            """,Map.of("scope",scope()));
    }
    @Transactional(readOnly=true)
    public Map<String,Object> detail(long id){
        var row=row(id,false);var result=new LinkedHashMap<String,Object>();
        for(var field:List.of("id","category","status","version"))result.put(field,row.get(field));
        String[][] fields={{"applicationNumber","application_number"},{"firstName","first_name"},{"middleName","middle_name"},{"lastName","last_name"},{"birthDate","birth_date"},{"campusId","campus_id"},{"programId","program_id"},{"termId","term_id"},{"phone","contact_phone"},{"email","email"},{"lrn","lrn"},{"voucher","voucher_code"},{"previousSchool","previous_school"},{"studentId","student_id"}};
        for(var field:fields)result.put(field[0],row.get(field[1]));
        result.put("submittedAt",row.get("submitted_at").toString().substring(0,10));result.put("profile",profile(row));
        result.put("workflow",enrollment.detailForApplication(row));return result;
    }
    @Transactional
    public Map<String,Object> create(IntakeController.Input input){
        if(!scope().contains(input.campusId()))throw error(HttpStatus.NOT_FOUND,"Campus not found.");
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM campuses WHERE id=:id AND active)",Map.of("id",input.campusId()),Boolean.class)))throw error(HttpStatus.BAD_REQUEST,"Choose an active campus.");
        var program=db.queryForList("SELECT code,category FROM programs WHERE id=:id",Map.of("id",input.programId()));
        if(program.isEmpty())throw error(HttpStatus.BAD_REQUEST,"Choose an existing program.");
        String track="TVET".equals(program.getFirst().get("category"))?"TVET":program.getFirst().get("code").toString().startsWith("TVL_")?"TVL":"ACADEMIC";
        if(!track.equals(input.track()))throw error(HttpStatus.BAD_REQUEST,"Track must match the selected program.");
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM academic_terms WHERE id=:id)",Map.of("id",input.termId()),Boolean.class)))throw error(HttpStatus.BAD_REQUEST,"Choose an existing academic term.");
        if(input.guardians().stream().filter(StudentInput.Guardian::primaryContact).count()>1)throw error(HttpStatus.BAD_REQUEST,"Choose at most one primary guardian.");
        Map<String,Object> profile=new LinkedHashMap<>();profile.put("track",track);profile.put("sex",text(input.sex()));profile.put("gradeLevel",input.gradeLevel().trim());profile.put("district",text(input.district()));profile.put("transportRoute",text(input.transportRoute()));profile.put("householdIncome",text(input.householdIncome()));profile.put("guardians",input.guardians());
        Map<String,Object> p=new HashMap<>();p.put("number","APP-"+UUID.randomUUID());p.put("campus",input.campusId());p.put("term",input.termId());p.put("program",input.programId());p.put("category",input.category());p.put("first",input.firstName().trim());p.put("middle",text(input.middleName()));p.put("last",input.lastName().trim());p.put("birth",input.birthDate());p.put("lrn",input.lrn());p.put("phone",text(input.phone()));p.put("email",text(input.email()));p.put("school",text(input.previousSchool()));p.put("voucher",input.voucher());p.put("profile",encode(profile));actor();
        db.queryForObject("SELECT pg_advisory_xact_lock(142014)",Map.of(),Object.class);
        long id=db.queryForObject("""
            INSERT INTO applications(application_number,campus_id,term_id,program_id,category,first_name,middle_name,last_name,birth_date,lrn,contact_phone,email,previous_school,voucher_code,profile)
            VALUES(:number,:campus,:term,:program,:category,:first,:middle,:last,:birth,:lrn,:phone,:email,:school,:voucher,CAST(:profile AS jsonb)) RETURNING id
            """,p,Long.class);
        duplicates.scan(id);
        return detail(id);
    }
    @Transactional
    public Map<String,Object> review(long id,int expected){
        // Serialize identity creation across reviewers; student LRN uniqueness remains a final guard.
        db.queryForObject("SELECT pg_advisory_xact_lock(142014)",Map.of(),Object.class);
        var app=row(id,true);
        if(((Number)app.get("version")).intValue()!=expected)throw error(HttpStatus.CONFLICT,"Application changed. Reload before reviewing.");
        if(!"submitted".equals(app.get("status")))throw error(HttpStatus.CONFLICT,"Only submitted applications can be reviewed through intake.");
        duplicates.ensureClear(id);
        var profile=profile(app);
        if(profile.get("track")==null||profile.get("gradeLevel")==null)throw error(HttpStatus.CONFLICT,"Complete the application profile before pre-enlisting.");
        if(app.get("student_id")!=null){duplicates.linkedStudent(((Number)app.get("student_id")).longValue(),id);actor();db.update("UPDATE applications SET status='pre_enlisted',version=version+1 WHERE id=:id",Map.of("id",id));return detail(id);}
        if(List.of("returnee","continuing").contains(app.get("category")))throw error(HttpStatus.CONFLICT,"Select and link the verified existing student before reviewing this application.");
        List<StudentInput.Guardian> guardians=json.convertValue(profile.getOrDefault("guardians",List.of()),new TypeReference<List<StudentInput.Guardian>>(){});
        var student=students.create(new StudentInput(((Number)app.get("campus_id")).longValue(),((Number)app.get("program_id")).longValue(),(String)app.get("voucher_code"),(String)profile.get("track"),(String)app.get("lrn"),(String)app.get("first_name"),(String)app.get("middle_name"),(String)app.get("last_name"),null,((java.sql.Date)app.get("birth_date")).toLocalDate(),(String)profile.get("sex"),(String)app.get("contact_phone"),(String)app.get("email"),new StudentInput.Address(null,null,null,null,null),(String)app.get("previous_school"),null,null,(String)profile.get("householdIncome"),guardians,true,null));
        actor();db.update("UPDATE applications SET student_id=:student,status='pre_enlisted',version=version+1 WHERE id=:id",Map.of("student",student.get("id"),"id",id));return detail(id);
    }
}
