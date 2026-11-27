package ph.edu.ici.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import ph.edu.ici.PostgresSupport;
import static ph.edu.ici.imports.ImportFixtures.n;
import static org.assertj.core.api.Assertions.*;

/** The grade matrix HTTP API (ICI2-38 phase 3) over real local HTTP and embedded PostgreSQL. All data is synthetic; the layout is assumed. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class GradeMatrixApiTest {
    static final String BASE="/api/legacy-grade-imports",PASSWORD="Grade-Test-Password-2026!";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->PostgresSupport.DATABASE.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");r.add("ici.auth.secret",()->PostgresSupport.SECRET);r.add("ici.bootstrap.enabled",()->false);
    }
    @Autowired TestRestTemplate rest;@Autowired JdbcTemplate db;@Autowired PasswordEncoder encoder;@Autowired ObjectMapper json;
    long iligan,bugo,registrar,bugoRegistrar,admin;String registrarToken,bugoToken,adminToken,teacherToken,principalToken,studentOnlyToken;

    long staff(String role,Long campus){
        String name="grd-"+UUID.randomUUID();long id=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES (?,?,?,?) RETURNING id",Long.class,name,name+"@example.invalid","Synthetic "+role,encoder.encode(PASSWORD));
        if(campus!=null)db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,?,?)",id,role,campus);else db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) SELECT ?,?,id FROM campuses",id,role);
        return id;
    }
    String login(long id){return (String)rest.postForObject("/api/auth/login",Map.of("username",db.queryForObject("SELECT username FROM users WHERE id=?",String.class,id),"password",PASSWORD),Map.class).get("accessToken");}
    @BeforeEach void users(){
        iligan=db.queryForObject("SELECT id FROM campuses WHERE code='ILIGAN'",Long.class);bugo=db.queryForObject("SELECT id FROM campuses WHERE code='BUGO'",Long.class);
        registrar=staff("ROLE_REGISTRAR",iligan);registrarToken=login(registrar);bugoRegistrar=staff("ROLE_REGISTRAR",bugo);bugoToken=login(bugoRegistrar);
        admin=staff("ROLE_ADMIN",null);adminToken=login(admin);teacherToken=login(staff("ROLE_TEACHER",iligan));principalToken=login(staff("ROLE_PRINCIPAL",iligan));
        db.update("INSERT INTO roles(code,name) VALUES ('ROLE_STUDENT_IMPORT_ONLY','Synthetic') ON CONFLICT DO NOTHING");
        db.update("INSERT INTO role_permissions(role_code,permission_code) VALUES ('ROLE_STUDENT_IMPORT_ONLY','imports.manage'),('ROLE_STUDENT_IMPORT_ONLY','imports.read') ON CONFLICT DO NOTHING");
        studentOnlyToken=login(staff("ROLE_STUDENT_IMPORT_ONLY",iligan));
    }

    // ---- synthetic setup and helpers -----------------------------------------------------------------------------------
    record Setup(String n,long year,long s1,long q1,long q2,long math,long sci){}
    Setup setup(){
        String n=n();
        long year=db.queryForObject("INSERT INTO academic_years(label,starts_on,ends_on) VALUES (?,'2018-06-01','2019-03-31') RETURNING id",Long.class,"SY"+n);
        long s1=db.queryForObject("INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES (?,'S1','Synthetic S1','2018-06-01','2018-10-31') RETURNING id",Long.class,year);
        long q1=db.queryForObject("INSERT INTO grading_periods(term_id,code,sequence) VALUES (?,'Q1',1) RETURNING id",Long.class,s1),q2=db.queryForObject("INSERT INTO grading_periods(term_id,code,sequence) VALUES (?,'Q2',2) RETURNING id",Long.class,s1);
        long math=db.queryForObject("INSERT INTO subjects(code,name) VALUES (?,'Synthetic mathematics') RETURNING id",Long.class,"SYNMATH"+n);
        long sci=db.queryForObject("INSERT INTO subjects(code,name) VALUES (?,'Synthetic science') RETURNING id",Long.class,"SYNSCI"+n);
        return new Setup(n,year,s1,q1,q2,math,sci);
    }
    long student(Setup s,String id){return student(s,id,null,iligan);}
    long student(Setup s,String id,String lrn,long campus){return db.queryForObject("INSERT INTO students(student_number,lrn,first_name,last_name,birth_date,campus_id) VALUES (?,?,?,?,'2009-01-01',?) RETURNING id",Long.class,"SYN-"+s.n()+"-"+id,lrn,"Stu"+id,"Syn"+id+ImportFixtures.letters(s.n()),campus);}
    String header(Setup s){return "Student No,Grade Level,SYNMATH"+s.n()+" - Q1,SYNMATH"+s.n()+" - Q2,SYNSCI"+s.n()+" - Q1\r\n";}
    byte[] csv(Setup s,String... rows){StringBuilder b=new StringBuilder(header(s));for(String r:rows)b.append(r.replace("%N%",s.n())).append("\r\n");return b.toString().getBytes(StandardCharsets.UTF_8);}
    ResponseEntity<Map> call(HttpMethod method,String path,Object body,String token){var h=new HttpHeaders();if(token!=null)h.setBearerAuth(token);h.setContentType(MediaType.APPLICATION_JSON);return rest.exchange(BASE+path,method,new HttpEntity<>(body,h),Map.class);}
    ResponseEntity<Map> get(String path,String token){return call(HttpMethod.GET,path,null,token);}
    ResponseEntity<Map> upload(String token,long campus,Setup s,String filename,byte[] bytes){return upload(token,campus,Map.of("termId",Long.toString(s.s1()),"gradeLevel","Grade 7"),filename,bytes);}
    ResponseEntity<Map> upload(String token,long campus,Map<String,String> fields,String filename,byte[] bytes){
        var h=new HttpHeaders();if(token!=null)h.setBearerAuth(token);h.setContentType(MediaType.MULTIPART_FORM_DATA);
        var form=new LinkedMultiValueMap<String,Object>();form.add("campusId",Long.toString(campus));fields.forEach(form::add);
        form.add("file",new ByteArrayResource(bytes){@Override public String getFilename(){return filename;}});
        return rest.exchange(BASE+"/batches",HttpMethod.POST,new HttpEntity<>(form,h),Map.class);
    }
    long stage(Setup s,byte[] bytes){var r=upload(registrarToken,iligan,s,"synthetic-grades.csv",bytes);assertThat(r.getStatusCode()).as(r.toString()).isEqualTo(HttpStatus.CREATED);return ((Number)r.getBody().get("id")).longValue();}
    long rowId(long batch,int rowNumber){return db.queryForObject("SELECT id FROM import_rows WHERE batch_id=? AND row_number=?",Long.class,batch,rowNumber);}
    int rowVersion(long row){return db.queryForObject("SELECT version FROM import_rows WHERE id=?",Integer.class,row);}
    int batchVersion(long batch){return db.queryForObject("SELECT version FROM import_batches WHERE id=?",Integer.class,batch);}
    String rowStatus(long batch,int rowNumber){return db.queryForObject("SELECT status FROM import_rows WHERE batch_id=? AND row_number=?",String.class,batch,rowNumber);}
    String batchStatus(long batch){return db.queryForObject("SELECT status FROM import_batches WHERE id=?",String.class,batch);}
    long count(String sql,Object... args){return db.queryForObject(sql,Long.class,args);}
    long records(long batch){return count("SELECT count(*) FROM legacy_grade_records g JOIN import_rows r ON r.id=g.import_row_id WHERE r.batch_id=?",batch);}
    long liveRecords(long batch){return count("SELECT count(*) FROM legacy_grade_records g JOIN import_rows r ON r.id=g.import_row_id WHERE r.batch_id=? AND g.voided_at IS NULL",batch);}
    ResponseEntity<Map> review(String token,long batch,long row,String decision,String reason,Long studentId){
        Map<String,Object> body=new HashMap<>();body.put("version",rowVersion(row));body.put("decision",decision);body.put("reason",reason);if(studentId!=null)body.put("studentId",studentId);
        return call(HttpMethod.POST,"/batches/"+batch+"/rows/"+row+"/review",body,token);
    }
    ResponseEntity<Map> commit(String token,long batch,int expected){return call(HttpMethod.POST,"/batches/"+batch+"/commit",Map.of("version",batchVersion(batch),"expectedValidCount",expected),token);}
    ResponseEntity<Map> voidBatch(String token,long batch,String reason){return call(HttpMethod.POST,"/batches/"+batch+"/void",Map.of("version",batchVersion(batch),"reason",reason),token);}
    ResponseEntity<Map> discard(String token,long batch,String reason){return call(HttpMethod.POST,"/batches/"+batch+"/discard",Map.of("version",batchVersion(batch),"reason",reason),token);}
    /** A committed legacy record for another batch, to create history that later files collide with. */
    long prior(Setup s,long student,String column,String value){
        long batch=db.queryForObject("INSERT INTO import_batches(source_name,checksum,source_count,status,imported_count,campus_id,source_type,imported_by,imported_at) VALUES ('prior.csv',substr(md5(random()::text)||md5(random()::text),1,64),1,'imported',1,?,'legacy_grade_matrix_csv',?,now()) RETURNING id",Long.class,iligan,registrar);
        long row=db.queryForObject("INSERT INTO import_rows(batch_id,row_number,status,campus_id,source_type,normalized_values,student_id) VALUES (?,2,'imported',?,'legacy_grade_matrix_csv','{}',?) RETURNING id",Long.class,batch,iligan,student);
        long period=column.equals("Q1")?s.q1():s.q2();
        return db.queryForObject("INSERT INTO legacy_grade_records(student_id,campus_id,term_id,grading_period_id,subject_id,grade_level,value,import_row_id,source_column) VALUES (?,?,?,?,?,'Grade 7',?::numeric,?,'x') RETURNING id",Long.class,student,iligan,s.s1(),period,s.math(),value,row);
    }

    // ---- permissions ---------------------------------------------------------------------------------------------------
    @Test void permissionMatrixCoversAdminRegistrarTeacherPrincipalOtherCampusAnonymousAndStudentImportOnlyAccounts(){
        var s=setup();student(s,"01");long batch=stage(s,csv(s,"SYN-%N%-01,Grade 7,90,91,92"));
        for(String path:List.of("/mappings","/batches","/batches/"+batch,"/batches/"+batch+"/rows","/batches/"+batch+"/errors")){
            assertThat(get(path,adminToken).getStatusCode()).as("admin "+path).isEqualTo(HttpStatus.OK);assertThat(get(path,registrarToken).getStatusCode()).as("registrar "+path).isEqualTo(HttpStatus.OK);
            assertThat(get(path,teacherToken).getStatusCode()).as("teacher "+path).isEqualTo(HttpStatus.FORBIDDEN);assertThat(get(path,principalToken).getStatusCode()).as("principal "+path).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(get(path,null).getStatusCode()).as("anonymous "+path).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        for(String path:List.of("/batches/"+batch,"/batches/"+batch+"/rows","/batches/"+batch+"/errors"))assertThat(get(path,bugoToken).getStatusCode()).as("other campus "+path).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/batches?campusId="+iligan,bugoToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(((List)get("/batches",bugoToken).getBody().get("items"))).extracting(i->((Map)i).get("id")).doesNotContain((int)batch);
        // The student import never sees grade batches, and the grade API never sees student batches.
        var studentBatches=rest.exchange("/api/legacy-student-imports/batches",HttpMethod.GET,new HttpEntity<>(bearer(registrarToken)),Map.class);
        assertThat(((List)studentBatches.getBody().get("items"))).extracting(i->((Map)i).get("id")).doesNotContain((int)batch);
        // Writes need imports.grades.manage for that campus; imports.manage (student import) is not enough.
        for(String role:List.of("teacher","principal","studentOnly")){
            String token=role.equals("teacher")?teacherToken:role.equals("principal")?principalToken:studentOnlyToken;
            assertThat(upload(token,iligan,s,"x.csv",csv(s,"SYN-%N%-01,Grade 7,1,2,3")).getStatusCode()).as(role+" upload").isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(commit(token,batch,1).getStatusCode()).as(role+" commit").isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(call(HttpMethod.POST,"/batches/"+batch+"/void",Map.of("version",0,"reason","x"),token).getStatusCode()).as(role+" void").isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(call(HttpMethod.POST,"/batches/"+batch+"/discard",Map.of("version",0,"reason","x"),token).getStatusCode()).as(role+" discard").isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(get("/batches/"+batch,studentOnlyToken).getStatusCode()).as("imports.read alone may read").isEqualTo(HttpStatus.OK);
        assertThat(upload(registrarToken,bugo,s,"x.csv",csv(s,"SYN-%N%-01,Grade 7,1,2,3")).getStatusCode()).as("registrar uploading for another campus").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(upload(bugoToken,iligan,s,"x.csv",csv(s,"SYN-%N%-01,Grade 7,1,2,3")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(upload(null,iligan,s,"x.csv",csv(s,"SYN-%N%-01,Grade 7,1,2,3")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(commit(bugoToken,batch,1).getStatusCode()).as("writes on an invisible batch").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(voidBatch(bugoToken,batch,"x").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);assertThat(discard(bugoToken,batch,"x").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        long row=rowId(batch,2);assertThat(review(teacherToken,batch,row,"skip","No access",null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);assertThat(review(bugoToken,batch,row,"skip","Wrong campus",null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(upload(adminToken,iligan,s,"admin.csv",csv(s,"SYN-%N%-01,Grade 7,95,96,97")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // Mappings: read with imports.read, write only for administrators.
        var seeded=get("/mappings",adminToken).getBody();var mapping=(Map)((List)seeded.get("items")).getFirst();
        var body=Map.of("name","m-"+n(),"mapping",mapping.get("mapping"),"settings",mapping.get("settings"));
        assertThat(call(HttpMethod.POST,"/mappings",body,registrarToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);assertThat(call(HttpMethod.POST,"/mappings",body,principalToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST,"/mappings",body,adminToken).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(get("/mappings/"+mapping.get("id"),registrarToken).getBody()).containsEntry("realFileValidated",false);
        assertThat(rest.exchange("/api/legacy-student-imports/mappings",HttpMethod.GET,new HttpEntity<>(bearer(registrarToken)),Map.class).getBody().toString()).as("student mappings stay separate").doesNotContain("subject_period_flat");
    }
    HttpHeaders bearer(String token){var h=new HttpHeaders();h.setBearerAuth(token);return h;}

    @Test void mappingEndpointsValidateVersionAndKeepGradeAndStudentMappingsApart(){
        var s=setup();var seeded=(Map)((List)get("/mappings",adminToken).getBody().get("items")).getFirst();
        ObjectNode mapping=json.valueToTree(seeded.get("mapping")),settings=json.valueToTree(seeded.get("settings"));
        mapping.with("valueMaps").with("subject").put("Mathematics","SYNMATH"+s.n());settings.putArray("ignoredColumns").add("Remarks");
        Map<String,Object> body=new HashMap<>(Map.of("name","grade-map-"+n(),"mapping",mapping,"settings",settings,"active",true));
        var created=call(HttpMethod.POST,"/mappings",body,adminToken);assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);assertThat(created.getBody()).containsEntry("version",0).containsEntry("realFileValidated",false);
        long id=((Number)created.getBody().get("id")).longValue();
        mapping.with("valueMaps").with("subject").put("Ghost","NO-SUCH-SUBJECT");
        var bad=call(HttpMethod.POST,"/mappings",new HashMap<>(Map.of("name","bad-"+n(),"mapping",mapping,"settings",settings)),adminToken);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);assertThat(bad.getBody().get("detail").toString()).contains("NO-SUCH-SUBJECT","Nothing is created");
        mapping.with("valueMaps").with("subject").remove("Ghost");
        body.put("version",0);body.put("realFileValidated",true);body.put("mapping",mapping);
        var updated=call(HttpMethod.PUT,"/mappings/"+id,body,adminToken);assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(updated.getBody()).containsEntry("version",1).containsEntry("realFileValidated",true);
        assertThat(call(HttpMethod.PUT,"/mappings/"+id,body,adminToken).getStatusCode()).as("stale version").isEqualTo(HttpStatus.CONFLICT);
        body.remove("version");assertThat(call(HttpMethod.PUT,"/mappings/"+id,body,adminToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.POST,"/mappings",new HashMap<>(Map.of("name","grade-map-X","mapping",mapping,"settings",settings)),registrarToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/mappings/999999999",adminToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var studentSide=rest.exchange("/api/legacy-student-imports/mappings/"+id,HttpMethod.GET,new HttpEntity<>(bearer(adminToken)),Map.class);assertThat(studentSide.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- upload: a dry run ---------------------------------------------------------------------------------------------
    @Test void uploadIsADryRunThatWritesNeitherGradesNorLegacyRecordsAndExplainsWhatItFound(){
        var s=setup();student(s,"01");student(s,"02");student(s,"03");long records=count("SELECT count(*) FROM legacy_grade_records"),grades=count("SELECT count(*) FROM grades"),students=count("SELECT count(*) FROM students");
        var r=upload(registrarToken,iligan,s,"C:\\exports\\grades "+s.n()+".csv",csv(s,"SYN-%N%-01,Grade 7,88.50,90,INC","SYN-%N%-02,Grade 7,75,NG,","SYN-%N%-03,Grade 7,101,,","SYN-%N%-99,Grade 7,80,80,80"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);var b=r.getBody();
        assertThat(count("SELECT count(*) FROM legacy_grade_records")).isEqualTo(records);assertThat(count("SELECT count(*) FROM grades")).isEqualTo(grades);assertThat(count("SELECT count(*) FROM students")).isEqualTo(students);
        assertThat(b).containsEntry("status","validated").containsEntry("sourceName","grades "+s.n()+".csv").containsEntry("campusId",(int)iligan).containsEntry("sourceCount",4).containsEntry("validCount",2).containsEntry("invalidCount",2).containsEntry("flaggedCount",0).containsEntry("importedCount",0)
            .containsEntry("version",0).containsEntry("recordCount",5).containsEntry("liveRecordCount",0).containsEntry("voidedRecordCount",0).containsEntry("discarded",false);
        assertThat((Map)b.get("uploadContext")).containsEntry("termId",(int)s.s1()).containsEntry("gradeLevel","Grade 7");assertThat((Map)b.get("mapping")).containsEntry("realFileValidated",false);
        assertThat(((List<Map>)b.get("errorSummary"))).extracting(m->m.get("code")).contains("VALUE_OUT_OF_RANGE","STUDENT_NOT_FOUND");
        assertThat(((List<Map>)b.get("columns"))).extracting(m->m.get("column")).contains("SYNMATH"+s.n()+" - Q1");
        long id=((Number)b.get("id")).longValue();
        var rows=get("/batches/"+id+"/rows?status=invalid&size=1",registrarToken).getBody();assertThat(rows).containsEntry("total",2).containsEntry("size",1);
        var first=(Map)((List)rows.get("items")).getFirst();assertThat(first).containsKeys("id","rowNumber","status","version","rawValues","messages");assertThat((List)first.get("messages")).isNotEmpty();
        var valid=(Map)((List)get("/batches/"+id+"/rows?status=valid",registrarToken).getBody().get("items")).getFirst();assertThat(((Map)valid.get("normalizedValues")).get("cells")).asList().hasSize(3);
        assertThat(((List)get("/batches/"+id+"/errors?severity=error&rowNumber="+first.get("rowNumber"),registrarToken).getBody().get("items"))).isNotEmpty();
        assertThat(get("/batches/"+id+"/rows?size=101",registrarToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);assertThat(get("/batches/"+id+"/rows?status=bogus",registrarToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/batches/"+id+"/rows?page=-1",registrarToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);assertThat(get("/batches/"+id+"/errors?severity=bogus",registrarToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/batches/999999999",registrarToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);assertThat(get("/batches?status=bogus",registrarToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((List)get("/batches?status=validated&size=2",registrarToken).getBody().get("items")).size()).isLessThanOrEqualTo(2);
        assertThat(count("SELECT count(*) FROM legacy_grade_records")).as("still nothing written").isEqualTo(records);
    }
    @Test void uploadRefusalsAnswer400409413415And422WithProblemDetails(){
        var s=setup();student(s,"01");var bytes=csv(s,"SYN-%N%-01,Grade 7,90,91,92");long first=stage(s,bytes);
        var again=upload(registrarToken,iligan,s,"b.csv",bytes);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);assertThat(again.getBody()).containsEntry("code","DUPLICATE_FILE").containsEntry("batchId",(int)first).containsEntry("batchStatus","validated");
        var big=upload(registrarToken,iligan,s,"big.csv",new byte[5_000_001]);assertThat(big.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);assertThat(big.getBody()).containsEntry("code","FILE_TOO_LARGE");
        var rows=new StringBuilder("Student No\n");for(int i=0;i<20_001;i++)rows.append("x\n");
        var many=upload(registrarToken,iligan,s,"many.csv",rows.toString().getBytes(StandardCharsets.UTF_8));assertThat(many.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);assertThat(many.getBody()).containsEntry("code","TOO_MANY_ROWS").doesNotContainKey("batchId");
        assertThat(upload(registrarToken,iligan,s,"notes.txt",bytes).getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        var unclassified=upload(registrarToken,iligan,s,"u.csv",("Student No,Grade Level,Remarks,Age,SYNMATH"+s.n()+" - Q1\r\nSYN-"+s.n()+"-01,Grade 7,ok,13,90\r\n").getBytes(StandardCharsets.UTF_8));
        assertThat(unclassified.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);assertThat(unclassified.getBody()).containsEntry("code","UNMAPPED_COLUMN").containsKey("batchId");
        assertThat(unclassified.getBody().get("detail").toString()).contains("'Remarks'","'Age'");assertThat((List<Map>)unclassified.getBody().get("problems")).extracting(p->p.get("header")).containsExactly("Remarks","Age");
        var failed=get("/batches/"+unclassified.getBody().get("batchId"),registrarToken).getBody();assertThat(failed).containsEntry("status","failed").containsEntry("discarded",false);
        assertThat((List<Map>)failed.get("batchExceptions")).extracting(e->e.get("field")).contains("Remarks","Age");
        assertThat(upload(registrarToken,iligan,s,"s.csv",("Student No,Grade Level,SYNNOPE"+s.n()+" - Q1\r\nx,Grade 7,1\r\n").getBytes(StandardCharsets.UTF_8)).getBody()).containsEntry("code","UNKNOWN_SUBJECT");
        assertThat(upload(registrarToken,iligan,s,"p.csv",("Student No,Grade Level,SYNMATH"+s.n()+" - Q9\r\nx,Grade 7,1\r\n").getBytes(StandardCharsets.UTF_8)).getBody()).containsEntry("code","UNKNOWN_PERIOD");
        var noTerm=upload(registrarToken,iligan,Map.of("gradeLevel","Grade 7"),"t.csv",csv(s,"SYN-%N%-01,Grade 7,1,2,3"));assertThat(noTerm.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);assertThat(noTerm.getBody()).containsEntry("code","MISSING_COLUMN");
        assertThat(upload(registrarToken,iligan,Map.of("termId","999999999"),"t.csv",csv(s,"SYN-%N%-01,Grade 7,1,2,3")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(upload(registrarToken,iligan,Map.of("termId",Long.toString(s.s1()),"academicYearId","999999999"),"t.csv",csv(s,"SYN-%N%-01,Grade 7,1,2,3")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var unknownMapping=upload(registrarToken,iligan,Map.of("termId",Long.toString(s.s1()),"mappingId","999999999"),"t.csv",csv(s,"SYN-%N%-01,Grade 7,4,5,6"));assertThat(unknownMapping.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);assertThat(unknownMapping.getBody()).containsEntry("code","UNKNOWN_MAPPING").doesNotContainKey("batchId");
        assertThat(upload(registrarToken,iligan,s,"empty.csv",new byte[0]).getBody()).containsEntry("code","EMPTY_FILE");
    }

    // ---- review --------------------------------------------------------------------------------------------------------
    @Test void reviewConfirmsOneVisibleCandidateOrSkipsAndNeverCreatesAStudent()throws Exception{
        var s=setup();String n=s.n();long a=student(s,"01"),s04=student(s,"04"),s05=student(s,"05","99"+n+"05",iligan);student(s,"06");
        byte[] file=("Student No,LRN,Grade Level,SYNMATH"+n+" - Q1\r\nSYN-"+n+"-01,,Grade 7,90\r\nSYN-"+n+"-04,99"+n+"05,Grade 7,80\r\nSYN-"+n+"-06,,Grade 7,70\r\n").getBytes(StandardCharsets.UTF_8);
        long b=stage(s,file);assertThat(rowStatus(b,3)).isEqualTo("duplicate_flagged");
        long flagged=rowId(b,3),valid=rowId(b,2),third=rowId(b,4);long students=count("SELECT count(*) FROM students");
        assertThat(review(registrarToken,b,flagged,"same_person","   ",s05).getStatusCode()).as("a reason is required").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.POST,"/batches/"+b+"/rows/"+flagged+"/review",Map.of("version",99,"decision","same_person","reason","Same learner","studentId",s05),registrarToken).getStatusCode()).as("stale row version").isEqualTo(HttpStatus.CONFLICT);
        var distinct=review(registrarToken,b,flagged,"distinct","Different learner",null);assertThat(distinct.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);assertThat(distinct.getBody().get("detail").toString()).contains("never create a student");
        assertThat(review(registrarToken,b,flagged,"bogus","x",null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(review(registrarToken,b,flagged,"same_person","Same learner",null).getStatusCode()).as("same_person needs a student").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(review(registrarToken,b,flagged,"same_person","Same learner",a).getStatusCode()).as("not one of the candidates").isEqualTo(HttpStatus.CONFLICT);
        assertThat(review(registrarToken,b,valid,"same_person","Not flagged",s05).getStatusCode()).as("a valid row has no identity question").isEqualTo(HttpStatus.CONFLICT);
        assertThat(commit(registrarToken,b,2).getStatusCode()).as("flagged rows block the commit").isEqualTo(HttpStatus.CONFLICT);
        assertThat(count("SELECT count(*) FROM import_rows WHERE batch_id=? AND review_decision IS NOT NULL",b)).isZero();
        // The database independently forbids a grade row from carrying the create-a-student decision.
        assertThatThrownBy(()->db.update("UPDATE import_rows SET status='valid',review_decision='distinct',review_reason='x',reviewed_by=?,reviewed_at=now() WHERE id=?",registrar,flagged)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var ok=review(registrarToken,b,flagged,"same_person","Same learner, confirmed against the paper record",s05);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);var row=(Map)ok.getBody().get("row");
        assertThat(row).containsEntry("status","valid");assertThat((Map)row.get("review")).containsEntry("decision","same_person").containsEntry("matchedStudentId",(int)s05).containsEntry("reason","Same learner, confirmed against the paper record");
        assertThat((Map)ok.getBody().get("batch")).containsEntry("validCount",3).containsEntry("flaggedCount",0).containsEntry("version",1);
        var skip=review(registrarToken,b,third,"skip","Not part of this import",null);assertThat(skip.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat((Map)skip.getBody().get("row")).containsEntry("status","skipped");
        assertThat(review(registrarToken,b,third,"skip","Again",null).getStatusCode()).as("a skipped row cannot be skipped again").isEqualTo(HttpStatus.CONFLICT);
        assertThat(count("SELECT count(*) FROM students")).as("review never creates a student").isEqualTo(students);
        assertThat(commit(registrarToken,b,2).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(db.queryForList("SELECT g.student_id FROM legacy_grade_records g JOIN import_rows r ON r.id=g.import_row_id WHERE r.batch_id=? AND r.row_number=3",Long.class,b)).as("the confirmed link, not the student number's owner").containsExactly(s05);
        assertThat(db.queryForObject("SELECT student_id FROM import_rows WHERE id=?",Long.class,flagged)).isEqualTo(s05);assertThat(s04).isPositive();
        assertThat(count("SELECT count(*) FROM students")).isEqualTo(students);
        assertThat(review(registrarToken,b,valid,"skip","Too late",null).getStatusCode()).as("committed batches cannot be reviewed").isEqualTo(HttpStatus.CONFLICT);
    }

    // ---- commit --------------------------------------------------------------------------------------------------------
    @Test void commitWritesLegacyRecordsWithFullProvenanceAndNothingElse()throws Exception{
        var s=setup();long s1=student(s,"01"),s2=student(s,"02");student(s,"03");long grades=count("SELECT count(*) FROM grades"),students=count("SELECT count(*) FROM students");
        byte[] file=csv(s,"SYN-%N%-01,Grade 7,88.50,90,INC","SYN-%N%-02,Grade 7,75,NG,","SYN-%N%-03,Grade 7,,,");long b=stage(s,file);
        var r=commit(registrarToken,b,3);assertThat(r.getStatusCode()).as(r.toString()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("status","imported").containsEntry("rowsImported",3).containsEntry("recordsCreated",5).containsEntry("recordsAlreadyRecorded",0).containsEntry("alreadyImported",false).containsEntry("invalid",0);
        assertThat(count("SELECT count(*) FROM grades")).as("grades table untouched").isEqualTo(grades);assertThat(count("SELECT count(*) FROM students")).isEqualTo(students);
        assertThat(records(b)).isEqualTo(5);assertThat(liveRecords(b)).isEqualTo(5);
        var batch=db.queryForMap("SELECT * FROM import_batches WHERE id=?",b);assertThat(batch).containsEntry("status","imported").containsEntry("imported_by",registrar);assertThat(batch.get("imported_at")).isNotNull();assertThat(((Number)batch.get("imported_count")).intValue()).isEqualTo(3);
        assertThat(db.queryForList("SELECT status FROM import_rows WHERE batch_id=? ORDER BY row_number",String.class,b)).containsOnly("imported");
        var rec=db.queryForMap("SELECT g.*,r.batch_id,r.row_number FROM legacy_grade_records g JOIN import_rows r ON r.id=g.import_row_id WHERE r.batch_id=? AND g.student_id=? AND g.grading_period_id=? AND g.subject_id=?",b,s1,s.q1(),s.math());
        assertThat(rec).containsEntry("source_column","SYNMATH"+s.n()+" - Q1").containsEntry("grade_level","Grade 7").containsEntry("term_id",s.s1()).containsEntry("campus_id",iligan).containsEntry("record_kind","period").containsEntry("import_source_type","legacy_grade_matrix_csv").containsEntry("created_by",registrar).containsEntry("row_number",2);
        assertThat((java.math.BigDecimal)rec.get("value")).isEqualByComparingTo("88.50");assertThat(rec.get("mark")).isNull();assertThat(rec.get("voided_at")).isNull();
        assertThat(db.queryForList("SELECT mark FROM legacy_grade_records WHERE student_id=? AND mark IS NOT NULL",String.class,s1)).containsExactly("INC");
        assertThat(db.queryForList("SELECT mark FROM legacy_grade_records WHERE student_id=? AND mark IS NOT NULL",String.class,s2)).containsExactly("NG");
        assertThat(count("SELECT count(*) FROM incomplete_cases ic JOIN grades g ON g.id=ic.grade_id")).as("legacy INC opens no incomplete case").isEqualTo(count("SELECT count(*) FROM incomplete_cases"));
        assertThat(db.queryForObject("SELECT b.checksum FROM legacy_grade_records g JOIN import_rows r ON r.id=g.import_row_id JOIN import_batches b ON b.id=r.batch_id WHERE g.student_id=? LIMIT 1",String.class,s1)).hasSize(64);
        var detail=get("/batches/"+b,registrarToken).getBody();assertThat(detail).containsEntry("status","imported").containsEntry("liveRecordCount",5).containsEntry("voidedRecordCount",0);
        assertThat(((Map)get("/batches/"+b+"/rows?status=imported",registrarToken).getBody()).get("total")).isEqualTo(3);
        // Committed records are history: no edits, deletes or truncation, even by SQL.
        assertThatThrownBy(()->db.update("UPDATE legacy_grade_records SET value=99 WHERE student_id=? AND grading_period_id=?",s1,s.q1())).hasMessageContaining("cannot be edited");
        assertThatThrownBy(()->db.update("DELETE FROM legacy_grade_records WHERE student_id=?",s1)).hasMessageContaining("cannot be deleted");
        assertThatThrownBy(()->db.execute("TRUNCATE legacy_grade_records")).hasMessageContaining("cannot be truncated");
        assertThatThrownBy(()->db.update("UPDATE legacy_grade_records SET student_id=? WHERE student_id=?",s2,s1)).hasMessageContaining("cannot be edited");
    }
    @Test void commitIsIdempotentAndRefusesStaleVersionsWrongCountsAndBatchesThatCannotBeCommitted(){
        var s=setup();student(s,"01");student(s,"02");long b=stage(s,csv(s,"SYN-%N%-01,Grade 7,90,91,92","SYN-%N%-02,Grade 7,80,81,82"));int version=batchVersion(b);
        assertThat(call(HttpMethod.POST,"/batches/"+b+"/commit",Map.of("version",version+5,"expectedValidCount",2),registrarToken).getStatusCode()).as("stale version").isEqualTo(HttpStatus.CONFLICT);
        var wrong=commit(registrarToken,b,5);assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);assertThat(wrong.getBody().get("detail").toString()).contains("Expected 5 importable rows but the batch has 2");
        assertThat(call(HttpMethod.POST,"/batches/"+b+"/commit",Map.of("version",version),registrarToken).getStatusCode()).as("expectedValidCount is required").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(records(b)).isZero();assertThat(batchStatus(b)).isEqualTo("validated");
        var first=commit(registrarToken,b,2);assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(first.getBody()).containsEntry("alreadyImported",false).containsEntry("recordsCreated",6);
        var again=call(HttpMethod.POST,"/batches/"+b+"/commit",Map.of("version",0,"expectedValidCount",99),registrarToken);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(again.getBody()).containsEntry("alreadyImported",true).containsEntry("status","imported").containsEntry("recordsCreated",6);
        assertThat(records(b)).as("a repeat creates nothing").isEqualTo(6);
        // Batches with nothing importable, failed batches and unknown batches.
        long none=stage(s,csv(s,"SYN-%N%-99,Grade 7,1,2,3"));var empty=commit(registrarToken,none,0);assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);assertThat(empty.getBody().get("detail").toString()).contains("no importable rows");
        var failed=upload(registrarToken,iligan,s,"f.csv",("Student No,Grade Level,Remarks,SYNMATH"+s.n()+" - Q1\r\nSYN-"+s.n()+"-01,Grade 7,x,1\r\n").getBytes(StandardCharsets.UTF_8));
        assertThat(commit(registrarToken,((Number)failed.getBody().get("batchId")).longValue(),0).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.POST,"/batches/999999999/commit",Map.of("version",0,"expectedValidCount",1),registrarToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
    @Test void twoConcurrentCommitsImportOnceAndRefuseTheLoser()throws Exception{
        var s=setup();StringBuilder rows=new StringBuilder();List<String> lines=new ArrayList<>();
        for(int i=0;i<120;i++){student(s,String.format("%03d",i));lines.add("SYN-%N%-"+String.format("%03d",i)+",Grade 7,"+(60+i%40)+",,");}
        long b=stage(s,csv(s,lines.toArray(String[]::new)));assertThat(batchVersion(b)).isZero();int version=batchVersion(b);
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);List<Future<ResponseEntity<Map>>> results=new ArrayList<>();
        for(int i=0;i<2;i++)results.add(pool.submit(()->{start.await();return call(HttpMethod.POST,"/batches/"+b+"/commit",Map.of("version",version,"expectedValidCount",120),registrarToken);}));
        start.countDown();List<Integer> codes=new ArrayList<>();for(var f:results)codes.add(f.get(60,TimeUnit.SECONDS).getStatusCode().value());pool.shutdown();
        assertThat(codes).as(codes.toString()).allMatch(c->c==200||c==409).contains(200);
        assertThat(records(b)).as("each cell is written exactly once").isEqualTo(120);assertThat(batchStatus(b)).isEqualTo("imported");
        assertThat(commit(registrarToken,b,120).getBody()).containsEntry("alreadyImported",true);assertThat(records(b)).isEqualTo(120);
    }
    @Test void aFailureAnywhereInTheCommitRollsEverythingBack()throws Exception{
        var s=setup();String n=s.n();student(s,"01");student(s,"02");student(s,"03");
        long b=stage(s,csv(s,"SYN-%N%-01,Grade 7,90,91,92","SYN-%N%-02,Grade 7,80,81,82","SYN-%N%-03,Grade 7,70,71,72"));
        db.execute("CREATE OR REPLACE FUNCTION fail_grade_commit_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.source_column LIKE 'SYNSCI"+n+" - Q1' AND NEW.value=72 THEN RAISE EXCEPTION 'synthetic failure' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
        db.execute("CREATE TRIGGER fail_grade_commit_test BEFORE INSERT ON legacy_grade_records FOR EACH ROW EXECUTE FUNCTION fail_grade_commit_test()");
        try{
            var r=commit(registrarToken,b,3);assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);assertThat(r.getBody().get("detail").toString()).contains("Nothing was imported");
            assertThat(records(b)).as("the first rows' records were rolled back too").isZero();assertThat(batchStatus(b)).isEqualTo("validated");
            assertThat(count("SELECT count(*) FROM import_rows WHERE batch_id=? AND status='valid'",b)).isEqualTo(3);assertThat(count("SELECT count(*) FROM import_rows WHERE batch_id=? AND student_id IS NOT NULL",b)).isZero();
            assertThat(db.queryForObject("SELECT imported_by FROM import_batches WHERE id=?",Long.class,b)).isNull();
        }finally{db.execute("DROP TRIGGER fail_grade_commit_test ON legacy_grade_records");db.execute("DROP FUNCTION fail_grade_commit_test()");}
        assertThat(commit(registrarToken,b,3).getStatusCode()).as("the same batch commits once the cause is gone").isEqualTo(HttpStatus.OK);assertThat(records(b)).isEqualTo(9);
    }
    @Test void recordsChangedAfterStagingDemoteRowsAndTheCommitAnswers409WithoutImportingAnything()throws Exception{
        var s=setup();long s1=student(s,"01"),s2=student(s,"02"),s3=student(s,"03");long s4=student(s,"04");
        long b=stage(s,csv(s,"SYN-%N%-01,Grade 7,90,,","SYN-%N%-02,Grade 7,80,85,","SYN-%N%-03,Grade 7,70,,","SYN-%N%-04,Grade 7,60,,"));assertThat(b).isPositive();
        prior(s,s1,"Q1","55");                                                       // a different value was recorded after staging
        prior(s,s2,"Q1","80");                                                       // an identical one: skipped, not a conflict
        db.update("UPDATE students SET campus_id=? WHERE id=?",bugo,s3);                // the student moved campus after staging
        db.update("UPDATE subjects SET active=false WHERE id=?",s.sci());              // an inactive subject stays acceptable
        long records=count("SELECT count(*) FROM legacy_grade_records");
        var r=commit(registrarToken,b,4);assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);assertThat(r.getBody().get("detail").toString()).contains("2 row(s) can no longer be imported");
        assertThat(count("SELECT count(*) FROM legacy_grade_records")).as("nothing imported").isEqualTo(records);assertThat(batchStatus(b)).isEqualTo("validated");
        assertThat(rowStatus(b,2)).isEqualTo("invalid");assertThat(rowStatus(b,3)).isEqualTo("valid");assertThat(rowStatus(b,4)).isEqualTo("invalid");assertThat(rowStatus(b,5)).isEqualTo("valid");
        assertThat(db.queryForList("SELECT error_code FROM import_exceptions WHERE batch_id=? AND row_number=2 AND severity='error'",String.class,b)).containsExactly("CONFLICTS_WITH_RECORDED");
        assertThat(db.queryForList("SELECT error_code FROM import_exceptions WHERE batch_id=? AND row_number=4 AND severity='error'",String.class,b)).containsExactly("STUDENT_UNAVAILABLE");
        var detail=get("/batches/"+b,registrarToken).getBody();assertThat(detail).containsEntry("validCount",2).containsEntry("invalidCount",2).containsEntry("importedCount",0);
        var ok=commit(registrarToken,b,2);assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("rowsImported",2).containsEntry("recordsCreated",2).containsEntry("recordsAlreadyRecorded",1).containsEntry("invalid",2);
        assertThat(count("SELECT count(*) FROM legacy_grade_records WHERE student_id=? AND voided_at IS NULL",s2)).as("the identical cell was not duplicated").isEqualTo(2);
        assertThat(s4).isPositive();
    }
    @Test void aReviewedLinkThatCollidesWithAnotherRowIsDemotedAtCommit()throws Exception{
        var s=setup();String n=s.n();long a=student(s,"01"),s04=student(s,"04"),s05=student(s,"05","99"+n+"05",iligan);
        byte[] file=("Student No,LRN,Grade Level,SYNMATH"+n+" - Q1\r\nSYN-"+n+"-05,,Grade 7,90\r\nSYN-"+n+"-04,99"+n+"05,Grade 7,91\r\n").getBytes(StandardCharsets.UTF_8);
        long b=stage(s,file);assertThat(rowStatus(b,3)).isEqualTo("duplicate_flagged");
        assertThat(review(registrarToken,b,rowId(b,3),"same_person","Same learner as row 2",s05).getStatusCode()).isEqualTo(HttpStatus.OK);
        var r=commit(registrarToken,b,2);assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rowStatus(b,3)).isEqualTo("invalid");assertThat(rowStatus(b,2)).isEqualTo("valid");assertThat(db.queryForList("SELECT error_code FROM import_exceptions WHERE batch_id=? AND row_number=3 AND severity='error'",String.class,b)).containsExactly("DUPLICATE_IN_BATCH");
        assertThat(db.queryForObject("SELECT matched_student_id FROM import_rows WHERE batch_id=? AND row_number=3",Long.class,b)).isNull();assertThat(records(b)).isZero();
        assertThat(commit(registrarToken,b,1).getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(records(b)).isEqualTo(1);assertThat(a+s04).isPositive();
    }

    // ---- void and discard; checksum rules ------------------------------------------------------------------------------
    @Test void voidingACommittedBatchVoidsEveryRecordAndFreesTheFileForUploadAgain()throws Exception{
        var s=setup();long s1=student(s,"01");byte[] file=csv(s,"SYN-%N%-01,Grade 7,88.50,90,INC");long b=stage(s,file);
        assertThat(voidBatch(registrarToken,b,"Too early").getStatusCode()).as("only committed batches are voided").isEqualTo(HttpStatus.CONFLICT);
        assertThat(commit(registrarToken,b,1).getStatusCode()).isEqualTo(HttpStatus.OK);
        var dup=upload(registrarToken,iligan,s,"again.csv",file);assertThat(dup.getStatusCode()).as("a committed file blocks its checksum").isEqualTo(HttpStatus.CONFLICT);assertThat(dup.getBody()).containsEntry("batchStatus","imported");
        assertThat(call(HttpMethod.POST,"/batches/"+b+"/void",Map.of("version",batchVersion(b),"reason","  "),registrarToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.POST,"/batches/"+b+"/void",Map.of("version",batchVersion(b)+3,"reason","Wrong file"),registrarToken).getStatusCode()).as("stale version").isEqualTo(HttpStatus.CONFLICT);
        var v=voidBatch(registrarToken,b,"Uploaded the wrong class by mistake");assertThat(v.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(v.getBody()).containsEntry("voided",true).containsEntry("voidedRecords",3).containsEntry("alreadyVoided",false).containsEntry("status","imported");
        assertThat(liveRecords(b)).isZero();assertThat(records(b)).as("history is kept").isEqualTo(3);
        var rec=db.queryForMap("SELECT voided_by,void_reason FROM legacy_grade_records WHERE student_id=? LIMIT 1",s1);assertThat(rec).containsEntry("voided_by",registrar).containsEntry("void_reason","Uploaded the wrong class by mistake");
        var batch=db.queryForMap("SELECT voided_at,voided_by,void_reason,status FROM import_batches WHERE id=?",b);assertThat(batch.get("voided_at")).isNotNull();assertThat(batch).containsEntry("voided_by",registrar).containsEntry("status","imported");
        var detail=get("/batches/"+b,registrarToken).getBody();assertThat(detail).containsEntry("liveRecordCount",0).containsEntry("voidedRecordCount",3).containsEntry("voidReason","Uploaded the wrong class by mistake");
        assertThat(voidBatch(registrarToken,b,"Again").getBody()).as("a repeat is idempotent").containsEntry("alreadyVoided",true).containsEntry("voidedRecords",0);
        assertThat(commit(registrarToken,b,1).getStatusCode()).as("a voided batch cannot be committed again").isEqualTo(HttpStatus.CONFLICT);
        assertThat(count("SELECT count(*) FROM audit_logs WHERE table_name='legacy_grade_records' AND operation='UPDATE' AND actor_id=?",registrar)).isGreaterThanOrEqualTo(3);
        var again=upload(registrarToken,iligan,s,"again.csv",file);assertThat(again.getStatusCode()).as("the same file can be uploaded again").isEqualTo(HttpStatus.CREATED);
        long b2=((Number)again.getBody().get("id")).longValue();assertThat(b2).isNotEqualTo(b);assertThat(commit(registrarToken,b2,1).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(liveRecords(b2)).isEqualTo(3);assertThat(count("SELECT count(*) FROM legacy_grade_records WHERE student_id=? AND voided_at IS NULL",s1)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM legacy_grade_records WHERE student_id=?",s1)).isEqualTo(6);
    }
    @SuppressWarnings("unchecked")
    @Test void voidingABatchThatALaterBatchReliesOnIsRefusedUntilTheLaterBatchIsVoided()throws Exception{
        // ICI2-89: B is a corrected superset of A. Its cells for student 01 match A's, so B skips them as already recorded and owns no
        // record for them. Voiding A alone would erase those grades while B still says imported.
        var s=setup();long s1=student(s,"01");long s2=student(s,"02");
        long a=stage(s,csv(s,"SYN-%N%-01,Grade 7,88.50,90,INC"));assertThat(commit(registrarToken,a,1).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map>)get("/batches/"+a,registrarToken).getBody().get("dependentBatches")).as("nothing relies on A yet").isEmpty();
        long b=stage(s,csv(s,"SYN-%N%-01,Grade 7,88.50,90,INC","SYN-%N%-02,Grade 7,80,81,82"));
        assertThat(commit(registrarToken,b,2).getBody()).containsEntry("recordsCreated",3).containsEntry("recordsAlreadyRecorded",3);

        var listed=(List<Map>)get("/batches/"+a,registrarToken).getBody().get("dependentBatches");
        assertThat(listed).singleElement().satisfies(d->{assertThat(((Number)d.get("batchId")).longValue()).isEqualTo(b);assertThat(((Number)d.get("sharedRecords")).longValue()).isEqualTo(3);});
        assertThat((List<Map>)get("/batches/"+b,registrarToken).getBody().get("dependentBatches")).as("B owns its own records; nothing relies on it").isEmpty();

        var refused=voidBatch(registrarToken,a,"Replaced by the corrected file");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).containsEntry("code","VOID_HAS_DEPENDENT_BATCHES");
        assertThat(refused.getBody().get("detail").toString()).contains("#"+b,"(3 grade(s))","Void that batch first");
        assertThat((List<Map>)refused.getBody().get("dependentBatches")).singleElement().satisfies(d->assertThat(((Number)d.get("batchId")).longValue()).isEqualTo(b));
        assertThat(liveRecords(a)).as("nothing was voided").isEqualTo(3);
        assertThat(db.queryForObject("SELECT voided_at FROM import_batches WHERE id=?",java.sql.Timestamp.class,a)).isNull();

        assertThat(voidBatch(registrarToken,b,"Re-uploading the corrected file").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map>)get("/batches/"+a,registrarToken).getBody().get("dependentBatches")).as("a voided batch no longer relies on A").isEmpty();
        var voided=voidBatch(registrarToken,a,"Replaced by the corrected file");
        assertThat(voided.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(voided.getBody()).containsEntry("voidedRecords",3);
        assertThat(count("SELECT count(*) FROM legacy_grade_records WHERE student_id IN (?,?) AND voided_at IS NULL",s1,s2)).isZero();
    }
    @Test void discardingAnUncommittedBatchFreesTheFileKeepsHistoryAndNeverTouchesCommittedOnes()throws Exception{
        var s=setup();student(s,"01");byte[] file=csv(s,"SYN-%N%-01,Grade 7,90,91,92");long b=stage(s,file);
        assertThat(call(HttpMethod.POST,"/batches/"+b+"/discard",Map.of("version",batchVersion(b),"reason"," "),registrarToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.POST,"/batches/"+b+"/discard",Map.of("version",batchVersion(b)+1,"reason","Wrong file"),registrarToken).getStatusCode()).as("stale version").isEqualTo(HttpStatus.CONFLICT);
        assertThat(upload(registrarToken,iligan,s,"again.csv",file).getStatusCode()).as("a staged file blocks its checksum").isEqualTo(HttpStatus.CONFLICT);
        var d=discard(registrarToken,b,"Students were not imported yet; will upload again");assertThat(d.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(d.getBody()).containsEntry("discarded",true).containsEntry("status","failed").containsEntry("alreadyDiscarded",false);
        var detail=get("/batches/"+b,registrarToken).getBody();assertThat(detail).containsEntry("discarded",true).containsEntry("discardReason","Students were not imported yet; will upload again").containsEntry("status","failed").containsKey("discardedAt");
        assertThat(((Number)detail.get("discardedBy")).longValue()).isEqualTo(registrar);assertThat(count("SELECT count(*) FROM import_rows WHERE batch_id=?",b)).as("rows are kept").isEqualTo(1);
        assertThat(discard(registrarToken,b,"Again").getBody()).containsEntry("alreadyDiscarded",true);
        assertThat(commit(registrarToken,b,1).getStatusCode()).as("a discarded batch cannot be committed").isEqualTo(HttpStatus.CONFLICT);
        assertThat(review(registrarToken,b,rowId(b,2),"skip","Late",null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(voidBatch(registrarToken,b,"x").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var again=upload(registrarToken,iligan,s,"again.csv",file);assertThat(again.getStatusCode()).as("the same file can be uploaded again").isEqualTo(HttpStatus.CREATED);
        long b2=((Number)again.getBody().get("id")).longValue();assertThat(commit(registrarToken,b2,1).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(discard(registrarToken,b2,"Too late").getStatusCode()).as("a committed batch is voided, not discarded").isEqualTo(HttpStatus.CONFLICT);
        var failed=upload(registrarToken,iligan,s,"f.csv",("Student No,Grade Level,Remarks,SYNMATH"+s.n()+" - Q1\r\nx,Grade 7,y,1\r\n").getBytes(StandardCharsets.UTF_8));
        assertThat(discard(registrarToken,((Number)failed.getBody().get("batchId")).longValue(),"Not mine").getStatusCode()).as("a batch that failed on its own is not a discard").isEqualTo(HttpStatus.CONFLICT);
        assertThat(get("/batches?status=failed&size=100",registrarToken).getBody().toString()).contains("discarded=true");
        // The mapping used by a discarded batch no longer blocks deactivation.
        assertThat(count("SELECT count(*) FROM import_batches WHERE mapping_id IS NOT NULL AND id=? AND status='validated'",b)).isZero();
    }

    // ---- warnings, privacy and the student import ----------------------------------------------------------------------
    @Test void liveDraftGradesWarnWithTheirStateButNeverTheirValue()throws Exception{
        var s=setup();long st=student(s,"01");long program=db.queryForObject("SELECT id FROM programs WHERE code='STEM'",Long.class);long teacher=staff("ROLE_TEACHER",iligan);
        long section=db.queryForObject("INSERT INTO sections(campus_id,term_id,program_id,name,grade_level,capacity) VALUES (?,?,?,?,'7',30) RETURNING id",Long.class,iligan,s.s1(),program,"SEC"+s.n());
        long offering=db.queryForObject("INSERT INTO class_offerings(section_id,term_id,subject_id,teacher_id) VALUES (?,?,?,?) RETURNING id",Long.class,section,s.s1(),s.math(),teacher);
        long app=db.queryForObject("INSERT INTO applications(application_number,campus_id,term_id,program_id,student_id,category,first_name,last_name,birth_date) VALUES (?,?,?,?,?,'new','A','B','2009-01-01') RETURNING id",Long.class,"APP"+s.n(),iligan,s.s1(),program,st);
        long enrollment=db.queryForObject("INSERT INTO enrollments(student_id,application_id,campus_id,term_id,program_id,section_id,status) VALUES (?,?,?,?,?,?,'provisional') RETURNING id",Long.class,st,app,iligan,s.s1(),program,section);
        db.update("INSERT INTO class_memberships(class_id,enrollment_id,section_id,term_id) VALUES (?,?,?,?)",offering,enrollment,section,s.s1());
        db.update("INSERT INTO grades(class_id,enrollment_id,term_id,grading_period_id,value,state) VALUES (?,?,?,?,86.25,'draft')",offering,enrollment,s.s1(),s.q1());
        long b=stage(s,csv(s,"SYN-%N%-01,Grade 7,90.75,,"));
        var warning=db.queryForMap("SELECT severity,detail FROM import_exceptions WHERE batch_id=? AND error_code='OVERLAPS_LIVE_GRADE'",b);
        assertThat(warning).containsEntry("severity","warning");assertThat(warning.get("detail").toString()).contains("drafts count","live state: draft").doesNotContain("86.25").doesNotContain("90.75");
        assertThat(rowStatus(b,2)).isEqualTo("valid");
    }
    @Test void noGradeOrPersonalValueReachesAuditLogsOrMessages()throws Exception{
        var s=setup();long st=student(s,"01");student(s,"02");long b=stage(s,csv(s,"SYN-%N%-01,Grade 7,88.55,INC,","SYN-%N%-02,Grade 7,101,90,"));assertThat(commit(registrarToken,b,1).getStatusCode()).isEqualTo(HttpStatus.OK);
        voidBatch(registrarToken,b,"Audit check");discard(registrarToken,stage(s,csv(s,"SYN-%N%-02,Grade 7,70.15,71,")),"Audit check");
        for(String table:List.of("import_batches","import_rows","import_exceptions","legacy_grade_records")){
            // A batch row also stores its mapping snapshot, which is configuration (for example a value map translating "Inc." to the INC mark),
            // not learner data. Other tests in the shared database create such mappings, so ignore the snapshot and scan everything else.
            assertThat(count("SELECT count(*) FROM audit_logs WHERE table_name=? AND ((new_data-'mapping_snapshot')::text LIKE '%88.55%' OR (old_data-'mapping_snapshot')::text LIKE '%88.55%' OR (new_data-'mapping_snapshot')::text LIKE '%70.15%' OR (new_data-'mapping_snapshot')::text LIKE '%\"INC\"%' OR (new_data-'mapping_snapshot')::text LIKE '%raw_values%' OR (new_data-'mapping_snapshot')::text LIKE '%normalized_values%')",table)).as(table).isZero();
        }
        assertThat(count("SELECT count(*) FROM audit_logs WHERE table_name='legacy_grade_records' AND operation='INSERT' AND actor_id=?",registrar)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM audit_logs WHERE table_name='legacy_grade_records' AND (jsonb_exists(new_data,'value') OR jsonb_exists(new_data,'mark') OR jsonb_exists(old_data,'value') OR jsonb_exists(old_data,'mark'))")).isZero();
        assertThat(count("SELECT count(*) FROM import_exceptions WHERE batch_id=? AND (detail LIKE '%101%' OR detail LIKE '%88.55%')",b)).isZero();
        var errors=get("/batches/"+b+"/errors?size=100",registrarToken).getBody();assertThat(errors.toString()).doesNotContain("88.55").doesNotContain("101,");assertThat(st).isPositive();
    }
    @Test void stagedGradeRowsAreNeverOfferedAsDuplicatesOfStudentImportRows()throws Exception{
        var s=setup();String n=s.n();
        // A grade row staged for another campus whose file carries an LRN (the student on record has none, so the row is valid).
        long bugoStudent=db.queryForObject("INSERT INTO students(student_number,first_name,last_name,birth_date,campus_id) VALUES (?,?,?,'2009-03-14',?) RETURNING id",Long.class,"G-"+n,"Grade","Learner",bugo);
        byte[] grades=("Student No,LRN,Grade Level,SYNMATH"+n+" - Q1\r\nG-"+n+",99"+n+"01,Grade 7,90\r\n").getBytes(StandardCharsets.UTF_8);
        var staged=upload(bugoToken,bugo,Map.of("termId",Long.toString(s.s1()),"gradeLevel","Grade 7"),"b.csv",grades);assertThat(staged.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long gb=((Number)staged.getBody().get("id")).longValue();assertThat(rowStatus(gb,2)).isEqualTo("valid");
        assertThat(db.queryForObject("SELECT normalized_values->>'lrn' FROM import_rows WHERE batch_id=?",String.class,gb)).isEqualTo("99"+n+"01");
        // The student import for Iligan uses the same LRN; before the source_type filter this matched the grade row as a "staged_row".
        var h=new HttpHeaders();h.setBearerAuth(registrarToken);h.setContentType(MediaType.MULTIPART_FORM_DATA);var form=new LinkedMultiValueMap<String,Object>();form.add("campusId",Long.toString(iligan));
        var csv=new String(ImportFixtures.fixture("synthetic-basic.csv",n),StandardCharsets.UTF_8);form.add("file",new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)){@Override public String getFilename(){return "students.csv";}});
        var up=rest.exchange("/api/legacy-student-imports/batches",HttpMethod.POST,new HttpEntity<>(form,h),Map.class);assertThat(up.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long sb=((Number)up.getBody().get("id")).longValue();
        assertThat(up.getBody()).containsEntry("validCount",3).containsEntry("flaggedCount",0);
        assertThat(db.queryForObject("SELECT status FROM import_rows WHERE batch_id=? AND raw_values->>'Student No'=?",String.class,sb,"SYN-"+n+"-01")).isEqualTo("valid");
        assertThat(db.queryForObject("SELECT count(*) FROM import_rows WHERE batch_id=? AND duplicate_evidence IS NOT NULL",Integer.class,sb)).isZero();assertThat(bugoStudent).isPositive();
    }
}
