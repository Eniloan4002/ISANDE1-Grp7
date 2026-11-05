package ph.edu.ici.workflows;

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
public class WorkflowService {
    private final JdbcTemplate jdbc;private final NamedParameterJdbcTemplate db;private final AccessScope access;
    public WorkflowService(JdbcTemplate jdbc,AccessScope access){this.jdbc=jdbc;this.db=new NamedParameterJdbcTemplate(jdbc);this.access=access;}
    private ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private void actor(StaffIdentity who){access.actor(who);}
    private List<Long> registrarScope(StaffIdentity actor){return access.campuses(actor,"enrollment.manage");}
    public List<Map<String,Object>> withdrawals(StaffIdentity actor,String status){
        boolean cashierOnly=!actor.grants().isEmpty()&&actor.grants().stream().allMatch(grant->"ROLE_CASHIER_REF".equals(grant.role()));
        List<Long> scope=new ArrayList<>(registrarScope(actor));scope.addAll(access.campusesForRole(actor,"ROLE_PRINCIPAL"));scope.addAll(access.campuses(actor,"cashier.read"));scope=scope.stream().distinct().toList();if(scope.isEmpty())return List.of();
        var p=new MapSqlParameterSource().addValue("campuses",scope);String filter="";if(status!=null){if(!Set.of("pending","approved","rejected","completed").contains(status))bad("Select a supported withdrawal status.");p.addValue("status",status);filter=" AND w.status=:status";}
        if(cashierOnly)return db.queryForList("SELECT w.id,e.id AS enrollment_id,e.student_id,e.campus_id,e.term_id,w.status,s.student_number,s.first_name,s.last_name,s.voucher_code, " +
            "(SELECT jsonb_agg(jsonb_build_object('office',x.office,'status',x.status) ORDER BY x.office) FROM withdrawal_clearances x WHERE x.withdrawal_id=w.id) AS clearances " +
            "FROM withdrawals w JOIN enrollments e ON e.id=w.enrollment_id JOIN students s ON s.id=e.student_id WHERE e.campus_id IN (:campuses)"+filter+" ORDER BY w.requested_at DESC",p);
        return db.queryForList("SELECT w.id,w.enrollment_id,w.reason,w.requested_at,w.status,e.student_id,e.campus_id,e.term_id,s.student_number,s.first_name,s.last_name " +
            "FROM withdrawals w JOIN enrollments e ON e.id=w.enrollment_id JOIN students s ON s.id=e.student_id WHERE e.campus_id IN (:campuses)"+filter+" ORDER BY w.requested_at DESC",p);
    }
    public List<Map<String,Object>> cashierRoster(StaffIdentity actor,Long termId){
        List<Long> scope=access.campuses(actor,"cashier.read");if(scope.isEmpty())return List.of();var p=new MapSqlParameterSource().addValue("campuses",scope);String filter="";
        if(termId!=null){p.addValue("term",termId);filter=" AND e.term_id=:term";}
        return db.queryForList("SELECT e.id AS enrollment_id,e.campus_id,e.term_id,e.status AS enrollment_status,s.id AS student_id,s.student_number,s.first_name,s.last_name,s.program_id,s.voucher_code " +
            "FROM enrollments e JOIN students s ON s.id=e.student_id WHERE e.campus_id IN (:campuses)"+filter+" ORDER BY s.last_name,s.first_name,s.id",p);
    }
    @Transactional public Map<String,Object> createWithdrawal(StaffIdentity actor,WorkflowController.WithdrawalInput input){
        List<Long> scope=registrarScope(actor);var rows=db.queryForList("SELECT id,campus_id,status FROM enrollments WHERE id=:id AND campus_id IN (:campuses)",new MapSqlParameterSource().addValue("id",input.enrollmentId()).addValue("campuses",scope));
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Enrollment not found.");if(!"enrolled".equals(rows.getFirst().get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Only enrolled students can start a withdrawal request.");
        actor(actor);long id=jdbc.queryForObject("INSERT INTO withdrawals(enrollment_id,reason) VALUES (?,?) RETURNING id",Long.class,input.enrollmentId(),input.reason().trim());
        jdbc.update("INSERT INTO withdrawal_clearances(withdrawal_id,office,status) VALUES (?, 'adviser','pending'), (?, 'principal','pending'), (?, 'cashier','pending')",id,id,id);
        return withdrawal(actor,id);
    }
    private Map<String,Object> withdrawal(StaffIdentity actor,long id){
        var rows=jdbc.queryForList("SELECT w.id,w.enrollment_id,w.reason,w.requested_at,w.status,e.campus_id FROM withdrawals w JOIN enrollments e ON e.id=w.enrollment_id WHERE w.id=?",id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Withdrawal not found.");var row=rows.getFirst();long campus=((Number)row.get("campus_id")).longValue();
        boolean registrar=registrarScope(actor).contains(campus),principal=access.campusesForRole(actor,"ROLE_PRINCIPAL").contains(campus);
        if(!registrar&&!principal)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Withdrawal not found.");
        row.put("clearances",jdbc.queryForList("SELECT office,status,acted_by,acted_at,notes FROM withdrawal_clearances WHERE withdrawal_id=? ORDER BY office",id));return row;
    }
    @Transactional public Map<String,Object> clearance(StaffIdentity actor,long id,String office,WorkflowController.ClearanceInput input){
        if(!Set.of("adviser","principal","cashier").contains(office))throw bad("Select an existing clearance office.");
        if(!Set.of("cleared","rejected").contains(input.status()))throw bad("Clearance status must be cleared or rejected.");
        Map<String,Object> row=withdrawal(actor,id);long campus=((Number)row.get("campus_id")).longValue();
        jdbc.queryForMap("SELECT id FROM withdrawals WHERE id=? FOR UPDATE",id);
        String currentStatus=jdbc.queryForObject("SELECT status FROM withdrawals WHERE id=?",String.class,id);
        if(Set.of("completed","rejected").contains(currentStatus))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"This withdrawal has already reached a terminal state.");
        boolean principalActor=access.hasRole(actor,"ROLE_PRINCIPAL");boolean registrar=registrarScope(actor).contains(campus);
        if("principal".equals(office)){
            if(!principalActor||!access.campusesForRole(actor,"ROLE_PRINCIPAL").contains(campus))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only the assigned campus principal can record principal approval.");
        }else if(!registrar)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only Registrar staff can record verified external clearances.");
        if(principalActor&&"principal".equals(office)&&"rejected".equals(input.status())){
            // A principal rejection is a terminal workflow decision.
        }
        actor(actor);
        jdbc.update("INSERT INTO withdrawal_clearances(withdrawal_id,office,status,acted_by,acted_at,notes) VALUES (?,?,?,?,current_timestamp,?) " +
            "ON CONFLICT(withdrawal_id,office) DO UPDATE SET status=EXCLUDED.status,acted_by=EXCLUDED.acted_by,acted_at=current_timestamp,notes=EXCLUDED.notes",
            id,office,input.status(),actor.userId(),input.notes());
        if("principal".equals(office))jdbc.update("UPDATE withdrawals SET status=? WHERE id=?", "cleared".equals(input.status())?"approved":"rejected",id);
        String status=Objects.toString(jdbc.queryForObject("SELECT status FROM withdrawals WHERE id=?",String.class,id));
        if("approved".equals(status)&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT EXISTS(SELECT 1 FROM withdrawal_clearances WHERE withdrawal_id=? AND status<>'cleared')",Boolean.class,id))){
            jdbc.update("UPDATE withdrawals SET status='completed' WHERE id=?",id);
            jdbc.update("UPDATE enrollments e SET status='withdrawn' FROM withdrawals w WHERE w.id=? AND w.enrollment_id=e.id",id);
            jdbc.update("UPDATE applications a SET status='withdrawn' FROM enrollments e JOIN withdrawals w ON w.enrollment_id=e.id WHERE w.id=? AND a.id=e.application_id",id);
        }
        return withdrawal(actor,id);
    }
    public List<Map<String,Object>> documents(StaffIdentity actor,String status){
        List<Long> scope=registrarScope(actor);if(scope.isEmpty())return List.of();var p=new MapSqlParameterSource().addValue("campuses",scope);String filter="";
        if(status!=null){if(!Set.of("requested","blocked","approved","issued").contains(status))bad("Select a supported document status.");p.addValue("status",status);filter=" AND d.status=:status";}
        return db.queryForList("SELECT d.id,d.student_id,d.document_type,d.status,d.special_order_number,d.requested_at,d.issued_at,d.issued_by,d.storage_key,s.campus_id,s.student_number,s.first_name,s.last_name " +
            "FROM document_requests d JOIN students s ON s.id=d.student_id WHERE s.campus_id IN (:campuses)"+filter+" ORDER BY d.requested_at DESC",p);
    }
    @Transactional public Map<String,Object> createDocument(StaffIdentity actor,WorkflowController.DocumentInput input){
        if(!Set.of("FORM137","FORM138","TOR").contains(input.documentType()))bad("Select a supported document type.");
        var rows=jdbc.queryForList("SELECT id,campus_id FROM students WHERE id=?",input.studentId());if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Student not found.");
        long campus=((Number)rows.getFirst().get("campus_id")).longValue();access.requireCampus(actor,"enrollment.manage",campus);actor(actor);
        long id=jdbc.queryForObject("INSERT INTO document_requests(student_id,document_type,status) VALUES (?,?,'requested') RETURNING id",Long.class,input.studentId(),input.documentType());
        String status=readyForDocument(input.studentId())?"requested":"blocked";jdbc.update("UPDATE document_requests SET status=? WHERE id=?",status,id);
        return document(actor,id);
    }
    private boolean readyForDocument(long studentId){
        boolean missing=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM enrollments e JOIN applications a ON a.id=e.application_id JOIN requirement_types t ON t.mandatory " +
            "LEFT JOIN application_requirements r ON r.application_id=a.id AND r.requirement_code=t.code WHERE e.student_id=? AND e.status<>'withdrawn' AND (r.status IS NULL OR r.status<>'submitted'))",Boolean.class,studentId));
        boolean incomplete=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM grades g JOIN enrollments e ON e.id=g.enrollment_id WHERE e.student_id=? AND (g.mark='INC' OR g.state<>'finalized'))",Boolean.class,studentId));
        return !missing&&!incomplete;
    }
    private Map<String,Object> document(StaffIdentity actor,long id){
        var rows=jdbc.queryForList("SELECT d.id,d.student_id,d.document_type,d.status,d.special_order_number,d.requested_at,d.issued_at,d.issued_by,d.storage_key,s.campus_id FROM document_requests d JOIN students s ON s.id=d.student_id WHERE d.id=?",id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Document request not found.");var row=rows.getFirst();
        if(!registrarScope(actor).contains(((Number)row.get("campus_id")).longValue()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Document request not found.");return row;
    }
    @Transactional public Map<String,Object> approveDocument(StaffIdentity actor,long id){
        Map<String,Object> row=document(actor,id);if(!readyForDocument(((Number)row.get("student_id")).longValue()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Student has incomplete requirements or academic records.");
        if(!"requested".equals(row.get("status"))&& !"blocked".equals(row.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Document request cannot be approved in its current state.");actor(actor);
        jdbc.update("UPDATE document_requests SET status='approved' WHERE id=?",id);return document(actor,id);
    }
    @Transactional public Map<String,Object> issueDocument(StaffIdentity actor,long id,WorkflowController.DocumentIssue input){
        Map<String,Object> row=document(actor,id);if(!"approved".equals(row.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Approve the document request before issuing it.");
        if(!readyForDocument(((Number)row.get("student_id")).longValue()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Student records no longer meet document release requirements.");actor(actor);
        jdbc.update("UPDATE document_requests SET status='issued',issued_at=current_timestamp,issued_by=?,storage_key=?,special_order_number=? WHERE id=?",actor.userId(),input.storageKey().trim(),input.specialOrderNumber(),id);
        return document(actor,id);
    }
}
