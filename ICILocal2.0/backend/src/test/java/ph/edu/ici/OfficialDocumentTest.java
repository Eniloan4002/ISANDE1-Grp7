package ph.edu.ici;

import java.util.*;
import java.nio.file.*;
import java.security.MessageDigest;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class OfficialDocumentTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->PostgresSupport.DATABASE.getJdbcUrl("postgres","postgres"));
        r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");
        r.add("ici.auth.secret",()->PostgresSupport.SECRET);r.add("ici.bootstrap.enabled",()->false);
    }
    @Autowired TestRestTemplate rest;@Autowired JdbcTemplate db;@Autowired PasswordEncoder encoder;
    final String password="Document-Test-Password-2026!";final String root="/api/official-documents";
    long campus,foreignCampus,program,term,section,student,enrollment,grade,registrar;String token,foreignToken,teacherToken;
    String key(){return UUID.randomUUID().toString().substring(0,8);}
    long staff(String role,long scope){String name="doc-"+key();long id=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES(?,?,?,?) RETURNING id",Long.class,name,name+"@example.invalid",name,encoder.encode(password));db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES(?,?,?)",id,role,scope);return id;}
    String login(long id){return (String)rest.postForObject("/api/auth/login",Map.of("username",db.queryForObject("SELECT username FROM users WHERE id=?",String.class,id),"password",password),Map.class).get("accessToken");}
    HttpHeaders headers(String bearer){var h=new HttpHeaders();h.setBearerAuth(bearer);h.setContentType(MediaType.APPLICATION_JSON);return h;}
    ResponseEntity<Map> call(HttpMethod method,String path,Object input,String bearer){return rest.exchange(root+path,method,new HttpEntity<>(input,headers(bearer)),Map.class);}
    ResponseEntity<byte[]> pdf(String path,Object body,HttpMethod method){return rest.exchange(root+path,method,new HttpEntity<>(body,headers(token)),byte[].class);}
    @BeforeEach void setup(){
        campus=db.queryForObject("SELECT id FROM campuses WHERE code='ILIGAN'",Long.class);foreignCampus=db.queryForObject("SELECT id FROM campuses WHERE code='BUGO'",Long.class);
        program=db.queryForObject("INSERT INTO programs(code,name,category) VALUES(?,'Document test program','SHS') RETURNING id",Long.class,"DOC"+key());
        registrar=staff("ROLE_REGISTRAR",campus);token=login(registrar);foreignToken=login(staff("ROLE_REGISTRAR",foreignCampus));long teacher=staff("ROLE_TEACHER",campus);teacherToken=login(teacher);
        long year=db.queryForObject("INSERT INTO academic_years(label,starts_on,ends_on) VALUES(?,'2026-06-01','2027-04-30') RETURNING id",Long.class,"DOC"+key());
        term=db.queryForObject("INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES(?,'T1','First Term','2026-06-01','2026-10-31') RETURNING id",Long.class,year);
        section=db.queryForObject("INSERT INTO sections(campus_id,term_id,program_id,name,grade_level,capacity) VALUES(?,?,?,?,'Grade 11',30) RETURNING id",Long.class,campus,term,program,"Doc "+key());
        student=db.queryForObject("INSERT INTO students(student_number,first_name,last_name,birth_date,campus_id,program_id) VALUES(?,'Peña','Dela Cruz','2009-01-01',?,?) RETURNING id",Long.class,"DOC-"+key(),campus,program);
        long app=db.queryForObject("INSERT INTO applications(application_number,campus_id,term_id,program_id,student_id,category,first_name,last_name,birth_date) VALUES(?,?,?,?,?,'new','Peña','Dela Cruz','2009-01-01') RETURNING id",Long.class,"DOC-"+key(),campus,term,program,student);
        enrollment=db.queryForObject("INSERT INTO enrollments(student_id,application_id,campus_id,term_id,program_id,section_id,status) VALUES(?,?,?,?,?,?,'completed') RETURNING id",Long.class,student,app,campus,term,program,section);
        db.update("INSERT INTO application_requirements(application_id,requirement_code,status) SELECT ?,code,'submitted' FROM requirement_types WHERE mandatory",app);
        long subject=db.queryForObject("INSERT INTO subjects(code,name,units) VALUES(?,'Mathematics - Peña',1) RETURNING id",Long.class,"DOCSUB"+key());
        long offering=db.queryForObject("INSERT INTO class_offerings(section_id,term_id,subject_id,teacher_id) VALUES(?,?,?,?) RETURNING id",Long.class,section,term,subject,teacher);
        db.update("INSERT INTO class_memberships(class_id,enrollment_id,section_id,term_id) VALUES(?,?,?,?)",offering,enrollment,section,term);
        long period=db.queryForObject("INSERT INTO grading_periods(term_id,code,sequence) VALUES(?,'Q1',1) RETURNING id",Long.class,term);
        grade=db.queryForObject("INSERT INTO grades(class_id,enrollment_id,term_id,grading_period_id,value,state) VALUES(?,?,?,?,95,'finalized') RETURNING id",Long.class,offering,enrollment,term,period);
    }
    Map<String,Object> policyInput(String type,boolean required){var m=new LinkedHashMap<String,Object>();m.put("campusId",campus);m.put("programId",program);m.put("documentType",type);m.put("institutionName","Fictional Test School");m.put("institutionAddress","Iligan City - TEST DATA");m.put("signatories","Fictional Registrar / Registrar\nFictional Principal / Principal");m.put("templateReference","TEST LAYOUT - school approval required in production");m.put("soRequired",required);m.put("soPrefix","SO-");m.put("soMaxLength",40);return m;}
    Map<String,Object> create(String type,boolean required){var r=call(HttpMethod.POST,"/policies",policyInput(type,required),token);assertThat(r.getStatusCode()).as(r.toString()).isEqualTo(HttpStatus.CREATED);return r.getBody();}
    Map<String,Object> approve(Map<String,Object> policy){var r=call(HttpMethod.POST,"/policies/"+policy.get("id")+"/approve",Map.of("version",policy.get("version"),"approvalReference","FICTIONAL TEST ONLY - NOT SCHOOL APPROVAL"),token);assertThat(r.getStatusCode()).as(r.toString()).isEqualTo(HttpStatus.OK);return r.getBody();}
    Map<String,Object> selection(Map<String,Object> policy){return Map.of("studentId",student,"policyId",policy.get("id"),"policyVersion",policy.get("version"),"enrollmentIds",List.of(enrollment),"requestKey",UUID.randomUUID().toString());}
    @Test void draftsDoNotIssueUnapprovedPoliciesAndPermissionsRemainScoped()throws Exception{
        var policy=create("FORM137",false);var input=selection(policy);
        var verify=call(HttpMethod.POST,"/verify",input,token);assertThat(verify.getBody()).containsEntry("eligible",false);assertThat(verify.getBody().get("reasons").toString()).contains("TEMPLATE_POLICY_UNAPPROVED");
        assertThat(call(HttpMethod.POST,"/issue",input,token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var preview=pdf("/preview",input,HttpMethod.POST);assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.OK);
        try(var doc=Loader.loadPDF(preview.getBody())){assertThat(new PDFTextStripper().getText(doc)).contains("DRAFT - NOT FOR OFFICIAL RELEASE","Peña","TEMPLATE_POLICY_UNAPPROVED");}
        assertThat(call(HttpMethod.POST,"/verify",input,teacherToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST,"/verify",input,foreignToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
    @Test void approvedOptionalSoIssuesImmutableAuditedSnapshotAndIdempotentDownload()throws Exception{
        var policy=approve(create("TOR",false));var input=selection(policy);
        db.update("INSERT INTO grade_corrections(grade_id,grade_version,original_value,proposed_value,justification,evidence_storage_key,requested_by,status,reviewed_by,reviewed_at,review_reason) VALUES(?,0,94,95,'Applied fictional correction','test-evidence',?,'applied',?,current_timestamp,'Verified fictional correction')",grade,registrar,registrar);
        var r=call(HttpMethod.POST,"/issue",input,token);assertThat(r.getStatusCode()).as(r.toString()).isEqualTo(HttpStatus.CREATED);var issued=r.getBody();String id=issued.get("id").toString();
        assertThat(call(HttpMethod.POST,"/issue",input,token).getBody().get("id")).isEqualTo(id);
        var pdf=pdf("/generations/"+id+"/pdf",null,HttpMethod.GET);assertThat(pdf.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf.getBody()))).isEqualTo(issued.get("sha256"));
        try(var doc=Loader.loadPDF(pdf.getBody())){assertThat(new PDFTextStripper().getText(doc)).contains("TOR - Academic Record","Peña","95.00").doesNotContain("DRAFT - NOT FOR OFFICIAL RELEASE");}
        assertThat(call(HttpMethod.GET,"/generations/"+id,null,foreignToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='official_document_generations' AND record_id=? AND actor_id=? AND NOT jsonb_exists(new_data,'pdf') AND NOT jsonb_exists(new_data,'snapshot')",Integer.class,id,registrar)).isEqualTo(1);
        assertThatThrownBy(()->db.update("UPDATE official_document_generations SET sha256='bad' WHERE id=?::uuid",id)).hasMessageContaining("append-only");
        db.update("UPDATE grades SET value=96,version=version+1 WHERE id=?",grade);
        assertThat(pdf("/generations/"+id+"/pdf",null,HttpMethod.GET).getBody()).isEqualTo(pdf.getBody());
    }
    @Test void requiredSpecialOrdersRespectPolicyAndVersionAndPolicyEditRevokesApproval(){
        var policy=approve(create("FORM137",true));var input=selection(policy);
        assertThat(call(HttpMethod.POST,"/verify",input,token).getBody().get("reasons").toString()).contains("SPECIAL_ORDER_REQUIRED");
        String so="/special-orders/"+enrollment;
        assertThat(call(HttpMethod.PUT,so,Map.of("version",-1,"number","WRONG-001","issuedOn","2026-01-01","issuingAuthority","Fictional authority"),token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST,"/verify",input,token).getBody().get("reasons").toString()).contains("SPECIAL_ORDER_FORMAT");
        var corrected=Map.of("version",0,"number","SO-001","issuedOn","2026-01-01","issuingAuthority","Fictional authority");
        assertThat(call(HttpMethod.PUT,so,corrected,token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.PUT,so,corrected,token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.POST,"/verify",input,token).getBody()).containsEntry("eligible",true);
        var change=policyInput("FORM137",true);change.put("version",policy.get("version"));
        var updated=call(HttpMethod.PUT,"/policies/"+policy.get("id"),change,token);assertThat(updated.getBody().get("approved_at")).isNull();
        assertThat(call(HttpMethod.POST,"/verify",input,token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
    @Test void unresolvedMarksMissingCredentialsAndWithdrawalBlockIssuance(){
        var policy=approve(create("TOR",false));var input=selection(policy);
        db.update("UPDATE grades SET value=NULL,mark='INC' WHERE id=?",grade);
        assertThat(call(HttpMethod.POST,"/verify",input,token).getBody().get("reasons").toString()).contains("UNRESOLVED_GRADES","PENDING_GRADE_REVIEW");
        db.update("UPDATE grades SET value=95,mark=NULL,state='submitted' WHERE id=?",grade);
        assertThat(call(HttpMethod.POST,"/verify",input,token).getBody().get("reasons").toString()).contains("UNRESOLVED_GRADES");
        db.update("UPDATE application_requirements SET status='missing' WHERE application_id=(SELECT application_id FROM enrollments WHERE id=?)",enrollment);
        db.update("INSERT INTO withdrawals(enrollment_id,reason) VALUES(?,'Fictional withdrawal')",enrollment);
        String reasons=call(HttpMethod.POST,"/verify",input,token).getBody().get("reasons").toString();assertThat(reasons).contains("MISSING_CREDENTIALS","WITHDRAWAL_CLEARANCE");
        assertThat(call(HttpMethod.POST,"/issue",input,token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
    @Test void activeSourceWriterProducesRetryConflictInsteadOfIssuance()throws Exception{
        var policy=approve(create("TOR",false));var input=selection(policy);
        try(var connection=Objects.requireNonNull(db.getDataSource()).getConnection()){
            connection.setAutoCommit(false);try(var statement=connection.prepareStatement("SELECT id FROM grades WHERE id=? FOR UPDATE")){statement.setLong(1,grade);statement.executeQuery();}
            assertThat(call(HttpMethod.POST,"/issue",input,token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);connection.rollback();
        }
        assertThat(call(HttpMethod.POST,"/issue",input,token).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
    @Test void wideUnicodeLongRecordsRenderAcrossPagesWithoutDroppingRows()throws Exception{
        var policy=approve(create("FORM137",false));var input=selection(policy);
        long classId=db.queryForObject("SELECT class_id FROM grades WHERE id=?",Long.class,grade);long teacher=db.queryForObject("SELECT teacher_id FROM class_offerings WHERE id=?",Long.class,classId);
        long period=db.queryForObject("SELECT grading_period_id FROM grades WHERE id=?",Long.class,grade);
        for(int i=0;i<65;i++){
            long subject=db.queryForObject("INSERT INTO subjects(code,name,units) VALUES(?,?,1) RETURNING id",Long.class,"LONG"+key(),"Peña "+"W".repeat(100)+" row "+i);
            long offering=db.queryForObject("INSERT INTO class_offerings(section_id,term_id,subject_id,teacher_id) VALUES(?,?,?,?) RETURNING id",Long.class,section,term,subject,teacher);
            db.update("INSERT INTO class_memberships(class_id,enrollment_id,section_id,term_id) VALUES(?,?,?,?)",offering,enrollment,section,term);
            db.update("INSERT INTO grades(class_id,enrollment_id,term_id,grading_period_id,value,state) VALUES(?,?,?,?,95,'finalized')",offering,enrollment,term,period);
        }
        var pdf=pdf("/preview",input,HttpMethod.POST);assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        try(var document=Loader.loadPDF(pdf.getBody())){assertThat(document.getNumberOfPages()).isGreaterThan(1);String text=new PDFTextStripper().getText(document);assertThat(text).contains("Peña","row 64");
            for(int page=1;page<=document.getNumberOfPages();page++){var stripper=new PDFTextStripper();stripper.setStartPage(page);stripper.setEndPage(page);assertThat(stripper.getText(document)).contains("DRAFT - NOT FOR OFFICIAL RELEASE","Page "+page+" of "+document.getNumberOfPages());}}
        Files.write(Path.of("/tmp/ici-official-document-preview.pdf"),pdf.getBody());
    }
    @Test void unsupportedGlyphsNeverSilentlyAlterOfficialRecordNames(){
        var policy=approve(create("TOR",false));var input=selection(policy);
        db.update("UPDATE subjects SET name=? WHERE id=(SELECT co.subject_id FROM class_offerings co JOIN grades g ON g.class_id=co.id WHERE g.id=?)","Unsupported glyph \uD83D\uDE00",grade);
        assertThat(pdf("/preview",input,HttpMethod.POST).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(call(HttpMethod.POST,"/issue",input,token).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
