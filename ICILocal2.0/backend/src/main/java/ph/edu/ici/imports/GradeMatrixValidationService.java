package ph.edu.ici.imports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
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
import ph.edu.ici.enrollment.IdentityMatcher;
import ph.edu.ici.imports.GradeMatrixHeaders.GradeColumn;
import ph.edu.ici.imports.GradeMatrixHeaders.Layout;
import ph.edu.ici.imports.ImportFileException.FileError;

/**
 * Stages and validates a legacy grade matrix (always a dry run, ICI2-38): writes import_batches, import_rows and import_exceptions only.
 * It never writes legacy_grade_records or grades, never creates students, subjects or academic setup, and never computes a rating.
 * A row is the unit: one bad cell makes the whole row invalid. Messages never echo grade values or personal data.
 */
@Service
public class GradeMatrixValidationService {
    private static final int CHUNK=5_000,MAX_PROBLEMS_PER_ROW=50;
    private final NamedParameterJdbcTemplate db;private final ObjectMapper json;private final AccessPolicy access;
    private final GradeMatrixMappingService mappings;private final ImportLimits limits;private final GradeImportLimits gradeLimits;private final TransactionTemplate tx;

    /** What the registrar declared at upload. Any item may instead (or also) come from a mapped column; if both exist they must agree. */
    public record UploadContext(Long academicYearId,Long termId,String gradeLevel,String section) {}
    public record Staged(long batchId,String status,int sourceCount,int valid,int invalid,int flagged,int skipped,int recordCount,int blankCount) {}

    private record Cell(GradeColumn column,Long periodId,BigDecimal value,String mark) {}
    private record Student(long id,String number,String lrn,String first,String last,boolean active) {}
    private static final class Row {
        final int number;final Map<String,String> raw;final List<RowProblem> problems=new ArrayList<>();
        String studentNumber,lrn,lastName,firstName,gradeLevel,section;Long termId,yearId;
        final List<Cell> cells=new ArrayList<>();int blanks;
        Student student;String matchedBy;final List<Map<String,Object>> evidence=new ArrayList<>();String status;
        Row(int number,Map<String,String> raw){this.number=number;this.raw=raw;}
        int errors,warnings;
        boolean eligible(){return errors==0;}
        void error(String code,String field,String detail){errors++;add(RowProblem.error(code,field,detail),errors);}
        void warn(String code,String field,String detail){warnings++;add(RowProblem.warning(code,field,detail),warnings);}
        /** Each kind is capped separately, so a flood of warnings can never hide an error. */
        private void add(RowProblem p,int count){if(count<=MAX_PROBLEMS_PER_ROW)problems.add(p);else if(count==MAX_PROBLEMS_PER_ROW+1)problems.add(RowProblem.warning("MORE_PROBLEMS",null,"More "+p.severity()+"s were found on this row than are listed."));}
        String key(Cell c){return student.id()+"|"+termId+"|"+c.column().subjectId()+"|"+(c.column().reportedFinal()?"final":c.periodId());}
    }

    public GradeMatrixValidationService(NamedParameterJdbcTemplate db,ObjectMapper json,AccessPolicy access,GradeMatrixMappingService mappings,ImportLimits limits,GradeImportLimits gradeLimits,PlatformTransactionManager transactions){
        this.db=db;this.json=json;this.access=access;this.mappings=mappings;this.limits=limits;this.gradeLimits=gradeLimits;this.tx=new TransactionTemplate(transactions);
    }
    private StaffIdentity identity(){return (StaffIdentity)SecurityContextHolder.getContext().getAuthentication().getPrincipal();}
    private void actor(){db.queryForObject("SELECT set_config('app.actor_id',:actor,true)",Map.of("actor",Long.toString(identity().userId())),String.class);}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private static String checksum(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String safeName(String name){String s=name==null?"":name.replaceAll("^.*[\\\\/]","").replaceAll("\\p{Cc}","").trim();if(s.isEmpty())s="upload.csv";return s.length()>255?s.substring(0,255):s;}
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private static <T> List<List<T>> chunks(Collection<T> values){List<T> all=new ArrayList<>(values);List<List<T>> out=new ArrayList<>();for(int i=0;i<all.size();i+=CHUNK)out.add(all.subList(i,Math.min(all.size(),i+CHUNK)));return out;}

    /** Parse, validate and stage one grade matrix for one campus. Throws {@link ImportFileException} (413/422) or {@link ImportConflictException} (409). */
    public Staged stageGradeMatrix(long campusId,UploadContext upload,String fileName,byte[] bytes,Long mappingId){
        if(!access.campuses("imports.grades.manage").contains(campusId))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Your account cannot import grades for this campus.");
        if(bytes.length>limits.maxFileBytes())throw new ImportFileException(FileError.FILE_TOO_LARGE,"The file is larger than "+limits.maxFileBytes()+" bytes.");
        if(Boolean.FALSE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM campuses WHERE id=:id)",Map.of("id",campusId),Boolean.class)))throw bad("Select an existing campus.");
        String hash=checksum(bytes);
        var existing=liveBatch(hash);
        if(existing!=null)throw new ImportConflictException(((Number)existing.get("id")).longValue(),(String)existing.get("status"));
        var ref=GradeReference.load(db.getJdbcTemplate());
        var ctx=context(upload,ref);
        var stored=mappings.forUpload(mappingId);var mapping=GradeMatrixMapping.parse(stored.mapping(),stored.settings());var snapshot=encode(mappings.snapshot(stored));
        String contextJson=encode(contextMap(ctx));String name=safeName(fileName);

        CsvParser.Parsed parsed;Layout layout;
        try{
            parsed=new CsvParser(gradeLimits.parserLimits(limits)).parse(bytes,mapping.csv());
            layout=classify(parsed,mapping,ref,ctx);
        }catch(ImportFileException e){
            if(e.error().recorded)e.batchId(recordFailure(campusId,name,hash,bytes.length,stored.id(),snapshot,contextJson,e));
            throw e;
        }

        List<Row> rows=new ArrayList<>();
        var number=Pattern.compile("-?\\d+(?:"+Pattern.quote(String.valueOf(mapping.settings().decimalSeparator()))+"\\d+)?");
        for(var record:parsed.rows()){
            Map<String,String> raw=new LinkedHashMap<>();for(int i=0;i<record.cells().size()&&i<parsed.headers().size();i++)raw.put(parsed.headers().get(i),record.cells().get(i));
            var row=new Row(record.number(),raw);rows.add(row);
            if(record.cells().size()!=parsed.headers().size()){row.error("COLUMN_COUNT_MISMATCH",null,"The row has "+record.cells().size()+" values but the header has "+parsed.headers().size()+".");continue;}
            identity(row,record.cells(),layout);context(row,record.cells(),layout,mapping,ctx,ref);cells(row,record.cells(),layout,mapping,ctx,ref,number);
        }
        resolveStudents(rows,campusId);
        checkCells(rows);
        for(Row r:rows){
            r.status=!r.eligible()?"invalid":!r.evidence.isEmpty()?"flagged":"valid";
            if(r.status.equals("valid")&&r.cells.isEmpty())r.warn("NO_GRADES_ON_ROW",null,"This row has no grade values; nothing will be recorded for it.");
        }
        try{return tx.execute(s->persist(campusId,name,hash,bytes.length,stored.id(),snapshot,contextJson,ctx,rows));}
        catch(DuplicateKeyException e){
            var raced=liveBatch(hash);if(raced==null)throw e;
            throw new ImportConflictException(((Number)raced.get("id")).longValue(),(String)raced.get("status"));
        }
    }

    /** A batch with the same bytes that still blocks re-upload: not failed and not fully voided. */
    private Map<String,Object> liveBatch(String hash){
        var rows=db.queryForList("SELECT id,status FROM import_batches WHERE source_type=:s AND checksum=:c AND status<>'failed' AND voided_at IS NULL",Map.of("s",GradeMatrixMappingService.SOURCE,"c",hash));
        return rows.isEmpty()?null:rows.getFirst();
    }

    // ---- upload context ------------------------------------------------------------------------------------------------

    private UploadContext context(UploadContext in,GradeReference ref){
        if(in==null)in=new UploadContext(null,null,null,null);
        Long year=in.academicYearId(),term=in.termId();
        if(year!=null&&!ref.yearsById().containsKey(year))throw bad("Select an existing academic year.");
        if(term!=null){
            var t=ref.termsById().get(term);if(t==null)throw bad("Select an existing term.");
            if(year!=null&&year!=t.yearId())throw bad("The selected term does not belong to the selected academic year.");
            year=t.yearId();
        }
        String level=ImportText.clean(in.gradeLevel()),section=ImportText.clean(in.section());
        if(level!=null&&(level.length()>30||ImportText.hasControl(level)))throw bad("Grade level must be 30 characters or fewer.");
        if(section!=null&&(section.length()>150||ImportText.hasControl(section)))throw bad("Section must be 150 characters or fewer.");
        return new UploadContext(year,term,level,section);
    }
    private static Map<String,Object> contextMap(UploadContext c){
        Map<String,Object> m=new LinkedHashMap<>();
        if(c.academicYearId()!=null)m.put("academicYearId",c.academicYearId());if(c.termId()!=null)m.put("termId",c.termId());
        if(c.gradeLevel()!=null)m.put("gradeLevel",c.gradeLevel());if(c.section()!=null)m.put("section",c.section());return m;
    }

    // ---- file-level classification -------------------------------------------------------------------------------------

    private Layout classify(CsvParser.Parsed parsed,GradeMatrixMapping mapping,GradeReference ref,UploadContext ctx){
        int width=parsed.headers().size();Set<Integer> blankColumns=new HashSet<>();
        for(int i=0;i<width;i++){
            if(!ImportText.header(parsed.headers().get(i)).isEmpty())continue;
            boolean empty=true;for(var r:parsed.rows())if(i<r.cells().size()&&!r.cells().get(i).isBlank()){empty=false;break;}
            if(empty)blankColumns.add(i);
        }
        List<String> headerKeys=parsed.headers().stream().map(ImportText::header).toList();
        if(headerKeys.stream().filter(k->!k.isEmpty()).count()>=2)
            for(var r:parsed.rows())if(r.cells().size()==width&&r.cells().stream().map(ImportText::header).toList().equals(headerKeys))
                throw new ImportFileException(FileError.REPEATED_HEADER,"Row "+r.number()+" repeats the header row. Files with several header blocks are not supported; split the file.");
        var layout=GradeMatrixHeaders.classify(parsed.headers(),mapping,ref,new GradeMatrixHeaders.Fixed(ctx.termId(),ctx.academicYearId(),ctx.gradeLevel()!=null),blankColumns);
        List<String> missing=new ArrayList<>();
        if(!layout.fields().containsKey("studentNumber")&&!layout.fields().containsKey("lrn"))missing.add("studentNumber or lrn");
        mapping.fields().forEach((field,spec)->{if(spec.required()&&!layout.fields().containsKey(field))missing.add(field);});
        if(ctx.termId()==null&&!layout.fields().containsKey("term"))missing.add("term (or choose a term at upload)");
        if(ctx.academicYearId()==null&&!layout.fields().containsKey("schoolYear"))missing.add("schoolYear (or choose an academic year or term at upload)");
        if(ctx.gradeLevel()==null&&!layout.fields().containsKey("gradeLevel"))missing.add("gradeLevel (or enter it at upload)");
        if(!missing.isEmpty())throw new ImportFileException(FileError.MISSING_COLUMN,"Required columns or upload values not found: "+missing+". Check the file's header row, the mapping's aliases or the upload form.");
        long cells=0;
        for(var r:parsed.rows())for(var g:layout.grades())if(g.index()<r.cells().size()&&!r.cells().get(g.index()).isBlank())cells++;
        if(cells>gradeLimits.maxCells())throw new ImportFileException(FileError.TOO_MANY_CELLS,"The file has more than "+gradeLimits.maxCells()+" grade values.");
        return layout;
    }

    // ---- row parsing ---------------------------------------------------------------------------------------------------

    private static String cell(List<String> cells,Layout layout,String field){Integer i=layout.fields().get(field);return i==null||i>=cells.size()?null:ImportText.clean(cells.get(i));}

    private void identity(Row r,List<String> cells,Layout layout){
        for(String field:layout.fields().keySet()){Integer i=layout.fields().get(field);if(i<cells.size()&&ImportText.hasControl(cells.get(i)))r.error("INVALID_CHARACTER",field,"The value contains control characters.");}
        r.studentNumber=cell(cells,layout,"studentNumber");r.lrn=cell(cells,layout,"lrn");r.lastName=cell(cells,layout,"lastName");r.firstName=cell(cells,layout,"firstName");
        if(r.studentNumber!=null&&r.studentNumber.length()>40){r.error("TOO_LONG","studentNumber","The student number is longer than 40 characters.");r.studentNumber=null;}
        if(r.lrn!=null&&!r.lrn.matches("[0-9]{12}")){r.error("INVALID_LRN","lrn","LRN must be exactly 12 digits.");r.lrn=null;}
        for(String name:new String[]{"lastName","firstName"}){String v=name.equals("lastName")?r.lastName:r.firstName;if(v!=null&&v.length()>150){r.error("TOO_LONG",name,"The name is longer than 150 characters.");if(name.equals("lastName"))r.lastName=null;else r.firstName=null;}}
        if(r.studentNumber==null&&r.lrn==null&&r.eligible())r.error("REQUIRED_MISSING","studentNumber","The row has neither a student number nor an LRN.");
    }

    private void context(Row r,List<String> cells,Layout layout,GradeMatrixMapping mapping,UploadContext ctx,GradeReference ref){
        for(String field:new String[]{"schoolYear","term","gradeLevel","section"}){Integer i=layout.fields().get(field);if(i!=null&&i<cells.size()&&ImportText.hasControl(cells.get(i)))r.error("INVALID_CHARACTER",field,"The value contains control characters.");}
        Long year=ctx.academicYearId(),term=ctx.termId();boolean bad=false;
        String yearCell=cell(cells,layout,"schoolYear");
        if(yearCell!=null){
            String mapped=mapping.valueMaps().get("schoolYear").get(ImportText.value(yearCell));
            var y=ref.yearsByLabel().get(GradeReference.key(mapped!=null?mapped:yearCell));
            if(y==null){r.error("UNKNOWN_SCHOOL_YEAR","schoolYear","The school year is not an existing academic year and is not in the mapping's schoolYear value map.");bad=true;}
            else if(year!=null&&year!=y.id()){r.error("CONTEXT_MISMATCH","schoolYear","The school year in the row differs from the one chosen at upload.");bad=true;}
            else year=y.id();
        }
        String termCell=cell(cells,layout,"term");
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
        r.gradeLevel=level(r,cells,layout,"gradeLevel",30,ctx.gradeLevel());r.section=level(r,cells,layout,"section",150,ctx.section());
        if(r.gradeLevel==null&&r.eligible())r.error("REQUIRED_MISSING","gradeLevel","The row has no grade level, and none was entered at upload.");
    }
    private String level(Row r,List<String> cells,Layout layout,String field,int max,String upload){
        String value=cell(cells,layout,field);
        if(value!=null&&value.length()>max){r.error("TOO_LONG",field,"The value is longer than "+max+" characters.");return upload;}
        if(value!=null&&upload!=null&&!ImportText.value(value).equals(ImportText.value(upload))){r.error("CONTEXT_MISMATCH",field,"The value in the row differs from the one entered at upload.");return upload;}
        return upload!=null?upload:value;
    }

    private void cells(Row r,List<String> cells,Layout layout,GradeMatrixMapping mapping,UploadContext ctx,GradeReference ref,Pattern number){
        char decimal=mapping.settings().decimalSeparator();
        for(GradeColumn c:layout.grades()){
            String raw=c.index()<cells.size()?cells.get(c.index()):null;String v=raw==null?null:ImportText.clean(raw);
            if(v==null){r.blanks++;continue;}                                        // blank: no record, and never INC
            if(ImportText.hasControl(raw)){r.error("INVALID_CHARACTER",c.header(),"The value contains control characters.");continue;}
            BigDecimal value=null;String mark=null;
            if(number.matcher(v).matches()){
                var n=new BigDecimal(v.replace(decimal,'.'));
                if(n.signum()<0||n.compareTo(BigDecimal.valueOf(100))>0){r.error("VALUE_OUT_OF_RANGE",c.header(),"The mark is outside 0 to 100.");continue;}
                if(n.stripTrailingZeros().scale()>2){r.error("TOO_MANY_DECIMALS",c.header(),"The mark has more than two decimal places; it is never rounded.");continue;}
                value=n.setScale(2);
            }else{
                String mapped=mapping.valueMaps().get("mark").get(ImportText.value(v));
                String candidate=(mapped!=null?mapped:v).trim().toUpperCase(Locale.ROOT);
                if(GradeMatrixMapping.MARKS.contains(candidate))mark=candidate;
                else{r.error(v.chars().anyMatch(Character::isDigit)?"INVALID_VALUE":"UNMAPPED_MARK",c.header(),"The value is not a mark from 0 to 100, INC, NG or DRP (or a value the mapping translates to one).");continue;}
            }
            Long periodId=null;
            if(!c.reportedFinal()&&r.termId!=null){
                var p=ref.periodsByTerm().getOrDefault(r.termId,Map.of()).get(c.periodKey());
                if(p==null){r.error("UNKNOWN_PERIOD",c.header(),"The grading period does not exist in this row's term.");continue;}
                periodId=p.id();
            }
            r.cells.add(new Cell(c,periodId,value,mark));
        }
    }

    // ---- student matching ----------------------------------------------------------------------------------------------

    /** Existing students of the batch's campus only; a student at another campus is reported as not found. */
    private void resolveStudents(List<Row> rows,long campusId){
        Set<String> numbers=new LinkedHashSet<>(),lrns=new LinkedHashSet<>();
        for(Row r:rows){if(r.studentNumber!=null)numbers.add(r.studentNumber);if(r.lrn!=null)lrns.add(r.lrn);}
        Map<String,Student> byNumber=new HashMap<>(),byLrn=new HashMap<>();
        String select="SELECT id,student_number,lrn,first_name,last_name,active FROM students WHERE campus_id=:campus AND ";
        for(var part:chunks(numbers))for(var m:db.queryForList(select+"student_number IN (:v)",Map.of("campus",campusId,"v",part))){var s=student(m);byNumber.put(s.number(),s);}
        for(var part:chunks(lrns))for(var m:db.queryForList(select+"lrn IN (:v)",Map.of("campus",campusId,"v",part))){var s=student(m);byLrn.put(s.lrn(),s);}
        for(Row r:rows){
            if(r.studentNumber==null&&r.lrn==null)continue;
            Student a=r.studentNumber==null?null:byNumber.get(r.studentNumber),b=r.lrn==null?null:byLrn.get(r.lrn);
            if(a==null&&b==null){r.error("STUDENT_NOT_FOUND",r.studentNumber!=null?"studentNumber":"lrn","No student with this student number or LRN exists at this campus. Import the student first (ICI2-37), then revalidate.");continue;}
            if(a!=null&&b!=null&&a.id()!=b.id()){
                r.evidence.add(evidence(a,"STUDENT_NUMBER",null));r.evidence.add(evidence(b,"LRN",null));
                r.warn("AMBIGUOUS_STUDENT",null,"The student number and the LRN belong to two different students. Review before importing.");continue;
            }
            Student s=a!=null?a:b;
            if(a!=null&&r.lrn!=null&&b==null&&a.lrn()!=null){
                r.evidence.add(evidence(a,"LRN_MISMATCH",null));
                r.warn("AMBIGUOUS_STUDENT","lrn","The LRN in the file differs from the LRN on record for this student number. Review before importing.");continue;
            }
            Double score=nameScore(r,s);
            if(score!=null&&score<IdentityMatcher.NAME_THRESHOLD){
                r.evidence.add(evidence(s,"NAME_MISMATCH",score));
                r.warn("NAME_MISMATCH",null,"The name in the file does not match the student found by identifier. Review before importing.");continue;
            }
            r.student=s;r.matchedBy=a!=null?"studentNumber":"lrn";
            if(!s.active())r.warn("STUDENT_INACTIVE",null,"The matched student is inactive (archived); the grades will still be recorded.");
        }
    }
    private static Student student(Map<String,Object> m){return new Student(((Number)m.get("id")).longValue(),(String)m.get("student_number"),(String)m.get("lrn"),(String)m.get("first_name"),(String)m.get("last_name"),(Boolean)m.get("active"));}
    /** The lowest available name similarity, or null when the file gives no name to compare. */
    private static Double nameScore(Row r,Student s){
        Double score=null;
        if(r.lastName!=null)score=IdentityMatcher.similarity(r.lastName,s.last());
        if(r.firstName!=null){double f=IdentityMatcher.similarity(IdentityMatcher.firstToken(r.firstName),IdentityMatcher.firstToken(s.first()));score=score==null?f:Math.min(score,f);}
        return score;
    }
    private static Map<String,Object> evidence(Student s,String rule,Double score){
        Map<String,Object> e=new LinkedHashMap<>();e.put("kind","student");e.put("rule",rule);e.put("id",s.id());e.put("campusVisible",true);if(score!=null)e.put("nameScore",Math.round(score*100)/100.0);return e;
    }

    // ---- cell checks: duplicates in the file, recorded history, live overlap -------------------------------------------

    private void checkCells(List<Row> rows){
        // A student/term/subject/period appearing twice in one file: the later row is invalid, both are never imported.
        Map<String,Integer> seen=new HashMap<>();
        for(Row r:rows){
            if(!r.eligible()||r.student==null)continue;
            Integer clash=null;for(Cell c:r.cells){clash=seen.get(r.key(c));if(clash!=null)break;}
            if(clash!=null){r.error("DUPLICATE_IN_BATCH",null,"This student already has a value for the same subject and period (term) on row "+clash+".");continue;}
            for(Cell c:r.cells)seen.put(r.key(c),r.number);
        }
        Set<Long> ids=new LinkedHashSet<>();for(Row r:rows)if(r.eligible()&&r.student!=null)ids.add(r.student.id());
        if(ids.isEmpty())return;
        Map<String,String> recorded=new HashMap<>();Map<String,Set<String>> liveGrades=new HashMap<>();Set<String> enrollments=new HashSet<>();
        for(var part:chunks(ids)){
            for(var m:db.queryForList("SELECT student_id,term_id,subject_id,record_kind,grading_period_id,value,mark FROM legacy_grade_records WHERE voided_at IS NULL AND student_id IN (:v)",Map.of("v",part)))
                recorded.put(m.get("student_id")+"|"+m.get("term_id")+"|"+m.get("subject_id")+"|"+("reported_final".equals(m.get("record_kind"))?"final":m.get("grading_period_id")),
                    m.get("value")!=null?"v"+((BigDecimal)m.get("value")).setScale(2).toPlainString():"m"+m.get("mark"));
            for(var m:db.queryForList("""
                SELECT e.student_id,g.term_id,co.subject_id,g.grading_period_id,g.state FROM grades g JOIN enrollments e ON e.id=g.enrollment_id JOIN class_offerings co ON co.id=g.class_id
                WHERE e.student_id IN (:v) AND (g.value IS NOT NULL OR g.mark IS NOT NULL)""",Map.of("v",part)))
                liveGrades.computeIfAbsent(m.get("student_id")+"|"+m.get("term_id")+"|"+m.get("subject_id")+"|"+m.get("grading_period_id"),k->new TreeSet<>()).add((String)m.get("state"));
            for(var m:db.queryForList("SELECT student_id,term_id FROM enrollments WHERE student_id IN (:v)",Map.of("v",part)))enrollments.add(m.get("student_id")+"|"+m.get("term_id"));
        }
        for(Row r:rows){
            if(!r.eligible()||r.student==null)continue;
            int already=0,overlap=0;Set<String> liveStates=new TreeSet<>();
            for(Cell c:r.cells){
                String previous=recorded.get(r.key(c));
                if(previous!=null){
                    String now=c.value()!=null?"v"+c.value().toPlainString():"m"+c.mark();
                    if(previous.equals(now))already++;else r.error("CONFLICTS_WITH_RECORDED",c.column().header(),"A different legacy grade is already recorded for this student, subject and period. Void the earlier record first.");
                }
                var live=c.column().reportedFinal()?null:liveGrades.get(r.student.id()+"|"+r.termId+"|"+c.column().subjectId()+"|"+c.periodId());
                if(live!=null){overlap++;liveStates.addAll(live);}
            }
            if(already>0)r.warn("ALREADY_RECORDED",null,already+" value(s) are already recorded identically and will be skipped.");
            if(overlap>0)r.warn("OVERLAPS_LIVE_GRADE",null,overlap+" value(s) also exist as live grades (drafts count) for the same subject and grading period; live state: "+String.join(", ",liveStates)+". Official documents must resolve this before issuance.");
            if(enrollments.contains(r.student.id()+"|"+r.termId))r.warn("OVERLAPS_LIVE_ENROLLMENT",null,"The student also has a live enrollment in this term.");
        }
    }

    // ---- persistence ---------------------------------------------------------------------------------------------------

    private Map<String,Object> normalized(Row r){
        Map<String,Object> v=new LinkedHashMap<>();
        v.put("studentId",r.student==null?null:r.student.id());v.put("matchedBy",r.matchedBy);v.put("studentNumber",r.studentNumber);v.put("lrn",r.lrn);
        Map<String,Object> ctx=new LinkedHashMap<>();ctx.put("academicYearId",r.yearId);ctx.put("termId",r.termId);ctx.put("gradeLevel",r.gradeLevel);ctx.put("section",r.section);v.put("context",ctx);
        List<Map<String,Object>> cells=new ArrayList<>();
        for(Cell c:r.cells){
            Map<String,Object> m=new LinkedHashMap<>();m.put("column",c.column().header());m.put("index",c.column().index());m.put("subjectId",c.column().subjectId());
            m.put("kind",c.column().reportedFinal()?"reported_final":"period");m.put("periodId",c.periodId());
            if(c.value()!=null)m.put("value",c.value().toPlainString());else m.put("mark",c.mark());cells.add(m);
        }
        v.put("cells",cells);v.put("blankCells",r.blanks);return v;
    }

    private Staged persist(long campusId,String name,String hash,long size,long mappingId,String snapshot,String contextJson,UploadContext ctx,List<Row> rows){
        actor();
        int valid=0,invalid=0,flagged=0,records=0,blanks=0;
        for(Row r:rows){blanks+=r.blanks;switch(r.status){case "valid"->{valid++;records+=r.cells.size();}case "invalid"->invalid++;default->flagged++;}}
        long batch=db.queryForObject("INSERT INTO import_batches(source_name,checksum,status,source_count,campus_id,mapping_id,mapping_snapshot,file_size_bytes,valid_count,invalid_count,flagged_count,source_type,upload_context) VALUES (:name,:hash,'validated',:count,:campus,:mapping,CAST(:snapshot AS jsonb),:size,:valid,:invalid,:flagged,:source,CAST(:context AS jsonb)) RETURNING id",
            new MapSqlParameterSource(Map.of("name",name,"hash",hash,"count",rows.size(),"campus",campusId,"mapping",mappingId,"snapshot",snapshot,"size",size,"valid",valid,"invalid",invalid,"flagged",flagged))
                .addValue("source",GradeMatrixMappingService.SOURCE).addValue("context",contextJson),Long.class);
        List<SqlParameterSource> inserts=new ArrayList<>();
        for(Row r:rows){
            String status=r.status.equals("flagged")?"duplicate_flagged":r.status;
            inserts.add(new MapSqlParameterSource().addValue("batch",batch).addValue("number",r.number).addValue("status",status).addValue("campus",campusId).addValue("source",GradeMatrixMappingService.SOURCE)
                .addValue("raw",encode(r.raw)).addValue("normalized",r.status.equals("invalid")?null:encode(normalized(r))).addValue("evidence",r.status.equals("flagged")?encode(r.evidence):null));
        }
        db.batchUpdate("INSERT INTO import_rows(batch_id,row_number,status,campus_id,source_type,raw_values,normalized_values,duplicate_evidence) VALUES (:batch,:number,:status,:campus,:source,CAST(:raw AS jsonb),CAST(:normalized AS jsonb),CAST(:evidence AS jsonb))",inserts.toArray(SqlParameterSource[]::new));
        Map<Integer,Long> ids=new HashMap<>();
        for(var m:db.queryForList("SELECT id,row_number FROM import_rows WHERE batch_id=:b",Map.of("b",batch)))ids.put(((Number)m.get("row_number")).intValue(),((Number)m.get("id")).longValue());
        List<SqlParameterSource> problems=new ArrayList<>();
        for(Row r:rows)for(var p:r.problems)problems.add(new MapSqlParameterSource().addValue("batch",batch).addValue("number",r.number).addValue("code",p.code()).addValue("detail",p.detail()).addValue("row",ids.get(r.number))
            .addValue("field",p.field()==null?null:p.field().length()>200?p.field().substring(0,200):p.field()).addValue("severity",p.severity()));
        if(!problems.isEmpty())db.batchUpdate("INSERT INTO import_exceptions(batch_id,row_number,error_code,detail,import_row_id,field_name,severity) VALUES (:batch,:number,:code,:detail,:row,:field,:severity)",problems.toArray(SqlParameterSource[]::new));
        return new Staged(batch,"validated",rows.size(),valid,invalid,flagged,0,records,blanks);
    }

    /** A failed batch with one summary exception and one exception per named header, so an administrator sees every header to classify or ignore. */
    private long recordFailure(long campusId,String name,String hash,long size,long mappingId,String snapshot,String contextJson,ImportFileException e){
        return tx.execute(s->{
            actor();
            long batch=db.queryForObject("INSERT INTO import_batches(source_name,checksum,status,source_count,campus_id,mapping_id,mapping_snapshot,file_size_bytes,source_type,upload_context) VALUES (:name,:hash,'failed',0,:campus,:mapping,CAST(:snapshot AS jsonb),:size,:source,CAST(:context AS jsonb)) RETURNING id",
                new MapSqlParameterSource(Map.of("name",name,"hash",hash,"campus",campusId,"mapping",mappingId,"snapshot",snapshot,"size",size)).addValue("source",GradeMatrixMappingService.SOURCE).addValue("context",contextJson),Long.class);
            String message=e.getMessage();
            db.update("INSERT INTO import_exceptions(batch_id,error_code,detail) VALUES (:batch,:code,:detail)",Map.of("batch",batch,"code",e.error().name(),"detail",message.length()>1000?message.substring(0,1000):message));
            List<SqlParameterSource> each=new ArrayList<>();
            for(var p:e.problems())each.add(new MapSqlParameterSource().addValue("batch",batch).addValue("code",p.code()).addValue("detail",p.detail().length()>1000?p.detail().substring(0,1000):p.detail()).addValue("field",p.header().length()>200?p.header().substring(0,200):p.header()));
            if(!each.isEmpty())db.batchUpdate("INSERT INTO import_exceptions(batch_id,error_code,detail,field_name) VALUES (:batch,:code,:detail,:field)",each.toArray(SqlParameterSource[]::new));
            return batch;
        });
    }
}
