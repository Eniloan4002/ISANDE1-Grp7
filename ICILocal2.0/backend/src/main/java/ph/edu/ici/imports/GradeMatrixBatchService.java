package ph.edu.ici.imports;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.AccessPolicy;

/**
 * Reads grade-matrix batches and applies the registrar actions that never touch grade values: review, void and discard.
 * Reads need imports.read; every write needs imports.grades.manage for the batch's campus. Nothing here creates a student.
 */
@Service
public class GradeMatrixBatchService {
    static final String SOURCE=GradeMatrixMappingService.SOURCE;
    private final NamedParameterJdbcTemplate db;private final AccessPolicy access;private final ImportBatchService common;

    public GradeMatrixBatchService(NamedParameterJdbcTemplate db,AccessPolicy access,ImportBatchService common){this.db=db;this.access=access;this.common=common;}
    private ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    private long n(Object value){return ((Number)value).longValue();}
    private void paging(int page,int size){if(page<0||page>1_000_000||size<1||size>100)throw error(HttpStatus.BAD_REQUEST,"Use a nonnegative page and a size of 1 to 100.");}

    /** Loads a grade batch the caller may see; for writes the caller must hold imports.grades.manage for its campus. A batch of another campus or source type is a 404. */
    Map<String,Object> batch(long id,boolean write,boolean lock){
        var rows=db.queryForList("SELECT * FROM import_batches WHERE id=:id AND source_type=:s"+(lock?" FOR UPDATE":""),Map.of("id",id,"s",SOURCE));
        if(rows.isEmpty()||rows.getFirst().get("campus_id")==null)throw error(HttpStatus.NOT_FOUND,"Import batch not found.");
        long campus=n(rows.getFirst().get("campus_id"));
        if(!access.campuses("imports.read").contains(campus)&&!access.campuses("imports.grades.manage").contains(campus))throw error(HttpStatus.NOT_FOUND,"Import batch not found.");
        if(write&&!access.campuses("imports.grades.manage").contains(campus))throw error(HttpStatus.FORBIDDEN,"Your account cannot change grade imports for this campus.");
        return rows.getFirst();
    }

    // ---- reads ---------------------------------------------------------------------------------------------------------

    Map<String,Object> summary(Map<String,Object> b){
        var m=common.summary(b);
        m.put("uploadContext",common.node(b.get("upload_context")));
        m.put("voidedAt",b.get("voided_at"));m.put("voidedBy",b.get("voided_by"));m.put("voidReason",b.get("void_reason"));
        var discard=discard(n(b.get("id")),(String)b.get("status"));
        m.put("discarded",discard!=null);
        if(discard!=null){m.put("discardedAt",discard.get("created_at"));m.put("discardedBy",discard.get("created_by"));m.put("discardReason",discard.get("detail"));}
        return m;
    }
    /** A discarded batch is a `failed` batch carrying a batch-level DISCARDED exception (actor and time come from its metadata). */
    private Map<String,Object> discard(long batchId,String status){
        if(!"failed".equals(status))return null;
        var rows=db.queryForList("SELECT detail,created_at,created_by FROM import_exceptions WHERE batch_id=:id AND error_code='DISCARDED' AND row_number IS NULL ORDER BY id DESC LIMIT 1",Map.of("id",batchId));
        return rows.isEmpty()?null:rows.getFirst();
    }

    @Transactional(readOnly=true) public Map<String,Object> list(Long campusId,String status,int page,int size){
        paging(page,size);var scope=access.campuses("imports.read");
        if(campusId!=null&&!scope.contains(campusId))throw error(HttpStatus.FORBIDDEN,"Your account cannot view grade imports for this campus.");
        if(status!=null&&!List.of("staged","validated","imported","reconciled","failed").contains(status))throw error(HttpStatus.BAD_REQUEST,"Unknown batch status.");
        if(scope.isEmpty())return Map.of("items",List.of(),"total",0,"page",page,"size",size);
        Map<String,Object> p=new HashMap<>();p.put("s",SOURCE);p.put("scope",campusId==null?scope:List.of(campusId));p.put("limit",size);p.put("offset",(long)page*size);
        String where=" WHERE source_type=:s AND campus_id IN (:scope)";if(status!=null){where+=" AND status=:status";p.put("status",status);}
        var items=db.queryForList("SELECT * FROM import_batches"+where+" ORDER BY started_at DESC,id DESC LIMIT :limit OFFSET :offset",p).stream().map(this::summary).toList();
        return Map.of("items",items,"total",db.queryForObject("SELECT count(*) FROM import_batches"+where,p,Long.class),"page",page,"size",size);
    }

    @Transactional(readOnly=true) public Map<String,Object> detail(long id){
        var b=batch(id,false,false);var m=summary(b);
        m.put("batchExceptions",db.queryForList("SELECT error_code AS code,detail,severity,field_name AS field FROM import_exceptions WHERE batch_id=:id AND row_number IS NULL ORDER BY id",Map.of("id",id)));
        m.put("errorSummary",db.queryForList("SELECT error_code AS code,severity,count(*) AS count FROM import_exceptions WHERE batch_id=:id AND row_number IS NOT NULL GROUP BY error_code,severity ORDER BY severity,error_code",Map.of("id",id)));
        var counts=db.queryForMap("""
            SELECT coalesce(sum(jsonb_array_length(normalized_values->'cells')) FILTER (WHERE status IN ('valid','imported')),0) AS records,
                   coalesce(sum((normalized_values->>'blankCells')::int) FILTER (WHERE normalized_values IS NOT NULL),0) AS blanks
            FROM import_rows WHERE batch_id=:id""",Map.of("id",id));
        m.put("recordCount",counts.get("records"));m.put("blankCellCount",counts.get("blanks"));
        m.put("columns",db.queryForList("""
            SELECT c->>'column' AS column,count(*) AS records FROM import_rows r, jsonb_array_elements(r.normalized_values->'cells') c
            WHERE r.batch_id=:id AND r.status IN ('valid','imported') GROUP BY 1 ORDER BY 1""",Map.of("id",id)));
        m.put("liveRecordCount",db.queryForObject("SELECT count(*) FROM legacy_grade_records g JOIN import_rows r ON r.id=g.import_row_id WHERE r.batch_id=:id AND g.voided_at IS NULL",Map.of("id",id),Long.class));
        m.put("voidedRecordCount",db.queryForObject("SELECT count(*) FROM legacy_grade_records g JOIN import_rows r ON r.id=g.import_row_id WHERE r.batch_id=:id AND g.voided_at IS NOT NULL",Map.of("id",id),Long.class));
        m.put("dependentBatches",dependents(id));
        return m;
    }
    @Transactional(readOnly=true) public Map<String,Object> rows(long id,String status,int page,int size){
        paging(page,size);batch(id,false,false);
        if(status!=null&&!ImportBatchService.ROW_STATUSES.contains(status))throw error(HttpStatus.BAD_REQUEST,"Unknown row status.");
        Map<String,Object> p=new HashMap<>();p.put("id",id);p.put("limit",size);p.put("offset",(long)page*size);String where=" WHERE batch_id=:id";if(status!=null){where+=" AND status=:status";p.put("status",status);}
        var rows=db.queryForList("SELECT * FROM import_rows"+where+" ORDER BY row_number LIMIT :limit OFFSET :offset",p);
        Map<Long,List<Map<String,Object>>> messages=new HashMap<>();
        if(!rows.isEmpty())for(var e:db.queryForList("SELECT import_row_id,error_code AS code,field_name AS field,severity,detail FROM import_exceptions WHERE import_row_id IN (:ids) ORDER BY id",Map.of("ids",rows.stream().map(r->r.get("id")).toList()))){
            long rowId=n(e.remove("import_row_id"));messages.computeIfAbsent(rowId,k->new ArrayList<>()).add(e);
        }
        var items=rows.stream().map(r->common.rowView(r,messages.getOrDefault(n(r.get("id")),List.of()))).toList();
        return Map.of("items",items,"total",db.queryForObject("SELECT count(*) FROM import_rows"+where,p,Long.class),"page",page,"size",size);
    }
    @Transactional(readOnly=true) public Map<String,Object> errors(long id,String severity,Integer rowNumber,int page,int size){
        paging(page,size);batch(id,false,false);
        if(severity!=null&&!List.of("error","warning").contains(severity))throw error(HttpStatus.BAD_REQUEST,"Severity must be error or warning.");
        Map<String,Object> p=new HashMap<>();p.put("id",id);p.put("limit",size);p.put("offset",(long)page*size);String where=" WHERE batch_id=:id";
        if(severity!=null){where+=" AND severity=:severity";p.put("severity",severity);}if(rowNumber!=null){where+=" AND row_number=:rn";p.put("rn",rowNumber);}
        return Map.of("items",db.queryForList("SELECT id,row_number AS \"rowNumber\",import_row_id AS \"rowId\",error_code AS code,field_name AS field,severity,detail,resolved FROM import_exceptions"+where+" ORDER BY row_number NULLS FIRST,id LIMIT :limit OFFSET :offset",p),
            "total",db.queryForObject("SELECT count(*) FROM import_exceptions"+where,p,Long.class),"page",page,"size",size);
    }

    // ---- review --------------------------------------------------------------------------------------------------------

    /** Confirm a row's identity (same_person, one of its candidate students) or skip it. A grade row can never create a student. */
    @Transactional public Map<String,Object> review(long batchId,long rowId,int version,String decision,String reason,Long studentId){
        var batch=batch(batchId,true,true);common.actor();
        if("distinct".equals(decision))throw error(HttpStatus.CONFLICT,"A grade row can never create a student. Link it to an existing student or skip it.");
        if(!"same_person".equals(decision)&&!"skip".equals(decision))throw error(HttpStatus.BAD_REQUEST,"Unknown decision.");
        if(reason==null||reason.isBlank())throw error(HttpStatus.BAD_REQUEST,"A reason is required.");
        if(!"validated".equals(batch.get("status")))throw error(HttpStatus.CONFLICT,"Only a batch awaiting commit can be reviewed.");
        var rows=db.queryForList("SELECT * FROM import_rows WHERE id=:id AND batch_id=:b FOR UPDATE",Map.of("id",rowId,"b",batchId));
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Import row not found.");var row=rows.getFirst();
        if(((Number)row.get("version")).intValue()!=version)throw error(HttpStatus.CONFLICT,"This row changed. Reload before deciding.");
        String status=(String)row.get("status");Long link=null;String newStatus;
        if("skip".equals(decision)){
            if(!List.of("valid","duplicate_flagged").contains(status))throw error(HttpStatus.CONFLICT,"Only valid or flagged rows can be skipped.");
            newStatus="skipped";
        }else{
            var evidence=common.node(row.get("duplicate_evidence"));
            if(!"duplicate_flagged".equals(status)||evidence==null)throw error(HttpStatus.CONFLICT,"Only a row flagged for identity review can be linked to a student.");
            if(studentId==null)throw error(HttpStatus.BAD_REQUEST,"Choose the existing student this row describes.");
            boolean listed=false;for(var e:evidence)if("student".equals(e.path("kind").asText())&&e.path("id").asLong(-1)==studentId)listed=true;
            if(!listed)throw error(HttpStatus.CONFLICT,"The student must be one of the candidates shown for this row.");
            var student=db.queryForList("SELECT campus_id FROM students WHERE id=:id",Map.of("id",studentId));
            if(student.isEmpty()||!Objects.equals(student.getFirst().get("campus_id"),batch.get("campus_id")))throw error(HttpStatus.CONFLICT,"That student is no longer at this campus.");
            link=studentId;newStatus="valid";
        }
        Map<String,Object> p=new HashMap<>();p.put("id",rowId);p.put("status",newStatus);p.put("decision",decision);p.put("reason",reason.trim());p.put("actor",common.identity().userId());p.put("link",link);
        db.update("UPDATE import_rows SET status=:status,review_decision=:decision,review_reason=:reason,reviewed_by=:actor,reviewed_at=current_timestamp,matched_student_id=:link,version=version+1 WHERE id=:id",p);
        common.recount(batchId);
        var fresh=db.queryForList("SELECT * FROM import_rows WHERE id=:id",Map.of("id",rowId)).getFirst();
        return Map.of("row",common.rowView(fresh,List.of()),"batch",summary(db.queryForList("SELECT * FROM import_batches WHERE id=:id",Map.of("id",batchId)).getFirst()));
    }

    // ---- void and discard ----------------------------------------------------------------------------------------------

    /**
     * Voids every live record of a committed batch in one transaction; the checksum is then free for a new upload.
     * Refused while another live batch relies on this batch's records (see {@link #dependents}).
     */
    @Transactional public Map<String,Object> voidBatch(long batchId,int version,String reason){
        var batch=batch(batchId,true,true);common.actor();
        if(reason==null||reason.isBlank()||reason.trim().length()>2000)throw error(HttpStatus.BAD_REQUEST,"A reason of 1 to 2000 characters is required.");
        if(batch.get("voided_at")!=null)return voidResult(batchId,true,0);
        if(!"imported".equals(batch.get("status")))throw error(HttpStatus.CONFLICT,"Only a committed batch can be voided. Discard a batch that was never committed.");
        if(((Number)batch.get("version")).intValue()!=version)throw error(HttpStatus.CONFLICT,"The batch changed since you loaded it. Reload before voiding.");
        // Lock this batch's live records before the check. A commit reads existing records FOR SHARE, so it either finished
        // before this point (and the check sees it) or waits until the void is done (and then records the grades itself).
        db.queryForList("SELECT g.id FROM legacy_grade_records g JOIN import_rows r ON r.id=g.import_row_id WHERE r.batch_id=:b AND g.voided_at IS NULL ORDER BY g.id FOR UPDATE OF g",Map.of("b",batchId));
        var dependents=dependents(batchId);
        if(!dependents.isEmpty())throw dependentsError(dependents);
        Map<String,Object> p=Map.of("b",batchId,"actor",common.identity().userId(),"reason",reason.trim());
        int voided=db.update("UPDATE legacy_grade_records SET voided_at=current_timestamp,voided_by=:actor,void_reason=:reason WHERE voided_at IS NULL AND import_row_id IN (SELECT id FROM import_rows WHERE batch_id=:b)",p);
        db.update("UPDATE import_batches SET voided_at=current_timestamp,voided_by=:actor,void_reason=:reason,version=version+1 WHERE id=:b",p);
        return voidResult(batchId,false,voided);
    }
    /**
     * Other live grade batches that rely on this batch's live records (ICI2-89). When a later batch is committed, cells whose
     * grade is already recorded are skipped as ALREADY_RECORDED and the row is still marked imported, so the later batch owns no
     * record for them. Only one live record can exist per student, term, subject and period, so an imported cell of another live
     * batch that matches one of this batch's live records was always skipped and depends on it.
     */
    List<Map<String,Object>> dependents(long batchId){
        return db.queryForList("""
            WITH owned AS (
                SELECT g.student_id,g.term_id,g.subject_id,CASE WHEN g.record_kind='reported_final' THEN -1 ELSE g.grading_period_id END AS slot
                FROM legacy_grade_records g JOIN import_rows r ON r.id=g.import_row_id
                WHERE r.batch_id=:b AND g.voided_at IS NULL)
            SELECT b.id AS "batchId",b.source_name AS "sourceName",count(*) AS "sharedRecords"
            FROM import_batches b JOIN import_rows r ON r.batch_id=b.id AND r.status='imported'
            CROSS JOIN LATERAL jsonb_array_elements(r.normalized_values->'cells') c
            JOIN owned o ON o.student_id=r.student_id AND o.term_id=(r.normalized_values->'context'->>'termId')::bigint
                AND o.subject_id=(c->>'subjectId')::bigint AND o.slot=CASE WHEN c->>'kind'='reported_final' THEN -1 ELSE (c->>'periodId')::bigint END
            WHERE b.source_type=:s AND b.id<>:b AND b.voided_at IS NULL AND b.status IN ('imported','reconciled')
              AND r.student_id IN (SELECT student_id FROM owned)
            GROUP BY b.id,b.source_name ORDER BY b.id""",Map.of("b",batchId,"s",SOURCE));
    }
    private ResponseStatusException dependentsError(List<Map<String,Object>> dependents){
        var named=dependents.stream().limit(5).map(d->"#"+d.get("batchId")+" \""+d.get("sourceName")+"\" ("+d.get("sharedRecords")+" grade(s))").toList();
        String list=String.join(", ",named)+(dependents.size()>5?" and "+(dependents.size()-5)+" more":"");
        boolean one=dependents.size()==1;
        var e=error(HttpStatus.CONFLICT,(one?"Batch ":"Batches ")+list+" skipped grades that this batch recorded, so "+(one?"it relies":"they rely")
            +" on them. Void "+(one?"that batch":"those batches")+" first, then void this one and upload the corrected file again.");
        e.getBody().setProperty("code","VOID_HAS_DEPENDENT_BATCHES");e.getBody().setProperty("dependentBatches",dependents);
        return e;
    }
    private Map<String,Object> voidResult(long batchId,boolean already,int voided){
        var b=db.queryForList("SELECT * FROM import_batches WHERE id=:id",Map.of("id",batchId)).getFirst();
        Map<String,Object> m=new LinkedHashMap<>();m.put("batchId",batchId);m.put("status",b.get("status"));m.put("voided",true);m.put("voidedRecords",voided);m.put("voidedAt",b.get("voided_at"));m.put("version",b.get("version"));m.put("alreadyVoided",already);return m;
    }

    /**
     * Discards a batch that was staged but never committed. It becomes `failed` (the existing status that already frees the checksum,
     * so no new status or schema change) and gets a batch-level DISCARDED exception holding the reason, actor and time. Rows are kept.
     */
    @Transactional public Map<String,Object> discard(long batchId,int version,String reason){
        var batch=batch(batchId,true,true);common.actor();
        if(reason==null||reason.isBlank()||reason.trim().length()>1000)throw error(HttpStatus.BAD_REQUEST,"A reason of 1 to 1000 characters is required.");
        String status=(String)batch.get("status");
        if("failed".equals(status)){
            if(discard(batchId,status)==null)throw error(HttpStatus.CONFLICT,"A batch that failed on its own cannot be discarded.");
            return discardResult(batchId,true);
        }
        if(!List.of("staged","validated").contains(status))throw error(HttpStatus.CONFLICT,"Only a batch that was never committed can be discarded. Void a committed batch instead.");
        if(((Number)batch.get("version")).intValue()!=version)throw error(HttpStatus.CONFLICT,"The batch changed since you loaded it. Reload before discarding.");
        db.update("UPDATE import_batches SET status='failed',version=version+1 WHERE id=:id",Map.of("id",batchId));
        db.update("INSERT INTO import_exceptions(batch_id,error_code,detail,severity) VALUES (:id,'DISCARDED',:reason,'warning')",Map.of("id",batchId,"reason",reason.trim()));
        return discardResult(batchId,false);
    }
    private Map<String,Object> discardResult(long batchId,boolean already){
        var b=db.queryForList("SELECT * FROM import_batches WHERE id=:id",Map.of("id",batchId)).getFirst();
        Map<String,Object> m=new LinkedHashMap<>();m.put("batchId",batchId);m.put("status",b.get("status"));m.put("discarded",true);m.put("version",b.get("version"));m.put("alreadyDiscarded",already);return m;
    }
}
