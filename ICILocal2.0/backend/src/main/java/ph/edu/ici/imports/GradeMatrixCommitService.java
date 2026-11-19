package ph.edu.ici.imports;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Commits a reviewed grade batch: one transaction under FOR UPDATE NOWAIT writes legacy_grade_records (with row provenance) for valid
 * and confirmed rows, marks those rows imported and the batch imported. It never writes grades or students. Rows that no longer
 * commit cleanly are demoted and the commit answers 409 without importing anything (as in ICI2-37).
 */
@Service
public class GradeMatrixCommitService {
    private final NamedParameterJdbcTemplate db;private final GradeMatrixBatchService batches;private final ImportBatchService common;private final TransactionTemplate tx;
    private record Stale(int invalid) {}
    private record Demotion(String code,String field,String detail) {}

    public GradeMatrixCommitService(NamedParameterJdbcTemplate db,GradeMatrixBatchService batches,ImportBatchService common,PlatformTransactionManager transactions){
        this.db=db;this.batches=batches;this.common=common;this.tx=new TransactionTemplate(transactions);
    }
    private ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    private ResponseStatusException busy(){return error(HttpStatus.CONFLICT,"Another commit of this batch is in progress. Reload to see its result.");}
    /** PostgreSQL 55P03 (lock_not_available) from FOR UPDATE NOWAIT, however Spring classified it. */
    private static boolean lockFailure(Throwable e){for(Throwable t=e;t!=null;t=t.getCause())if(t instanceof java.sql.SQLException sql&&"55P03".equals(sql.getSQLState()))return true;return false;}

    public Map<String,Object> commit(long batchId,int version,int expectedValidCount){
        batches.batch(batchId,true,false);   // existence, visibility and imports.grades.manage for the campus before any lock
        Object outcome;
        try{outcome=tx.execute(s->commitLocked(batchId,version,expectedValidCount));}
        catch(PessimisticLockingFailureException e){throw busy();}
        catch(DataIntegrityViolationException e){throw error(HttpStatus.CONFLICT,"The grades conflict with existing data. Nothing was imported; reload the batch.");}
        catch(DataAccessException e){if(lockFailure(e))throw busy();throw e;}
        if(outcome instanceof Stale stale)throw error(HttpStatus.CONFLICT,stale.invalid()+" row(s) can no longer be imported because records changed since this batch was staged or reviewed. Nothing was imported; review the batch again.");
        @SuppressWarnings("unchecked") Map<String,Object> result=(Map<String,Object>)outcome;return result;
    }

    private Object commitLocked(long batchId,int version,int expected){
        var batch=db.queryForList("SELECT * FROM import_batches WHERE id=:id FOR UPDATE NOWAIT",Map.of("id",batchId)).getFirst();
        String status=(String)batch.get("status");long campus=((Number)batch.get("campus_id")).longValue();
        if(("imported".equals(status)||"reconciled".equals(status))&&!isVoided(batch))return result(batchId,true,0,0);
        if(isVoided(batch))throw error(HttpStatus.CONFLICT,"This batch was voided. Upload the file again to import it.");
        if(!"validated".equals(status))throw error(HttpStatus.CONFLICT,"Only a validated batch can be committed.");
        if(((Number)batch.get("version")).intValue()!=version)throw error(HttpStatus.CONFLICT,"The batch changed since you loaded it. Reload before committing.");
        common.actor();
        long unresolved=db.queryForObject("SELECT count(*) FROM import_rows WHERE batch_id=:id AND status='duplicate_flagged'",Map.of("id",batchId),Long.class);
        if(unresolved>0)throw error(HttpStatus.CONFLICT,unresolved+" row(s) need an identity decision. Link each to a student or skip it before committing.");
        var rows=db.queryForList("SELECT * FROM import_rows WHERE batch_id=:id AND status='valid' ORDER BY row_number FOR UPDATE",Map.of("id",batchId));
        if(rows.size()!=expected)throw error(HttpStatus.CONFLICT,"Expected "+expected+" importable rows but the batch has "+rows.size()+". Reload before committing.");
        if(rows.isEmpty())throw error(HttpStatus.CONFLICT,"The batch has no importable rows.");

        // Final student of each row: the reviewed link, else the staged match.
        Map<Long,Long> studentOf=new HashMap<>();Map<Long,JsonNode> normalized=new HashMap<>();Set<Long> studentIds=new LinkedHashSet<>(),subjectIds=new HashSet<>(),termIds=new HashSet<>(),periodIds=new HashSet<>();
        for(var r:rows){
            long id=((Number)r.get("id")).longValue();var v=common.node(r.get("normalized_values"));normalized.put(id,v);
            Long student=r.get("matched_student_id")!=null?((Number)r.get("matched_student_id")).longValue():v.path("studentId").isNumber()?v.path("studentId").asLong():null;
            studentOf.put(id,student);if(student!=null)studentIds.add(student);
            termIds.add(v.path("context").path("termId").asLong());
            for(var c:v.path("cells")){subjectIds.add(c.path("subjectId").asLong());if(c.path("periodId").isNumber())periodIds.add(c.path("periodId").asLong());}
        }
        Set<Long> presentStudents=new HashSet<>(ids("SELECT id FROM students WHERE campus_id=:campus AND id IN (:v)",studentIds,campus));
        Set<Long> presentSubjects=new HashSet<>(ids("SELECT id FROM subjects WHERE id IN (:v)",subjectIds,0));
        Set<Long> presentTerms=new HashSet<>(ids("SELECT id FROM academic_terms WHERE id IN (:v)",termIds,0));
        Set<Long> presentPeriods=new HashSet<>(ids("SELECT id FROM grading_periods WHERE id IN (:v)",periodIds,0));
        Map<String,String> recorded=recorded(studentIds);

        Map<Long,Demotion> demoted=new LinkedHashMap<>();Map<String,Integer> seen=new HashMap<>();Map<Long,Set<String>> skipCells=new HashMap<>();
        for(var r:rows){
            long id=((Number)r.get("id")).longValue();var v=normalized.get(id);Long student=studentOf.get(id);long term=v.path("context").path("termId").asLong();
            if(student==null||!presentStudents.contains(student)){demoted.put(id,new Demotion("STUDENT_UNAVAILABLE",null,"The student is no longer at this campus."));continue;}
            if(!presentTerms.contains(term)){demoted.put(id,new Demotion("SETUP_UNAVAILABLE","term","The term no longer exists."));continue;}
            Demotion bad=null;
            for(var c:v.path("cells")){
                long subject=c.path("subjectId").asLong();
                if(!presentSubjects.contains(subject)||(c.path("periodId").isNumber()&&!presentPeriods.contains(c.path("periodId").asLong()))){bad=new Demotion("SETUP_UNAVAILABLE",c.path("column").asText(),"The subject or grading period no longer exists.");break;}
                String key=key(student,term,subject,c);String previous=recorded.get(key);
                if(previous!=null&&!previous.equals(c.has("value")?"v"+new BigDecimal(c.path("value").asText()).setScale(2).toPlainString():"m"+c.path("mark").asText())){bad=new Demotion("CONFLICTS_WITH_RECORDED",c.path("column").asText(),"A different legacy grade was recorded for this student, subject and period after staging. Void the earlier record first.");break;}
                if(previous!=null)skipCells.computeIfAbsent(id,k->new HashSet<>()).add(key);
                Integer first=seen.get(key);if(first!=null&&first!=((Number)r.get("row_number")).intValue()){bad=new Demotion("DUPLICATE_IN_BATCH",c.path("column").asText(),"This student already has a value for the same subject and period on row "+first+".");break;}
            }
            if(bad!=null){demoted.put(id,bad);continue;}
            for(var c:v.path("cells"))seen.putIfAbsent(key(student,term,c.path("subjectId").asLong(),c),((Number)r.get("row_number")).intValue());
        }
        if(!demoted.isEmpty())return demote(batchId,demoted,rows);

        List<SqlParameterSource> inserts=new ArrayList<>();int skippedCells=0;
        for(var r:rows){
            long id=((Number)r.get("id")).longValue();var v=normalized.get(id);long student=studentOf.get(id);var ctx=v.path("context");
            db.update("UPDATE import_rows SET status='imported',student_id=:student,version=version+1 WHERE id=:id",Map.of("id",id,"student",student));
            for(var c:v.path("cells")){
                String key=key(student,ctx.path("termId").asLong(),c.path("subjectId").asLong(),c);
                if(skipCells.getOrDefault(id,Set.of()).contains(key)){skippedCells++;continue;}
                String column=c.path("column").asText();
                inserts.add(new MapSqlParameterSource().addValue("student",student).addValue("campus",campus).addValue("term",ctx.path("termId").asLong())
                    .addValue("period",c.path("periodId").isNumber()?c.path("periodId").asLong():null).addValue("subject",c.path("subjectId").asLong()).addValue("kind",c.path("kind").asText())
                    .addValue("level",ctx.path("gradeLevel").asText()).addValue("section",ctx.path("section").isNull()||ctx.path("section").isMissingNode()?null:ctx.path("section").asText())
                    .addValue("value",c.has("value")?new BigDecimal(c.path("value").asText()):null).addValue("mark",c.has("mark")?c.path("mark").asText():null)
                    .addValue("row",id).addValue("column",column.length()>200?column.substring(0,200):column));
            }
        }
        if(!inserts.isEmpty())db.batchUpdate("INSERT INTO legacy_grade_records(student_id,campus_id,term_id,grading_period_id,subject_id,record_kind,grade_level,section_name,value,mark,import_row_id,source_column) VALUES (:student,:campus,:term,:period,:subject,:kind,:level,:section,:value,:mark,:row,:column)",inserts.toArray(SqlParameterSource[]::new));
        common.recount(batchId);
        db.update("UPDATE import_batches SET status='imported',imported_by=:actor,imported_at=current_timestamp WHERE id=:id",Map.of("id",batchId,"actor",common.identity().userId()));
        return result(batchId,false,inserts.size(),skippedCells);
    }

    private boolean isVoided(Map<String,Object> batch){return batch.get("voided_at")!=null;}
    private static String key(long student,long term,long subject,JsonNode cell){return student+"|"+term+"|"+subject+"|"+("reported_final".equals(cell.path("kind").asText())?"final":cell.path("periodId").asLong());}
    private List<Long> ids(String sql,Collection<Long> values,long campus){
        List<Long> out=new ArrayList<>();if(values.isEmpty())return out;List<Long> all=new ArrayList<>(values);
        for(int i=0;i<all.size();i+=5000){Map<String,Object> p=new HashMap<>();p.put("v",all.subList(i,Math.min(all.size(),i+5000)));p.put("campus",campus);for(var m:db.queryForList(sql,p))out.add(((Number)m.get("id")).longValue());}
        return out;
    }
    /** Live legacy records of these students, keyed like {@link #key}, valued "v88.50" or "mINC". */
    private Map<String,String> recorded(Collection<Long> students){
        Map<String,String> out=new HashMap<>();List<Long> all=new ArrayList<>(students);
        for(int i=0;i<all.size();i+=5000)for(var m:db.queryForList("SELECT student_id,term_id,subject_id,record_kind,grading_period_id,value,mark FROM legacy_grade_records WHERE voided_at IS NULL AND student_id IN (:v)",Map.of("v",all.subList(i,Math.min(all.size(),i+5000)))))
            out.put(m.get("student_id")+"|"+m.get("term_id")+"|"+m.get("subject_id")+"|"+("reported_final".equals(m.get("record_kind"))?"final":m.get("grading_period_id")),
                m.get("value")!=null?"v"+((BigDecimal)m.get("value")).setScale(2).toPlainString():"m"+m.get("mark"));
        return out;
    }

    private Object demote(long batchId,Map<Long,Demotion> demoted,List<Map<String,Object>> rows){
        Map<Long,Integer> numbers=new HashMap<>();for(var r:rows)numbers.put(((Number)r.get("id")).longValue(),((Number)r.get("row_number")).intValue());
        for(var e:demoted.entrySet()){
            long id=e.getKey();var d=e.getValue();
            db.update("UPDATE import_rows SET status='invalid',review_decision=NULL,review_reason=NULL,reviewed_by=NULL,reviewed_at=NULL,matched_student_id=NULL,version=version+1 WHERE id=:id",Map.of("id",id));
            Map<String,Object> p=new HashMap<>();p.put("b",batchId);p.put("rn",numbers.get(id));p.put("code",d.code());p.put("detail",d.detail());p.put("row",id);p.put("field",d.field()==null?null:d.field().length()>200?d.field().substring(0,200):d.field());
            db.update("INSERT INTO import_exceptions(batch_id,row_number,error_code,detail,import_row_id,field_name,severity) VALUES (:b,:rn,:code,:detail,:row,:field,'error')",p);
        }
        common.recount(batchId);
        return new Stale(demoted.size());
    }

    private Map<String,Object> result(long batchId,boolean already,int created,int skippedCells){
        var b=db.queryForList("SELECT * FROM import_batches WHERE id=:id",Map.of("id",batchId)).getFirst();
        long records=db.queryForObject("SELECT count(*) FROM legacy_grade_records g JOIN import_rows r ON r.id=g.import_row_id WHERE r.batch_id=:id",Map.of("id",batchId),Long.class);
        Map<String,Object> m=new LinkedHashMap<>();m.put("batchId",batchId);m.put("status",b.get("status"));m.put("rowsImported",b.get("imported_count"));
        m.put("recordsCreated",already?records:created);m.put("recordsAlreadyRecorded",skippedCells);m.put("skipped",b.get("skipped_count"));m.put("invalid",b.get("invalid_count"));
        m.put("importedCount",b.get("imported_count"));m.put("version",b.get("version"));m.put("alreadyImported",already);return m;
    }
}
