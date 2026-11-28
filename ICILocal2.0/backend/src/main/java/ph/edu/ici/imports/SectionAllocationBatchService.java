package ph.edu.ici.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.AccessPolicy;

/**
 * Reads section allocation batches and applies the registrar actions that never touch enrollments: review and discard.
 * Reads need imports.read; every write needs imports.enrollment.manage for the batch's campus. There is no void: an allocation is undone
 * through the enrollment workflow (assign another section, or none), which is audited there.
 */
@Service
public class SectionAllocationBatchService {
    static final String SOURCE=SectionAllocationMappingService.SOURCE;
    private final NamedParameterJdbcTemplate db;private final ImportBatchService common;private final BatchReads reads;

    public SectionAllocationBatchService(NamedParameterJdbcTemplate db,ObjectMapper json,AccessPolicy access,ImportBatchService common){
        this.db=db;this.common=common;this.reads=new BatchReads(db,json,access,SOURCE,"imports.enrollment.manage","section allocation imports");
    }
    private ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    private long n(Object value){return ((Number)value).longValue();}

    /** Loads an allocation batch the caller may see; for writes the caller must hold imports.enrollment.manage for its campus. A batch of another campus or source type is a 404. */
    Map<String,Object> batch(long id,boolean write,boolean lock){return reads.batch(id,write,lock);}

    // ---- reads ---------------------------------------------------------------------------------------------------------

    Map<String,Object> summary(Map<String,Object> b){
        var m=reads.summary(b);
        m.put("uploadContext",common.node(b.get("upload_context")));
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

    @Transactional(readOnly=true) public Map<String,Object> list(Long campusId,String status,int page,int size){return reads.list(campusId,status,page,size,this::summary);}

    @Transactional(readOnly=true) public Map<String,Object> detail(long id){
        var b=batch(id,false,false);var m=summary(b);
        m.put("batchExceptions",db.queryForList("SELECT error_code AS code,detail,severity,field_name AS field FROM import_exceptions WHERE batch_id=:id AND row_number IS NULL ORDER BY id",Map.of("id",id)));
        m.put("errorSummary",db.queryForList("SELECT error_code AS code,severity,count(*) AS count FROM import_exceptions WHERE batch_id=:id AND row_number IS NOT NULL GROUP BY error_code,severity ORDER BY severity,error_code",Map.of("id",id)));
        // What would (or did) change, by action: assign, reassign, noop; counted over rows that can still be committed or were.
        m.put("actions",db.queryForList("""
            SELECT normalized_values->>'action' AS action,count(*) AS count FROM import_rows
            WHERE batch_id=:id AND status IN ('valid','imported') AND normalized_values->>'action' IS NOT NULL GROUP BY 1 ORDER BY 1""",Map.of("id",id)));
        m.put("sections",db.queryForList("""
            SELECT (normalized_values->>'sectionId')::bigint AS "sectionId",normalized_values->>'sectionName' AS section,count(*) AS rows FROM import_rows
            WHERE batch_id=:id AND status IN ('valid','imported') AND normalized_values->>'sectionId' IS NOT NULL GROUP BY 1,2 ORDER BY 2,1""",Map.of("id",id)));
        return m;
    }
    @Transactional(readOnly=true) public Map<String,Object> rows(long id,String status,int page,int size){return reads.rows(id,status,page,size);}
    @Transactional(readOnly=true) public Map<String,Object> errors(long id,String severity,Integer rowNumber,int page,int size){return reads.errors(id,severity,rowNumber,page,size);}

    // ---- review --------------------------------------------------------------------------------------------------------

    /**
     * same_person: confirm which existing student a flagged identity row describes. apply: accept a reassignment (a student already in a
     * different section). skip: leave the row out. distinct is refused (an allocation row can never create a student). Every decision
     * needs a reason, and a row is only reviewed while its batch awaits commit. What a linked or applied row will actually do is decided
     * again from live data at commit, never here.
     */
    @Transactional public Map<String,Object> review(long batchId,long rowId,int version,String decision,String reason,Long studentId){
        var batch=batch(batchId,true,true);common.actor();
        if("distinct".equals(decision))throw error(HttpStatus.CONFLICT,"A section allocation row can never create a student. Link it to an existing student or skip it.");
        if(!List.of("same_person","apply","skip").contains(decision))throw error(HttpStatus.BAD_REQUEST,"Unknown decision.");
        if(reason==null||reason.isBlank())throw error(HttpStatus.BAD_REQUEST,"A reason is required.");
        if(!"validated".equals(batch.get("status")))throw error(HttpStatus.CONFLICT,"Only a batch awaiting commit can be reviewed.");
        var rows=db.queryForList("SELECT * FROM import_rows WHERE id=:id AND batch_id=:b FOR UPDATE",Map.of("id",rowId,"b",batchId));
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Import row not found.");var row=rows.getFirst();
        if(((Number)row.get("version")).intValue()!=version)throw error(HttpStatus.CONFLICT,"This row changed. Reload before deciding.");
        String status=(String)row.get("status");Long link=null;String newStatus;
        var evidence=common.node(row.get("duplicate_evidence"));
        if("skip".equals(decision)){
            if(!List.of("valid","duplicate_flagged").contains(status))throw error(HttpStatus.CONFLICT,"Only valid or flagged rows can be skipped.");
            newStatus="skipped";
        }else if("apply".equals(decision)){
            if(!"duplicate_flagged".equals(status)||!(has(evidence,"reassignment")||has(evidence,"changed")))throw error(HttpStatus.CONFLICT,"Only a row that would move a student to a different section, or whose enrollment changed since staging, can be applied.");
            if(has(evidence,"student"))throw error(HttpStatus.CONFLICT,"Link this row to its student first (same_person); then it can be applied.");
            newStatus="valid";
        }else{
            if(!"duplicate_flagged".equals(status)||!has(evidence,"student"))throw error(HttpStatus.CONFLICT,"Only a row flagged for identity review can be linked to a student.");
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
    private static boolean has(com.fasterxml.jackson.databind.JsonNode evidence,String kind){
        if(evidence==null)return false;for(var e:evidence)if(kind.equals(e.path("kind").asText()))return true;return false;
    }

    // ---- discard -------------------------------------------------------------------------------------------------------

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
        if(!List.of("staged","validated").contains(status))throw error(HttpStatus.CONFLICT,"Only a batch that was never committed can be discarded. Change a committed allocation through the enrollment workflow.");
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
