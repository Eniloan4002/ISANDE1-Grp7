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
import ph.edu.ici.requirements.RequirementService;
import ph.edu.ici.requirements.RequirementUpdate;

/**
 * Commits a reviewed document-log batch: one transaction under FOR UPDATE NOWAIT records each document state through
 * {@link RequirementService#update}, the method behind PUT /api/applications/{id}/requirements/{code}, with the item version seen at staging.
 * Campus scope, the application lock shared with enrollment confirmation, verifier attribution (the committing registrar, at commit time) and
 * the requirement audit trail are therefore the checklist workflow's own. Rows that no longer match what was staged or reviewed are demoted and
 * nothing is imported (409); a refusal by the service itself rolls the whole batch back and names the row. The result reports which
 * applications became, or stopped being, credentials-complete, since that is an input of enrollment confirmation (nothing is confirmed here).
 */
@Service
public class DocumentLogCommitService {
    private final NamedParameterJdbcTemplate db;private final DocumentLogBatchService batches;private final ImportBatchService common;
    private final TransactionTemplate tx;private final RequirementService requirements;private final DocumentLogPlanner planner;
    private record Stale(int rows) {}
    private record Demotion(String status,String code,String field,String detail,Map<String,Object> patch,Map<String,Object> evidence) {}
    private record Item(long rowId,int rowNumber,String code,String status,String note,DocumentLogPlanner.Plan plan) {}

    public DocumentLogCommitService(NamedParameterJdbcTemplate db,DocumentLogBatchService batches,ImportBatchService common,PlatformTransactionManager transactions,
                                    RequirementService requirements,DocumentLogPlanner planner){
        this.db=db;this.batches=batches;this.common=common;this.tx=new TransactionTemplate(transactions);this.requirements=requirements;this.planner=planner;
    }
    private ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    private ResponseStatusException busy(){return error(HttpStatus.CONFLICT,"Another commit of this batch is in progress. Reload to see its result.");}
    private static boolean lockFailure(Throwable e){for(Throwable t=e;t!=null;t=t.getCause())if(t instanceof java.sql.SQLException sql&&"55P03".equals(sql.getSQLState()))return true;return false;}
    private static String text(JsonNode n,String field){return n.path(field).isTextual()?n.path(field).asText():null;}

    public Map<String,Object> commit(long batchId,int version,int expectedValidCount){
        batches.batch(batchId,true,false);
        Object outcome;
        try{outcome=tx.execute(s->commitLocked(batchId,version,expectedValidCount));}
        catch(PessimisticLockingFailureException e){throw busy();}
        catch(DataIntegrityViolationException e){throw error(HttpStatus.CONFLICT,"The document log conflicts with existing data. Nothing was imported; reload the batch.");}
        catch(DataAccessException e){if(lockFailure(e))throw busy();throw e;}
        if(outcome instanceof Stale stale)throw error(HttpStatus.CONFLICT,stale.rows()+" row(s) can no longer be imported as staged or reviewed because checklists or applications changed. Nothing was imported; review the batch again.");
        @SuppressWarnings("unchecked") Map<String,Object> result=(Map<String,Object>)outcome;return result;
    }

    private Object commitLocked(long batchId,int version,int expected){
        var batch=db.queryForList("SELECT * FROM import_batches WHERE id=:id FOR UPDATE NOWAIT",Map.of("id",batchId)).getFirst();
        String status=(String)batch.get("status");long campus=((Number)batch.get("campus_id")).longValue();
        if("imported".equals(status)||"reconciled".equals(status))return result(batchId,true,0,0);
        if(!"validated".equals(status))throw error(HttpStatus.CONFLICT,"Only a validated batch can be committed.");
        if(((Number)batch.get("version")).intValue()!=version)throw error(HttpStatus.CONFLICT,"The batch changed since you loaded it. Reload before committing.");
        common.actor();
        long unresolved=db.queryForObject("SELECT count(*) FROM import_rows WHERE batch_id=:id AND status='duplicate_flagged'",Map.of("id",batchId),Long.class);
        if(unresolved>0)throw error(HttpStatus.CONFLICT,unresolved+" row(s) need a decision. Link each to a student, apply or skip it before committing.");
        var rows=db.queryForList("SELECT * FROM import_rows WHERE batch_id=:id AND status='valid' ORDER BY row_number FOR UPDATE",Map.of("id",batchId));
        if(rows.size()!=expected)throw error(HttpStatus.CONFLICT,"Expected "+expected+" importable rows but the batch has "+rows.size()+". Reload before committing.");
        if(rows.isEmpty())throw error(HttpStatus.CONFLICT,"The batch has no importable rows.");

        Map<Long,Demotion> demoted=new LinkedHashMap<>();Map<Long,Integer> numbers=new HashMap<>();List<Item> items=new ArrayList<>();Map<String,Integer> seen=new HashMap<>();
        for(var r:rows){
            long id=((Number)r.get("id")).longValue();int number=((Number)r.get("row_number")).intValue();numbers.put(id,number);
            JsonNode n=common.node(r.get("normalized_values"));String decision=(String)r.get("review_decision");
            Long student=r.get("matched_student_id")!=null?((Number)r.get("matched_student_id")).longValue():n.path("studentId").isNumber()?n.path("studentId").asLong():null;
            String applicationNumber=text(n,"applicationNumber");
            boolean byStudent=n.path("studentNumber").isTextual()||n.path("lrn").isTextual();
            if(byStudent&&(student==null||!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM students WHERE id=:id AND campus_id=:campus)",Map.of("id",student,"campus",campus),Boolean.class)))){
                demoted.put(id,new Demotion("invalid","STUDENT_UNAVAILABLE",null,"The student is no longer a student of this campus.",null,null));continue;
            }
            String code=text(n,"code"),state=text(n,"status"),note=text(n,"note");
            Long term=n.path("termId").isNumber()?n.path("termId").asLong():null;
            var plan=planner.plan(campus,term,byStudent?student:null,applicationNumber,code,state,note);
            if(!plan.ok()){
                var first=plan.problems().stream().filter(p->RowProblem.ERROR.equals(p.severity())).findFirst().get();
                demoted.put(id,new Demotion("invalid",first.code(),first.field(),first.detail(),null,null));continue;
            }
            String key=plan.applicationId()+"|"+code;Integer clash=seen.putIfAbsent(key,number);
            if(clash!=null){demoted.put(id,new Demotion("invalid","DUPLICATE_IN_BATCH","document","This application's document already appears on row "+clash+".",null,null));continue;}
            boolean staged=n.has("action")&&!n.path("action").isNull();
            boolean changed=staged&&(!plan.action().equals(n.path("action").asText())||plan.itemVersion()!=n.path("itemVersion").asInt(-1)||!Objects.equals(plan.applicationId(),n.path("applicationId").asLong(-1)));
            if(changed||(plan.conflict()!=null&&!"apply".equals(decision))){
                Map<String,Object> patch=new LinkedHashMap<>();patch.put("studentId",plan.studentId());patch.put("action",plan.action());patch.put("applicationId",plan.applicationId());patch.put("itemVersion",plan.itemVersion());
                patch.put("currentStatus",plan.recorded()?plan.currentStatus():null);patch.put("conflict",plan.conflict());patch.put("regression",plan.regression());
                Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("kind",changed?"changed":plan.conflict());evidence.put("applicationId",plan.applicationId());evidence.put("code",code);
                evidence.put("currentStatus",plan.currentStatus());evidence.put("importedStatus",state);evidence.put("regression",plan.regression());
                demoted.put(id,new Demotion("duplicate_flagged",changed?"CHANGED_SINCE_STAGING":"CONFLICTS_WITH_RECORDED","status",
                    changed?"The document's checklist item changed after this batch was staged. Review the row again.":"The row would change a recorded document; accept it with a reason or skip it.",patch,evidence));
                continue;
            }
            items.add(new Item(id,number,code,state,note,plan));
        }
        if(!demoted.isEmpty())return demote(batchId,demoted,numbers);

        // Stable lock order (application, then document code) so concurrent commits cannot deadlock on application rows.
        List<Item> writes=items.stream().filter(i->DocumentLogPlanner.RECORD.equals(i.plan().action()))
            .sorted(Comparator.<Item>comparingLong(i->i.plan().applicationId()).thenComparing(Item::code)).toList();
        Map<Long,Boolean> before=new TreeMap<>();
        for(Item i:writes)before.computeIfAbsent(i.plan().applicationId(),a->requirements.checklist(a).summary().credentialsComplete());
        Map<Long,Map<String,Object>> after=new HashMap<>();
        for(Item i:writes){
            try{
                var checklist=requirements.update(i.plan().applicationId(),i.code(),new RequirementUpdate(i.status(),i.plan().itemVersion(),i.note()));
                var item=checklist.items().stream().filter(x->x.code().equals(i.code())).findFirst().get();
                after.put(i.rowId(),Map.of("status",item.status(),"version",item.version()));
            }catch(ResponseStatusException e){
                throw new ResponseStatusException(e.getStatusCode(),"Row "+i.rowNumber()+" was refused by the checklist workflow: "+e.getReason()+" Nothing was imported.");
            }
        }
        int nowComplete=0,noLongerComplete=0;
        for(var e:before.entrySet()){
            boolean now=requirements.checklist(e.getKey()).summary().credentialsComplete();
            if(now&&!e.getValue())nowComplete++;if(!now&&e.getValue())noLongerComplete++;
        }
        for(Item i:items){
            Map<String,Object> applied=new LinkedHashMap<>();applied.put("action",i.plan().action());applied.put("applicationId",i.plan().applicationId());applied.put("code",i.code());
            if(DocumentLogPlanner.NOOP.equals(i.plan().action()))applied.put("noop",true);
            else{
                Map<String,Object> was=new LinkedHashMap<>();was.put("recorded",i.plan().recorded());was.put("status",i.plan().currentStatus());was.put("version",i.plan().itemVersion());was.put("notes",i.plan().currentNotes());
                applied.put("before",was);applied.put("after",after.get(i.rowId()));
            }
            db.update("UPDATE import_rows SET status='imported',student_id=:student,applied=CAST(:applied AS jsonb),version=version+1 WHERE id=:id",Map.of("id",i.rowId(),"student",i.plan().studentId(),"applied",common.json(applied)));
        }
        common.recount(batchId);
        db.update("UPDATE import_batches SET status='imported',imported_by=:actor,imported_at=current_timestamp WHERE id=:id",Map.of("id",batchId,"actor",common.identity().userId()));
        return result(batchId,false,nowComplete,noLongerComplete);
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
            Map<String,Object> x=new HashMap<>();x.put("b",batchId);x.put("rn",numbers.get(id));x.put("code",d.code());x.put("detail",d.detail());x.put("row",id);x.put("field",d.field());
            x.put("severity","duplicate_flagged".equals(d.status())?"warning":"error");
            db.update("INSERT INTO import_exceptions(batch_id,row_number,error_code,detail,import_row_id,field_name,severity) VALUES (:b,:rn,:code,:detail,:row,:field,:severity)",x);
        }
        common.recount(batchId);
        return new Stale(demoted.size());
    }

    private Map<String,Object> result(long batchId,boolean already,int nowComplete,int noLongerComplete){
        var b=db.queryForList("SELECT * FROM import_batches WHERE id=:id",Map.of("id",batchId)).getFirst();
        var counts=db.queryForList("SELECT applied->>'action' AS action,count(*) AS n FROM import_rows WHERE batch_id=:id AND status='imported' GROUP BY 1",Map.of("id",batchId));
        long recorded=0,noop=0;
        for(var c:counts){long n=((Number)c.get("n")).longValue();if(DocumentLogPlanner.RECORD.equals(c.get("action")))recorded=n;else if(DocumentLogPlanner.NOOP.equals(c.get("action")))noop=n;}
        Map<String,Object> m=new LinkedHashMap<>();m.put("batchId",batchId);m.put("status",b.get("status"));m.put("applied",recorded);m.put("alreadyRecorded",noop);
        m.put("skipped",b.get("skipped_count"));m.put("invalid",b.get("invalid_count"));
        if(!already){m.put("applicationsNowComplete",nowComplete);m.put("applicationsNoLongerComplete",noLongerComplete);}
        m.put("importedCount",b.get("imported_count"));m.put("version",b.get("version"));m.put("alreadyImported",already);return m;
    }
}
