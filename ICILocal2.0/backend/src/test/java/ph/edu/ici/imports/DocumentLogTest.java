package ph.edu.ici.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
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
import ph.edu.ici.imports.DocumentLogValidationService.UploadContext;
import ph.edu.ici.imports.ImportFileException.FileError;
import ph.edu.ici.imports.ImportMappingService.MappingInput;
import static ph.edu.ici.imports.ImportFixtures.n;
import static org.assertj.core.api.Assertions.*;

/**
 * Document tracking log import (ICI2-39 phase 4): mapping, staging, review, commit and discard over embedded PostgreSQL. Most staging goes
 * through the service with an in-code CSV grid; the .xlsx upload route is covered end to end over HTTP. All data is synthetic and the layout
 * is assumed.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class DocumentLogTest {
    static final String BASE="/api/document-log-imports",PASSWORD="Doclog-Test-Password-2026!",HEADER="Student No,Document,Status,Date,Remarks";
    static final List<String> MANDATORY=List.of("COMPLETION","FORM137","GOOD_MORAL","PSA");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->PostgresSupport.DATABASE.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");r.add("ici.auth.secret",()->PostgresSupport.SECRET);r.add("ici.bootstrap.enabled",()->false);
    }
    @Autowired TestRestTemplate rest;@Autowired JdbcTemplate db;@Autowired PasswordEncoder encoder;@Autowired ObjectMapper json;
    @Autowired DocumentLogValidationService service;@Autowired DocumentLogMappingService mappings;@Autowired ImportLimits limits;
    long iligan,bugo,registrar,admin;String registrarToken,bugoToken,teacherToken,importOnlyToken,adminToken;

    long staff(String role,Long campus){
        String name="doclog-"+UUID.randomUUID();long id=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES (?,?,?,?) RETURNING id",Long.class,name,name+"@example.invalid","Synthetic "+role,encoder.encode(PASSWORD));
        if(campus!=null)db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,?,?)",id,role,campus);else db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) SELECT ?,?,id FROM campuses",id,role);
        return id;
    }
    String login(long id){return (String)rest.postForObject("/api/auth/login",Map.of("username",db.queryForObject("SELECT username FROM users WHERE id=?",String.class,id),"password",PASSWORD),Map.class).get("accessToken");}
    void as(long id){var identity=new StaffIdentity(id,"u"+id,"Synthetic staff",List.of(),UUID.randomUUID(),Instant.now().plusSeconds(600),false);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(identity,null,List.of()));}
    @BeforeEach void users(){
        iligan=db.queryForObject("SELECT id FROM campuses WHERE code='ILIGAN'",Long.class);bugo=db.queryForObject("SELECT id FROM campuses WHERE code='BUGO'",Long.class);
        registrar=staff("ROLE_REGISTRAR",iligan);registrarToken=login(registrar);bugoToken=login(staff("ROLE_REGISTRAR",bugo));
        admin=staff("ROLE_ADMIN",null);adminToken=login(admin);teacherToken=login(staff("ROLE_TEACHER",iligan));
        db.update("INSERT INTO roles(code,name) VALUES ('ROLE_DOCLOG_IMPORT_ONLY','Synthetic') ON CONFLICT DO NOTHING");
        db.update("INSERT INTO role_permissions(role_code,permission_code) VALUES ('ROLE_DOCLOG_IMPORT_ONLY','imports.manage'),('ROLE_DOCLOG_IMPORT_ONLY','imports.read') ON CONFLICT DO NOTHING");
        importOnlyToken=login(staff("ROLE_DOCLOG_IMPORT_ONLY",iligan));
        as(registrar);
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    // ---- synthetic setup -----------------------------------------------------------------------------------------------
    record Setup(String n,long year,long term,long program){}
    Setup setup(){
        String n=n();
        long year=db.queryForObject("INSERT INTO academic_years(label,starts_on,ends_on) VALUES (?,'2026-06-01','2027-03-31') RETURNING id",Long.class,"SY"+n);
        long term=db.queryForObject("INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES (?,'S1','Synthetic S1','2026-06-01','2026-10-31') RETURNING id",Long.class,year);
        return new Setup(n,year,term,db.queryForObject("SELECT id FROM programs WHERE code='STEM'",Long.class));
    }
    String number(Setup s,String id){return "DL-"+s.n()+"-"+id;}
    long student(Setup s,String id){
        return db.queryForObject("INSERT INTO students(student_number,first_name,last_name,birth_date,campus_id,active) VALUES (?,?,?,'2009-01-01',?,true) RETURNING id",Long.class,number(s,id),"Stu"+id,"Syn"+id+ImportFixtures.letters(s.n()),iligan);
    }
    String appNumber(Setup s,String id){return "APP-DL-"+s.n()+"-"+id;}
    long application(Setup s,String id,Long student,String status){
        return db.queryForObject("INSERT INTO applications(application_number,campus_id,term_id,program_id,student_id,category,first_name,last_name,birth_date,status,profile) VALUES (?,?,?,?,?,'new','Synthetic','Applicant','2009-01-01',?,'{\"gradeLevel\":\"Grade 11\"}') RETURNING id",
            Long.class,appNumber(s,id),iligan,s.term(),s.program(),student,status);
    }
    /** A student with one application in the setup's term. */
    long applicant(Setup s,String id){return application(s,id,student(s,id),"pre_enlisted");}
    void recorded(long app,String code,String status,String notes){
        db.update("INSERT INTO application_requirements(application_id,requirement_code,status,notes,version,verified_by,verified_at) VALUES (?,?,?,?,1,?,CASE WHEN ?='submitted' THEN now() END)",app,code,status,notes,"submitted".equals(status)?registrar:null,status);
    }
    Map<String,Object> item(long app,String code){var r=db.queryForList("SELECT * FROM application_requirements WHERE application_id=? AND requirement_code=?",app,code);return r.isEmpty()?null:r.getFirst();}
    static String note(String label,String date,String remark){return "Legacy document log: status \""+label+"\""+(date!=null?", dated "+date:"")+"."+(remark!=null?" "+remark:"");}

    byte[] csv(Setup s,String... rows){return csv(HEADER,s,rows);}
    byte[] csv(String header,Setup s,String... rows){StringBuilder b=new StringBuilder(header).append("\r\n");for(String r:rows)b.append(r.replace("%N%",s.n())).append("\r\n");return b.toString().getBytes(StandardCharsets.UTF_8);}
    String line(Setup s,String id,String document,String status,String date,String remark){return number(s,id)+","+document+","+status+","+(date==null?"":date)+","+(remark==null?"":remark);}
    DocumentLogValidationService.Staged stage(Setup s,byte[] bytes){return stage(s,bytes,null);}
    DocumentLogValidationService.Staged stage(Setup s,byte[] bytes,Long mapping){return service.stage(iligan,new UploadContext(null,s.term()),"synthetic-documents.csv",bytes,new CsvGridReader(limits),mapping);}
    long staged(Setup s,String... rows){return stage(s,csv(s,rows)).batchId();}

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
    ResponseEntity<Map> upload(String token,long campus,Setup s,String filename,byte[] bytes){
        var h=new HttpHeaders();if(token!=null)h.setBearerAuth(token);h.setContentType(MediaType.MULTIPART_FORM_DATA);
        var form=new LinkedMultiValueMap<String,Object>();form.add("campusId",Long.toString(campus));form.add("termId",Long.toString(s.term()));
        form.add("file",new ByteArrayResource(bytes){@Override public String getFilename(){return filename;}});
        return rest.exchange(BASE+"/batches",HttpMethod.POST,new HttpEntity<>(form,h),Map.class);
    }
    /** A mapping like the default with extra value maps and settings, created as the administrator. */
    long mapping(Map<String,Map<String,String>> valueMaps,Map<String,Object> settings){
        var base=mappings.forUpload(null);ObjectNode m=base.mapping().deepCopy();ObjectNode st=base.settings().deepCopy();
        valueMaps.forEach((group,entries)->entries.forEach((k,v)->((ObjectNode)m.with("valueMaps").with(group)).put(k,v)));
        settings.forEach((k,v)->st.set(k,json.valueToTree(v)));
        as(admin);try{return mappings.create(new MappingInput("Synthetic doclog "+UUID.randomUUID(),m,st,false,true,false)).id();}finally{as(registrar);}
    }

    // ---- mapping -------------------------------------------------------------------------------------------------------
    @Test void seededDefaultIsAssumedUnvalidatedAndHasNoTranslations(){
        var seeded=mappings.forUpload(null);
        assertThat(seeded.isDefault()).isTrue();assertThat(seeded.realFileValidated()).isFalse();
        assertThat(seeded.mapping().path("valueMaps").path("status")).isEmpty();assertThat(seeded.mapping().path("valueMaps").path("document")).isEmpty();
        assertThat(mappings.validate(seeded.mapping(),seeded.settings())).isEmpty();
        assertThat(get("/mappings",registrarToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/mappings",teacherToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    @Test void mappingValidationChecksDocumentsStatesDatesAndIdentity(){
        var base=mappings.forUpload(null);
        ObjectNode good=base.mapping().deepCopy();((ObjectNode)good.with("valueMaps").with("document")).put("Birth Cert","PSA");((ObjectNode)good.with("valueMaps").with("status")).put("Verified","submitted");
        assertThat(mappings.validate(good,base.settings())).isEmpty();
        ObjectNode bad=base.mapping().deepCopy();ObjectNode settings=base.settings().deepCopy();
        ((ObjectNode)bad.with("valueMaps").with("document")).put("Birth Cert","psa");      // codes are exact case
        ((ObjectNode)bad.with("valueMaps").with("status")).put("Received","received");
        ((ObjectNode)bad.with("fields").with("status")).put("required",false);
        settings.putArray("dateFormats").add("dd/MM/yy");settings.put("repeatedEntries","latest_by_date");settings.put("layout","wide");
        var problems=String.join(" | ",mappings.validate(bad,settings));
        assertThat(problems).contains("'psa'").contains("'received'").contains("'status' cannot be made optional").contains("dateFormats").contains("repeatedEntries").contains("layout must be");
        ObjectNode noIdentity=base.mapping().deepCopy();for(String f:List.of("studentNumber","lrn","applicationNumber"))noIdentity.with("fields").remove(f);
        assertThat(String.join(" ",mappings.validate(noIdentity,base.settings()))).contains("studentNumber, lrn or applicationNumber");
        ObjectNode placeholder=base.settings().deepCopy();placeholder.putObject("cleaning").putArray("blankValues").add("verified");
        assertThat(String.join(" ",mappings.validate(good,placeholder))).as("a placeholder may not also be a status key").isNotEmpty();
    }

    // ---- staging -------------------------------------------------------------------------------------------------------
    @Test void everyRowOutcomeHasItsOwnCodeAndStagingWritesNothing()throws Exception{
        var s=setup();
        long fresh=applicant(s,"01");
        long blank=applicant(s,"02");
        long same=applicant(s,"03");recorded(same,"PSA","submitted",note("submitted","2026-06-01",null));
        long notes=applicant(s,"04");recorded(notes,"PSA","submitted",null);
        long other=applicant(s,"05");recorded(other,"PSA","missing",null);
        long regress=application(s,"06",student(s,"06"),"enrolled");recorded(regress,"PSA","submitted",null);
        application(s,"07",student(s,"07"),"withdrawn");
        student(s,"08");                                                       // no application
        long two=student(s,"09");application(s,"09a",two,"pre_enlisted");application(s,"09b",two,"pre_enlisted");
        applicant(s,"10");
        applicant(s,"11");
        long before=count("SELECT count(*) FROM application_requirements");
        long batch=staged(s,
            line(s,"01","PSA","submitted","2026-06-01","copy on file"),     // 2 valid, record
            line(s,"02","PSA","",null,null),                                // 3 blank state: nothing to record
            line(s,"03","PSA","submitted","2026-06-01",null),               // 4 same state and note: no-op
            line(s,"04","PSA","submitted",null,null),                       // 5 notes would change
            line(s,"05","PSA","submitted",null,null),                       // 6 different state
            line(s,"06","PSA","missing",null,null),                         // 7 regression on an enrolled application
            line(s,"07","PSA","submitted",null,null),                       // 8 closed application
            line(s,"08","PSA","submitted",null,null),                       // 9 no application
            line(s,"09","PSA","submitted",null,null),                       // 10 two applications
            line(s,"10","NOSUCHDOC","submitted",null,null),                 // 11 unknown document
            line(s,"10","FORM137","received",null,null),                    // 12 unknown state
            line(s,"11","form137","pending",null,null),                     // 13 unrecorded + pending: nothing to record (code matched case-insensitively)
            line(s,"11","FORM137","submitted",null,null),                   // 14 same application and document twice
            line(s,"10","PSA","submitted","2999-01-01",null),               // 15 future date
            line(s,"10","GOOD_MORAL","submitted","06/01/2026",null),        // 16 unaccepted date format
            "NOPE-%N%,PSA,submitted,,");                                    // 17 unknown student
        assertThat(status(batch,2)).isEqualTo("valid");assertThat(normalized(batch,2).path("action").asText()).isEqualTo("record");
        assertThat(normalized(batch,2).path("note").asText()).isEqualTo(note("submitted","2026-06-01","copy on file"));
        assertThat(status(batch,3)).isEqualTo("valid");assertThat(codes(batch,3)).containsExactly("NOTHING_TO_RECORD");
        assertThat(status(batch,4)).isEqualTo("valid");assertThat(codes(batch,4)).containsExactly("ALREADY_RECORDED");assertThat(normalized(batch,4).path("action").asText()).isEqualTo("noop");
        assertThat(status(batch,5)).isEqualTo("duplicate_flagged");assertThat(codes(batch,5)).containsExactly("NOTES_WOULD_CHANGE");assertThat(evidence(batch,5).get(0).path("kind").asText()).isEqualTo("notes");
        assertThat(status(batch,6)).isEqualTo("duplicate_flagged");assertThat(codes(batch,6)).containsExactly("CONFLICTS_WITH_RECORDED");assertThat(evidence(batch,6).get(0).path("regression").asBoolean()).isFalse();
        assertThat(status(batch,7)).isEqualTo("duplicate_flagged");assertThat(codes(batch,7)).containsExactly("CONFLICTS_WITH_RECORDED","APPLICATION_ENROLLED");
        assertThat(evidence(batch,7).get(0).path("regression").asBoolean()).isTrue();assertThat(evidence(batch,7).get(0).path("currentStatus").asText()).isEqualTo("submitted");
        assertThat(codes(batch,8)).containsExactly("APPLICATION_CLOSED");
        assertThat(codes(batch,9)).containsExactly("APPLICATION_NOT_FOUND");
        assertThat(codes(batch,10)).containsExactly("AMBIGUOUS_APPLICATION");
        assertThat(codes(batch,11)).containsExactly("UNMAPPED_VALUE");assertThat(codes(batch,12)).containsExactly("UNMAPPED_VALUE");
        assertThat(status(batch,13)).isEqualTo("valid");assertThat(codes(batch,13)).containsExactly("NOTHING_TO_RECORD");assertThat(normalized(batch,13).path("code").asText()).isEqualTo("FORM137");
        assertThat(codes(batch,14)).containsExactly("DUPLICATE_IN_BATCH");
        assertThat(codes(batch,15)).containsExactly("FUTURE_DATE");assertThat(codes(batch,16)).containsExactly("INVALID_DATE");
        assertThat(codes(batch,17)).containsExactly("STUDENT_NOT_FOUND");
        for(int i=8;i<=17;i++)if(i!=13)assertThat(status(batch,i)).as("row "+i).isEqualTo("invalid");
        assertThat(count("SELECT count(*) FROM application_requirements")).as("staging never writes a checklist item").isEqualTo(before);
        assertThat(item(fresh,"PSA")).isNull();assertThat(item(blank,"PSA")).isNull();
        // Messages never echo personal values or note text.
        assertThat(String.join(" ",db.queryForList("SELECT detail FROM import_exceptions WHERE batch_id=?",String.class,batch))).doesNotContain(s.n()).doesNotContain("copy on file");
        var detail=get("/batches/"+batch,registrarToken).getBody();
        assertThat(detail.get("validCount")).isEqualTo(4);assertThat(detail.get("flaggedCount")).isEqualTo(3);assertThat(detail.get("sourceFormat")).isEqualTo("csv");
    }

    @Test void valueMapsApplicationNumbersAndConfiguredDateFormats(){
        var s=setup();
        long linked=applicant(s,"21");long unlinked=application(s,"22",null,"pre_enlisted");long otherStudent=applicant(s,"23");
        long mapping=mapping(Map.of("document",Map.of("Birth Certificate","PSA"),"status",Map.of("Verified","submitted","Lacking","missing")),Map.of("dateFormats",List.of("MM/dd/yyyy","dd/MM/yyyy")));
        String header="Application No,Student No,Document,Status,Date";
        long batch=stage(s,csv(header,s,
            appNumber(s,"21")+",,Birth Certificate,Verified,06/30/2026",          // 2 by application number only; unambiguous date
            appNumber(s,"21")+","+number(s,"21")+",FORM137,Lacking,",            // 3 application and student agree
            appNumber(s,"22")+",,PSA,Verified,",                                 // 4 application not linked to a student
            appNumber(s,"23")+","+number(s,"21")+",PSA,Verified,",               // 5 application of another student
            appNumber(s,"21")+",,GOOD_MORAL,Verified,06/07/2026"),mapping).batchId();   // 6 ambiguous date (June 7 or July 6)
        assertThat(status(batch,2)).isEqualTo("valid");assertThat(status(batch,3)).isEqualTo("valid");
        assertThat(codes(batch,4)).containsExactly("APPLICATION_NOT_LINKED");
        assertThat(codes(batch,5)).containsExactly("CONTEXT_MISMATCH");
        assertThat(codes(batch,6)).containsExactly("AMBIGUOUS_DATE");
        assertThat(commit(registrarToken,batch,2).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(item(linked,"PSA").get("status")).isEqualTo("submitted");assertThat(item(linked,"PSA").get("notes")).isEqualTo(note("Verified","2026-06-30",null));
        assertThat(item(linked,"FORM137").get("status")).isEqualTo("missing");
        assertThat(item(unlinked,"PSA")).isNull();assertThat(item(otherStudent,"PSA")).isNull();
    }

    @Test void fileLevelErrorsRecordAFailedBatch(){
        var s=setup();
        var wide=catchThrowableOfType(ImportFileException.class,()->stage(s,csv("Student No,PSA,FORM137",s,number(s,"x")+",submitted,missing")));
        assertThat(wide.error()).isEqualTo(FileError.UNMAPPED_COLUMN);assertThat(batchStatus(wide.batchId())).isEqualTo("failed");
        var missing=catchThrowableOfType(ImportFileException.class,()->stage(s,csv("Student No,Document",s,number(s,"x")+",PSA")));
        assertThat(missing.error()).isEqualTo(FileError.MISSING_COLUMN);
    }

    // ---- commit --------------------------------------------------------------------------------------------------------
    @Test void commitRecordsThroughTheChecklistServiceAttributesTheRegistrarAndReportsCompleteness()throws Exception{
        var s=setup();long app=applicant(s,"31");
        for(String code:List.of("FORM137","COMPLETION","GOOD_MORAL"))recorded(app,code,"submitted",null);
        long batch=staged(s,line(s,"31","PSA","submitted","2026-06-02",null));
        var r=commit(registrarToken,batch,1);
        assertThat(r.getStatusCode()).as(r.toString()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("applied",1).containsEntry("applicationsNowComplete",1).containsEntry("applicationsNoLongerComplete",0).containsEntry("alreadyImported",false);
        var psa=item(app,"PSA");assertThat(psa.get("status")).isEqualTo("submitted");assertThat(((Number)psa.get("verified_by")).longValue()).isEqualTo(registrar);
        assertThat(status(batch,2)).isEqualTo("imported");
        var applied=json.readTree(row(batch,2).get("applied").toString());
        assertThat(applied.path("before").path("recorded").asBoolean()).isFalse();assertThat(applied.path("after").path("status").asText()).isEqualTo("submitted");
        // The requirement audit shows the committing registrar, with the legacy details only in the note.
        var audit=(List<Map<String,Object>>)rest.exchange("/api/applications/"+app+"/requirements/audit?code=PSA",HttpMethod.GET,new HttpEntity<>(bearer(registrarToken)),Map.class).getBody().get("items");
        assertThat(audit).isNotEmpty();assertThat(((Number)audit.getFirst().get("actorId")).longValue()).isEqualTo(registrar);
        assertThat(audit.getFirst().get("newNotes")).isEqualTo(note("submitted","2026-06-02",null));
        // Idempotent: committing again changes nothing; the same data in a new file is a no-op.
        assertThat(commit(registrarToken,batch,1).getBody()).containsEntry("alreadyImported",true);
        long again=stage(s,csv(s,line(s,"31","PSA","submitted","2026-06-02",null),"")).batchId();
        assertThat(codes(again,2)).containsExactly("ALREADY_RECORDED");
        // Privacy: legacy values and the applied record never reach audit_logs through import_rows.
        String rowAudit=String.join(" ",db.queryForList("SELECT coalesce(new_data::text,'') FROM audit_logs WHERE table_name='import_rows' AND new_data->>'batch_id'=?",String.class,Long.toString(batch)));
        assertThat(rowAudit).isNotEmpty().doesNotContain(s.n()).doesNotContain("raw_values").doesNotContain("applied").doesNotContain("Legacy document log");
    }
    HttpHeaders bearer(String token){var h=new HttpHeaders();h.setBearerAuth(token);return h;}

    @Test void conflictsNeedAnApplyDecisionAndARegressionIsRecordedOnlyThen(){
        var s=setup();long app=applicant(s,"41");recorded(app,"PSA","submitted",null);
        long batch=staged(s,line(s,"41","PSA","invalid",null,"blurred copy"),line(s,"41","FORM137","submitted",null,null));
        assertThat(commit(registrarToken,batch,1).getStatusCode()).as("a flagged row blocks the commit").isEqualTo(HttpStatus.CONFLICT);
        long flagged=rowId(batch,2);
        assertThat(review(registrarToken,batch,flagged,"distinct","no",null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(review(teacherToken,batch,flagged,"apply","x",null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(review(registrarToken,batch,flagged,"apply","Original was returned as unreadable",null).getStatusCode()).isEqualTo(HttpStatus.OK);
        var r=commit(registrarToken,batch,2);
        assertThat(r.getStatusCode()).as(r.toString()).isEqualTo(HttpStatus.OK);
        var psa=item(app,"PSA");assertThat(psa.get("status")).isEqualTo("invalid");assertThat(psa.get("verified_by")).isNull();
        assertThat(item(app,"FORM137").get("status")).isEqualTo("submitted");
    }

    @Test void aChecklistChangedAfterStagingDemotesTheRowAndImportsNothing(){
        var s=setup();long app=applicant(s,"51");long app2=applicant(s,"52");
        long batch=staged(s,line(s,"51","PSA","submitted",null,null),line(s,"52","PSA","submitted",null,null));
        recorded(app,"PSA","missing","registrar marked it missing");       // a manual change after staging
        var r=commit(registrarToken,batch,2);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(status(batch,2)).isEqualTo("duplicate_flagged");assertThat(codes(batch,2)).contains("CHANGED_SINCE_STAGING");
        assertThat(item(app2,"PSA")).as("nothing is imported when any row is demoted").isNull();
        assertThat(item(app,"PSA").get("status")).isEqualTo("missing");
        assertThat(review(registrarToken,batch,rowId(batch,2),"apply","Legacy file is the later record",null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(commit(registrarToken,batch,2).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(item(app,"PSA").get("status")).isEqualTo("submitted");assertThat(item(app2,"PSA").get("status")).isEqualTo("submitted");
    }

    @Test void skipAndDiscardWork(){
        var s=setup();applicant(s,"61");long app=applicant(s,"62");
        long batch=staged(s,line(s,"61","PSA","submitted",null,null),line(s,"62","PSA","submitted",null,null));
        assertThat(review(registrarToken,batch,rowId(batch,3),"skip","Not ours",null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(commit(registrarToken,batch,1).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(item(app,"PSA")).isNull();
        assertThat(discard(registrarToken,batch,"too late").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        long other=staged(s,line(s,"62","FORM137","submitted",null,null));
        assertThat(discard(registrarToken,other,"Wrong file").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(batchStatus(other)).isEqualTo("failed");
        assertThat(staged(s,line(s,"62","FORM137","submitted",null,null))).as("discard frees the checksum").isNotEqualTo(other);
    }

    // ---- .xlsx upload and permissions ----------------------------------------------------------------------------------
    @Test void anXlsxDocumentLogIsUploadedReviewedAndCommittedOverHttp(){
        var s=setup();long app=applicant(s,"71");
        byte[] file=XlsxFixtures.sheet(new Object[]{"Student No","Document","Status","Date","Remarks"},
            new Object[]{number(s,"71"),"PSA","submitted",LocalDate.of(2026,6,3),"original seen"},new Object[]{number(s,"71"),"FORM137","missing",null,null});
        var r=upload(registrarToken,iligan,s,"documents.xlsx",file);
        assertThat(r.getStatusCode()).as(r.toString()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("sourceFormat")).isEqualTo("xlsx");assertThat(r.getBody().get("validCount")).isEqualTo(2);
        long batch=((Number)r.getBody().get("id")).longValue();
        assertThat(commit(registrarToken,batch,2).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(item(app,"PSA").get("notes")).isEqualTo(note("submitted","2026-06-03","original seen"));
        assertThat(item(app,"FORM137").get("status")).isEqualTo("missing");
        assertThat(upload(registrarToken,iligan,s,"documents.xlsx",csv(s,line(s,"71","PSA","submitted",null,null))).getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test void permissionsFollowTheCampusAndTheEnrollmentImportPermission(){
        var s=setup();applicant(s,"81");byte[] file=XlsxFixtures.sheet(new Object[]{"Student No","Document","Status"},new Object[]{number(s,"81"),"PSA","submitted"});
        assertThat(upload(teacherToken,iligan,s,"d.xlsx",file).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(upload(importOnlyToken,iligan,s,"d.xlsx",file).getStatusCode()).as("imports.manage is not enough").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(upload(bugoToken,iligan,s,"d.xlsx",file).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(upload(null,iligan,s,"d.xlsx",file).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        long batch=staged(s,line(s,"81","PSA","submitted",null,null));
        assertThat(get("/batches/"+batch,bugoToken).getStatusCode()).as("another campus").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(commit(bugoToken,batch,1).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(commit(importOnlyToken,batch,1).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/batches/"+batch,adminToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        // Other import types never see this batch.
        assertThat(rest.exchange("/api/section-allocation-imports/batches/"+batch,HttpMethod.GET,new HttpEntity<>(bearer(registrarToken)),Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
