package ph.edu.ici.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.AccessPolicy;
import ph.edu.ici.auth.StaffIdentity;

/** Reads staged batches/rows/exceptions and records registrar review decisions. Writes never create students. */
@Service
public class ImportBatchService {
    static final List<String> ROW_STATUSES=BatchReads.ROW_STATUSES;
    private final NamedParameterJdbcTemplate db;private final AccessPolicy access;private final BatchReads reads;

    public ImportBatchService(NamedParameterJdbcTemplate db,ObjectMapper json,AccessPolicy access){this.db=db;this.access=access;this.reads=new BatchReads(db,json,access,ImportMappingService.SOURCE,"imports.manage","imports");}
    StaffIdentity identity(){return (StaffIdentity)SecurityContextHolder.getContext().getAuthentication().getPrincipal();}
    ResponseStatusException error(HttpStatus status,String message){return reads.error(status,message);}
    void actor(){db.queryForObject("SELECT set_config('app.actor_id',:actor,true)",Map.of("actor",Long.toString(identity().userId())),String.class);}
    long n(Object value){return reads.n(value);}
    String json(Object value){return reads.json(value);}
    JsonNode node(Object value){return reads.node(value);}

    /** Loads a batch the caller may see; for writes the caller must hold imports.manage for its campus. */
    Map<String,Object> batch(long id,boolean write,boolean lock){return reads.batch(id,write,lock);}
    Map<String,Object> summary(Map<String,Object> b){
        var m=reads.summary(b);
        var discard=discardRecord(n(b.get("id")),(String)b.get("status"));
        m.put("discarded",discard!=null);
        if(discard!=null){m.put("discardedAt",discard.get("created_at"));m.put("discardedBy",discard.get("created_by"));m.put("discardReason",discard.get("detail"));}
        return m;
    }

    /** A discarded batch is a `failed` batch carrying a batch-level DISCARDED exception (actor and time come from its metadata). */
    private Map<String,Object> discardRecord(long batchId,String status){
        if(!"failed".equals(status))return null;
        var rows=db.queryForList("SELECT detail,created_at,created_by FROM import_exceptions WHERE batch_id=:id AND error_code='DISCARDED' AND row_number IS NULL ORDER BY id DESC LIMIT 1",Map.of("id",batchId));
        return rows.isEmpty()?null:rows.getFirst();
    }

    /**
     * Discards a batch that was staged or validated but never committed, for example a file uploaded under the wrong campus.
     * It becomes `failed`, the existing status that already frees the file checksum and removes the batch's rows from other uploads'
     * duplicate checks, and gets a batch-level DISCARDED exception holding the reason, actor and time. Rows are kept for audit.
     */
    @Transactional public Map<String,Object> discard(long batchId,int version,String reason){
        var batch=batch(batchId,true,true);actor();
        if(reason==null||reason.isBlank()||reason.trim().length()>1000)throw error(HttpStatus.BAD_REQUEST,"A reason of 1 to 1000 characters is required.");
        String status=(String)batch.get("status");
        if("failed".equals(status)){
            if(discardRecord(batchId,status)==null)throw error(HttpStatus.CONFLICT,"A batch that failed on its own cannot be discarded.");
            return discardResult(batchId,true);
        }
        if(!List.of("staged","validated").contains(status))throw error(HttpStatus.CONFLICT,"Only a batch that was never committed can be discarded.");
        if(((Number)batch.get("version")).intValue()!=version)throw error(HttpStatus.CONFLICT,"The batch changed since you loaded it. Reload before discarding.");
        db.update("UPDATE import_batches SET status='failed',version=version+1 WHERE id=:id",Map.of("id",batchId));
        db.update("INSERT INTO import_exceptions(batch_id,error_code,detail,severity) VALUES (:id,'DISCARDED',:reason,'warning')",Map.of("id",batchId,"reason",reason.trim()));
        return discardResult(batchId,false);
    }
    private Map<String,Object> discardResult(long batchId,boolean already){
        var b=db.queryForList("SELECT * FROM import_batches WHERE id=:id",Map.of("id",batchId)).getFirst();
        Map<String,Object> m=new LinkedHashMap<>();m.put("batchId",batchId);m.put("status",b.get("status"));m.put("discarded",true);m.put("version",b.get("version"));m.put("alreadyDiscarded",already);return m;
    }

    @Transactional(readOnly=true) public Map<String,Object> list(Long campusId,String status,int page,int size){return reads.list(campusId,status,page,size,this::summary);}
    @Transactional(readOnly=true) public Map<String,Object> detail(long id){
        var b=batch(id,false,false);var m=summary(b);
        m.put("batchExceptions",db.queryForList("SELECT error_code AS code,detail,severity FROM import_exceptions WHERE batch_id=:id AND row_number IS NULL ORDER BY id",Map.of("id",id)));
        m.put("errorSummary",db.queryForList("SELECT error_code AS code,severity,count(*) AS count FROM import_exceptions WHERE batch_id=:id AND row_number IS NOT NULL GROUP BY error_code,severity ORDER BY severity,error_code",Map.of("id",id)));
        return m;
    }
    @Transactional(readOnly=true) public Map<String,Object> rows(long id,String status,int page,int size){return reads.rows(id,status,page,size);}
    Map<String,Object> rowView(Map<String,Object> r,List<Map<String,Object>> messages){return reads.rowView(r,messages);}
    @Transactional(readOnly=true) public Map<String,Object> errors(long id,String severity,Integer rowNumber,int page,int size){return reads.errors(id,severity,rowNumber,page,size);}

    /** Recomputes the batch's per-status counts from its rows and bumps the batch version. */
    void recount(long batchId){
        db.update("""
            UPDATE import_batches b SET
             valid_count=(SELECT count(*) FROM import_rows WHERE batch_id=b.id AND status='valid'),
             invalid_count=(SELECT count(*) FROM import_rows WHERE batch_id=b.id AND status='invalid'),
             flagged_count=(SELECT count(*) FROM import_rows WHERE batch_id=b.id AND status='duplicate_flagged'),
             skipped_count=(SELECT count(*) FROM import_rows WHERE batch_id=b.id AND status='skipped'),
             imported_count=(SELECT count(*) FROM import_rows WHERE batch_id=b.id AND status='imported'),
             version=version+1 WHERE b.id=:id""",Map.of("id",batchId));
    }

    @Transactional public Map<String,Object> review(long batchId,long rowId,int version,String decision,String reason,Long studentId){
        var scope=access.campuses("imports.manage");
        var batch=batch(batchId,true,true);actor();
        if(!"validated".equals(batch.get("status")))throw error(HttpStatus.CONFLICT,"Only a batch awaiting commit can be reviewed.");
        var rows=db.queryForList("SELECT * FROM import_rows WHERE id=:id AND batch_id=:b FOR UPDATE",Map.of("id",rowId,"b",batchId));
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Import row not found.");var row=rows.getFirst();
        if(((Number)row.get("version")).intValue()!=version)throw error(HttpStatus.CONFLICT,"This row changed. Reload before deciding.");
        String status=(String)row.get("status");
        if(reason==null||reason.isBlank())throw error(HttpStatus.BAD_REQUEST,"A reason is required.");
        Long link=null;String newStatus="valid";
        if("skip".equals(decision)){
            if(!List.of("valid","duplicate_flagged").contains(status))throw error(HttpStatus.CONFLICT,"Only valid or flagged rows can be skipped.");
            newStatus="skipped";
        }else if("same_person".equals(decision)||"distinct".equals(decision)){
            var evidence=node(row.get("duplicate_evidence"));
            if(evidence==null||!List.of("duplicate_flagged","valid").contains(status))throw error(HttpStatus.CONFLICT,"Only rows flagged as possible duplicates need a same-person or distinct decision.");
            boolean hidden=false;for(var e:evidence)if(!e.path("campusVisible").asBoolean(false))hidden=true;
            if(hidden&&!scope.containsAll(db.getJdbcTemplate().queryForList("SELECT id FROM campuses WHERE active",Long.class)))
                throw error(HttpStatus.FORBIDDEN,"This row matches a record at a campus you cannot view; an administrator must review it.");
            if("same_person".equals(decision)){
                if(studentId==null)throw error(HttpStatus.BAD_REQUEST,"Choose the existing student this row describes.");
                boolean listed=false;for(var e:evidence)if("student".equals(e.path("kind").asText())&&e.path("id").asLong(-1)==studentId)listed=true;
                if(!listed)throw error(HttpStatus.CONFLICT,"The student must be one of the matches shown for this row.");
                var student=db.queryForList("SELECT campus_id,active FROM students WHERE id=:id",Map.of("id",studentId));
                if(student.isEmpty()||student.getFirst().get("campus_id")==null||!scope.contains(n(student.getFirst().get("campus_id"))))throw error(HttpStatus.FORBIDDEN,"Your account cannot link to that student.");
                if(!Boolean.TRUE.equals(student.getFirst().get("active")))throw error(HttpStatus.CONFLICT,"Reactivate the existing student before linking.");
                link=studentId;
            }else{
                for(var e:evidence)if("LRN_EXACT".equals(e.path("rule").asText()))throw error(HttpStatus.CONFLICT,"Distinct people cannot share a permanent LRN. Correct the source or link/skip the row.");
                var number=node(row.get("normalized_values")).path("studentNumber").asText();
                if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM students WHERE student_number=:n)",Map.of("n",number),Boolean.class)))throw error(HttpStatus.CONFLICT,"This student number already belongs to another student.");
            }
        }else throw error(HttpStatus.BAD_REQUEST,"Unknown decision.");
        Map<String,Object> p=new HashMap<>();p.put("id",rowId);p.put("status",newStatus);p.put("decision",decision);p.put("reason",reason.trim());p.put("actor",identity().userId());p.put("link",link);
        db.update("UPDATE import_rows SET status=:status,review_decision=:decision,review_reason=:reason,reviewed_by=:actor,reviewed_at=current_timestamp,matched_student_id=:link,version=version+1 WHERE id=:id",p);
        recount(batchId);
        var fresh=db.queryForList("SELECT * FROM import_rows WHERE id=:id",Map.of("id",rowId)).getFirst();
        return Map.of("row",rowView(fresh,List.of()),"batch",summary(db.queryForList("SELECT * FROM import_batches WHERE id=:id",Map.of("id",batchId)).getFirst()));
    }
}
