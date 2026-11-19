package ph.edu.ici.imports;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
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
import ph.edu.ici.auth.AccessPolicy;
import ph.edu.ici.students.StudentInput;
import ph.edu.ici.students.StudentService;

/**
 * Commits a reviewed batch: one transaction creates students for valid and reviewed-distinct rows (through StudentService,
 * attributed to the committing user), links same-person rows to the existing student, and moves the batch to imported.
 */
@Service
public class ImportCommitService {
    private final NamedParameterJdbcTemplate db;private final ImportBatchService batches;private final ImportValidationService validation;
    private final StudentService students;private final AccessPolicy access;private final TransactionTemplate tx;
    private record Stale(int flagged,int invalid) {}

    public ImportCommitService(NamedParameterJdbcTemplate db,ImportBatchService batches,ImportValidationService validation,StudentService students,AccessPolicy access,PlatformTransactionManager transactions){
        this.db=db;this.batches=batches;this.validation=validation;this.students=students;this.access=access;this.tx=new TransactionTemplate(transactions);
    }
    private ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}

    public Map<String,Object> commit(long batchId,int version,int expectedValidCount){
        batches.batch(batchId,true,false);   // existence, visibility and imports.manage for the batch's campus before any lock
        Object outcome;
        try{outcome=tx.execute(s->commitLocked(batchId,version,expectedValidCount));}
        catch(PessimisticLockingFailureException e){throw busy();}
        catch(DataIntegrityViolationException e){throw error(HttpStatus.CONFLICT,"A student could not be created because it conflicts with existing data. Nothing was imported; reload the batch.");}
        catch(DataAccessException e){if(lockFailure(e))throw busy();throw e;}
        if(outcome instanceof Stale stale)throw error(HttpStatus.CONFLICT,"Records changed since this batch was staged: "+stale.flagged()+" row(s) are now possible duplicates and "+stale.invalid()+" can no longer be imported. Nothing was imported; review the batch again.");
        @SuppressWarnings("unchecked") Map<String,Object> result=(Map<String,Object>)outcome;return result;
    }

    private ResponseStatusException busy(){return error(HttpStatus.CONFLICT,"Another commit of this batch is in progress. Reload to see its result.");}
    /** PostgreSQL 55P03 (lock_not_available) from FOR UPDATE NOWAIT, however Spring classified it. */
    private static boolean lockFailure(Throwable e){for(Throwable t=e;t!=null;t=t.getCause())if(t instanceof java.sql.SQLException sql&&"55P03".equals(sql.getSQLState()))return true;return false;}
    private Object commitLocked(long batchId,int version,int expected){
        // NOWAIT: a concurrent commit holding the batch is refused immediately rather than queued.
        var locked=db.queryForList("SELECT * FROM import_batches WHERE id=:id FOR UPDATE NOWAIT",Map.of("id",batchId));
        var batch=locked.getFirst();String status=(String)batch.get("status");long campus=((Number)batch.get("campus_id")).longValue();
        if("imported".equals(status)||"reconciled".equals(status))return result(batchId,true);
        if(!"validated".equals(status))throw error(HttpStatus.CONFLICT,"Only a validated batch can be committed.");
        if(((Number)batch.get("version")).intValue()!=version)throw error(HttpStatus.CONFLICT,"The batch changed since you loaded it. Reload before committing.");
        batches.actor();
        long unresolved=db.queryForObject("SELECT count(*) FROM import_rows WHERE batch_id=:id AND status='duplicate_flagged'",Map.of("id",batchId),Long.class);
        if(unresolved>0)throw error(HttpStatus.CONFLICT,unresolved+" row(s) are flagged as possible duplicates. Link, mark distinct or skip each before committing.");
        var rows=db.queryForList("SELECT * FROM import_rows WHERE batch_id=:id AND status='valid' ORDER BY row_number FOR UPDATE",Map.of("id",batchId));
        if(rows.size()!=expected)throw error(HttpStatus.CONFLICT,"Expected "+expected+" importable rows but the batch has "+rows.size()+". Reload before committing.");
        if(rows.isEmpty())throw error(HttpStatus.CONFLICT,"The batch has no importable rows.");

        Set<Long> visible=new HashSet<>(access.campuses("imports.read"));visible.addAll(access.campuses("imports.manage"));
        var since=((java.sql.Timestamp)batch.get("started_at")).toInstant();
        List<ImportValidationService.Pending> pending=new ArrayList<>();Map<Long,Map<String,Object>> byId=new HashMap<>();
        for(var r:rows){long id=((Number)r.get("id")).longValue();byId.put(id,r);
            if(!"same_person".equals(r.get("review_decision")))pending.add(new ImportValidationService.Pending(id,toMap(batches.node(r.get("normalized_values")))));}
        var changed=new LinkedHashMap<>(validation.recheck(campus,visible,since,pending));
        for(var r:rows)if("same_person".equals(r.get("review_decision"))){      // the linked student may have been archived or moved since review
            var s=db.queryForList("SELECT active,campus_id FROM students WHERE id=:id",Map.of("id",r.get("matched_student_id")));
            if(s.isEmpty()||!Boolean.TRUE.equals(s.getFirst().get("active"))||!Objects.equals(s.getFirst().get("campus_id"),batch.get("campus_id")))
                changed.put(((Number)r.get("id")).longValue(),new ImportValidationService.Recheck("duplicate_flagged",List.of(RowProblem.warning("LINK_UNAVAILABLE",null,"The student chosen for this row can no longer be linked.")),null));
        }
        if(!changed.isEmpty())return demote(batchId,changed,byId);

        int created=0,linked=0;
        for(var r:rows){
            long id=((Number)r.get("id")).longValue();var v=batches.node(r.get("normalized_values"));Long studentId;
            if("same_person".equals(r.get("review_decision"))){studentId=((Number)r.get("matched_student_id")).longValue();linked++;}
            else{
                try{studentId=((Number)students.create(input(v,campus),v.path("studentNumber").asText()).get("id")).longValue();created++;}
                catch(ResponseStatusException e){
                    if(e.getStatusCode().value()==403)throw e;
                    throw error(HttpStatus.UNPROCESSABLE_ENTITY,"Row "+r.get("row_number")+" could not be created: "+e.getReason()+" Nothing was imported.");
                }
                catch(DataIntegrityViolationException e){throw error(HttpStatus.CONFLICT,"Row "+r.get("row_number")+" conflicts with existing data. Nothing was imported; reload the batch.");}
            }
            db.update("UPDATE import_rows SET status='imported',student_id=:student,version=version+1 WHERE id=:id",Map.of("id",id,"student",studentId));
        }
        batches.recount(batchId);
        db.update("UPDATE import_batches SET status='imported',imported_by=:actor,imported_at=current_timestamp WHERE id=:id",Map.of("id",batchId,"actor",batches.identity().userId()));
        return result(batchId,false);
    }

    private Object demote(long batchId,Map<Long,ImportValidationService.Recheck> changed,Map<Long,Map<String,Object>> byId){
        int flagged=0,invalid=0;
        for(var e:changed.entrySet()){
            var c=e.getValue();long id=e.getKey();boolean bad="invalid".equals(c.status());if(bad)invalid++;else flagged++;
            Object evidence=c.evidence()==null?byId.get(id).get("duplicate_evidence"):batches.json(c.evidence());
            if(!bad&&evidence==null)evidence="[]";
            Map<String,Object> p=new HashMap<>();p.put("id",id);p.put("status",c.status());p.put("evidence",evidence==null?null:evidence.toString());
            db.update("UPDATE import_rows SET status=:status,review_decision=NULL,review_reason=NULL,reviewed_by=NULL,reviewed_at=NULL,matched_student_id=NULL,duplicate_evidence=CASE WHEN :status='invalid' THEN duplicate_evidence ELSE CAST(:evidence AS jsonb) END,version=version+1 WHERE id=:id",p);
            for(var problem:c.problems())db.update("INSERT INTO import_exceptions(batch_id,row_number,error_code,detail,import_row_id,field_name,severity) VALUES (:b,:rn,:code,:detail,:row,:field,:severity)",
                new HashMap<>(Map.of("b",batchId,"rn",byId.get(id).get("row_number"),"code",problem.code(),"detail",problem.detail(),"row",id,"severity",problem.severity()))
                {{put("field",problem.field());}});
        }
        batches.recount(batchId);
        return new Stale(flagged,invalid);
    }

    private Map<String,Object> toMap(JsonNode node){
        Map<String,Object> m=new HashMap<>();node.fields().forEachRemaining(e->m.put(e.getKey(),e.getValue().isNull()?null:e.getValue().isNumber()?e.getValue().numberValue():e.getValue().asText()));return m;
    }
    private StudentInput input(JsonNode v,long campus){
        return new StudentInput(campus,v.path("programId").asLong(),v.path("voucherCode").asText(),v.path("track").asText(),text(v,"lrn"),v.path("firstName").asText(),text(v,"middleName"),v.path("lastName").asText(),null,
            LocalDate.parse(v.path("birthDate").asText()),text(v,"sex"),null,null,new StudentInput.Address(null,null,null,null,null),text(v,"previousSchool"),null,null,null,List.of(),true,null);
    }
    private String text(JsonNode v,String field){return v.path(field).isNull()||v.path(field).isMissingNode()?null:v.path(field).asText();}

    private Map<String,Object> result(long batchId,boolean already){
        var b=db.queryForList("SELECT * FROM import_batches WHERE id=:id",Map.of("id",batchId)).getFirst();
        long linked=db.queryForObject("SELECT count(*) FROM import_rows WHERE batch_id=:id AND status='imported' AND review_decision='same_person'",Map.of("id",batchId),Long.class);
        long importedCount=((Number)b.get("imported_count")).longValue();
        Map<String,Object> m=new LinkedHashMap<>();m.put("batchId",batchId);m.put("status",b.get("status"));m.put("created",importedCount-linked);m.put("linked",linked);
        m.put("skipped",b.get("skipped_count"));m.put("invalid",b.get("invalid_count"));m.put("importedCount",importedCount);m.put("version",b.get("version"));m.put("alreadyImported",already);
        return m;
    }
}
