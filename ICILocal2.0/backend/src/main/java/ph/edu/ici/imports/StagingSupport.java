package ph.edu.ici.imports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.StaffIdentity;
import ph.edu.ici.imports.ImportFileException.FileError;
import ph.edu.ici.imports.ImportFileException.Problem;

/**
 * The staging machinery the ICI2-39 import types share (section allocation, document log): duplicate-file check, upload context, header
 * classification, the identity and term columns, student resolution, and persistence of a validated or failed batch with its rows and
 * exceptions. Each type keeps only what is its own: required columns, its own fields, what a row would change, and its normalized values.
 * Messages never echo names, student numbers, LRNs or other cell values.
 */
final class StagingSupport {
    static final int MAX_PROBLEMS_PER_ROW=50;
    private final NamedParameterJdbcTemplate db;private final ObjectMapper json;private final TransactionTemplate tx;private final String source;

    StagingSupport(NamedParameterJdbcTemplate db,ObjectMapper json,TransactionTemplate tx,String source){this.db=db;this.json=json;this.tx=tx;this.source=source;}

    /** The academic year and term in effect for a file: chosen at upload, a term implying its year. */
    record Context(Long academicYearId,Long termId) {
        Map<String,Object> json(){Map<String,Object> m=new LinkedHashMap<>();if(academicYearId!=null)m.put("academicYearId",academicYearId);if(termId!=null)m.put("termId",termId);return m;}
    }
    /** Everything a recorded batch carries besides its rows. */
    record Upload(long campusId,String name,String hash,long size,long mappingId,String snapshot,String context,String format) {}
    record Persisted(long batchId,int valid,int invalid,int flagged) {}
    record Grid(CsvParser.Parsed parsed,Map<String,Integer> fields) {}

    /** One staged row: its cells, problems and cleaning log, the shared identity and term values, and its outcome ({@code valid}, {@code invalid} or {@code flagged}). */
    static class Row {
        final int number;final Map<String,String> raw=new LinkedHashMap<>();final List<String> cells;final List<RowProblem> problems=new ArrayList<>();
        final CleaningLog cleaning=new CleaningLog();final CellCleaner cleaner;final List<String> headers;final Map<Integer,CellMeta> meta;final Map<String,Integer> fields;
        private int flushed,errors,warnings;
        String studentNumber,lrn,lastName,firstName;Long termId,yearId;
        StudentResolver.Student student;String matchedBy;final List<Map<String,Object>> evidence=new ArrayList<>();
        String outcome;
        Row(CsvParser.Record record,Grid grid,CellCleaner cleaner){
            this.number=record.number();this.cells=record.cells();this.cleaner=cleaner;this.headers=grid.parsed().headers();this.meta=record.meta();this.fields=grid.fields();
            for(int i=0;i<cells.size()&&i<headers.size();i++)raw.put(headers.get(i),cells.get(i));
        }
        /** False (with COLUMN_COUNT_MISMATCH recorded) when the row is not as wide as the header. */
        boolean widthMatches(){
            if(cells.size()==headers.size())return true;
            error("COLUMN_COUNT_MISMATCH",null,"The row has "+cells.size()+" values but the header has "+headers.size()+".");return false;
        }
        boolean eligible(){return errors==0;}
        /** The cleaned value of a mapped field, or null; cleaning problems become row problems. */
        String cell(String field){
            Integer i=fields.get(field);if(i==null||i>=cells.size())return null;
            String value=cleaner.clean(cells.get(i),field,i<headers.size()?headers.get(i):null,meta.get(i),cleaning);flush();return value;
        }
        CellMeta meta(String field){Integer i=fields.get(field);return i==null?null:meta.get(i);}
        void error(String code,String field,String detail){errors++;add(RowProblem.error(code,field,detail),errors);}
        void warn(String code,String field,String detail){warnings++;add(RowProblem.warning(code,field,detail),warnings);}
        void add(RowProblem p){if(RowProblem.ERROR.equals(p.severity()))error(p.code(),p.field(),p.detail());else warn(p.code(),p.field(),p.detail());}
        private void flush(){for(;flushed<cleaning.problems().size();flushed++){var p=cleaning.problems().get(flushed);if(RowProblem.ERROR.equals(p.severity()))error(p.code(),p.field(),p.detail());else warn(p.code(),p.field(),p.detail());}}
        private void add(RowProblem p,int count){if(count<=MAX_PROBLEMS_PER_ROW)problems.add(p);else if(count==MAX_PROBLEMS_PER_ROW+1)problems.add(RowProblem.warning("MORE_PROBLEMS",null,"More "+p.severity()+"s were found on this row than are listed."));}
    }

    private StaffIdentity identity(){return (StaffIdentity)SecurityContextHolder.getContext().getAuthentication().getPrincipal();}
    private void actor(){db.queryForObject("SELECT set_config('app.actor_id',:actor,true)",Map.of("actor",Long.toString(identity().userId())),String.class);}
    String encode(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    static String checksum(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    static String safeName(String name){String s=name==null?"":name.replaceAll("^.*[\\\\/]","").replaceAll("\\p{Cc}","").trim();if(s.isEmpty())s="upload";return s.length()>255?s.substring(0,255):s;}
    static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}

    // ---- before reading ------------------------------------------------------------------------------------------------

    /** File size and campus checks, then the checksum; a batch with the same bytes that is not failed (a discarded batch is failed) blocks re-upload. */
    String admit(long campusId,byte[] bytes,ImportLimits limits){
        if(bytes.length>limits.maxFileBytes())throw new ImportFileException(FileError.FILE_TOO_LARGE,"The file is larger than "+limits.maxFileBytes()+" bytes.");
        if(Boolean.FALSE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM campuses WHERE id=:id)",Map.of("id",campusId),Boolean.class)))throw bad("Select an existing campus.");
        String hash=checksum(bytes);
        var existing=liveBatch(hash);
        if(existing!=null)throw new ImportConflictException(((Number)existing.get("id")).longValue(),(String)existing.get("status"));
        return hash;
    }
    private Map<String,Object> liveBatch(String hash){
        var rows=db.queryForList("SELECT id,status FROM import_batches WHERE source_type=:s AND checksum=:c AND status<>'failed' AND voided_at IS NULL",Map.of("s",source,"c",hash));
        return rows.isEmpty()?null:rows.getFirst();
    }

    Context context(Long year,Long term,GradeReference ref){
        if(year!=null&&!ref.yearsById().containsKey(year))throw bad("Select an existing academic year.");
        if(term!=null){
            var t=ref.termsById().get(term);if(t==null)throw bad("Select an existing term.");
            if(year!=null&&year!=t.yearId())throw bad("The selected term does not belong to the selected academic year.");
            year=t.yearId();
        }
        return new Context(year,term);
    }

    /** Reads and classifies the file; a file error that is recorded (422) leaves a failed batch whose id the exception carries. */
    Grid read(Upload upload,Supplier<CsvParser.Parsed> read,Function<CsvParser.Parsed,Map<String,Integer>> classify){
        try{var parsed=read.get();return new Grid(parsed,classify.apply(parsed));}
        catch(ImportFileException e){if(e.error().recorded)e.batchId(recordFailure(upload,e));throw e;}
    }

    // ---- file-level classification -------------------------------------------------------------------------------------

    /**
     * Every non-blank header must be a field alias or an ignored column, otherwise the whole file fails: a repeated header row, an unclassified
     * column (each one listed), a column claimed twice or a field matched by two columns. Required columns are the caller's check.
     * @param repeated message for a repeated header row ("Row N repeats the header row. " is prefixed)
     * @param unmappedHint appended to each unclassified column's detail (the unsupported layout it usually means)
     */
    Map<String,Integer> classify(CsvParser.Parsed parsed,List<String> fieldOrder,Map<String,ImportMapping.FieldSpec> specs,Set<String> ignoredColumns,String repeated,String unmappedHint){
        int width=parsed.headers().size();
        List<String> headerKeys=parsed.headers().stream().map(ImportText::header).toList();
        if(headerKeys.stream().filter(k->!k.isEmpty()).count()>=2)
            for(var r:parsed.rows())if(r.cells().size()==width&&r.cells().stream().map(ImportText::header).toList().equals(headerKeys))
                throw new ImportFileException(FileError.REPEATED_HEADER,"Row "+r.number()+" repeats the header row. "+repeated);
        Map<String,List<String>> owners=new HashMap<>();
        for(String field:fieldOrder){
            var spec=specs.get(field);if(spec==null)continue;
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
            List<String> fieldOwners=owners.getOrDefault(key,List.of());boolean ignored=ignoredColumns.contains(key);
            if(fieldOwners.size()>1||(!fieldOwners.isEmpty()&&ignored))
                throw new ImportFileException(FileError.AMBIGUOUS_HEADER,"Column '"+shown+"' is claimed by more than one classification: "+(fieldOwners.size()>1?fieldOwners:List.of(fieldOwners.getFirst(),"ignoredColumns"))+".");
            if(ignored)continue;
            if(fieldOwners.isEmpty()){unmapped.add(new Problem("UNMAPPED_COLUMN",shown,"This column is neither a field of the mapping nor listed in ignoredColumns. "+unmappedHint));continue;}
            String field=fieldOwners.getFirst();Integer previous=fields.putIfAbsent(field,i);
            if(previous!=null)throw new ImportFileException(FileError.AMBIGUOUS_HEADER,"More than one column matches "+field+": '"+GradeMatrixHeaders.label(parsed.headers().get(previous),previous)+"' and '"+shown+"'.");
        }
        if(!unmapped.isEmpty())throw new ImportFileException(FileError.UNMAPPED_COLUMN,"Columns the mapping neither classifies nor lists in ignoredColumns (classify or ignore each): "+unmapped.stream().map(p->"'"+p.header()+"'").toList()+".",unmapped);
        return fields;
    }

    // ---- shared columns ------------------------------------------------------------------------------------------------

    /** Control characters in any mapped column except {@code exempt}, then student number, LRN and names with their length and format rules. */
    static void identity(Row r,Set<String> exempt){
        for(var e:r.fields.entrySet()){int i=e.getValue();if(!exempt.contains(e.getKey())&&i<r.cells.size()&&ImportText.hasControl(r.cells.get(i)))r.error("INVALID_CHARACTER",e.getKey(),"The value contains control characters.");}
        r.studentNumber=r.cell("studentNumber");r.lrn=r.cell("lrn");r.lastName=r.cell("lastName");r.firstName=r.cell("firstName");
        if(r.studentNumber!=null&&r.studentNumber.length()>40){r.error("TOO_LONG","studentNumber","The student number is longer than 40 characters.");r.studentNumber=null;}
        if(r.lrn!=null&&!r.lrn.matches("[0-9]{12}")){r.error("INVALID_LRN","lrn","LRN must be exactly 12 digits.");r.lrn=null;}
        if(r.lastName!=null&&r.lastName.length()>150){r.error("TOO_LONG","lastName","The name is longer than 150 characters.");r.lastName=null;}
        if(r.firstName!=null&&r.firstName.length()>150){r.error("TOO_LONG","firstName","The name is longer than 150 characters.");r.firstName=null;}
    }

    /** The row's school year and term: from the upload, or from the schoolYear and term columns through their value maps; both present must agree. */
    static void term(Row r,Map<String,Map<String,String>> valueMaps,Context ctx,GradeReference ref){
        Long year=ctx.academicYearId(),term=ctx.termId();boolean bad=false;
        String yearCell=r.cell("schoolYear");
        if(yearCell!=null){
            String mapped=valueMaps.get("schoolYear").get(ImportText.value(yearCell));
            var y=ref.yearsByLabel().get(GradeReference.key(mapped!=null?mapped:yearCell));
            if(y==null){r.error("UNKNOWN_SCHOOL_YEAR","schoolYear","The school year is not an existing academic year and is not in the mapping's schoolYear value map.");bad=true;}
            else if(year!=null&&year!=y.id()){r.error("CONTEXT_MISMATCH","schoolYear","The school year in the row differs from the one chosen at upload.");bad=true;}
            else year=y.id();
        }
        String termCell=r.cell("term");
        if(termCell!=null&&!bad){
            if(year==null)r.error("REQUIRED_MISSING","schoolYear","A term value needs a school year, from the file or the upload.");
            else{
                String mapped=valueMaps.get("term").get(ImportText.value(termCell));
                var t=ref.termsByYear().getOrDefault(year,Map.of()).get(GradeReference.key(mapped!=null?mapped:termCell));
                if(t==null)r.error("UNKNOWN_TERM","term","The term is not an existing term of that academic year and is not in the mapping's term value map.");
                else if(term!=null&&term!=t.id())r.error("CONTEXT_MISMATCH","term","The term in the row differs from the one chosen at upload.");
                else term=t.id();
            }
        }
        r.termId=term;r.yearId=year;
    }

    /**
     * Existing students of the batch's campus only, exactly as in ICI2-38 ({@link StudentResolver}).
     * @param inactiveIsError an archived student is an error (it cannot hold a seat) instead of the resolver's warning
     */
    void resolveStudents(List<? extends Row> rows,long campusId,boolean inactiveIsError){
        var outcomes=new StudentResolver(db).resolve(campusId,rows.stream().map(r->new StudentResolver.Query(r.studentNumber,r.lrn,r.lastName,r.firstName)).toList());
        for(int i=0;i<rows.size();i++){
            Row r=rows.get(i);var o=outcomes.get(i);
            r.evidence.addAll(o.evidence());
            for(RowProblem p:o.problems()){if(inactiveIsError&&"STUDENT_INACTIVE".equals(p.code()))continue;r.add(p);}
            r.student=o.student();r.matchedBy=o.matchedBy();
            if(inactiveIsError&&r.student!=null&&!r.student.active())r.error("STUDENT_INACTIVE",null,"The matched student is inactive (archived) and cannot be placed in a section.");
        }
    }

    // ---- persistence ---------------------------------------------------------------------------------------------------

    /**
     * Stores a validated batch, its rows and their exceptions in one transaction. Normalized values are kept for rows that are not invalid;
     * evidence (the row's identity evidence plus {@code extraEvidence}) only for flagged rows. A concurrent upload of the same bytes is a 409.
     */
    <R extends Row> Persisted save(Upload u,List<R> rows,Function<R,Map<String,Object>> normalized,Function<R,List<Map<String,Object>>> extraEvidence){
        try{return tx.execute(s->persist(u,rows,normalized,extraEvidence));}
        catch(DuplicateKeyException e){
            var raced=liveBatch(u.hash());if(raced==null)throw e;
            throw new ImportConflictException(((Number)raced.get("id")).longValue(),(String)raced.get("status"));
        }
    }
    private <R extends Row> Persisted persist(Upload u,List<R> rows,Function<R,Map<String,Object>> normalized,Function<R,List<Map<String,Object>>> extraEvidence){
        actor();
        int valid=0,invalid=0,flagged=0;
        for(Row r:rows)switch(r.outcome){case "valid"->valid++;case "invalid"->invalid++;default->flagged++;}
        long batch=db.queryForObject("INSERT INTO import_batches(source_name,checksum,status,source_count,campus_id,mapping_id,mapping_snapshot,file_size_bytes,valid_count,invalid_count,flagged_count,source_type,upload_context,source_format,cleaning_summary) VALUES (:name,:hash,'validated',:count,:campus,:mapping,CAST(:snapshot AS jsonb),:size,:valid,:invalid,:flagged,:source,CAST(:context AS jsonb),:format,CAST(:summary AS jsonb)) RETURNING id",
            new MapSqlParameterSource(Map.of("name",u.name(),"hash",u.hash(),"count",rows.size(),"campus",u.campusId(),"mapping",u.mappingId(),"snapshot",u.snapshot(),"size",u.size(),"valid",valid,"invalid",invalid,"flagged",flagged))
                .addValue("source",source).addValue("context",u.context()).addValue("format",u.format()).addValue("summary",CleaningSummary.json(rows.stream().map(r->r.cleaning).toList(),json)),Long.class);
        List<SqlParameterSource> inserts=new ArrayList<>();
        for(R r:rows){
            boolean isFlagged=r.outcome.equals("flagged"),isInvalid=r.outcome.equals("invalid");
            List<Map<String,Object>> evidence=new ArrayList<>(r.evidence);if(isFlagged)evidence.addAll(extraEvidence.apply(r));
            inserts.add(new MapSqlParameterSource().addValue("batch",batch).addValue("number",r.number).addValue("status",isFlagged?"duplicate_flagged":r.outcome).addValue("campus",u.campusId()).addValue("source",source)
                .addValue("raw",encode(r.raw)).addValue("normalized",isInvalid?null:encode(normalized.apply(r))).addValue("evidence",isFlagged?encode(evidence):null)
                .addValue("cleaning",r.cleaning.hasEntries()?encode(r.cleaning.entries()):null));
        }
        db.batchUpdate("INSERT INTO import_rows(batch_id,row_number,status,campus_id,source_type,raw_values,normalized_values,duplicate_evidence,cleaning) VALUES (:batch,:number,:status,:campus,:source,CAST(:raw AS jsonb),CAST(:normalized AS jsonb),CAST(:evidence AS jsonb),CAST(:cleaning AS jsonb))",inserts.toArray(SqlParameterSource[]::new));
        Map<Integer,Long> ids=new HashMap<>();
        for(var m:db.queryForList("SELECT id,row_number FROM import_rows WHERE batch_id=:b",Map.of("b",batch)))ids.put(((Number)m.get("row_number")).intValue(),((Number)m.get("id")).longValue());
        List<SqlParameterSource> problems=new ArrayList<>();
        for(Row r:rows)for(var p:r.problems)problems.add(new MapSqlParameterSource().addValue("batch",batch).addValue("number",r.number).addValue("code",p.code()).addValue("detail",p.detail()).addValue("row",ids.get(r.number))
            .addValue("field",p.field()==null?null:p.field().length()>200?p.field().substring(0,200):p.field()).addValue("severity",p.severity()));
        if(!problems.isEmpty())db.batchUpdate("INSERT INTO import_exceptions(batch_id,row_number,error_code,detail,import_row_id,field_name,severity) VALUES (:batch,:number,:code,:detail,:row,:field,:severity)",problems.toArray(SqlParameterSource[]::new));
        return new Persisted(batch,valid,invalid,flagged);
    }

    /** A failed batch with one summary exception and one exception per named header, so an administrator sees every header to classify or ignore. */
    private long recordFailure(Upload u,ImportFileException e){
        return tx.execute(s->{
            actor();
            long batch=db.queryForObject("INSERT INTO import_batches(source_name,checksum,status,source_count,campus_id,mapping_id,mapping_snapshot,file_size_bytes,source_type,upload_context,source_format) VALUES (:name,:hash,'failed',0,:campus,:mapping,CAST(:snapshot AS jsonb),:size,:source,CAST(:context AS jsonb),:format) RETURNING id",
                new MapSqlParameterSource(Map.of("name",u.name(),"hash",u.hash(),"campus",u.campusId(),"mapping",u.mappingId(),"snapshot",u.snapshot(),"size",u.size())).addValue("source",source).addValue("context",u.context()).addValue("format",u.format()),Long.class);
            String message=e.getMessage();
            db.update("INSERT INTO import_exceptions(batch_id,error_code,detail) VALUES (:batch,:code,:detail)",Map.of("batch",batch,"code",e.error().name(),"detail",message.length()>1000?message.substring(0,1000):message));
            List<SqlParameterSource> each=new ArrayList<>();
            for(var p:e.problems())each.add(new MapSqlParameterSource().addValue("batch",batch).addValue("code",p.code()).addValue("detail",p.detail().length()>1000?p.detail().substring(0,1000):p.detail()).addValue("field",p.header().length()>200?p.header().substring(0,200):p.header()));
            if(!each.isEmpty())db.batchUpdate("INSERT INTO import_exceptions(batch_id,error_code,detail,field_name) VALUES (:batch,:code,:detail,:field)",each.toArray(SqlParameterSource[]::new));
            return batch;
        });
    }
}
