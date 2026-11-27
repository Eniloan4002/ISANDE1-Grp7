package ph.edu.ici.imports;

import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import ph.edu.ici.enrollment.EnrollmentService;

/**
 * What one allocation row would do to one student's enrollment, decided from live data. Used by staging, and again by the commit recheck,
 * so both always answer the same question. Read-only. The rules that decide whether a section can take the student are not copied here:
 * they come from {@link EnrollmentService#assignmentBlockers}, the read-only half of the method the commit finally calls. What this class
 * adds is only what that service cannot know: which enrollment the row names, and whether the file's own grade level and program agree with it.
 * Capacity is deliberately not decided here (the caller simulates it across the whole batch); the service rechecks it under locks on every write.
 */
@Component
final class SectionAllocationPlanner {
    static final String ASSIGN="assign",REASSIGN="reassign",NOOP="noop";
    /**
     * @param action null when the row cannot be applied (see the errors in {@code problems})
     * @param enrollmentId, enrollmentVersion, fromSectionId what the enrollment looked like when planned (null before it was found)
     */
    record Plan(String action,Long enrollmentId,Integer enrollmentVersion,Long fromSectionId,Long toSectionId,List<RowProblem> problems) {
        boolean ok(){return action!=null;}
    }
    private final NamedParameterJdbcTemplate db;private final EnrollmentService enrollments;
    SectionAllocationPlanner(NamedParameterJdbcTemplate db,EnrollmentService enrollments){this.db=db;this.enrollments=enrollments;}

    /** Grade levels compare as the enrollment service does: case-insensitive, with a leading "Grade " dropped. */
    static String gradeKey(String grade){return grade==null?"":grade.trim().toLowerCase(Locale.ROOT).replaceFirst("^grade\\s+","");}

    /**
     * @param fileGradeLevel the file's grade level for the row, or null
     * @param fileProgramCode the file's program, already translated to a program code, or null
     */
    Plan plan(long campusId,long termId,long studentId,long sectionId,String fileGradeLevel,String fileProgramCode){
        List<RowProblem> problems=new ArrayList<>();
        var found=db.queryForList("""
            SELECT e.id,e.version,e.section_id,p.code AS program,coalesce(a.profile->>'gradeLevel','') AS grade
            FROM enrollments e JOIN applications a ON a.id=e.application_id JOIN programs p ON p.id=e.program_id
            WHERE e.student_id=:student AND e.term_id=:term AND e.campus_id=:campus""",Map.of("student",studentId,"term",termId,"campus",campusId));
        if(found.isEmpty()){
            problems.add(RowProblem.error("ENROLLMENT_NOT_FOUND",null,"The student has no enrollment in this term at this campus. Enrollment is created and confirmed through the enrollment workflow, never by this import."));
            return new Plan(null,null,null,null,sectionId,problems);
        }
        var e=found.getFirst();
        long enrollment=((Number)e.get("id")).longValue();int version=((Number)e.get("version")).intValue();
        Long from=e.get("section_id")==null?null:((Number)e.get("section_id")).longValue();
        if(fileGradeLevel!=null&&!gradeKey(fileGradeLevel).equals(gradeKey((String)e.get("grade"))))
            problems.add(RowProblem.error("CONTEXT_MISMATCH","gradeLevel","The grade level in the file differs from the one recorded for the enrollment."));
        if(fileProgramCode!=null&&!fileProgramCode.equalsIgnoreCase((String)e.get("program")))
            problems.add(RowProblem.error("CONTEXT_MISMATCH","program","The program in the file differs from the enrollment's program."));
        for(var blocker:enrollments.assignmentBlockers(enrollment,sectionId)){
            if("SECTION_FULL".equals(blocker.code()))continue;                       // capacity is simulated across the batch by the caller
            String code="SECTION_NOT_FOUND".equals(blocker.code())?"UNKNOWN_SECTION":blocker.code();
            problems.add(RowProblem.error(code,"section",blocker.message()));
        }
        boolean errors=problems.stream().anyMatch(p->RowProblem.ERROR.equals(p.severity()));
        if(errors)return new Plan(null,enrollment,version,from,sectionId,problems);
        if(Objects.equals(from,sectionId)){
            problems.add(RowProblem.warning("ALREADY_ASSIGNED",null,"The student is already in this section; nothing will be written."));
            return new Plan(NOOP,enrollment,version,from,sectionId,problems);
        }
        if(from!=null){
            problems.add(RowProblem.warning("REASSIGNMENT",null,"The student is already in a different section. Applying this row moves the student; review it."));
            return new Plan(REASSIGN,enrollment,version,from,sectionId,problems);
        }
        return new Plan(ASSIGN,enrollment,version,null,sectionId,problems);
    }
}
