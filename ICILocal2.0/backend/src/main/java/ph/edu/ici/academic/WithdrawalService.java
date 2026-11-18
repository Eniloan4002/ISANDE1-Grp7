package ph.edu.ici.academic;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.AccessPolicy;
import ph.edu.ici.auth.StaffIdentity;
import static ph.edu.ici.academic.WithdrawalController.*;

/** Clearance affects the term enrollment, never archives the permanent student identity. */
@Service
public class WithdrawalService {
    private final NamedParameterJdbcTemplate db;private final AccessPolicy access;
    public WithdrawalService(NamedParameterJdbcTemplate db,AccessPolicy access){this.db=db;this.access=access;}
    private long actor(){return ((StaffIdentity)SecurityContextHolder.getContext().getAuthentication().getPrincipal()).userId();}
    private ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    private long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private void audit(){db.queryForObject("SELECT set_config('app.actor_id',:actor,true)",Map.of("actor",Long.toString(actor())),String.class);}
    private List<Long> campuses(String permission){var values=access.campuses(permission);return values.isEmpty()?List.of(-1L):values;}
    private MapSqlParameterSource readParams(){return new MapSqlParameterSource("actor",actor())
        .addValue("manager",campuses("enrollment.manage")).addValue("principal",campuses("withdrawals.approve"))
        .addValue("cashier",campuses("withdrawals.clear")).addValue("adviser",campuses("advisory.read"));}
    private static final String FROM="""
        FROM withdrawals w JOIN enrollments e ON e.id=w.enrollment_id
        JOIN students st ON st.id=e.student_id LEFT JOIN sections s ON s.id=e.section_id
        """;
    private static final String READ="""
        (e.campus_id IN (:manager) OR e.campus_id IN (:principal) OR e.campus_id IN (:cashier)
          OR (e.campus_id IN (:adviser) AND s.adviser_id=:actor))
        """;
    private static final String SELECT="""
        SELECT w.id,w.enrollment_id AS "enrollmentId",e.student_id AS "studentId",e.application_id AS "applicationId",
               e.campus_id AS "campusId",e.term_id AS "termId",e.section_id AS "sectionId",
               e.status AS "enrollmentStatus",e.version AS "enrollmentVersion",w.reason,w.status,w.version,
               w.requested_at AS "requestedAt",st.student_number AS "studentNumber",
               concat_ws(' ',st.first_name,st.middle_name,st.last_name,st.suffix) AS "studentName",s.name AS section
        """;
    private Map<String,Object> readable(long id){
        var rows=db.queryForList(SELECT+FROM+" WHERE w.id=:id AND "+READ,readParams().addValue("id",id));
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Withdrawal not found.");return rows.getFirst();
    }
    private boolean eligible(long student){
        return !Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM withdrawals w JOIN enrollments e ON e.id=w.enrollment_id WHERE e.student_id=:student AND w.status<>'completed')",Map.of("student",student),Boolean.class));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> detail(long id){
        var row=readable(id);row.put("clearances",db.queryForList("""
            SELECT wc.office,wc.status,wc.version,wc.acted_by AS "actedBy",u.full_name AS "actorName",
                   wc.acted_at AS "actedAt",wc.notes
            FROM withdrawal_clearances wc LEFT JOIN users u ON u.id=wc.acted_by
            WHERE wc.withdrawal_id=:id ORDER BY CASE wc.office WHEN 'adviser' THEN 1 WHEN 'principal' THEN 2 ELSE 3 END
            """,Map.of("id",id)));
        row.put("credentialReleaseEligible",eligible(number(row,"studentId")));
        row.put("history",db.queryForList("""
            SELECT a.id,a.table_name AS "tableName",a.operation,a.occurred_at AS "occurredAt",a.actor_id AS "actorId",
                   u.full_name AS "actorName",a.old_data->>'status' AS "oldStatus",a.new_data->>'status' AS "newStatus",
                   a.new_data->>'office' AS office,coalesce(a.new_data->>'notes',a.new_data->>'reason') AS reason
            FROM audit_logs a LEFT JOIN users u ON u.id=a.actor_id
            WHERE (a.table_name='withdrawals' AND coalesce(a.new_data->>'id',a.old_data->>'id')=:id)
               OR (a.table_name='withdrawal_clearances' AND coalesce(a.new_data->>'withdrawal_id',a.old_data->>'withdrawal_id')=:id)
            ORDER BY a.occurred_at,a.id
            """,Map.of("id",Long.toString(id))));return row;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> release(long id){var row=readable(id);boolean allowed=eligible(number(row,"studentId"));
        return Map.of("withdrawalId",id,"eligible",allowed,"reason",allowed?"Withdrawal clearances complete; other document rules still apply.":"Credential release is blocked by unresolved or rejected withdrawal clearance.");}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> list(Long campusId,Long termId,String status,int page,int size){
        if(page<0||page>1000000||size<1||size>100)throw error(HttpStatus.BAD_REQUEST,"Use page >= 0 and size between 1 and 100.");
        if(status!=null&&!Set.of("pending","approved","rejected","completed").contains(status))throw error(HttpStatus.BAD_REQUEST,"Unknown withdrawal status.");
        var params=readParams();String where=" WHERE "+READ;
        if(campusId!=null){
            boolean authorized=List.of("enrollment.manage","withdrawals.approve","withdrawals.clear","advisory.read").stream().anyMatch(p->access.campuses(p).contains(campusId));
            if(!authorized)throw error(HttpStatus.FORBIDDEN,"Campus is outside your withdrawal scope.");
            where+=" AND e.campus_id=:campus";params.addValue("campus",campusId);
        }
        if(termId!=null){where+=" AND e.term_id=:term";params.addValue("term",termId);}
        if(status!=null){where+=" AND w.status=:status";params.addValue("status",status);}
        long total=db.queryForObject("SELECT count(*) "+FROM+where,params,Long.class);
        params.addValue("size",size).addValue("offset",(long)page*size);
        return Map.of("items",db.queryForList(SELECT+FROM+where+" ORDER BY w.requested_at DESC,w.id DESC LIMIT :size OFFSET :offset",params),"total",total,"page",page,"size",size);
    }

    /** Lock hierarchy matches enrollment assignment and its database guard. */
    private Map<String,Object> lockEnrollment(long enrollmentId,List<Long> scope){
        var params=Map.of("id",enrollmentId,"scope",scope.isEmpty()?List.of(-1L):scope);
        var rows=db.queryForList("SELECT application_id FROM enrollments WHERE id=:id AND campus_id IN (:scope)",params);
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Enrollment not found.");
        long app=number(rows.getFirst(),"application_id");
        db.queryForList("SELECT id FROM applications WHERE id=:id FOR UPDATE",Map.of("id",app));
        var enrollment=db.queryForList("SELECT * FROM enrollments WHERE id=:id AND campus_id IN (:scope) FOR UPDATE",params);
        if(enrollment.isEmpty())throw error(HttpStatus.NOT_FOUND,"Enrollment not found.");var row=enrollment.getFirst();
        if(row.get("section_id")!=null)db.queryForList("SELECT id FROM sections WHERE id=:id FOR UPDATE",Map.of("id",row.get("section_id")));
        return row;
    }
    @Transactional
    public Map<String,Object> create(CreateInput input){
        var enrollment=lockEnrollment(input.enrollmentId(),access.campuses("enrollment.manage"));
        if(number(enrollment,"version")!=input.enrollmentVersion())throw error(HttpStatus.CONFLICT,"Enrollment changed. Reload before requesting withdrawal.");
        if(!Set.of("enrolled","provisional").contains(enrollment.get("status")))throw error(HttpStatus.CONFLICT,"Only active enrollments can request withdrawal.");
        if(enrollment.get("section_id")==null||!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM sections WHERE id=:id AND adviser_id IS NOT NULL)",Map.of("id",enrollment.get("section_id")),Boolean.class)))
            throw error(HttpStatus.CONFLICT,"Assign a section adviser before requesting withdrawal.");
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM withdrawals WHERE enrollment_id=:id)",Map.of("id",input.enrollmentId()),Boolean.class)))
            throw error(HttpStatus.CONFLICT,"This enrollment already has a withdrawal request.");
        audit();long id=db.queryForObject("INSERT INTO withdrawals(enrollment_id,reason) VALUES (:id,:reason) RETURNING id",Map.of("id",input.enrollmentId(),"reason",input.reason().trim()),Long.class);
        for(String office:List.of("adviser","principal","cashier"))db.update("INSERT INTO withdrawal_clearances(withdrawal_id,office) VALUES (:id,:office)",Map.of("id",id,"office",office));
        return detail(id);
    }

    @Transactional
    public Map<String,Object> decide(long id,String office,DecisionInput input){return decide(id,office,input,false);}

    /** Keeps the published adviser endpoint's clearance-version response contract. */
    @Transactional
    public Map<String,Object> legacyAdviser(long id,AcademicController.WithdrawalDecision input){
        var result=decide(id,"adviser",new DecisionInput(input.version(),null,input.decision(),input.notes()),true);
        var clearance=((List<Map<String,Object>>)result.get("clearances")).stream().filter(row->"adviser".equals(row.get("office"))).findFirst().orElseThrow();
        return Map.of("withdrawalId",id,"endorsementStatus",clearance.get("status"),"withdrawalStatus",result.get("status"),"version",clearance.get("version"),"withdrawalVersion",result.get("version"));
    }
    private Map<String,Object> decide(long id,String office,DecisionInput input,boolean legacy){
        String permission=switch(office){case "adviser"->"advisory.read";case "principal"->"withdrawals.approve";case "cashier"->"withdrawals.clear";default->throw error(HttpStatus.BAD_REQUEST,"Unknown clearance office.");};
        var scope=access.campuses(permission);if(scope.isEmpty())throw error(HttpStatus.FORBIDDEN,"Your role cannot decide this office's clearance.");
        var rows=db.queryForList("SELECT w.enrollment_id FROM withdrawals w JOIN enrollments e ON e.id=w.enrollment_id WHERE w.id=:id AND e.campus_id IN (:scope)",Map.of("id",id,"scope",scope));
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Withdrawal not found.");
        var enrollment=lockEnrollment(number(rows.getFirst(),"enrollment_id"),scope);
        if("adviser".equals(office)&&!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM sections WHERE id=:section AND adviser_id=:actor)",new MapSqlParameterSource("section",enrollment.get("section_id")).addValue("actor",actor()),Boolean.class)))
            throw error(HttpStatus.NOT_FOUND,"Assigned withdrawal endorsement not found.");
        var current=db.queryForList("SELECT * FROM withdrawals WHERE id=:id FOR UPDATE",Map.of("id",id)).getFirst();
        if(!Set.of("pending","approved").contains(current.get("status")))throw error(HttpStatus.CONFLICT,"This withdrawal has already been decided.");
        if(!legacy&&number(current,"version")!=input.withdrawalVersion())throw error(HttpStatus.CONFLICT,"Withdrawal changed. Reload before deciding.");
        var clearances=db.queryForList("SELECT * FROM withdrawal_clearances WHERE withdrawal_id=:id ORDER BY office FOR UPDATE",Map.of("id",id));
        var byOffice=new HashMap<String,Map<String,Object>>();for(var row:clearances)byOffice.put((String)row.get("office"),row);
        var own=byOffice.get(office);
        if(own==null)throw error(HttpStatus.CONFLICT,"The request is missing this clearance office.");
        if(!"pending".equals(own.get("status"))||number(own,"version")!=input.version())throw error(HttpStatus.CONFLICT,"Clearance changed or has already been decided.");
        String previous="principal".equals(office)?"adviser":"cashier".equals(office)?"principal":null;
        if(previous!=null&&(byOffice.get(previous)==null||!"cleared".equals(byOffice.get(previous).get("status"))))throw error(HttpStatus.CONFLICT,"Complete the preceding clearance office first.");
        audit();var params=new MapSqlParameterSource("id",id).addValue("office",office).addValue("decision",input.decision()).addValue("actor",actor()).addValue("notes",input.notes()==null?null:input.notes().trim());
        db.update("UPDATE withdrawal_clearances SET status=:decision,acted_by=:actor,acted_at=clock_timestamp(),notes=:notes,version=version+1 WHERE withdrawal_id=:id AND office=:office",params);
        String state="rejected".equals(input.decision())?"rejected":"cashier".equals(office)?"completed":"principal".equals(office)?"approved":"pending";
        db.update("UPDATE withdrawals SET status=:status,version=version+1 WHERE id=:id",Map.of("id",id,"status",state));
        if("completed".equals(state)){
            // Do not archive the permanent student: another campus/term may remain active.
            db.update("UPDATE enrollments SET status='withdrawn',version=version+1 WHERE id=:id",Map.of("id",enrollment.get("id")));
            db.update("UPDATE applications SET status='withdrawn',version=version+1 WHERE id=:id",Map.of("id",enrollment.get("application_id")));
        }
        return detail(id);
    }
}
