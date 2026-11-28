package ph.edu.ici.honors;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.*;
import static ph.edu.ici.honors.HonorController.*;

@Service
public class HonorService {
    private final NamedParameterJdbcTemplate db;
    private final AccessPolicy access;
    private final ObjectMapper json;
    public HonorService(NamedParameterJdbcTemplate db,AccessPolicy access,ObjectMapper json){this.db=db;this.access=access;this.json=json;}
    private ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    private void allowed(long campus,String permission){if(!access.campuses(permission).contains(campus))throw error(HttpStatus.FORBIDDEN,"Campus is outside your honor-policy scope.");}
    private void audit(){long id=((StaffIdentity)SecurityContextHolder.getContext().getAuthentication().getPrincipal()).userId();db.queryForObject("SELECT set_config('app.actor_id',:actor,true)",Map.of("actor",Long.toString(id)),String.class);}
    private String encode(Rules rules){try{return json.writeValueAsString(rules);}catch(Exception e){throw new IllegalStateException(e);}}
    private Rules rules(Map<String,Object> policy){try{return json.readValue(policy.get("rules").toString(),Rules.class);}catch(Exception e){throw new IllegalStateException("Invalid stored honor rules",e);}}
    private Map<String,Object> row(long id,boolean lock,String permission){
        var scope=access.campuses(permission);if(scope.isEmpty())throw error(HttpStatus.FORBIDDEN,"Honor policy access is required.");
        var rows=db.queryForList("SELECT * FROM honor_policies WHERE id=:id AND campus_id IN (:scope)"+(lock?" FOR UPDATE":""),Map.of("id",id,"scope",scope));
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Honor policy not found.");return rows.getFirst();
    }
    private Map<String,Object> exposed(Map<String,Object> row){var result=new LinkedHashMap<>(row);result.put("rules",rules(row));return result;}
    private void version(Map<String,Object> row,int version){if(((Number)row.get("version")).intValue()!=version)throw error(HttpStatus.CONFLICT,"Policy changed. Reload before saving.");}
    private BigDecimal average(List<BigDecimal> values,Rules rule){BigDecimal sum=BigDecimal.ZERO,weights=BigDecimal.ZERO;for(int i=0;i<values.size();i++){var weight=rule.subjects().get(i).weight();sum=sum.add(values.get(i).multiply(weight));weights=weights.add(weight);}return sum.divide(weights,rule.decimalPlaces(),RoundingMode.valueOf(rule.rounding()));}
    private String category(BigDecimal avg,List<BigDecimal> values,Rules rules){
        if(values.stream().anyMatch(v->v.compareTo(rules.minimumSubjectGrade())<0))return null;
        return rules.categories().stream().sorted(Comparator.comparing(Category::minimumAverage).reversed()).filter(c->avg.compareTo(c.minimumAverage())>=0).map(Category::name).findFirst().orElse(null);
    }
    private void validate(Rules rules){
        if(rules.subjects().stream().map(SubjectWeight::subjectId).distinct().count()!=rules.subjects().size())throw error(HttpStatus.BAD_REQUEST,"Each included subject must appear once.");
        var ids=rules.subjects().stream().map(SubjectWeight::subjectId).toList();
        if(db.queryForObject("SELECT count(*) FROM subjects WHERE id IN (:ids)",Map.of("ids",ids),Integer.class)!=ids.size())throw error(HttpStatus.BAD_REQUEST,"Included subject does not exist.");
        var names=new HashSet<String>();var thresholds=new HashSet<BigDecimal>();
        for(var c:rules.categories())if(!names.add(c.name().trim())||!thresholds.add(c.minimumAverage().stripTrailingZeros()))throw error(HttpStatus.BAD_REQUEST,"Honor categories require distinct names and thresholds.");
        for(var example:rules.examples()){
            if(example.values().size()!=rules.subjects().size())throw error(HttpStatus.BAD_REQUEST,"Example values must match included subjects in order.");
            var avg=average(example.values(),rules);var category=category(avg,example.values(),rules);
            if(avg.compareTo(example.expectedAverage())!=0||!Objects.equals(category,example.expectedCategory()))throw error(HttpStatus.BAD_REQUEST,"Example '"+example.name()+"' does not match the configured calculation and eligibility rules.");
        }
    }
    @Transactional
    public Map<String,Object> create(PolicyInput input){
        allowed(input.campusId(),"honors.configure");validate(input.rules());
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM grading_periods WHERE id=:id)",Map.of("id",input.gradingPeriodId()),Boolean.class))||!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM programs WHERE id=:id)",Map.of("id",input.programId()),Boolean.class)))throw error(HttpStatus.BAD_REQUEST,"Academic period or program does not exist.");
        audit();long id=db.queryForObject("INSERT INTO honor_policies(campus_id,program_id,grade_level,grading_period_id,rules) VALUES(:campus,:program,:grade,:period,CAST(:rules AS jsonb)) RETURNING id",new MapSqlParameterSource("campus",input.campusId()).addValue("program",input.programId()).addValue("grade",input.gradeLevel().trim()).addValue("period",input.gradingPeriodId()).addValue("rules",encode(input.rules())),Long.class);
        return exposed(row(id,false,"honors.configure"));
    }
    @Transactional(readOnly=true)
    public Map<String,Object> get(long id){
        var scope=new HashSet<Long>();for(String p:List.of("honors.configure","honors.approve","reports.read"))scope.addAll(access.campuses(p));
        if(scope.isEmpty())throw error(HttpStatus.FORBIDDEN,"Honor policy access is required.");
        var rows=db.queryForList("SELECT * FROM honor_policies WHERE id=:id AND campus_id IN (:scope)",Map.of("id",id,"scope",scope));if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Honor policy not found.");return exposed(rows.getFirst());
    }
    /**
     * Lists the honor policies the caller may see, newest first, optionally narrowed by scope fields or status. The honor-policy
     * editor needs this to find the draft and the approved policy for a program, grade level and grading period without knowing ids.
     */
    @Transactional(readOnly=true)
    public Map<String,Object> list(Long campusId,Long programId,String gradeLevel,Long gradingPeriodId,String status,int page,int size){
        if(page<0||page>1_000_000||size<1||size>100)throw error(HttpStatus.BAD_REQUEST,"Use a nonnegative page and a size of 1 to 100.");
        if(status!=null&&!List.of("draft","approved","superseded").contains(status))throw error(HttpStatus.BAD_REQUEST,"Status must be draft, approved or superseded.");
        var scope=new TreeSet<Long>();for(String p:List.of("honors.configure","honors.approve","reports.read"))scope.addAll(access.campuses(p));
        if(scope.isEmpty())throw error(HttpStatus.FORBIDDEN,"Honor policy access is required.");
        if(campusId!=null&&!scope.contains(campusId))throw error(HttpStatus.FORBIDDEN,"Campus is outside your honor-policy scope.");
        var params=new HashMap<String,Object>();params.put("scope",campusId==null?List.copyOf(scope):List.of(campusId));
        var where=new StringBuilder(" WHERE campus_id IN (:scope)");
        if(programId!=null){where.append(" AND program_id=:program");params.put("program",programId);}
        if(gradeLevel!=null&&!gradeLevel.isBlank()){where.append(" AND grade_level=:grade");params.put("grade",gradeLevel.trim());}
        if(gradingPeriodId!=null){where.append(" AND grading_period_id=:period");params.put("period",gradingPeriodId);}
        if(status!=null){where.append(" AND status=:status");params.put("status",status);}
        long total=db.queryForObject("SELECT count(*) FROM honor_policies"+where,params,Long.class);
        params.put("limit",size);params.put("offset",(long)page*size);
        var items=db.queryForList("SELECT * FROM honor_policies"+where+" ORDER BY updated_at DESC,id DESC LIMIT :limit OFFSET :offset",params).stream().map(this::exposed).toList();
        return Map.of("items",items,"total",total,"page",page,"size",size);
    }
    @Transactional
    public Map<String,Object> update(long id,DraftUpdate input){
        var row=row(id,true,"honors.configure");version(row,input.version());if(!"draft".equals(row.get("status")))throw error(HttpStatus.CONFLICT,"Approved policy content is immutable. Create a new draft revision.");validate(input.rules());audit();
        db.update("UPDATE honor_policies SET rules=CAST(:rules AS jsonb),version=version+1 WHERE id=:id",Map.of("id",id,"rules",encode(input.rules())));return exposed(row(id,false,"honors.configure"));
    }
    @Transactional
    public Map<String,Object> approve(long id,Approval input){
        // Serialize approvals by parent grading period before policy rows, including replacements.
        var initial=row(id,false,"honors.approve");db.queryForObject("SELECT id FROM grading_periods WHERE id=:id FOR UPDATE",Map.of("id",initial.get("grading_period_id")),Long.class);
        var policy=row(id,true,"honors.approve");version(policy,input.version());if(!"draft".equals(policy.get("status")))throw error(HttpStatus.CONFLICT,"Only draft policies can be approved.");validate(rules(policy));audit();
        var p=new MapSqlParameterSource("id",id).addValue("campus",policy.get("campus_id")).addValue("program",policy.get("program_id")).addValue("grade",policy.get("grade_level")).addValue("period",policy.get("grading_period_id")).addValue("reference",input.reference().trim());
        db.update("UPDATE honor_policies SET status='superseded',version=version+1 WHERE campus_id=:campus AND program_id=:program AND grade_level=:grade AND grading_period_id=:period AND status='approved'",p);
        db.update("UPDATE honor_policies SET status='approved',approval_reference=:reference,approved_by=current_setting('app.actor_id')::bigint,approved_at=current_timestamp,version=version+1 WHERE id=:id",p);return exposed(row(id,false,"honors.approve"));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> roll(long campus,long program,String grade,long period){
        allowed(campus,"reports.read");var p=new MapSqlParameterSource("campus",campus).addValue("program",program).addValue("grade",grade).addValue("period",period);
        var policies=db.queryForList("SELECT hp.*,gp.term_id,t.academic_year_id FROM honor_policies hp JOIN grading_periods gp ON gp.id=hp.grading_period_id JOIN academic_terms t ON t.id=gp.term_id WHERE campus_id=:campus AND program_id=:program AND grade_level=:grade AND grading_period_id=:period AND status='approved'",p);
        if(policies.isEmpty())throw error(HttpStatus.CONFLICT,"No school-approved honor policy applies to this campus, program, grade level and grading period.");
        var policy=policies.getFirst();var rules=rules(policy);p.addValue("term",policy.get("term_id"));
        var candidates=db.queryForList("SELECT e.id AS \"enrollmentId\",st.student_number AS \"studentNumber\",concat_ws(' ',st.first_name,st.middle_name,st.last_name) AS name FROM enrollments e JOIN sections s ON s.id=e.section_id JOIN students st ON st.id=e.student_id WHERE e.campus_id=:campus AND e.program_id=:program AND e.term_id=:term AND s.grade_level=:grade AND e.status IN ('enrolled','completed') ORDER BY e.id LIMIT 5001",p);
        if(candidates.size()>5000)throw error(HttpStatus.PAYLOAD_TOO_LARGE,"Honor roll is limited to 5,000 enrollments per configured cohort.");
        var result=new ArrayList<Map<String,Object>>();
        if(!candidates.isEmpty()){
            p.addValue("ids",candidates.stream().map(c->c.get("enrollmentId")).toList());p.addValue("subjects",rules.subjects().stream().map(SubjectWeight::subjectId).toList());
            var gradeRows=db.queryForList("SELECT cm.enrollment_id,co.subject_id,g.value,g.mark,g.state,EXISTS(SELECT 1 FROM grade_corrections gc WHERE gc.grade_id=g.id AND gc.status IN ('pending','approved')) AS disputed FROM class_memberships cm JOIN class_offerings co ON co.id=cm.class_id LEFT JOIN grades g ON g.class_id=cm.class_id AND g.enrollment_id=cm.enrollment_id AND g.grading_period_id=:period WHERE cm.enrollment_id IN (:ids) AND co.subject_id IN (:subjects) AND cm.term_id=:term",p);
            var byStudent=new HashMap<Long,Map<Long,List<Map<String,Object>>>>();for(var g:gradeRows)byStudent.computeIfAbsent(((Number)g.get("enrollment_id")).longValue(),k->new HashMap<>()).computeIfAbsent(((Number)g.get("subject_id")).longValue(),k->new ArrayList<>()).add(g);
            for(var candidate:candidates){
                long id=((Number)candidate.get("enrollmentId")).longValue();var marks=byStudent.getOrDefault(id,Map.of());var reasons=new LinkedHashSet<String>();var values=new ArrayList<BigDecimal>();
                for(var subject:rules.subjects()){
                    var records=marks.getOrDefault(subject.subjectId(),List.of());
                    if(records.size()!=1){reasons.add(records.isEmpty()?"missing_subject":"ambiguous_subject");continue;}
                    var g=records.getFirst();if(Boolean.TRUE.equals(g.get("disputed")))reasons.add("pending_grade_correction");if(!"finalized".equals(g.get("state")))reasons.add("unfinalized_or_missing_grade");
                    if(g.get("mark")!=null){reasons.add("non_numeric_mark_"+g.get("mark"));continue;}if(g.get("value")==null){reasons.add("missing_grade");continue;}
                    var value=(BigDecimal)g.get("value");values.add(value);if(value.compareTo(rules.minimumSubjectGrade())<0)reasons.add("below_subject_minimum");
                }
                var item=new LinkedHashMap<>(candidate);BigDecimal avg=values.size()==rules.subjects().size()?average(values,rules):null;
                String honor=avg!=null&&reasons.isEmpty()?category(avg,values,rules):null;if(honor==null&&reasons.isEmpty())reasons.add("below_honor_threshold");
                item.put("average",avg);item.put("category",honor);item.put("eligible",honor!=null);item.put("reasons",reasons);item.put("rank",null);result.add(item);
            }
        }
        result.sort(Comparator.<Map<String,Object>,Boolean>comparing(r->Boolean.TRUE.equals(r.get("eligible"))).reversed().thenComparing(r->(BigDecimal)r.get("average"),Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(r->r.get("studentNumber").toString()));
        BigDecimal previous=null;int position=0,rank=0;for(var r:result)if(Boolean.TRUE.equals(r.get("eligible"))){position++;var avg=(BigDecimal)r.get("average");if(previous==null||avg.compareTo(previous)!=0)rank=position;r.put("rank",rank);previous=avg;}
        return Map.of("policyId",policy.get("id"),"policyVersion",policy.get("version"),"approvalReference",policy.get("approval_reference"),"academicYearId",policy.get("academic_year_id"),"gradingPeriodId",period,"tiePolicy",rules.tiePolicy(),"total",result.size(),"items",result);
    }
}
