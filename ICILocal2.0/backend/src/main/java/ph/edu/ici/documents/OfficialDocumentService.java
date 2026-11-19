package ph.edu.ici.documents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.*;
import static ph.edu.ici.documents.OfficialDocumentController.*;

/** Registrar-configured academic record formatter; no national template or approval is presumed. */
@Service
public class OfficialDocumentService {
    private final JdbcTemplate db; private final AccessPolicy access; private final ObjectMapper json;
    public OfficialDocumentService(JdbcTemplate db,AccessPolicy access,ObjectMapper json){this.db=db;this.access=access;this.json=json;}
    private ResponseStatusException bad(String s){return new ResponseStatusException(HttpStatus.BAD_REQUEST,s);}
    private ResponseStatusException conflict(String s){return new ResponseStatusException(HttpStatus.CONFLICT,s);}
    private ResponseStatusException absent(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Record not found.");}
    private long num(Map<String,Object> m,String k){return ((Number)m.get(k)).longValue();}
    private long actor(){return ((StaffIdentity)SecurityContextHolder.getContext().getAuthentication().getPrincipal()).userId();}
    private void audit(){db.queryForObject("SELECT set_config('app.actor_id',?,true)",String.class,Long.toString(actor()));}
    private void campus(long id){if(!access.campuses("documents.manage").contains(id))throw absent();}
    private String text(String value,String field,int max){
        if(value==null||value.isBlank()||value.length()>max||value.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'))throw bad(field+" must contain 1 to "+max+" printable characters.");
        return value.trim();
    }
    private void version(Integer version){if(version==null||version<0)throw bad("A nonnegative record version is required.");}
    private String type(String type){if(!Set.of("FORM137","TOR").contains(type==null?"":type))throw bad("Document type must be FORM137 or TOR.");return type;}
    private Map<String,Object> policy(long id,boolean lock){
        var rows=db.queryForList("SELECT * FROM official_document_policies WHERE id=?"+(lock?" FOR UPDATE":""),id);
        if(rows.isEmpty())throw absent();var row=rows.getFirst();campus(num(row,"campus_id"));return row;
    }
    public List<Map<String,Object>> policies(){
        var rows=db.queryForList("SELECT * FROM official_document_policies ORDER BY campus_id,program_id,document_type,id");
        return rows.stream().filter(r->access.campuses("documents.manage").contains(num(r,"campus_id"))).toList();
    }
    @Transactional public Map<String,Object> savePolicy(Long id,PolicyInput input){
        if(input.campusId()==null||input.programId()==null||input.soRequired()==null||input.soMaxLength()==null||input.soMaxLength()<1||input.soMaxLength()>120)throw bad("Campus, program, SO applicability and maximum length (1 to 120) are required.");
        campus(input.campusId());type(input.documentType());
        String name=text(input.institutionName(),"Institution name",200),address=text(input.institutionAddress(),"Institution address",500),
                signatories=text(input.signatories(),"Signatories",1000),reference=text(input.templateReference(),"Template reference",300);
        String prefix=input.soPrefix()==null?"":input.soPrefix().trim();
        if(prefix.length()>60||prefix.length()>input.soMaxLength()||prefix.codePoints().anyMatch(Character::isISOControl))throw bad("SO prefix must be printable and fit the configured SO length.");
        audit();long staff=actor();
        if(id==null){
            id=db.queryForObject("""
                INSERT INTO official_document_policies(campus_id,program_id,document_type,institution_name,institution_address,signatories,
                    template_reference,so_required,so_prefix,so_max_length,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id
                """,Long.class,input.campusId(),input.programId(),input.documentType(),name,address,signatories,reference,input.soRequired(),prefix,input.soMaxLength(),staff,staff);
        }else{
            version(input.version());var old=policy(id,true);
            if(num(old,"campus_id")!=input.campusId()||num(old,"program_id")!=input.programId()||!old.get("document_type").equals(input.documentType()))throw bad("Policy scope cannot change; create another policy for a different scope.");
            if(db.update("""
                UPDATE official_document_policies SET institution_name=?,institution_address=?,signatories=?,template_reference=?,
                  so_required=?,so_prefix=?,so_max_length=?,version=version+1,approval_reference=NULL,approved_by=NULL,approved_at=NULL,updated_by=?,updated_at=current_timestamp
                WHERE id=? AND version=?
                """,name,address,signatories,reference,input.soRequired(),prefix,input.soMaxLength(),staff,id,input.version())!=1)throw conflict("Policy changed. Reload before saving.");
        }
        return policy(id,false);
    }
    @Transactional public Map<String,Object> approve(long id,Approval input){
        version(input.version());String reference=text(input.approvalReference(),"Registrar approval reference",300);var row=policy(id,true);audit();
        if(num(row,"version")!=input.version())throw conflict("Policy changed. Reload before approval.");
        if(row.get("approved_at")!=null)throw conflict("This policy is already approved.");
        db.update("UPDATE official_document_policies SET approval_reference=?,approved_by=?,approved_at=current_timestamp,updated_by=?,updated_at=current_timestamp,version=version+1 WHERE id=?",reference,actor(),actor(),id);
        return policy(id,false);
    }
    private Map<String,Object> enrollment(long id){
        var rows=db.queryForList("SELECT * FROM enrollments WHERE id=?",id);if(rows.isEmpty())throw absent();var row=rows.getFirst();campus(num(row,"campus_id"));return row;
    }
    public Map<String,Object> specialOrder(long enrollmentId){
        enrollment(enrollmentId);return db.queryForList("SELECT * FROM student_special_orders WHERE enrollment_id=?",enrollmentId).stream().findFirst().orElse(Map.of("enrollment_id",enrollmentId,"version",-1));
    }
    @Transactional public Map<String,Object> saveSpecialOrder(long enrollmentId,SpecialOrder input){
        var e=enrollment(enrollmentId);String number=text(input.number(),"SO number",120),authority=text(input.issuingAuthority(),"Issuing authority",200);
        if(number.codePoints().anyMatch(Character::isISOControl))throw bad("SO number must be a single printable line.");
        if(input.version()==null||input.version()<-1||input.issuedOn()==null||input.issuedOn().isAfter(LocalDate.now(ZoneOffset.UTC)))throw bad("Use version -1 for a new SO, an existing version for a correction, and a nonfuture issue date.");
        // Existing enrollment lock serializes simultaneous first insert/corrections; scope was checked above.
        db.queryForObject("SELECT id FROM enrollments WHERE id=? FOR UPDATE",Long.class,enrollmentId);audit();
        if(input.version()==-1){db.update("INSERT INTO student_special_orders(enrollment_id,number,issued_on,issuing_authority,updated_by,created_by) VALUES(?,?,?,?,?,?)",enrollmentId,number,input.issuedOn(),authority,actor(),actor());}
        else if(db.update("UPDATE student_special_orders SET number=?,issued_on=?,issuing_authority=?,version=version+1,updated_by=?,updated_at=current_timestamp WHERE enrollment_id=? AND version=?",number,input.issuedOn(),authority,actor(),enrollmentId,input.version())!=1)throw conflict("Special Order changed. Reload before saving.");
        return specialOrder(enrollmentId);
    }
    private void selection(Selection s){
        if(s.studentId()==null||s.policyId()==null||s.enrollmentIds()==null||s.enrollmentIds().isEmpty()||s.enrollmentIds().size()>50||s.enrollmentIds().stream().anyMatch(Objects::isNull)||new HashSet<>(s.enrollmentIds()).size()!=s.enrollmentIds().size())throw bad("Select a student, policy and 1 to 50 distinct enrollment records.");
        version(s.policyVersion());
    }
    private Map<String,Object> snapshot(Selection selection){
        selection(selection);var policy=policy(selection.policyId(),false);
        if(num(policy,"version")!=selection.policyVersion())throw conflict("Policy changed. Reload it before generating a document.");
        var studentRows=db.queryForList("SELECT id,student_number,lrn,first_name,middle_name,last_name,suffix,birth_date,sex FROM students WHERE id=?",selection.studentId());
        if(studentRows.isEmpty())throw absent();
        var reasons=new ArrayList<String>();var terms=new ArrayList<Map<String,Object>>();
        if(policy.get("approved_at")==null)reasons.add("TEMPLATE_POLICY_UNAPPROVED: Registrar approval of this template and SO policy is required.");
        int totalRows=0;
        for(long id:selection.enrollmentIds().stream().sorted().toList()){
            var e=enrollment(id);
            if(num(e,"student_id")!=selection.studentId()||num(e,"campus_id")!=num(policy,"campus_id")||num(e,"program_id")!=num(policy,"program_id"))throw absent();
            var term=new LinkedHashMap<String,Object>(db.queryForMap("SELECT t.id,t.name,t.starts_on,t.ends_on,y.label AS academic_year,p.code AS program,p.name AS program_name,c.name AS campus FROM academic_terms t JOIN academic_years y ON y.id=t.academic_year_id CROSS JOIN programs p CROSS JOIN campuses c WHERE t.id=? AND p.id=? AND c.id=?",e.get("term_id"),e.get("program_id"),e.get("campus_id")));
            term.put("enrollmentId",id);term.put("enrollmentStatus",e.get("status"));
            if("provisional".equals(e.get("status")))reasons.add("ENROLLMENT_NOT_CONFIRMED: Enrollment "+id+" is provisional.");
            var credentials=db.queryForList("SELECT rt.code,rt.name,coalesce(ar.status,'missing') AS status FROM requirement_types rt LEFT JOIN application_requirements ar ON ar.requirement_code=rt.code AND ar.application_id=? WHERE rt.mandatory ORDER BY rt.code",e.get("application_id"));
            if(credentials.isEmpty()||credentials.stream().anyMatch(r->!"submitted".equals(r.get("status"))))reasons.add("MISSING_CREDENTIALS: All mandatory credentials must be verified for enrollment "+id+".");
            term.put("credentials",credentials);
            long classes=db.queryForObject("SELECT count(*) FROM class_memberships WHERE enrollment_id=?",Long.class,id);
            long periods=db.queryForObject("SELECT count(*) FROM grading_periods WHERE term_id=?",Long.class,e.get("term_id"));
            if(classes==0||periods==0)reasons.add("INCOMPLETE_ACADEMIC_RECORD: Classes and grading periods are required for enrollment "+id+".");
            long expected=classes*periods;
            if(expected>1000||totalRows+expected>2000)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Selected academic records exceed 2,000 grade cells or 1,000 cells in one term. Generate smaller term selections.");
            var grades=db.queryForList("""
                SELECT sub.code,sub.name,sub.units,gp.code AS period,gp.sequence,g.value,g.mark,g.state,g.version AS grade_version
                FROM class_memberships cm JOIN class_offerings co ON co.id=cm.class_id JOIN subjects sub ON sub.id=co.subject_id
                JOIN grading_periods gp ON gp.term_id=cm.term_id
                LEFT JOIN grades g ON g.class_id=cm.class_id AND g.enrollment_id=cm.enrollment_id AND g.grading_period_id=gp.id
                WHERE cm.enrollment_id=? ORDER BY sub.code,co.id,gp.sequence,gp.id
                """,id);
            totalRows+=grades.size();term.put("grades",grades);
            if(grades.stream().anyMatch(g->!"finalized".equals(g.get("state"))||g.get("value")==null||g.get("mark")!=null))reasons.add("UNRESOLVED_GRADES: Every selected grade must be finalized and numeric for enrollment "+id+".");
            long pending=db.queryForObject("SELECT count(*) FROM grade_corrections gc JOIN grades g ON g.id=gc.grade_id WHERE g.enrollment_id=? AND gc.status IN ('pending','approved')",Long.class,id);
            long inc=db.queryForObject("SELECT count(*) FROM incomplete_cases ic JOIN grades g ON g.id=ic.grade_id WHERE g.enrollment_id=? AND ic.status<>'resolved'",Long.class,id);
            if(pending>0||inc>0)reasons.add("PENDING_GRADE_REVIEW: Resolve correction and INC cases for enrollment "+id+".");
            var soRows=db.queryForList("SELECT * FROM student_special_orders WHERE enrollment_id=?",id);
            Map<String,Object> so=soRows.isEmpty()?Map.of():soRows.getFirst();term.put("specialOrder",so);
            if(Boolean.TRUE.equals(policy.get("so_required"))){
                if(so.isEmpty())reasons.add("SPECIAL_ORDER_REQUIRED: Registrar SO information is required for enrollment "+id+".");
                else {String number=so.get("number").toString();if(!number.startsWith(policy.get("so_prefix").toString())||number.length()>num(policy,"so_max_length"))reasons.add("SPECIAL_ORDER_FORMAT: SO information does not match the approved policy for enrollment "+id+".");}
            }
            terms.add(term);
        }
        if(db.queryForObject("SELECT EXISTS(SELECT 1 FROM withdrawals w JOIN enrollments e ON e.id=w.enrollment_id WHERE e.student_id=? AND w.status<>'completed')",Boolean.class,selection.studentId()))reasons.add("WITHDRAWAL_CLEARANCE: Every student withdrawal must be completed before release.");
        var result=new LinkedHashMap<String,Object>();result.put("student",studentRows.getFirst());result.put("policy",policy);result.put("terms",terms);result.put("eligible",reasons.isEmpty());result.put("reasons",reasons);result.put("scope","Only the explicitly selected enrollment terms are included; no unapproved final rating is computed.");return result;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Map<String,Object> verify(Selection input){return snapshot(input);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public byte[] preview(Selection input){return render(snapshot(input),null,true);}
    @Transactional(isolation=Isolation.READ_COMMITTED) public Map<String,Object> issue(Selection input){
        selection(input);if(input.requestKey()==null)throw bad("A unique requestKey UUID is required for issuance.");
        // EXCLUSIVE/NOWAIT also conflicts with existing SELECT FOR UPDATE (ROW SHARE) writers.
        // A busy writer yields 409 immediately; no locks are held during any network calls.
        // PostgreSQL explicit table locks: https://www.postgresql.org/docs/current/explicit-locking.html
        db.execute("LOCK TABLE academic_terms,academic_years,application_requirements,applications,campuses,class_memberships,class_offerings,enrollments,grade_corrections,grades,grading_periods,incomplete_cases,official_document_policies,programs,requirement_types,student_special_orders,students,subjects,withdrawals IN EXCLUSIVE MODE NOWAIT");
        var existing=db.queryForList("SELECT id,student_id,policy_id,policy_version,snapshot::text FROM official_document_generations WHERE request_key=?",input.requestKey());
        if(!existing.isEmpty()){
            var row=existing.getFirst();
            if(num(row,"student_id")!=input.studentId()||num(row,"policy_id")!=input.policyId()||num(row,"policy_version")!=input.policyVersion())throw conflict("Request key was already used for another selection.");
            var old=parse(row.get("snapshot").toString());
            @SuppressWarnings("unchecked") var oldTerms=(List<Map<String,Object>>)old.get("terms");
            var ids=oldTerms.stream().map(t->num(t,"enrollmentId")).sorted().toList();
            if(!ids.equals(input.enrollmentIds().stream().sorted().toList()))throw conflict("Request key was already used for different enrollment records.");
            return generation(UUID.fromString(row.get("id").toString()));
        }
        var snapshot=snapshot(input);
        if(!Boolean.TRUE.equals(snapshot.get("eligible")))throw conflict("Document generation blocked: "+String.join("; ",(List<String>)snapshot.get("reasons")));
        UUID id=UUID.randomUUID();byte[] pdf=render(snapshot,id,false);
        if(pdf.length>5_000_000)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Generated document exceeds the 5 MB limit.");
        audit();String storage="db:official-document:"+id;var policy=(Map<String,Object>)snapshot.get("policy");
        long request=db.queryForObject("INSERT INTO document_requests(student_id,document_type,status,issued_at,issued_by,storage_key) VALUES (?,?,'issued',current_timestamp,?,?) RETURNING id",Long.class,input.studentId(),policy.get("document_type"),actor(),storage);
        db.update("INSERT INTO official_document_generations(id,request_key,document_request_id,student_id,campus_id,program_id,document_type,policy_id,policy_version,snapshot,pdf,sha256,generated_by,created_by) VALUES(?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?)",id,input.requestKey(),request,input.studentId(),policy.get("campus_id"),policy.get("program_id"),policy.get("document_type"),input.policyId(),input.policyVersion(),stringify(snapshot),pdf,sha(pdf),actor(),actor());
        return generation(id);
    }
    public Map<String,Object> generation(UUID id){
        var rows=db.queryForList("SELECT id,document_request_id,student_id,campus_id,program_id,document_type,policy_id,policy_version,sha256,generated_by,generated_at FROM official_document_generations WHERE id=?",id);
        if(rows.isEmpty())throw absent();var row=rows.getFirst();campus(num(row,"campus_id"));return row;
    }
    public byte[] download(UUID id){generation(id);return db.queryForObject("SELECT pdf FROM official_document_generations WHERE id=?",byte[].class,id);}
    private String stringify(Object object){try{return json.writeValueAsString(object);}catch(JsonProcessingException e){throw new IllegalStateException("Document snapshot could not be serialized",e);}}
    private Map<String,Object> parse(String text){try{return json.readValue(text,Map.class);}catch(JsonProcessingException e){throw new IllegalStateException("Stored document snapshot is invalid",e);}}
    private String sha(byte[] data){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    @SuppressWarnings("unchecked") private byte[] render(Map<String,Object> snapshot,UUID id,boolean draft){
        var policy=(Map<String,Object>)snapshot.get("policy");var student=(Map<String,Object>)snapshot.get("student");var lines=new ArrayList<String>();
        String title=(draft?"DRAFT - NOT FOR OFFICIAL RELEASE - ":"")+policy.get("document_type")+" - Academic Record";
        lines.add(policy.get("institution_name").toString());lines.add(policy.get("institution_address").toString());
        lines.add("Template: "+policy.get("template_reference")+" | revision "+policy.get("version"));
        lines.add(draft?"Registrar template/policy review required before official release.":"Record ID: "+id+" | Registrar approval: "+policy.get("approval_reference"));
        lines.add("Student: "+student.get("last_name")+", "+student.get("first_name")+" "+Objects.toString(student.get("middle_name"),"")+" "+Objects.toString(student.get("suffix"),""));
        lines.add("Student number: "+student.get("student_number")+" | LRN: "+Objects.toString(student.get("lrn"),"Not recorded"));
        lines.add("Birth date: "+student.get("birth_date")+" | Sex: "+Objects.toString(student.get("sex"),"Not recorded"));
        if(draft)for(String reason:(List<String>)snapshot.get("reasons"))lines.add("BLOCKER: "+reason);
        for(var term:(List<Map<String,Object>>)snapshot.get("terms")){
            lines.add("");lines.add(term.get("academic_year")+" / "+term.get("name")+" / "+term.get("program")+" / "+term.get("campus"));
            lines.add("Enrollment: "+term.get("enrollmentId")+" | standing: "+term.get("enrollmentStatus"));
            var so=(Map<String,Object>)term.get("specialOrder");lines.add(so.isEmpty()?"Special Order: not required or not recorded":"Special Order: "+so.get("number")+" | "+so.get("issued_on")+" | "+so.get("issuing_authority"));
            lines.add("SUBJECT | UNITS | GRADING PERIOD | RECORDED MARK | STATE");
            for(var grade:(List<Map<String,Object>>)term.get("grades"))lines.add(grade.get("code")+" - "+grade.get("name")+" | "+grade.get("units")+" | "+grade.get("period")+" | "+Objects.toString(grade.get("value"),Objects.toString(grade.get("mark"),"MISSING"))+" | "+Objects.toString(grade.get("state"),"MISSING"));
        }
        lines.add("");lines.add(snapshot.get("scope").toString());lines.add("Authorized signatories (names/titles only; no digital signatures are implied):");lines.add(policy.get("signatories").toString());
        return OfficialDocumentPdf.render(title,lines);
    }
}
