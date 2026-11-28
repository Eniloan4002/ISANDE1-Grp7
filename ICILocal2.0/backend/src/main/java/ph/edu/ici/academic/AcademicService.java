package ph.edu.ici.academic;

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
import static ph.edu.ici.academic.AcademicController.*;

@Service
public class AcademicService {
    private final NamedParameterJdbcTemplate db;
    private final AccessPolicy accessPolicy;
    private final WithdrawalService withdrawals;

    public AcademicService(NamedParameterJdbcTemplate db,AccessPolicy accessPolicy,WithdrawalService withdrawals){
        this.db=db;this.accessPolicy=accessPolicy;this.withdrawals=withdrawals;
    }

    private StaffIdentity identity(){return (StaffIdentity)SecurityContextHolder.getContext().getAuthentication().getPrincipal();}
    private List<Long> scope(String permission){return accessPolicy.campuses(permission);}
    private ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    private long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private int version(Map<String,Object> row){return ((Number)row.get("version")).intValue();}

    private void actor(){
        db.queryForObject("SELECT set_config('app.actor_id',:actor,true)",
                Map.of("actor",Long.toString(identity().userId())),String.class);
    }

    private Map<String,Object> yearTerm(long academicYearId,long termId){
        var rows=db.queryForList("""
            SELECT y.id AS "academicYearId",y.label AS "academicYear",t.id AS "termId",t.name AS term
            FROM academic_terms t JOIN academic_years y ON y.id=t.academic_year_id
            WHERE y.id=:year AND t.id=:term
            """,Map.of("year",academicYearId,"term",termId));
        if(rows.isEmpty())throw error(HttpStatus.BAD_REQUEST,"The selected term does not belong to the selected academic year.");
        return rows.getFirst();
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> academicTerms(){
        return Map.of(
            "years",db.queryForList("SELECT id,label FROM academic_years ORDER BY starts_on DESC,id DESC",Map.of()),
            "terms",db.queryForList("""
                SELECT id,academic_year_id,code,name FROM academic_terms ORDER BY starts_on,id
                """,Map.of()));
    }

    private void requireCampus(long campusId,List<Long> campuses){
        if(!campuses.contains(campusId))throw error(HttpStatus.FORBIDDEN,"You do not have access to this campus.");
    }

    private void eligibleStaff(long staffId,long campusId,String role,String label){
        boolean eligible=Boolean.TRUE.equals(db.queryForObject("""
            SELECT EXISTS(
              SELECT 1 FROM users u
              JOIN user_role_grants g ON g.user_id=u.id
              JOIN campuses c ON c.id=g.campus_id AND c.active
              WHERE u.id=:staff AND u.active AND g.role_code=:role AND g.campus_id=:campus
            )
            """,Map.of("staff",staffId,"role",role,"campus",campusId),Boolean.class));
        if(!eligible)throw error(HttpStatus.BAD_REQUEST,"Choose an active "+label+" assigned to this campus.");
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> assignments(long academicYearId,long termId,long campusId){
        var yearTerm=yearTerm(academicYearId,termId);
        var campuses=scope("enrollment.manage");requireCampus(campusId,campuses);
        var params=Map.<String,Object>of("term",termId,"campus",campusId);
        var sections=db.queryForList("""
            SELECT s.id,s.name,s.grade_level AS "gradeLevel",s.room,s.capacity,s.adviser_id AS "adviserId",
                   u.full_name AS adviser,s.version
            FROM sections s LEFT JOIN users u ON u.id=s.adviser_id
            WHERE s.term_id=:term AND s.campus_id=:campus
            ORDER BY s.name,s.id
            """,params);
        var classes=db.queryForList("""
            SELECT co.id AS "classId",co.section_id AS "sectionId",s.name AS section,
                   co.subject_id AS "subjectId",sub.code AS "subjectCode",sub.name AS subject,
                   co.teacher_id AS "teacherId",u.full_name AS teacher,co.version
            FROM class_offerings co JOIN sections s ON s.id=co.section_id
            JOIN subjects sub ON sub.id=co.subject_id JOIN users u ON u.id=co.teacher_id
            WHERE co.term_id=:term AND s.campus_id=:campus
            ORDER BY s.name,sub.code,co.id
            """,params);
        var teachers=db.queryForList("""
            SELECT DISTINCT u.id,u.full_name AS name,g.campus_id AS "campusId"
            FROM users u JOIN user_role_grants g ON g.user_id=u.id
            WHERE u.active AND g.role_code='ROLE_TEACHER' AND g.campus_id=:campus
            ORDER BY u.full_name,u.id
            """,Map.of("campus",campusId));
        var advisers=db.queryForList("""
            SELECT DISTINCT u.id,u.full_name AS name,g.campus_id AS "campusId"
            FROM users u JOIN user_role_grants g ON g.user_id=u.id
            WHERE u.active AND g.role_code='ROLE_ADVISER' AND g.campus_id=:campus
            ORDER BY u.full_name,u.id
            """,Map.of("campus",campusId));
        var subjects=db.queryForList("SELECT id,code,name,units FROM subjects WHERE active ORDER BY code,id",Map.of());
        var result=new LinkedHashMap<String,Object>(yearTerm);
        result.put("campusId",campusId);result.put("sections",sections);result.put("classes",classes);
        result.put("teachers",teachers);result.put("advisers",advisers);result.put("subjects",subjects);
        return result;
    }

    private Map<String,Object> sectionForManager(long id,long termId,List<Long> campuses,boolean lock){
        var rows=db.queryForList("""
            SELECT s.id,s.campus_id,s.term_id,s.adviser_id,s.version
            FROM sections s
            WHERE s.id=:id AND s.term_id=:term AND s.campus_id IN (:scope)
            """+(lock?" FOR UPDATE":""),Map.of("id",id,"term",termId,"scope",campuses));
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Section not found.");
        return rows.getFirst();
    }

    private Map<String,Object> sectionView(long id,List<Long> campuses){
        var rows=db.queryForList("""
            SELECT s.id,s.campus_id AS "campusId",s.term_id AS "termId",s.name,s.grade_level AS "gradeLevel",
                   s.room,s.adviser_id AS "adviserId",u.full_name AS adviser,s.version
            FROM sections s LEFT JOIN users u ON u.id=s.adviser_id
            WHERE s.id=:id AND s.campus_id IN (:scope)
            """,Map.of("id",id,"scope",campuses));
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Section not found.");
        return rows.getFirst();
    }

    private static final String OFFERING_SELECT="""
        SELECT co.id AS "classId",co.section_id AS "sectionId",s.name AS section,
               s.grade_level AS "gradeLevel",s.campus_id AS "campusId",co.term_id AS "termId",
               co.subject_id AS "subjectId",sub.code AS "subjectCode",sub.name AS subject,
               co.teacher_id AS "teacherId",u.full_name AS teacher,co.version
        FROM class_offerings co JOIN sections s ON s.id=co.section_id
        JOIN subjects sub ON sub.id=co.subject_id JOIN users u ON u.id=co.teacher_id
        """;

    private Map<String,Object> offering(long id,List<Long> campuses,boolean lock){
        var rows=db.queryForList(OFFERING_SELECT+" WHERE co.id=:id AND s.campus_id IN (:scope)"+
                (lock?" FOR UPDATE OF co":""),Map.of("id",id,"scope",campuses));
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Class offering not found.");
        return rows.getFirst();
    }

    @Transactional
    public Map<String,Object> createClassOffering(CreateClassOffering input){
        yearTerm(input.academicYearId(),input.termId());
        var campuses=scope("enrollment.manage");
        var section=sectionForManager(input.sectionId(),input.termId(),campuses,true);
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM section_grading_windows WHERE section_id=:section AND finalized_at IS NOT NULL)",
                Map.of("section",input.sectionId()),Boolean.class)))
            throw error(HttpStatus.CONFLICT,"This section has finalized grades. New subject offerings require a reviewed academic amendment.");
        long campus=number(section,"campus_id");
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM subjects WHERE id=:id AND active)",
                Map.of("id",input.subjectId()),Boolean.class)))
            throw error(HttpStatus.BAD_REQUEST,"Choose an active subject.");
        eligibleStaff(input.teacherId(),campus,"ROLE_TEACHER","teacher");
        actor();
        long id=db.queryForObject("""
            INSERT INTO class_offerings(section_id,term_id,subject_id,teacher_id)
            VALUES(:section,:term,:subject,:teacher) RETURNING id
            """,Map.of("section",input.sectionId(),"term",input.termId(),"subject",input.subjectId(),"teacher",input.teacherId()),Long.class);
        addSectionMembers(id,input.sectionId(),input.termId());
        return offering(id,campuses,false);
    }

    /**
     * Adds every active (provisional or enrolled) student of the section to the class roster.
     * Students already on the roster are left unchanged, so this is safe to repeat.
     */
    private int addSectionMembers(long classId,long sectionId,long termId){
        return db.update("""
            INSERT INTO class_memberships(class_id,enrollment_id,section_id,term_id)
            SELECT :class,e.id,e.section_id,e.term_id FROM enrollments e
            WHERE e.section_id=:section AND e.term_id=:term AND e.status IN ('provisional','enrolled')
              AND NOT EXISTS(SELECT 1 FROM class_membership_exclusions x WHERE x.class_id=:class AND x.enrollment_id=e.id)
            ON CONFLICT DO NOTHING
            """,Map.of("class",classId,"section",sectionId,"term",termId));
    }

    /** Adds students who joined the section after the class was created (late enrollees, transferees). */
    @Transactional
    public Map<String,Object> syncClassMembers(long id,long academicYearId,long termId){
        yearTerm(academicYearId,termId);
        var campuses=scope("enrollment.manage");
        var current=offering(id,campuses,true);
        if(number(current,"termId")!=termId)throw error(HttpStatus.NOT_FOUND,"Class offering not found.");
        sectionForManager(number(current,"sectionId"),termId,campuses,true);
        actor();
        int added=addSectionMembers(id,number(current,"sectionId"),termId);
        var result=new LinkedHashMap<String,Object>(offering(id,campuses,false));
        result.put("addedMembers",added);
        return result;
    }

    private Map<String,Object> membershipClass(long id,long termId,List<Long> campuses){
        var current=offering(id,campuses,true);
        if(number(current,"termId")!=termId)throw error(HttpStatus.NOT_FOUND,"Class offering not found.");
        sectionForManager(number(current,"sectionId"),termId,campuses,true);
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM section_grading_windows WHERE section_id=:section AND finalized_at IS NOT NULL)",
                Map.of("section",number(current,"sectionId")),Boolean.class)))
            throw error(HttpStatus.CONFLICT,"This section has finalized grades. Class membership changes require a reviewed academic amendment.");
        return current;
    }

    /**
     * Takes one student out of one class (electives, students on a different subject load).
     * Blocked (409) once the student has a submitted or finalized grade in the class. Draft grades also block
     * unless {@code discardDrafts} is true, in which case the draft rows are deleted (audited) with the membership.
     */
    @Transactional
    public Map<String,Object> removeClassMember(long id,long enrollmentId,long academicYearId,long termId,boolean discardDrafts){
        yearTerm(academicYearId,termId);
        var campuses=scope("enrollment.manage");
        var current=membershipClass(id,termId,campuses);
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM class_memberships WHERE class_id=:class AND enrollment_id=:enrollment)",
                Map.of("class",id,"enrollment",enrollmentId),Boolean.class)))
            throw error(HttpStatus.NOT_FOUND,"This student is not a member of the class.");
        var grades=db.queryForList("SELECT state,count(*) AS n FROM grades WHERE class_id=:class AND enrollment_id=:enrollment GROUP BY state",
                Map.of("class",id,"enrollment",enrollmentId));
        long drafts=0,locked=0;
        for(var row:grades){long n=number(row,"n");if("draft".equals(row.get("state")))drafts+=n;else locked+=n;}
        if(locked>0)throw error(HttpStatus.CONFLICT,"This student already has a submitted or finalized grade in this class and cannot be removed from it.");
        if(drafts>0&&!discardDrafts)throw error(HttpStatus.CONFLICT,"This student has "+drafts+" draft grade(s) in this class. Pass discardDrafts=true to delete the drafts and remove the student.");
        actor();
        db.update("DELETE FROM grades WHERE class_id=:class AND enrollment_id=:enrollment AND state='draft'",Map.of("class",id,"enrollment",enrollmentId));
        db.update("DELETE FROM class_memberships WHERE class_id=:class AND enrollment_id=:enrollment",Map.of("class",id,"enrollment",enrollmentId));
        // Only home-section students are re-added by sync, so only they need an exclusion.
        db.update("""
            INSERT INTO class_membership_exclusions(class_id,enrollment_id)
            SELECT :class,e.id FROM enrollments e WHERE e.id=:enrollment AND e.section_id=:section
            ON CONFLICT DO NOTHING
            """,Map.of("class",id,"enrollment",enrollmentId,"section",number(current,"sectionId")));
        var result=new LinkedHashMap<String,Object>(offering(id,campuses,false));
        result.put("removedEnrollmentId",enrollmentId);result.put("discardedDrafts",drafts);
        return result;
    }

    /** Adds one active student of the same campus and term to one class, including students of another section. */
    @Transactional
    public Map<String,Object> addClassMember(long id,AddClassMember input){
        yearTerm(input.academicYearId(),input.termId());
        var campuses=scope("enrollment.manage");
        var current=membershipClass(id,input.termId(),campuses);
        var rows=db.queryForList("SELECT campus_id,term_id,status FROM enrollments WHERE id=:id AND campus_id IN (:scope)",
                Map.of("id",input.enrollmentId(),"scope",campuses));
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Enrollment not found.");
        var enrollment=rows.getFirst();
        if(number(enrollment,"campus_id")!=number(current,"campusId")||number(enrollment,"term_id")!=input.termId())
            throw error(HttpStatus.BAD_REQUEST,"The student must be enrolled at the class's campus in the class's term.");
        if(!Set.of("provisional","enrolled").contains(enrollment.get("status")))
            throw error(HttpStatus.CONFLICT,"Only provisional or enrolled students can be added to a class.");
        actor();
        db.update("DELETE FROM class_membership_exclusions WHERE class_id=:class AND enrollment_id=:enrollment",Map.of("class",id,"enrollment",input.enrollmentId()));
        int added=db.update("""
            INSERT INTO class_memberships(class_id,enrollment_id,section_id,term_id) VALUES(:class,:enrollment,:section,:term)
            ON CONFLICT DO NOTHING
            """,Map.of("class",id,"enrollment",input.enrollmentId(),"section",number(current,"sectionId"),"term",input.termId()));
        if(added==0)throw error(HttpStatus.CONFLICT,"This student is already a member of the class.");
        var result=new LinkedHashMap<String,Object>(offering(id,campuses,false));
        result.put("addedEnrollmentId",input.enrollmentId());
        return result;
    }

    @Transactional
    public Map<String,Object> assignTeacher(long id,TeacherAssignment input){
        yearTerm(input.academicYearId(),input.termId());
        var campuses=scope("enrollment.manage");
        var current=offering(id,campuses,true);
        if(number(current,"termId")!=input.termId())throw error(HttpStatus.NOT_FOUND,"Class offering not found.");
        if(version(current)!=input.version())throw error(HttpStatus.CONFLICT,"This class assignment changed. Reload before saving.");
        eligibleStaff(input.teacherId(),number(current,"campusId"),"ROLE_TEACHER","teacher");
        if(number(current,"teacherId")==input.teacherId())return current;
        actor();
        db.update("UPDATE class_offerings SET teacher_id=:teacher,version=version+1 WHERE id=:id",
                Map.of("teacher",input.teacherId(),"id",id));
        return offering(id,campuses,false);
    }

    @Transactional
    public Map<String,Object> assignAdviser(long id,AdviserAssignment input){
        yearTerm(input.academicYearId(),input.termId());
        var campuses=scope("enrollment.manage");
        var current=sectionForManager(id,input.termId(),campuses,true);
        if(version(current)!=input.version())throw error(HttpStatus.CONFLICT,"This section assignment changed. Reload before saving.");
        if(input.adviserId()!=null)eligibleStaff(input.adviserId(),number(current,"campus_id"),"ROLE_ADVISER","adviser");
        Object previous=current.get("adviser_id");
        if(Objects.equals(previous,input.adviserId()))return sectionView(id,campuses);
        actor();
        db.update("UPDATE sections SET adviser_id=:adviser,version=version+1 WHERE id=:id",
                new MapSqlParameterSource().addValue("adviser",input.adviserId()).addValue("id",id));
        return sectionView(id,campuses);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> teacherClasses(long academicYearId,long termId){
        var selected=yearTerm(academicYearId,termId);
        var rows=db.queryForList(OFFERING_SELECT+" WHERE co.term_id=:term AND co.teacher_id=:teacher AND s.campus_id IN (:scope) ORDER BY s.name,sub.code,co.id",
                Map.of("term",termId,"teacher",identity().userId(),"scope",scope("grades.encode")));
        var result=new LinkedHashMap<String,Object>(selected);
        result.put("items",rows);return result;
    }

    private Map<String,Object> assignedOffering(long id,long academicYearId,long termId){
        yearTerm(academicYearId,termId);
        var rows=db.queryForList(OFFERING_SELECT+" WHERE co.id=:id AND co.term_id=:term AND co.teacher_id=:teacher AND s.campus_id IN (:scope)",
                Map.of("id",id,"term",termId,"teacher",identity().userId(),"scope",scope("grades.encode")));
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Assigned class not found.");
        return rows.getFirst();
    }

    private static final String REQUIREMENT_STATUS_JOIN="""
        LEFT JOIN LATERAL (
          SELECT CASE
            WHEN count(rt.code)=0 THEN 'Pending'
            WHEN count(rt.code) FILTER(WHERE coalesce(ar.status,'pending')<>'submitted')=0 THEN 'Complete'
            WHEN count(rt.code) FILTER(WHERE ar.status IN ('missing','invalid'))>0 THEN 'Incomplete'
            ELSE 'Pending' END AS status
          FROM requirement_types rt
          LEFT JOIN application_requirements ar ON ar.requirement_code=rt.code AND ar.application_id=a.id
          WHERE rt.mandatory
        ) requirement_summary ON true
        """;

    private List<Map<String,Object>> classStudents(long classId){
        return db.queryForList("""
            SELECT e.id AS "enrollmentId",st.student_number AS "studentNumber",concat_ws(' ',st.first_name,st.middle_name,st.last_name,st.suffix) AS name,
                   st.phone AS "contactNumber",
                   CASE e.status WHEN 'provisional' THEN 'Pre-enlisted' ELSE initcap(e.status) END AS "enrollmentStatus",
                   coalesce(requirement_summary.status,'Pending') AS "requirementsStatus"
            FROM class_memberships cm JOIN enrollments e ON e.id=cm.enrollment_id
            JOIN students st ON st.id=e.student_id JOIN applications a ON a.id=e.application_id
            """+REQUIREMENT_STATUS_JOIN+" WHERE cm.class_id=:class AND e.status IN ('provisional','enrolled') ORDER BY st.last_name,st.first_name,st.id",
                Map.of("class",classId));
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> teacherClassRoster(long id,long academicYearId,long termId){
        var offering=assignedOffering(id,academicYearId,termId);
        return Map.of("class",offering,"students",classStudents(id));
    }

    private List<Map<String,Object>> advisoryStudents(List<Long> sectionIds){
        if(sectionIds.isEmpty())return new ArrayList<>();
        return db.queryForList("""
            SELECT e.section_id AS "sectionId",st.student_number AS "studentNumber",
                   concat_ws(' ',st.first_name,st.middle_name,st.last_name,st.suffix) AS name,
                   st.phone AS "contactNumber",
                   CASE e.status WHEN 'provisional' THEN 'Pre-enlisted' ELSE initcap(e.status) END AS "enrollmentStatus",
                   coalesce(requirement_summary.status,'Pending') AS "requirementsStatus"
            FROM enrollments e JOIN students st ON st.id=e.student_id
            JOIN applications a ON a.id=e.application_id
            """+REQUIREMENT_STATUS_JOIN+" WHERE e.section_id IN (:sections) AND e.status IN ('provisional','enrolled') ORDER BY e.section_id,st.last_name,st.first_name,st.id",
                Map.of("sections",sectionIds));
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> advisoryRosters(long academicYearId,long termId){
        var yearTerm=yearTerm(academicYearId,termId);
        var rows=db.queryForList("""
            SELECT s.id,s.name,s.grade_level AS "gradeLevel",s.room,s.campus_id AS "campusId",
                   s.adviser_id AS "adviserId",u.full_name AS adviser,s.version
            FROM sections s JOIN users u ON u.id=s.adviser_id
            WHERE s.term_id=:term AND s.adviser_id=:adviser AND s.campus_id IN (:scope)
            ORDER BY s.name,s.id
            """,Map.of("term",termId,"adviser",identity().userId(),"scope",scope("advisory.read")));
        var sectionIds=rows.stream().map(row->number(row,"id")).toList();
        var students=advisoryStudents(sectionIds);
        Map<Long,List<Map<String,Object>>> bySection=new HashMap<>();
        for(var student:students)bySection.computeIfAbsent(number(student,"sectionId"),ignored->new ArrayList<>()).add(student);
        for(var section:rows){
            section.put("students",bySection.getOrDefault(number(section,"id"),List.of()));
            section.remove("campusId");
        }
        var result=new LinkedHashMap<String,Object>(yearTerm);
        result.put("adviser",Map.of("id",identity().userId(),"name",identity().fullName()));
        result.put("sections",rows);return result;
    }

    /**
     * Read-only grade matrix for one advisory section: every subject of the section against each student, for
     * one grading period. Only submitted and finalized grades appear, so a teacher's working drafts never
     * reach the adviser. Without gradingPeriodId the term's first period is used.
     */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> advisoryClassRecords(long academicYearId,long termId,long sectionId,Long gradingPeriodId){
        var yearTerm=yearTerm(academicYearId,termId);
        var section=db.queryForList("""
            SELECT s.id,s.name,s.grade_level AS "gradeLevel" FROM sections s
            WHERE s.id=:section AND s.term_id=:term AND s.adviser_id=:adviser AND s.campus_id IN (:scope)
            """,Map.of("section",sectionId,"term",termId,"adviser",identity().userId(),"scope",scope("advisory.read")));
        if(section.isEmpty())throw error(HttpStatus.NOT_FOUND,"Advisory section not found.");
        var periods=db.queryForList("SELECT id,code FROM grading_periods WHERE term_id=:term ORDER BY sequence,id",Map.of("term",termId));
        if(periods.isEmpty())throw error(HttpStatus.CONFLICT,"No grading periods are set up for this term.");
        var period=gradingPeriodId==null?periods.getFirst():periods.stream().filter(item->number(item,"id")==gradingPeriodId).findFirst()
                .orElseThrow(()->error(HttpStatus.BAD_REQUEST,"Grading period does not belong to the term."));
        long periodId=number(period,"id");
        var subjects=db.queryForList("""
            SELECT co.id AS "classId",sub.code,sub.name FROM class_offerings co JOIN subjects sub ON sub.id=co.subject_id
            WHERE co.section_id=:section AND co.term_id=:term ORDER BY sub.name,co.id
            """,Map.of("section",sectionId,"term",termId));
        var students=db.queryForList("""
            SELECT e.id AS "enrollmentId",st.student_number AS "studentNumber",
                   concat_ws(' ',st.first_name,st.middle_name,st.last_name,st.suffix) AS name
            FROM enrollments e JOIN students st ON st.id=e.student_id
            WHERE e.section_id=:section AND e.status IN ('provisional','enrolled')
            ORDER BY st.last_name,st.first_name,st.id
            """,Map.of("section",sectionId));
        var grades=db.queryForList("""
            SELECT g.enrollment_id AS "enrollmentId",g.class_id AS "classId",g.value,g.mark,g.state
            FROM grades g JOIN class_offerings co ON co.id=g.class_id
            WHERE co.section_id=:section AND g.grading_period_id=:period AND g.state IN ('submitted','finalized')
            """,Map.of("section",sectionId,"period",periodId));
        Map<Long,Map<String,Object>> byStudent=new HashMap<>();
        for(var student:students){var own=new LinkedHashMap<String,Object>();student.put("grades",own);byStudent.put(number(student,"enrollmentId"),own);}
        for(var grade:grades){
            var own=byStudent.get(number(grade,"enrollmentId"));if(own==null)continue; // a student who has left the section
            var cell=new LinkedHashMap<String,Object>();cell.put("value",grade.get("value"));cell.put("mark",grade.get("mark"));cell.put("state",grade.get("state"));
            own.put(String.valueOf(number(grade,"classId")),cell);
        }
        var result=new LinkedHashMap<String,Object>(yearTerm);
        result.put("section",section.getFirst());result.put("periods",periods);result.put("period",period);
        result.put("subjects",subjects);result.put("students",students);return result;
    }

    private static final String WITHDRAWAL_FROM="""
        FROM withdrawal_clearances wc JOIN withdrawals w ON w.id=wc.withdrawal_id
        JOIN enrollments e ON e.id=w.enrollment_id JOIN sections s ON s.id=e.section_id
        JOIN students st ON st.id=e.student_id
        WHERE wc.office='adviser' AND wc.status='pending' AND w.status='pending'
          AND s.adviser_id=:adviser AND e.campus_id IN (:scope)
        """;

    private void paging(int page,int size){
        if(page<0||page>1000000||size<1||size>100)
            throw error(HttpStatus.BAD_REQUEST,"Use a nonnegative page and a size of 1 to 100.");
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> withdrawalEndorsements(int page,int size){
        paging(page,size);
        var params=Map.<String,Object>of("adviser",identity().userId(),"scope",scope("advisory.read"),
                "limit",size,"offset",(long)page*size);
        long total=db.queryForObject("SELECT count(*) "+WITHDRAWAL_FROM,params,Long.class);
        var items=db.queryForList("""
            SELECT w.id AS "withdrawalId",w.enrollment_id AS "enrollmentId",w.reason,
                   w.requested_at AS "requestedAt",e.term_id AS "termId",s.id AS "sectionId",s.name AS section,
                   st.student_number AS "studentNumber",concat_ws(' ',st.first_name,st.middle_name,st.last_name,st.suffix) AS student,
                   wc.version
            """+WITHDRAWAL_FROM+" ORDER BY w.requested_at,w.id LIMIT :limit OFFSET :offset",params);
        return Map.of("items",items,"total",total,"page",page,"size",size);
    }

    @Transactional
    public Map<String,Object> decideWithdrawal(long withdrawalId,WithdrawalDecision input){
        return withdrawals.legacyAdviser(withdrawalId,input);
    }
}
