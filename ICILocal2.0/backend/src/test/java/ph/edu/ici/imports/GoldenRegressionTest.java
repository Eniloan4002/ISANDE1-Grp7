package ph.edu.ici.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
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

/**
 * ICI2-39 phase 2 golden regression. Every ICI2-37/38 synthetic fixture is staged with DEFAULT settings and its complete outcome
 * (row statuses, raw and normalized values, duplicate evidence, every exception code/field/severity/detail, batch counts and file-level
 * errors) is compared with a snapshot recorded from the code as it was BEFORE the phase 2 refactor. Database ids are replaced by symbolic
 * codes so snapshots are stable. To re-record on purpose: -Dgolden.record=true (only when a behavior change is intended and reviewed).
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class GoldenRegressionTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->PostgresSupport.DATABASE.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");r.add("ici.auth.secret",()->PostgresSupport.SECRET);r.add("ici.bootstrap.enabled",()->false);
    }
    static final boolean RECORD=Boolean.getBoolean("golden.record");
    @Autowired JdbcTemplate db;@Autowired ObjectMapper mapper;
    @Autowired ImportValidationService students;@Autowired ImportMappingService studentMappings;
    @Autowired GradeMatrixValidationService grades;@Autowired GradeMatrixMappingService gradeMappings;
    long iligan,registrar,admin;
    ObjectMapper canonical;

    @BeforeEach void users(){
        canonical=mapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY,true).enable(SerializationFeature.INDENT_OUTPUT);
        iligan=db.queryForObject("SELECT id FROM campuses WHERE code='ILIGAN'",Long.class);
        registrar=user("ROLE_REGISTRAR",iligan);admin=user("ROLE_ADMIN",0);as(registrar);
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    long user(String role,long campus){
        String name="golden-"+UUID.randomUUID();
        long id=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES (?,?,?,'unused') RETURNING id",Long.class,name,name+"@example.invalid","Synthetic staff");
        if(campus>0)db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,?,?)",id,role,campus);else db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) SELECT ?,?,id FROM campuses",id,role);
        return id;
    }
    void as(long id){var identity=new StaffIdentity(id,"u"+id,"Synthetic staff",List.of(),UUID.randomUUID(),Instant.now().plusSeconds(600),false);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(identity,null,List.of()));}

    // ---- snapshot helpers ----------------------------------------------------------------------------------------------
    void check(String name,Object snapshot)throws Exception{
        String actual=canonical.writeValueAsString(snapshot)+"\n";
        Path file=Path.of("src/test/resources/import/golden/"+name+".json");
        if(RECORD){Files.createDirectories(file.getParent());Files.writeString(file,actual,StandardCharsets.UTF_8);return;}
        String expected=new String(new ClassPathResource("import/golden/"+name+".json").getContentAsByteArray(),StandardCharsets.UTF_8);
        assertThat(actual).as("golden snapshot "+name).isEqualTo(expected);
    }
    String symbol(String table,String column,Object id){
        if(id==null)return null;var rows=db.queryForList("SELECT "+column+" FROM "+table+" WHERE id=?",String.class,((Number)id).longValue());return rows.isEmpty()?"#missing":rows.getFirst();
    }
    /** Replaces database ids by stable codes and drops nothing else. */
    JsonNode scrub(JsonNode node,String key){
        if(node==null||node.isNull())return node;
        if(node.isObject()){ObjectNode out=mapper.createObjectNode();node.fields().forEachRemaining(e->out.set(e.getKey(),scrub(e.getValue(),e.getKey())));return out;}
        if(node.isArray()){ArrayNode out=mapper.createArrayNode();node.forEach(e->out.add(scrub(e,key)));return out;}
        if(node.isNumber()&&key!=null){
            String s=switch(key){
                case "studentId","id"->symbol("students","student_number",node.asLong());
                case "campusId"->symbol("campuses","code",node.asLong());case "programId"->symbol("programs","code",node.asLong());
                case "subjectId"->symbol("subjects","code",node.asLong());case "periodId"->symbol("grading_periods","code",node.asLong());
                case "termId"->symbol("academic_terms","code",node.asLong());case "academicYearId"->symbol("academic_years","label",node.asLong());
                default->null;
            };
            if(s!=null)return mapper.getNodeFactory().textNode(s);
        }
        return node;
    }
    Map<String,Object> batchSnapshot(long batch)throws Exception{
        Map<String,Object> out=new LinkedHashMap<>();
        var b=db.queryForMap("SELECT status,source_count,valid_count,invalid_count,flagged_count,skipped_count,imported_count FROM import_batches WHERE id=?",batch);
        out.put("batch",b);List<Object> rows=new ArrayList<>();
        for(var r:db.queryForList("SELECT * FROM import_rows WHERE batch_id=? ORDER BY row_number",batch)){
            Map<String,Object> m=new LinkedHashMap<>();m.put("row",r.get("row_number"));m.put("status",r.get("status"));
            m.put("raw",tree(r.get("raw_values")));m.put("normalized",scrub(tree(r.get("normalized_values")),null));m.put("evidence",scrub(tree(r.get("duplicate_evidence")),null));
            m.put("messages",db.queryForList("SELECT error_code,field_name,severity,detail FROM import_exceptions WHERE import_row_id=? ORDER BY id",((Number)r.get("id")).longValue()));rows.add(m);
        }
        out.put("rows",rows);
        out.put("batchMessages",db.queryForList("SELECT error_code,field_name,severity,detail FROM import_exceptions WHERE batch_id=? AND row_number IS NULL ORDER BY id",batch));
        return out;
    }
    JsonNode tree(Object value)throws Exception{return value==null?null:mapper.readTree(value.toString());}
    Map<String,Object> failure(ImportFileException e){
        Map<String,Object> m=new LinkedHashMap<>();m.put("error",e.error().name());m.put("message",e.getMessage());
        m.put("problems",e.problems().stream().map(p->List.of(p.code(),p.header(),p.detail())).toList());
        if(e.batchId()!=null)m.put("batchMessages",db.queryForList("SELECT error_code,field_name,severity,detail FROM import_exceptions WHERE batch_id=? ORDER BY id",e.batchId()));
        return m;
    }
    byte[] fixture(String name,String n){return ImportFixtures.fixture(name,n);}

    // ---- ICI2-37 student fixtures -------------------------------------------------------------------------------------
    long studentMapping(Consumer<ObjectNode> settingsEdit){
        var d=studentMappings.forUpload(null);ObjectNode settings=d.settings().deepCopy();settingsEdit.accept(settings);
        as(admin);try{return studentMappings.create(new MappingInput("Golden "+UUID.randomUUID(),d.mapping().deepCopy(),settings,false,true,false)).id();}finally{as(registrar);}
    }
    Map<String,Object> student(String file,String n,Long mapping)throws Exception{
        try{return batchSnapshot(students.stage(iligan,file,fixture(file,n),mapping).batchId());}
        catch(ImportFileException e){return failure(e);}
    }
    @Test void studentFixturesWithDefaultSettings()throws Exception{
        Map<String,Object> all=new LinkedHashMap<>();
        all.put("basic",student("synthetic-basic.csv","71000001",null));
        all.put("campus-column",student("synthetic-campus-column.csv","71000002",null));
        all.put("duplicates",student("synthetic-duplicates.csv","71000003",null));
        all.put("mixed-default-case",student("synthetic-mixed.csv","71000004",null));
        all.put("missing-header",student("synthetic-missing-header.csv","71000005",null));
        all.put("ambiguous-header",student("synthetic-ambiguous-header.csv","71000006",null));
        all.put("windows-1252-read-as-utf8",student("synthetic-windows-1252.csv","71000007",null));
        check("students-default",all);
    }
    @Test void studentFixturesWithNameCaseAndWindows1252Mappings()throws Exception{
        Map<String,Object> all=new LinkedHashMap<>();
        all.put("mixed-name-case",student("synthetic-mixed.csv","71000011",studentMapping(s->s.put("normalizeNameCase",true))));
        all.put("windows-1252",student("synthetic-windows-1252.csv","71000012",studentMapping(s->s.put("encoding","windows-1252"))));
        check("students-mappings",all);
    }

    // ---- ICI2-38 grade matrix fixtures --------------------------------------------------------------------------------
    record Setup(String n,long year,long s1,long s2){}
    Setup setup(String n){
        long year=db.queryForObject("INSERT INTO academic_years(label,starts_on,ends_on) VALUES (?,'2018-06-01','2019-03-31') RETURNING id",Long.class,"SY"+n);
        long s1=db.queryForObject("INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES (?,'S1','S1','2018-06-01','2018-10-31') RETURNING id",Long.class,year);
        long s2=db.queryForObject("INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES (?,'S2','S2','2018-11-01','2019-03-31') RETURNING id",Long.class,year);
        for(long t:new long[]{s1,s2}){db.update("INSERT INTO grading_periods(term_id,code,sequence) VALUES (?,'Q1',1)",t);db.update("INSERT INTO grading_periods(term_id,code,sequence) VALUES (?,'Q2',2)",t);}
        db.update("INSERT INTO subjects(code,name) VALUES (?,'Synthetic mathematics')","SYNMATH"+n);db.update("INSERT INTO subjects(code,name) VALUES (?,'Synthetic science')","SYNSCI"+n);
        return new Setup(n,year,s1,s2);
    }
    long student(String number,String lrn,String first,String last,boolean active){
        return db.queryForObject("INSERT INTO students(student_number,lrn,first_name,last_name,birth_date,campus_id,active) VALUES (?,?,?,?,'2009-01-01',?,?) RETURNING id",Long.class,number,lrn,first,last,iligan,active);
    }
    void roster(Setup s,int count){for(int i=1;i<=count;i++){String id=String.format("%02d",i);student("SYN-"+s.n()+"-"+id,null,"Stu"+id,"Syn"+id+ImportFixtures.letters(s.n()),true);}}
    Map<String,Object> grade(Setup s,String file,UploadContext ctx,Long mapping)throws Exception{
        try{return batchSnapshot(grades.stageGradeMatrix(iligan,ctx,file,fixture(file,s.n()),mapping).batchId());}
        catch(ImportFileException e){return failure(e);}
    }
    UploadContext term(Setup s){return new UploadContext(null,s.s1(),"Grade 7",null);}

    @Test void gradeFixturesWithDefaultSettings()throws Exception{
        Map<String,Object> all=new LinkedHashMap<>();
        var a=setup("72000001");String l=ImportFixtures.letters(a.n());
        student("SYN-"+a.n()+"-01",null,"Ana","Alphaone"+l,true);student("SYN-"+a.n()+"-02","99"+a.n()+"02","Ben","Betatwo"+l,true);student("SYN-"+a.n()+"-03",null,"Cara","Gammathree"+l,true);
        all.put("basic",grade(a,"synthetic-grades-basic.csv",term(a),null));

        var c=setup("72000002");roster(c,12);
        all.put("cells",grade(c,"synthetic-grades-cells.csv",term(c),null));

        var t=setup("72000003");roster(t,3);
        all.put("terms",grade(t,"synthetic-grades-terms.csv",new UploadContext(t.year(),null,"Grade 7",null),null));

        var st=setup("72000004");String n=st.n();String ls=ImportFixtures.letters(n);
        student("SYN-"+n+"-01",null,"Ana","Alphaone"+ls,true);student("SYN-"+n+"-02","99"+n+"02","Ben","Betatwo"+ls,true);student("SYN-"+n+"-03","99"+n+"03","Cara","Gammathree"+ls,true);
        student("SYN-"+n+"-04",null,"Dan","Deltafour"+ls,true);student("SYN-"+n+"-05","99"+n+"05","Eve","Deltafive"+ls,true);student("SYN-"+n+"-06","99"+n+"06","Eli","Epsilonsix"+ls,true);
        student("SYN-"+n+"-07",null,"Fay","Etaseven"+ls,true);student("SYN-"+n+"-08",null,"Gus","Thetaeight"+ls,false);
        all.put("students",grade(st,"synthetic-grades-students.csv",term(st),null));

        String[][] failures={{"ambiguous-header","synthetic-grades-ambiguous-header.csv"},{"no-identifier","synthetic-grades-no-identifier.csv"},{"unclassified","synthetic-grades-unclassified.csv"},
                             {"unknown-period","synthetic-grades-unknown-period.csv"},{"unknown-subject","synthetic-grades-unknown-subject.csv"}};
        for(int i=0;i<failures.length;i++){
            var f=setup(String.format("7200%04d",10+i));student("SYN-"+f.n()+"-01",null,"Ana","Alphaone"+ImportFixtures.letters(f.n()),true);
            all.put(failures[i][0],grade(f,failures[i][1],term(f),null));
        }
        check("grades-default",all);
    }
    @Test void gradeLegacyLabelsWithAMappingThatOnlyUsesPreExistingSettings()throws Exception{
        var s=setup("72000100");student("SYN-"+s.n()+"-01",null,"Ana","Alphaone"+ImportFixtures.letters(s.n()),true);
        // the mark target is lower-case on purpose: the service upper-cases it (the snapshot still shows INC), and the stored mapping text then never
        // matches the literal "INC" that GradeMatrixApiTest looks for in the shared audit log.
        var base=gradeMappings.forUpload(null);ObjectNode m=base.mapping().deepCopy();
        m.with("fields").with("schoolYear").withArray("aliases").add("ay");
        ((ObjectNode)m.with("valueMaps").with("subject")).put("Mathematics 7","SYNMATH"+s.n()).put("Science 7","SYNSCI"+s.n());
        ((ObjectNode)m.with("valueMaps").with("period")).put("1st Quarter","Q1");((ObjectNode)m.with("valueMaps").with("term")).put("First Semester","S1");
        ((ObjectNode)m.with("valueMaps").with("schoolYear")).put("AY 2018-19","SY"+s.n());((ObjectNode)m.with("valueMaps").with("mark")).put("Inc.","inc");
        m.with("grid").putArray("reportedFinalLabels").add("Final Grade");
        as(admin);long id;try{id=gradeMappings.create(new MappingInput("Golden "+UUID.randomUUID(),m,base.settings().deepCopy(),false,true,false)).id();}finally{as(registrar);}
        check("grades-legacy-labels",grade(s,"synthetic-grades-legacy-labels.csv",new UploadContext(null,null,null,null),id));
    }
}
