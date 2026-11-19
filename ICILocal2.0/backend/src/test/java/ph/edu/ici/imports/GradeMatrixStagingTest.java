package ph.edu.ici.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.PostgresSupport;
import ph.edu.ici.auth.AccessPolicy;
import ph.edu.ici.auth.StaffIdentity;
import ph.edu.ici.imports.GradeMatrixValidationService.UploadContext;
import ph.edu.ici.imports.ImportFileException.FileError;
import ph.edu.ici.imports.ImportMappingService.MappingInput;
import static org.assertj.core.api.Assertions.*;

/** Grade matrix parsing, mapping and staging (ICI2-38 phase 2) against embedded PostgreSQL. All data is synthetic; the layout is assumed. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class GradeMatrixStagingTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->PostgresSupport.DATABASE.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");r.add("ici.auth.secret",()->PostgresSupport.SECRET);r.add("ici.bootstrap.enabled",()->false);
    }
    @Autowired JdbcTemplate db;@Autowired NamedParameterJdbcTemplate named;@Autowired ObjectMapper json;@Autowired AccessPolicy access;@Autowired PlatformTransactionManager transactions;
    @Autowired GradeMatrixValidationService service;@Autowired GradeMatrixMappingService mappings;@Autowired ImportMappingService studentMappings;
    long iligan,bugo,registrar,bugoRegistrar,admin,teacher;

    @BeforeEach void users(){
        iligan=db.queryForObject("SELECT id FROM campuses WHERE code='ILIGAN'",Long.class);bugo=db.queryForObject("SELECT id FROM campuses WHERE code='BUGO'",Long.class);
        registrar=user("ROLE_REGISTRAR",iligan);bugoRegistrar=user("ROLE_REGISTRAR",bugo);admin=user("ROLE_ADMIN",0);teacher=user("ROLE_TEACHER",iligan);as(registrar);
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    long user(String role,long campus){
        String name="grades-"+UUID.randomUUID();
        long id=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES (?,?,?,'unused') RETURNING id",Long.class,name,name+"@example.invalid","Synthetic staff");
        if(role!=null&&campus>0)db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,?,?)",id,role,campus);
        if(role!=null&&campus==0)db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) SELECT ?,?,id FROM campuses",id,role);
        return id;
    }
    void as(long id){var identity=new StaffIdentity(id,"u"+id,"Synthetic staff",List.of(),UUID.randomUUID(),Instant.now().plusSeconds(600),false);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(identity,null,List.of()));}

    // ---- synthetic setup -----------------------------------------------------------------------------------------------
    record Setup(String n,long year,long yearB,long s1,long s2,long b1,long q1,long q2,long math,long sci,long program){}
    String n(){return String.format("%08d",ThreadLocalRandom.current().nextInt(100_000_000));}
    byte[] fixture(String name,String n){return fixture(name,n,StandardCharsets.UTF_8);}
    byte[] fixture(String name,String n,Charset cs){
        try{String letters=ImportFixtures.letters(n);return new String(new ClassPathResource("import/"+name).getContentAsByteArray(),cs).replace("%N%",n).replace("%L%",letters).replace("%U%",letters.toUpperCase()).getBytes(cs);}
        catch(IOException e){throw new IllegalStateException(e);}
    }
    byte[] text(String csv){return csv.getBytes(StandardCharsets.UTF_8);}
    long term(long year,String code,String start,String end){return db.queryForObject("INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES (?,?,?,?::date,?::date) RETURNING id",Long.class,year,code,"Synthetic "+code,start,end);}
    long period(long term,String code,int sequence){return db.queryForObject("INSERT INTO grading_periods(term_id,code,sequence) VALUES (?,?,?) RETURNING id",Long.class,term,code,sequence);}
    Setup setup(){
        String n=n();
        long year=db.queryForObject("INSERT INTO academic_years(label,starts_on,ends_on) VALUES (?,'2018-06-01','2019-03-31') RETURNING id",Long.class,"SY"+n);
        long yearB=db.queryForObject("INSERT INTO academic_years(label,starts_on,ends_on) VALUES (?,'2017-06-01','2018-03-31') RETURNING id",Long.class,"SY"+n+"B");
        long s1=term(year,"S1","2018-06-01","2018-10-31"),s2=term(year,"S2","2018-11-01","2019-03-31"),b1=term(yearB,"S1","2017-06-01","2017-10-31");
        long q1=period(s1,"Q1",1),q2=period(s1,"Q2",2);period(s2,"Q1",1);period(s2,"Q2",2);period(b1,"Q1",1);period(b1,"Q2",2);
        long math=db.queryForObject("INSERT INTO subjects(code,name) VALUES (?,'Synthetic mathematics') RETURNING id",Long.class,"SYNMATH"+n);
        long sci=db.queryForObject("INSERT INTO subjects(code,name) VALUES (?,'Synthetic science') RETURNING id",Long.class,"SYNSCI"+n);
        long program=db.queryForObject("SELECT id FROM programs WHERE code='STEM'",Long.class);
        return new Setup(n,year,yearB,s1,s2,b1,q1,q2,math,sci,program);
    }
    long student(String number,String lrn,String first,String last,long campus,boolean active){
        return db.queryForObject("INSERT INTO students(student_number,lrn,first_name,last_name,birth_date,campus_id,active) VALUES (?,?,?,?,'2009-01-01',?,?) RETURNING id",Long.class,number,lrn,first,last,campus,active);
    }
    long student(Setup s,String id){return student("SYN-"+s.n()+"-"+id,null,"Stu"+id,"Syn"+id+ImportFixtures.letters(s.n()),iligan,true);}
    UploadContext term(Setup s){return new UploadContext(null,s.s1(),"Grade 7",null);}
    GradeMatrixValidationService.Staged stage(Setup s,byte[] bytes){return service.stageGradeMatrix(iligan,term(s),"synthetic-grades.csv",bytes,null);}
    GradeMatrixValidationService.Staged stage(Setup s,byte[] bytes,UploadContext ctx,Long mapping){return service.stageGradeMatrix(iligan,ctx,"synthetic-grades.csv",bytes,mapping);}
    byte[] withNewline(byte[] bytes){return (new String(bytes,StandardCharsets.UTF_8)+"\r\n").getBytes(StandardCharsets.UTF_8);}
    ImportFileException fileError(Setup s,byte[] bytes){return catchThrowableOfType(ImportFileException.class,()->stage(s,bytes));}
    ImportFileException fileError(Setup s,byte[] bytes,UploadContext ctx,Long mapping){return catchThrowableOfType(ImportFileException.class,()->stage(s,bytes,ctx,mapping));}

    Map<String,Object> row(long batch,int rowNumber){return db.queryForMap("SELECT * FROM import_rows WHERE batch_id=? AND row_number=?",batch,rowNumber);}
    String status(long batch,int rowNumber){return (String)row(batch,rowNumber).get("status");}
    List<String> codes(long batch,int rowNumber){return db.queryForList("SELECT error_code FROM import_exceptions WHERE batch_id=? AND row_number=? ORDER BY id",String.class,batch,rowNumber);}
    List<String> errors(long batch,int rowNumber){return db.queryForList("SELECT error_code FROM import_exceptions WHERE batch_id=? AND row_number=? AND severity='error' ORDER BY id",String.class,batch,rowNumber);}
    JsonNode normalized(long batch,int rowNumber)throws Exception{return json.readTree(row(batch,rowNumber).get("normalized_values").toString());}
    JsonNode evidence(long batch,int rowNumber)throws Exception{return json.readTree(row(batch,rowNumber).get("duplicate_evidence").toString());}
    long count(String table){return db.queryForObject("SELECT count(*) FROM "+table,Long.class);}

    /** Creates an Administrator-owned mapping derived from the seeded default. */
    long mapping(Consumer<ObjectNode> mappingEdit,Consumer<ObjectNode> settingsEdit){
        var base=mappings.forUpload(null);ObjectNode mapping=base.mapping().deepCopy(),settings=base.settings().deepCopy();
        mappingEdit.accept(mapping);settingsEdit.accept(settings);
        as(admin);try{return mappings.create(new MappingInput("Synthetic "+UUID.randomUUID(),mapping,settings,false,true,false)).id();}finally{as(registrar);}
    }

    // ---- mapping service -----------------------------------------------------------------------------------------------
    @Test void seededDefaultIsAssumedUnvalidatedAndSeparateFromTheStudentMappings(){
        var seeded=mappings.forUpload(null);
        assertThat(seeded.isDefault()).isTrue();assertThat(seeded.realFileValidated()).isFalse();
        assertThat(seeded.settings().path("ignoredColumns").isArray()).isTrue();assertThat(seeded.settings().path("ignoredColumns")).isEmpty();
        assertThat(seeded.mapping().path("valueMaps").path("subject")).isEmpty();
        assertThat(mappings.validate(seeded.mapping(),seeded.settings())).isEmpty();
        assertThat(mappings.list()).allMatch(m->db.queryForObject("SELECT source_type FROM import_column_mappings WHERE id=?",String.class,m.id()).equals("legacy_grade_matrix_csv"));
        assertThat(studentMappings.list()).extracting(ImportMappingService.StoredMapping::id).doesNotContain(seeded.id());
        assertThatThrownBy(()->studentMappings.get(seeded.id())).isInstanceOf(ResponseStatusException.class);
    }
    @Test void mappingValidationRejectsUnknownSetupValuesAndInconsistentClassification()throws Exception{
        var s=setup();var base=mappings.forUpload(null);
        ObjectNode good=base.mapping().deepCopy();((ObjectNode)good.with("valueMaps").with("subject")).put("Mathematics 7","SYNMATH"+s.n());((ObjectNode)good.with("valueMaps").with("period")).put("1st Quarter","Q1");
        ((ObjectNode)good.with("valueMaps").with("term")).put("First Semester","S1");((ObjectNode)good.with("valueMaps").with("schoolYear")).put("AY 2018-19","SY"+s.n());((ObjectNode)good.with("valueMaps").with("mark")).put("Inc.","INC");
        assertThat(mappings.validate(good,base.settings())).isEmpty();
        ObjectNode bad=good.deepCopy();
        ((ObjectNode)bad.with("valueMaps").with("subject")).put("Ghost","NOSUCH"+s.n());((ObjectNode)bad.with("valueMaps").with("period")).put("9th","Q99");
        ((ObjectNode)bad.with("valueMaps").with("term")).put("Ghost term","T99");((ObjectNode)bad.with("valueMaps").with("schoolYear")).put("Ghost year","NO-SUCH-YEAR");((ObjectNode)bad.with("valueMaps").with("mark")).put("W","WITHDRAWN");
        var problems=String.join(" ",mappings.validate(bad,base.settings()));
        assertThat(problems).contains("NOSUCH"+s.n(),"Q99","T99","NO-SUCH-YEAR","WITHDRAWN","Nothing is created");
        ObjectNode layout=good.deepCopy();layout.with("grid").put("layout","two_row_band");
        assertThat(mappings.validate(layout,base.settings())).anyMatch(p->p.contains("grid.layout"));
        ObjectNode finals=good.deepCopy();finals.with("grid").putArray("reportedFinalLabels").add("1st Quarter");
        assertThat(mappings.validate(finals,base.settings())).anyMatch(p->p.contains("reported final label and a period value-map"));
        ObjectNode noId=good.deepCopy();((ObjectNode)noId.with("fields")).remove(List.of("studentNumber","lrn"));
        assertThat(mappings.validate(noId,base.settings())).anyMatch(p->p.contains("studentNumber or lrn"));
        ObjectNode settings=base.settings().deepCopy();settings.putArray("ignoredColumns").add("Student No").add("Remarks").add("remarks");
        assertThat(String.join(" ",mappings.validate(good,settings))).contains("both an ignored column and a field alias","more than once");
        settings=base.settings().deepCopy();settings.put("decimalSeparator",",").put("delimiter",",");
        assertThat(mappings.validate(good,settings)).anyMatch(p->p.contains("decimalSeparator cannot"));
        settings=base.settings().deepCopy();settings.put("unknownKey",1);settings.put("headerRow",0);
        assertThat(String.join(" ",mappings.validate(good,settings))).contains("Unknown setting 'unknownKey'","headerRow");
        ObjectNode extra=good.deepCopy();extra.with("grid").putArray("ignoreColumns");   // the phase-1 spelling, now ignoredColumns in settings
        assertThat(mappings.validate(extra,base.settings())).anyMatch(p->p.contains("Unknown grid key 'ignoreColumns'"));
    }
    @Test void onlyAdministratorsWriteMappingsAndABatchKeepsItsSnapshot()throws Exception{
        var s=setup();var base=mappings.forUpload(null);
        assertThatThrownBy(()->mappings.create(new MappingInput("Not allowed",base.mapping(),base.settings(),false,true,false))).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(403));
        student(s,"01");
        long id=mapping(m->{},st->{});var batch=stage(s,text("Student No,Grade Level,SYNMATH"+s.n()+" - Q1\r\nSYN-"+s.n()+"-01,Grade 7,90\r\n"),term(s),id).batchId();
        var snap=json.readTree(db.queryForObject("SELECT mapping_snapshot::text FROM import_batches WHERE id=?",String.class,batch));
        assertThat(snap.get("mappingId").asLong()).isEqualTo(id);assertThat(snap.get("mappingVersion").asInt()).isZero();assertThat(snap.get("realFileValidated").asBoolean()).isFalse();
        // Editing the mapping later changes only new uploads.
        as(admin);var current=mappings.get(id);ObjectNode settings=current.settings().deepCopy();settings.putArray("ignoredColumns").add("Remarks");
        var updated=mappings.update(id,current.version(),new MappingInput(current.name(),current.mapping(),settings,false,true,true));as(registrar);
        assertThat(updated.version()).isEqualTo(1);
        var after=json.readTree(db.queryForObject("SELECT mapping_snapshot::text FROM import_batches WHERE id=?",String.class,batch));
        assertThat(after.get("mappingVersion").asInt()).isZero();assertThat(after.get("settings").get("ignoredColumns")).isEmpty();assertThat(after.get("realFileValidated").asBoolean()).isFalse();
        assertThat(db.queryForMap("SELECT mapping_id,source_type,upload_context::text AS ctx FROM import_batches WHERE id=?",batch)).containsEntry("mapping_id",id).containsEntry("source_type","legacy_grade_matrix_csv");
    }

    // ---- staging -------------------------------------------------------------------------------------------------------
    @Test void stagingWritesOnlyBatchRowsAndExceptionsAndKeepsGradesOutOfAudits()throws Exception{
        var s=setup();String letters=ImportFixtures.letters(s.n());
        student("SYN-"+s.n()+"-01",null,"Ana","Alphaone"+letters,iligan,true);student("SYN-"+s.n()+"-02","99"+s.n()+"02","Ben","Betatwo"+letters,iligan,true);student("SYN-"+s.n()+"-03",null,"Cara","Gammathree"+letters,iligan,true);
        long records=count("legacy_grade_records"),grades=count("grades"),students=count("students");
        var result=stage(s,fixture("synthetic-grades-basic.csv",s.n()));long b=result.batchId();
        assertThat(count("legacy_grade_records")).isEqualTo(records);assertThat(count("grades")).isEqualTo(grades);assertThat(count("students")).isEqualTo(students);
        assertThat(result.status()).isEqualTo("validated");assertThat(result.sourceCount()).isEqualTo(3);assertThat(result.valid()).isEqualTo(3);assertThat(result.invalid()).isZero();assertThat(result.flagged()).isZero();
        assertThat(result.recordCount()).as("INC counts, blanks do not").isEqualTo(3+4+0);assertThat(result.blankCount()).isEqualTo(5);
        var batch=db.queryForMap("SELECT * FROM import_batches WHERE id=?",b);
        assertThat(batch).containsEntry("source_type","legacy_grade_matrix_csv").containsEntry("status","validated").containsEntry("campus_id",iligan).containsEntry("source_name","synthetic-grades.csv");
        assertThat(batch.get("created_by")).isEqualTo(registrar);assertThat(batch.get("upload_context").toString()).contains("\"termId\"","Grade 7").contains(Long.toString(s.s1()));
        assertThat(db.queryForList("SELECT row_number FROM import_rows WHERE batch_id=? ORDER BY row_number",Integer.class,b)).containsExactly(2,3,4);
        assertThat(db.queryForObject("SELECT count(DISTINCT source_type) FROM import_rows WHERE batch_id=?",Integer.class,b)).isEqualTo(1);
        var first=normalized(b,2);
        assertThat(first.get("studentId").asLong()).isEqualTo(db.queryForObject("SELECT id FROM students WHERE student_number=?",Long.class,"SYN-"+s.n()+"-01"));assertThat(first.get("matchedBy").asText()).isEqualTo("studentNumber");
        assertThat(first.get("context").get("termId").asLong()).isEqualTo(s.s1());assertThat(first.get("context").get("academicYearId").asLong()).isEqualTo(s.year());assertThat(first.get("context").get("gradeLevel").asText()).isEqualTo("Grade 7");
        var cells=first.get("cells");assertThat(cells).hasSize(3);
        assertThat(cells.get(0).get("column").asText()).isEqualTo("SYNMATH"+s.n()+" - Q1");assertThat(cells.get(0).get("value").asText()).isEqualTo("88.50");assertThat(cells.get(0).get("kind").asText()).isEqualTo("period");assertThat(cells.get(0).get("periodId").asLong()).isEqualTo(s.q1());
        assertThat(cells.get(1).get("value").asText()).isEqualTo("90.00");assertThat(cells.get(1).get("periodId").asLong()).isEqualTo(s.q2());
        assertThat(cells.get(2).get("mark").asText()).isEqualTo("INC");assertThat(cells.get(2).has("value")).isFalse();assertThat(first.get("blankCells").asInt()).isEqualTo(1);
        assertThat(normalized(b,3).get("cells").findValuesAsText("mark")).containsExactly("NG","DRP");assertThat(normalized(b,3).get("matchedBy").asText()).isEqualTo("studentNumber");
        assertThat(normalized(b,4).get("cells")).as("blank cells never become INC or zero").isEmpty();assertThat(normalized(b,4).get("blankCells").asInt()).isEqualTo(4);
        assertThat(codes(b,4)).containsExactly("NO_GRADES_ON_ROW");assertThat(status(b,4)).isEqualTo("valid");
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='import_rows' AND (new_data::text LIKE '%88.50%' OR new_data::text LIKE '%INC%' OR new_data::text LIKE '%raw_values%' OR new_data::text LIKE '%normalized_values%')",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='import_rows' AND operation='INSERT' AND actor_id=?",Integer.class,registrar)).isGreaterThanOrEqualTo(3);
        assertThat(db.queryForObject("SELECT count(*) FROM import_exceptions WHERE batch_id=? AND (detail LIKE '%88%' OR detail LIKE '%Alphaone%')",Integer.class,b)).isZero();
    }
    @Test void mixedCellsAreValidatedPerCellAndOneBadCellInvalidatesItsWholeRow()throws Exception{
        var s=setup();for(int i=1;i<=12;i++)student(s,String.format("%02d",i));
        var r=stage(s,fixture("synthetic-grades-cells.csv",s.n()));long b=r.batchId();
        assertThat(r.sourceCount()).isEqualTo(12);assertThat(r.valid()).isEqualTo(5);assertThat(r.invalid()).isEqualTo(7);
        assertThat(status(b,2)).isEqualTo("valid");assertThat(normalized(b,2).get("cells").findValuesAsText("value")).containsExactly("88.50","90.00");assertThat(normalized(b,2).get("cells").findValuesAsText("mark")).containsExactly("INC");
        assertThat(normalized(b,3).get("cells").findValuesAsText("mark")).as("marks are case-insensitive").containsExactly("NG","DRP","DRP");assertThat(normalized(b,3).get("cells").findValuesAsText("value")).containsExactly("85.00");
        Map<Integer,String> expected=new LinkedHashMap<>();
        expected.put(4,"VALUE_OUT_OF_RANGE");expected.put(5,"VALUE_OUT_OF_RANGE");expected.put(6,"TOO_MANY_DECIMALS");expected.put(8,"INVALID_VALUE");expected.put(9,"UNMAPPED_MARK");expected.put(10,"INVALID_VALUE");expected.put(13,"UNMAPPED_MARK");
        expected.forEach((row,code)->{assertThat(status(b,row)).as("row "+row).isEqualTo("invalid");assertThat(errors(b,row)).as("row "+row).containsExactly(code);assertThat(row(b,row).get("normalized_values")).as("row "+row).isNull();});
        assertThat(normalized(b,7).get("cells").findValuesAsText("value")).as("88.500 has no real third decimal").contains("88.50");
        assertThat(normalized(b,12).get("cells").findValuesAsText("value")).containsExactly("77.00");
        assertThat(status(b,11)).isEqualTo("valid");assertThat(normalized(b,11).get("cells")).isEmpty();assertThat(codes(b,11)).containsExactly("NO_GRADES_ON_ROW");
        assertThat(db.queryForObject("SELECT field_name FROM import_exceptions WHERE batch_id=? AND row_number=13",String.class,b)).isEqualTo("SYNMATH"+s.n()+" - Q2");
        assertThat(db.queryForObject("SELECT count(*) FROM import_exceptions WHERE batch_id=? AND (detail LIKE '%101%' OR detail LIKE '%88.555%' OR detail LIKE '%85%%')",Integer.class,b)).as("grade values never appear in messages").isZero();
    }
    @Test void nonDefaultDecimalSeparatorAndMarkTranslationsComeFromTheMapping()throws Exception{
        var s=setup();student(s,"01");
        long id=mapping(m->((ObjectNode)m.with("valueMaps").with("mark")).put("Inc.","INC"),st->st.put("delimiter",";").put("decimalSeparator",","));
        var csv="Student No;Grade Level;SYNMATH"+s.n()+" - Q1;SYNMATH"+s.n()+" - Q2;SYNSCI"+s.n()+" - Q1\r\nSYN-"+s.n()+"-01;Grade 7;88,5;Inc.;90.5\r\n";
        var r=stage(s,text(csv),term(s),id);assertThat(r.invalid()).isEqualTo(1);assertThat(errors(r.batchId(),2)).containsExactly("INVALID_VALUE");
        var ok=stage(s,text(csv.replace("90.5","90,5")),term(s),id);assertThat(ok.valid()).isEqualTo(1);
        var cells=normalized(ok.batchId(),2).get("cells");assertThat(cells.get(0).get("value").asText()).isEqualTo("88.50");assertThat(cells.get(1).get("mark").asText()).isEqualTo("INC");assertThat(cells.get(2).get("value").asText()).isEqualTo("90.50");
    }
    @Test void valueMapsTranslateLegacyLabelsAndReportedFinalsAreStoredAsReportedNeverComputed()throws Exception{
        var s=setup();student(s,"01");
        long id=mapping(m->{
            m.with("fields").with("schoolYear").withArray("aliases").add("ay");
            ((ObjectNode)m.with("valueMaps").with("subject")).put("Mathematics 7","SYNMATH"+s.n()).put("Science 7","SYNSCI"+s.n());
            ((ObjectNode)m.with("valueMaps").with("period")).put("1st Quarter","Q1");((ObjectNode)m.with("valueMaps").with("term")).put("First Semester","S1");
            ((ObjectNode)m.with("valueMaps").with("schoolYear")).put("AY 2018-19","SY"+s.n());((ObjectNode)m.with("valueMaps").with("mark")).put("Inc.","INC");
            m.with("grid").putArray("reportedFinalLabels").add("Final Grade");
        },st->{});
        var r=stage(s,fixture("synthetic-grades-legacy-labels.csv",s.n()),new UploadContext(null,null,null,null),id);
        assertThat(r.valid()).isEqualTo(1);var v=normalized(r.batchId(),2);
        assertThat(v.get("context").get("termId").asLong()).isEqualTo(s.s1());assertThat(v.get("context").get("academicYearId").asLong()).isEqualTo(s.year());
        var cells=v.get("cells");assertThat(cells).hasSize(3);
        assertThat(cells.get(0).get("kind").asText()).isEqualTo("period");assertThat(cells.get(0).get("mark").asText()).isEqualTo("INC");
        assertThat(cells.get(1).get("kind").asText()).isEqualTo("reported_final");assertThat(cells.get(1).get("value").asText()).isEqualTo("88.00");assertThat(cells.get(1).get("periodId").isNull()).isTrue();
        assertThat(cells.get(2).get("kind").asText()).isEqualTo("period");assertThat(cells.get(2).get("value").asText()).isEqualTo("90.00");
        assertThat(cells.findValuesAsText("value")).as("only values present in the file; no average or final rating is computed").containsExactly("88.00","90.00");
    }

    // ---- headers -------------------------------------------------------------------------------------------------------
    @Test void unclassifiedColumnsFailTheWholeFileAndEveryHeaderIsNamed()throws Exception{
        var s=setup();student(s,"01");long batches=count("import_batches"),rows=count("import_rows");
        var e=fileError(s,fixture("synthetic-grades-unclassified.csv",s.n()));
        assertThat(e.error()).isEqualTo(FileError.UNMAPPED_COLUMN);assertThat(e.error().status).isEqualTo(422);assertThat(e.batchId()).isNotNull();
        assertThat(e.getMessage()).contains("'Remarks'","'Age'","'Advisory Note'","ignoredColumns").doesNotContain("Student No'","SYNMATH");
        assertThat(e.problems()).extracting(ImportFileException.Problem::header).containsExactly("Remarks","Age","Advisory Note");
        assertThat(db.queryForMap("SELECT status,source_count FROM import_batches WHERE id=?",e.batchId())).containsEntry("status","failed").containsEntry("source_count",0);
        assertThat(db.queryForList("SELECT field_name FROM import_exceptions WHERE batch_id=? AND error_code='UNMAPPED_COLUMN' AND field_name IS NOT NULL ORDER BY id",String.class,e.batchId())).containsExactly("Remarks","Age","Advisory Note");
        assertThat(db.queryForObject("SELECT count(*) FROM import_exceptions WHERE batch_id=? AND row_number IS NULL AND import_row_id IS NULL",Integer.class,e.batchId())).isEqualTo(4);
        assertThat(count("import_rows")).isEqualTo(rows);assertThat(count("import_batches")).isEqualTo(batches+1);
        // The same bytes can be uploaded again once the mapping ignores those columns; a failed batch never blocks.
        long fixed=mapping(m->{},st->st.putArray("ignoredColumns").add("Remarks").add("age").add("ADVISORY_NOTE"));
        var ok=stage(s,fixture("synthetic-grades-unclassified.csv",s.n()),term(s),fixed);assertThat(ok.valid()).isEqualTo(1);
        assertThat(normalized(ok.batchId(),2).get("cells")).as("ignored columns produce no records").hasSize(1);
        // Ignoring only some still names the rest.
        long partial=mapping(m->{},st->st.putArray("ignoredColumns").add("Remarks"));
        var again=fileError(s,withNewline(fixture("synthetic-grades-unclassified.csv",s.n())),term(s),partial);
        assertThat(again.error()).isEqualTo(FileError.UNMAPPED_COLUMN);assertThat(again.problems()).extracting(ImportFileException.Problem::header).containsExactly("Age","Advisory Note");
    }
    @Test void ambiguousDuplicateAndMissingIdentityClassificationsAreFileErrors(){
        var s=setup();
        var ambiguous=fileError(s,fixture("synthetic-grades-ambiguous-header.csv",s.n()));
        assertThat(ambiguous.error()).isEqualTo(FileError.AMBIGUOUS_HEADER);assertThat(ambiguous.getMessage()).contains("studentNumber","Student No","student_no");assertThat(ambiguous.batchId()).isNotNull();
        var missing=fileError(s,fixture("synthetic-grades-no-identifier.csv",s.n()));
        assertThat(missing.error()).isEqualTo(FileError.MISSING_COLUMN);assertThat(missing.getMessage()).contains("studentNumber or lrn");
        var twice=fileError(s,text("Student No,Grade Level,SYNMATH"+s.n()+" - Q1,synmath"+s.n()+"  -  q1\r\nSYN-"+s.n()+"-01,Grade 7,90,91\r\n"));
        assertThat(twice.error()).isEqualTo(FileError.AMBIGUOUS_HEADER);assertThat(twice.getMessage()).contains("both classify as subject");
        assertThatThrownBy(()->mapping(m->{},st->st.putArray("ignoredColumns").add("Grade Level"))).as("a header cannot be both ignored and a field alias").isInstanceOf(ResponseStatusException.class).hasMessageContaining("both an ignored column and a field alias");
    }
    @Test void unknownSubjectsAndPeriodsFailTheFileAndNothingIsCreated(){
        var s=setup();long subjects=count("subjects"),periods=count("grading_periods");
        var subject=fileError(s,fixture("synthetic-grades-unknown-subject.csv",s.n()));
        assertThat(subject.error()).isEqualTo(FileError.UNKNOWN_SUBJECT);assertThat(subject.getMessage()).contains("SYNNOPE"+s.n(),"SYNNOPE2"+s.n());assertThat(subject.problems()).hasSize(2);
        var period=fileError(s,fixture("synthetic-grades-unknown-period.csv",s.n()));
        assertThat(period.error()).isEqualTo(FileError.UNKNOWN_PERIOD);assertThat(period.getMessage()).contains("Q9");
        assertThat(count("subjects")).isEqualTo(subjects);assertThat(count("grading_periods")).isEqualTo(periods);
        var repeated=fileError(s,text("Student No,Grade Level,SYNMATH"+s.n()+" - Q1\r\nSYN-"+s.n()+"-01,Grade 7,90\r\nStudent No,Grade Level,SYNMATH"+s.n()+" - Q1\r\n"));
        assertThat(repeated.error()).isEqualTo(FileError.REPEATED_HEADER);assertThat(repeated.batchId()).isNotNull();
    }
    @Test void aBlankTrailingHeaderIsHarmlessUntilItHoldsData(){
        var s=setup();student(s,"01");
        var ok=stage(s,text("Student No,Grade Level,SYNMATH"+s.n()+" - Q1,\r\nSYN-"+s.n()+"-01,Grade 7,90,\r\n"));assertThat(ok.valid()).isEqualTo(1);
        var e=fileError(s,text("Student No,Grade Level,SYNMATH"+s.n()+" - Q1,\r\nSYN-"+s.n()+"-01,Grade 7,91,secret\r\n"));
        assertThat(e.error()).isEqualTo(FileError.UNMAPPED_COLUMN);assertThat(e.getMessage()).contains("no header");
    }

    // ---- students ------------------------------------------------------------------------------------------------------
    @Test void studentsMatchByNumberThenLrnWithinTheCampusAndEveryProblemRowIsReported()throws Exception{
        var s=setup();String n=s.n();
        long s01=student("SYN-"+n+"-01",null,"Ana","Alphaone"+ImportFixtures.letters(n),iligan,true);
        student("SYN-"+n+"-02","99"+n+"02","Ben","Betatwo"+ImportFixtures.letters(n),iligan,true);
        long s03=student("SYN-"+n+"-03","99"+n+"03","Cara","Gammathree"+ImportFixtures.letters(n),iligan,true);
        long s04=student("SYN-"+n+"-04",null,"Dan","Deltafour"+ImportFixtures.letters(n),iligan,true);
        long s05=student("SYN-"+n+"-05","99"+n+"05","Eve","Deltafive"+ImportFixtures.letters(n),iligan,true);
        student("SYN-"+n+"-06","99"+n+"06","Eli","Epsilonsix"+ImportFixtures.letters(n),iligan,true);
        student("SYN-"+n+"-07",null,"Fay","Etaseven"+ImportFixtures.letters(n),iligan,true);
        student("SYN-"+n+"-08",null,"Gus","Thetaeight"+ImportFixtures.letters(n),iligan,false);
        student("BUGO-"+n+"-01",null,"Hal","Iotanine"+ImportFixtures.letters(n),bugo,true);
        var r=stage(s,fixture("synthetic-grades-students.csv",n));long b=r.batchId();
        assertThat(r.sourceCount()).isEqualTo(10);assertThat(r.valid()).isEqualTo(4);assertThat(r.invalid()).isEqualTo(3);assertThat(r.flagged()).isEqualTo(3);
        assertThat(status(b,2)).isEqualTo("valid");assertThat(normalized(b,2).get("studentId").asLong()).isEqualTo(s01);
        assertThat(status(b,3)).isEqualTo("valid");assertThat(normalized(b,3).get("matchedBy").asText()).isEqualTo("studentNumber");
        assertThat(status(b,4)).isEqualTo("valid");assertThat(normalized(b,4).get("studentId").asLong()).isEqualTo(s03);assertThat(normalized(b,4).get("matchedBy").asText()).as("number unknown, LRN identifies the student").isEqualTo("lrn");
        assertThat(status(b,5)).isEqualTo("invalid");assertThat(errors(b,5)).containsExactly("STUDENT_NOT_FOUND");
        assertThat(status(b,6)).isEqualTo("duplicate_flagged");assertThat(codes(b,6)).containsExactly("AMBIGUOUS_STUDENT");
        var conflict=evidence(b,6);assertThat(conflict).hasSize(2);assertThat(conflict.findValues("id").stream().map(JsonNode::asLong)).containsExactlyInAnyOrder(s04,s05);assertThat(conflict.findValuesAsText("rule")).containsExactly("STUDENT_NUMBER","LRN");
        assertThat(status(b,7)).isEqualTo("duplicate_flagged");assertThat(evidence(b,7).get(0).get("rule").asText()).isEqualTo("LRN_MISMATCH");
        assertThat(status(b,8)).isEqualTo("duplicate_flagged");assertThat(evidence(b,8).get(0).get("rule").asText()).isEqualTo("NAME_MISMATCH");assertThat(codes(b,8)).containsExactly("NAME_MISMATCH");
        assertThat(normalized(b,6).get("studentId").isNull()).as("a flagged row has no student until reviewed").isTrue();
        assertThat(status(b,9)).isEqualTo("valid");assertThat(codes(b,9)).containsExactly("STUDENT_INACTIVE");
        assertThat(status(b,10)).as("a student at another campus is not found").isEqualTo("invalid");assertThat(errors(b,10)).containsExactly("STUDENT_NOT_FOUND");
        assertThat(status(b,11)).as("the same student and cell twice in one file").isEqualTo("invalid");assertThat(errors(b,11)).containsExactly("DUPLICATE_IN_BATCH");assertThat(status(b,2)).isEqualTo("valid");
        assertThat(db.queryForObject("SELECT count(*) FROM import_exceptions WHERE batch_id=? AND detail LIKE '%99"+n+"%'",Integer.class,b)).as("LRNs are never echoed").isZero();
    }
    @Test void aStudentOfAnotherCampusIsInvisibleEvenWhenTheNumberIsExact(){
        var s=setup();student("SYN-"+s.n()+"-01",null,"Ana","Alpha",bugo,true);
        var r=stage(s,text("Student No,Grade Level,SYNMATH"+s.n()+" - Q1\r\nSYN-"+s.n()+"-01,Grade 7,90\r\n"));
        assertThat(r.invalid()).isEqualTo(1);assertThat(errors(r.batchId(),2)).containsExactly("STUDENT_NOT_FOUND");
        // The Bugo registrar may import it for Bugo only.
        as(bugoRegistrar);var ok=service.stageGradeMatrix(bugo,new UploadContext(null,s.s1(),"Grade 7",null),"b.csv",text("Student No,Grade Level,SYNMATH"+s.n()+" - Q1\r\nSYN-"+s.n()+"-01,Grade 7,91\r\n"),null);
        assertThat(ok.valid()).isEqualTo(1);
    }
    @Test void aStudentRepeatedForDifferentTermsIsFineButAnOverlappingCellIsNot()throws Exception{
        var s=setup();student(s,"01");student(s,"02");student(s,"03");
        var r=stage(s,fixture("synthetic-grades-terms.csv",s.n()),new UploadContext(s.year(),null,"Grade 7",null),null);long b=r.batchId();
        assertThat(status(b,2)).isEqualTo("valid");assertThat(normalized(b,2).get("context").get("termId").asLong()).isEqualTo(s.s1());
        assertThat(status(b,3)).as("same student, another term").isEqualTo("valid");assertThat(normalized(b,3).get("context").get("termId").asLong()).isEqualTo(s.s2());
        assertThat(status(b,4)).isEqualTo("invalid");assertThat(errors(b,4)).containsExactly("UNKNOWN_TERM");
        assertThat(status(b,5)).isEqualTo("invalid");assertThat(errors(b,5)).containsExactly("UNKNOWN_SCHOOL_YEAR");
        assertThat(status(b,6)).as("blank school year uses the upload's year").isEqualTo("valid");
        assertThat(status(b,7)).isEqualTo("invalid");assertThat(errors(b,7)).containsExactly("CONTEXT_MISMATCH");
        assertThat(db.queryForObject("SELECT count(*) FROM academic_years WHERE label LIKE ?",Integer.class,"%"+s.n()+"%")).as("no academic year was created").isEqualTo(2);
    }

    // ---- upload context ------------------------------------------------------------------------------------------------
    @Test void uploadAndFileContextMustAgreeAndMustExist()throws Exception{
        var s=setup();student(s,"01");
        var noTerm=fileError(s,text("Student No,Grade Level,SYNMATH"+s.n()+" - Q1\r\nSYN-"+s.n()+"-01,Grade 7,90\r\n"),new UploadContext(null,null,"Grade 7",null),null);
        assertThat(noTerm.error()).isEqualTo(FileError.MISSING_COLUMN);assertThat(noTerm.getMessage()).contains("term","schoolYear");
        var noLevel=fileError(s,text("Student No,SYNMATH"+s.n()+" - Q1\r\nSYN-"+s.n()+"-01,90\r\n"),new UploadContext(null,s.s1(),null,null),null);
        assertThat(noLevel.error()).isEqualTo(FileError.MISSING_COLUMN);assertThat(noLevel.getMessage()).contains("gradeLevel");
        var level=stage(s,text("Student No,Grade Level,Section,SYNMATH"+s.n()+" - Q1\r\nSYN-"+s.n()+"-01,Grade 8,Rizal,90\r\n"),new UploadContext(null,s.s1(),"Grade 7","Rizal"),null);
        assertThat(errors(level.batchId(),2)).containsExactly("CONTEXT_MISMATCH");
        var fileOnly=stage(s,text("Student No,Grade Level,Section,SYNMATH"+s.n()+" - Q1\r\nSYN-"+s.n()+"-01,Grade 8,Rizal,92\r\n"),new UploadContext(null,s.s1(),null,null),null);
        assertThat(normalized(fileOnly.batchId(),2).get("context").get("gradeLevel").asText()).isEqualTo("Grade 8");assertThat(normalized(fileOnly.batchId(),2).get("context").get("section").asText()).isEqualTo("Rizal");
        var other=catchThrowableOfType(ResponseStatusException.class,()->stage(s,text("x\r\n1\r\n"),new UploadContext(s.yearB(),s.s1(),"Grade 7",null),null));
        assertThat(other.getStatusCode().value()).isEqualTo(400);
        for(var bad:List.of(new UploadContext(-1L,null,"Grade 7",null),new UploadContext(null,-1L,"Grade 7",null),new UploadContext(null,s.s1(),"x".repeat(31),null),new UploadContext(null,s.s1(),"Grade 7","y".repeat(151))))
            assertThat(catchThrowableOfType(ResponseStatusException.class,()->stage(s,text("x\r\n1\r\n"),bad,null)).getStatusCode().value()).isEqualTo(400);
        assertThat(normalized(stage(s,text("Student No,SYNMATH"+s.n()+" - Q1\r\nSYN-"+s.n()+"-01,90\r\n"),new UploadContext(s.year(),s.s1(),"Grade 7",null),null).batchId(),2).get("context").get("academicYearId").asLong()).isEqualTo(s.year());
    }

    // ---- encoding, size and limits -------------------------------------------------------------------------------------
    @Test void bomIsToleratedAndWrongEncodingFailsTheFileWithARecordedBatch(){
        var s=setup();student(s,"01");
        byte[] plain=text("Student No,Grade Level,SYNMATH"+s.n()+" - Q1\r\nSYN-"+s.n()+"-01,Grade 7,90\r\n");
        byte[] bom=new byte[plain.length+3];bom[0]=(byte)0xEF;bom[1]=(byte)0xBB;bom[2]=(byte)0xBF;System.arraycopy(plain,0,bom,3,plain.length);
        assertThat(stage(s,bom).valid()).as("the BOM is not part of the first header").isEqualTo(1);
        byte[] cp1252=("Student No,Grade Level,SYN\u00e9 - Q1\r\nSYN-"+s.n()+"-01,Grade 7,90\r\n").getBytes(Charset.forName("windows-1252"));
        var wrong=fileError(s,cp1252);assertThat(wrong.error()).isEqualTo(FileError.INVALID_ENCODING);assertThat(wrong.batchId()).isNotNull();
        var utf16=fileError(s,"Student No\r\n".getBytes(StandardCharsets.UTF_16));assertThat(utf16.error()).isEqualTo(FileError.INVALID_ENCODING);
        long cp=mapping(m->{},st->st.put("encoding","windows-1252"));
        var good=fileError(s,cp1252,term(s),cp);assertThat(good.error()).as("decoded, so it reaches classification").isEqualTo(FileError.UNKNOWN_SUBJECT);
    }
    @Test void limitsAnswer413ForSizeRowsAndCellsAnd422ForColumnsAndEmptyFiles(){
        var s=setup();student(s,"01");byte[] file=fixture("synthetic-grades-basic.csv",s.n());long batches=count("import_batches");
        var tinyFile=svc(new ImportLimits(120,20_000,64,1000),GradeImportLimits.DEFAULTS);
        var big=catchThrowableOfType(ImportFileException.class,()->tinyFile.stageGradeMatrix(iligan,term(s),"big.csv",file,null));
        assertThat(big.error()).isEqualTo(FileError.FILE_TOO_LARGE);assertThat(big.error().status).isEqualTo(413);assertThat(big.batchId()).isNull();
        var tinyRows=svc(new ImportLimits(5_000_000,2,64,1000),GradeImportLimits.DEFAULTS);
        var many=catchThrowableOfType(ImportFileException.class,()->tinyRows.stageGradeMatrix(iligan,term(s),"rows.csv",file,null));
        assertThat(many.error()).isEqualTo(FileError.TOO_MANY_ROWS);assertThat(many.error().status).isEqualTo(413);assertThat(many.batchId()).isNull();
        var tinyCells=svc(ImportLimits.DEFAULTS,new GradeImportLimits(300,5));
        var cells=catchThrowableOfType(ImportFileException.class,()->tinyCells.stageGradeMatrix(iligan,term(s),"cells.csv",file,null));
        assertThat(cells.error()).isEqualTo(FileError.TOO_MANY_CELLS);assertThat(cells.error().status).isEqualTo(413);assertThat(cells.batchId()).isNull();
        assertThat(count("import_batches")).as("413 persists nothing").isEqualTo(batches);
        var narrow=svc(ImportLimits.DEFAULTS,new GradeImportLimits(5,250_000));
        var wide=catchThrowableOfType(ImportFileException.class,()->narrow.stageGradeMatrix(iligan,term(s),"wide.csv",file,null));
        assertThat(wide.error()).isEqualTo(FileError.TOO_MANY_COLUMNS);assertThat(wide.error().status).isEqualTo(422);assertThat(wide.batchId()).isNotNull();
        assertThat(stage(s,file).sourceCount()).as("the default limits accept more than the student import's 64 columns").isEqualTo(3);
        var empty=fileError(s,new byte[0]);assertThat(empty.error()).isEqualTo(FileError.EMPTY_FILE);
        var headerOnly=fileError(s,text("Student No,Grade Level,SYNMATH"+s.n()+" - Q1\r\n"));assertThat(headerOnly.error()).isEqualTo(FileError.EMPTY_FILE);
    }
    GradeMatrixValidationService svc(ImportLimits limits,GradeImportLimits grade){return new GradeMatrixValidationService(named,json,access,mappings,limits,grade,transactions);}

    // ---- authorization and duplicate files -----------------------------------------------------------------------------
    @Test void onlyHoldersOfTheGradePermissionForTheCampusMayStageAndIdenticalFilesConflict(){
        var s=setup();student(s,"01");byte[] file=text("Student No,Grade Level,SYNMATH"+s.n()+" - Q1\r\nSYN-"+s.n()+"-01,Grade 7,90\r\n");
        as(bugoRegistrar);assertThat(catchThrowableOfType(ResponseStatusException.class,()->stage(s,file)).getStatusCode().value()).as("another campus").isEqualTo(403);
        as(teacher);assertThat(catchThrowableOfType(ResponseStatusException.class,()->stage(s,file)).getStatusCode().value()).as("teacher").isEqualTo(403);
        as(user(null,0));assertThat(catchThrowableOfType(ResponseStatusException.class,()->stage(s,file)).getStatusCode().value()).isEqualTo(403);
        // imports.manage alone (the student import) does not authorize grades.
        long onlyStudents=user(null,0);db.update("INSERT INTO roles(code,name) VALUES (?,?) ON CONFLICT DO NOTHING","ROLE_STUDENT_IMPORT_ONLY","Synthetic");
        db.update("INSERT INTO role_permissions(role_code,permission_code) VALUES ('ROLE_STUDENT_IMPORT_ONLY','imports.manage') ON CONFLICT DO NOTHING");db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,?,?)",onlyStudents,"ROLE_STUDENT_IMPORT_ONLY",iligan);
        as(onlyStudents);assertThat(catchThrowableOfType(ResponseStatusException.class,()->stage(s,file)).getStatusCode().value()).isEqualTo(403);
        as(registrar);var first=stage(s,file);
        var conflict=catchThrowableOfType(ImportConflictException.class,()->stage(s,file));assertThat(conflict.batchId()).isEqualTo(first.batchId());assertThat(conflict.status()).isEqualTo("validated");
        assertThat(stage(s,withNewline(file)).batchId()).isNotEqualTo(first.batchId());
        as(admin);assertThat(stage(s,text("Student No,Grade Level,SYNMATH"+s.n()+" - Q1\r\nSYN-"+s.n()+"-01,Grade 7,92\r\n")).valid()).isEqualTo(1);
    }
    @Test void aFullyVoidedBatchFreesItsChecksumButOneWithLiveRecordsCannotBeVoided(){
        var s=setup();long student=student(s,"01");byte[] file=text("Student No,Grade Level,SYNMATH"+s.n()+" - Q1\r\nSYN-"+s.n()+"-01,Grade 7,90\r\n");
        var first=stage(s,file);long b=first.batchId();
        db.update("UPDATE import_batches SET status='imported',imported_by=?,imported_at=now(),valid_count=0,imported_count=source_count WHERE id=?",registrar,b);
        db.update("UPDATE import_rows SET status='imported',student_id=? WHERE batch_id=?",student,b);
        assertThat(catchThrowableOfType(ImportConflictException.class,()->stage(s,file)).status()).isEqualTo("imported");
        long row=db.queryForObject("SELECT id FROM import_rows WHERE batch_id=?",Long.class,b);
        db.update("INSERT INTO legacy_grade_records(student_id,campus_id,term_id,grading_period_id,subject_id,grade_level,value,import_row_id,source_column) VALUES (?,?,?,?,?,'Grade 7',90,?,'x')",student,iligan,s.s1(),s.q1(),s.math(),row);
        assertThatThrownBy(()->db.update("UPDATE import_batches SET voided_at=now(),voided_by=?,void_reason='Wrong file' WHERE id=?",registrar,b)).hasMessageContaining("Every record of the batch must be voided");
        db.update("UPDATE legacy_grade_records SET voided_at=now(),voided_by=?,void_reason='Wrong file' WHERE import_row_id=?",registrar,row);
        assertThat(db.update("UPDATE import_batches SET voided_at=now(),voided_by=?,void_reason='Wrong file' WHERE id=?",registrar,b)).isEqualTo(1);
        var again=stage(s,file);assertThat(again.batchId()).as("the same bytes can be uploaded after a full void").isNotEqualTo(b);
        assertThat(db.queryForObject("SELECT status FROM import_batches WHERE id=?",String.class,b)).as("history is kept").isEqualTo("imported");
        assertThat(count("legacy_grade_records")).isGreaterThanOrEqualTo(1);
        assertThatThrownBy(()->db.update("UPDATE import_batches SET status='failed' WHERE id=?",b)).hasMessageContaining("voided import batch cannot be changed");
        assertThatThrownBy(()->db.update("UPDATE import_batches SET voided_at=now() WHERE id=?",again.batchId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ---- history and live overlap --------------------------------------------------------------------------------------
    long priorRecord(Setup s,long student,String value){
        long batch=db.queryForObject("INSERT INTO import_batches(source_name,checksum,source_count,status,imported_count,campus_id,source_type,imported_by,imported_at) VALUES ('prior.csv',substr(md5(random()::text)||md5(random()::text),1,64),1,'imported',1,?,'legacy_grade_matrix_csv',?,now()) RETURNING id",Long.class,iligan,registrar);
        long row=db.queryForObject("INSERT INTO import_rows(batch_id,row_number,status,campus_id,source_type,normalized_values,student_id) VALUES (?,2,'imported',?,'legacy_grade_matrix_csv','{}',?) RETURNING id",Long.class,batch,iligan,student);
        return db.queryForObject("INSERT INTO legacy_grade_records(student_id,campus_id,term_id,grading_period_id,subject_id,grade_level,value,import_row_id,source_column) VALUES (?,?,?,?,?,'Grade 7',?::numeric,?,'x') RETURNING id",Long.class,student,iligan,s.s1(),s.q1(),s.math(),value,row);
    }
    @Test void identicalRecordedCellsWarnAndDifferentOnesConflictWithoutOverwriting()throws Exception{
        var s=setup();long same=student(s,"01"),diff=student(s,"02");priorRecord(s,same,"88.50");priorRecord(s,diff,"70");
        var r=stage(s,text("Student No,Grade Level,SYNMATH"+s.n()+" - Q1,SYNMATH"+s.n()+" - Q2\r\nSYN-"+s.n()+"-01,Grade 7,88.5,91\r\nSYN-"+s.n()+"-02,Grade 7,88.5,91\r\n"));long b=r.batchId();
        assertThat(status(b,2)).isEqualTo("valid");assertThat(codes(b,2)).containsExactly("ALREADY_RECORDED");
        assertThat(status(b,3)).isEqualTo("invalid");assertThat(errors(b,3)).containsExactly("CONFLICTS_WITH_RECORDED");
        assertThat(db.queryForObject("SELECT value FROM legacy_grade_records WHERE student_id=?",java.math.BigDecimal.class,diff)).isEqualByComparingTo("70");
        // A voided record no longer counts.
        db.update("UPDATE legacy_grade_records SET voided_at=now(),voided_by=?,void_reason='Corrected' WHERE student_id=?",registrar,diff);
        var after=stage(s,text("Student No,Grade Level,SYNMATH"+s.n()+" - Q1\r\nSYN-"+s.n()+"-02,Grade 7,88.5\r\n"));assertThat(after.valid()).isEqualTo(1);
    }
    @Test void liveGradesAndEnrollmentsProduceWarningsButNeverBlockStaging()throws Exception{
        var s=setup();long sameCell=student(s,"01"),enrolledOnly=student(s,"02"),noOverlap=student(s,"03");
        long program=s.program();
        long section=db.queryForObject("INSERT INTO sections(campus_id,term_id,program_id,name,grade_level,capacity) VALUES (?,?,?,?,'7',30) RETURNING id",Long.class,iligan,s.s1(),program,"SEC"+s.n());
        long offering=db.queryForObject("INSERT INTO class_offerings(section_id,term_id,subject_id,teacher_id) VALUES (?,?,?,?) RETURNING id",Long.class,section,s.s1(),s.math(),teacher);
        for(long st:new long[]{sameCell,enrolledOnly}){
            long app=db.queryForObject("INSERT INTO applications(application_number,campus_id,term_id,program_id,student_id,category,first_name,last_name,birth_date) VALUES (?,?,?,?,?,'new','A','B','2009-01-01') RETURNING id",Long.class,"APP"+st+s.n(),iligan,s.s1(),program,st);
            long enrollment=db.queryForObject("INSERT INTO enrollments(student_id,application_id,campus_id,term_id,program_id,section_id,status) VALUES (?,?,?,?,?,?,'provisional') RETURNING id",Long.class,st,app,iligan,s.s1(),program,section);
            db.update("INSERT INTO class_memberships(class_id,enrollment_id,section_id,term_id) VALUES (?,?,?,?)",offering,enrollment,section,s.s1());
            if(st==sameCell)db.update("INSERT INTO grades(class_id,enrollment_id,term_id,grading_period_id,value,state) VALUES (?,?,?,?,85,'finalized')",offering,enrollment,s.s1(),s.q1());
        }
        long grades=count("grades");
        var r=stage(s,text("Student No,Grade Level,SYNMATH"+s.n()+" - Q1,SYNMATH"+s.n()+" - Q2\r\nSYN-"+s.n()+"-01,Grade 7,90,91\r\nSYN-"+s.n()+"-02,Grade 7,90,91\r\nSYN-"+s.n()+"-03,Grade 7,90,91\r\n"));long b=r.batchId();
        assertThat(r.valid()).as("warnings never invalidate a row").isEqualTo(3);assertThat(r.invalid()).isZero();
        assertThat(codes(b,2)).containsExactly("OVERLAPS_LIVE_GRADE","OVERLAPS_LIVE_ENROLLMENT");
        assertThat(db.queryForObject("SELECT detail FROM import_exceptions WHERE batch_id=? AND row_number=2 AND error_code='OVERLAPS_LIVE_GRADE'",String.class,b)).startsWith("1 value(s)").contains("drafts count","live state: finalized").doesNotContain("85");
        assertThat(codes(b,3)).as("an enrollment without the same subject and period is only an enrollment overlap").containsExactly("OVERLAPS_LIVE_ENROLLMENT");
        assertThat(codes(b,4)).isEmpty();
        assertThat(db.queryForList("SELECT severity FROM import_exceptions WHERE batch_id=? AND error_code LIKE 'OVERLAPS%'",String.class,b)).containsOnly("warning");
        assertThat(count("grades")).isEqualTo(grades);
        assertThat(db.queryForObject("SELECT value FROM grades WHERE grading_period_id=? AND class_id=?",java.math.BigDecimal.class,s.q1(),offering)).as("live grades are untouched").isEqualByComparingTo("85");
    }
}
