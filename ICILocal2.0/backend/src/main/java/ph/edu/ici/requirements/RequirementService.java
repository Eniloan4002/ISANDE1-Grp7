package ph.edu.ici.requirements;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.StaffIdentity;
import ph.edu.ici.auth.AccessPolicy;

@Service
public class RequirementService {
    public record Item(String code,String name,boolean mandatory,String status,int version,
            boolean recorded,String notes,Long verifiedBy,Instant verifiedAt,
            Long updatedBy,Instant updatedAt,String originalFilename) {}
    public record Summary(String overallStatus,int mandatoryTotal,int submitted,int missing,int pending,
            int invalid,boolean credentialsComplete) {}
    public record Checklist(long applicationId,String applicationNumber,long campusId,Long studentId,
            Summary summary,List<Item> items) {}

    private final NamedParameterJdbcTemplate db;
    private final AccessPolicy accessPolicy;
    public RequirementService(NamedParameterJdbcTemplate db,AccessPolicy accessPolicy){this.accessPolicy=accessPolicy;this.db=db;}
    private StaffIdentity identity(){return (StaffIdentity)SecurityContextHolder.getContext().getAuthentication().getPrincipal();}
    private List<Long> scope(){return accessPolicy.campuses("enrollment.manage");}
    private ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Application or requirement not found.");}
    private String normalize(String code){
        if(code==null||code.isBlank()||code.length()>30)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use a valid requirement code.");
        return code.trim();
    }
    private Map<String,Object> application(long id,boolean lock){
        var campuses=scope();if(campuses.isEmpty())throw missing();
        var rows=db.queryForList("SELECT id,application_number,campus_id,student_id FROM applications WHERE id=:id AND campus_id IN (:campuses)"+(lock?" FOR UPDATE":""),Map.of("id",id,"campuses",campuses));
        if(rows.isEmpty())throw missing();return rows.getFirst();
    }
    private void requireType(String code){
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM requirement_types WHERE code=:code)",Map.of("code",code),Boolean.class)))throw missing();
    }
    public List<Map<String,Object>> types(){
        return db.queryForList("SELECT code,name,mandatory FROM requirement_types ORDER BY mandatory DESC,code",Map.of());
    }
    private Instant instant(Timestamp value){return value==null?null:value.toInstant();}
    private Checklist assemble(Map<String,Object> application){
        long id=((Number)application.get("id")).longValue();
        var items=db.query("""
            SELECT t.code,t.name,t.mandatory,coalesce(r.status,'pending') status,
                   coalesce(r.version,0) version,r.application_id IS NOT NULL recorded,
                   r.notes,r.verified_by,r.verified_at,r.updated_by,r.updated_at,r.original_filename
            FROM requirement_types t LEFT JOIN application_requirements r
              ON r.requirement_code=t.code AND r.application_id=:id
            ORDER BY t.mandatory DESC,t.code
            """,Map.of("id",id),(rs,row)->new Item(rs.getString("code"),rs.getString("name"),rs.getBoolean("mandatory"),
                rs.getString("status"),rs.getInt("version"),rs.getBoolean("recorded"),rs.getString("notes"),
                rs.getObject("verified_by",Long.class),instant(rs.getTimestamp("verified_at")),
                rs.getObject("updated_by",Long.class),instant(rs.getTimestamp("updated_at")),rs.getString("original_filename")));
        var required=items.stream().filter(Item::mandatory).toList();
        int submitted=0,missing=0,pending=0,invalid=0;
        for(var item:required)switch(item.status()){
            case "submitted" -> submitted++;
            case "missing" -> missing++;
            case "invalid" -> invalid++;
            default -> pending++;
        }
        String status=required.isEmpty()?"Pending":submitted==required.size()?"Complete":missing>0||invalid>0?"Incomplete":"Pending";
        var summary=new Summary(status,required.size(),submitted,missing,pending,invalid,status.equals("Complete"));
        Number student=(Number)application.get("student_id");
        return new Checklist(id,(String)application.get("application_number"),((Number)application.get("campus_id")).longValue(),student==null?null:student.longValue(),summary,items);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Checklist checklist(long applicationId){return assemble(application(applicationId,false));}

    // Reuse an application already read by the enrollment service, but recheck its scope.
    public Checklist checklistForApplication(Map<String,Object> application){
        if(!scope().contains(((Number)application.get("campus_id")).longValue()))throw missing();
        return assemble(application);
    }

    @Transactional
    public Checklist update(long applicationId,String rawCode,RequirementUpdate input){
        // The same application row is locked by enrollment confirmation. This
        // serializes credential updates with each other and with that gate.
        var application=application(applicationId,true);String code=normalize(rawCode);requireType(code);
        Map<String,Object> params=new HashMap<>();params.put("id",applicationId);params.put("code",code);
        var rows=db.queryForList("SELECT status,notes,version FROM application_requirements WHERE application_id=:id AND requirement_code=:code",params);
        var current=rows.isEmpty()?null:rows.getFirst();
        int version=current==null?0:((Number)current.get("version")).intValue();
        if(input.version()!=version)throw new ResponseStatusException(HttpStatus.CONFLICT,"This requirement changed in another session. Reload the checklist before saving.");
        String notes=input.notes()==null||input.notes().isBlank()?null:input.notes().trim();
        if(current!=null&&input.status().equals(current.get("status"))&&Objects.equals(notes,current.get("notes")))return assemble(application);
        long actor=identity().userId();
        db.queryForObject("SELECT set_config('app.actor_id',:actor,true)",Map.of("actor",Long.toString(actor)),String.class);
        params.put("status",input.status());params.put("notes",notes);params.put("actor",actor);
        if(current==null){
            db.update("""
                INSERT INTO application_requirements(application_id,requirement_code,status,notes,version,verified_by,verified_at)
                VALUES (:id,:code,:status,:notes,1,
                    CASE WHEN :status='submitted' THEN CAST(:actor AS bigint) ELSE NULL END,
                    CASE WHEN :status='submitted' THEN current_timestamp ELSE NULL END)
                """,params);
        }else{
            // Retain original verifier/time on note-only changes. A subsequent
            // transition into submitted gets a fresh verified staff/time pair.
            db.update("""
                UPDATE application_requirements SET
                  verified_by=CASE WHEN :status<>'submitted' THEN NULL WHEN status='submitted' THEN verified_by ELSE CAST(:actor AS bigint) END,
                  verified_at=CASE WHEN :status<>'submitted' THEN NULL WHEN status='submitted' THEN verified_at ELSE current_timestamp END,
                  status=:status,notes=:notes,version=version+1
                WHERE application_id=:id AND requirement_code=:code
                """,params);
        }
        return assemble(application);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> audit(long applicationId,String rawCode,int page,int size){
        application(applicationId,false);
        if(page<0||page>1000000||size<1||size>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use a nonnegative page and a size of 1 to 100.");
        Map<String,Object> params=new HashMap<>();params.put("application",Long.toString(applicationId));params.put("limit",size);params.put("offset",(long)page*size);
        String where=" WHERE a.table_name='application_requirements' AND coalesce(a.new_data->>'application_id',a.old_data->>'application_id')=:application";
        if(rawCode!=null){String code=normalize(rawCode);requireType(code);where+=" AND coalesce(a.new_data->>'requirement_code',a.old_data->>'requirement_code')=:code";params.put("code",code);}
        long total=db.queryForObject("SELECT count(*) FROM audit_logs a"+where,params,Long.class);
        var items=db.queryForList("""
            SELECT a.id,a.operation,a.occurred_at AS "occurredAt",a.actor_id AS "actorId",u.full_name AS "actorName",
                   coalesce(a.new_data->>'requirement_code',a.old_data->>'requirement_code') AS "requirementCode",
                   a.old_data->>'status' AS "oldStatus",a.new_data->>'status' AS "newStatus",
                   a.old_data->>'notes' AS "oldNotes",a.new_data->>'notes' AS "newNotes"
            FROM audit_logs a LEFT JOIN users u ON u.id=a.actor_id
            """+where+" ORDER BY a.id DESC LIMIT :limit OFFSET :offset",params);
        return Map.of("applicationId",applicationId,"items",items,"total",total,"page",page,"size",size);
    }
}
