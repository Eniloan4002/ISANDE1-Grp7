package ph.edu.ici.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ph.edu.ici.PostgresSupport;
import ph.edu.ici.auth.StaffIdentity;
import ph.edu.ici.imports.GradeMatrixValidationService.UploadContext;
import ph.edu.ici.imports.ImportMappingService.MappingInput;
import static org.assertj.core.api.Assertions.*;

/** ICI2-39 phase 2 against embedded PostgreSQL: V22 storage, mapping validation of the new settings, staging with opt-in cleaning, the new read fields and audit privacy. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class CleaningFoundationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->PostgresSupport.DATABASE.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");r.add("ici.auth.secret",()->PostgresSupport.SECRET);r.add("ici.bootstrap.enabled",()->false);
    }
    @Autowired JdbcTemplate db;@Autowired ObjectMapper json;
    @Autowired ImportValidationService students;@Autowired ImportMappingService studentMappings;@Autowired ImportBatchService studentBatches;
    @Autowired GradeMatrixValidationService grades;@Autowired GradeMatrixMappingService gradeMappings;@Autowired GradeMatrixBatchService gradeBatches;
    long iligan,bugo,registrar,admin;

    @BeforeEach void users(){
        iligan=db.queryForObject("SELECT id FROM campuses WHERE code='ILIGAN'",Long.class);bugo=db.queryForObject("SELECT id FROM campuses WHERE code='BUGO'",Long.class);
        registrar=user("ROLE_REGISTRAR",iligan);admin=user("ROLE_ADMIN",0);as(registrar);
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    long user(String role,long campus){
        String name="clean-"+UUID.randomUUID();
        long id=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES (?,?,?,'unused') RETURNING id",Long.class,name,name+"@example.invalid","Synthetic staff");
        if(campus>0)db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,?,?)",id,role,campus);else db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) SELECT ?,?,id FROM campuses",id,role);
        return id;
    }
    void as(long id){var identity=new StaffIdentity(id,"u"+id,"Synthetic staff",List.of(),UUID.randomUUID(),Instant.now().plusSeconds(600),false);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(identity,null,List.of()));}
    String n(){return ImportFixtures.n();}
    byte[] text(String csv){return csv.getBytes(StandardCharsets.UTF_8);}
    JsonNode tree(Object v)throws Exception{return v==null?null:json.readTree(v.toString());}

    long studentMapping(Consumer<ObjectNode> mappingEdit,Consumer<ObjectNode> settingsEdit){
        var d=studentMappings.forUpload(null);ObjectNode m=d.mapping().deepCopy(),s=d.settings().deepCopy();mappingEdit.accept(m);settingsEdit.accept(s);
        as(admin);try{return studentMappings.create(new MappingInput("Cleaning "+UUID.randomUUID(),m,s,false,true,false)).id();}finally{as(registrar);}
    }
    /** The ICI2-39 opt-ins that the staging tests turn on. */
    static void cleaning(ObjectNode settings){
        settings.putArray("dateFormats").add("yyyy-MM-dd").add("M/d/yyyy");settings.put("normalizeNameCase",true);
        var c=settings.putObject("cleaning");c.put("unicodeSpaces",true).put("stripInvisible",true).put("lrnStripSeparators",true).put("extendedDateFormats",true);
        c.putArray("blankValues").add("n/a").add("-");c.putArray("nameParticles").add("de").add("la");
    }
    String studentCsv(String... rows){return "Student No,LRN,Last Name,First Name,Middle Name,Birth Date,Sex,Previous School,Voucher,Strand\n"+String.join("\n",rows)+"\n";}

    // ---- V22 -----------------------------------------------------------------------------------------------------------------
    @Test void v22AddsTheColumnsDefaultsExistingBatchesToCsvAndChecksTheirShape()throws Exception{
        String n=n();long id=students.stage(iligan,"v22.csv",text(studentCsv("SYN-"+n+"-01,99"+n+"01,Alphaone"+ImportFixtures.letters(n)+",Ana,,2009-03-14,,,QVR,STEM")),null).batchId();
        assertThat(db.queryForObject("SELECT source_format FROM import_batches WHERE id=?",String.class,id)).isEqualTo("csv");
        assertThat(db.queryForObject("SELECT count(*) FROM import_batches WHERE source_format<>'csv'",Integer.class)).as("every batch staged so far is csv").isZero();
        assertThat(db.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name IN ('import_batches','import_rows') AND column_name IN ('source_format','cleaning_summary','cleaning') ORDER BY column_name",String.class)).containsExactly("cleaning","cleaning_summary","source_format");
        assertThatThrownBy(()->db.update("UPDATE import_batches SET source_format='pdf' WHERE id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
        db.update("UPDATE import_batches SET source_format='xlsx' WHERE id=?",id);db.update("UPDATE import_batches SET source_format='csv' WHERE id=?",id);
        assertThatThrownBy(()->db.update("UPDATE import_batches SET cleaning_summary='[]'::jsonb WHERE id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->db.update("UPDATE import_rows SET cleaning='{}'::jsonb WHERE batch_id=?",id)).as("the row log is an array").isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- mapping services accept and validate the new settings -------------------------------------------------------------------
    @Test void studentMappingsAcceptTheNewKeysAndStillRejectUnknownOnes()throws Exception{
        var d=studentMappings.forUpload(null);as(admin);
        var ok=d.settings().deepCopy();((ObjectNode)ok).put("nativeDates","reject").put("sheet","Students");cleaning((ObjectNode)ok);
        assertThat(studentMappings.validate(d.mapping(),ok)).isEmpty();
        var created=studentMappings.create(new MappingInput("keys-"+n(),d.mapping(),ok,false,true,false));
        assertThat(created.settings().path("cleaning").path("nameParticles")).extracting(JsonNode::asText).containsExactly("de","la");assertThat(created.settings().path("nativeDates").asText()).isEqualTo("reject");
        for(String[] bad:new String[][]{{"surprise","1"},{"cleaning","{\"surprise\":true}"},{"cleaning","{\"padNumeric\":{\"lastName\":3}}"},{"nativeDates","\"maybe\""},{"cleaning","{\"blankValues\":[\"x\",\"X\"]}"}}){
            var s=d.settings().deepCopy();((ObjectNode)s).set(bad[0],json.readTree(bad[1]));
            assertThat(studentMappings.validate(d.mapping(),s)).as(bad[0]+bad[1]).isNotEmpty();
        }
        var old=d.settings().deepCopy();assertThat(studentMappings.validate(d.mapping(),old)).as("mappings stored before ICI2-39 stay valid").isEmpty();
        var formats=d.settings().deepCopy();((ObjectNode)formats).putArray("dateFormats").add("d MMM yyyy");
        assertThat(studentMappings.validate(d.mapping(),formats)).as("extended date tokens need the opt-in").isNotEmpty();
        ((ObjectNode)formats).putObject("cleaning").put("extendedDateFormats",true);assertThat(studentMappings.validate(d.mapping(),formats)).isEmpty();
        var collide=d.mapping().deepCopy();((ObjectNode)collide.with("valueMaps").with("sex")).put("N/A","Unknown");
        assertThat(studentMappings.validate(collide,ok)).anyMatch(p->p.contains("also a key of a value map"));
    }
    @Test void gradeMappingsAcceptTheNewKeysAndStillRejectUnknownOnes()throws Exception{
        var d=gradeMappings.forUpload(null);as(admin);
        var ok=d.settings().deepCopy();((ObjectNode)ok).put("nativeDates","accept");var c=((ObjectNode)ok).putObject("cleaning");c.put("unicodeSpaces",true);c.putArray("blankValues").add("n/a");
        assertThat(gradeMappings.validate(d.mapping(),ok)).isEmpty();
        var created=gradeMappings.create(new MappingInput("gkeys-"+n(),d.mapping(),ok,false,true,false));assertThat(created.settings().path("cleaning").path("unicodeSpaces").asBoolean()).isTrue();
        var surprise=d.settings().deepCopy();((ObjectNode)surprise).put("surprise",true);assertThat(gradeMappings.validate(d.mapping(),surprise)).anyMatch(p->p.contains("Unknown setting 'surprise'"));
        var unknown=d.settings().deepCopy();((ObjectNode)unknown).putObject("cleaning").put("surprise",1);assertThat(gradeMappings.validate(d.mapping(),unknown)).anyMatch(p->p.contains("Unknown cleaning key"));
        var placeholderIsMark=d.mapping().deepCopy();((ObjectNode)placeholderIsMark.with("valueMaps").with("mark")).put("N/A","INC");
        assertThat(gradeMappings.validate(placeholderIsMark,ok)).as("a token cannot be both a placeholder and a mark").anyMatch(p->p.contains("also a key of a value map"));
        var fieldOfOtherType=d.settings().deepCopy();((ObjectNode)fieldOfOtherType).putObject("cleaning").putObject("fieldBlankValues").putArray("birthDate").add("x");
        assertThat(gradeMappings.validate(d.mapping(),fieldOfOtherType)).as("the grade matrix has no birthDate field").anyMatch(p->p.contains("unknown field"));
    }

    // ---- student staging with the opt-in rules ------------------------------------------------------------------------------------
    record Staged(String n,long batch,long mapping){}
    Staged stageWithCleaning()throws Exception{
        String n=n(),u=ImportFixtures.letters(n).toUpperCase(),l=ImportFixtures.letters(n);long mapping=studentMapping(m->{},CleaningFoundationTest::cleaning);
        String csv=studentCsv(
            "SYN-"+n+"-01,99 "+n+" 01,DE LA CRUZ"+u+",JUAN​ CARLOS"+u+",,3/14/2009,,N/A,qvr,stem",
            "SYN-"+n+"-02,99"+n+"02,  Beta  "+l+" ,Ben,,2009-11-02,,Synthetic Junior High,ESC,ABM");
        return new Staged(n,students.stage(iligan,"cleaning.csv",text(csv),mapping).batchId(),mapping);
    }
    @Test void optInRulesCleanTheRowAndLogOnlyNonTrivialChangesWithRawAndCleanedValues()throws Exception{
        var s=stageWithCleaning();String l=ImportFixtures.letters(s.n());
        var row=db.queryForMap("SELECT * FROM import_rows WHERE batch_id=? AND row_number=2",s.batch());
        assertThat(row.get("status")).as(db.queryForList("SELECT error_code,detail FROM import_exceptions WHERE batch_id=?",s.batch()).toString()).isEqualTo("valid");
        var v=tree(row.get("normalized_values"));
        assertThat(v.path("lastName").asText()).isEqualTo("de la Cruz"+l);assertThat(v.path("firstName").asText()).isEqualTo("Juan Carlos"+l);
        assertThat(v.path("lrn").asText()).isEqualTo("99"+s.n()+"01");assertThat(v.path("birthDate").asText()).isEqualTo("2009-03-14");assertThat(v.path("previousSchool").isNull()).isTrue();
        assertThat(v.path("voucherCode").asText()).isEqualTo("QVR");
        assertThat(tree(row.get("raw_values")).path("Last Name").asText()).as("the raw cell is kept untouched").isEqualTo("DE LA CRUZ"+l.toUpperCase());
        var log=tree(row.get("cleaning"));
        assertThat(log).extracting(e->e.path("rule").asText()).containsExactly("lrn_strip_separators","name_case","name_particles","name_case","date_parsed:M/d/yyyy","placeholder_to_blank");
        assertThat(log.get(0).path("before").asText()).isEqualTo("99 "+s.n()+" 01");assertThat(log.get(0).path("after").asText()).isEqualTo("99"+s.n()+"01");assertThat(log.get(0).path("column").asText()).isEqualTo("LRN");assertThat(log.get(0).path("field").asText()).isEqualTo("lrn");
        assertThat(log.get(1).path("before").asText()).isEqualTo("DE LA CRUZ"+l.toUpperCase());assertThat(log.get(1).path("after").asText()).isEqualTo("De La Cruz"+l);
        assertThat(log.get(2).path("after").asText()).isEqualTo("de la Cruz"+l);
        assertThat(log.get(4).path("before").asText()).isEqualTo("3/14/2009");assertThat(log.get(4).path("after").asText()).isEqualTo("2009-03-14");assertThat(log.get(4).path("column").asText()).isEqualTo("Birth Date");
        assertThat(log.get(5).path("before").asText()).isEqualTo("N/A");assertThat(log.get(5).path("after").isNull()).isTrue();
        assertThat(log).as("trivial changes (NBSP, zero-width, spaces) are never logged per cell").noneMatch(e->CleaningLog.TRIVIAL.contains(e.path("rule").asText()));
        assertThat(db.queryForList("SELECT error_code||':'||field_name FROM import_exceptions WHERE batch_id=? AND row_number=2 ORDER BY id",String.class,s.batch())).containsExactly("VALUE_CLEANED:previousSchool");
        assertThat(db.queryForObject("SELECT severity FROM import_exceptions WHERE batch_id=? AND error_code='VALUE_CLEANED'",String.class,s.batch())).isEqualTo("warning");
        assertThat(db.queryForObject("SELECT count(*) FROM import_exceptions WHERE batch_id=? AND (detail LIKE '%N/A%' OR detail LIKE '%Cruz%' OR detail LIKE '%3/14%')",Integer.class,s.batch())).as("messages never echo values").isZero();
        assertThat(db.queryForObject("SELECT cleaning IS NULL FROM import_rows WHERE batch_id=? AND row_number=3",Boolean.class,s.batch())).as("a row with only trivial changes has no log").isTrue();
        assertThat(db.queryForObject("SELECT normalized_values->>'lastName' FROM import_rows WHERE batch_id=? AND row_number=3",String.class,s.batch())).as("whitespace is cleaned, a mixed-case name is left as written").isEqualTo("Beta "+l);
    }
    @Test void theBatchCountsEveryRuleIncludingTrivialOnesAndKeepsTheSourceFormat()throws Exception{
        var s=stageWithCleaning();
        var summary=tree(db.queryForObject("SELECT cleaning_summary::text FROM import_batches WHERE id=?",String.class,s.batch()));
        assertThat(summary.path("unicode_spaces").asInt()).isEqualTo(1);assertThat(summary.path("strip_invisible").asInt()).isEqualTo(1);
        assertThat(summary.path("lrn_strip_separators").asInt()).isEqualTo(1);assertThat(summary.path("name_case").asInt()).as("two names re-cased in row 2 (row 3 is mixed case)").isEqualTo(2);
        assertThat(summary.path("name_particles").asInt()).isEqualTo(1);assertThat(summary.path("placeholder_to_blank").asInt()).isEqualTo(1);assertThat(summary.path("date_parsed:M/d/yyyy").asInt()).isEqualTo(1);
        assertThat(summary.path("collapse_spaces").asInt()).as("trivial whitespace changes are counted").isPositive();assertThat(summary.path("trim").asInt()).isPositive();
        var detail=studentBatches.detail(s.batch());
        assertThat(detail).containsEntry("sourceFormat","csv");
        @SuppressWarnings("unchecked") var counts=(List<Map<String,Object>>)detail.get("cleaningSummary");
        assertThat(counts).anySatisfy(c->{assertThat(c).containsEntry("rule","placeholder_to_blank").containsEntry("count",1L).containsEntry("trivial",false);});
        assertThat(counts).anySatisfy(c->{assertThat(c).containsEntry("rule","unicode_spaces").containsEntry("trivial",true);});
        @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)studentBatches.rows(s.batch(),null,0,25).get("items");
        assertThat(items).hasSize(2);assertThat(items.get(0)).containsKey("cleaning");assertThat(items.get(0).get("cleaning")).isInstanceOf(JsonNode.class);assertThat(((JsonNode)items.get(0).get("cleaning")).size()).isEqualTo(6);
        assertThat(items.get(1).get("cleaning")).as("no non-trivial change, no log").isNull();
        @SuppressWarnings("unchecked") var listed=(List<Map<String,Object>>)studentBatches.list(iligan,null,0,100).get("items");
        assertThat(listed).anySatisfy(b->assertThat(b).containsEntry("id",s.batch()).containsEntry("sourceFormat","csv"));
    }
    @Test void withTheDefaultMappingNothingNonTrivialIsEverLoggedOrWarned()throws Exception{
        String n=n();var r=students.stage(iligan,"mixed.csv",ImportFixtures.fixture("synthetic-mixed.csv",n),null);
        assertThat(db.queryForObject("SELECT count(*) FROM import_rows WHERE batch_id=? AND cleaning IS NOT NULL",Integer.class,r.batchId())).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM import_exceptions WHERE batch_id=? AND error_code IN ('VALUE_CLEANED','VALUE_PADDED','FORMULA_CACHED')",Integer.class,r.batchId())).isZero();
        var summary=tree(db.queryForObject("SELECT cleaning_summary::text FROM import_batches WHERE id=?",String.class,r.batchId()));
        assertThat(summary).isNotNull();summary.fieldNames().forEachRemaining(rule->assertThat(CleaningLog.TRIVIAL).contains(rule));
        assertThat(summary.has("collapse_spaces")&&summary.has("trim")).as("the fixture has doubled and edge spaces").isTrue();
    }

    // ---- redaction: another campus's personal data is never retained --------------------------------------------------------------
    @Test void cleaningEntriesOfARowForAnotherCampusAreNotRetainedLikeItsRawValues()throws Exception{
        String n=n(),l=ImportFixtures.letters(n);long mapping=studentMapping(m->{},CleaningFoundationTest::cleaning);
        String csv="Student No,LRN,Last Name,First Name,Middle Name,Birth Date,Sex,Previous School,Voucher,Strand,Campus\n"
            +"SYN-"+n+"-01,99"+n+"01,Own"+l+",Ana,,2009-03-14,,N/A,QVR,STEM,ILIGAN\nSYN-"+n+"-02,99"+n+"02,Other"+l+",Ben,,2009-03-15,,N/A,QVR,STEM,BUGO\n";
        long batch=students.stage(iligan,"campus.csv",text(csv),mapping).batchId();
        assertThat(db.queryForObject("SELECT cleaning IS NOT NULL FROM import_rows WHERE batch_id=? AND row_number=2",Boolean.class,batch)).isTrue();
        assertThat(db.queryForObject("SELECT raw_values::text FROM import_rows WHERE batch_id=? AND row_number=3",String.class,batch)).isEqualTo("{}");
        assertThat(db.queryForObject("SELECT cleaning IS NULL FROM import_rows WHERE batch_id=? AND row_number=3",Boolean.class,batch)).as("redacted like raw_values").isTrue();
        assertThat(bugo).isNotEqualTo(iligan);
    }

    // ---- grade matrix ---------------------------------------------------------------------------------------------------------------
    record Setup(String n,long s1){}
    Setup gradeSetup(){
        String n=n();long year=db.queryForObject("INSERT INTO academic_years(label,starts_on,ends_on) VALUES (?,'2018-06-01','2019-03-31') RETURNING id",Long.class,"SY"+n);
        long s1=db.queryForObject("INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES (?,'S1','S1','2018-06-01','2018-10-31') RETURNING id",Long.class,year);
        db.update("INSERT INTO grading_periods(term_id,code,sequence) VALUES (?,'Q1',1)",s1);db.update("INSERT INTO grading_periods(term_id,code,sequence) VALUES (?,'Q2',2)",s1);
        db.update("INSERT INTO subjects(code,name) VALUES (?,'Synthetic mathematics')","SYNMATH"+n);
        for(int i=1;i<=3;i++)db.update("INSERT INTO students(student_number,first_name,last_name,birth_date,campus_id) VALUES (?,?,?,'2009-01-01',?)","SYN-"+n+"-0"+i,"Stu"+i,"Syn"+i+ImportFixtures.letters(n),iligan);
        return new Setup(n,s1);
    }
    @Test void gradePlaceholdersAreBlankNeverMarksAndOnlyWhenConfiguredAndTheReadsShowTheLog()throws Exception{
        var s=gradeSetup();String col1="SYNMATH"+s.n()+" - Q1",col2="SYNMATH"+s.n()+" - Q2";
        String csv="Student No,Grade Level,"+col1+","+col2+"\nSYN-"+s.n()+"-01,Grade 7,N/A,90\nSYN-"+s.n()+"-02,Grade 7, 88.5 ,n/a\nSYN-"+s.n()+"-03,Grade 7,INC,\n";
        var ctx=new UploadContext(null,s.s1(),"Grade 7",null);
        var plain=grades.stageGradeMatrix(iligan,ctx,"plain.csv",text(csv),null);
        assertThat(db.queryForList("SELECT error_code FROM import_exceptions WHERE batch_id=? AND severity='error' ORDER BY row_number",String.class,plain.batchId())).as("without the opt-in a placeholder is an unmapped mark").containsExactly("UNMAPPED_MARK","UNMAPPED_MARK");

        var base=gradeMappings.forUpload(null);ObjectNode settings=base.settings().deepCopy();var c=settings.putObject("cleaning");c.putArray("blankValues").add("n/a");c.put("unicodeSpaces",true);
        as(admin);long mapping=gradeMappings.create(new MappingInput("Grades "+UUID.randomUUID(),base.mapping().deepCopy(),settings,false,true,false)).id();as(registrar);
        var r=grades.stageGradeMatrix(iligan,ctx,"cleaned.csv",text(csv+"\n"),mapping);
        assertThat(r.valid()).isEqualTo(3);assertThat(r.invalid()).isZero();
        var first=tree(db.queryForObject("SELECT normalized_values::text FROM import_rows WHERE batch_id=? AND row_number=2",String.class,r.batchId()));
        assertThat(first.path("cells")).hasSize(1);assertThat(first.path("cells").get(0).path("value").asText()).isEqualTo("90.00");assertThat(first.path("blankCells").asInt()).as("a placeholder is blank, never INC or 0").isEqualTo(1);
        assertThat(tree(db.queryForObject("SELECT normalized_values::text FROM import_rows WHERE batch_id=? AND row_number=3",String.class,r.batchId())).path("cells").get(0).path("value").asText()).isEqualTo("88.50");
        var log=tree(db.queryForObject("SELECT cleaning::text FROM import_rows WHERE batch_id=? AND row_number=2",String.class,r.batchId()));
        assertThat(log).singleElement().satisfies(e->{assertThat(e.path("rule").asText()).isEqualTo("placeholder_to_blank");assertThat(e.path("column").asText()).isEqualTo(col1);assertThat(e.path("before").asText()).isEqualTo("N/A");assertThat(e.path("after").isNull()).isTrue();});
        assertThat(db.queryForList("SELECT error_code||':'||field_name FROM import_exceptions WHERE batch_id=? AND row_number=2",String.class,r.batchId())).containsExactly("VALUE_CLEANED:"+col1);
        assertThat(db.queryForObject("SELECT count(*) FROM import_exceptions WHERE batch_id=? AND detail LIKE '%N/A%'",Integer.class,r.batchId())).isZero();

        var detail=gradeBatches.detail(r.batchId());
        assertThat(detail).containsEntry("sourceFormat","csv");
        @SuppressWarnings("unchecked") var counts=(List<Map<String,Object>>)detail.get("cleaningSummary");
        assertThat(counts).anySatisfy(x->assertThat(x).containsEntry("rule","placeholder_to_blank").containsEntry("count",2L).containsEntry("trivial",false));
        assertThat(counts).anySatisfy(x->assertThat(x).containsEntry("rule","trim").containsEntry("trivial",true));
        @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)gradeBatches.rows(r.batchId(),null,0,10).get("items");
        assertThat(items.get(0).get("cleaning")).isNotNull();assertThat(items.get(2).get("cleaning")).isNull();
    }

    // ---- audit privacy --------------------------------------------------------------------------------------------------------------
    @Test void theCleaningLogNeverReachesTheAuditTrailButTheBatchCountsDo()throws Exception{
        var s=stageWithCleaning();String l=ImportFixtures.letters(s.n());
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='import_rows' AND operation='INSERT' AND actor_id=? AND new_data->>'batch_id'=?",Integer.class,registrar,Long.toString(s.batch()))).as("the row audit still runs").isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='import_rows' AND (jsonb_exists(new_data,'cleaning') OR jsonb_exists(old_data,'cleaning') OR jsonb_exists(new_data,'raw_values') OR jsonb_exists(new_data,'normalized_values'))",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='import_rows' AND (new_data::text LIKE '%placeholder_to_blank%' OR new_data::text LIKE '%Cruz"+l+"%' )",Integer.class)).isZero();
        long row=db.queryForObject("SELECT id FROM import_rows WHERE batch_id=? AND row_number=2",Long.class,s.batch());
        db.update("UPDATE import_rows SET cleaning='[{\"rule\":\"x\",\"before\":\"SECRET-CLEANING-VALUE\",\"after\":\"y\"}]'::jsonb WHERE id=?",row);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='import_rows' AND operation='UPDATE' AND record_id::jsonb->>'id'=? AND (old_data::text LIKE '%SECRET-CLEANING-VALUE%' OR new_data::text LIKE '%SECRET-CLEANING-VALUE%' OR jsonb_exists(new_data,'cleaning'))",Integer.class,Long.toString(row))).as("an UPDATE of the log is audited without it").isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='import_rows' AND operation='UPDATE' AND record_id::jsonb->>'id'=?",Integer.class,Long.toString(row))).isEqualTo(1);
        var batchAudit=db.queryForList("SELECT new_data::text FROM audit_logs WHERE table_name='import_batches' AND operation='INSERT' AND new_data->>'id'=?",String.class,Long.toString(s.batch()));
        assertThat(batchAudit).singleElement().satisfies(t->{assertThat(t).contains("cleaning_summary","placeholder_to_blank").doesNotContain("Cruz"+l).doesNotContain("SYN-"+s.n());});
    }
}
