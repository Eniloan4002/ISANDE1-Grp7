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
    static final List<String> ROW_STATUSES=List.of("valid","invalid","duplicate_flagged","skipped","imported");
    private final NamedParameterJdbcTemplate db;private final ObjectMapper json;private final AccessPolicy access;

    public ImportBatchService(NamedParameterJdbcTemplate db,ObjectMapper json,AccessPolicy access){this.db=db;this.json=json;this.access=access;}
    StaffIdentity identity(){return (StaffIdentity)SecurityContextHolder.getContext().getAuthentication().getPrincipal();}
    ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    void actor(){db.queryForObject("SELECT set_config('app.actor_id',:actor,true)",Map.of("actor",Long.toString(identity().userId())),String.class);}
    long n(Object value){return ((Number)value).longValue();}
    String json(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    JsonNode node(Object value){if(value==null)return null;try{return json.readTree(value.toString());}catch(Exception e){throw new IllegalStateException(e);}}
    private void paging(int page,int size){if(page<0||page>1_000_000||size<1||size>100)throw error(HttpStatus.BAD_REQUEST,"Use a nonnegative page and a size of 1 to 100.");}

    /** Loads a batch the caller may see; for writes the caller must hold imports.manage for its campus. */
    Map<String,Object> batch(long id,boolean write,boolean lock){
        var rows=db.queryForList("SELECT * FROM import_batches WHERE id=:id AND source_type=:s"+(lock?" FOR UPDATE":""),Map.of("id",id,"s",ImportMappingService.SOURCE));
        if(rows.isEmpty()||rows.getFirst().get("campus_id")==null)throw error(HttpStatus.NOT_FOUND,"Import batch not found.");
        long campus=n(rows.getFirst().get("campus_id"));
        if(!access.campuses("imports.read").contains(campus)&&!access.campuses("imports.manage").contains(campus))throw error(HttpStatus.NOT_FOUND,"Import batch not found.");
        if(write&&!access.campuses("imports.manage").contains(campus))throw error(HttpStatus.FORBIDDEN,"Your account cannot change imports for this campus.");
        return rows.getFirst();
    }
    Map<String,Object> summary(Map<String,Object> b){
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("id",b.get("id"));m.put("sourceName",b.get("source_name"));m.put("status",b.get("status"));m.put("campusId",b.get("campus_id"));m.put("checksum",b.get("checksum"));
        m.put("fileSizeBytes",b.get("file_size_bytes"));m.put("sourceCount",b.get("source_count"));m.put("validCount",b.get("valid_count"));m.put("invalidCount",b.get("invalid_count"));
        m.put("flaggedCount",b.get("flagged_count"));m.put("skippedCount",b.get("skipped_count"));m.put("importedCount",b.get("imported_count"));
        m.put("version",b.get("version"));m.put("startedAt",b.get("started_at"));m.put("createdBy",b.get("created_by"));m.put("importedBy",b.get("imported_by"));m.put("importedAt",b.get("imported_at"));
        var discard=discardRecord(n(b.get("id")),(String)b.get("status"));
        m.put("discarded",discard!=null);
        if(discard!=null){m.put("discardedAt",discard.get("created_at"));m.put("discardedBy",discard.get("created_by"));m.put("discardReason",discard.get("detail"));}
        var snapshot=node(b.get("mapping_snapshot"));
        if(snapshot!=null)m.put("mapping",Map.of("id",snapshot.path("mappingId").asLong(),"name",snapshot.path("name").asText(),"version",snapshot.path("mappingVersion").asInt(),"realFileValidated",snapshot.path("realFileValidated").asBoolean()));
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

    @Transactional(readOnly=true) public Map<String,Object> list(Long campusId,String status,int page,int size){
        paging(page,size);var scope=access.campuses("imports.read");
        if(campusId!=null&&!scope.contains(campusId))throw error(HttpStatus.FORBIDDEN,"Your account cannot view imports for this campus.");
        if(status!=null&&!List.of("staged","validated","imported","reconciled","failed").contains(status))throw error(HttpStatus.BAD_REQUEST,"Unknown batch status.");
        if(scope.isEmpty())return Map.of("items",List.of(),"total",0,"page",page,"size",size);
        Map<String,Object> p=new HashMap<>();p.put("s",ImportMappingService.SOURCE);p.put("scope",campusId==null?scope:List.of(campusId));p.put("limit",size);p.put("offset",(long)page*size);
        String where=" WHERE source_type=:s AND campus_id IN (:scope)";if(status!=null){where+=" AND status=:status";p.put("status",status);}
        var items=db.queryForList("SELECT * FROM import_batches"+where+" ORDER BY started_at DESC,id DESC LIMIT :limit OFFSET :offset",p).stream().map(this::summary).toList();
        return Map.of("items",items,"total",db.queryForObject("SELECT count(*) FROM import_batches"+where,p,Long.class),"page",page,"size",size);
    }
    @Transactional(readOnly=true) public Map<String,Object> detail(long id){
        var b=batch(id,false,false);var m=summary(b);
        m.put("batchExceptions",db.queryForList("SELECT error_code AS code,detail,severity FROM import_exceptions WHERE batch_id=:id AND row_number IS NULL ORDER BY id",Map.of("id",id)));
        m.put("errorSummary",db.queryForList("SELECT error_code AS code,severity,count(*) AS count FROM import_exceptions WHERE batch_id=:id AND row_number IS NOT NULL GROUP BY error_code,severity ORDER BY severity,error_code",Map.of("id",id)));
        return m;
    }
    @Transactional(readOnly=true) public Map<String,Object> rows(long id,String status,int page,int size){
        paging(page,size);batch(id,false,false);
        if(status!=null&&!ROW_STATUSES.contains(status))throw error(HttpStatus.BAD_REQUEST,"Unknown row status.");
        Map<String,Object> p=new HashMap<>();p.put("id",id);p.put("limit",size);p.put("offset",(long)page*size);String where=" WHERE batch_id=:id";if(status!=null){where+=" AND status=:status";p.put("status",status);}
        var rows=db.queryForList("SELECT * FROM import_rows"+where+" ORDER BY row_number LIMIT :limit OFFSET :offset",p);
        Map<Long,List<Map<String,Object>>> messages=new HashMap<>();
        if(!rows.isEmpty())for(var e:db.queryForList("SELECT import_row_id,error_code AS code,field_name AS field,severity,detail FROM import_exceptions WHERE import_row_id IN (:ids) ORDER BY id",Map.of("ids",rows.stream().map(r->r.get("id")).toList()))){
            long rowId=n(e.remove("import_row_id"));messages.computeIfAbsent(rowId,k->new ArrayList<>()).add(e);
        }
        var items=rows.stream().map(r->rowView(r,messages.getOrDefault(n(r.get("id")),List.of()))).toList();
        return Map.of("items",items,"total",db.queryForObject("SELECT count(*) FROM import_rows"+where,p,Long.class),"page",page,"size",size);
    }
    Map<String,Object> rowView(Map<String,Object> r,List<Map<String,Object>> messages){
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("id",r.get("id"));m.put("rowNumber",r.get("row_number"));m.put("status",r.get("status"));m.put("version",r.get("version"));
        m.put("rawValues",node(r.get("raw_values")));m.put("normalizedValues",node(r.get("normalized_values")));m.put("duplicateEvidence",node(r.get("duplicate_evidence")));
        if(r.get("review_decision")==null)m.put("review",null);
        else{Map<String,Object> review=new LinkedHashMap<>();review.put("decision",r.get("review_decision"));review.put("reason",r.get("review_reason"));review.put("reviewedBy",r.get("reviewed_by"));review.put("reviewedAt",r.get("reviewed_at"));review.put("matchedStudentId",r.get("matched_student_id"));m.put("review",review);}
        m.put("studentId",r.get("student_id"));m.put("messages",messages);
        return m;
    }
    @Transactional(readOnly=true) public Map<String,Object> errors(long id,String severity,Integer rowNumber,int page,int size){
        paging(page,size);batch(id,false,false);
        if(severity!=null&&!List.of("error","warning").contains(severity))throw error(HttpStatus.BAD_REQUEST,"Severity must be error or warning.");
        Map<String,Object> p=new HashMap<>();p.put("id",id);p.put("limit",size);p.put("offset",(long)page*size);String where=" WHERE batch_id=:id";
        if(severity!=null){where+=" AND severity=:severity";p.put("severity",severity);}if(rowNumber!=null){where+=" AND row_number=:rn";p.put("rn",rowNumber);}
        return Map.of("items",db.queryForList("SELECT id,row_number AS \"rowNumber\",import_row_id AS \"rowId\",error_code AS code,field_name AS field,severity,detail,resolved FROM import_exceptions"+where+" ORDER BY row_number NULLS FIRST,id LIMIT :limit OFFSET :offset",p),
            "total",db.queryForObject("SELECT count(*) FROM import_exceptions"+where,p,Long.class),"page",page,"size",size);
    }

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
