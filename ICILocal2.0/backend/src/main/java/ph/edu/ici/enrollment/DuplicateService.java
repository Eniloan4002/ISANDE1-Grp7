package ph.edu.ici.enrollment;

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

/** Adjudicates application identities without deleting or overwriting master/academic records. */
@Service
public class DuplicateService {
    private final NamedParameterJdbcTemplate db;
    private final AccessPolicy accessPolicy;private final ObjectMapper json;
    public DuplicateService(NamedParameterJdbcTemplate db,ObjectMapper json,AccessPolicy accessPolicy){this.accessPolicy=accessPolicy;this.db=db;this.json=json;}
    private StaffIdentity identity(){return (StaffIdentity)SecurityContextHolder.getContext().getAuthentication().getPrincipal();}
    private List<Long> scope(){return accessPolicy.campuses("enrollment.manage");}
    private ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    private long n(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private void actor(){db.queryForObject("SELECT set_config('app.actor_id',:actor,true)",Map.of("actor",Long.toString(identity().userId())),String.class);}
    private void serial(){db.queryForObject("SELECT pg_advisory_xact_lock(142014)",Map.of(),Object.class);}
    private Map<String,Object> app(long id,boolean lock){var rows=db.queryForList("SELECT * FROM applications WHERE id=:id AND campus_id IN (:scope)"+(lock?" FOR UPDATE":""),Map.of("id",id,"scope",scope()));if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Application not found.");return rows.getFirst();}
    private Map<String,Object> student(long id,boolean lock){var rows=db.queryForList("SELECT * FROM students WHERE id=:id AND campus_id IN (:scope)"+(lock?" FOR UPDATE":""),Map.of("id",id,"scope",scope()));if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Student not found in your campus scope.");return rows.getFirst();}
    private String normalize(Object value){return Normalizer.normalize(Objects.toString(value,""),Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).replaceAll("[^a-z\\s]","").trim().replaceAll("\\s+"," ");}
    private double similarity(Object left,Object right){String a=normalize(left),b=normalize(right);if(a.isEmpty()||b.isEmpty())return 0;int[] prev=new int[b.length()+1];for(int j=0;j<prev.length;j++)prev[j]=j;for(int i=1;i<=a.length();i++){int[] next=new int[b.length()+1];next[0]=i;for(int j=1;j<=b.length();j++)next[j]=Math.min(Math.min(next[j-1]+1,prev[j]+1),prev[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));prev=next;}return 1.0-(double)prev[b.length()]/Math.max(a.length(),b.length());}
    private boolean sameLrn(Map<String,Object> a,Map<String,Object> b){return a.get("lrn")!=null&&a.get("lrn").equals(b.get("lrn"));}
    private boolean sameNameDob(Map<String,Object> a,Map<String,Object> b){return Objects.equals(a.get("birth_date"),b.get("birth_date"))&&similarity(a.get("last_name"),b.get("last_name"))>=0.8&&similarity(normalize(a.get("first_name")).split(" ")[0],normalize(b.get("first_name")).split(" ")[0])>=0.8;}
    private List<Map<String,Object>> candidates(Map<String,Object> a){
        var rows=db.queryForList("""
            SELECT 'student' AS kind,id,campus_id,first_name,last_name,birth_date,lrn,id AS canonical_student_id FROM students
            WHERE id<>:linked AND (lrn=:lrn OR birth_date=:birth)
            UNION ALL SELECT 'application' AS kind,id,campus_id,first_name,last_name,birth_date,lrn,student_id AS canonical_student_id FROM applications
            WHERE id<>:id AND (student_id IS NULL OR student_id<>:linked) AND status NOT IN ('rejected','withdrawn') AND (lrn=:lrn OR birth_date=:birth)
            """,Map.of("id",a.get("id"),"linked",a.get("student_id")==null?0:a.get("student_id"),"lrn",Objects.toString(a.get("lrn"),""),"birth",a.get("birth_date")));
        // A linked application's identifiers can differ from its master (legacy data).
        // Scan both, but report one candidate per canonical identity when both match.
        Map<String,Map<String,Object>> unique=new LinkedHashMap<>();
        for(var b:rows)if(sameLrn(a,b)||sameNameDob(a,b))unique.putIfAbsent(b.get("canonical_student_id")==null?"application:"+b.get("id"):"student:"+b.get("canonical_student_id"),b);
        return new ArrayList<>(unique.values());
    }
    private boolean settled(long application,Map<String,Object> target){
        String field="student".equals(target.get("kind"))?"matched_student_id":"matched_application_id";
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM duplicate_cases WHERE application_id=:app AND "+field+"=:target AND status<>'open')",Map.of("app",application,"target",target.get("id")),Boolean.class)))return true;
        return "student".equals(target.get("kind"))&&Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM duplicate_cases d JOIN applications a ON a.id=d.matched_application_id WHERE d.application_id=:app AND a.student_id=:target AND d.status='distinct')",Map.of("app",application,"target",target.get("id")),Boolean.class));
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private void standing(long id){
        db.update("""
            UPDATE applications SET status=CASE WHEN EXISTS(SELECT 1 FROM duplicate_cases WHERE (application_id=:id OR matched_application_id=:id) AND status='open') THEN 'duplicate_flag' ELSE 'submitted' END,version=version+1
            WHERE id=:id AND status IN ('submitted','duplicate_flag','under_review')
            """,Map.of("id",id));
    }
    @Transactional public void scan(long id){
        serial();var a=app(id,true);if(!List.of("submitted","duplicate_flag","under_review").contains(a.get("status")))return;
        actor();for(var b:candidates(a)){
            String field="student".equals(b.get("kind"))?"matched_student_id":"matched_application_id";
            if(settled(id,b)||Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM duplicate_cases WHERE application_id=:app AND "+field+"=:target)",Map.of("app",id,"target",b.get("id")),Boolean.class)))continue;
            var evidence=Map.of("type",sameLrn(a,b)?"LRN_EXACT":"NAME_DOB","nameScore",Math.min(similarity(a.get("last_name"),b.get("last_name")),similarity(normalize(a.get("first_name")).split(" ")[0],normalize(b.get("first_name")).split(" ")[0])));
            db.update("INSERT INTO duplicate_cases(application_id,"+field+",evidence) VALUES(:app,:target,CAST(:evidence AS jsonb))",Map.of("app",id,"target",b.get("id"),"evidence",encode(evidence)));
        }
        // Only change standing/version when new checks actually change standing.
        boolean open=Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM duplicate_cases WHERE (application_id=:id OR matched_application_id=:id) AND status='open')",Map.of("id",id),Boolean.class));
        String desired=open?"duplicate_flag":"submitted";if(!desired.equals(a.get("status")))db.update("UPDATE applications SET status=:status,version=version+1 WHERE id=:id",Map.of("id",id,"status",desired));
    }
    public void ensureClear(long id){
        var a=app(id,false);
        boolean open=Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM duplicate_cases WHERE (application_id=:id OR matched_application_id=:id) AND status='open')",Map.of("id",id),Boolean.class));
        if(open||candidates(a).stream().anyMatch(b->!settled(id,b)))throw error(HttpStatus.CONFLICT,"Resolve duplicate checks before pre-enlisting. Open Duplicate Records and run a check if needed.");
    }
    private Map<String,Object> caseRow(long id,boolean lock){var rows=db.queryForList("SELECT * FROM duplicate_cases WHERE id=:id"+(lock?" FOR UPDATE":""),Map.of("id",id));if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Duplicate case not found.");var c=rows.getFirst();app(n(c,"application_id"),false);if(c.get("matched_student_id")!=null)student(n(c,"matched_student_id"),false);else app(n(c,"matched_application_id"),false);return c;}
    private Map<String,Object> publicPerson(Map<String,Object> row,boolean isStudent){
        Map<String,Object> result=new LinkedHashMap<>();for(String key:List.of("id","first_name","middle_name","last_name","birth_date","lrn","campus_id","version"))result.put(key,row.get(key));
        result.put("number",row.get(isStudent?"student_number":"application_number"));result.put("standing",isStudent?Boolean.TRUE.equals(row.get("active"))?"active":"archived":row.get("status"));result.put("student_id",isStudent?row.get("id"):row.get("student_id"));
        result.put("campus",db.queryForObject("SELECT name FROM campuses WHERE id=:id",Map.of("id",row.get("campus_id")),String.class));return result;
    }
    @Transactional(readOnly=true) public Map<String,Object> detail(long id){
        var c=caseRow(id,false);var incoming=app(n(c,"application_id"),false);boolean isStudent=c.get("matched_student_id")!=null;
        var matched=isStudent?student(n(c,"matched_student_id"),false):app(n(c,"matched_application_id"),false);
        Map<String,Object> result=new LinkedHashMap<>();for(String key:List.of("id","status","version","reason","resolved_by","resolved_at"))result.put(key,c.get(key));
        try{result.put("evidence",json.readValue(c.get("evidence").toString(),Map.class));}catch(Exception e){throw new IllegalStateException(e);}
        result.put("incoming",publicPerson(incoming,false));result.put("matched",publicPerson(matched,isStudent));result.put("matchedKind",isStudent?"student":"application");result.put("studentVersion",isStudent?matched.get("version"):matched.get("student_id")==null?null:student(n(matched,"student_id"),false).get("version"));
        result.put("history",db.queryForList("SELECT l.id,l.occurred_at AS \"occurredAt\",u.full_name AS actor,l.new_data->>'status' AS status,l.new_data->>'reason' AS reason FROM audit_logs l LEFT JOIN users u ON u.id=l.actor_id WHERE l.table_name='duplicate_cases' AND coalesce(l.new_data,l.old_data)->>'id'=:id ORDER BY l.id DESC LIMIT 50",Map.of("id",Long.toString(id))));return result;
    }
    @Transactional(readOnly=true) public Map<String,Object> list(String status,Long application,int page){
        if(!List.of("open","merged","distinct","rejected","all").contains(status)||page<0||page>1000000)throw error(HttpStatus.BAD_REQUEST,"Use a valid status and page.");
        Map<String,Object> p=new HashMap<>();p.put("scope",scope());p.put("offset",(long)page*25);
        String from=" FROM duplicate_cases d JOIN applications a ON a.id=d.application_id LEFT JOIN applications m ON m.id=d.matched_application_id LEFT JOIN students s ON s.id=d.matched_student_id WHERE a.campus_id IN (:scope)";
        if(!"all".equals(status)){from+=" AND d.status=:status";p.put("status",status);}if(application!=null){from+=" AND a.id=:application";p.put("application",application);}
        var items=db.queryForList("SELECT d.id,d.version,d.status,a.id AS \"applicationId\",a.application_number AS \"applicationNumber\",concat_ws(' ',a.first_name,a.last_name) AS name,(coalesce(m.campus_id,s.campus_id) IN (:scope)) AS \"canReview\""+from+" ORDER BY d.id DESC LIMIT 25 OFFSET :offset",p);
        return Map.of("items",items,"total",db.queryForObject("SELECT count(*)"+from,p,Long.class),"page",page,"size",25);
    }
    private void editable(Map<String,Object> a,int version){if(n(a,"version")!=version)throw error(HttpStatus.CONFLICT,"Application changed. Reload before deciding.");if(!List.of("submitted","duplicate_flag","under_review").contains(a.get("status")))throw error(HttpStatus.CONFLICT,"Only unreviewed applications can change identity or be rejected here.");}
    public Map<String,Object> linkedStudent(long studentId,long applicationId){
        var s=student(studentId,true);if(!Boolean.TRUE.equals(s.get("active")))throw error(HttpStatus.CONFLICT,"Reactivate the verified student record before linking.");
        var a=app(applicationId,false);
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM enrollments WHERE student_id=:student AND term_id=:term AND application_id<>:app) OR EXISTS(SELECT 1 FROM applications WHERE student_id=:student AND term_id=:term AND id<>:app AND status IN ('pre_enlisted','enrolled'))",Map.of("student",studentId,"term",a.get("term_id"),"app",applicationId),Boolean.class)))throw error(HttpStatus.CONFLICT,"This student already has an enrollment or reviewed application for this term. Use the canonical application instead.");return s;
    }
    @Transactional public void link(long id,long studentId,int applicationVersion,int studentVersion,String reason){
        serial();var a=app(id,true);editable(a,applicationVersion);var s=linkedStudent(studentId,id);if(n(s,"version")!=studentVersion)throw error(HttpStatus.CONFLICT,"Student changed. Reload the selected record.");
        if(a.get("student_id")!=null&&n(a,"student_id")!=studentId)throw error(HttpStatus.CONFLICT,"A student identity is already linked; do not overwrite it.");
        if(!Objects.equals(a.get("birth_date"),s.get("birth_date"))||!sameLrn(a,s)&&!sameNameDob(a,s)||a.get("lrn")!=null&&s.get("lrn")!=null&&!sameLrn(a,s))throw error(HttpStatus.CONFLICT,"Student DOB and identity identifiers must match. Correct the source record before linking.");
        var cases=db.queryForList("SELECT d.* FROM duplicate_cases d LEFT JOIN applications m ON m.id=d.matched_application_id WHERE d.application_id=:id AND d.status='open' AND (d.matched_student_id=:student OR m.student_id=:student) FOR UPDATE OF d",Map.of("id",id,"student",studentId));
        for(var c:cases)caseRow(n(c,"id"),false);
        actor();db.update("UPDATE applications SET student_id=:student,version=version+1 WHERE id=:id",Map.of("id",id,"student",studentId));
        for(var c:cases)decision(n(c,"id"),"merged",reason);
        standing(id);
    }
    private void decision(long id,String status,String reason){db.update("UPDATE duplicate_cases SET status=:status,reason=:reason,resolved_by=:actor,resolved_at=current_timestamp,version=version+1 WHERE id=:id",Map.of("id",id,"status",status,"reason",reason.trim(),"actor",identity().userId()));}
    @Transactional public Map<String,Object> resolve(long id,String action,int version,int applicationVersion,Integer studentVersion,String reason){
        serial();var c=caseRow(id,true);if(!"open".equals(c.get("status"))||n(c,"version")!=version)throw error(HttpStatus.CONFLICT,"Case already decided or changed. Reload before deciding.");
        long appId=n(c,"application_id");var a=app(appId,true);editable(a,applicationVersion);actor();
        if("same_person".equals(action)){
            Long target=c.get("matched_student_id")==null?null:n(c,"matched_student_id");if(target==null){var matched=app(n(c,"matched_application_id"),false);if(matched.get("student_id")!=null)target=n(matched,"student_id");}
            if(target==null)throw error(HttpStatus.CONFLICT,"The matched application has no verified student yet. Keep that canonical application and reject the incoming duplicate, then review the canonical record.");
            if(studentVersion==null)throw error(HttpStatus.BAD_REQUEST,"Current student version is required.");link(appId,target,applicationVersion,studentVersion,reason);return detail(id);
        }
        if("distinct".equals(action)){
            var matched=c.get("matched_student_id")!=null?student(n(c,"matched_student_id"),false):app(n(c,"matched_application_id"),false);
            if(sameLrn(a,matched))throw error(HttpStatus.CONFLICT,"Distinct people cannot share a permanent LRN. Correct the identifier or retain the canonical record.");
            decision(id,"distinct",reason);
            if(c.get("matched_application_id")!=null){long other=n(c,"matched_application_id");var reverse=db.queryForList("SELECT id FROM duplicate_cases WHERE application_id=:other AND matched_application_id=:id AND status='open'",Map.of("other",other,"id",appId));for(var r:reverse)decision(n(r,"id"),"distinct",reason);
                if(reverse.isEmpty()&&!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM duplicate_cases WHERE application_id=:other AND matched_application_id=:id)",Map.of("other",other,"id",appId),Boolean.class)))db.update("INSERT INTO duplicate_cases(application_id,matched_application_id,evidence,status,resolved_by,resolved_at,reason) VALUES(:other,:id,'{\"type\":\"RECIPROCAL_REVIEW\"}','distinct',:actor,current_timestamp,:reason)",Map.of("other",other,"id",appId,"actor",identity().userId(),"reason",reason.trim()));standing(other);}
        }else if("reject".equals(action)){
            var related=db.queryForList("SELECT * FROM duplicate_cases WHERE (application_id=:id OR matched_application_id=:id) AND status='open' FOR UPDATE",Map.of("id",appId));
            for(var r:related)caseRow(n(r,"id"),false);
            db.update("UPDATE applications SET status='rejected',version=version+1 WHERE id=:id",Map.of("id",appId));
            for(var r:related){decision(n(r,"id"),"rejected",reason);if(n(r,"application_id")!=appId)standing(n(r,"application_id"));if(r.get("matched_application_id")!=null&&n(r,"matched_application_id")!=appId)standing(n(r,"matched_application_id"));}
        }else throw error(HttpStatus.BAD_REQUEST,"Unknown resolution action.");
        standing(appId);return detail(id);
    }
}
