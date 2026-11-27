package ph.edu.ici.imports;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.enrollment.EnrollmentController.AssignmentInput;
import ph.edu.ici.enrollment.EnrollmentService;

/**
 * Commits a reviewed section allocation batch: one transaction under FOR UPDATE NOWAIT places the students through
 * {@link EnrollmentService#assign}, the same method behind PUT /api/enrollments/{id}/section, so scope, versions, the confirmed-enrollment gate,
 * section compatibility, capacity and audit are the enrollment workflow's own. Nothing is written to enrollments directly. If anything cannot be
 * applied cleanly the whole batch rolls back: rows that no longer match what was staged or reviewed are demoted and the commit answers 409
 * without importing anything; a refusal by the service itself aborts the transaction and names the row.
 */
@Service
public class SectionAllocationCommitService {
    private final NamedParameterJdbcTemplate db;private final SectionAllocationBatchService batches;private final ImportBatchService common;
    private final TransactionTemplate tx;private final EnrollmentService enrollments;private final SectionAllocationPlanner planner;
    private record Stale(int rows) {}
    /** A row the commit cannot apply as staged. {@code patch} refreshes the staged picture so a later review decision approves what is now true. */
    private record Demotion(String status,String code,String field,String detail,Map<String,Object> patch,Map<String,Object> evidence) {}

    public SectionAllocationCommitService(NamedParameterJdbcTemplate db,SectionAllocationBatchService batches,ImportBatchService common,PlatformTransactionManager transactions,
                                          EnrollmentService enrollments,SectionAllocationPlanner planner){
        this.db=db;this.batches=batches;this.common=common;this.tx=new TransactionTemplate(transactions);this.enrollments=enrollments;this.planner=planner;
    }
    private ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    private ResponseStatusException busy(){return error(HttpStatus.CONFLICT,"Another commit of this batch is in progress. Reload to see its result.");}
    /** PostgreSQL 55P03 (lock_not_available) from FOR UPDATE NOWAIT, however Spring classified it. */
    private static boolean lockFailure(Throwable e){for(Throwable t=e;t!=null;t=t.getCause())if(t instanceof java.sql.SQLException sql&&"55P03".equals(sql.getSQLState()))return true;return false;}

    public Map<String,Object> commit(long batchId,int version,int expectedValidCount){
        batches.batch(batchId,true,false);   // existence, visibility and imports.enrollment.manage for the campus before any lock
        Object outcome;
        try{outcome=tx.execute(s->commitLocked(batchId,version,expectedValidCount));}
        catch(PessimisticLockingFailureException e){throw busy();}
        catch(DataIntegrityViolationException e){throw error(HttpStatus.CONFLICT,"The allocation conflicts with existing data. Nothing was imported; reload the batch.");}
        catch(DataAccessException e){if(lockFailure(e))throw busy();throw e;}
        if(outcome instanceof Stale stale)throw error(HttpStatus.CONFLICT,stale.rows()+" row(s) can no longer be imported as staged or reviewed because enrollments or sections changed. Nothing was imported; review the batch again.");
        @SuppressWarnings("unchecked") Map<String,Object> result=(Map<String,Object>)outcome;return result;
    }

    /** What one importable row will do, from live data. */
    private record Item(long rowId,int rowNumber,long student,long section,SectionAllocationPlanner.Plan plan) {}

    private Object commitLocked(long batchId,int version,int expected){
        var batch=db.queryForList("SELECT * FROM import_batches WHERE id=:id FOR UPDATE NOWAIT",Map.of("id",batchId)).getFirst();
        String status=(String)batch.get("status");long campus=((Number)batch.get("campus_id")).longValue();
        if(("imported".equals(status)||"reconciled".equals(status)))return result(batchId,true);
        if(!"validated".equals(status))throw error(HttpStatus.CONFLICT,"Only a validated batch can be committed.");
        if(((Number)batch.get("version")).intValue()!=version)throw error(HttpStatus.CONFLICT,"The batch changed since you loaded it. Reload before committing.");
        common.actor();
        long unresolved=db.queryForObject("SELECT count(*) FROM import_rows WHERE batch_id=:id AND status='duplicate_flagged'",Map.of("id",batchId),Long.class);
        if(unresolved>0)throw error(HttpStatus.CONFLICT,unresolved+" row(s) need a decision. Link each to a student, apply or skip it before committing.");
        var rows=db.queryForList("SELECT * FROM import_rows WHERE batch_id=:id AND status='valid' ORDER BY row_number FOR UPDATE",Map.of("id",batchId));
        if(rows.size()!=expected)throw error(HttpStatus.CONFLICT,"Expected "+expected+" importable rows but the batch has "+rows.size()+". Reload before committing.");
        if(rows.isEmpty())throw error(HttpStatus.CONFLICT,"The batch has no importable rows.");

        // Recheck every row against live data with the same preflight staging used, and compare with the staged picture.
        Map<Long,Demotion> demoted=new LinkedHashMap<>();Map<Long,Integer> numbers=new HashMap<>();List<Item> items=new ArrayList<>();
        for(var r:rows){
            long id=((Number)r.get("id")).longValue();int number=((Number)r.get("row_number")).intValue();numbers.put(id,number);
            JsonNode n=common.node(r.get("normalized_values"));String decision=(String)r.get("review_decision");
            Long student=r.get("matched_student_id")!=null?((Number)r.get("matched_student_id")).longValue():n.path("studentId").isNumber()?n.path("studentId").asLong():null;
            if(student==null||!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM students WHERE id=:id AND campus_id=:campus AND active)",Map.of("id",student,"campus",campus),Boolean.class))){
                demoted.put(id,new Demotion("invalid","STUDENT_UNAVAILABLE",null,"The student is no longer an active student of this campus.",null,null));continue;
            }
            long term=n.path("termId").asLong(),section=n.path("sectionId").asLong();
            var plan=planner.plan(campus,term,student,section,n.path("gradeLevel").isTextual()?n.path("gradeLevel").asText():null,n.path("program").isTextual()?n.path("program").asText():null);
            if(!plan.ok()){
                var first=plan.problems().stream().filter(p->RowProblem.ERROR.equals(p.severity())).findFirst().get();
                demoted.put(id,new Demotion("invalid",first.code(),first.field(),first.detail(),null,null));continue;
            }
            boolean staged=n.has("action")&&!n.path("action").isNull();
            boolean reassign=SectionAllocationPlanner.REASSIGN.equals(plan.action());
            boolean changed=staged&&(!plan.action().equals(n.path("action").asText())||plan.enrollmentVersion()!=n.path("enrollmentVersion").asInt(-1)||!Objects.equals(plan.fromSectionId(),n.path("fromSectionId").isNumber()?n.path("fromSectionId").asLong():null));
            if(changed||(reassign&&!"apply".equals(decision))){
                Map<String,Object> patch=new LinkedHashMap<>();patch.put("studentId",student);patch.put("action",plan.action());patch.put("enrollmentId",plan.enrollmentId());patch.put("enrollmentVersion",plan.enrollmentVersion());patch.put("fromSectionId",plan.fromSectionId());
                Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("kind",reassign?"reassignment":"changed");evidence.put("fromSectionId",plan.fromSectionId());evidence.put("toSectionId",section);
                demoted.put(id,new Demotion("duplicate_flagged",changed?"CHANGED_SINCE_STAGING":"REASSIGNMENT",null,
                    changed?"The student's enrollment changed after this batch was staged. Review the row again.":"The student is already in a different section; accept the move with a reason or skip the row.",patch,evidence));
                continue;
            }
            items.add(new Item(id,number,student,section,plan));
        }
        if(!demoted.isEmpty())return demote(batchId,demoted,numbers);

        // Free seats before taking them (a swap between two full sections must be feasible), then place, in a stable lock order.
        List<Item> leaving=items.stream().filter(i->SectionAllocationPlanner.REASSIGN.equals(i.plan().action())).sorted(Comparator.comparingLong(i->i.plan().enrollmentId())).toList();
        for(Item i:leaving)place(i,null,i.plan().enrollmentVersion());
        List<Item> placing=items.stream().filter(i->!SectionAllocationPlanner.NOOP.equals(i.plan().action())).sorted(Comparator.<Item>comparingLong(Item::section).thenComparingInt(Item::rowNumber)).toList();
        Set<Long> unassigned=new HashSet<>();for(Item i:leaving)unassigned.add(i.rowId());
        for(Item i:placing)place(i,i.section(),i.plan().enrollmentVersion()+(unassigned.contains(i.rowId())?1:0));

        for(Item i:items){
            Map<String,Object> applied=new LinkedHashMap<>();applied.put("action",i.plan().action());applied.put("enrollmentId",i.plan().enrollmentId());
            if(SectionAllocationPlanner.NOOP.equals(i.plan().action()))applied.put("noop",true);
            else{
                applied.put("fromSectionId",i.plan().fromSectionId());applied.put("toSectionId",i.section());applied.put("versionBefore",i.plan().enrollmentVersion());
                applied.put("versionAfter",db.queryForObject("SELECT version FROM enrollments WHERE id=:id",Map.of("id",i.plan().enrollmentId()),Integer.class));
            }
            db.update("UPDATE import_rows SET status='imported',student_id=:student,applied=CAST(:applied AS jsonb),version=version+1 WHERE id=:id",Map.of("id",i.rowId(),"student",i.student(),"applied",common.json(applied)));
        }
        common.recount(batchId);
        db.update("UPDATE import_batches SET status='imported',imported_by=:actor,imported_at=current_timestamp WHERE id=:id",Map.of("id",batchId,"actor",common.identity().userId()));
        return result(batchId,false);
    }

    /** Moves one enrollment through the enrollment service. Any refusal aborts the whole commit and names the row. */
    private void place(Item item,Long section,int version){
        try{enrollments.assign(item.plan().enrollmentId(),new AssignmentInput(section,version));}
        catch(ResponseStatusException e){
            throw new ResponseStatusException(e.getStatusCode(),"Row "+item.rowNumber()+" was refused by the enrollment workflow: "+e.getReason()+" Nothing was imported.");
        }
    }

    private Object demote(long batchId,Map<Long,Demotion> demoted,Map<Long,Integer> numbers){
        for(var e:demoted.entrySet()){
            long id=e.getKey();var d=e.getValue();
            Map<String,Object> p=new HashMap<>();p.put("id",id);p.put("status",d.status());
            p.put("patch",d.patch()==null?null:common.json(d.patch()));p.put("evidence",d.evidence()==null?null:common.json(List.of(d.evidence())));
            db.update("""
                UPDATE import_rows SET status=:status,review_decision=NULL,review_reason=NULL,reviewed_by=NULL,reviewed_at=NULL,matched_student_id=NULL,
                  normalized_values=CASE WHEN CAST(:patch AS jsonb) IS NULL THEN normalized_values ELSE coalesce(normalized_values,'{}'::jsonb)||CAST(:patch AS jsonb) END,
                  duplicate_evidence=CAST(:evidence AS jsonb),version=version+1 WHERE id=:id""",p);
            Map<String,Object> x=new HashMap<>();x.put("b",batchId);x.put("rn",numbers.get(id));x.put("code",d.code());x.put("detail",d.detail());x.put("row",id);x.put("field",d.field()==null?null:d.field().length()>200?d.field().substring(0,200):d.field());
            x.put("severity","duplicate_flagged".equals(d.status())?"warning":"error");
            db.update("INSERT INTO import_exceptions(batch_id,row_number,error_code,detail,import_row_id,field_name,severity) VALUES (:b,:rn,:code,:detail,:row,:field,:severity)",x);
        }
        common.recount(batchId);
        return new Stale(demoted.size());
    }

    private Map<String,Object> result(long batchId,boolean already){
        var b=db.queryForList("SELECT * FROM import_batches WHERE id=:id",Map.of("id",batchId)).getFirst();
        var counts=db.queryForList("SELECT applied->>'action' AS action,count(*) AS n FROM import_rows WHERE batch_id=:id AND status='imported' GROUP BY 1",Map.of("id",batchId));
        long assigned=0,reassigned=0,noop=0;
        for(var c:counts){long n=((Number)c.get("n")).longValue();switch(String.valueOf(c.get("action"))){case SectionAllocationPlanner.ASSIGN->assigned=n;case SectionAllocationPlanner.REASSIGN->reassigned=n;case SectionAllocationPlanner.NOOP->noop=n;default->{}}}
        Map<String,Object> m=new LinkedHashMap<>();m.put("batchId",batchId);m.put("status",b.get("status"));m.put("assigned",assigned);m.put("reassigned",reassigned);m.put("alreadyAssigned",noop);
        m.put("skipped",b.get("skipped_count"));m.put("invalid",b.get("invalid_count"));m.put("importedCount",b.get("imported_count"));m.put("version",b.get("version"));m.put("alreadyImported",already);return m;
    }
}
