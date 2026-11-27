package ph.edu.ici.imports;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.AccessPolicy;
import ph.edu.ici.auth.StaffIdentity;

/**
 * ICI2-40 cutover reconciliation. Compares what each legacy upload said it contained with what was actually imported and lets an
 * authorised registrar sign off a batch (status `reconciled`) once every source row is accounted for. Reads only counts and ids;
 * no learner names or other personal values.
 *
 * A batch can be signed off only when it is committed and every source row was imported: the V1 constraint on `import_batches`
 * requires source_count = imported_count for `reconciled`. Batches with invalid, flagged or skipped rows therefore show those rows as
 * discrepancies and cannot be signed off until they are corrected and re-uploaded as a new batch.
 */
@Service
public class ReconciliationService {
    private final NamedParameterJdbcTemplate db;private final AccessPolicy access;
    public ReconciliationService(NamedParameterJdbcTemplate db,AccessPolicy access){this.db=db;this.access=access;}

    private ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    private long n(Object value){return value==null?0:((Number)value).longValue();}
    private StaffIdentity identity(){return (StaffIdentity)SecurityContextHolder.getContext().getAuthentication().getPrincipal();}

    /** Student and future sources use imports.manage; legacy grades use their own, stricter permission. */
    static String writePermission(String sourceType){return "legacy_grade_matrix_csv".equals(sourceType)?"imports.grades.manage":"imports.manage";}

    private static final String BATCH_SELECT="""
        SELECT b.id,b.source_type,b.source_name,b.campus_id,c.name AS campus,b.status,b.version,b.source_count,b.valid_count,b.invalid_count,
               b.flagged_count,b.skipped_count,b.imported_count,b.imported_at,b.signed_off_by,b.signed_off_at,
               (SELECT count(*) FROM import_rows r WHERE r.batch_id=b.id) AS rows_recorded,
               (SELECT count(*) FROM import_rows r WHERE r.batch_id=b.id AND r.status='imported') AS rows_imported,
               (SELECT count(*) FROM import_rows r WHERE r.batch_id=b.id AND r.status='imported' AND r.student_id IS NULL) AS imported_without_student,
               (SELECT count(*) FROM legacy_grade_records g JOIN import_rows r ON r.id=g.import_row_id WHERE r.batch_id=b.id AND g.voided_at IS NULL) AS live_grade_records,
               (SELECT count(*) FROM legacy_grade_records g JOIN import_rows r ON r.id=g.import_row_id WHERE r.batch_id=b.id AND g.voided_at IS NOT NULL) AS voided_grade_records
        FROM import_batches b JOIN campuses c ON c.id=b.campus_id
        """;

    /** Everything that stops a batch from being signed off, in plain language. Empty means every source row is accounted for. */
    List<Map<String,Object>> discrepancies(Map<String,Object> b){
        var list=new ArrayList<Map<String,Object>>();
        String status=(String)b.get("status");
        long source=n(b.get("source_count")),imported=n(b.get("imported_count"));
        if("staged".equals(status)||"validated".equals(status)){
            list.add(Map.of("code","NOT_COMMITTED","detail","The batch has been uploaded but not committed, so none of its "+source+" source rows are in the system yet."));
            return list;
        }
        if(n(b.get("rows_recorded"))!=source)
            list.add(Map.of("code","ROW_COUNT_MISMATCH","detail","The batch records "+n(b.get("rows_recorded"))+" rows but its source count is "+source+"."));
        if(n(b.get("rows_imported"))!=imported)
            list.add(Map.of("code","IMPORTED_COUNT_MISMATCH","detail","The batch says "+imported+" rows were imported but "+n(b.get("rows_imported"))+" rows are marked imported."));
        if(source!=imported){
            long valid=n(b.get("valid_count"));
            list.add(Map.of("code","UNIMPORTED_ROWS","detail",(source-imported)+" of "+source+" source rows were not imported (invalid "+n(b.get("invalid_count"))
                    +", flagged as possible duplicates "+n(b.get("flagged_count"))+", skipped "+n(b.get("skipped_count"))+", valid but not imported "+valid+"). Correct them and upload a new batch."));
        }
        if(n(b.get("imported_without_student"))>0)
            list.add(Map.of("code","IMPORTED_WITHOUT_STUDENT","detail",n(b.get("imported_without_student"))+" imported rows are not linked to a student record."));
        if(n(b.get("voided_grade_records"))>0)
            list.add(Map.of("code","VOIDED_GRADE_RECORDS","detail",n(b.get("voided_grade_records"))+" grade records from this batch were voided after the import."));
        return list;
    }

    private Map<String,Object> view(Map<String,Object> b){
        var m=new LinkedHashMap<String,Object>();
        m.put("id",b.get("id"));m.put("sourceType",b.get("source_type"));m.put("sourceName",b.get("source_name"));m.put("campusId",b.get("campus_id"));m.put("campus",b.get("campus"));
        m.put("status",b.get("status"));m.put("version",b.get("version"));
        m.put("sourceCount",b.get("source_count"));m.put("importedCount",b.get("imported_count"));m.put("validCount",b.get("valid_count"));m.put("invalidCount",b.get("invalid_count"));
        m.put("flaggedCount",b.get("flagged_count"));m.put("skippedCount",b.get("skipped_count"));
        m.put("unimportedCount",n(b.get("source_count"))-n(b.get("imported_count")));
        if("legacy_grade_matrix_csv".equals(b.get("source_type"))){m.put("liveGradeRecords",b.get("live_grade_records"));m.put("voidedGradeRecords",b.get("voided_grade_records"));}
        m.put("importedAt",b.get("imported_at"));m.put("signedOffBy",b.get("signed_off_by"));m.put("signedOffAt",b.get("signed_off_at"));
        var problems=discrepancies(b);
        m.put("discrepancies",problems);
        m.put("canSignOff","imported".equals(b.get("status"))&&problems.isEmpty());
        return m;
    }

    /** Live batches only: discarded, failed and fully voided uploads were intentionally removed and are counted separately. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> summary(Long campusId){
        var scope=access.campuses("imports.read");
        if(campusId!=null&&!scope.contains(campusId))throw error(HttpStatus.FORBIDDEN,"Your account cannot view imports for this campus.");
        var result=new LinkedHashMap<String,Object>();
        if(scope.isEmpty()){result.put("batches",List.of());result.put("totals",Map.of());result.put("excludedBatches",0);result.put("readyToDecommission",false);return result;}
        var params=new HashMap<String,Object>();params.put("scope",campusId==null?scope:List.of(campusId));
        var live=db.queryForList(BATCH_SELECT+" WHERE b.campus_id IN (:scope) AND b.status<>'failed' AND b.voided_at IS NULL ORDER BY b.started_at,b.id",params).stream().map(this::view).toList();
        long excluded=db.queryForObject("SELECT count(*) FROM import_batches WHERE campus_id IN (:scope) AND (status='failed' OR voided_at IS NOT NULL)",params,Long.class);

        long source=0,imported=0,reconciled=0,pending=0,blocked=0;
        var byStatus=new TreeMap<String,Long>();
        for(var b:live){
            source+=n(b.get("sourceCount"));imported+=n(b.get("importedCount"));byStatus.merge((String)b.get("status"),1L,Long::sum);
            if("reconciled".equals(b.get("status")))reconciled++;
            else if(Boolean.TRUE.equals(b.get("canSignOff")))pending++;
            else blocked++;
        }
        var totals=new LinkedHashMap<String,Object>();
        totals.put("batches",live.size());totals.put("byStatus",byStatus);totals.put("sourceRows",source);totals.put("importedRows",imported);totals.put("unimportedRows",source-imported);
        totals.put("reconciledBatches",reconciled);totals.put("readyForSignOff",pending);totals.put("blockedBatches",blocked);
        result.put("batches",live);result.put("totals",totals);result.put("excludedBatches",excluded);
        // The old system can be retired only when something was imported and every live batch is signed off.
        result.put("readyToDecommission",!live.isEmpty()&&reconciled==live.size());
        return result;
    }

    @Transactional
    public Map<String,Object> signOff(long batchId,int version,int expectedImportedCount,String note){
        var rows=db.queryForList(BATCH_SELECT+" WHERE b.id=:id FOR UPDATE OF b",Map.of("id",batchId));
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Import batch not found.");
        var batch=rows.getFirst();
        long campus=n(batch.get("campus_id"));String permission=writePermission((String)batch.get("source_type"));
        if(!access.campuses("imports.read").contains(campus)&&!access.campuses(permission).contains(campus))throw error(HttpStatus.NOT_FOUND,"Import batch not found.");
        if(!access.campuses(permission).contains(campus))throw error(HttpStatus.FORBIDDEN,"Your account cannot sign off imports for this campus.");
        if(note!=null&&note.trim().length()>1000)throw error(HttpStatus.BAD_REQUEST,"The note can be at most 1000 characters.");
        long actor=identity().userId();
        db.queryForObject("SELECT set_config('app.actor_id',:actor,true)",Map.of("actor",Long.toString(actor)),String.class);

        String status=(String)batch.get("status");
        if("reconciled".equals(status))return result(batchId,true);
        if(!"imported".equals(status))
            throw error(HttpStatus.CONFLICT,"Only a committed batch can be signed off.");
        if(n(batch.get("version"))!=version)throw error(HttpStatus.CONFLICT,"The batch changed since you loaded it. Reload before signing off.");
        if(n(batch.get("imported_count"))!=expectedImportedCount)throw error(HttpStatus.CONFLICT,"The imported count is "+n(batch.get("imported_count"))+", not "+expectedImportedCount+". Reload and check the totals before signing off.");
        var problems=discrepancies(batch);
        if(!problems.isEmpty())
            throw error(HttpStatus.CONFLICT,"This batch cannot be signed off until every source row is accounted for: "+problems.stream().map(p->(String)p.get("code")).toList());

        db.update("UPDATE import_batches SET status='reconciled',signed_off_by=:actor,signed_off_at=current_timestamp,version=version+1 WHERE id=:id",Map.of("id",batchId,"actor",actor));
        var audit=new HashMap<String,Object>();
        audit.put("id",batchId);audit.put("type",batch.get("source_type"));audit.put("campus",campus);audit.put("source",n(batch.get("source_count")));
        audit.put("imported",n(batch.get("imported_count")));audit.put("actor",actor);audit.put("note",note==null||note.isBlank()?null:note.trim());
        db.update("INSERT INTO audit_logs(table_name,record_id,operation,new_data,actor_id) VALUES ('import_batches',jsonb_build_object('id',cast(:id AS bigint))::text,'SIGNOFF',"+
                "jsonb_build_object('status','reconciled','sourceType',cast(:type AS text),'campusId',cast(:campus AS bigint),'sourceCount',cast(:source AS integer),'importedCount',cast(:imported AS integer),'note',cast(:note AS text)),:actor)",audit);
        return result(batchId,false);
    }

    private Map<String,Object> result(long batchId,boolean already){
        var b=db.queryForList("SELECT status,version,signed_off_by,signed_off_at FROM import_batches WHERE id=:id",Map.of("id",batchId)).getFirst();
        var m=new LinkedHashMap<String,Object>();m.put("batchId",batchId);m.put("status",b.get("status"));m.put("version",b.get("version"));
        m.put("signedOffBy",b.get("signed_off_by"));m.put("signedOffAt",b.get("signed_off_at"));m.put("alreadySignedOff",already);return m;
    }
}
