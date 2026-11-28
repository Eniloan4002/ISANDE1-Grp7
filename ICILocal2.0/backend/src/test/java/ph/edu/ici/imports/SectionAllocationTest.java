package ph.edu.ici.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import ph.edu.ici.PostgresSupport;
import ph.edu.ici.auth.StaffIdentity;
import ph.edu.ici.imports.ImportFileException.FileError;
import ph.edu.ici.imports.ImportMappingService.MappingInput;
import ph.edu.ici.imports.SectionAllocationValidationService.UploadContext;
import static ph.edu.ici.imports.ImportFixtures.n;
import static org.assertj.core.api.Assertions.*;

/**
 * Section allocation import (ICI2-39 phase 3): mapping, staging, review, commit and discard over embedded PostgreSQL. Staging goes through the
 * service with an in-code CSV grid (the CSV reader stands in for the .xlsx reader of a later phase); everything after staging uses real HTTP.
 * All data is synthetic and the layout is assumed.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class SectionAllocationTest {
    static final String BASE="/api/section-allocation-imports",PASSWORD="Alloc-Test-Password-2026!";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->PostgresSupport.DATABASE.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");r.add("ici.auth.secret",()->PostgresSupport.SECRET);r.add("ici.bootstrap.enabled",()->false);
    }
    @Autowired TestRestTemplate rest;@Autowired JdbcTemplate db;@Autowired PasswordEncoder encoder;@Autowired ObjectMapper json;
    @Autowired SectionAllocationValidationService service;@Autowired SectionAllocationMappingService mappings;@Autowired ImportLimits limits;
    long iligan,bugo,registrar,bugoRegistrar,admin;String registrarToken,bugoToken,teacherToken,importOnlyToken;

    long staff(String role,Long campus){
        String name="alloc-"+UUID.randomUUID();long id=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES (?,?,?,?) RETURNING id",Long.class,name,name+"@example.invalid","Synthetic "+role,encoder.encode(PASSWORD));
        if(campus!=null)db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,?,?)",id,role,campus);else db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) SELECT ?,?,id FROM campuses",id,role);
        return id;
    }
    String login(long id){return (String)rest.postForObject("/api/auth/login",Map.of("username",db.queryForObject("SELECT username FROM users WHERE id=?",String.class,id),"password",PASSWORD),Map.class).get("accessToken");}
    void as(long id){var identity=new StaffIdentity(id,"u"+id,"Synthetic staff",List.of(),UUID.randomUUID(),Instant.now().plusSeconds(600),false);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(identity,null,List.of()));}
    @BeforeEach void users(){
        iligan=db.queryForObject("SELECT id FROM campuses WHERE code='ILIGAN'",Long.class);bugo=db.queryForObject("SELECT id FROM campuses WHERE code='BUGO'",Long.class);
        registrar=staff("ROLE_REGISTRAR",iligan);registrarToken=login(registrar);bugoRegistrar=staff("ROLE_REGISTRAR",bugo);bugoToken=login(bugoRegistrar);
        admin=staff("ROLE_ADMIN",null);teacherToken=login(staff("ROLE_TEACHER",iligan));
        db.update("INSERT INTO roles(code,name) VALUES ('ROLE_ALLOC_IMPORT_ONLY','Synthetic') ON CONFLICT DO NOTHING");
        db.update("INSERT INTO role_permissions(role_code,permission_code) VALUES ('ROLE_ALLOC_IMPORT_ONLY','imports.manage'),('ROLE_ALLOC_IMPORT_ONLY','imports.read') ON CONFLICT DO NOTHING");
        importOnlyToken=login(staff("ROLE_ALLOC_IMPORT_ONLY",iligan));
        as(registrar);
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    // ---- synthetic setup -----------------------------------------------------------------------------------------------
    /** One term with three Grade 11 STEM sections (Alpha holds 2, Beta 1, Gamma 2), one Grade 12 section and one ABM section. */
    record Setup(String n,long year,long term,long program,long abm,long alpha,long beta,long gamma,long grade12,long abmSection){}
    Setup setup(){
        String n=n();
        long year=db.queryForObject("INSERT INTO academic_years(label,starts_on,ends_on) VALUES (?,'2026-06-01','2027-03-31') RETURNING id",Long.class,"SY"+n);
        long term=db.queryForObject("INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES (?,'S1','Synthetic S1','2026-06-01','2026-10-31') RETURNING id",Long.class,year);
        long program=db.queryForObject("SELECT id FROM programs WHERE code='STEM'",Long.class),abm=db.queryForObject("SELECT id FROM programs WHERE code='ABM'",Long.class);
        return new Setup(n,year,term,program,abm,section(term,program,"Alpha","Grade 11",2),section(term,program,"Beta","Grade 11",1),section(term,program,"Gamma","Grade 11",2),
            section(term,program,"Twelve","Grade 12",5),section(term,abm,"Business","Grade 11",5));
    }
    long section(long term,long program,String name,String grade,int capacity){return db.queryForObject("INSERT INTO sections(campus_id,term_id,program_id,name,grade_level,capacity) VALUES (?,?,?,?,?,?) RETURNING id",Long.class,iligan,term,program,name,grade,capacity);}
    long student(Setup s,String id){return student(s,id,iligan,true);}
    long student(Setup s,String id,long campus,boolean active){
        return db.queryForObject("INSERT INTO students(student_number,first_name,last_name,birth_date,campus_id,active) VALUES (?,?,?,'2009-01-01',?,?) RETURNING id",Long.class,number(s,id),"Stu"+id,"Syn"+id+ImportFixtures.letters(s.n()),campus,active);
    }
    String number(Setup s,String id){return "AL-"+s.n()+"-"+id;}
    /** An enrollment of the student in the setup's term. {@code status} is provisional, enrolled or withdrawn; {@code section} may be null. */
    long enrollment(Setup s,long student,String status,Long section){
        long app=db.queryForObject("INSERT INTO applications(application_number,campus_id,term_id,program_id,student_id,category,first_name,last_name,birth_date,status,profile) VALUES (?,?,?,?,?,'new','Synthetic','Applicant','2009-01-01','pre_enlisted','{\"gradeLevel\":\"Grade 11\"}') RETURNING id",Long.class,"APP-"+UUID.randomUUID(),iligan,s.term(),s.program(),student);
        // The enrollment guard demands present attendance and every mandatory credential before a row may become "enrolled".
        db.update("INSERT INTO orientation_attendance(application_id,attendance_date,status,recorded_by,version) VALUES (?,current_date,'present',?,1)",app,registrar);
        db.update("INSERT INTO application_requirements(application_id,requirement_code,status,verified_by) SELECT ?,code,'submitted',? FROM requirement_types WHERE mandatory",app,registrar);
        long id=db.queryForObject("INSERT INTO enrollments(student_id,application_id,campus_id,term_id,program_id,section_id,status) VALUES (?,?,?,?,?,?,'provisional') RETURNING id",Long.class,student,app,iligan,s.term(),s.program(),section);
        if(!"provisional".equals(status))db.update("UPDATE enrollments SET status=?,attendance_verified_at=now(),attendance_verified_by=?,confirmed_at=now(),confirmed_by=? WHERE id=?",status,registrar,registrar,id);
        return id;
    }
    long enrolled(Setup s,String id,Long section){return enrollment(s,student(s,id),"enrolled",section);}
    Long sectionOf(long enrollment){return db.queryForObject("SELECT section_id FROM enrollments WHERE id=?",Long.class,enrollment);}
    int enrollmentVersion(long enrollment){return db.queryForObject("SELECT version FROM enrollments WHERE id=?",Integer.class,enrollment);}

    byte[] csv(Setup s,String... rows){StringBuilder b=new StringBuilder("Student No,Section\r\n");for(String r:rows)b.append(r.replace("%N%",s.n())).append("\r\n");return b.toString().getBytes(StandardCharsets.UTF_8);}
    String line(Setup s,String id,String section){return number(s,id)+","+section;}
    SectionAllocationValidationService.Staged stage(Setup s,byte[] bytes){return service.stage(iligan,new UploadContext(null,s.term()),"synthetic-allocation.csv",bytes,new CsvGridReader(limits),null);}
    long staged(Setup s,String... rows){return stage(s,csv(s,rows)).batchId();}
    ImportFileException fileError(Setup s,byte[] bytes){return catchThrowableOfType(ImportFileException.class,()->stage(s,bytes));}

    Map<String,Object> row(long batch,int rowNumber){return db.queryForMap("SELECT * FROM import_rows WHERE batch_id=? AND row_number=?",batch,rowNumber);}
    String status(long batch,int rowNumber){return (String)row(batch,rowNumber).get("status");}
    List<String> codes(long batch,int rowNumber){return db.queryForList("SELECT error_code FROM import_exceptions WHERE batch_id=? AND row_number=? ORDER BY id",String.class,batch,rowNumber);}
    JsonNode normalized(long batch,int rowNumber)throws Exception{return json.readTree(row(batch,rowNumber).get("normalized_values").toString());}
    JsonNode evidence(long batch,int rowNumber)throws Exception{return json.readTree(row(batch,rowNumber).get("duplicate_evidence").toString());}
    long rowId(long batch,int rowNumber){return db.queryForObject("SELECT id FROM import_rows WHERE batch_id=? AND row_number=?",Long.class,batch,rowNumber);}
    int rowVersion(long row){return db.queryForObject("SELECT version FROM import_rows WHERE id=?",Integer.class,row);}
    int batchVersion(long batch){return db.queryForObject("SELECT version FROM import_batches WHERE id=?",Integer.class,batch);}
    String batchStatus(long batch){return db.queryForObject("SELECT status FROM import_batches WHERE id=?",String.class,batch);}
    long count(String sql,Object... args){return db.queryForObject(sql,Long.class,args);}

    ResponseEntity<Map> call(HttpMethod method,String path,Object body,String token){var h=new HttpHeaders();if(token!=null)h.setBearerAuth(token);h.setContentType(MediaType.APPLICATION_JSON);return rest.exchange(BASE+path,method,new HttpEntity<>(body,h),Map.class);}
    ResponseEntity<Map> get(String path,String token){return call(HttpMethod.GET,path,null,token);}
    ResponseEntity<Map> review(String token,long batch,long row,String decision,String reason,Long studentId){
        Map<String,Object> body=new HashMap<>();body.put("version",rowVersion(row));body.put("decision",decision);body.put("reason",reason);if(studentId!=null)body.put("studentId",studentId);
        return call(HttpMethod.POST,"/batches/"+batch+"/rows/"+row+"/review",body,token);
    }
    ResponseEntity<Map> commit(String token,long batch,int expected){return call(HttpMethod.POST,"/batches/"+batch+"/commit",Map.of("version",batchVersion(batch),"expectedValidCount",expected),token);}
    ResponseEntity<Map> discard(String token,long batch,String reason){return call(HttpMethod.POST,"/batches/"+batch+"/discard",Map.of("version",batchVersion(batch),"reason",reason),token);}

    // ---- mapping -------------------------------------------------------------------------------------------------------
    @Test void seededDefaultIsAssumedUnvalidatedAndSeparateFromTheOtherImportMappings(){
        var seeded=mappings.forUpload(null);
        assertThat(seeded.isDefault()).isTrue();assertThat(seeded.realFileValidated()).isFalse();
        assertThat(seeded.mapping().path("valueMaps").path("section")).isEmpty();
        assertThat(mappings.validate(seeded.mapping(),seeded.settings())).isEmpty();
        assertThat(mappings.list()).allMatch(m->db.queryForObject("SELECT source_type FROM import_column_mappings WHERE id=?",String.class,m.id()).equals("section_allocation"));
        assertThat(get("/mappings",registrarToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/mappings",teacherToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    @Test void mappingValidationListsEveryProblemAndChecksOnlyExistingTargets(){
        var base=mappings.forUpload(null);
        ObjectNode good=base.mapping().deepCopy();((ObjectNode)good.with("valueMaps").with("section")).put("11-A","Alpha");((ObjectNode)good.with("valueMaps").with("program")).put("Science","STEM");
        assertThat(mappings.validate(good,base.settings())).isEmpty();
        ObjectNode bad=base.mapping().deepCopy();ObjectNode settings=base.settings().deepCopy();
        ((ObjectNode)bad.with("valueMaps").with("program")).put("Science","NOSUCHPROGRAM");((ObjectNode)bad.with("valueMaps").with("term")).put("First","NOSUCHTERM");
        ((ObjectNode)bad.with("valueMaps").with("section")).put("A","");
        ((ObjectNode)bad.with("fields").with("lrn")).putArray("aliases").add("student_no");   // alias already owned by studentNumber
        bad.with("fields").remove("section");settings.put("layout","matrix");settings.put("surprise",true);
        var problems=String.join(" | ",mappings.validate(bad,settings));
        assertThat(problems).contains("NOSUCHPROGRAM").contains("NOSUCHTERM").contains("section names").contains("student_no").contains("'section' is missing").contains("layout must be").contains("Unknown setting 'surprise'");
        ObjectNode noIdentifier=base.mapping().deepCopy();noIdentifier.with("fields").remove("studentNumber");noIdentifier.with("fields").remove("lrn");
        assertThat(String.join(" ",mappings.validate(noIdentifier,base.settings()))).contains("studentNumber or lrn");
        ObjectNode optionalSection=base.mapping().deepCopy();((ObjectNode)optionalSection.with("fields").with("section")).put("required",false);
        assertThat(String.join(" ",mappings.validate(optionalSection,base.settings()))).contains("cannot be made optional");
    }

    // ---- staging -------------------------------------------------------------------------------------------------------
    @Test void everyRowOutcomeIsStagedWithItsOwnCodeAndNothingIsWritten()throws Exception{
        var s=setup();
        enrolled(s,"ok",null);                                                     // valid
        enrolled(s,"same",s.alpha());                                              // already in Alpha
        long moved=enrolled(s,"move",s.beta());                                    // in Beta, file says Gamma
        enrollment(s,student(s,"prov"),"provisional",null);                        // not confirmed
        enrollment(s,student(s,"gone"),"withdrawn",null);                          // closed
        student(s,"none");                                                         // student without an enrollment
        enrolled(s,"unknown",null);
        enrolled(s,"grade",null);                                                  // Grade 11 applicant, file names the Grade 12 section
        enrolled(s,"program",null);                                                // STEM applicant, file names the ABM section
        long history=enrolled(s,"history",s.gamma());                              // has class memberships
        long subject=db.queryForObject("INSERT INTO subjects(code,name) VALUES (?,'Synthetic') RETURNING id",Long.class,"ALSUB"+s.n());
        long offering=db.queryForObject("INSERT INTO class_offerings(section_id,term_id,subject_id,teacher_id) VALUES (?,?,?,?) RETURNING id",Long.class,s.gamma(),s.term(),subject,registrar);
        db.update("INSERT INTO class_memberships(class_id,enrollment_id,section_id,term_id) VALUES (?,?,?,?)",offering,history,s.gamma(),s.term());
        student(s,"inactive",iligan,false);student(s,"other",bugo,true);
        enrolled(s,"dup",null);db.update("UPDATE sections SET capacity=4 WHERE id=?",s.alpha());
        long beforeEnrollments=count("SELECT count(*) FROM enrollments WHERE version>0");
        var staged=stage(s,csv(s,line(s,"ok","Alpha"),line(s,"same","alpha"),line(s,"move","Gamma"),line(s,"prov","Alpha"),line(s,"gone","Alpha"),line(s,"none","Alpha"),
            line(s,"unknown","No such section"),line(s,"grade","Twelve"),line(s,"program","Business"),line(s,"history","Alpha"),line(s,"inactive","Alpha"),line(s,"other","Alpha"),
            "AL-"+s.n()+"-missing,Alpha",line(s,"dup","Alpha"),line(s,"dup","Gamma")));
        long b=staged.batchId();
        assertThat(staged.sourceCount()).isEqualTo(15);
        assertThat(status(b,2)).isEqualTo("valid");assertThat(codes(b,2)).isEmpty();assertThat(normalized(b,2).path("action").asText()).isEqualTo("assign");
        assertThat(status(b,3)).isEqualTo("valid");assertThat(codes(b,3)).containsExactly("ALREADY_ASSIGNED");assertThat(normalized(b,3).path("action").asText()).isEqualTo("noop");
        assertThat(status(b,4)).isEqualTo("duplicate_flagged");assertThat(codes(b,4)).containsExactly("REASSIGNMENT");
        assertThat(evidence(b,4).get(0).path("kind").asText()).isEqualTo("reassignment");assertThat(evidence(b,4).get(0).path("fromSectionId").asLong()).isEqualTo(s.beta());assertThat(evidence(b,4).get(0).path("toSectionId").asLong()).isEqualTo(s.gamma());
        assertThat(codes(b,5)).containsExactly("NOT_CONFIRMED");assertThat(status(b,5)).isEqualTo("invalid");
        assertThat(codes(b,6)).containsExactly("ENROLLMENT_CLOSED");
        assertThat(codes(b,7)).containsExactly("ENROLLMENT_NOT_FOUND");
        assertThat(codes(b,8)).containsExactly("UNKNOWN_SECTION");
        assertThat(codes(b,9)).containsExactly("GRADE_MISMATCH");
        assertThat(codes(b,10)).containsExactly("SECTION_INCOMPATIBLE");
        assertThat(codes(b,11)).containsExactly("ACADEMIC_HISTORY_EXISTS");
        assertThat(codes(b,12)).containsExactly("STUDENT_INACTIVE");
        assertThat(codes(b,13)).containsExactly("STUDENT_NOT_FOUND");                 // another campus: never disclosed
        assertThat(codes(b,14)).containsExactly("STUDENT_NOT_FOUND");
        assertThat(status(b,15)).isEqualTo("valid");assertThat(status(b,16)).isEqualTo("invalid");assertThat(codes(b,16)).containsExactly("DUPLICATE_IN_BATCH");
        assertThat(staged.valid()).isEqualTo(3);assertThat(staged.flagged()).isEqualTo(1);assertThat(staged.invalid()).isEqualTo(11);
        assertThat(count("SELECT count(*) FROM enrollments WHERE version>0")).isEqualTo(beforeEnrollments);   // staging is a dry run
        assertThat(sectionOf(moved)).isEqualTo(s.beta());
        assertThat(db.queryForList("SELECT detail FROM import_exceptions WHERE batch_id=?",String.class,b)).noneMatch(d->d.contains(s.n()));   // messages never echo identifiers
        assertThat(db.queryForObject("SELECT source_format FROM import_batches WHERE id=?",String.class,b)).isEqualTo("csv");
    }
    @Test void fileColumnsThatDisagreeWithTheEnrollmentAreContextMismatches(){
        var s=setup();
        enrolled(s,"level",null);enrolled(s,"prog",null);enrolled(s,"fine",null);
        var base=mappings.forUpload(null);long mapping;
        as(admin);try{mapping=mappings.create(new MappingInput("Synthetic "+UUID.randomUUID(),base.mapping(),base.settings(),false,true,false)).id();}finally{as(registrar);}
        byte[] bytes=("Student No,Section,Grade Level,Program\r\n"+line(s,"level","Alpha")+",Grade 12,STEM\r\n"+line(s,"prog","Alpha")+",Grade 11,ABM\r\n"+line(s,"fine","Alpha")+",11,stem\r\n").getBytes(StandardCharsets.UTF_8);
        long b=service.stage(iligan,new UploadContext(null,s.term()),"context.csv",bytes,new CsvGridReader(limits),mapping).batchId();
        assertThat(codes(b,2)).containsExactly("CONTEXT_MISMATCH");assertThat(codes(b,3)).containsExactly("CONTEXT_MISMATCH");
        assertThat(codes(b,4)).as("codes").isEmpty();assertThat(status(b,4)).isEqualTo("valid");           // "11" equals "Grade 11"; program codes compare case-insensitively
    }
    @Test void seatsAreCountedAcrossTheBatchInFileOrderAndSwapsBetweenFullSectionsAreFeasible()throws Exception{
        var s=setup();
        enrolled(s,"f1",null);enrolled(s,"f2",null);enrolled(s,"f3",null);                         // three unassigned students, Beta holds only 1
        long b=staged(s,line(s,"f1","Beta"),line(s,"f2","Beta"),line(s,"f3","Alpha"));
        assertThat(status(b,2)).isEqualTo("valid");
        assertThat(status(b,3)).isEqualTo("invalid");assertThat(codes(b,3)).containsExactly("SECTION_FULL");
        assertThat(db.queryForObject("SELECT detail FROM import_exceptions WHERE batch_id=? AND error_code='SECTION_FULL'",String.class,b)).contains("1 seats").doesNotContain("Stu");
        assertThat(status(b,4)).isEqualTo("valid");
        // A swap between two sections that are both full: each student leaves one seat and takes the other.
        var t=setup();long x=enrolled(t,"x",t.beta());                                              // Beta (capacity 1) full
        db.update("UPDATE sections SET capacity=1 WHERE id=?",t.alpha());long y=enrolled(t,"y",t.alpha());   // Alpha now full too
        long swap=staged(t,line(t,"x","Alpha"),line(t,"y","Beta"));
        assertThat(codes(swap,2)).containsExactly("REASSIGNMENT");assertThat(codes(swap,3)).containsExactly("REASSIGNMENT");
        assertThat(status(swap,2)).isEqualTo("duplicate_flagged");assertThat(status(swap,3)).isEqualTo("duplicate_flagged");
        // One side leaving to a full third section is refused, so the other side no longer has a seat either.
        var u=setup();enrolled(u,"p",u.beta());enrolled(u,"q",u.alpha());db.update("UPDATE sections SET capacity=1 WHERE id=?",u.alpha());
        enrolled(u,"r",u.gamma());enrolled(u,"r2",u.gamma());                                     // Gamma (capacity 2) full
        long chain=staged(u,line(u,"p","Gamma"),line(u,"q","Beta"));
        assertThat(codes(chain,2)).contains("SECTION_FULL");assertThat(status(chain,2)).isEqualTo("invalid");
        assertThat(codes(chain,3)).contains("SECTION_FULL");assertThat(status(chain,3)).isEqualTo("invalid");       // Beta stays full because p never leaves it
    }
    @Test void fileLevelErrorsFailTheWholeFileAndAreRecorded(){
        var s=setup();enrolled(s,"a",null);
        var matrix=fileError(s,"Student No,Alpha,Beta\r\nX,1,\r\n".getBytes(StandardCharsets.UTF_8));
        assertThat(matrix.error()).isEqualTo(FileError.UNMAPPED_COLUMN);assertThat(matrix.batchId()).isNotNull();
        assertThat(batchStatus(matrix.batchId())).isEqualTo("failed");assertThat(matrix.problems()).extracting(ImportFileException.Problem::header).contains("Alpha","Beta");
        assertThat(fileError(s,"Student No\r\nX\r\n".getBytes(StandardCharsets.UTF_8)).error()).isEqualTo(FileError.MISSING_COLUMN);
        assertThat(fileError(s,("Student No,Section\r\n"+number(s,"a")+",Alpha\r\nStudent No,Section\r\n").getBytes(StandardCharsets.UTF_8)).error()).isEqualTo(FileError.REPEATED_HEADER);
        assertThat(fileError(s,"Student No,Section,Student No\r\nX,Alpha,Y\r\n".getBytes(StandardCharsets.UTF_8)).error()).isEqualTo(FileError.AMBIGUOUS_HEADER);
        var noTerm=catchThrowableOfType(ImportFileException.class,()->service.stage(iligan,new UploadContext(null,null),"x.csv",csv(s,line(s,"a","Alpha")),new CsvGridReader(limits),null));
        assertThat(noTerm.error()).isEqualTo(FileError.MISSING_COLUMN);
    }
    @Test void stagingNeedsBothImportAndEnrollmentPermissionsAtTheCampus(){
        var s=setup();enrolled(s,"a",null);byte[] bytes=csv(s,line(s,"a","Alpha"));
        as(bugoRegistrar);
        assertThatThrownBy(()->service.stage(iligan,new UploadContext(null,s.term()),"x.csv",bytes,new CsvGridReader(limits),null)).hasMessageContaining("cannot import section allocations");
        long importOnly=staff("ROLE_ALLOC_IMPORT_ONLY",iligan);as(importOnly);
        assertThatThrownBy(()->service.stage(iligan,new UploadContext(null,s.term()),"x.csv",bytes,new CsvGridReader(limits),null)).hasMessageContaining("cannot import section allocations");
        as(registrar);assertThat(stage(s,bytes).valid()).isEqualTo(1);
    }

    // ---- review, commit, discard ---------------------------------------------------------------------------------------
    @Test void commitPlacesStudentsThroughTheEnrollmentServiceAndIsIdempotent()throws Exception{
        var s=setup();
        long a=enrolled(s,"a",null),b2=enrolled(s,"b",null),same=enrolled(s,"same",s.alpha());
        long batch=staged(s,line(s,"a","Alpha"),line(s,"b","Gamma"),line(s,"same","Alpha"));
        int versionBefore=enrollmentVersion(a);
        assertThat(commit(importOnlyToken,batch,3).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(commit(bugoToken,batch,3).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(commit(registrarToken,batch,2).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);                   // expectedValidCount must match
        var done=commit(registrarToken,batch,3);
        assertThat(done.getStatusCode()).as(done.toString()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody()).containsEntry("status","imported").containsEntry("assigned",2).containsEntry("reassigned",0).containsEntry("alreadyAssigned",1).containsEntry("alreadyImported",false);
        assertThat(sectionOf(a)).isEqualTo(s.alpha());assertThat(sectionOf(b2)).isEqualTo(s.gamma());assertThat(sectionOf(same)).isEqualTo(s.alpha());
        assertThat(enrollmentVersion(a)).isEqualTo(versionBefore+1);assertThat(enrollmentVersion(same)).isEqualTo(0);                  // a no-op writes nothing
        assertThat(status(batch,2)).isEqualTo("imported");assertThat(status(batch,4)).isEqualTo("imported");
        var applied=json.readTree(row(batch,2).get("applied").toString());
        assertThat(applied.path("action").asText()).isEqualTo("assign");assertThat(applied.path("enrollmentId").asLong()).isEqualTo(a);assertThat(applied.path("toSectionId").asLong()).isEqualTo(s.alpha());assertThat(applied.path("versionAfter").asInt()).isEqualTo(versionBefore+1);
        assertThat(json.readTree(row(batch,4).get("applied").toString()).path("noop").asBoolean()).isTrue();
        // the enrollment's own audit trail shows the committing user, not the import
        assertThat(count("SELECT count(*) FROM audit_logs WHERE table_name='enrollments' AND (record_id::jsonb)->>'id'=? AND actor_id=? AND operation='UPDATE'",String.valueOf(a),registrar)).isGreaterThanOrEqualTo(1);
        assertThat(db.queryForObject("SELECT row_to_json(r)::text FROM (SELECT * FROM audit_logs WHERE table_name='import_rows' ORDER BY id DESC LIMIT 1) r",String.class)).doesNotContain("applied","normalized_values","raw_values");
        var again=commit(registrarToken,batch,3);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(again.getBody()).containsEntry("alreadyImported",true).containsEntry("assigned",2).containsEntry("alreadyAssigned",1);
        assertThat(enrollmentVersion(a)).isEqualTo(versionBefore+1);
        var detail=get("/batches/"+batch,registrarToken);assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(detail.getBody()).containsEntry("sourceFormat","csv");
        assertThat(get("/batches/"+batch+"/rows",registrarToken).getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(get("/batches/"+batch+"/errors",registrarToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/batches/"+batch,bugoToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);assertThat(get("/batches/"+batch,teacherToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/batches/"+batch,importOnlyToken).getStatusCode()).isEqualTo(HttpStatus.OK);                                  // reading needs only imports.read
        assertThat(stageAgain(s,batch)).isInstanceOf(ImportConflictException.class);                                                   // the same bytes cannot be staged twice
    }
    Throwable stageAgain(Setup s,long batch){return catchThrowable(()->stage(s,csv(s,line(s,"a","Alpha"),line(s,"b","Gamma"),line(s,"same","Alpha"))));}
    @Test void reassignmentsNeedAnApplyDecisionAndSwapsBetweenFullSectionsCommit(){
        var s=setup();
        long x=enrolled(s,"x",s.beta());db.update("UPDATE sections SET capacity=1 WHERE id=?",s.alpha());long y=enrolled(s,"y",s.alpha());
        long batch=staged(s,line(s,"x","Alpha"),line(s,"y","Beta"));
        assertThat(commit(registrarToken,batch,0).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);                                  // flagged rows block the commit
        assertThat(review(registrarToken,batch,rowId(batch,2),"apply","",null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);   // a reason is required
        assertThat(review(registrarToken,batch,rowId(batch,2),"distinct","no",null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(review(registrarToken,batch,rowId(batch,2),"same_person","no",1L).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);   // not an identity row
        assertThat(review(bugoToken,batch,rowId(batch,2),"apply","move",null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(review(registrarToken,batch,rowId(batch,2),"apply","Registrar approved the swap",null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(review(registrarToken,batch,rowId(batch,3),"apply","Registrar approved the swap",null).getStatusCode()).isEqualTo(HttpStatus.OK);
        var done=commit(registrarToken,batch,2);
        assertThat(done.getStatusCode()).as(done.toString()).isEqualTo(HttpStatus.OK);assertThat(done.getBody()).containsEntry("reassigned",2).containsEntry("assigned",0);
        assertThat(sectionOf(x)).isEqualTo(s.alpha());assertThat(sectionOf(y)).isEqualTo(s.beta());
        assertThat(count("SELECT count(*) FROM audit_logs WHERE table_name='enrollments' AND (record_id::jsonb)->>'id'=? AND operation='UPDATE'",String.valueOf(x))).isGreaterThanOrEqualTo(2);   // unassign, then assign
    }
    @Test void skippedRowsAreLeftOutAndAnEmptyBatchCannotCommit(){
        var s=setup();long a=enrolled(s,"a",null);long m=enrolled(s,"m",s.beta());
        long batch=staged(s,line(s,"a","Alpha"),line(s,"m","Gamma"));
        assertThat(review(registrarToken,batch,rowId(batch,3),"skip","Leave as it is",null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(review(registrarToken,batch,rowId(batch,3),"skip","again",null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);   // stale row version is reloaded by the helper, but skipped rows cannot be skipped
        var done=commit(registrarToken,batch,1);assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(done.getBody()).containsEntry("skipped",1).containsEntry("assigned",1);
        assertThat(sectionOf(a)).isEqualTo(s.alpha());assertThat(sectionOf(m)).isEqualTo(s.beta());
        var t=setup();enrolled(t,"only",null);long onlySkipped=staged(t,line(t,"only","Alpha"));
        assertThat(review(registrarToken,onlySkipped,rowId(onlySkipped,2),"skip","skip all",null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(commit(registrarToken,onlySkipped,0).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
    @Test void aFlaggedIdentityIsLinkedToACandidateStudentAndTheRowThenPlacesThem()throws Exception{
        var s=setup();long target=student(s,"who");enrollment(s,target,"enrolled",null);
        byte[] bytes=("Student No,Last Name,First Name,Section\r\n"+number(s,"who")+",Completely,Different,Alpha\r\n").getBytes(StandardCharsets.UTF_8);
        long batch=service.stage(iligan,new UploadContext(null,s.term()),"identity.csv",bytes,new CsvGridReader(limits),null).batchId();
        assertThat(status(batch,2)).isEqualTo("duplicate_flagged");assertThat(codes(batch,2)).containsExactly("NAME_MISMATCH");
        assertThat(evidence(batch,2).get(0).path("kind").asText()).isEqualTo("student");
        assertThat(review(registrarToken,batch,rowId(batch,2),"apply","not a move",null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(review(registrarToken,batch,rowId(batch,2),"same_person","Wrong student",target+1000).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(review(registrarToken,batch,rowId(batch,2),"same_person","Same learner, name spelled differently in the old file",target).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(commit(registrarToken,batch,1).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(db.queryForObject("SELECT section_id FROM enrollments WHERE student_id=? AND term_id=?",Long.class,target,s.term())).isEqualTo(s.alpha());
    }
    @Test void rowsThatChangedAfterStagingAreDemotedAndNothingIsImported()throws Exception{
        var s=setup();
        long a=enrolled(s,"a",null),b2=enrolled(s,"b",null),c=enrolled(s,"c",null);
        long batch=staged(s,line(s,"a","Alpha"),line(s,"b","Alpha"),line(s,"c","Gamma"));
        // a: someone placed the student in Beta by hand; b: the enrollment was withdrawn; c is untouched.
        db.update("UPDATE enrollments SET section_id=?,version=version+1 WHERE id=?",s.beta(),a);
        db.update("UPDATE enrollments SET status='withdrawn',version=version+1 WHERE id=?",b2);
        var stale=commit(registrarToken,batch,3);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(batchStatus(batch)).isEqualTo("validated");assertThat(sectionOf(c)).isNull();                                    // nothing applied, not even the untouched row
        assertThat(status(batch,2)).isEqualTo("duplicate_flagged");assertThat(codes(batch,2)).contains("CHANGED_SINCE_STAGING");
        assertThat(evidence(batch,2).get(0).path("kind").asText()).isEqualTo("reassignment");assertThat(normalized(batch,2).path("action").asText()).isEqualTo("reassign");
        assertThat(status(batch,3)).isEqualTo("invalid");assertThat(codes(batch,3)).contains("ENROLLMENT_CLOSED");
        assertThat(status(batch,4)).isEqualTo("valid");
        assertThat(count("SELECT count(*) FROM import_rows WHERE batch_id=? AND status='imported'",batch)).isZero();
        // The registrar accepts what is true now; the refreshed picture is what the decision approves.
        assertThat(review(registrarToken,batch,rowId(batch,2),"apply","Beta was a stopgap",null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(review(registrarToken,batch,rowId(batch,3),"skip","withdrawn",null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);   // invalid rows cannot be skipped
        var retry=commit(registrarToken,batch,2);assertThat(retry.getStatusCode()).as(retry.toString()).isEqualTo(HttpStatus.OK);
        assertThat(sectionOf(a)).isEqualTo(s.alpha());assertThat(sectionOf(c)).isEqualTo(s.gamma());assertThat(sectionOf(b2)).isNull();
    }
    @Test void aRefusalByTheEnrollmentServiceRollsBackTheWholeBatchAndNamesTheRow(){
        var s=setup();
        long a=enrolled(s,"a",null),b2=enrolled(s,"b",null);
        long batch=staged(s,line(s,"a","Gamma"),line(s,"b","Gamma"));                                                                // Gamma holds 2: both fit when staged
        enrolled(s,"intruder",s.gamma());                                                                                            // a seat is taken after staging, without touching a or b
        var refused=commit(registrarToken,batch,2);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(refused.getBody().get("detail"))).contains("Row 3").contains("full").contains("Nothing was imported");
        assertThat(sectionOf(a)).isNull();assertThat(sectionOf(b2)).isNull();                                                        // the first placement was rolled back too
        assertThat(batchStatus(batch)).isEqualTo("validated");assertThat(count("SELECT count(*) FROM import_rows WHERE batch_id=? AND status='imported'",batch)).isZero();
    }
    @Test void aCommitRacingAManualAssignmentNeverOverfillsASection()throws Exception{
        var s=setup();long a=enrolled(s,"a",null);enrolled(s,"b",s.beta());long other=enrolled(s,"c",null);
        db.update("UPDATE sections SET capacity=2 WHERE id=?",s.beta());
        long batch=staged(s,line(s,"a","Beta"));
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var start=new java.util.concurrent.CountDownLatch(1);
            var commit=executor.submit(()->{start.await();return commit(registrarToken,batch,1).getStatusCode().value();});
            var manual=executor.submit(()->{start.await();var h=new HttpHeaders();h.setBearerAuth(registrarToken);h.setContentType(MediaType.APPLICATION_JSON);
                return rest.exchange("/api/enrollments/"+other+"/section",HttpMethod.PUT,new HttpEntity<>(Map.of("sectionId",s.beta(),"version",0),h),Map.class).getStatusCode().value();});
            start.countDown();int imported=commit.get(),placed=manual.get();
            assertThat(count("SELECT count(*) FROM enrollments WHERE section_id=?",s.beta())).isLessThanOrEqualTo(2);
            assertThat(List.of(imported,placed)).contains(200);
        }
    }
    @Test void discardFreesTheChecksumAndKeepsTheRows(){
        var s=setup();enrolled(s,"a",null);byte[] bytes=csv(s,line(s,"a","Alpha"));
        long batch=stage(s,bytes).batchId();
        assertThat(catchThrowable(()->stage(s,bytes))).isInstanceOf(ImportConflictException.class);
        assertThat(discard(teacherToken,batch,"x").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);assertThat(discard(bugoToken,batch,"x").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.POST,"/batches/"+batch+"/discard",Map.of("version",batchVersion(batch),"reason",""),registrarToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var done=discard(registrarToken,batch,"Wrong term chosen");
        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(done.getBody()).containsEntry("status","failed").containsEntry("discarded",true);
        assertThat(count("SELECT count(*) FROM import_rows WHERE batch_id=?",batch)).isEqualTo(1);
        assertThat(commit(registrarToken,batch,1).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);                                  // a discarded batch cannot be committed
        assertThat(discard(registrarToken,batch,"again").getBody()).containsEntry("alreadyDiscarded",true);
        assertThat(stage(s,bytes).valid()).isEqualTo(1);                                                                              // the same file can be staged again
        assertThat(get("/batches/"+batch,registrarToken).getBody()).containsEntry("discarded",true).containsEntry("discardReason","Wrong term chosen");
    }
    @Test void aCommittedBatchCannotBeDiscardedOrVoidedAndOtherSourceTypesAreInvisible()throws Exception{
        var s=setup();enrolled(s,"a",null);long batch=staged(s,line(s,"a","Alpha"));
        assertThat(commit(registrarToken,batch,1).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(discard(registrarToken,batch,"too late").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThatThrownBy(()->db.update("UPDATE import_batches SET voided_at=now(),voided_by=?,void_reason='x' WHERE id=?",registrar,batch)).hasMessageContaining("import_batch_void_scope");
        long gradeBatch=db.queryForObject("INSERT INTO import_batches(source_name,checksum,source_count,status,campus_id,source_type) VALUES ('g.csv',substr(md5(random()::text)||md5(random()::text),1,64),0,'failed',?,'legacy_grade_matrix_csv') RETURNING id",Long.class,iligan);
        assertThat(get("/batches/"+gradeBatch,registrarToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        List<Long> listed=((List<?>)get("/batches?campusId="+iligan,registrarToken).getBody().get("items")).stream().map(i->((Number)((Map<?,?>)i).get("id")).longValue()).toList();
        assertThat(listed).contains(batch).doesNotContain(gradeBatch);
    }

    // ---- .xlsx upload (phase 6) ------------------------------------------------------------------------------------------
    byte[] xlsx(Setup s,String[]... rows){List<List<Object>> list=new ArrayList<>();list.add(List.of("Student No","Section"));for(String[] r:rows)list.add(List.of(number(s,r[0]),r[1]));return XlsxFixtures.sheet(list);}
    ResponseEntity<Map> upload(String token,long campus,Setup s,String filename,byte[] bytes){
        var h=new HttpHeaders();if(token!=null)h.setBearerAuth(token);h.setContentType(MediaType.MULTIPART_FORM_DATA);
        var form=new LinkedMultiValueMap<String,Object>();form.add("campusId",Long.toString(campus));form.add("termId",Long.toString(s.term()));
        form.add("file",new ByteArrayResource(bytes){@Override public String getFilename(){return filename;}});
        return rest.exchange(BASE+"/batches",HttpMethod.POST,new HttpEntity<>(form,h),Map.class);
    }

    @Test void anXlsxUploadIsStagedReviewedAndCommittedEndToEnd(){
        var s=setup();long a=enrolled(s,"X1",null),b=enrolled(s,"X2",null);
        // POI stamps the workbook with the current time, so building the file twice can give different bytes; reuse one copy.
        byte[] file=xlsx(s,new String[]{"X1","Alpha"},new String[]{"X2","Beta"});
        var r=upload(registrarToken,iligan,s,"sections.xlsx",file);
        assertThat(r.getStatusCode()).as(r.toString()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("sourceFormat")).isEqualTo("xlsx");assertThat(r.getBody().get("validCount")).isEqualTo(2);
        long batch=((Number)r.getBody().get("id")).longValue();
        assertThat(commit(registrarToken,batch,2).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sectionOf(a)).isEqualTo(s.alpha());assertThat(sectionOf(b)).isEqualTo(s.beta());
        assertThat(upload(registrarToken,iligan,s,"again.xlsx",file).getStatusCode()).as("identical bytes").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test void uploadsAreXlsxOnlyAndRefusedFilesAreRecordedOrNotAsTheirStatusSays(){
        var s=setup();enrolled(s,"Y1",null);
        var csv=upload(registrarToken,iligan,s,"sections.xlsx",csv(s,line(s,"Y1","Alpha")));
        assertThat(csv.getStatusCode()).as("CSV bytes with an .xlsx name").isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        long before=count("SELECT count(*) FROM import_batches WHERE source_type='section_allocation'");
        byte[] merged=XlsxFixtures.workbook(wb->{var sh=wb.createSheet("Sheet1");XlsxFixtures.fill(wb,sh,List.of(List.of("Student No","Section"),List.of(number(s,"Y1"),"Alpha")));sh.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(1,1,0,1));});
        var m=upload(registrarToken,iligan,s,"merged.xlsx",merged);
        assertThat(m.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);assertThat(m.getBody().get("code")).isEqualTo("MERGED_CELLS");
        long failed=((Number)m.getBody().get("batchId")).longValue();
        assertThat(batchStatus(failed)).isEqualTo("failed");assertThat(db.queryForObject("SELECT source_format FROM import_batches WHERE id=?",String.class,failed)).isEqualTo("xlsx");
        byte[] ole2=new byte[512];byte[] magic={(byte)0xD0,(byte)0xCF,0x11,(byte)0xE0,(byte)0xA1,(byte)0xB1,0x1A,(byte)0xE1};System.arraycopy(magic,0,ole2,0,8);
        var legacy=upload(registrarToken,iligan,s,"old.xls",ole2);
        assertThat(legacy.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);assertThat(legacy.getBody().get("code")).isEqualTo("UNSUPPORTED_FORMAT");
        byte[] bomb=XlsxFixtures.rezip(xlsx(s,new String[]{"Y1","Alpha"}),Map.of("xl/media/padding.bin",new byte[4_000_000]),x->x);
        var big=upload(registrarToken,iligan,s,"bomb.xlsx",bomb);
        assertThat(big.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);assertThat(big.getBody().get("code")).isEqualTo("XLSX_LIMIT_EXCEEDED");
        assertThat(count("SELECT count(*) FROM import_batches WHERE source_type='section_allocation'")).as("two recorded failures, nothing for the 413 or 415").isEqualTo(before+2);
    }

    @Test void uploadPermissionsFollowTheCampusAndTheEnrollmentImportPermission(){
        var s=setup();enrolled(s,"Z1",null);byte[] file=xlsx(s,new String[]{"Z1","Alpha"});
        assertThat(upload(teacherToken,iligan,s,"z.xlsx",file).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(upload(importOnlyToken,iligan,s,"z.xlsx",file).getStatusCode()).as("imports.manage is not enough").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(upload(bugoToken,iligan,s,"z.xlsx",file).getStatusCode()).as("registrar of another campus").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(upload(null,iligan,s,"z.xlsx",file).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(count("SELECT count(*) FROM import_batches WHERE source_type='section_allocation' AND file_size_bytes=?",(long)file.length)).isZero();
    }
}
