package ph.edu.ici.imports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
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
 * Stages and validates a section allocation file (ICI2-39 type A, always a dry run): writes import_batches, import_rows and
 * import_exceptions only, never an enrollment. One row means "this student sits in this section for this term" (layout student_list).
 * Students, enrollments and sections are matched exactly or through the stored mapping, never by similarity or position; the rules that
 * decide whether a section can take a student come from the enrollment service (see {@link SectionAllocationPlanner}). Messages never
 * echo names, student numbers or LRNs.
 */
@Service
public class SectionAllocationValidationService {
    private static final int MAX_PROBLEMS_PER_ROW=50;
    static final String SOURCE=SectionAllocationMappingService.SOURCE;
    private final NamedParameterJdbcTemplate db;private final ObjectMapper json;private final AccessPolicy access;
    private final SectionAllocationMappingService mappings;private final ImportLimits limits;private final TransactionTemplate tx;
    private final StudentResolver resolver;private final SectionAllocationPlanner planner;

    /** What the registrar declared at upload. Either may instead (or also) come from a mapped column; if both exist they must agree. */
    public record UploadContext(Long academicYearId,Long termId) {}
    public record Staged(long batchId,String status,int sourceCount,int valid,int invalid,int flagged,int skipped) {}

    private record Section(long id,String name,int capacity,int occupied) {}
    private static final class Row {
        final int number;final Map<String,String> raw;final List<RowProblem> problems=new ArrayList<>();
        final CleaningLog cleaning=new CleaningLog();CellCleaner cleaner;List<String> headers=List.of();Map<Integer,CellMeta> meta=Map.of();int flushed;
        String studentNumber,lrn,lastName,firstName,sectionLabel,gradeLevel,programCode;Long termId,yearId;
        Section section;
        StudentResolver.Student student;String matchedBy;final List<Map<String,Object>> evidence=new ArrayList<>();
        SectionAllocationPlanner.Plan plan;String status;
        Row(int number,Map<String,String> raw){this.number=number;this.raw=raw;}
        int errors,warnings;
        boolean eligible(){return errors==0;}
        void flush(){for(;flushed<cleaning.problems().size();flushed++){var p=cleaning.problems().get(flushed);if(RowProblem.ERROR.equals(p.severity()))error(p.code(),p.field(),p.detail());else warn(p.code(),p.field(),p.detail());}}
        void error(String code,String field,String detail){errors++;add(RowProblem.error(code,field,detail),errors);}
        void warn(String code,String field,String detail){warnings++;add(RowProblem.warning(code,field,detail),warnings);}
        private void add(RowProblem p,int count){if(count<=MAX_PROBLEMS_PER_ROW)problems.add(p);else if(count==MAX_PROBLEMS_PER_ROW+1)problems.add(RowProblem.warning("MORE_PROBLEMS",null,"More "+p.severity()+"s were found on this row than are listed."));}
        void add(RowProblem p){if(RowProblem.ERROR.equals(p.severity()))error(p.code(),p.field(),p.detail());else warn(p.code(),p.field(),p.detail());}
    }

    public SectionAllocationValidationService(NamedParameterJdbcTemplate db,ObjectMapper json,AccessPolicy access,SectionAllocationMappingService mappings,ImportLimits limits,
                                              PlatformTransactionManager transactions,SectionAllocationPlanner planner){
        this.db=db;this.json=json;this.access=access;this.mappings=mappings;this.limits=limits;this.tx=new TransactionTemplate(transactions);this.resolver=new StudentResolver(db);this.planner=planner;
    }
    private StaffIdentity identity(){return (StaffIdentity)SecurityContextHolder.getContext().getAuthentication().getPrincipal();}
    private void actor(){db.queryForObject("SELECT set_config('app.actor_id',:actor,true)",Map.of("actor",Long.toString(identity().userId())),String.class);}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private static String checksum(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String safeName(String name){String s=name==null?"":name.replaceAll("^.*[\\\\/]","").replaceAll("\\p{Cc}","").trim();if(s.isEmpty())s="upload";return s.length()>255?s.substring(0,255):s;}
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}

    /**
     * Parse, validate and stage one allocation file for one campus. The caller supplies the reader for the file's format ({@link XlsxGridReader}
     * for uploads; the CSV reader stands in for service-level tests). Throws {@link ImportFileException} (413/422) or {@link ImportConflictException} (409).
     */
    public Staged stage(long campusId,UploadContext upload,String fileName,byte[] bytes,GridReader reader,Long mappingId){
        if(!access.campuses("imports.enrollment.manage").contains(campusId)||!access.campuses("enrollment.manage").contains(campusId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Your account cannot import section allocations for this campus.");
        if(bytes.length>limits.maxFileBytes())throw new ImportFileException(FileError.FILE_TOO_LARGE,"The file is larger than "+limits.maxFileBytes()+" bytes.");
        if(Boolean.FALSE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM campuses WHERE id=:id)",Map.of("id",campusId),Boolean.class)))throw bad("Select an existing campus.");
        String hash=checksum(bytes);
        var existing=liveBatch(hash);
        if(existing!=null)throw new ImportConflictException(((Number)existing.get("id")).longValue(),(String)existing.get("status"));
        var ref=GradeReference.load(db.getJdbcTemplate());
        var ctx=context(upload,ref);
        var stored=mappings.forUpload(mappingId);var mapping=SectionAllocationMapping.parse(stored.mapping(),stored.settings());var snapshot=encode(mappings.snapshot(stored));
        String contextJson=encode(contextMap(ctx));String name=safeName(fileName);

        CsvParser.Parsed parsed;Map<String,Integer> fields;
        try{
            parsed=reader.read(bytes,mapping.csv(),mapping.settings().sheet());
            fields=classify(parsed,mapping,ctx);
        }catch(ImportFileException e){
            if(e.error().recorded)e.batchId(recordFailure(campusId,name,hash,bytes.length,stored.id(),snapshot,contextJson,reader.format(),e));
            throw e;
        }

        Map<String,Long> programs=new HashMap<>();
        for(var p:db.getJdbcTemplate().queryForList("SELECT id,code FROM programs"))programs.put(GradeReference.key((String)p.get("code")),((Number)p.get("id")).longValue());
        List<Row> rows=new ArrayList<>();var cleaner=new CellCleaner(mapping.cleaning());
        for(var record:parsed.rows()){
            Map<String,String> raw=new LinkedHashMap<>();for(int i=0;i<record.cells().size()&&i<parsed.headers().size();i++)raw.put(parsed.headers().get(i),record.cells().get(i));
            var row=new Row(record.number(),raw);rows.add(row);row.cleaner=cleaner;row.headers=parsed.headers();row.meta=record.meta();
            if(record.cells().size()!=parsed.headers().size()){row.error("COLUMN_COUNT_MISMATCH",null,"The row has "+record.cells().size()+" values but the header has "+parsed.headers().size()+".");continue;}
            identity(row,record.cells(),fields);context(row,record.cells(),fields,mapping,ctx,ref,programs);
        }
        resolveSections(rows,campusId,mapping);
        resolveStudents(rows,campusId);
        plan(rows,campusId);
        capacity(rows);
        for(Row r:rows)r.status=!r.eligible()?"invalid":(!r.evidence.isEmpty()||(r.plan!=null&&SectionAllocationPlanner.REASSIGN.equals(r.plan.action())))?"flagged":"valid";
        try{return tx.execute(s->persist(campusId,name,hash,bytes.length,stored.id(),snapshot,contextJson,reader.format(),rows));}
        catch(DuplicateKeyException e){
            var raced=liveBatch(hash);if(raced==null)throw e;
            throw new ImportConflictException(((Number)raced.get("id")).longValue(),(String)raced.get("status"));
        }
    }

    /** A batch with the same bytes that still blocks re-upload: not failed (a discarded batch is failed). */
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

    /** Every non-blank header must be a field alias or an ignored column; a repeated header row, an ambiguous column or a missing required column fails the file. */
    private Map<String,Integer> classify(CsvParser.Parsed parsed,SectionAllocationMapping mapping,UploadContext ctx){
        int width=parsed.headers().size();
        List<String> headerKeys=parsed.headers().stream().map(ImportText::header).toList();
        if(headerKeys.stream().filter(k->!k.isEmpty()).count()>=2)
            for(var r:parsed.rows())if(r.cells().size()==width&&r.cells().stream().map(ImportText::header).toList().equals(headerKeys))
                throw new ImportFileException(FileError.REPEATED_HEADER,"Row "+r.number()+" repeats the header row. Files written as repeated section blocks are not supported; use one list with a section column.");
        Map<String,List<String>> owners=new HashMap<>();
        for(String field:SectionAllocationMapping.FIELDS){
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
            if(fieldOwners.isEmpty()){unmapped.add(new Problem("UNMAPPED_COLUMN",shown,"This column is neither a field of the mapping nor listed in ignoredColumns. A matrix with sections as columns is not supported."));continue;}
            String field=fieldOwners.getFirst();Integer previous=fields.putIfAbsent(field,i);
            if(previous!=null)throw new ImportFileException(FileError.AMBIGUOUS_HEADER,"More than one column matches "+field+": '"+GradeMatrixHeaders.label(parsed.headers().get(previous),previous)+"' and '"+shown+"'.");
        }
        if(!unmapped.isEmpty())throw new ImportFileException(FileError.UNMAPPED_COLUMN,"Columns the mapping neither classifies nor lists in ignoredColumns (classify or ignore each): "+unmapped.stream().map(p->"'"+p.header()+"'").toList()+".",unmapped);
        List<String> missing=new ArrayList<>();
        if(!fields.containsKey("studentNumber")&&!fields.containsKey("lrn"))missing.add("studentNumber or lrn");
        if(!fields.containsKey("section"))missing.add("section");
        if(ctx.termId()==null&&!fields.containsKey("term"))missing.add("term (or choose a term at upload)");
        if(ctx.academicYearId()==null&&!fields.containsKey("schoolYear"))missing.add("schoolYear (or choose an academic year or term at upload)");
        if(!missing.isEmpty())throw new ImportFileException(FileError.MISSING_COLUMN,"Required columns or upload values not found: "+missing+". Check the file's header row, the mapping's aliases or the upload form.");
        return fields;
    }

    // ---- row parsing ---------------------------------------------------------------------------------------------------

    private static String cell(Row r,List<String> cells,Map<String,Integer> fields,String field){
        Integer i=fields.get(field);if(i==null||i>=cells.size())return null;
        String value=r.cleaner.clean(cells.get(i),field,i<r.headers.size()?r.headers.get(i):null,r.meta.get(i),r.cleaning);r.flush();return value;
    }

    private void identity(Row r,List<String> cells,Map<String,Integer> fields){
        for(String field:fields.keySet()){Integer i=fields.get(field);if(i<cells.size()&&ImportText.hasControl(cells.get(i)))r.error("INVALID_CHARACTER",field,"The value contains control characters.");}
        r.studentNumber=cell(r,cells,fields,"studentNumber");r.lrn=cell(r,cells,fields,"lrn");r.lastName=cell(r,cells,fields,"lastName");r.firstName=cell(r,cells,fields,"firstName");
        if(r.studentNumber!=null&&r.studentNumber.length()>40){r.error("TOO_LONG","studentNumber","The student number is longer than 40 characters.");r.studentNumber=null;}
        if(r.lrn!=null&&!r.lrn.matches("[0-9]{12}")){r.error("INVALID_LRN","lrn","LRN must be exactly 12 digits.");r.lrn=null;}
        if(r.lastName!=null&&r.lastName.length()>150){r.error("TOO_LONG","lastName","The name is longer than 150 characters.");r.lastName=null;}
        if(r.firstName!=null&&r.firstName.length()>150){r.error("TOO_LONG","firstName","The name is longer than 150 characters.");r.firstName=null;}
        if(r.studentNumber==null&&r.lrn==null&&r.eligible())r.error("REQUIRED_MISSING","studentNumber","The row has neither a student number nor an LRN.");
    }

    private void context(Row r,List<String> cells,Map<String,Integer> fields,SectionAllocationMapping mapping,UploadContext ctx,GradeReference ref,Map<String,Long> programs){
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
        if(term==null&&r.eligible())r.error("REQUIRED_MISSING","term","The row has no term, and none was chosen at upload.");
        r.termId=term;r.yearId=year;
        r.sectionLabel=cell(r,cells,fields,"section");
        if(r.sectionLabel==null)r.error("REQUIRED_MISSING","section","The row has no section.");
        else if(r.sectionLabel.length()>150){r.error("TOO_LONG","section","The section is longer than 150 characters.");r.sectionLabel=null;}
        String level=cell(r,cells,fields,"gradeLevel");
        if(level!=null&&level.length()>30){r.error("TOO_LONG","gradeLevel","The grade level is longer than 30 characters.");level=null;}
        r.gradeLevel=level;
        String program=cell(r,cells,fields,"program");
        if(program!=null){
            String mapped=mapping.valueMaps().get("program").get(ImportText.value(program));String key=GradeReference.key(mapped!=null?mapped:program);
            if(!programs.containsKey(key))r.error("UNKNOWN_PROGRAM","program","The program is not an existing program code and is not in the mapping's program value map.");
            else r.programCode=key;
        }
    }

    // ---- sections ------------------------------------------------------------------------------------------------------

    /** Sections of a campus and term with current occupancy, loaded once per term of the file. */
    private Map<Long,List<Section>> sectionsByTerm(long campusId,Collection<Long> terms){
        Map<Long,List<Section>> out=new HashMap<>();
        for(long term:terms){
            List<Section> sections=new ArrayList<>();
            for(var m:db.queryForList("""
                SELECT s.id,s.name,s.capacity,(SELECT count(*) FROM enrollments e WHERE e.section_id=s.id AND e.status IN ('provisional','enrolled')) AS occupied
                FROM sections s WHERE s.campus_id=:campus AND s.term_id=:term ORDER BY s.id""",Map.of("campus",campusId,"term",term)))
                sections.add(new Section(((Number)m.get("id")).longValue(),(String)m.get("name"),((Number)m.get("capacity")).intValue(),((Number)m.get("occupied")).intValue()));
            out.put(term,sections);
        }
        return out;
    }

    /** The legacy label is translated by valueMaps.section (to a section name), then must equal exactly one section name of the campus and term after value normalization. */
    private void resolveSections(List<Row> rows,long campusId,SectionAllocationMapping mapping){
        Set<Long> terms=new LinkedHashSet<>();for(Row r:rows)if(r.eligible()&&r.termId!=null&&r.sectionLabel!=null)terms.add(r.termId);
        var byTerm=sectionsByTerm(campusId,terms);
        for(Row r:rows){
            if(!r.eligible()||r.termId==null||r.sectionLabel==null)continue;
            String mapped=mapping.valueMaps().get("section").get(ImportText.value(r.sectionLabel));
            String key=ImportText.value(mapped!=null?mapped:r.sectionLabel);
            List<Section> hits=byTerm.getOrDefault(r.termId,List.of()).stream().filter(s->ImportText.value(s.name()).equals(key)).toList();
            if(hits.isEmpty())r.error("UNKNOWN_SECTION","section","No section of this campus and term has this name, and the label is not in the mapping's section value map. Sections are never created by the import.");
            else if(hits.size()>1)r.error("AMBIGUOUS_SECTION","section","More than one section of this campus and term has this name once case and spacing are ignored.");
            else r.section=hits.getFirst();
        }
    }

    // ---- student matching ----------------------------------------------------------------------------------------------

    /** Existing students of the batch's campus only. An inactive (archived) student cannot hold a seat, so it is an error here, not the grade matrix's warning. */
    private void resolveStudents(List<Row> rows,long campusId){
        var outcomes=resolver.resolve(campusId,rows.stream().map(r->new StudentResolver.Query(r.studentNumber,r.lrn,r.lastName,r.firstName)).toList());
        for(int i=0;i<rows.size();i++){
            Row r=rows.get(i);var o=outcomes.get(i);
            r.evidence.addAll(o.evidence());
            for(RowProblem p:o.problems()){
                if("STUDENT_INACTIVE".equals(p.code()))continue;
                if(RowProblem.ERROR.equals(p.severity()))r.error(p.code(),p.field(),p.detail());else r.warn(p.code(),p.field(),p.detail());
            }
            r.student=o.student();r.matchedBy=o.matchedBy();
            if(r.student!=null&&!r.student.active())r.error("STUDENT_INACTIVE",null,"The matched student is inactive (archived) and cannot be placed in a section.");
        }
    }

    // ---- enrollment plans and capacity ---------------------------------------------------------------------------------

    private void plan(List<Row> rows,long campusId){
        Map<Long,Integer> seen=new HashMap<>();
        for(Row r:rows){
            if(!r.eligible()||r.student==null||r.section==null||r.termId==null)continue;
            Integer clash=seen.get(r.student.id());
            if(clash!=null){r.error("DUPLICATE_IN_BATCH",null,"This student already appears on row "+clash+".");continue;}
            seen.put(r.student.id(),r.number);
            r.plan=planner.plan(campusId,r.termId,r.student.id(),r.section.id(),r.gradeLevel,r.programCode);
            for(var p:r.plan.problems())r.add(p);
        }
    }

    /**
     * Seats across the batch, per section: free = capacity - occupied now + students of this batch leaving it; arriving rows are admitted in
     * file order until it is full and the rest get SECTION_FULL (numbers only). A refused row stops leaving or arriving, so the count is
     * recomputed from the surviving rows until it is stable. Reassignments awaiting review are counted as if applied; if the reviewer skips one,
     * seats only become freer, and the enrollment service rechecks capacity under locks at commit.
     */
    private void capacity(List<Row> rows){
        boolean changed=true;
        while(changed){
            changed=false;
            Map<Long,Integer> free=new HashMap<>();Map<Long,Section> sections=new HashMap<>();
            List<Row> moving=new ArrayList<>();
            for(Row r:rows)if(r.eligible()&&r.plan!=null&&r.plan.ok()&&!SectionAllocationPlanner.NOOP.equals(r.plan.action())){moving.add(r);sections.put(r.section.id(),r.section);}
            for(Section s:sections.values())free.put(s.id(),s.capacity()-s.occupied());
            for(Row r:moving)if(r.plan.fromSectionId()!=null)free.merge(r.plan.fromSectionId(),1,Integer::sum);
            for(Row r:moving){
                long target=r.section.id();int left=free.get(target);
                if(left>0){free.put(target,left-1);continue;}
                r.error("SECTION_FULL","section","The section has "+r.section.capacity()+" seats and "+r.section.occupied()+" are occupied, so no seat is left for this row once earlier rows of the file are placed.");
                boolean wasLeaving=r.plan.fromSectionId()!=null;
                r.plan=null;
                // A refused plain assignment took no seat and freed none; a refused reassignment no longer frees its old seat, so recount.
                if(wasLeaving){changed=true;break;}
            }
        }
    }

    // ---- persistence ---------------------------------------------------------------------------------------------------

    private Map<String,Object> normalized(Row r){
        Map<String,Object> v=new LinkedHashMap<>();
        v.put("studentId",r.student==null?null:r.student.id());v.put("matchedBy",r.matchedBy);v.put("studentNumber",r.studentNumber);v.put("lrn",r.lrn);
        v.put("academicYearId",r.yearId);v.put("termId",r.termId);
        v.put("sectionId",r.section==null?null:r.section.id());v.put("sectionName",r.section==null?null:r.section.name());
        v.put("gradeLevel",r.gradeLevel);v.put("program",r.programCode);
        if(r.plan!=null){v.put("action",r.plan.action());v.put("enrollmentId",r.plan.enrollmentId());v.put("enrollmentVersion",r.plan.enrollmentVersion());v.put("fromSectionId",r.plan.fromSectionId());}
        return v;
    }

    private Staged persist(long campusId,String name,String hash,long size,long mappingId,String snapshot,String contextJson,String format,List<Row> rows){
        actor();
        int valid=0,invalid=0,flagged=0;
        for(Row r:rows)switch(r.status){case "valid"->valid++;case "invalid"->invalid++;default->flagged++;}
        long batch=db.queryForObject("INSERT INTO import_batches(source_name,checksum,status,source_count,campus_id,mapping_id,mapping_snapshot,file_size_bytes,valid_count,invalid_count,flagged_count,source_type,upload_context,source_format,cleaning_summary) VALUES (:name,:hash,'validated',:count,:campus,:mapping,CAST(:snapshot AS jsonb),:size,:valid,:invalid,:flagged,:source,CAST(:context AS jsonb),:format,CAST(:summary AS jsonb)) RETURNING id",
            new MapSqlParameterSource(Map.of("name",name,"hash",hash,"count",rows.size(),"campus",campusId,"mapping",mappingId,"snapshot",snapshot,"size",size,"valid",valid,"invalid",invalid,"flagged",flagged))
                .addValue("source",SOURCE).addValue("context",contextJson).addValue("format",format).addValue("summary",CleaningSummary.json(rows.stream().map(r->r.cleaning).toList(),json)),Long.class);
        List<SqlParameterSource> inserts=new ArrayList<>();
        for(Row r:rows){
            String status=r.status.equals("flagged")?"duplicate_flagged":r.status;
            List<Map<String,Object>> evidence=new ArrayList<>(r.evidence);
            if(r.status.equals("flagged")&&r.plan!=null&&SectionAllocationPlanner.REASSIGN.equals(r.plan.action())){
                Map<String,Object> e=new LinkedHashMap<>();e.put("kind","reassignment");e.put("fromSectionId",r.plan.fromSectionId());e.put("toSectionId",r.plan.toSectionId());evidence.add(e);
            }
            inserts.add(new MapSqlParameterSource().addValue("batch",batch).addValue("number",r.number).addValue("status",status).addValue("campus",campusId).addValue("source",SOURCE)
                .addValue("raw",encode(r.raw)).addValue("normalized",r.status.equals("invalid")?null:encode(normalized(r))).addValue("evidence",r.status.equals("flagged")?encode(evidence):null)
                .addValue("cleaning",r.cleaning.hasEntries()?encode(r.cleaning.entries()):null));
        }
        db.batchUpdate("INSERT INTO import_rows(batch_id,row_number,status,campus_id,source_type,raw_values,normalized_values,duplicate_evidence,cleaning) VALUES (:batch,:number,:status,:campus,:source,CAST(:raw AS jsonb),CAST(:normalized AS jsonb),CAST(:evidence AS jsonb),CAST(:cleaning AS jsonb))",inserts.toArray(SqlParameterSource[]::new));
        Map<Integer,Long> ids=new HashMap<>();
        for(var m:db.queryForList("SELECT id,row_number FROM import_rows WHERE batch_id=:b",Map.of("b",batch)))ids.put(((Number)m.get("row_number")).intValue(),((Number)m.get("id")).longValue());
        List<SqlParameterSource> problems=new ArrayList<>();
        for(Row r:rows)for(var p:r.problems)problems.add(new MapSqlParameterSource().addValue("batch",batch).addValue("number",r.number).addValue("code",p.code()).addValue("detail",p.detail()).addValue("row",ids.get(r.number))
            .addValue("field",p.field()==null?null:p.field().length()>200?p.field().substring(0,200):p.field()).addValue("severity",p.severity()));
        if(!problems.isEmpty())db.batchUpdate("INSERT INTO import_exceptions(batch_id,row_number,error_code,detail,import_row_id,field_name,severity) VALUES (:batch,:number,:code,:detail,:row,:field,:severity)",problems.toArray(SqlParameterSource[]::new));
        return new Staged(batch,"validated",rows.size(),valid,invalid,flagged,0);
    }

    /** A failed batch with one summary exception and one exception per named header, so an administrator sees every header to classify or ignore. */
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
