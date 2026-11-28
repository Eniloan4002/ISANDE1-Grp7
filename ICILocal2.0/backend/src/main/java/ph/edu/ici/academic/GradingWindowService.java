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
import static ph.edu.ici.academic.GradingWindowController.*;

/** Registrar controls; section then ordered offering locks serialize with teacher writes. */
@Service
public class GradingWindowService {
    private final NamedParameterJdbcTemplate db;
    private final AccessPolicy access;
    public GradingWindowService(NamedParameterJdbcTemplate db,AccessPolicy access){this.db=db;this.access=access;}
    private ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    private long actor(){return ((StaffIdentity)SecurityContextHolder.getContext().getAuthentication().getPrincipal()).userId();}
    private void audit(){db.queryForObject("SELECT set_config('app.actor_id',:actor,true)",Map.of("actor",Long.toString(actor())),String.class);}
    private List<Long> scope(){
        var campuses=access.campuses("grades.review");
        if(campuses.isEmpty())throw error(HttpStatus.FORBIDDEN,"A grading-review campus grant is required.");
        return campuses;
    }

    private static final String SELECT="""
        SELECT s.id AS "sectionId",s.name AS section,s.campus_id AS "campusId",s.term_id AS "termId",
               s.grade_level AS "gradeLevel",p.id AS "gradingPeriodId",p.code AS period,
               w.deadline,w.locked_at AS "lockedAt",w.locked_by AS "lockedBy",
               w.finalized_at AS "finalizedAt",w.finalized_by AS "finalizedBy",w.reason,
               coalesce(w.version,0) AS version,
               CASE WHEN w.finalized_at IS NOT NULL THEN 'finalized'
                    WHEN w.locked_at IS NOT NULL OR w.deadline<=clock_timestamp() THEN 'locked'
                    ELSE 'open' END AS status
        FROM sections s JOIN grading_periods p ON p.term_id=s.term_id
        LEFT JOIN section_grading_windows w ON w.section_id=s.id AND w.grading_period_id=p.id
        """;

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> list(Long campusId,Long termId,Long sectionId,Long periodId,String gradeLevel,int page,int size){
        if(page<0||size<1||size>100)throw error(HttpStatus.BAD_REQUEST,"Use page >= 0 and size between 1 and 100.");
        var scope=scope();
        if(campusId!=null&&!scope.contains(campusId))throw error(HttpStatus.FORBIDDEN,"Campus is outside your grading scope.");
        var params=new MapSqlParameterSource("scope",scope);
        String where=" WHERE s.campus_id IN (:scope)";
        if(campusId!=null){where+=" AND s.campus_id=:campus";params.addValue("campus",campusId);}
        if(termId!=null){where+=" AND s.term_id=:term";params.addValue("term",termId);}
        if(sectionId!=null){where+=" AND s.id=:section";params.addValue("section",sectionId);}
        if(periodId!=null){where+=" AND p.id=:period";params.addValue("period",periodId);}
        if(gradeLevel!=null&&!gradeLevel.isBlank()){where+=" AND s.grade_level=:grade";params.addValue("grade",gradeLevel.trim());}
        long total=db.queryForObject("SELECT count(*) FROM sections s JOIN grading_periods p ON p.term_id=s.term_id"+where,params,Long.class);
        params.addValue("size",size).addValue("offset",(long)page*size);
        return Map.of("items",db.queryForList(SELECT+where+" ORDER BY s.id,p.sequence,p.id LIMIT :size OFFSET :offset",params),
                "total",total,"page",page,"size",size);
    }

    private Map<String,Object> current(long sectionId,long periodId,boolean lock){
        var scope=scope();
        var params=Map.of("section",sectionId,"period",periodId,"scope",scope);
        if(lock){
            var rows=db.queryForList("SELECT id FROM sections WHERE id=:section AND campus_id IN (:scope) FOR UPDATE",params);
            if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Section grading window not found.");
        }
        var rows=db.queryForList(SELECT+" WHERE s.id=:section AND p.id=:period AND s.campus_id IN (:scope)",params);
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Section grading window not found.");
        return rows.getFirst();
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> detail(long sectionId,long periodId){
        var window=current(sectionId,periodId,false);
        var classes=db.queryForList("""
            SELECT co.id AS "classId",sub.code AS "subjectCode",sub.name AS subject,
                   count(e.id) AS students,
                   count(e.id) FILTER (WHERE g.state='submitted') AS submitted,
                   count(e.id) FILTER (WHERE g.state='finalized') AS finalized
            FROM class_offerings co JOIN subjects sub ON sub.id=co.subject_id
            LEFT JOIN class_memberships cm ON cm.class_id=co.id
            LEFT JOIN enrollments e ON e.id=cm.enrollment_id AND e.status IN ('provisional','enrolled')
            LEFT JOIN grades g ON g.class_id=co.id AND g.enrollment_id=e.id AND g.grading_period_id=:period
            WHERE co.section_id=:section GROUP BY co.id,sub.id ORDER BY co.id
            """,Map.of("section",sectionId,"period",periodId));
        return Map.of("window",window,"classes",classes);
    }

    private void expected(Map<String,Object> window,int version){
        if(((Number)window.get("version")).intValue()!=version)
            throw error(HttpStatus.CONFLICT,"Grading controls changed. Reload before saving.");
    }
    private List<Long> lockClasses(long sectionId){
        return db.queryForList("SELECT id FROM class_offerings WHERE section_id=:section ORDER BY id FOR UPDATE",
                Map.of("section",sectionId),Long.class);
    }
    private void persist(long sectionId,long periodId,Map<String,Object> current,Object deadline,String reason,boolean lock,boolean finalize){
        audit();
        var params=new MapSqlParameterSource().addValue("section",sectionId).addValue("period",periodId)
                .addValue("term",current.get("termId")).addValue("deadline",deadline)
                .addValue("reason",reason.trim()).addValue("actor",actor()).addValue("lock",lock).addValue("finalize",finalize);
        db.update("""
            INSERT INTO section_grading_windows(section_id,grading_period_id,term_id,deadline,reason,locked_at,locked_by,finalized_at,finalized_by)
            VALUES(:section,:period,:term,:deadline,:reason,CASE WHEN :lock THEN clock_timestamp() END,
                   CASE WHEN :lock THEN :actor END,CASE WHEN :finalize THEN clock_timestamp() END,CASE WHEN :finalize THEN :actor END)
            ON CONFLICT(section_id,grading_period_id) DO UPDATE SET deadline=EXCLUDED.deadline,reason=EXCLUDED.reason,
                locked_at=coalesce(section_grading_windows.locked_at,EXCLUDED.locked_at),
                locked_by=coalesce(section_grading_windows.locked_by,EXCLUDED.locked_by),
                finalized_at=coalesce(section_grading_windows.finalized_at,EXCLUDED.finalized_at),
                finalized_by=coalesce(section_grading_windows.finalized_by,EXCLUDED.finalized_by),
                version=section_grading_windows.version+1
            """,params);
    }

    @Transactional
    public Map<String,Object> deadline(long sectionId,long periodId,DeadlineInput input){
        var window=current(sectionId,periodId,true);expected(window,input.version());lockClasses(sectionId);
        // Re-read after waiting on writers: the database wall clock determines cutoff.
        window=current(sectionId,periodId,false);
        if(!"open".equals(window.get("status")))throw error(HttpStatus.CONFLICT,"Closed grading windows cannot be reopened through deadline edits.");
        // Judge against the database wall clock, the same clock that evaluates cutoff. A past deadline would lock
        // teachers out with no locked_at/locked_by; use the lock endpoint to close a window deliberately.
        boolean past=Boolean.TRUE.equals(db.queryForObject("SELECT :deadline::timestamptz<=clock_timestamp()",
                new MapSqlParameterSource("deadline",input.deadline()),Boolean.class));
        if(past)throw error(HttpStatus.BAD_REQUEST,"Deadline must be in the future. Use the lock action to close grading now.");
        persist(sectionId,periodId,window,input.deadline(),input.reason(),false,false);
        return detail(sectionId,periodId);
    }

    @Transactional
    public Map<String,Object> change(long sectionId,long periodId,ChangeInput input,boolean finalize){
        var window=current(sectionId,periodId,true);expected(window,input.version());
        var classes=lockClasses(sectionId);
        List<Map<String,Object>> skipped=List.of();
        if("finalized".equals(window.get("status")))throw error(HttpStatus.CONFLICT,"This grading period is already finalized.");
        if(!finalize&&window.get("lockedAt")!=null)throw error(HttpStatus.CONFLICT,"This grading period is already manually locked.");
        if(finalize){
            if(classes.isEmpty())throw error(HttpStatus.CONFLICT,"Configure classes and submit their grades before finalizing.");
            var params=Map.of("section",sectionId,"period",periodId);
            var incomplete=db.queryForList("""
                SELECT co.id FROM class_offerings co
                LEFT JOIN class_memberships cm ON cm.class_id=co.id
                LEFT JOIN enrollments e ON e.id=cm.enrollment_id AND e.status IN ('provisional','enrolled')
                LEFT JOIN grades g ON g.class_id=co.id AND g.enrollment_id=e.id AND g.grading_period_id=:period
                WHERE co.section_id=:section GROUP BY co.id
                HAVING count(e.id)>0 AND count(e.id)<>count(e.id) FILTER (WHERE g.state IN ('submitted','finalized'))
                """,params);
            if(!incomplete.isEmpty())throw error(HttpStatus.CONFLICT,"Every active class roster must have submitted grades before finalization. No grades were finalized.");
            // Offerings with no active members have nothing to finalize; report them instead of blocking forever.
            skipped=db.queryForList("""
                SELECT co.id AS "classId",sub.code AS "subjectCode",sub.name AS subject
                FROM class_offerings co JOIN subjects sub ON sub.id=co.subject_id
                LEFT JOIN class_memberships cm ON cm.class_id=co.id
                LEFT JOIN enrollments e ON e.id=cm.enrollment_id AND e.status IN ('provisional','enrolled')
                WHERE co.section_id=:section GROUP BY co.id,sub.id HAVING count(e.id)=0 ORDER BY co.id
                """,params);
            audit();
            db.update("""
                UPDATE grades g SET state='finalized',version=g.version+1
                FROM class_offerings co,enrollments e
                WHERE g.class_id=co.id AND co.section_id=:section AND g.grading_period_id=:period
                  AND e.id=g.enrollment_id AND e.status IN ('provisional','enrolled') AND g.state='submitted'
                """,params);
        }
        persist(sectionId,periodId,window,window.get("deadline"),input.reason(),true,finalize);
        var result=detail(sectionId,periodId);
        if(!finalize)return result;
        var withSkipped=new HashMap<String,Object>(result);
        withSkipped.put("skippedClasses",skipped);
        return withSkipped;
    }
}
