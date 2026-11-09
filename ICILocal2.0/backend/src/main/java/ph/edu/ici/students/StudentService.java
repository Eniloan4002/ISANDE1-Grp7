package ph.edu.ici.students;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.StaffIdentity;
import ph.edu.ici.auth.AccessPolicy;

@Service
public class StudentService {
    private final NamedParameterJdbcTemplate db;
    private final ObjectMapper json;
    private final AccessPolicy accessPolicy;
    public StudentService(NamedParameterJdbcTemplate db,ObjectMapper json,AccessPolicy accessPolicy){this.db=db;this.json=json;this.accessPolicy=accessPolicy;}
    private StaffIdentity identity(){return (StaffIdentity)SecurityContextHolder.getContext().getAuthentication().getPrincipal();}
    private List<Long> campuses(){return accessPolicy.campuses("enrollment.manage");}
    private void campus(long id){if(!campuses().contains(id))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Your account cannot manage students at this campus.");}
    private ResponseStatusException invalid(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private String text(String value){return value==null||value.isBlank()?null:value.trim();}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw invalid("Invalid address.");}}
    private Map<String,Object> fields(StudentInput input){
        Map<String,Object> p=new HashMap<>();
        p.put("campus",input.campusId());p.put("program",input.programId());p.put("voucher",input.voucherCode());p.put("track",input.track());
        p.put("lrn",input.lrn());p.put("first",input.firstName().trim());p.put("middle",text(input.middleName()));p.put("last",input.lastName().trim());p.put("suffix",text(input.suffix()));
        p.put("birth",input.birthDate());p.put("sex",text(input.sex()));p.put("phone",text(input.phone()));p.put("email",text(input.email()));p.put("address",encode(input.address()));
        p.put("school",text(input.previousSchoolName()));p.put("schoolId",text(input.previousSchoolId()));p.put("schoolAddress",text(input.previousSchoolAddress()));p.put("income",text(input.householdIncomeBracket()));p.put("active",input.active());
        return p;
    }
    private void validate(StudentInput input){
        campus(input.campusId());
        if(input.guardians().stream().filter(StudentInput.Guardian::primaryContact).count()>1)throw invalid("Choose at most one primary guardian.");
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM campuses WHERE id=:id AND active)",Map.of("id",input.campusId()),Boolean.class)))throw invalid("Select an active campus.");
        var programs=db.queryForList("SELECT code,category FROM programs WHERE id=:id",Map.of("id",input.programId()));
        if(programs.isEmpty())throw invalid("Select an existing program.");
        var program=programs.getFirst();String code=(String)program.get("code");
        String expected="TVET".equals(program.get("category"))?"TVET":code.startsWith("TVL_")?"TVL":"ACADEMIC";
        if(!expected.equals(input.track()))throw invalid("Track must match the selected program.");
    }
    private void actor(){db.queryForObject("SELECT set_config('app.actor_id',:actor,true)",Map.of("actor",Long.toString(identity().userId())),String.class);}
    private Map<String,Object> decode(Map<String,Object> row){
        try{row.put("address",json.readValue(row.get("address").toString(),Map.class));}catch(Exception e){throw new IllegalStateException("Stored address is invalid",e);}
        return row;
    }
    public Map<String,Object> get(long id){
        List<Long> scope=campuses();if(scope.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Student not found.");
        var rows=db.queryForList("SELECT * FROM students WHERE id=:id AND campus_id IN (:campuses)",Map.of("id",id,"campuses",scope));
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Student not found.");
        Map<String,Object> row=decode(rows.getFirst());row.put("guardians",db.queryForList("SELECT id,full_name,relationship,phone,address,is_primary FROM guardians WHERE student_id=:id ORDER BY is_primary DESC,id",Map.of("id",id)));return row;
    }
    public Map<String,Object> list(String search,Long campusId,Long programId,String voucher,Boolean active,int page,int size){
        if(page<0||size<1||size>100||page>1000000||search.length()>150)throw invalid("Use a nonnegative page, a size of 1–100 and a search of at most 150 characters.");
        if(campusId!=null)campus(campusId);
        List<Long> scope=campusId==null?campuses():List.of(campusId);
        if(scope.isEmpty())return Map.of("items",List.of(),"total",0,"page",page,"size",size);
        Map<String,Object> p=new HashMap<>();p.put("campuses",scope);p.put("limit",size);p.put("offset",(long)page*size);
        String where=" WHERE campus_id IN (:campuses)";
        if(!search.isBlank()){
            String q=search.trim().replace("!","!!").replace("%","!%").replace("_","!_");p.put("query","%"+q+"%");
            where+=" AND (concat_ws(' ',first_name,middle_name,last_name) ILIKE :query ESCAPE '!' OR lrn LIKE :query ESCAPE '!' OR student_number ILIKE :query ESCAPE '!')";
        }
        if(programId!=null){where+=" AND program_id=:program";p.put("program",programId);}
        if(voucher!=null){if(!List.of("QVR","ESC","ALS","REGULAR").contains(voucher))throw invalid("Invalid voucher filter.");where+=" AND voucher_code=:voucher";p.put("voucher",voucher);}
        if(active!=null){where+=" AND active=:active";p.put("active",active);}
        long total=db.queryForObject("SELECT count(*) FROM students"+where,p,Long.class);
        var rows=db.queryForList("SELECT id,student_number,lrn,first_name,middle_name,last_name,campus_id,program_id,voucher_code,active,version FROM students"+where+" ORDER BY last_name,first_name,id LIMIT :limit OFFSET :offset",p);
        return Map.of("items",rows,"total",total,"page",page,"size",size);
    }
    @Transactional public Map<String,Object> create(StudentInput input){
        validate(input);actor();var p=fields(input);p.put("number","STU-"+UUID.randomUUID());
        long id=db.queryForObject("INSERT INTO students(student_number,campus_id,program_id,voucher_code,track,lrn,first_name,middle_name,last_name,suffix,birth_date,sex,phone,email,address,previous_school_name,previous_school_id,previous_school_address,household_income_bracket,active) VALUES (:number,:campus,:program,:voucher,:track,:lrn,:first,:middle,:last,:suffix,:birth,:sex,:phone,:email,CAST(:address AS jsonb),:school,:schoolId,:schoolAddress,:income,:active) RETURNING id",p,Long.class);
        guardians(id,input.guardians());return get(id);
    }
    @Transactional public Map<String,Object> update(long id,StudentInput input){
        var existing=get(id);validate(input);
        if(((Number)existing.get("campus_id")).longValue()!=input.campusId())throw new ResponseStatusException(HttpStatus.CONFLICT,"Use the transfer workflow to change a student's campus.");
        if(input.version()==null)throw invalid("Record version is required for updates.");
        actor();var p=fields(input);p.put("id",id);p.put("version",input.version());
        int count=db.update("UPDATE students SET program_id=:program,voucher_code=:voucher,track=:track,lrn=:lrn,first_name=:first,middle_name=:middle,last_name=:last,suffix=:suffix,birth_date=:birth,sex=:sex,phone=:phone,email=:email,address=CAST(:address AS jsonb),previous_school_name=:school,previous_school_id=:schoolId,previous_school_address=:schoolAddress,household_income_bracket=:income,active=:active,version=version+1 WHERE id=:id AND campus_id=:campus AND version=:version",p);
        if(count==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"This student changed in another session. Reload before saving.");
        db.update("DELETE FROM guardians WHERE student_id=:id",Map.of("id",id));guardians(id,input.guardians());return get(id);
    }
    private void guardians(long id,List<StudentInput.Guardian> guardians){
        for(var guardian:guardians){Map<String,Object> p=new HashMap<>();p.put("id",id);p.put("name",guardian.fullName().trim());p.put("relationship",guardian.relationship().trim());p.put("phone",text(guardian.phone()));p.put("address",text(guardian.address()));p.put("primary",guardian.primaryContact());
            db.update("INSERT INTO guardians(student_id,full_name,relationship,phone,address,is_primary) VALUES (:id,:name,:relationship,:phone,:address,:primary)",p);}
    }
    @Transactional public void archive(long id,int version){
        var existing=get(id);if(version<0)throw invalid("Version cannot be negative.");actor();
        if(db.update("UPDATE students SET active=false,version=version+1 WHERE id=:id AND campus_id=:campus AND version=:version",Map.of("id",id,"campus",existing.get("campus_id"),"version",version))==0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"This student changed in another session. Reload before archiving.");
    }
    public Map<String,Object> referenceData(){
        var scope=campuses();return Map.of("campuses",scope.isEmpty()?List.of():db.queryForList("SELECT id,code,name FROM campuses WHERE active AND id IN (:ids) ORDER BY name",Map.of("ids",scope)),
            "programs",db.queryForList("SELECT id,code,name,category FROM programs ORDER BY code",Map.of()),"vouchers",db.queryForList("SELECT code,name FROM vouchers ORDER BY code",Map.of()));
    }
}
