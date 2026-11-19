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
import ph.edu.ici.imports.ImportFileException.FileError;
import static org.assertj.core.api.Assertions.*;

/** Staging/validation against embedded PostgreSQL using the synthetic fixtures in src/test/resources/import. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class LegacyImportStagingTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->PostgresSupport.DATABASE.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");r.add("ici.auth.secret",()->PostgresSupport.SECRET);r.add("ici.bootstrap.enabled",()->false);
    }
    @Autowired JdbcTemplate db;@Autowired NamedParameterJdbcTemplate named;@Autowired ObjectMapper json;@Autowired AccessPolicy access;@Autowired PlatformTransactionManager transactions;
    @Autowired ImportValidationService service;@Autowired ImportMappingService mappings;
    long iligan,bugo,registrar,bugoRegistrar,admin,nobody;

    @BeforeEach void users(){
        iligan=db.queryForObject("SELECT id FROM campuses WHERE code='ILIGAN'",Long.class);bugo=db.queryForObject("SELECT id FROM campuses WHERE code='BUGO'",Long.class);
        registrar=user("ROLE_REGISTRAR",iligan);bugoRegistrar=user("ROLE_REGISTRAR",bugo);nobody=user(null,0);
        admin=user("ROLE_ADMIN",0);as(registrar);
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    long user(String role,long campus){
        String name="import-"+UUID.randomUUID();
        long id=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES (?,?,?,'unused') RETURNING id",Long.class,name,name+"@example.invalid","Synthetic staff");
        if(role!=null&&campus>0)db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,?,?)",id,role,campus);
        if(role!=null&&campus==0)db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) SELECT ?,?,id FROM campuses",id,role);
        return id;
    }
    void as(long id){var identity=new StaffIdentity(id,"u"+id,"Synthetic staff",List.of(),UUID.randomUUID(),Instant.now().plusSeconds(600),false);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(identity,null,List.of()));}

    // ---- fixtures ----------------------------------------------------------------------------------------------------
    String n(){return String.format("%08d",ThreadLocalRandom.current().nextInt(100_000_000));}
    byte[] fixture(String name,String n){return fixture(name,n,StandardCharsets.UTF_8);}
    byte[] fixture(String name,String n,Charset cs){
        try{
            String text=new String(new ClassPathResource("import/"+name).getContentAsByteArray(),cs);
            StringBuilder letters=new StringBuilder();for(char c:n.toCharArray())letters.append((char)('a'+(c-'0')));
            return text.replace("%N%",n).replace("%L%",letters).replace("%U%",letters.toString().toUpperCase()).getBytes(cs);
        }catch(IOException e){throw new IllegalStateException(e);}
    }
    ImportValidationService.Staged stage(byte[] bytes){return service.stage(iligan,"synthetic.csv",bytes,null);}
    /** Same content, different bytes: a blank line is ignored by the parser but changes the checksum. */
    byte[] withTrailingNewline(byte[] bytes){return (new String(bytes,StandardCharsets.UTF_8)+"\r\n").getBytes(StandardCharsets.UTF_8);}
    String number(String n,String id){return "SYN-"+n+"-"+id;}
    Map<String,Object> row(long batch,String studentNumber){return db.queryForMap("SELECT * FROM import_rows WHERE batch_id=? AND raw_values->>'Student No'=?",batch,studentNumber);}
    String status(long batch,String studentNumber){return (String)row(batch,studentNumber).get("status");}
    List<String> codes(long batch,String studentNumber){return db.queryForList("SELECT e.error_code FROM import_exceptions e JOIN import_rows r ON r.id=e.import_row_id WHERE r.batch_id=? AND r.raw_values->>'Student No'=? ORDER BY e.id",String.class,batch,studentNumber);}
    List<String> codesAt(long batch,int rowNumber){return db.queryForList("SELECT error_code FROM import_exceptions WHERE batch_id=? AND row_number=? ORDER BY id",String.class,batch,rowNumber);}
    JsonNode normalized(long batch,String studentNumber)throws Exception{return json.readTree(row(batch,studentNumber).get("normalized_values").toString());}
    JsonNode evidence(long batch,String studentNumber)throws Exception{return json.readTree(row(batch,studentNumber).get("duplicate_evidence").toString());}
    long students(){return db.queryForObject("SELECT count(*) FROM students",Long.class);}
    ResponseStatusException status(Throwable t){return (ResponseStatusException)t;}

    // ---- staging ------------------------------------------------------------------------------------------------------
    @Test void stagingWritesBatchRowsAndSnapshotButNeverStudentsAndKeepsPersonalDataOutOfAudits(){
        String n=n();long before=students();var result=stage(fixture("synthetic-basic.csv",n));
        assertThat(students()).isEqualTo(before);
        assertThat(result).isEqualTo(new ImportValidationService.Staged(result.batchId(),"validated",3,3,0,0,0));
        var batch=db.queryForMap("SELECT * FROM import_batches WHERE id=?",result.batchId());
        assertThat(batch).containsEntry("status","validated").containsEntry("source_name","synthetic.csv").containsEntry("source_type","legacy_student_csv").containsEntry("campus_id",iligan);
        assertThat(((Number)batch.get("source_count")).intValue()).isEqualTo(3);assertThat(((Number)batch.get("imported_count")).intValue()).isZero();
        assertThat(batch.get("checksum").toString()).hasSize(64);assertThat(batch.get("mapping_snapshot").toString()).contains("studentNumber","realFileValidated");assertThat(batch.get("created_by")).isEqualTo(registrar);
        assertThat(db.queryForList("SELECT row_number FROM import_rows WHERE batch_id=? ORDER BY row_number",Integer.class,result.batchId())).containsExactly(2,3,4);
        assertThat(row(result.batchId(),number(n,"01"))).containsEntry("campus_id",iligan).containsEntry("status","valid");
        assertThat(row(result.batchId(),number(n,"01")).get("raw_values").toString()).contains("Alphaone");
        assertThat(db.queryForObject("SELECT count(*) FROM import_rows WHERE batch_id=? AND student_id IS NOT NULL",Integer.class,result.batchId())).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='import_rows' AND (new_data::text LIKE '%Alphaone%' OR new_data::text LIKE '%raw_values%' OR new_data::text LIKE '%normalized_values%')",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='import_rows' AND operation='INSERT' AND actor_id=?",Integer.class,registrar)).isGreaterThanOrEqualTo(3);
    }
    @Test void normalizesMixedDatesCasingAccentsAndWhitespaceAndReportsEachRowProblem()throws Exception{
        String n=n();var r=stage(fixture("synthetic-mixed.csv",n));long b=r.batchId();
        assertThat(r.valid()).isEqualTo(3);assertThat(r.invalid()).isEqualTo(14);assertThat(r.flagged()).isZero();
        var v=normalized(b,number(n,"11"));
        assertThat(v.get("lastName").asText()).as("name casing is off by default").isEqualTo("GARCIA"+n.chars().mapToObj(c->""+(char)('A'+(c-'0'))).reduce("",String::concat));
        assertThat(v.get("firstName").asText()).isEqualTo("JOSÉ MARIA");assertThat(v.get("birthDate").asText()).isEqualTo("2009-03-14");assertThat(v.get("previousSchool").asText()).isEqualTo("Escuela Synthetic");
        assertThat(v.get("voucherCode").asText()).isEqualTo("QVR");assertThat(v.get("programCode").asText()).isEqualTo("STEM");assertThat(v.get("track").asText()).isEqualTo("ACADEMIC");assertThat(v.get("sex").asText()).isEqualTo("male");assertThat(v.get("campusId").asLong()).isEqualTo(iligan);
        assertThat(normalized(b,number(n,"12")).get("lastName").asText()).startsWith("peña");assertThat(normalized(b,number(n,"12")).get("firstName").asText()).isEqualTo("ana lyn");assertThat(normalized(b,number(n,"12")).get("middleName").asText()).isEqualTo("dela Cruz");
        assertThat(normalized(b,number(n,"13")).get("lastName").asText()).startsWith("Quoted, Name");assertThat(normalized(b,number(n,"13")).get("previousSchool").asText()).isEqualTo("School \"Synthetic\" Annex");
        Map<String,String> expected=new LinkedHashMap<>();
        expected.put("14","INVALID_DATE");expected.put("15","INVALID_DATE");expected.put("16","INVALID_DATE");expected.put("17","FUTURE_DATE");expected.put("18","INVALID_DATE");expected.put("19","INVALID_DATE");expected.put("20","INVALID_DATE");
        expected.put("21","INVALID_LRN");expected.put("22","REQUIRED_MISSING");expected.put("23","UNMAPPED_VALUE");expected.put("24","UNMAPPED_VALUE");expected.put("27","COLUMN_COUNT_MISMATCH");
        expected.forEach((id,code)->{assertThat(status(b,number(n,id))).as(id).isEqualTo("invalid");assertThat(codes(b,number(n,id))).as(id).containsExactly(code);});
        assertThat(codes(b,number(n,"25"))).containsExactly("REQUIRED_MISSING","REQUIRED_MISSING");
        assertThat(db.queryForList("SELECT e.error_code FROM import_exceptions e JOIN import_rows r ON r.id=e.import_row_id WHERE r.batch_id=? AND r.raw_values->>'Student No'=''",String.class,b)).containsExactly("REQUIRED_MISSING");
        var detail=db.queryForMap("SELECT e.*,r.row_number AS rn FROM import_exceptions e JOIN import_rows r ON r.id=e.import_row_id WHERE r.batch_id=? AND e.error_code='INVALID_LRN'",b);
        assertThat(detail).containsEntry("field_name","lrn").containsEntry("severity","error").containsEntry("row_number",detail.get("rn"));
        assertThat(db.queryForList("SELECT detail FROM import_exceptions WHERE batch_id=?",String.class,b)).allSatisfy(d->assertThat(d.length()).isBetween(1,1000)).noneMatch(d->d.contains(n)||d.contains("2009")||d.contains("40251"));
        assertThat(db.queryForObject("SELECT count(*) FROM import_rows WHERE batch_id=? AND status='invalid' AND normalized_values IS NOT NULL",Integer.class,b)).isZero();
        assertThat(db.queryForMap("SELECT valid_count,invalid_count,flagged_count,source_count FROM import_batches WHERE id=?",b)).containsEntry("valid_count",3).containsEntry("invalid_count",14).containsEntry("source_count",17);
    }

    // ---- duplicates ---------------------------------------------------------------------------------------------------
    @Test void flagsSamePersonRepeatedInOneFileAndRefusesAStudentNumberUsedForDifferentPeople()throws Exception{
        String n=n();var r=stage(fixture("synthetic-duplicates.csv",n));long b=r.batchId();
        assertThat(status(b,number(n,"01"))).isEqualTo("duplicate_flagged");assertThat(status(b,number(n,"02"))).isEqualTo("duplicate_flagged");
        assertThat(evidence(b,number(n,"01")).get(0).get("rule").asText()).isEqualTo("LRN_EXACT");assertThat(evidence(b,number(n,"01")).get(0).get("kind").asText()).isEqualTo("row");assertThat(evidence(b,number(n,"01")).get(0).get("rowNumber").asInt()).isEqualTo(3);
        assertThat(status(b,number(n,"03"))).isEqualTo("duplicate_flagged");assertThat(status(b,number(n,"04"))).isEqualTo("duplicate_flagged");
        assertThat(evidence(b,number(n,"03")).get(0).get("rule").asText()).isEqualTo("NAME_DOB");assertThat(evidence(b,number(n,"03")).get(0).get("nameScore").asDouble()).isBetween(0.8,1.0);
        assertThat(status(b,number(n,"05"))).isEqualTo("valid");
        var firstSix=db.queryForList("SELECT status FROM import_rows WHERE batch_id=? AND raw_values->>'Student No'=? ORDER BY row_number",String.class,b,number(n,"06"));
        assertThat(firstSix).containsExactly("valid","invalid");
        assertThat(db.queryForList("SELECT error_code FROM import_exceptions WHERE batch_id=? AND row_number=8",String.class,b)).containsExactly("DUPLICATE_IN_BATCH");
        assertThat(db.queryForList("SELECT error_code FROM import_exceptions WHERE batch_id=? AND severity='warning'",String.class,b)).hasSize(4).containsOnly("DUPLICATE_CANDIDATE");
        assertThat(db.queryForMap("SELECT valid_count,invalid_count,flagged_count FROM import_batches WHERE id=?",b)).containsEntry("valid_count",2).containsEntry("invalid_count",1).containsEntry("flagged_count",4);
    }
    long student(String number,String lrn,String first,String last,String birth,long campus){
        return db.queryForObject("INSERT INTO students(student_number,lrn,first_name,last_name,birth_date,campus_id) VALUES (?,?,?,?,?::date,?) RETURNING id",Long.class,number,lrn,first,last,birth,campus);
    }
    @Test void flagsRowsMatchingExistingStudentsApplicationsAndNeverTouchesThem()throws Exception{
        String n=n();String letters=n.chars().mapToObj(c->""+(char)('a'+(c-'0'))).reduce("",String::concat);
        long lrnStudent=student("OLD-"+n+"-A","99"+n+"01","Different","Person","2001-01-01",iligan);
        long nameStudent=student("OLD-"+n+"-B",null,"Ben","Betatwo"+letters,"2008-11-02",iligan);
        long conflict=student(number(n,"03"),null,"Someone","Else","1999-09-09",iligan);
        long year=db.queryForObject("INSERT INTO academic_years(label,starts_on,ends_on) VALUES (?,'2026-06-01','2027-05-31') RETURNING id",Long.class,"Y"+n);
        long term=db.queryForObject("INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES (?,'T1','Synthetic term','2026-06-01','2026-12-31') RETURNING id",Long.class,year);
        long program=db.queryForObject("SELECT id FROM programs WHERE code='STEM'",Long.class);
        var snapshot=db.queryForList("SELECT * FROM students WHERE id IN (?,?,?)",lrnStudent,nameStudent,conflict);long before=students();
        var r=stage(fixture("synthetic-basic.csv",n));long b=r.batchId();
        assertThat(students()).isEqualTo(before);assertThat(db.queryForList("SELECT * FROM students WHERE id IN (?,?,?)",lrnStudent,nameStudent,conflict)).isEqualTo(snapshot);
        assertThat(status(b,number(n,"01"))).isEqualTo("duplicate_flagged");var lrn=evidence(b,number(n,"01")).get(0);
        assertThat(lrn.get("kind").asText()).isEqualTo("student");assertThat(lrn.get("rule").asText()).isEqualTo("LRN_EXACT");assertThat(lrn.get("id").asLong()).isEqualTo(lrnStudent);assertThat(lrn.get("campusVisible").asBoolean()).isTrue();
        assertThat(status(b,number(n,"02"))).isEqualTo("duplicate_flagged");assertThat(evidence(b,number(n,"02")).get(0).get("rule").asText()).isEqualTo("NAME_DOB");
        assertThat(status(b,number(n,"03"))).as("student number owned by a different person").isEqualTo("invalid");assertThat(codes(b,number(n,"03"))).containsExactly("STUDENT_NUMBER_CONFLICT");
        // an open application for the same person is also a duplicate candidate
        long application=db.queryForObject("INSERT INTO applications(application_number,campus_id,term_id,program_id,category,first_name,last_name,birth_date,lrn) VALUES (?,?,?,?,'new','Ann','Applicant','2009-03-14',?) RETURNING id",Long.class,"APP-"+n,iligan,term,program,"99"+n+"02");
        var again=stage(withTrailingNewline(fixture("synthetic-basic.csv",n)));
        var app=evidence(again.batchId(),number(n,"02"));
        assertThat(java.util.stream.StreamSupport.stream(app.spliterator(),false).map(e->e.get("kind").asText()+":"+e.get("rule").asText())).contains("application:LRN_EXACT");
        assertThat(application).isPositive();
    }
    @Test void sameLearnerInAnotherCampusIsFlaggedWithoutRevealingThatCampusToAnUnauthorizedReviewer()throws Exception{
        String n=n();long other=student("OLD-"+n,"99"+n+"71","Olivia","Overlap"+n.chars().mapToObj(c->""+(char)('a'+(c-'0'))).reduce("",String::concat),"2009-09-09",bugo);
        var r=stage(fixture("synthetic-overlap-b.csv",n));long b=r.batchId();
        assertThat(status(b,"SYN-B-"+n+"-01")).isEqualTo("duplicate_flagged");var hidden=evidence(b,"SYN-B-"+n+"-01").get(0);
        assertThat(hidden.get("campusVisible").asBoolean()).isFalse();assertThat(hidden.has("id")).isFalse();assertThat(hidden.get("rule").asText()).isEqualTo("LRN_EXACT");
        assertThat(db.queryForList("SELECT detail FROM import_exceptions WHERE batch_id=? AND severity='warning'",String.class,b)).anyMatch(d->d.contains("campus you cannot view"));
        assertThat(status(b,"SYN-B-"+n+"-02")).isEqualTo("valid");
        as(admin);var seen=service.stage(iligan,"admin.csv",withTrailingNewline(fixture("synthetic-overlap-b.csv",n)),null);
        var visible=evidence(seen.batchId(),"SYN-B-"+n+"-01").get(0);assertThat(visible.get("campusVisible").asBoolean()).isTrue();assertThat(visible.get("id").asLong()).isEqualTo(other);
    }
    @Test void sameLearnerStagedForTwoCampusesIsFlaggedOnTheSecondUploadButNotOnACorrectedSameCampusReupload()throws Exception{
        String n=n();as(bugoRegistrar);var first=service.stage(bugo,"bugo.csv",fixture("synthetic-overlap-a.csv",n),null);
        assertThat(first.valid()).isEqualTo(2);
        as(registrar);var second=stage(fixture("synthetic-overlap-b.csv",n));
        assertThat(status(second.batchId(),"SYN-B-"+n+"-01")).isEqualTo("duplicate_flagged");var e=evidence(second.batchId(),"SYN-B-"+n+"-01").get(0);
        assertThat(e.get("kind").asText()).isEqualTo("staged_row");assertThat(e.get("campusVisible").asBoolean()).isFalse();assertThat(e.has("batchId")).as("another campus's batch id is not revealed").isFalse();
        assertThat(status(second.batchId(),"SYN-B-"+n+"-02")).isEqualTo("valid");
        assertThat(db.queryForObject("SELECT flagged_count FROM import_batches WHERE id=?",Integer.class,first.batchId())).as("the first batch is not rewritten").isZero();
        // a corrected re-upload for the same campus is not flagged against its own earlier staging
        var fixed=stage(withTrailingNewline(fixture("synthetic-overlap-b.csv",n)));
        assertThat(fixed.batchId()).isNotEqualTo(second.batchId());assertThat(fixed.flagged()).isEqualTo(1);   // still flagged vs the BUGO batch, but not vs the earlier ILIGAN batch
        assertThat(db.queryForObject("SELECT count(*) FROM jsonb_array_elements((SELECT duplicate_evidence FROM import_rows WHERE batch_id=? AND status='duplicate_flagged')) e WHERE e->>'kind'='staged_row'",Integer.class,fixed.batchId())).isEqualTo(1);
    }

    @Test void historicalStudentsWithoutACampusAreStillMatchedButNeverShownAsVisible()throws Exception{
        String n=n();db.update("INSERT INTO students(student_number,lrn,first_name,last_name,birth_date) VALUES (?,?,'Hist','Orical','1990-01-01')","OLD-"+n,"99"+n+"01");
        var r=stage(fixture("synthetic-basic.csv",n));var e=evidence(r.batchId(),number(n,"01")).get(0);
        assertThat(status(r.batchId(),number(n,"01"))).isEqualTo("duplicate_flagged");assertThat(e.get("campusVisible").asBoolean()).isFalse();assertThat(e.has("id")).isFalse();
    }

    // ---- campus column ------------------------------------------------------------------------------------------------
    @Test void optionalCampusColumnMustAgreeWithTheUploadCampusAndOtherCampusDataIsNotRetainedForUnauthorizedUploaders()throws Exception{
        String n=n();var r=stage(fixture("synthetic-campus-column.csv",n));long b=r.batchId();
        assertThat(status(b,number(n,"01"))).isEqualTo("valid");assertThat(status(b,number(n,"04"))).as("blank campus falls back to the upload's campus").isEqualTo("valid");
        assertThat(codesAt(b,3)).containsExactly("CAMPUS_MISMATCH");assertThat(codesAt(b,4)).containsExactly("UNKNOWN_CAMPUS");
        assertThat(db.queryForObject("SELECT raw_values::text FROM import_rows WHERE batch_id=? AND row_number=3",String.class,b)).as("another campus's row is not retained for a registrar without that campus").isEqualTo("{}");
        as(admin);var adminBatch=service.stage(iligan,"admin.csv",withTrailingNewline(fixture("synthetic-campus-column.csv",n)),null);
        assertThat(codesAt(adminBatch.batchId(),3)).containsExactly("CAMPUS_MISMATCH");assertThat(row(adminBatch.batchId(),number(n,"02")).get("raw_values").toString()).contains("Campusother");
    }

    // ---- file-level failures and idempotency -----------------------------------------------------------------------------
    @Test void identicalBytesAreRefusedWithTheExistingBatchWhileFailedBatchesDoNotBlockAReupload()throws Exception{
        String n=n();var bytes=fixture("synthetic-basic.csv",n);var first=stage(bytes);
        assertThat(catchThrowableOfType(ImportConflictException.class,()->stage(bytes))).satisfies(e->{assertThat(e.batchId()).isEqualTo(first.batchId());assertThat(e.status()).isEqualTo("validated");});
        assertThat(db.queryForObject("SELECT count(*) FROM import_batches WHERE checksum=?",Integer.class,db.queryForObject("SELECT checksum FROM import_batches WHERE id=?",String.class,first.batchId()))).isEqualTo(1);
        var broken=fixture("synthetic-missing-header.csv",n);
        var one=catchThrowableOfType(ImportFileException.class,()->stage(broken));var two=catchThrowableOfType(ImportFileException.class,()->stage(broken));
        assertThat(one.batchId()).isNotNull().isNotEqualTo(two.batchId());
    }
    @Test void missingAmbiguousAndMisencodedHeadersFailTheFileAndAreRecordedWithoutRows()throws Exception{
        String n=n();long before=students();
        var missing=catchThrowableOfType(ImportFileException.class,()->stage(fixture("synthetic-missing-header.csv",n)));
        assertThat(missing.error()).isEqualTo(FileError.MISSING_COLUMN);assertThat(missing.getMessage()).contains("birthDate");
        var ambiguous=catchThrowableOfType(ImportFileException.class,()->stage(fixture("synthetic-ambiguous-header.csv",n)));assertThat(ambiguous.error()).isEqualTo(FileError.AMBIGUOUS_HEADER);
        var cp1252=fixture("synthetic-windows-1252.csv",n,Charset.forName("windows-1252"));
        var wrong=catchThrowableOfType(ImportFileException.class,()->stage(cp1252));assertThat(wrong.error()).isEqualTo(FileError.INVALID_ENCODING);
        for(var e:List.of(missing,ambiguous,wrong)){
            assertThat(db.queryForMap("SELECT status,source_count,imported_count FROM import_batches WHERE id=?",e.batchId())).containsEntry("status","failed").containsEntry("source_count",0);
            assertThat(db.queryForList("SELECT error_code FROM import_exceptions WHERE batch_id=? AND row_number IS NULL AND import_row_id IS NULL",String.class,e.batchId())).containsExactly(e.error().name());
            assertThat(db.queryForObject("SELECT count(*) FROM import_rows WHERE batch_id=?",Integer.class,e.batchId())).isZero();
        }
        assertThat(students()).isEqualTo(before);
    }
    @Test void sizeAndRowLimitsAnswer413WithoutPersistingAnything()throws Exception{
        var tiny=new ImportValidationService(named,json,access,mappings,new ImportLimits(200,2,64,1000),transactions);
        long batches=db.queryForObject("SELECT count(*) FROM import_batches",Long.class);String n=n();
        var big=catchThrowableOfType(ImportFileException.class,()->tiny.stage(iligan,"big.csv",fixture("synthetic-basic.csv",n),null));
        assertThat(big.error()).isEqualTo(FileError.FILE_TOO_LARGE);assertThat(big.error().status).isEqualTo(413);assertThat(big.batchId()).isNull();
        var rows=new ImportValidationService(named,json,access,mappings,new ImportLimits(5_000_000,2,64,1000),transactions);
        var many=catchThrowableOfType(ImportFileException.class,()->rows.stage(iligan,"rows.csv",fixture("synthetic-basic.csv",n),null));
        assertThat(many.error()).isEqualTo(FileError.TOO_MANY_ROWS);assertThat(many.error().status).isEqualTo(413);assertThat(many.batchId()).isNull();
        assertThat(db.queryForObject("SELECT count(*) FROM import_batches",Long.class)).isEqualTo(batches);
    }
    @Test void largeFilesStageInReasonableTimeWithBucketedDuplicateChecks(){
        var rnd=new Random(7);StringBuilder csv=new StringBuilder("Student No,LRN,Last Name,First Name,Birth Date,Voucher,Strand\r\n");String n=n();
        for(int i=0;i<3000;i++){csv.append("SYN-").append(n).append('-').append(i).append(",,").append(word(rnd,12)).append(',').append(word(rnd,9)).append(",20").append(String.format("%02d-%02d-%02d",rnd.nextInt(10),1+rnd.nextInt(12),1+rnd.nextInt(28))).append(",QVR,STEM\r\n");}
        long start=System.nanoTime();var r=stage(csv.toString().getBytes(StandardCharsets.UTF_8));long seconds=(System.nanoTime()-start)/1_000_000_000L;
        assertThat(r.sourceCount()).isEqualTo(3000);assertThat(r.valid()+r.invalid()+r.flagged()).isEqualTo(3000);assertThat(r.invalid()).isZero();assertThat(seconds).isLessThan(60);
    }
    String word(Random rnd,int length){StringBuilder s=new StringBuilder();for(int i=0;i<length;i++)s.append((char)('a'+rnd.nextInt(26)));return s.toString();}

    // ---- authorization ------------------------------------------------------------------------------------------------
    @Test void onlyHoldersOfImportsManageForThatCampusMayStage(){
        String n=n();
        assertThat(status(catchThrowable(()->service.stage(bugo,"x.csv",fixture("synthetic-basic.csv",n),null))).getStatusCode().value()).isEqualTo(403);
        as(nobody);assertThat(status(catchThrowable(()->stage(fixture("synthetic-basic.csv",n)))).getStatusCode().value()).isEqualTo(403);
        assertThat(status(catchThrowable(()->mappings.list())).getStatusCode().value()).isEqualTo(403);
        assertThat(db.queryForObject("SELECT count(*) FROM import_batches WHERE campus_id=? AND source_name='x.csv'",Integer.class,bugo)).isZero();
    }

    // ---- mappings -----------------------------------------------------------------------------------------------------
    ObjectNode copy(JsonNode node){return (ObjectNode)node.deepCopy();}
    ImportMappingService.MappingInput input(String name,JsonNode mapping,JsonNode settings){return new ImportMappingService.MappingInput(name,mapping,settings,false,true,false);}
    ImportMappingService.StoredMapping base(){return mappings.forUpload(null);}
    @Test void seededDefaultMappingPassesItsOwnValidationAndIsAssumedNotValidated(){
        var d=base();assertThat(mappings.validate(d.mapping(),d.settings())).isEmpty();assertThat(d.isDefault()).isTrue();assertThat(d.realFileValidated()).isFalse();
        assertThat(d.mapping().at("/fields/campus/required").asBoolean()).as("campus is optional").isFalse();assertThat(d.mapping().at("/fields/voucher/required").asBoolean()).isTrue();assertThat(d.mapping().at("/fields/specialization/required").asBoolean()).isTrue();
        assertThat(d.settings().get("dateFormats")).extracting(JsonNode::asText).containsExactly("yyyy-MM-dd","MM/dd/yyyy");
        assertThat(mappings.list()).isNotEmpty();assertThat(mappings.get(d.id()).name()).isEqualTo(d.name());
    }
    @Test void onlyAdministratorsMayCreateOrEditMappingsRegistrarsMayListThem(){
        var d=base();
        assertThat(status(catchThrowable(()->mappings.create(input("reg-"+n(),d.mapping(),d.settings())))).getStatusCode().value()).isEqualTo(403);
        assertThat(status(catchThrowable(()->mappings.update(d.id(),d.version(),input(d.name(),d.mapping(),d.settings())))).getStatusCode().value()).isEqualTo(403);
        as(admin);var created=mappings.create(input("admin-"+n(),d.mapping(),d.settings()));assertThat(created.isDefault()).isFalse();assertThat(created.version()).isZero();assertThat(created.realFileValidated()).isFalse();
        assertThat(db.queryForObject("SELECT created_by FROM import_column_mappings WHERE id=?",Long.class,created.id())).isEqualTo(admin);
        as(registrar);assertThat(mappings.list()).extracting(ImportMappingService.StoredMapping::id).contains(created.id());
    }
    @Test void saveValidationRejectsUnknownFieldsAliasCollisionsBadTargetsAndSettings(){
        var d=base();as(admin);
        java.util.function.Function<java.util.function.Consumer<ObjectNode>,List<String>> mapped=edit->{var m=copy(d.mapping());edit.accept(m);return mappings.validate(m,d.settings());};
        java.util.function.Function<java.util.function.Consumer<ObjectNode>,List<String>> settings=edit->{var s=copy(d.settings());edit.accept(s);return mappings.validate(d.mapping(),s);};
        assertThat(mapped.apply(m->((ObjectNode)m.get("fields")).putObject("nickname"))).anyMatch(p->p.contains("Unknown field"));
        assertThat(mapped.apply(m->((ObjectNode)m.get("fields").get("firstName")).withArray("aliases").add("LRN"))).anyMatch(p->p.contains("used by both"));
        assertThat(mapped.apply(m->((ObjectNode)m.get("fields")).remove("birthDate"))).anyMatch(p->p.contains("'birthDate' is missing"));
        assertThat(mapped.apply(m->((ObjectNode)m.get("fields").get("voucher")).put("required",false))).anyMatch(p->p.contains("cannot be made optional"));
        assertThat(mapped.apply(m->((ObjectNode)m.get("fields").get("voucher")).putArray("aliases"))).anyMatch(p->p.contains("at least one alias"));
        assertThat(mapped.apply(m->((ObjectNode)m.get("valueMaps").get("voucher")).put("Legacy A","NOPE"))).anyMatch(p->p.contains("unknown code"));
        assertThat(mapped.apply(m->((ObjectNode)m.get("valueMaps").get("specialization")).put("Legacy B","NOPE"))).anyMatch(p->p.contains("unknown code"));
        assertThat(mapped.apply(m->((ObjectNode)m.get("valueMaps").get("campus")).put("Main","NOPE"))).anyMatch(p->p.contains("unknown code"));
        assertThat(mapped.apply(m->{((ObjectNode)m.get("valueMaps").get("voucher")).put("Reg","REGULAR");((ObjectNode)m.get("valueMaps").get("voucher")).put(" reg ","ESC");})).anyMatch(p->p.contains("more than once"));
        assertThat(mapped.apply(m->((ObjectNode)m.get("valueMaps").get("voucher")).put("Legacy A","regular"))).as("valid targets, case-insensitive").isEmpty();
        assertThat(settings.apply(s->s.put("delimiter","|"))).anyMatch(p->p.contains("delimiter"));
        assertThat(settings.apply(s->s.put("encoding","UTF-16"))).anyMatch(p->p.contains("encoding"));
        assertThat(settings.apply(s->s.put("headerRow",0))).anyMatch(p->p.contains("headerRow"));
        assertThat(settings.apply(s->s.put("surprise",true))).anyMatch(p->p.contains("Unknown setting"));
        assertThat(settings.apply(s->s.putArray("dateFormats").add("MM/dd/yyyy").add("dd/MM/yyyy"))).anyMatch(p->p.contains("ambiguous"));
        assertThat(mappings.validate(json.createArrayNode(),d.settings())).isNotEmpty();
        assertThat(status(catchThrowable(()->mappings.create(input("bad",d.mapping(),copy(d.settings()).put("delimiter","|"))))).getStatusCode().value()).isEqualTo(400);
    }
    @Test void mappingEditsAreVersionedNamedUniquelyAndKeepTheDefaultAndInUseGuards()throws Exception{
        var d=base();as(admin);String name="edit-"+n();var m=mappings.create(input(name,d.mapping(),d.settings()));
        assertThat(status(catchThrowable(()->mappings.create(input(name.toUpperCase(),d.mapping(),d.settings())))).getStatusCode().value()).isEqualTo(409);
        var edited=mappings.update(m.id(),0,input(name+"-b",m.mapping(),m.settings()));assertThat(edited.version()).isEqualTo(1);assertThat(edited.name()).isEqualTo(name+"-b");
        assertThat(status(catchThrowable(()->mappings.update(m.id(),0,input(name,m.mapping(),m.settings())))).getStatusCode().value()).isEqualTo(409);
        assertThat(status(catchThrowable(()->mappings.update(d.id(),d.version(),input(d.name(),d.mapping(),d.settings())))).getStatusCode().value()).as("the default cannot simply be unset").isEqualTo(409);
        var inUse=mappings.update(edited.id(),1,input(edited.name(),edited.mapping(),edited.settings()));
        as(registrar);service.stage(iligan,"uses-mapping.csv",fixture("synthetic-basic.csv",n()),inUse.id());as(admin);
        assertThat(status(catchThrowable(()->mappings.update(inUse.id(),inUse.version(),new ImportMappingService.MappingInput(inUse.name(),inUse.mapping(),inUse.settings(),false,false,false)))).getStatusCode().value()).isEqualTo(409);
        var inactive=mappings.create(new ImportMappingService.MappingInput("inactive-"+n(),d.mapping(),d.settings(),false,false,false));
        as(registrar);assertThat(catchThrowableOfType(ImportFileException.class,()->service.stage(iligan,"x.csv",fixture("synthetic-basic.csv",n()),inactive.id())).error()).isEqualTo(FileError.UNKNOWN_MAPPING);
        assertThat(catchThrowableOfType(ImportFileException.class,()->service.stage(iligan,"x.csv",fixture("synthetic-basic.csv",n()),-5L)).error()).isEqualTo(FileError.UNKNOWN_MAPPING);
    }
    @Test void changingAMappingAffectsOnlyNewBatchesBecauseEachBatchKeepsItsSnapshot()throws Exception{
        var d=base();as(admin);String n=n();
        var custom=copy(d.mapping());((ObjectNode)custom.get("fields").get("studentNumber")).putArray("aliases").add("student_no");
        var mapping=mappings.create(input("snapshot-"+n,custom,d.settings()));
        as(registrar);var oldFile=fixture("synthetic-basic.csv",n);var before=service.stage(iligan,"before.csv",oldFile,mapping.id());
        var snapshotBefore=db.queryForObject("SELECT mapping_snapshot::text FROM import_batches WHERE id=?",String.class,before.batchId());var rowsBefore=db.queryForList("SELECT row_number,status,normalized_values::text FROM import_rows WHERE batch_id=? ORDER BY row_number",before.batchId());
        as(admin);var renamed=copy(mapping.mapping());((ObjectNode)renamed.get("fields").get("studentNumber")).putArray("aliases").add("matric_no");
        var updated=mappings.update(mapping.id(),mapping.version(),input(mapping.name(),renamed,mapping.settings()));assertThat(updated.version()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT mapping_snapshot::text FROM import_batches WHERE id=?",String.class,before.batchId())).isEqualTo(snapshotBefore);
        assertThat(db.queryForList("SELECT row_number,status,normalized_values::text FROM import_rows WHERE batch_id=? ORDER BY row_number",before.batchId())).isEqualTo(rowsBefore);
        assertThat(json.readTree(snapshotBefore).at("/mapping/fields/studentNumber/aliases/0").asText()).isEqualTo("student_no");assertThat(json.readTree(snapshotBefore).get("mappingVersion").asInt()).isZero();
        as(registrar);var again=n();var failure=catchThrowableOfType(ImportFileException.class,()->service.stage(iligan,"after.csv",fixture("synthetic-basic.csv",again),mapping.id()));
        assertThat(failure.error()).as("the old header no longer matches the edited mapping").isEqualTo(FileError.MISSING_COLUMN);
        var renamedFile=new String(fixture("synthetic-basic.csv",again),StandardCharsets.UTF_8).replace("Student No","Matric No").getBytes(StandardCharsets.UTF_8);
        var after=service.stage(iligan,"after.csv",renamedFile,mapping.id());assertThat(after.valid()).isEqualTo(3);
        assertThat(json.readTree(db.queryForObject("SELECT mapping_snapshot::text FROM import_batches WHERE id=?",String.class,after.batchId())).get("mappingVersion").asInt()).isEqualTo(1);
    }
}
