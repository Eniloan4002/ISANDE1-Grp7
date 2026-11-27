package ph.edu.ici.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Stages and validates a section allocation file (ICI2-39 type A, always a dry run): writes import_batches, import_rows and
 * import_exceptions only, never an enrollment. One row means "this student sits in this section for this term" (layout student_list).
 * Students, enrollments and sections are matched exactly or through the stored mapping, never by similarity or position; the rules that
 * decide whether a section can take a student come from the enrollment service (see {@link SectionAllocationPlanner}). The machinery shared
 * with the document log import is in {@link StagingSupport}. Messages never echo names, student numbers or LRNs.
 */
@Service
public class SectionAllocationValidationService {
    static final String SOURCE=SectionAllocationMappingService.SOURCE;
    private final NamedParameterJdbcTemplate db;private final AccessPolicy access;
    private final SectionAllocationMappingService mappings;private final ImportLimits limits;private final StagingSupport staging;private final SectionAllocationPlanner planner;

    /** What the registrar declared at upload. Either may instead (or also) come from a mapped column; if both exist they must agree. */
    public record UploadContext(Long academicYearId,Long termId) {}
    public record Staged(long batchId,String status,int sourceCount,int valid,int invalid,int flagged,int skipped) {}

    private record Section(long id,String name,int capacity,int occupied) {}
    private static final class Row extends StagingSupport.Row {
        String sectionLabel,gradeLevel,programCode;Section section;SectionAllocationPlanner.Plan plan;
        Row(CsvParser.Record record,StagingSupport.Grid grid,CellCleaner cleaner){super(record,grid,cleaner);}
    }

    public SectionAllocationValidationService(NamedParameterJdbcTemplate db,ObjectMapper json,AccessPolicy access,SectionAllocationMappingService mappings,ImportLimits limits,
                                              PlatformTransactionManager transactions,SectionAllocationPlanner planner){
        this.db=db;this.access=access;this.mappings=mappings;this.limits=limits;this.planner=planner;
        this.staging=new StagingSupport(db,json,new TransactionTemplate(transactions),SOURCE);
    }

    /**
     * Parse, validate and stage one allocation file for one campus. The caller supplies the reader for the file's format ({@link XlsxGridReader}
     * for uploads; the CSV reader stands in for service-level tests). Throws {@link ImportFileException} (413/422) or {@link ImportConflictException} (409).
     */
    public Staged stage(long campusId,UploadContext upload,String fileName,byte[] bytes,GridReader reader,Long mappingId){
        if(!access.campuses("imports.enrollment.manage").contains(campusId)||!access.campuses("enrollment.manage").contains(campusId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Your account cannot import section allocations for this campus.");
        String hash=staging.admit(campusId,bytes,limits);
        var ref=GradeReference.load(db.getJdbcTemplate());
        var ctx=upload==null?staging.context(null,null,ref):staging.context(upload.academicYearId(),upload.termId(),ref);
        var stored=mappings.forUpload(mappingId);var mapping=SectionAllocationMapping.parse(stored.mapping(),stored.settings());
        var u=new StagingSupport.Upload(campusId,StagingSupport.safeName(fileName),hash,bytes.length,stored.id(),staging.encode(mappings.snapshot(stored)),staging.encode(ctx.json()),reader.format());
        var grid=staging.read(u,()->reader.read(bytes,mapping.csv(),mapping.settings().sheet()),parsed->classify(parsed,mapping,ctx));

        Map<String,Long> programs=new HashMap<>();
        for(var p:db.getJdbcTemplate().queryForList("SELECT id,code FROM programs"))programs.put(GradeReference.key((String)p.get("code")),((Number)p.get("id")).longValue());
        List<Row> rows=new ArrayList<>();var cleaner=new CellCleaner(mapping.cleaning());
        for(var record:grid.parsed().rows()){
            var row=new Row(record,grid,cleaner);rows.add(row);
            if(!row.widthMatches())continue;
            StagingSupport.identity(row,Set.of());
            if(row.studentNumber==null&&row.lrn==null&&row.eligible())row.error("REQUIRED_MISSING","studentNumber","The row has neither a student number nor an LRN.");
            context(row,mapping,ctx,ref,programs);
        }
        resolveSections(rows,campusId,mapping);
        staging.resolveStudents(rows,campusId,true);
        plan(rows,campusId);
        capacity(rows);
        for(Row r:rows)r.outcome=!r.eligible()?"invalid":(!r.evidence.isEmpty()||(r.plan!=null&&SectionAllocationPlanner.REASSIGN.equals(r.plan.action())))?"flagged":"valid";
        var saved=staging.save(u,rows,this::normalized,SectionAllocationValidationService::reassignmentEvidence);
        return new Staged(saved.batchId(),"validated",rows.size(),saved.valid(),saved.invalid(),saved.flagged(),0);
    }

    /** Every non-blank header must be a field alias or an ignored column; a repeated header row, an ambiguous column or a missing required column fails the file. */
    private Map<String,Integer> classify(CsvParser.Parsed parsed,SectionAllocationMapping mapping,StagingSupport.Context ctx){
        var fields=staging.classify(parsed,SectionAllocationMapping.FIELDS,mapping.fields(),mapping.settings().ignoredColumns(),
            "Files written as repeated section blocks are not supported; use one list with a section column.","A matrix with sections as columns is not supported.");
        List<String> missing=new ArrayList<>();
        if(!fields.containsKey("studentNumber")&&!fields.containsKey("lrn"))missing.add("studentNumber or lrn");
        if(!fields.containsKey("section"))missing.add("section");
        if(ctx.termId()==null&&!fields.containsKey("term"))missing.add("term (or choose a term at upload)");
        if(ctx.academicYearId()==null&&!fields.containsKey("schoolYear"))missing.add("schoolYear (or choose an academic year or term at upload)");
        if(!missing.isEmpty())throw new ImportFileException(FileError.MISSING_COLUMN,"Required columns or upload values not found: "+missing+". Check the file's header row, the mapping's aliases or the upload form.");
        return fields;
    }

    // ---- row parsing ---------------------------------------------------------------------------------------------------

    private void context(Row r,SectionAllocationMapping mapping,StagingSupport.Context ctx,GradeReference ref,Map<String,Long> programs){
        StagingSupport.term(r,mapping.valueMaps(),ctx,ref);
        if(r.termId==null&&r.eligible())r.error("REQUIRED_MISSING","term","The row has no term, and none was chosen at upload.");
        r.sectionLabel=r.cell("section");
        if(r.sectionLabel==null)r.error("REQUIRED_MISSING","section","The row has no section.");
        else if(r.sectionLabel.length()>150){r.error("TOO_LONG","section","The section is longer than 150 characters.");r.sectionLabel=null;}
        String level=r.cell("gradeLevel");
        if(level!=null&&level.length()>30){r.error("TOO_LONG","gradeLevel","The grade level is longer than 30 characters.");level=null;}
        r.gradeLevel=level;
        String program=r.cell("program");
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
    private static List<Map<String,Object>> reassignmentEvidence(Row r){
        if(r.plan==null||!SectionAllocationPlanner.REASSIGN.equals(r.plan.action()))return List.of();
        Map<String,Object> e=new LinkedHashMap<>();e.put("kind","reassignment");e.put("fromSectionId",r.plan.fromSectionId());e.put("toSectionId",r.plan.toSectionId());return List.of(e);
    }
}
