package ph.edu.ici.academic;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.AccessPolicy;
import ph.edu.ici.auth.StaffIdentity;
import static ph.edu.ici.academic.GradeController.*;

/** Teacher grade drafts and submission for a class and grading period. */
@Service
public class GradeService {
    static final Set<String> MARKS=Set.of("INC","NG","DRP");
    private static final BigDecimal HUNDRED=new BigDecimal("100");

    private final NamedParameterJdbcTemplate db;
    private final AccessPolicy accessPolicy;

    public GradeService(NamedParameterJdbcTemplate db,AccessPolicy accessPolicy){
        this.db=db;this.accessPolicy=accessPolicy;
    }

    private StaffIdentity identity(){return (StaffIdentity)SecurityContextHolder.getContext().getAuthentication().getPrincipal();}
    private ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    private long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}

    private void actor(){
        db.queryForObject("SELECT set_config('app.actor_id',:actor,true)",
                Map.of("actor",Long.toString(identity().userId())),String.class);
    }

    /** The class must be taught by the caller in a campus where they hold grades.encode. */
    private Map<String,Object> assignedClass(long id,long academicYearId,long termId,boolean lock){
        var valid=db.queryForList("SELECT 1 FROM academic_terms WHERE id=:term AND academic_year_id=:year",
                Map.of("term",termId,"year",academicYearId));
        if(valid.isEmpty())throw error(HttpStatus.BAD_REQUEST,"The selected term does not belong to the selected academic year.");
        var rows=db.queryForList("""
            SELECT co.id AS "classId",co.section_id AS "sectionId",s.name AS section,s.grade_level AS "gradeLevel",
                   s.room,co.term_id AS "termId",sub.code AS "subjectCode",sub.name AS subject
            FROM class_offerings co JOIN sections s ON s.id=co.section_id JOIN subjects sub ON sub.id=co.subject_id
            WHERE co.id=:id AND co.term_id=:term AND co.teacher_id=:teacher AND s.campus_id IN (:scope)
            """+(lock?" FOR UPDATE OF co":""),
                Map.of("id",id,"term",termId,"teacher",identity().userId(),"scope",accessPolicy.campuses("grades.encode")));
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Assigned class not found.");
        return rows.getFirst();
    }

    private Map<String,Object> period(long classId,long termId,long periodId){
        var rows=db.queryForList("""
            SELECT gp.id,gp.code,gp.sequence,gp.starts_on AS "startsOn",gp.ends_on AS "endsOn",
                   LEAST(gw.deadline,sw.deadline) AS deadline,
                   CASE WHEN gw.locked_at IS NOT NULL OR sw.locked_at IS NOT NULL
                             OR gw.deadline<=clock_timestamp() OR sw.deadline<=clock_timestamp()
                        THEN 'locked' ELSE 'open' END AS status
            FROM grading_periods gp
            LEFT JOIN grading_windows gw ON gw.grading_period_id=gp.id AND gw.class_id=:class
            LEFT JOIN class_offerings co ON co.id=:class
            LEFT JOIN section_grading_windows sw ON sw.section_id=co.section_id AND sw.grading_period_id=gp.id
            WHERE gp.id=:period AND gp.term_id=:term
            """,Map.of("class",classId,"period",periodId,"term",termId));
        if(rows.isEmpty())throw error(HttpStatus.BAD_REQUEST,"The grading period does not belong to the selected term.");
        return rows.getFirst();
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> gradingPeriods(long id,long academicYearId,long termId){
        var offering=assignedClass(id,academicYearId,termId,false);
        var periods=db.queryForList("""
            SELECT gp.id,gp.code,gp.sequence,gp.starts_on AS "startsOn",gp.ends_on AS "endsOn",
                   LEAST(gw.deadline,sw.deadline) AS deadline,
                   CASE WHEN gw.locked_at IS NOT NULL OR sw.locked_at IS NOT NULL
                             OR gw.deadline<=clock_timestamp() OR sw.deadline<=clock_timestamp()
                        THEN 'locked' ELSE 'open' END AS status
            FROM grading_periods gp
            LEFT JOIN grading_windows gw ON gw.grading_period_id=gp.id AND gw.class_id=:class
            LEFT JOIN class_offerings co ON co.id=:class
            LEFT JOIN section_grading_windows sw ON sw.section_id=co.section_id AND sw.grading_period_id=gp.id
            WHERE gp.term_id=:term ORDER BY gp.sequence
            """,Map.of("class",id,"term",termId));
        return Map.of("class",offering,"periods",periods);
    }

    private List<Map<String,Object>> students(long classId,long periodId){
        return db.queryForList("""
            SELECT e.id AS "enrollmentId",st.student_number AS "studentNumber",
                   concat_ws(' ',st.first_name,st.middle_name,st.last_name,st.suffix) AS name,
                   g.value,g.mark,coalesce(g.state,'draft') AS state,g.version
            FROM class_memberships cm JOIN enrollments e ON e.id=cm.enrollment_id
            JOIN students st ON st.id=e.student_id
            LEFT JOIN grades g ON g.class_id=cm.class_id AND g.enrollment_id=cm.enrollment_id AND g.grading_period_id=:period
            WHERE cm.class_id=:class AND e.status IN ('provisional','enrolled')
            ORDER BY st.last_name,st.first_name,st.id
            """,Map.of("class",classId,"period",periodId));
    }

    private Map<String,Object> view(Map<String,Object> offering,Map<String,Object> period,long classId){
        var students=students(classId,number(period,"id"));
        String state="draft";
        if(students.stream().anyMatch(row->"finalized".equals(row.get("state"))))state="finalized";
        else if(!students.isEmpty()&&students.stream().allMatch(row->Set.of("submitted","finalized").contains(row.get("state"))))state="submitted";
        var result=new LinkedHashMap<String,Object>();
        result.put("class",offering);result.put("period",period);result.put("state",state);result.put("students",students);
        return result;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> gradebook(long id,long academicYearId,long termId,long periodId){
        var offering=assignedClass(id,academicYearId,termId,false);
        return view(offering,period(id,termId,periodId),id);
    }

    private void validate(GradeEntry entry){
        boolean hasValue=entry.value()!=null,hasMark=entry.mark()!=null&&!entry.mark().isBlank();
        if(hasValue&&hasMark)throw error(HttpStatus.BAD_REQUEST,"Enter either a numeric grade or a mark, not both.");
        if(hasMark&&!MARKS.contains(entry.mark().trim().toUpperCase(Locale.ROOT)))
            throw error(HttpStatus.BAD_REQUEST,"Use an approved mark: INC, NG or DRP.");
        if(hasValue){
            var value=entry.value();
            if(value.compareTo(BigDecimal.ZERO)<0||value.compareTo(HUNDRED)>0)
                throw error(HttpStatus.BAD_REQUEST,"Numeric grades must be between 0.00 and 100.00.");
            if(value.stripTrailingZeros().scale()>2)
                throw error(HttpStatus.BAD_REQUEST,"Numeric grades allow at most two decimal places.");
        }
    }

    private void requireOpen(Map<String,Object> period){
        if("locked".equals(period.get("status")))
            throw error(HttpStatus.FORBIDDEN,"This grading period is locked or its submission deadline has passed.");
    }

    @Transactional
    public Map<String,Object> saveDraft(long id,DraftGrades input){
        var offering=assignedClass(id,input.academicYearId(),input.termId(),true);
        var period=period(id,input.termId(),input.gradingPeriodId());
        requireOpen(period);
        input.entries().forEach(this::validate);
        var seen=new HashSet<Long>();
        for(var entry:input.entries())
            if(!seen.add(entry.enrollmentId()))throw error(HttpStatus.BAD_REQUEST,"Each student may appear only once.");
        var members=new HashSet<Long>();
        students(id,input.gradingPeriodId()).forEach(row->members.add(number(row,"enrollmentId")));
        actor();
        for(var entry:input.entries()){
            if(!members.contains(entry.enrollmentId()))
                throw error(HttpStatus.BAD_REQUEST,"A student in the request is not in this class.");
            BigDecimal value=entry.value()==null?null:entry.value().setScale(2);
            String mark=entry.mark()==null||entry.mark().isBlank()?null:entry.mark().trim().toUpperCase(Locale.ROOT);
            var existing=db.queryForList("""
                SELECT id,state,version FROM grades
                WHERE class_id=:class AND enrollment_id=:enrollment AND grading_period_id=:period FOR UPDATE
                """,Map.of("class",id,"enrollment",entry.enrollmentId(),"period",input.gradingPeriodId()));
            if(existing.isEmpty()){
                if(entry.version()!=null)throw error(HttpStatus.CONFLICT,"Grades changed. Reload before saving.");
                if(value==null&&mark==null)continue;
                db.update("""
                    INSERT INTO grades(class_id,enrollment_id,term_id,grading_period_id,value,mark,updated_by)
                    VALUES(:class,:enrollment,:term,:period,:value,:mark,:actor)
                    """,params(id,entry.enrollmentId(),input.termId(),input.gradingPeriodId(),value,mark));
                continue;
            }
            var row=existing.getFirst();
            if(!"draft".equals(row.get("state")))
                throw error(HttpStatus.CONFLICT,"Submitted grades are read-only.");
            if(entry.version()==null||((Number)row.get("version")).intValue()!=entry.version())
                throw error(HttpStatus.CONFLICT,"Grades changed. Reload before saving.");
            db.update("""
                UPDATE grades SET value=:value,mark=:mark,version=version+1,updated_at=current_timestamp,updated_by=:actor
                WHERE id=:grade
                """,params(id,entry.enrollmentId(),input.termId(),input.gradingPeriodId(),value,mark).addValue("grade",number(row,"id")));
        }
        // A batch that crosses its cutoff rolls back instead of committing late marks.
        var latestPeriod=period(id,input.termId(),input.gradingPeriodId());
        requireOpen(latestPeriod);
        return view(offering,latestPeriod,id);
    }

    private MapSqlParameterSource params(long classId,long enrollment,long term,long period,BigDecimal value,String mark){
        return new MapSqlParameterSource().addValue("class",classId).addValue("enrollment",enrollment)
                .addValue("term",term).addValue("period",period).addValue("value",value).addValue("mark",mark)
                .addValue("actor",identity().userId());
    }

    @Transactional
    public Map<String,Object> submit(long id,SubmitGrades input){
        var offering=assignedClass(id,input.academicYearId(),input.termId(),true);
        var period=period(id,input.termId(),input.gradingPeriodId());
        requireOpen(period);
        var roster=students(id,input.gradingPeriodId());
        if(roster.isEmpty())throw error(HttpStatus.BAD_REQUEST,"This class has no students to grade.");
        if(roster.stream().anyMatch(row->!"draft".equals(row.get("state"))&&row.get("version")!=null))
            throw error(HttpStatus.CONFLICT,"Grades have already been submitted.");
        long missing=roster.stream().filter(row->row.get("value")==null&&row.get("mark")==null).count();
        if(missing>0)throw error(HttpStatus.BAD_REQUEST,missing+" student(s) have no grade or mark. Complete the grades before submitting.");
        actor();
        db.update("""
            UPDATE grades SET state='submitted',version=version+1,updated_at=current_timestamp,updated_by=:actor
            WHERE class_id=:class AND grading_period_id=:period AND state='draft'
            """,new MapSqlParameterSource().addValue("class",id).addValue("period",input.gradingPeriodId())
                .addValue("actor",identity().userId()));
        var latestPeriod=period(id,input.termId(),input.gradingPeriodId());
        requireOpen(latestPeriod);
        return view(offering,latestPeriod,id);
    }
}
