package ph.edu.ici;

import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReportingGuidanceTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->PostgresSupport.DATABASE.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");r.add("ici.auth.secret",()->PostgresSupport.SECRET);r.add("ici.bootstrap.enabled",()->false);
    }
    @Autowired TestRestTemplate rest;@Autowired JdbcTemplate db;@Autowired PasswordEncoder encoder;
    long campus,otherCampus,year,term,section,program,period,teacher,e1,e2;String registrarToken,principalToken,guidanceToken,teacherToken,unassignedToken,foreignToken;
    String key(){return UUID.randomUUID().toString().replace("-","").substring(0,12);}
    long staff(String role,long campus){String name="report-"+key();long id=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES (?,?,?,?) RETURNING id",Long.class,name,name+"@example.invalid",name,encoder.encode("Reporting-Test-Password-2026!"));db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES(?,?,?)",id,role,campus);return id;}
    String login(long user){return (String)rest.postForObject("/api/auth/login",Map.of("username",db.queryForObject("SELECT username FROM users WHERE id=?",String.class,user),"password","Reporting-Test-Password-2026!"),Map.class).get("accessToken");}
    long enrollment(String first,String voucher,long campus){
        long student=db.queryForObject("INSERT INTO students(student_number,first_name,last_name,birth_date,campus_id,program_id,voucher_code) VALUES (?,?,'Reportstudent','2009-01-01',?,?,?) RETURNING id",Long.class,"STU-"+key(),first,campus,program,voucher);
        long application=db.queryForObject("INSERT INTO applications(application_number,campus_id,term_id,program_id,student_id,category,first_name,last_name,birth_date,voucher_code,profile) VALUES (?,?,?,?,?,'new',?,'Reportstudent','2009-01-01',?,'{\"gradeLevel\":\"11\"}') RETURNING id",Long.class,"APP-"+key(),campus,term,program,student,first,voucher);
        return db.queryForObject("INSERT INTO enrollments(student_id,application_id,campus_id,term_id,program_id,section_id,status) VALUES(?,?,?,?,?,?,'provisional') RETURNING id",Long.class,student,application,campus,term,program,campus==this.campus?section:null);
    }
    @BeforeEach void setup(){
        campus=db.queryForObject("SELECT id FROM campuses WHERE code='ILIGAN'",Long.class);otherCampus=db.queryForObject("SELECT id FROM campuses WHERE code='BUGO'",Long.class);program=db.queryForObject("SELECT id FROM programs WHERE code='STEM'",Long.class);
        year=db.queryForObject("INSERT INTO academic_years(label,starts_on,ends_on) VALUES (?,'2026-01-01','2026-12-31') RETURNING id",Long.class,"YR"+key());
        term=db.queryForObject("INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES (?,'T1','Report term','2026-01-01','2026-12-31') RETURNING id",Long.class,year);
        section=db.queryForObject("INSERT INTO sections(campus_id,term_id,program_id,name,grade_level,capacity) VALUES (?,?,?,?,'11',30) RETURNING id",Long.class,campus,term,program,"SEC"+key());
        period=db.queryForObject("INSERT INTO grading_periods(term_id,code,sequence) VALUES (?,'Q1',1) RETURNING id",Long.class,term);
        long registrar=staff("ROLE_REGISTRAR",campus);registrarToken=login(registrar);principalToken=login(staff("ROLE_PRINCIPAL",campus));guidanceToken=login(staff("ROLE_GUIDANCE",campus));teacher=staff("ROLE_TEACHER",campus);teacherToken=login(teacher);unassignedToken=login(staff("ROLE_TEACHER",campus));
        long foreign=staff("ROLE_REGISTRAR",otherCampus);db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,'ROLE_TEACHER',?)",foreign,campus);foreignToken=login(foreign);
        e1=enrollment("=FORMULA\nJosé Ñ", "QVR",campus);e2=enrollment("Second","ESC",campus);enrollment("Hidden","QVR",otherCampus);
        long app=db.queryForObject("SELECT application_id FROM enrollments WHERE id=?",Long.class,e2);db.update("INSERT INTO application_requirements(application_id,requirement_code,status) SELECT ?,code,'submitted' FROM requirement_types WHERE mandatory",app);
        long subject=db.queryForObject("INSERT INTO subjects(code,name) VALUES (?,'Reporting subject') RETURNING id",Long.class,"SUB"+key());
        long offering=db.queryForObject("INSERT INTO class_offerings(section_id,term_id,subject_id,teacher_id) VALUES (?,?,?,?) RETURNING id",Long.class,section,term,subject,teacher);
        for(long e:List.of(e1,e2))db.update("INSERT INTO class_memberships(class_id,enrollment_id,section_id,term_id) VALUES(?,?,?,?)",offering,e,section,term);
        db.update("INSERT INTO grades(class_id,enrollment_id,term_id,grading_period_id,mark,state) VALUES (?,?,?,?,'INC','submitted')",offering,e1,term,period);
        db.update("INSERT INTO grades(class_id,enrollment_id,term_id,grading_period_id,value,state) VALUES (?,?,?,?,40,'submitted')",offering,e2,term,period);
    }
    ResponseEntity<Map> call(HttpMethod method,String path,Object body,String token){var h=new HttpHeaders();if(token!=null)h.setBearerAuth(token);h.setContentType(MediaType.APPLICATION_JSON);return rest.exchange(path,method,new HttpEntity<>(body,h),Map.class);}
    ResponseEntity<byte[]> bytes(String path,String token){var h=new HttpHeaders();h.setBearerAuth(token);return rest.exchange(path,HttpMethod.GET,new HttpEntity<>(h),byte[].class);}
    String report(){return "/api/reports/enrollments?termId="+term;}
    String policy(){return "/api/guidance/policies/"+campus+"/"+term;}
    String calendar(){return "/api/academic/school-days/"+campus+"/"+term;}
    Map<String,Object> day(String date,boolean school,int version){return Map.of("date",date,"schoolDay",school,"version",version);}
    Map<String,Object> attendance(long enrollment,String date,String status,int version){return Map.of("enrollmentId",enrollment,"date",date,"status",status,"version",version,"note","Fictional test attendance");}
    void configure(){
        var result=call(HttpMethod.PUT,policy(),Map.of("failingBelow",75,"consecutiveAbsences",3,"credentialsDueOn","2026-01-01","reason","Fictional approved thresholds","version",0),registrarToken);assertThat(result.getStatusCode()).as(result.toString()).isEqualTo(HttpStatus.OK);
        var calendar=call(HttpMethod.PUT,calendar(),Map.of("reason","Fictional test calendar","days",List.of(day("2026-01-05",true,0),day("2026-01-06",true,0),day("2026-01-07",false,0),day("2026-01-08",true,0),day("2026-01-09",true,0))),registrarToken);assertThat(calendar.getStatusCode()).as(calendar.toString()).isEqualTo(HttpStatus.OK);
    }

    @Test void combinedReportFiltersTotalsAndScopeAreConsistent(){
        var result=call(HttpMethod.GET,report()+"&academicYearId="+year+"&gradingPeriodId="+period+"&sectionId="+section+"&gradeLevel=11&voucher=QVR&requirementStatus=incomplete&size=1",null,principalToken);
        assertThat(result.getStatusCode()).as(result.toString()).isEqualTo(HttpStatus.OK);assertThat(((Number)result.getBody().get("total")).intValue()).isEqualTo(1);assertThat((List)result.getBody().get("items")).hasSize(1);
        assertThat(((Map)((List)result.getBody().get("items")).getFirst())).containsEntry("missingRequirements",4);
        assertThat(call(HttpMethod.GET,report()+"&requirementStatus=complete",null,registrarToken).getBody().get("total")).isEqualTo(1);
        assertThat(call(HttpMethod.GET,report()+"&academicYearId="+(year+999999),null,registrarToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.GET,report()+"&campusId="+otherCampus,null,registrarToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET,report(),null,teacherToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET,report(),null,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(((Number)call(HttpMethod.GET,report(),null,foreignToken).getBody().get("total")).intValue()).isEqualTo(1);
        assertThat(call(HttpMethod.GET,report()+"&size=101",null,registrarToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void filterOptionsStayInsideTheCallersReportCampuses(){
        var principal=call(HttpMethod.GET,"/api/reports/filter-options",null,principalToken);
        assertThat(principal.getStatusCode()).as(principal.toString()).isEqualTo(HttpStatus.OK);
        assertThat(ids(principal.getBody(),"campuses")).contains(campus).doesNotContain(otherCampus);
        assertThat(ids(principal.getBody(),"sections")).contains(section);
        assertThat((List)principal.getBody().get("vouchers")).contains("QVR","ESC");
        var foreign=call(HttpMethod.GET,"/api/reports/filter-options",null,foreignToken).getBody();
        assertThat(ids(foreign,"campuses")).contains(otherCampus).doesNotContain(campus);
        assertThat(ids(foreign,"sections")).doesNotContain(section);
        assertThat(call(HttpMethod.GET,"/api/reports/filter-options",null,teacherToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET,"/api/reports/filter-options",null,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    List<Long> ids(Map body,String key){return ((List<Map>)body.get(key)).stream().map(row->((Number)row.get("id")).longValue()).toList();}
    @Test void csvAndPdfExportsPreserveNamesAndPreventFormulas()throws Exception{
        String query="?termId="+term+"&voucher=QVR";var csv=bytes("/api/reports/enrollments/export"+query+"&format=csv",registrarToken);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);String text=new String(csv.getBody(),StandardCharsets.UTF_8);assertThat(csv.getBody()).startsWith((byte)0xEF,(byte)0xBB,(byte)0xBF);assertThat(text).contains("'\u003dFORMULA\nJosé Ñ").doesNotContain("Hidden");
        var pdf=bytes("/api/reports/enrollments/export"+query+"&format=pdf",principalToken);assertThat(pdf.getStatusCode()).as(new String(pdf.getBody(),StandardCharsets.UTF_8)).isEqualTo(HttpStatus.OK);
        try(var doc=Loader.loadPDF(pdf.getBody())){assertThat(new PDFTextStripper().getText(doc)).contains("José Ñ").contains("Matching enrollments: 1");}
        assertThat(bytes("/api/reports/enrollments/export"+query+"&format=exe",registrarToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void exportWritesOneAuditRowWithoutNamesAndPreviewWritesNone()throws Exception{
        String sql="SELECT count(*) FROM audit_logs WHERE table_name='enrollment_report_exports'";
        int before=db.queryForObject(sql,Integer.class);
        assertThat(call(HttpMethod.GET,report()+"&voucher=QVR",null,registrarToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(db.queryForObject(sql,Integer.class)).isEqualTo(before);
        var csv=bytes("/api/reports/enrollments/export?termId="+term+"&voucher=QVR&format=csv",registrarToken);assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(db.queryForObject(sql,Integer.class)).isEqualTo(before+1);
        var audit=db.queryForMap("SELECT operation,actor_id,new_data->>'rows' AS rows,new_data->>'sha256' AS sha,new_data->>'format' AS format,new_data->>'termId' AS term,new_data->>'voucher' AS voucher,new_data::text AS data FROM audit_logs WHERE table_name='enrollment_report_exports' ORDER BY id DESC LIMIT 1");
        assertThat(audit.get("operation")).isEqualTo("EXPORT");assertThat(audit.get("actor_id")).isNotNull();assertThat(audit.get("rows")).isEqualTo("1");assertThat(audit.get("format")).isEqualTo("csv");
        assertThat(audit.get("term")).isEqualTo(String.valueOf(term));assertThat(audit.get("voucher")).isEqualTo("QVR");
        assertThat(audit.get("sha")).isEqualTo(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(csv.getBody())));
        assertThat((String)audit.get("data")).doesNotContain("José").doesNotContain("Reportstudent").doesNotContain("FORMULA");
        assertThat(bytes("/api/reports/enrollments/export?termId="+term+"&format=pdf",principalToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(db.queryForObject(sql,Integer.class)).isEqualTo(before+2);
        assertThat(bytes("/api/reports/enrollments/export?termId="+term+"&format=exe",registrarToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(db.queryForObject(sql,Integer.class)).isEqualTo(before+2);
    }
    @Test void explicitCalendarAttendanceAndConfiguredRiskSignals(){
        var initial=call(HttpMethod.GET,"/api/guidance/risks?termId="+term+"&asOf=2026-01-08",null,guidanceToken);assertThat(initial.getStatusCode()).as(initial.toString()).isEqualTo(HttpStatus.OK);
        var before=(List<Map>)initial.getBody().get("items");assertThat((List)before.stream().filter(r->((Number)r.get("enrollmentId")).longValue()==e1).findFirst().orElseThrow().get("unconfiguredSignals")).contains("failing_grade_threshold","consecutive_absence_threshold","credential_due_date");
        configure();var write=call(HttpMethod.POST,"/api/academic/daily-attendance/import",Map.of("entries",List.of(attendance(e1,"2026-01-05","absent",0),attendance(e1,"2026-01-06","absent",0),attendance(e1,"2026-01-08","absent",0))),teacherToken);assertThat(write.getStatusCode()).as(write.toString()).isEqualTo(HttpStatus.OK);
        var items=(List<Map>)call(HttpMethod.GET,"/api/guidance/risks?termId="+term+"&asOf=2026-01-08",null,guidanceToken).getBody().get("items");
        var first=items.stream().filter(r->((Number)r.get("enrollmentId")).longValue()==e1).findFirst().orElseThrow();assertThat((List)first.get("signals")).contains("incomplete_grades","overdue_credentials","consecutive_unexcused_absences");assertThat(first).containsEntry("consecutiveUnexcusedAbsences",3);
        assertThat((List)items.stream().filter(r->((Number)r.get("enrollmentId")).longValue()==e2).findFirst().orElseThrow().get("signals")).containsExactly("failing_grades");
        assertThat(call(HttpMethod.POST,"/api/academic/daily-attendance/import",Map.of("entries",List.of(attendance(e1,"2026-01-06","excused",1))),registrarToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        var reset=(List<Map>)call(HttpMethod.GET,"/api/guidance/risks?termId="+term+"&asOf=2026-01-08",null,guidanceToken).getBody().get("items");assertThat(reset.stream().filter(r->((Number)r.get("enrollmentId")).longValue()==e1).findFirst().orElseThrow()).containsEntry("consecutiveUnexcusedAbsences",1);
        assertThat(call(HttpMethod.GET,"/api/guidance/risks?termId="+term,null,principalToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    @Test void attendanceImportsAreAtomicVersionedAndAssignedTeacherScoped(){
        configure();var valid=attendance(e1,"2026-01-05","present",0);assertThat(call(HttpMethod.POST,"/api/academic/daily-attendance/import",Map.of("entries",List.of(valid)),unassignedToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var failed=call(HttpMethod.POST,"/api/academic/daily-attendance/import",Map.of("entries",List.of(valid,attendance(e1,"2026-01-10","absent",0))),registrarToken);assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);assertThat(db.queryForObject("SELECT count(*) FROM daily_academic_attendance WHERE enrollment_id=?",Integer.class,e1)).isZero();
        assertThat(call(HttpMethod.POST,"/api/academic/daily-attendance/import",Map.of("entries",List.of(valid)),teacherToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST,"/api/academic/daily-attendance/import",Map.of("entries",List.of(valid)),teacherToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((List)call(HttpMethod.GET,"/api/academic/daily-attendance/"+e1,null,teacherToken).getBody().get("items")).hasSize(1);
        assertThat(call(HttpMethod.PUT,calendar(),Map.of("reason","Cannot remove recorded day","days",List.of(day("2026-01-05",false,1))),registrarToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.PUT,policy(),Map.of("reason","No guidance config permission","version",1),guidanceToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    @Test void counselingNotesArePersistentVersionedAndConfidential()throws Exception{
        var created=call(HttpMethod.POST,"/api/guidance/cases",Map.of("enrollmentId",e1,"reason","Fictional referral"),guidanceToken);assertThat(created.getStatusCode()).as(created.toString()).isEqualTo(HttpStatus.CREATED);long id=((Number)created.getBody().get("id")).longValue();String path="/api/guidance/cases/"+id;
        var note=call(HttpMethod.POST,path+"/notes",Map.of("note","\n=Confidential fictional case note","version",1,"actor",999999),guidanceToken);assertThat(note.getStatusCode()).as(note.toString()).isEqualTo(HttpStatus.OK);assertThat(note.getBody()).containsEntry("version",2);assertThat((List)call(HttpMethod.GET,path,null,guidanceToken).getBody().get("notes")).hasSize(1);
        assertThat(call(HttpMethod.POST,path+"/notes",Map.of("note","stale","version",1),guidanceToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.PUT,path,Map.of("status","monitoring","reason","Reviewed fictional referral","version",2),guidanceToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET,path,null,registrarToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(new String(bytes(path+"/export?format=csv",guidanceToken).getBody(),StandardCharsets.UTF_8)).contains("'=Confidential");
        var pdf=bytes(path+"/export?format=pdf",guidanceToken);assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);try(var doc=Loader.loadPDF(pdf.getBody())){assertThat(new PDFTextStripper().getText(doc)).contains("Confidential fictional case note");}
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='guidance_case_notes' AND new_data->>'case_id'=? AND actor_id IS NOT NULL",Integer.class,Long.toString(id))).isEqualTo(1);
    }
    @Test void missingAttendanceAndCalendarGapsBreakConsecutiveRuns(){
        configure();assertThat(call(HttpMethod.POST,"/api/academic/daily-attendance/import",Map.of("entries",List.of(attendance(e1,"2026-01-05","absent",0),attendance(e1,"2026-01-08","absent",0))),registrarToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        var items=(List<Map>)call(HttpMethod.GET,"/api/guidance/risks?termId="+term+"&asOf=2026-01-08",null,guidanceToken).getBody().get("items");assertThat(items.stream().filter(r->((Number)r.get("enrollmentId")).longValue()==e1).findFirst().orElseThrow()).containsEntry("consecutiveUnexcusedAbsences",1);
        var unknown=(List<Map>)call(HttpMethod.GET,"/api/guidance/risks?termId="+term+"&asOf=2026-01-10",null,guidanceToken).getBody().get("items");assertThat(unknown.stream().filter(r->((Number)r.get("enrollmentId")).longValue()==e1).findFirst().orElseThrow()).containsEntry("consecutiveUnexcusedAbsences",0);
    }
    @Test void proportionalPdfWrapsWithinPageAndRetainsAllText()throws Exception{
        String wide="W".repeat(250);var lines=new ArrayList<String>();for(int i=0;i<100;i++)lines.add(wide);
        byte[] bytes=ph.edu.ici.reporting.ReportDocuments.pdf("Wide text regression",lines);
        try(var document=Loader.loadPDF(bytes)){
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            var extractor=new PDFTextStripper(){
                @Override protected void processTextPosition(org.apache.pdfbox.text.TextPosition text){
                    assertThat(text.getXDirAdj()+text.getWidthDirAdj()).isLessThanOrEqualTo(576.1f);super.processTextPosition(text);
                }
            };
            String text=extractor.getText(document);assertThat(text.chars().filter(c->c=='W').count()).isEqualTo(25001);
        }
        for(String value:List.of("\n=SUM(1,2)","  \n+SUM(1,2)","\t@malicious","\u200B-formula"))assertThat(ph.edu.ici.reporting.ReportDocuments.csvCell(value)).startsWith("\"'");
    }
    @Test void reversedEnrollmentAndCalendarDateOrderCompletesWithoutDeadlock()throws Exception{
        configure();var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var start=new java.util.concurrent.CountDownLatch(1);
            var edit=pool.submit(()->{start.await();return call(HttpMethod.PUT,calendar(),Map.of("reason","Concurrent calendar revision","days",List.of(day("2026-01-05",true,1),day("2026-01-08",true,1))),registrarToken);});
            var imported=pool.submit(()->{start.await();return call(HttpMethod.POST,"/api/academic/daily-attendance/import",Map.of("entries",List.of(attendance(e1,"2026-01-08","absent",0),attendance(e2,"2026-01-05","present",0))),registrarToken);});
            start.countDown();assertThat(edit.get(15,java.util.concurrent.TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(imported.get(15,java.util.concurrent.TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(db.queryForObject("SELECT count(*) FROM daily_academic_attendance WHERE enrollment_id IN (?,?)",Integer.class,e1,e2)).isEqualTo(2);
        }finally{pool.shutdownNow();}
    }

    @Test void oversizedExportsRejectWithoutTruncatingPreviewTotals(){
        String prefix=key();
        db.update("""
            WITH students_added AS (
              INSERT INTO students(student_number,first_name,last_name,birth_date,campus_id,program_id)
              SELECT ?||n,'Bulk','Reportstudent','2009-01-01',?,? FROM generate_series(1,5000) n RETURNING id,student_number
            ), applications_added AS (
              INSERT INTO applications(application_number,campus_id,term_id,program_id,student_id,category,first_name,last_name,birth_date)
              SELECT student_number,?,?,?,id,'new','Bulk','Reportstudent','2009-01-01' FROM students_added RETURNING id,student_id
            ) INSERT INTO enrollments(student_id,application_id,campus_id,term_id,program_id,status)
              SELECT student_id,id,?,?,?,'provisional' FROM applications_added
            """,prefix,campus,program,campus,term,program,campus,term,program);
        var preview=call(HttpMethod.GET,report()+"&size=1",null,registrarToken);assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(((Number)preview.getBody().get("total")).intValue()).isEqualTo(5002);assertThat((List)preview.getBody().get("items")).hasSize(1);
        for(String format:List.of("csv","pdf"))assertThat(bytes("/api/reports/enrollments/export?termId="+term+"&format="+format,registrarToken).getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(call(HttpMethod.GET,"/api/guidance/risks?termId="+term,null,guidanceToken).getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

}
