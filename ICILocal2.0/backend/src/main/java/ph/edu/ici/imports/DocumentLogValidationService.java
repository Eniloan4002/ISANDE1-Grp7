package ph.edu.ici.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.AccessPolicy;
import ph.edu.ici.imports.ImportFileException.FileError;

/**
 * Stages and validates a document tracking log (ICI2-39 type B, always a dry run): writes import_batches, import_rows and import_exceptions
 * only, never a checklist item. One row means "for this student's application in this term, this document has this state" (layout long).
 * Students, applications, documents and states are matched exactly or through the stored mapping, never by similarity, position or guess;
 * what a row would change is decided by {@link DocumentLogPlanner} from the checklist service's own read. The machinery shared with the
 * section allocation import is in {@link StagingSupport}. Messages never echo names, student numbers, LRNs or note text.
 */
@Service
public class DocumentLogValidationService {
    private static final int MAX_NOTE=2000;
    static final String SOURCE=DocumentLogMappingService.SOURCE;
    private static final ZoneId ZONE=ZoneId.of("Asia/Manila");
    private final NamedParameterJdbcTemplate db;private final AccessPolicy access;
    private final DocumentLogMappingService mappings;private final ImportLimits limits;private final StagingSupport staging;private final DocumentLogPlanner planner;

    /** What the registrar declared at upload. Either may instead (or also) come from a mapped column; if both exist they must agree. */
    public record UploadContext(Long academicYearId,Long termId) {}
    public record Staged(long batchId,String status,int sourceCount,int valid,int invalid,int flagged) {}

    private static final class Row extends StagingSupport.Row {
        String applicationNumber,documentCode,statusLabel,status,note,composedNote;LocalDate date;DocumentLogPlanner.Plan plan;
        Row(CsvParser.Record record,StagingSupport.Grid grid,CellCleaner cleaner){super(record,grid,cleaner);}
    }

    public DocumentLogValidationService(NamedParameterJdbcTemplate db,ObjectMapper json,AccessPolicy access,DocumentLogMappingService mappings,ImportLimits limits,
                                        PlatformTransactionManager transactions,DocumentLogPlanner planner){
        this.db=db;this.access=access;this.mappings=mappings;this.limits=limits;this.planner=planner;
        this.staging=new StagingSupport(db,json,new TransactionTemplate(transactions),SOURCE);
    }

    /**
     * Parse, validate and stage one document log for one campus. The caller supplies the reader ({@link XlsxGridReader} for uploads; the CSV
     * reader stands in for service-level tests). Throws {@link ImportFileException} (413/422) or {@link ImportConflictException} (409).
     */
    public Staged stage(long campusId,UploadContext upload,String fileName,byte[] bytes,GridReader reader,Long mappingId){
        if(!access.campuses("imports.enrollment.manage").contains(campusId)||!access.campuses("enrollment.manage").contains(campusId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Your account cannot import document logs for this campus.");
        String hash=staging.admit(campusId,bytes,limits);
        var ref=GradeReference.load(db.getJdbcTemplate());
        var ctx=upload==null?staging.context(null,null,ref):staging.context(upload.academicYearId(),upload.termId(),ref);
        var stored=mappings.forUpload(mappingId);var mapping=DocumentLogMapping.parse(stored.mapping(),stored.settings());
        var u=new StagingSupport.Upload(campusId,StagingSupport.safeName(fileName),hash,bytes.length,stored.id(),staging.encode(mappings.snapshot(stored)),staging.encode(ctx.json()),reader.format());
        var grid=staging.read(u,()->reader.read(bytes,mapping.csv(),mapping.settings().sheet()),parsed->classify(parsed,mapping));

        Map<String,String> codes=new HashMap<>();   // lower-case code to the catalog's exact code
        for(String code:db.getJdbcTemplate().queryForList("SELECT code FROM requirement_types",String.class))codes.put(code.toLowerCase(Locale.ROOT),code);
        LocalDate today=LocalDate.now(ZONE);
        List<Row> rows=new ArrayList<>();var cleaner=new CellCleaner(mapping.cleaning());
        for(var record:grid.parsed().rows()){
            var row=new Row(record,grid,cleaner);rows.add(row);
            if(!row.widthMatches())continue;
            identity(row);
            StagingSupport.term(row,mapping.valueMaps(),ctx,ref);
            if(row.termId==null&&row.applicationNumber==null&&row.eligible())row.error("REQUIRED_MISSING","term","The row has no term and no application number, and no term was chosen at upload.");
            document(row,mapping,codes,today);
        }
        staging.resolveStudents(rows,campusId,false);
        plan(rows,campusId);
        for(Row r:rows)r.outcome=!r.eligible()?"invalid":(!r.evidence.isEmpty()||(r.plan!=null&&r.plan.conflict()!=null))?"flagged":"valid";
        var saved=staging.save(u,rows,DocumentLogValidationService::normalized,DocumentLogValidationService::conflictEvidence);
        return new Staged(saved.batchId(),"validated",rows.size(),saved.valid(),saved.invalid(),saved.flagged());
    }

    /** Every non-blank header must be a field alias or an ignored column; a wide layout (one column per document) therefore fails as UNMAPPED_COLUMN. */
    private Map<String,Integer> classify(CsvParser.Parsed parsed,DocumentLogMapping mapping){
        var fields=staging.classify(parsed,DocumentLogMapping.FIELDS,mapping.fields(),mapping.settings().ignoredColumns(),
            "Use one list with a header row at the top.","A layout with one column per document is not supported.");
        List<String> missing=new ArrayList<>();
        if(!fields.containsKey("studentNumber")&&!fields.containsKey("lrn")&&!fields.containsKey("applicationNumber"))missing.add("studentNumber, lrn or applicationNumber");
        if(!fields.containsKey("document"))missing.add("document");
        if(!fields.containsKey("status"))missing.add("status");
        if(!missing.isEmpty())throw new ImportFileException(FileError.MISSING_COLUMN,"Required columns not found: "+missing+". Check the file's header row and the mapping's aliases.");
        return fields;
    }

    // ---- row parsing ---------------------------------------------------------------------------------------------------

    /** The shared identity columns (the free-text note may contain line breaks), then the application number. */
    private static void identity(Row r){
        StagingSupport.identity(r,Set.of("note"));
        r.applicationNumber=r.cell("applicationNumber");
        if(r.applicationNumber!=null&&r.applicationNumber.length()>40){r.error("TOO_LONG","applicationNumber","The application number is longer than 40 characters.");r.applicationNumber=null;}
        if(r.studentNumber==null&&r.lrn==null&&r.applicationNumber==null&&r.eligible())r.error("REQUIRED_MISSING","studentNumber","The row has no student number, LRN or application number.");
    }

    /** Document, state, date and note; then the note the import would store, so staging and commit compare exactly what would be written. */
    private static void document(Row r,DocumentLogMapping mapping,Map<String,String> codes,LocalDate today){
        String label=r.cell("document");
        if(label==null)r.error("REQUIRED_MISSING","document","The row has no document.");
        else{
            String mapped=mapping.valueMaps().get("document").get(ImportText.value(label));
            String code=mapped!=null?(codes.containsValue(mapped)?mapped:null):codes.get(label.toLowerCase(Locale.ROOT));
            if(code==null)r.error("UNMAPPED_VALUE","document","The document is not a requirement code and is not in the mapping's document value map. Requirement types are never created by the import.");
            else r.documentCode=code;
        }
        r.statusLabel=r.cell("status");
        if(r.statusLabel!=null){
            String key=ImportText.value(r.statusLabel);String mapped=mapping.valueMaps().get("status").get(key);
            String state=mapped!=null?mapped:DocumentLogMapping.STATES.contains(key)?key:null;
            if(state==null)r.error("UNMAPPED_VALUE","status","The state is not one of "+DocumentLogMapping.STATES+" and is not in the mapping's status value map. States are never guessed.");
            else r.status=state;
        }
        String dateCell=r.cell("date");
        if(dateCell!=null){
            var m=r.meta("date");LocalDate date=null;
            if(m!=null&&m.kind()==CellMeta.Kind.DATE){try{date=LocalDate.parse(dateCell);}catch(RuntimeException e){r.error("INVALID_DATE","date","The date cell could not be read.");}}
            else{
                var found=mapping.dates(dateCell);
                if(found.size()>1)r.error("AMBIGUOUS_DATE","date","The date can be read in more than one configured format.");
                else if(found.isEmpty())r.error("INVALID_DATE","date","The value does not match an accepted format. Accepted formats: "+String.join(", ",mapping.settings().dateFormats())+".");
                else date=found.iterator().next();
            }
            if(date!=null&&date.getYear()<1900){r.error("INVALID_DATE","date","Dates before 1900 are not accepted.");date=null;}
            if(date!=null&&date.isAfter(today)){r.error("FUTURE_DATE","date","The log date is in the future.");date=null;}
            r.date=date;
        }
        r.note=r.cell("note");
        if(r.status!=null){
            String composed="Legacy document log: status \""+r.statusLabel+"\""+(r.date!=null?", dated "+r.date:"")+"."+(r.note!=null?" "+r.note:"");
            if(composed.length()>MAX_NOTE)r.error("TOO_LONG","note","The legacy note is too long to store: the stored note may have at most "+MAX_NOTE+" characters. It is never truncated.");
            else r.composedNote=composed;
        }
    }

    // ---- plans ---------------------------------------------------------------------------------------------------------

    /** Rows with a resolved student, or naming only an application number, are planned now; identities awaiting review are planned at commit. */
    private void plan(List<Row> rows,long campusId){
        Map<String,Integer> seen=new HashMap<>();
        for(Row r:rows){
            if(!r.eligible()||r.documentCode==null)continue;
            boolean identified=r.student!=null||(r.studentNumber==null&&r.lrn==null&&r.applicationNumber!=null);
            if(!identified)continue;
            r.plan=planner.plan(campusId,r.termId,r.student==null?null:r.student.id(),r.applicationNumber,r.documentCode,r.status,r.composedNote);
            for(var p:r.plan.problems())r.add(p);
            if(!r.plan.ok())continue;
            String key=r.plan.applicationId()+"|"+r.documentCode;Integer clash=seen.get(key);
            if(clash!=null){r.error("DUPLICATE_IN_BATCH","document","This application's document already appears on row "+clash+". A log with several entries for one document is not replayed.");r.plan=null;continue;}
            seen.put(key,r.number);
        }
    }

    // ---- persistence ---------------------------------------------------------------------------------------------------

    private static Map<String,Object> normalized(Row r){
        Map<String,Object> v=new LinkedHashMap<>();
        v.put("studentId",r.student!=null?r.student.id():r.plan!=null?r.plan.studentId():null);v.put("matchedBy",r.matchedBy);
        v.put("studentNumber",r.studentNumber);v.put("lrn",r.lrn);v.put("applicationNumber",r.applicationNumber);
        v.put("academicYearId",r.yearId);v.put("termId",r.termId);
        v.put("code",r.documentCode);v.put("status",r.status);v.put("date",r.date==null?null:r.date.toString());v.put("note",r.composedNote);
        if(r.plan!=null){
            v.put("action",r.plan.action());v.put("applicationId",r.plan.applicationId());v.put("itemVersion",r.plan.itemVersion());
            v.put("currentStatus",r.plan.recorded()?r.plan.currentStatus():null);v.put("conflict",r.plan.conflict());v.put("regression",r.plan.regression());
        }
        return v;
    }
    private static List<Map<String,Object>> conflictEvidence(Row r){
        if(r.plan==null||r.plan.conflict()==null)return List.of();
        Map<String,Object> e=new LinkedHashMap<>();e.put("kind",r.plan.conflict());e.put("applicationId",r.plan.applicationId());e.put("code",r.documentCode);
        e.put("currentStatus",r.plan.currentStatus());e.put("importedStatus",r.status);e.put("regression",r.plan.regression());return List.of(e);
    }
}
