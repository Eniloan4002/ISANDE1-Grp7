package ph.edu.ici.imports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.time.LocalDate;
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
import ph.edu.ici.enrollment.IdentityMatcher;
import ph.edu.ici.imports.ImportFileException.FileError;

/**
 * Stages and validates a legacy student CSV (always a dry run): writes import_batches, import_rows and import_exceptions,
 * never students. Rows that look like existing or staged people are flagged, not inserted.
 */
@Service
public class ImportValidationService {
    private static final int CHUNK=5_000,MAX_EVIDENCE=20,MAX_DUPLICATE_MESSAGES=10;
    private final NamedParameterJdbcTemplate db;private final ObjectMapper json;private final AccessPolicy access;
    private final ImportMappingService mappings;private final ImportLimits limits;private final TransactionTemplate tx;private final GridReader reader;

    public record Staged(long batchId,String status,int sourceCount,int valid,int invalid,int flagged,int skipped) {}
    private static final class Row {
        final int number;final Map<String,String> raw;Map<String,Object> values;final List<RowProblem> problems=new ArrayList<>();
        Long fileCampusId;final CleaningLog cleaning=new CleaningLog();final List<Map<String,Object>> evidence=new ArrayList<>();final Set<Long> matchedStudents=new HashSet<>();String status;
        Row(int number,Map<String,String> raw){this.number=number;this.raw=raw;}
        boolean eligible(){return problems.stream().noneMatch(p->RowProblem.ERROR.equals(p.severity()));}
        Map<String,Object> identity(){Map<String,Object> m=new HashMap<>();m.put("lrn",values.get("lrn"));m.put("birth_date",LocalDate.parse((String)values.get("birthDate")));m.put("first_name",values.get("firstName"));m.put("last_name",values.get("lastName"));return m;}
    }
    private record Candidate(String kind,long id,Long studentId,Long batchId,Integer rowNumber,Long campusId,Map<String,Object> identity) {}

    public ImportValidationService(NamedParameterJdbcTemplate db,ObjectMapper json,AccessPolicy access,ImportMappingService mappings,ImportLimits limits,PlatformTransactionManager transactions){
        this.db=db;this.json=json;this.access=access;this.mappings=mappings;this.limits=limits;this.tx=new TransactionTemplate(transactions);this.reader=new CsvGridReader(limits);
    }
    private StaffIdentity identity(){return (StaffIdentity)SecurityContextHolder.getContext().getAuthentication().getPrincipal();}
    private void actor(){db.queryForObject("SELECT set_config('app.actor_id',:actor,true)",Map.of("actor",Long.toString(identity().userId())),String.class);}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private static String checksum(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String safeName(String name){String s=name==null?"":name.replaceAll("^.*[\\\\/]","").replaceAll("\\p{Cc}","").trim();if(s.isEmpty())s="upload.csv";return s.length()>255?s.substring(0,255):s;}

    /** Parse, validate and stage one file for one campus. Throws {@link ImportFileException} (413/422) or {@link ImportConflictException} (409). */
    public Staged stage(long campusId,String fileName,byte[] bytes,Long mappingId){
        if(!access.campuses("imports.manage").contains(campusId))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Your account cannot import students for this campus.");
        if(bytes.length>limits.maxFileBytes())throw new ImportFileException(FileError.FILE_TOO_LARGE,"The file is larger than "+limits.maxFileBytes()+" bytes.");
        String hash=checksum(bytes);
        var existing=db.queryForList("SELECT id,status FROM import_batches WHERE source_type=:s AND checksum=:c AND status<>'failed'",Map.of("s",ImportMappingService.SOURCE,"c",hash));
        if(!existing.isEmpty())throw new ImportConflictException(((Number)existing.getFirst().get("id")).longValue(),(String)existing.getFirst().get("status"));
        var stored=mappings.forUpload(mappingId);var mapping=ImportMapping.parse(stored.mapping(),stored.settings());var snapshot=encode(mappings.snapshot(stored));
        var ref=reference();
        if(ref.campuses().values().stream().noneMatch(c->c.id()==campusId))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Select an existing campus.");
        String name=safeName(fileName);
        CsvParser.Parsed parsed;Map<String,Integer> columns;
        try{parsed=reader.read(bytes,mapping.settings());columns=HeaderResolver.resolve(parsed.headers(),mapping);}
        catch(ImportFileException e){
            if(e.error().recorded)e.batchId(recordFailure(campusId,name,hash,bytes.length,stored.id(),snapshot,e));
            throw e;
        }
        var normalizer=new RowNormalizer(mapping,ref,campusId,LocalDate.now(),parsed.headers());
        List<Row> rows=new ArrayList<>();
        for(var record:parsed.rows()){
            Map<String,String> raw=new LinkedHashMap<>();for(int i=0;i<record.cells().size()&&i<parsed.headers().size();i++)raw.put(parsed.headers().get(i),record.cells().get(i));
            var row=new Row(record.number(),raw);var result=normalizer.normalize(record.cells(),columns,record.meta(),row.cleaning);
            row.values=result.values();row.problems.addAll(result.problems());row.fileCampusId=result.fileCampusId();rows.add(row);
        }
        Set<Long> visible=new HashSet<>(access.campuses("imports.read"));visible.addAll(access.campuses("imports.manage"));
        detectDuplicates(rows,campusId,visible);
        for(Row r:rows)r.status=!r.eligible()?"invalid":r.evidence.isEmpty()?"valid":"duplicate_flagged";
        try{return tx.execute(s->persist(campusId,name,hash,bytes.length,stored.id(),snapshot,rows,new HashSet<>(access.campuses("imports.manage"))));}
        catch(DuplicateKeyException e){
            var raced=db.queryForList("SELECT id,status FROM import_batches WHERE source_type=:s AND checksum=:c AND status<>'failed'",Map.of("s",ImportMappingService.SOURCE,"c",hash));
            if(raced.isEmpty())throw e;throw new ImportConflictException(((Number)raced.getFirst().get("id")).longValue(),(String)raced.getFirst().get("status"));
        }
    }

    private ReferenceData reference(){
        Map<String,ReferenceData.Ref> campuses=new HashMap<>(),programs=new HashMap<>(),vouchers=new HashMap<>();
        for(var r:db.getJdbcTemplate().queryForList("SELECT id,code FROM campuses"))campuses.put(((String)r.get("code")).toLowerCase(Locale.ROOT),new ReferenceData.Ref(((Number)r.get("id")).longValue(),(String)r.get("code"),null));
        for(var r:db.getJdbcTemplate().queryForList("SELECT id,code,category FROM programs"))programs.put(((String)r.get("code")).toLowerCase(Locale.ROOT),new ReferenceData.Ref(((Number)r.get("id")).longValue(),(String)r.get("code"),(String)r.get("category")));
        for(var r:db.getJdbcTemplate().queryForList("SELECT code FROM vouchers"))vouchers.put(((String)r.get("code")).toLowerCase(Locale.ROOT),new ReferenceData.Ref(0,(String)r.get("code"),null));
        return new ReferenceData(campuses,programs,vouchers);
    }

    // ---- duplicate detection -------------------------------------------------------------------------------------------

    private static LocalDate date(Object value){return value instanceof java.sql.Date d?d.toLocalDate():(LocalDate)value;}
    private static <T> List<List<T>> chunks(Collection<T> values){List<T> all=new ArrayList<>(values);List<List<T>> out=new ArrayList<>();for(int i=0;i<all.size();i+=CHUNK)out.add(all.subList(i,Math.min(all.size(),i+CHUNK)));return out;}
    /** Historical students may have no campus; their campus is then never visible to a reviewer. */
    private static Long campus(Map<String,Object> m){return m.get("campus_id")==null?null:((Number)m.get("campus_id")).longValue();}
    private Map<String,Object> identityOf(Map<String,Object> r){Map<String,Object> m=new HashMap<>();m.put("lrn",r.get("lrn"));m.put("birth_date",date(r.get("birth_date")));m.put("first_name",r.get("first_name"));m.put("last_name",r.get("last_name"));return m;}

    private void detectDuplicates(List<Row> rows,long campusId,Set<Long> visible){
        List<Row> eligible=rows.stream().filter(Row::eligible).toList();if(eligible.isEmpty())return;
        existing(eligible,campusId,visible,null);
        inFile(eligible);
    }
    /** A row that conflicts with records written after staging, found when it is about to be committed. */
    record Recheck(String status,List<RowProblem> problems,List<Map<String,Object>> evidence) {}
    record Pending(long rowId,Map<String,Object> values) {}
    /**
     * Commit-time recheck of rows about to be inserted. Only students/applications changed after {@code since} can newly match
     * (earlier matches were flagged and reviewed at staging); student-number ownership is always rechecked.
     * @return row id to its new outcome, only for rows that now conflict
     */
    Map<Long,Recheck> recheck(long campusId,Set<Long> visible,java.time.Instant since,List<Pending> pending){
        List<Row> rows=new ArrayList<>();Map<Row,Long> ids=new IdentityHashMap<>();
        for(var p:pending){var row=new Row(0,Map.of());row.values=p.values();rows.add(row);ids.put(row,p.rowId());}
        existing(rows,campusId,visible,since);
        Map<Long,Recheck> out=new LinkedHashMap<>();
        for(Row r:rows){
            boolean invalid=!r.eligible();
            if(invalid||!r.evidence.isEmpty()){
                for(var e:r.evidence)if(r.problems.size()<MAX_DUPLICATE_MESSAGES)r.problems.add(RowProblem.warning("DUPLICATE_CANDIDATE",null,describe(e)));
                out.put(ids.get(r),new Recheck(invalid?"invalid":"duplicate_flagged",r.problems,r.evidence));
            }
        }
        return out;
    }
    /** since==null: staging (all existing records plus other campuses' staged rows). Otherwise commit recheck. */
    private void existing(List<Row> eligible,long campusId,Set<Long> visible,java.time.Instant since){
        Set<String> lrns=new LinkedHashSet<>();Set<LocalDate> births=new LinkedHashSet<>();Set<String> numbers=new LinkedHashSet<>();
        for(Row r:eligible){if(r.values.get("lrn")!=null)lrns.add((String)r.values.get("lrn"));births.add(LocalDate.parse((String)r.values.get("birthDate")));numbers.add((String)r.values.get("studentNumber"));}

        Map<String,Candidate> found=new LinkedHashMap<>();
        String f=since==null?"":" AND updated_at > :since";
        for(var part:chunks(lrns))for(var m:db.queryForList("SELECT id,campus_id,lrn,first_name,last_name,birth_date FROM students WHERE lrn IN (:v)"+f,params(part,since)))found.putIfAbsent("s"+m.get("id"),new Candidate("student",((Number)m.get("id")).longValue(),((Number)m.get("id")).longValue(),null,null,campus(m),identityOf(m)));
        for(var part:chunks(births))for(var m:db.queryForList("SELECT id,campus_id,lrn,first_name,last_name,birth_date FROM students WHERE birth_date IN (:v)"+f,params(part,since)))found.putIfAbsent("s"+m.get("id"),new Candidate("student",((Number)m.get("id")).longValue(),((Number)m.get("id")).longValue(),null,null,campus(m),identityOf(m)));
        for(var part:chunks(lrns))for(var m:db.queryForList("SELECT id,campus_id,student_id,lrn,first_name,last_name,birth_date FROM applications WHERE status NOT IN ('rejected','withdrawn') AND lrn IN (:v)"+f,params(part,since)))found.putIfAbsent("a"+m.get("id"),application(m));
        for(var part:chunks(births))for(var m:db.queryForList("SELECT id,campus_id,student_id,lrn,first_name,last_name,birth_date FROM applications WHERE status NOT IN ('rejected','withdrawn') AND birth_date IN (:v)"+f,params(part,since)))found.putIfAbsent("a"+m.get("id"),application(m));
        // Uncommitted batches for other campuses: the same learner may appear in two campuses' exports before either is committed.
        List<String> birthText=births.stream().map(LocalDate::toString).toList();
        if(since==null)for(var part:chunks(lrns))for(var m:db.queryForList(stagedSql("r.normalized_values->>'lrn' IN (:v)"),Map.of("v",part,"campus",campusId)))found.putIfAbsent("r"+m.get("batch_id")+"/"+m.get("row_number"),staged(m));
        if(since==null)for(var part:chunks(birthText))for(var m:db.queryForList(stagedSql("r.normalized_values->>'birthDate' IN (:v)"),Map.of("v",part,"campus",campusId)))found.putIfAbsent("r"+m.get("batch_id")+"/"+m.get("row_number"),staged(m));

        Map<String,List<Candidate>> byLrn=new HashMap<>();Map<LocalDate,List<Candidate>> byBirth=new HashMap<>();
        for(var c:found.values()){if(c.identity().get("lrn")!=null)byLrn.computeIfAbsent((String)c.identity().get("lrn"),k->new ArrayList<>()).add(c);byBirth.computeIfAbsent((LocalDate)c.identity().get("birth_date"),k->new ArrayList<>()).add(c);}
        for(Row r:eligible){
            var identity=r.identity();
            Set<Candidate> near=new LinkedHashSet<>();near.addAll(byLrn.getOrDefault((String)r.values.get("lrn"),List.of()));near.addAll(byBirth.getOrDefault((LocalDate)identity.get("birth_date"),List.of()));
            List<Candidate> hits=new ArrayList<>();for(var c:near)if(IdentityMatcher.matches(identity,c.identity()))hits.add(c);
            for(var c:hits)if(c.studentId()!=null&&c.kind().equals("student"))r.matchedStudents.add(c.id());
            for(var c:hits){
                if(c.kind().equals("application")&&c.studentId()!=null&&r.matchedStudents.contains(c.studentId()))continue;
                if(r.evidence.size()<MAX_EVIDENCE)r.evidence.add(evidence(c.kind(),c.id(),c.batchId(),c.rowNumber(),IdentityMatcher.rule(identity,c.identity()),IdentityMatcher.nameScore(identity,c.identity()),c.campusId()!=null&&visible.contains(c.campusId())));
            }
        }
        // A student number already used by a different person cannot be imported; the same person is already flagged above.
        Map<String,Long> taken=new HashMap<>();
        for(var part:chunks(numbers))for(var m:db.queryForList("SELECT id,student_number FROM students WHERE student_number IN (:v)",Map.of("v",part)))taken.put((String)m.get("student_number"),((Number)m.get("id")).longValue());
        for(Row r:eligible){Long owner=taken.get((String)r.values.get("studentNumber"));
            if(owner!=null&&!r.matchedStudents.contains(owner)){r.problems.add(RowProblem.error("STUDENT_NUMBER_CONFLICT","studentNumber","This student number already belongs to a different student."));r.evidence.clear();}}
    }
    private static Map<String,Object> params(List<?> part,java.time.Instant since){Map<String,Object> m=new HashMap<>();m.put("v",part);if(since!=null)m.put("since",java.sql.Timestamp.from(since));return m;}
    private void inFile(List<Row> eligible){
        // The same number twice in one file is only acceptable when both rows describe the same person (then both are flagged below).
        Map<String,Row> firstByNumber=new HashMap<>();
        for(Row r:eligible){if(!r.eligible())continue;String number=(String)r.values.get("studentNumber");var first=firstByNumber.putIfAbsent(number,r);
            if(first!=null&&!IdentityMatcher.matches(first.identity(),r.identity())){r.problems.add(RowProblem.error("DUPLICATE_IN_BATCH","studentNumber","This student number was already used on row "+first.number+" for a different person."));r.evidence.clear();}}
        // Same person more than once in the file (compare only rows that share an LRN or birth date).
        List<Row> alive=eligible.stream().filter(Row::eligible).toList();
        Map<String,List<Row>> rowsByLrn=new HashMap<>();Map<String,List<Row>> rowsByBirth=new HashMap<>();
        for(Row r:alive){if(r.values.get("lrn")!=null)rowsByLrn.computeIfAbsent((String)r.values.get("lrn"),k->new ArrayList<>()).add(r);rowsByBirth.computeIfAbsent((String)r.values.get("birthDate"),k->new ArrayList<>()).add(r);}
        for(Row r:alive){
            var identity=r.identity();Set<Row> near=new LinkedHashSet<>();near.addAll(rowsByLrn.getOrDefault((String)r.values.get("lrn"),List.of()));near.addAll(rowsByBirth.get((String)r.values.get("birthDate")));
            for(Row other:near){
                if(other==r||!IdentityMatcher.matches(identity,other.identity())||r.evidence.size()>=MAX_EVIDENCE)continue;
                r.evidence.add(evidence("row",0,null,other.number,IdentityMatcher.rule(identity,other.identity()),IdentityMatcher.nameScore(identity,other.identity()),true));
            }
        }
        for(Row r:alive){int shown=0;for(var e:r.evidence){if(shown++>=MAX_DUPLICATE_MESSAGES)break;r.problems.add(RowProblem.warning("DUPLICATE_CANDIDATE",null,describe(e)));}}
    }
    private String stagedSql(String where){return "SELECT r.batch_id,r.row_number,b.campus_id,r.normalized_values->>'lrn' AS lrn,r.normalized_values->>'firstName' AS first_name,r.normalized_values->>'lastName' AS last_name,(r.normalized_values->>'birthDate')::date AS birth_date "+
        "FROM import_rows r JOIN import_batches b ON b.id=r.batch_id WHERE b.source_type='legacy_student_csv' AND b.status='validated' AND b.campus_id<>:campus AND r.status IN ('valid','duplicate_flagged') AND "+where;}
    private Candidate application(Map<String,Object> m){return new Candidate("application",((Number)m.get("id")).longValue(),m.get("student_id")==null?null:((Number)m.get("student_id")).longValue(),null,null,campus(m),identityOf(m));}
    private Candidate staged(Map<String,Object> m){return new Candidate("staged_row",0,null,((Number)m.get("batch_id")).longValue(),((Number)m.get("row_number")).intValue(),campus(m),identityOf(m));}
    /** Only identifiers of records in the reviewer's campuses are exposed; other campuses show rule and score only. */
    private Map<String,Object> evidence(String kind,long id,Long batchId,Integer rowNumber,String rule,double score,boolean visible){
        Map<String,Object> e=new LinkedHashMap<>();e.put("kind",kind);e.put("rule",rule);e.put("nameScore",Math.round(score*100)/100.0);e.put("campusVisible",visible);
        if(visible){if(kind.equals("student")||kind.equals("application"))e.put("id",id);if(rowNumber!=null)e.put("rowNumber",rowNumber);if(batchId!=null)e.put("batchId",batchId);}
        return e;
    }
    private String describe(Map<String,Object> e){
        String what=switch((String)e.get("kind")){case "student"->"an existing student";case "application"->"an open application";case "staged_row"->"a row staged for another campus";default->"another row in this file";};
        String how=IdentityMatcher.LRN_EXACT.equals(e.get("rule"))?"same LRN":"same birth date and similar name";
        return "Possible duplicate of "+what+" ("+how+")"+(Boolean.TRUE.equals(e.get("campusVisible"))?"":"; the record is at a campus you cannot view")+". Review before importing.";
    }

    // ---- persistence ---------------------------------------------------------------------------------------------------

    private Staged persist(long campusId,String name,String hash,long size,long mappingId,String snapshot,List<Row> rows,Set<Long> manageScope){
        actor();
        int valid=0,invalid=0,flagged=0;for(Row r:rows){switch(r.status){case "valid"->valid++;case "invalid"->invalid++;default->flagged++;}}
        long batch=db.queryForObject("INSERT INTO import_batches(source_name,checksum,status,source_count,campus_id,mapping_id,mapping_snapshot,file_size_bytes,valid_count,invalid_count,flagged_count,source_format,cleaning_summary) VALUES (:name,:hash,'validated',:count,:campus,:mapping,CAST(:snapshot AS jsonb),:size,:valid,:invalid,:flagged,'csv',CAST(:summary AS jsonb)) RETURNING id",
            new MapSqlParameterSource(Map.of("name",name,"hash",hash,"count",rows.size(),"campus",campusId,"mapping",mappingId,"snapshot",snapshot,"size",size,"valid",valid,"invalid",invalid,"flagged",flagged))
                .addValue("summary",CleaningSummary.json(rows.stream().map(r->r.cleaning).toList(),json)),Long.class);
        List<SqlParameterSource> inserts=new ArrayList<>();
        for(Row r:rows){
            boolean redact=r.fileCampusId!=null&&!manageScope.contains(r.fileCampusId);   // never retain another campus's personal data
            inserts.add(new MapSqlParameterSource().addValue("batch",batch).addValue("number",r.number).addValue("status",r.status).addValue("campus",campusId)
                .addValue("raw",encode(redact?Map.of():r.raw)).addValue("normalized",r.status.equals("invalid")?null:encode(r.values)).addValue("evidence",r.status.equals("duplicate_flagged")?encode(r.evidence):null)
                .addValue("cleaning",!redact&&r.cleaning.hasEntries()?encode(r.cleaning.entries()):null));
        }
        db.batchUpdate("INSERT INTO import_rows(batch_id,row_number,status,campus_id,raw_values,normalized_values,duplicate_evidence,cleaning) VALUES (:batch,:number,:status,:campus,CAST(:raw AS jsonb),CAST(:normalized AS jsonb),CAST(:evidence AS jsonb),CAST(:cleaning AS jsonb))",inserts.toArray(SqlParameterSource[]::new));
        Map<Integer,Long> ids=new HashMap<>();
        for(var m:db.queryForList("SELECT id,row_number FROM import_rows WHERE batch_id=:b",Map.of("b",batch)))ids.put(((Number)m.get("row_number")).intValue(),((Number)m.get("id")).longValue());
        List<SqlParameterSource> problems=new ArrayList<>();
        for(Row r:rows)for(var p:r.problems)problems.add(new MapSqlParameterSource().addValue("batch",batch).addValue("number",r.number).addValue("code",p.code()).addValue("detail",p.detail()).addValue("row",ids.get(r.number)).addValue("field",p.field()).addValue("severity",p.severity()));
        if(!problems.isEmpty())db.batchUpdate("INSERT INTO import_exceptions(batch_id,row_number,error_code,detail,import_row_id,field_name,severity) VALUES (:batch,:number,:code,:detail,:row,:field,:severity)",problems.toArray(SqlParameterSource[]::new));
        return new Staged(batch,"validated",rows.size(),valid,invalid,flagged,0);
    }

    private long recordFailure(long campusId,String name,String hash,long size,long mappingId,String snapshot,ImportFileException e){
        return tx.execute(s->{
            actor();
            long batch=db.queryForObject("INSERT INTO import_batches(source_name,checksum,status,source_count,campus_id,mapping_id,mapping_snapshot,file_size_bytes) VALUES (:name,:hash,'failed',0,:campus,:mapping,CAST(:snapshot AS jsonb),:size) RETURNING id",
                Map.of("name",name,"hash",hash,"campus",campusId,"mapping",mappingId,"snapshot",snapshot,"size",size),Long.class);
            db.update("INSERT INTO import_exceptions(batch_id,error_code,detail) VALUES (:batch,:code,:detail)",Map.of("batch",batch,"code",e.error().name(),"detail",e.getMessage().length()>1000?e.getMessage().substring(0,1000):e.getMessage()));
            return batch;
        });
    }
}
