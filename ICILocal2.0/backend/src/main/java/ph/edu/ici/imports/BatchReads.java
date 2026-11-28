package ph.edu.ici.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.AccessPolicy;

/**
 * The read side every import type shares: load a batch the caller may see, list batches, page rows and exceptions, and the common
 * JSON views. Extracted unchanged from the ICI2-37 and ICI2-38 batch services and parameterized by source type, the write permission
 * and the noun used in refusals, so a new import type adds configuration instead of another copy. Writes (review, commit, void,
 * discard) stay with each type.
 */
final class BatchReads {
    static final List<String> ROW_STATUSES=List.of("valid","invalid","duplicate_flagged","skipped","imported");
    private final NamedParameterJdbcTemplate db;private final ObjectMapper json;private final AccessPolicy access;
    private final String sourceType,writePermission,noun;

    /** @param writePermission the permission that allows changing a batch (reads need imports.read or this); @param noun "imports" or "grade imports" in refusal messages */
    BatchReads(NamedParameterJdbcTemplate db,ObjectMapper json,AccessPolicy access,String sourceType,String writePermission,String noun){
        this.db=db;this.json=json;this.access=access;this.sourceType=sourceType;this.writePermission=writePermission;this.noun=noun;
    }
    ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    long n(Object value){return ((Number)value).longValue();}
    String json(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    JsonNode node(Object value){if(value==null)return null;try{return json.readTree(value.toString());}catch(Exception e){throw new IllegalStateException(e);}}
    void paging(int page,int size){if(page<0||page>1_000_000||size<1||size>100)throw error(HttpStatus.BAD_REQUEST,"Use a nonnegative page and a size of 1 to 100.");}

    /** Loads a batch of this source type the caller may see; for writes the caller must hold the write permission for its campus. Anything else is a 404. */
    Map<String,Object> batch(long id,boolean write,boolean lock){
        var rows=db.queryForList("SELECT * FROM import_batches WHERE id=:id AND source_type=:s"+(lock?" FOR UPDATE":""),Map.of("id",id,"s",sourceType));
        if(rows.isEmpty()||rows.getFirst().get("campus_id")==null)throw error(HttpStatus.NOT_FOUND,"Import batch not found.");
        long campus=n(rows.getFirst().get("campus_id"));
        if(!access.campuses("imports.read").contains(campus)&&!access.campuses(writePermission).contains(campus))throw error(HttpStatus.NOT_FOUND,"Import batch not found.");
        if(write&&!access.campuses(writePermission).contains(campus))throw error(HttpStatus.FORBIDDEN,"Your account cannot change "+noun+" for this campus.");
        return rows.getFirst();
    }

    /** The fields every batch view has. {@code cleaningSummary} lists what cleaning changed (trivial rules are counted only). */
    Map<String,Object> summary(Map<String,Object> b){
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("id",b.get("id"));m.put("sourceName",b.get("source_name"));m.put("status",b.get("status"));m.put("campusId",b.get("campus_id"));m.put("checksum",b.get("checksum"));
        m.put("sourceFormat",b.get("source_format"));
        m.put("fileSizeBytes",b.get("file_size_bytes"));m.put("sourceCount",b.get("source_count"));m.put("validCount",b.get("valid_count"));m.put("invalidCount",b.get("invalid_count"));
        m.put("flaggedCount",b.get("flagged_count"));m.put("skippedCount",b.get("skipped_count"));m.put("importedCount",b.get("imported_count"));
        m.put("version",b.get("version"));m.put("startedAt",b.get("started_at"));m.put("createdBy",b.get("created_by"));m.put("importedBy",b.get("imported_by"));m.put("importedAt",b.get("imported_at"));
        var snapshot=node(b.get("mapping_snapshot"));
        if(snapshot!=null)m.put("mapping",Map.of("id",snapshot.path("mappingId").asLong(),"name",snapshot.path("name").asText(),"version",snapshot.path("mappingVersion").asInt(),"realFileValidated",snapshot.path("realFileValidated").asBoolean()));
        List<Map<String,Object>> cleaning=new ArrayList<>();
        var summary=node(b.get("cleaning_summary"));
        if(summary!=null)summary.fields().forEachRemaining(e->cleaning.add(new LinkedHashMap<>(Map.of("rule",e.getKey(),"count",e.getValue().asLong(),"trivial",CleaningLog.TRIVIAL.contains(e.getKey())))));
        m.put("cleaningSummary",cleaning);
        return m;
    }

    Map<String,Object> list(Long campusId,String status,int page,int size,Function<Map<String,Object>,Map<String,Object>> view){
        paging(page,size);var scope=access.campuses("imports.read");
        if(campusId!=null&&!scope.contains(campusId))throw error(HttpStatus.FORBIDDEN,"Your account cannot view "+noun+" for this campus.");
        if(status!=null&&!List.of("staged","validated","imported","reconciled","failed").contains(status))throw error(HttpStatus.BAD_REQUEST,"Unknown batch status.");
        if(scope.isEmpty())return Map.of("items",List.of(),"total",0,"page",page,"size",size);
        Map<String,Object> p=new HashMap<>();p.put("s",sourceType);p.put("scope",campusId==null?scope:List.of(campusId));p.put("limit",size);p.put("offset",(long)page*size);
        String where=" WHERE source_type=:s AND campus_id IN (:scope)";if(status!=null){where+=" AND status=:status";p.put("status",status);}
        var items=db.queryForList("SELECT * FROM import_batches"+where+" ORDER BY started_at DESC,id DESC LIMIT :limit OFFSET :offset",p).stream().map(view).toList();
        return Map.of("items",items,"total",db.queryForObject("SELECT count(*) FROM import_batches"+where,p,Long.class),"page",page,"size",size);
    }

    Map<String,Object> rows(long id,String status,int page,int size){
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

    /** One staged row. {@code cleaning} lists the non-trivial changes cleaning made (raw value, cleaned value, rule), null when there were none. */
    Map<String,Object> rowView(Map<String,Object> r,List<Map<String,Object>> messages){
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("id",r.get("id"));m.put("rowNumber",r.get("row_number"));m.put("status",r.get("status"));m.put("version",r.get("version"));
        m.put("rawValues",node(r.get("raw_values")));m.put("normalizedValues",node(r.get("normalized_values")));m.put("duplicateEvidence",node(r.get("duplicate_evidence")));
        m.put("cleaning",node(r.get("cleaning")));
        if(r.get("review_decision")==null)m.put("review",null);
        else{Map<String,Object> review=new LinkedHashMap<>();review.put("decision",r.get("review_decision"));review.put("reason",r.get("review_reason"));review.put("reviewedBy",r.get("reviewed_by"));review.put("reviewedAt",r.get("reviewed_at"));review.put("matchedStudentId",r.get("matched_student_id"));m.put("review",review);}
        m.put("studentId",r.get("student_id"));m.put("messages",messages);
        return m;
    }

    Map<String,Object> errors(long id,String severity,Integer rowNumber,int page,int size){
        paging(page,size);batch(id,false,false);
        if(severity!=null&&!List.of("error","warning").contains(severity))throw error(HttpStatus.BAD_REQUEST,"Severity must be error or warning.");
        Map<String,Object> p=new HashMap<>();p.put("id",id);p.put("limit",size);p.put("offset",(long)page*size);String where=" WHERE batch_id=:id";
        if(severity!=null){where+=" AND severity=:severity";p.put("severity",severity);}if(rowNumber!=null){where+=" AND row_number=:rn";p.put("rn",rowNumber);}
        return Map.of("items",db.queryForList("SELECT id,row_number AS \"rowNumber\",import_row_id AS \"rowId\",error_code AS code,field_name AS field,severity,detail,resolved FROM import_exceptions"+where+" ORDER BY row_number NULLS FIRST,id LIMIT :limit OFFSET :offset",p),
            "total",db.queryForObject("SELECT count(*) FROM import_exceptions"+where,p,Long.class),"page",page,"size",size);
    }
}
