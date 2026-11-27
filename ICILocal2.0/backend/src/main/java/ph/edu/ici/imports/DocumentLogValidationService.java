package ph.edu.ici.imports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.AccessPolicy;
import ph.edu.ici.auth.StaffIdentity;
import ph.edu.ici.imports.ImportFileException.FileError;
import ph.edu.ici.imports.ImportFileException.Problem;

/**
 * Stages and validates a document tracking log (ICI2-39 type B, always a dry run): writes import_batches, import_rows and import_exceptions
 * only, never a checklist item. One row means "for this student's application in this term, this document has this state" (layout long).
 * Students, applications, documents and states are matched exactly or through the stored mapping, never by similarity, position or guess;
 * what a row would change is decided by {@link DocumentLogPlanner} from the checklist service's own read. Messages never echo names,
 * student numbers, LRNs or note text.
 */
@Service
public class DocumentLogValidationService {
    private static final int MAX_PROBLEMS_PER_ROW=50,MAX_NOTE=2000;
    static final String SOURCE=DocumentLogMappingService.SOURCE;
    private static final ZoneId ZONE=ZoneId.of("Asia/Manila");
    private final NamedParameterJdbcTemplate db;private final ObjectMapper json;private final AccessPolicy access;
    private final DocumentLogMappingService mappings;private final ImportLimits limits;private final TransactionTemplate tx;
    private final StudentResolver resolver;private final DocumentLogPlanner planner;

    /** What the registrar declared at upload. Either may instead (or also) come from a mapped column; if both exist they must agree. */
    public record UploadContext(Long academicYearId,Long termId) {}
    public record Staged(long batchId,String status,int sourceCount,int valid,int invalid,int flagged) {}

    private static final class Row {
        final int number;final Map<String,String> raw;final List<RowProblem> problems=new ArrayList<>();
        final CleaningLog cleaning=new CleaningLog();CellCleaner cleaner;List<String> headers=List.of();Map<Integer,CellMeta> meta=Map.of();int flushed;
        String studentNumber,lrn,applicationNumber,lastName,firstName,documentCode,statusLabel,status,note,composedNote;LocalDate date;Long termId,yearId;
        StudentResolver.Student student;String matchedBy;final List<Map<String,Object>> evidence=new ArrayList<>();
        DocumentLogPlanner.Plan plan;String rowStatus;
        Row(int number,Map<String,String> raw){this.number=number;this.raw=raw;}
        int errors,warnings;
        boolean eligible(){return errors==0;}
        void flush(){for(;flushed<cleaning.problems().size();flushed++){var p=cleaning.problems().get(flushed);if(RowProblem.ERROR.equals(p.severity()))error(p.code(),p.field(),p.detail());else warn(p.code(),p.field(),p.detail());}}
        void error(String code,String field,String detail){errors++;add(RowProblem.error(code,field,detail),errors);}
        void warn(String code,String field,String detail){warnings++;add(RowProblem.warning(code,field,detail),warnings);}
        private void add(RowProblem p,int count){if(count<=MAX_PROBLEMS_PER_ROW)problems.add(p);else if(count==MAX_PROBLEMS_PER_ROW+1)problems.add(RowProblem.warning("MORE_PROBLEMS",null,"More "+p.severity()+"s were found on this row than are listed."));}
        void add(RowProblem p){if(RowProblem.ERROR.equals(p.severity()))error(p.code(),p.field(),p.detail());else warn(p.code(),p.field(),p.detail());}
    }

    public DocumentLogValidationService(NamedParameterJdbcTemplate db,ObjectMapper json,AccessPolicy access,DocumentLogMappingService mappings,ImportLimits limits,
                                        PlatformTransactionManager transactions,DocumentLogPlanner planner){
        this.db=db;this.json=json;this.access=access;this.mappings=mappings;this.limits=limits;this.tx=new TransactionTemplate(transactions);this.resolver=new StudentResolver(db);this.planner=planner;
    }
    private StaffIdentity identity(){return (StaffIdentity)SecurityContextHolder.getContext().getAuthentication().getPrincipal();}
    private void actor(){db.queryForObject("SELECT set_config('app.actor_id',:actor,true)",Map.of("actor",Long.toString(identity().userId())),String.class);}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private static String checksum(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String safeName(String name){String s=name==null?"":name.replaceAll("^.*[\\\\/]","").replaceAll("\\p{Cc}","").trim();if(s.isEmpty())s="upload";return s.length()>255?s.substring(0,255):s;}
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}

    /**
     * Parse, validate and stage one document log for one campus. The caller supplies the reader ({@link XlsxGridReader} for uploads; the CSV
     * reader stands in for service-level tests). Throws {@link ImportFileException} (413/422) or {@link ImportConflictException} (409).
     */
    public Staged stage(long campusId,UploadContext upload,String fileName,byte[] bytes,GridReader reader,Long mappingId){
        if(!access.campuses("imports.enrollment.manage").contains(campusId)||!access.campuses("enrollment.manage").contains(campusId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Your account cannot import document logs for this campus.");
        if(bytes.length>limits.maxFileBytes())throw new ImportFileException(FileError.FILE_TOO_LARGE,"The file is larger than "+limits.maxFileBytes()+" bytes.");
        if(Boolean.FALSE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM campuses WHERE id=:id)",Map.of("id",campusId),Boolean.class)))throw bad("Select an existing campus.");
        String hash=checksum(bytes);
        var existing=liveBatch(hash);
        if(existing!=null)throw new ImportConflictException(((Number)existing.get("id")).longValue(),(String)existing.get("status"));
        var ref=GradeReference.load(db.getJdbcTemplate());
        var ctx=context(upload,ref);
        var stored=mappings.forUpload(mappingId);var mapping=DocumentLogMapping.parse(stored.mapping(),stored.settings());var snapshot=encode(mappings.snapshot(stored));
        String contextJson=encode(contextMap(ctx));String name=safeName(fileName);

        CsvParser.Parsed parsed;Map<String,Integer> fields;
        try{
            parsed=reader.read(bytes,mapping.csv(),mapping.settings().sheet());
            fields=classify(parsed,mapping);
        }catch(ImportFileException e){
            if(e.error().recorded)e.batchId(recordFailure(campusId,name,hash,bytes.length,stored.id(),snapshot,contextJson,reader.format(),e));
            throw e;
        }

        Map<String,String> codes=new HashMap<>();   // lower-case code to the catalog's exact code
        for(String code:db.getJdbcTemplate().queryForList("SELECT code FROM requirement_types",String.class))codes.put(code.toLowerCase(Locale.ROOT),code);
        LocalDate today=LocalDate.now(ZONE);
        List<Row> rows=new ArrayList<>();var cleaner=new CellCleaner(mapping.cleaning());
        for(var record:parsed.rows()){
            Map<String,String> raw=new LinkedHashMap<>();for(int i=0;i<record.cells().size()&&i<parsed.headers().size();i++)raw.put(parsed.headers().get(i),record.cells().get(i));
            var row=new Row(record.number(),raw);rows.add(row);row.cleaner=cleaner;row.headers=parsed.headers();row.meta=record.meta();
            if(record.cells().size()!=parsed.headers().size()){row.error("COLUMN_COUNT_MISMATCH",null,"The row has "+record.cells().size()+" values but the header has "+parsed.headers().size()+".");continue;}
            identity(row,record.cells(),fields);context(row,record.cells(),fields,mapping,ctx,ref);document(row,record.cells(),fields,mapping,codes,today);
        }
        resolveStudents(rows,campusId);
        plan(rows,campusId);
        for(Row r:rows)r.rowStatus=!r.eligible()?"invalid":(!r.evidence.isEmpty()||(r.plan!=null&&r.plan.conflict()!=null))?"flagged":"valid";
        try{return tx.execute(s->persist(campusId,name,hash,bytes.length,stored.id(),snapshot,contextJson,reader.format(),rows));}
        catch(DuplicateKeyException e){
            var raced=liveBatch(hash);if(raced==null)throw e;
            throw new ImportConflictException(((Number)raced.get("id")).longValue(),(String)raced.get("status"));
        }
    }

    private Map<String,Object> liveBatch(String hash){
        var rows=db.queryForList("SELECT id,status FROM import_batches WHERE source_type=:s AND checksum=:c AND status<>'failed' AND voided_at IS NULL",Map.of("s",SOURCE,"c",hash));
        return rows.isEmpty()?null:rows.getFirst();
    }

    // ---- upload context ------------------------------------------------------------------------------------------------

    private UploadContext context(UploadContext in,GradeReference ref){
        if(in==null)in=new UploadContext(null,null);
        Long year=in.academicYearId(),term=in.termId();
        if(year!=null&&!ref.yearsById().containsKey(year))throw bad("Select an existing academic year.");
        if(term!=null){
            var t=ref.termsById().get(term);if(t==null)throw bad("Select an existing term.");
            if(year!=null&&year!=t.yearId())throw bad("The selected term does not belong to the selected academic year.");
            year=t.yearId();
        }
        return new UploadContext(year,term);
    }
    private static Map<String,Object> contextMap(UploadContext c){
        Map<String,Object> m=new LinkedHashMap<>();
        if(c.academicYearId()!=null)m.put("academicYearId",c.academicYearId());if(c.termId()!=null)m.put("termId",c.termId());return m;
    }

    // ---- file-level classification -------------------------------------------------------------------------------------

    /** Every non-blank header must be a field alias or an ignored column; a wide layout (one column per document) therefore fails as UNMAPPED_COLUMN. */
    private Map<String,Integer> classify(CsvParser.Parsed parsed,DocumentLogMapping mapping){
        int width=parsed.headers().size();
        List<String> headerKeys=parsed.headers().stream().map(ImportText::header).toList();
        if(headerKeys.stream().filter(k->!k.isEmpty()).count()>=2)
            for(var r:parsed.rows())if(r.cells().size()==width&&r.cells().stream().map(ImportText::header).toList().equals(headerKeys))
                throw new ImportFileException(FileError.REPEATED_HEADER,"Row "+r.number()+" repeats the header row. Use one list with a header row at the top.");
        Map<String,List<String>> owners=new HashMap<>();
        for(String field:DocumentLogMapping.FIELDS){
            var spec=mapping.fields().get(field);if(spec==null)continue;
            for(String alias:spec.aliases()){String k=ImportText.header(alias);if(!k.isEmpty())owners.computeIfAbsent(k,x->new ArrayList<>()).add(field);}
        }
        Map<String,Integer> fields=new LinkedHashMap<>();List<Problem> unmapped=new ArrayList<>();
        for(int i=0;i<width;i++){
            String header=parsed.headers().get(i);String key=headerKeys.get(i);String shown=GradeMatrixHeaders.label(header,i);
            if(key.isEmpty()){
                boolean empty=true;for(var r:parsed.rows())if(i<r.cells().size()&&!r.cells().get(i).isBlank()){empty=false;break;}
                if(!empty)unmapped.add(new Problem("UNMAPPED_COLUMN",shown,"This column has data but no header, so it cannot be classified."));
                continue;
            }
            List<String> fieldOwners=owners.getOrDefault(key,List.of());boolean ignored=mapping.settings().ignoredColumns().contains(key);
            if(fieldOwners.size()>1||(!fieldOwners.isEmpty()&&ignored))
                throw new ImportFileException(FileError.AMBIGUOUS_HEADER,"Column '"+shown+"' is claimed by more than one classification: "+(fieldOwners.size()>1?fieldOwners:List.of(fieldOwners.getFirst(),"ignoredColumns"))+".");
            if(ignored)continue;
            if(fieldOwners.isEmpty()){unmapped.add(new Problem("UNMAPPED_COLUMN",shown,"This column is neither a field of the mapping nor listed in ignoredColumns. A layout with one column per document is not supported."));continue;}
            String field=fieldOwners.getFirst();Integer previous=fields.putIfAbsent(field,i);
            if(previous!=null)throw new ImportFileException(FileError.AMBIGUOUS_HEADER,"More than one column matches "+field+": '"+GradeMatrixHeaders.label(parsed.headers().get(previous),previous)+"' and '"+shown+"'.");
        }
        if(!unmapped.isEmpty())throw new ImportFileException(FileError.UNMAPPED_COLUMN,"Columns the mapping neither classifies nor lists in ignoredColumns (classify or ignore each): "+unmapped.stream().map(p->"'"+p.header()+"'").toList()+".",unmapped);
        List<String> missing=new ArrayList<>();
        if(!fields.containsKey("studentNumber")&&!fields.containsKey("lrn")&&!fields.containsKey("applicationNumber"))missing.add("studentNumber, lrn or applicationNumber");
        if(!fields.containsKey("document"))missing.add("document");
        if(!fields.containsKey("status"))missing.add("status");
        if(!missing.isEmpty())throw new ImportFileException(FileError.MISSING_COLUMN,"Required columns not found: "+missing+". Check the file's header row and the mapping's aliases.");
        return fields;
    }

    // ---- row parsing ---------------------------------------------------------------------------------------------------

    private static String cell(Row r,List<String> cells,Map<String,Integer> fields,String field){
        Integer i=fields.get(field);if(i==null||i>=cells.size())return null;
        String value=r.cleaner.clean(cells.get(i),field,i<r.headers.size()?r.headers.get(i):null,r.meta.get(i),r.cleaning);r.flush();return value;
    }
    private static CellMeta meta(Row r,Map<String,Integer> fields,String field){Integer i=fields.get(field);return i==null?null:r.meta.get(i);}

    private void identity(Row r,List<String> cells,Map<String,Integer> fields){
        for(String field:fields.keySet()){Integer i=fields.get(field);if(i<cells.size()&&ImportText.hasControl(cells.get(i))&&!"note".equals(field))r.error("INVALID_CHARACTER",field,"The value contains control characters.");}
        r.studentNumber=cell(r,cells,fields,"studentNumber");r.lrn=cell(r,cells,fields,"lrn");r.applicationNumber=cell(r,cells,fields,"applicationNumber");
        r.lastName=cell(r,cells,fields,"lastName");r.firstName=cell(r,cells,fields,"firstName");
        if(r.studentNumber!=null&&r.studentNumber.length()>40){r.error("TOO_LONG","studentNumber","The student number is longer than 40 characters.");r.studentNumber=null;}
        if(r.lrn!=null&&!r.lrn.matches("[0-9]{12}")){r.error("INVALID_LRN","lrn","LRN must be exactly 12 digits.");r.lrn=null;}
        if(r.applicationNumber!=null&&r.applicationNumber.length()>40){r.error("TOO_LONG","applicationNumber","The application number is longer than 40 characters.");r.applicationNumber=null;}
        if(r.lastName!=null&&r.lastName.length()>150){r.error("TOO_LONG","lastName","The name is longer than 150 characters.");r.lastName=null;}
        if(r.firstName!=null&&r.firstName.length()>150){r.error("TOO_LONG","firstName","The name is longer than 150 characters.");r.firstName=null;}
        if(r.studentNumber==null&&r.lrn==null&&r.applicationNumber==null&&r.eligible())r.error("REQUIRED_MISSING","studentNumber","The row has no student number, LRN or application number.");
    }

    private void context(Row r,List<String> cells,Map<String,Integer> fields,DocumentLogMapping mapping,UploadContext ctx,GradeReference ref){
        Long year=ctx.academicYearId(),term=ctx.termId();boolean bad=false;
        String yearCell=cell(r,cells,fields,"schoolYear");
        if(yearCell!=null){
            String mapped=mapping.valueMaps().get("schoolYear").get(ImportText.value(yearCell));
            var y=ref.yearsByLabel().get(GradeReference.key(mapped!=null?mapped:yearCell));
            if(y==null){r.error("UNKNOWN_SCHOOL_YEAR","schoolYear","The school year is not an existing academic year and is not in the mapping's schoolYear value map.");bad=true;}
            else if(year!=null&&year!=y.id()){r.error("CONTEXT_MISMATCH","schoolYear","The school year in the row differs from the one chosen at upload.");bad=true;}
            else year=y.id();
        }
        String termCell=cell(r,cells,fields,"term");
        if(termCell!=null&&!bad){
            if(year==null)r.error("REQUIRED_MISSING","schoolYear","A term value needs a school year, from the file or the upload.");
            else{
                String mapped=mapping.valueMaps().get("term").get(ImportText.value(termCell));
                var t=ref.termsByYear().getOrDefault(year,Map.of()).get(GradeReference.key(mapped!=null?mapped:termCell));
                if(t==null)r.error("UNKNOWN_TERM","term","The term is not an existing term of that academic year and is not in the mapping's term value map.");
                else if(term!=null&&term!=t.id())r.error("CONTEXT_MISMATCH","term","The term in the row differs from the one chosen at upload.");
                else term=t.id();
            }
        }
        if(term==null&&r.applicationNumber==null&&r.eligible())r.error("REQUIRED_MISSING","term","The row has no term and no application number, and no term was chosen at upload.");
        r.termId=term;r.yearId=year;
    }

    /** Document, state, date and note; then the note the import would store, so staging and commit compare exactly what would be written. */
    private void document(Row r,List<String> cells,Map<String,Integer> fields,DocumentLogMapping mapping,Map<String,String> codes,LocalDate today){
        String label=cell(r,cells,fields,"document");
        if(label==null)r.error("REQUIRED_MISSING","document","The row has no document.");
        else{
            String mapped=mapping.valueMaps().get("document").get(ImportText.value(label));
            String code=mapped!=null?(codes.containsValue(mapped)?mapped:null):codes.get(label.toLowerCase(Locale.ROOT));
            if(code==null)r.error("UNMAPPED_VALUE","document","The document is not a requirement code and is not in the mapping's document value map. Requirement types are never created by the import.");
            else r.documentCode=code;
        }
        r.statusLabel=cell(r,cells,fields,"status");
        if(r.statusLabel!=null){
            String key=ImportText.value(r.statusLabel);String mapped=mapping.valueMaps().get("status").get(key);
            String state=mapped!=null?mapped:DocumentLogMapping.STATES.contains(key)?key:null;
            if(state==null)r.error("UNMAPPED_VALUE","status","The state is not one of "+DocumentLogMapping.STATES+" and is not in the mapping's status value map. States are never guessed.");
            else r.status=state;
        }
        String dateCell=cell(r,cells,fields,"date");
        if(dateCell!=null){
            var m=meta(r,fields,"date");LocalDate date=null;
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
        r.note=cell(r,cells,fields,"note");
        if(r.status!=null){
            String composed="Legacy document log: status \""+r.statusLabel+"\""+(r.date!=null?", dated "+r.date:"")+"."+(r.note!=null?" "+r.note:"");
            if(composed.length()>MAX_NOTE)r.error("TOO_LONG","note","The legacy note is too long to store: the stored note may have at most "+MAX_NOTE+" characters. It is never truncated.");
            else r.composedNote=composed;
        }
    }

    // ---- student matching ----------------------------------------------------------------------------------------------

    /** Rows that carry a student number or LRN resolve exactly as in ICI2-38; rows that name only an application number take its student. */
    private void resolveStudents(List<Row> rows,long campusId){
        var outcomes=resolver.resolve(campusId,rows.stream().map(r->new StudentResolver.Query(r.studentNumber,r.lrn,r.lastName,r.firstName)).toList());
        for(int i=0;i<rows.size();i++){
            Row r=rows.get(i);var o=outcomes.get(i);
            r.evidence.addAll(o.evidence());
            for(RowProblem p:o.problems())if(RowProblem.ERROR.equals(p.severity()))r.error(p.code(),p.field(),p.detail());else r.warn(p.code(),p.field(),p.detail());
            r.student=o.student();r.matchedBy=o.matchedBy();
        }
    }

    // ---- plans ---------------------------------------------------------------------------------------------------------

    private void plan(List<Row> rows,long campusId){
        Map<String,Integer> seen=new HashMap<>();
        for(Row r:rows){
            if(!r.eligible()||r.documentCode==null)continue;
            boolean identified=r.student!=null||(r.studentNumber==null&&r.lrn==null&&r.applicationNumber!=null);
            if(!identified)continue;   // an identity that needs review is planned at commit, once linked
            r.plan=planner.plan(campusId,r.termId,r.student==null?null:r.student.id(),r.applicationNumber,r.documentCode,r.status,r.composedNote);
            for(var p:r.plan.problems())r.add(p);
            if(!r.plan.ok())continue;
            String key=r.plan.applicationId()+"|"+r.documentCode;Integer clash=seen.get(key);
            if(clash!=null){r.error("DUPLICATE_IN_BATCH","document","This application's document already appears on row "+clash+". A log with several entries for one document is not replayed.");r.plan=null;continue;}
            seen.put(key,r.number);
        }
    }

    // ---- persistence ---------------------------------------------------------------------------------------------------

    private Map<String,Object> normalized(Row r){
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
    private static Map<String,Object> conflictEvidence(Row r){
        Map<String,Object> e=new LinkedHashMap<>();e.put("kind",r.plan.conflict());e.put("applicationId",r.plan.applicationId());e.put("code",r.documentCode);
        e.put("currentStatus",r.plan.currentStatus());e.put("importedStatus",r.status);e.put("regression",r.plan.regression());return e;
    }

    private Staged persist(long campusId,String name,String hash,long size,long mappingId,String snapshot,String contextJson,String format,List<Row> rows){
        actor();
        int valid=0,invalid=0,flagged=0;
        for(Row r:rows)switch(r.rowStatus){case "valid"->valid++;case "invalid"->invalid++;default->flagged++;}
        long batch=db.queryForObject("INSERT INTO import_batches(source_name,checksum,status,source_count,campus_id,mapping_id,mapping_snapshot,file_size_bytes,valid_count,invalid_count,flagged_count,source_type,upload_context,source_format,cleaning_summary) VALUES (:name,:hash,'validated',:count,:campus,:mapping,CAST(:snapshot AS jsonb),:size,:valid,:invalid,:flagged,:source,CAST(:context AS jsonb),:format,CAST(:summary AS jsonb)) RETURNING id",
            new MapSqlParameterSource(Map.of("name",name,"hash",hash,"count",rows.size(),"campus",campusId,"mapping",mappingId,"snapshot",snapshot,"size",size,"valid",valid,"invalid",invalid,"flagged",flagged))
                .addValue("source",SOURCE).addValue("context",contextJson).addValue("format",format).addValue("summary",CleaningSummary.json(rows.stream().map(r->r.cleaning).toList(),json)),Long.class);
        List<SqlParameterSource> inserts=new ArrayList<>();
        for(Row r:rows){
            String status=r.rowStatus.equals("flagged")?"duplicate_flagged":r.rowStatus;
            List<Map<String,Object>> evidence=new ArrayList<>(r.evidence);
            if(r.rowStatus.equals("flagged")&&r.plan!=null&&r.plan.conflict()!=null)evidence.add(conflictEvidence(r));
            inserts.add(new MapSqlParameterSource().addValue("batch",batch).addValue("number",r.number).addValue("status",status).addValue("campus",campusId).addValue("source",SOURCE)
                .addValue("raw",encode(r.raw)).addValue("normalized",r.rowStatus.equals("invalid")?null:encode(normalized(r))).addValue("evidence",r.rowStatus.equals("flagged")?encode(evidence):null)
                .addValue("cleaning",r.cleaning.hasEntries()?encode(r.cleaning.entries()):null));
        }
        db.batchUpdate("INSERT INTO import_rows(batch_id,row_number,status,campus_id,source_type,raw_values,normalized_values,duplicate_evidence,cleaning) VALUES (:batch,:number,:status,:campus,:source,CAST(:raw AS jsonb),CAST(:normalized AS jsonb),CAST(:evidence AS jsonb),CAST(:cleaning AS jsonb))",inserts.toArray(SqlParameterSource[]::new));
        Map<Integer,Long> ids=new HashMap<>();
        for(var m:db.queryForList("SELECT id,row_number FROM import_rows WHERE batch_id=:b",Map.of("b",batch)))ids.put(((Number)m.get("row_number")).intValue(),((Number)m.get("id")).longValue());
        List<SqlParameterSource> problems=new ArrayList<>();
        for(Row r:rows)for(var p:r.problems)problems.add(new MapSqlParameterSource().addValue("batch",batch).addValue("number",r.number).addValue("code",p.code()).addValue("detail",p.detail()).addValue("row",ids.get(r.number))
            .addValue("field",p.field()==null?null:p.field().length()>200?p.field().substring(0,200):p.field()).addValue("severity",p.severity()));
        if(!problems.isEmpty())db.batchUpdate("INSERT INTO import_exceptions(batch_id,row_number,error_code,detail,import_row_id,field_name,severity) VALUES (:batch,:number,:code,:detail,:row,:field,:severity)",problems.toArray(SqlParameterSource[]::new));
        return new Staged(batch,"validated",rows.size(),valid,invalid,flagged);
    }

    private long recordFailure(long campusId,String name,String hash,long size,long mappingId,String snapshot,String contextJson,String format,ImportFileException e){
        return tx.execute(s->{
            actor();
            long batch=db.queryForObject("INSERT INTO import_batches(source_name,checksum,status,source_count,campus_id,mapping_id,mapping_snapshot,file_size_bytes,source_type,upload_context,source_format) VALUES (:name,:hash,'failed',0,:campus,:mapping,CAST(:snapshot AS jsonb),:size,:source,CAST(:context AS jsonb),:format) RETURNING id",
                new MapSqlParameterSource(Map.of("name",name,"hash",hash,"campus",campusId,"mapping",mappingId,"snapshot",snapshot,"size",size)).addValue("source",SOURCE).addValue("context",contextJson).addValue("format",format),Long.class);
            String message=e.getMessage();
            db.update("INSERT INTO import_exceptions(batch_id,error_code,detail) VALUES (:batch,:code,:detail)",Map.of("batch",batch,"code",e.error().name(),"detail",message.length()>1000?message.substring(0,1000):message));
            List<SqlParameterSource> each=new ArrayList<>();
            for(var p:e.problems())each.add(new MapSqlParameterSource().addValue("batch",batch).addValue("code",p.code()).addValue("detail",p.detail().length()>1000?p.detail().substring(0,1000):p.detail()).addValue("field",p.header().length()>200?p.header().substring(0,200):p.header()));
            if(!each.isEmpty())db.batchUpdate("INSERT INTO import_exceptions(batch_id,error_code,detail,field_name) VALUES (:batch,:code,:detail,:field)",each.toArray(SqlParameterSource[]::new));
            return batch;
        });
    }
}
