package ph.edu.ici.reporting;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.AccessPolicy;

@Service
public class ReportService {
    public static final int EXPORT_LIMIT=5000;
    private final NamedParameterJdbcTemplate db;private final AccessPolicy access;
    public ReportService(NamedParameterJdbcTemplate db,AccessPolicy access){this.db=db;this.access=access;}
    private ResponseStatusException bad(String text){return new ResponseStatusException(HttpStatus.BAD_REQUEST,text);}
    private record Query(String sql,MapSqlParameterSource params){}
    private Query query(ReportFilter f,String permission){
        var scope=access.campuses(permission);if(scope.isEmpty())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"A permission-bearing campus grant is required.");
        if(f.campusId()!=null&&!scope.contains(f.campusId()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Campus is outside your report scope.");
        if(f.academicYearId()!=null&&f.termId()!=null&&!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM academic_terms WHERE id=:term AND academic_year_id=:year)",Map.of("term",f.termId(),"year",f.academicYearId()),Boolean.class)))throw bad("Term does not belong to the academic year.");
        if(f.gradingPeriodId()!=null&&f.termId()!=null&&!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM grading_periods WHERE id=:period AND term_id=:term)",Map.of("term",f.termId(),"period",f.gradingPeriodId()),Boolean.class)))throw bad("Grading period does not belong to the term.");
        String sql="""
            SELECT e.id AS "enrollmentId",e.student_id AS "studentId",e.application_id AS "applicationId",
                   e.campus_id AS "campusId",e.term_id AS "termId",e.section_id AS "sectionId",e.status,
                   st.student_number AS "studentNumber",concat_ws(' ',st.first_name,st.middle_name,st.last_name,st.suffix) AS name,
                   c.name AS campus,y.label AS "academicYear",y.id AS "academicYearId",t.name AS term,
                   t.starts_on AS "termStartsOn",t.ends_on AS "termEndsOn",s.name AS section,
                   coalesce(s.grade_level,a.profile->>'gradeLevel','') AS "gradeLevel",p.code AS program,
                   coalesce(a.voucher_code,st.voucher_code,'UNCLASSIFIED') AS voucher,
                   req.missing AS "missingRequirements",req.names AS "missingDocuments"
            FROM enrollments e JOIN students st ON st.id=e.student_id JOIN applications a ON a.id=e.application_id
            JOIN campuses c ON c.id=e.campus_id JOIN academic_terms t ON t.id=e.term_id
            JOIN academic_years y ON y.id=t.academic_year_id JOIN programs p ON p.id=e.program_id
            LEFT JOIN sections s ON s.id=e.section_id
            CROSS JOIN LATERAL (
              SELECT count(*) FILTER (WHERE coalesce(ar.status,'missing')<>'submitted') AS missing,
                     coalesce(string_agg(rt.name,', ' ORDER BY rt.code) FILTER (WHERE coalesce(ar.status,'missing')<>'submitted'),'') AS names
              FROM requirement_types rt LEFT JOIN application_requirements ar ON ar.requirement_code=rt.code AND ar.application_id=e.application_id
              WHERE rt.mandatory
            ) req WHERE e.campus_id IN (:scope)
            """;
        var params=new MapSqlParameterSource("scope",scope);
        Object[][] filters={{"e.campus_id","campus",f.campusId()},{"y.id","year",f.academicYearId()},{"e.term_id","term",f.termId()},{"e.section_id","section",f.sectionId()},{"coalesce(s.grade_level,a.profile->>'gradeLevel','')","grade",f.gradeLevel()},{"coalesce(a.voucher_code,st.voucher_code,'UNCLASSIFIED')","voucher",f.voucher()},{"e.status","status",f.enrollmentStatus()}};
        for(var filter:filters)if(filter[2]!=null&&!filter[2].toString().isBlank()){sql+=" AND "+filter[0]+"=:"+filter[1];params.addValue(filter[1].toString(),filter[2]);}
        if(f.gradingPeriodId()!=null){sql+=" AND EXISTS(SELECT 1 FROM grading_periods gp WHERE gp.id=:period AND gp.term_id=e.term_id)";params.addValue("period",f.gradingPeriodId());}
        if(f.sectionId()!=null&&f.termId()!=null){
            var sections=db.queryForList("SELECT term_id FROM sections WHERE id=:id AND campus_id IN (:scope)",new MapSqlParameterSource("id",f.sectionId()).addValue("scope",scope));
            if(sections.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Section not found.");
            if(((Number)sections.getFirst().get("term_id")).longValue()!=f.termId())throw bad("Section does not belong to the selected term.");
        }
        if(f.requirementStatus()!=null&&!f.requirementStatus().isBlank()){
            if(!Set.of("complete","incomplete").contains(f.requirementStatus()))throw bad("Requirement status must be complete or incomplete.");
            sql+=f.requirementStatus().equals("complete")?" AND req.missing=0":" AND req.missing>0";
        }
        return new Query(sql,params);
    }
    /**
     * Choices for the report filter panel, limited to the caller's report campuses. Principals hold only
     * reports.read, so they cannot use the setup or enrollment reference endpoints that registrars use.
     */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> filterOptions(){
        var scope=access.campuses("reports.read");if(scope.isEmpty())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"A permission-bearing campus grant is required.");
        var params=Map.of("scope",scope);
        var enrollmentJoin="""
            FROM enrollments e JOIN students st ON st.id=e.student_id JOIN applications a ON a.id=e.application_id
            LEFT JOIN sections s ON s.id=e.section_id WHERE e.campus_id IN (:scope)
            """;
        var result=new LinkedHashMap<String,Object>();
        result.put("campuses",db.queryForList("SELECT id,name FROM campuses WHERE id IN (:scope) ORDER BY name",params));
        result.put("academicYears",db.queryForList("SELECT id,label FROM academic_years ORDER BY label DESC",Map.of()));
        result.put("terms",db.queryForList("SELECT id,name,academic_year_id AS \"academicYearId\" FROM academic_terms ORDER BY starts_on DESC,id",Map.of()));
        result.put("gradingPeriods",db.queryForList("SELECT id,code,term_id AS \"termId\" FROM grading_periods ORDER BY term_id,sequence",Map.of()));
        result.put("sections",db.queryForList("SELECT id,name,term_id AS \"termId\",campus_id AS \"campusId\" FROM sections WHERE campus_id IN (:scope) ORDER BY name,id",params));
        result.put("gradeLevels",db.queryForList("SELECT DISTINCT coalesce(s.grade_level,a.profile->>'gradeLevel','') AS level "+enrollmentJoin+" ORDER BY 1",params).stream().map(row->row.get("level")).filter(level->level!=null&&!level.toString().isBlank()).toList());
        result.put("vouchers",db.queryForList("SELECT DISTINCT coalesce(a.voucher_code,st.voucher_code,'UNCLASSIFIED') AS code "+enrollmentJoin+" ORDER BY 1",params).stream().map(row->row.get("code")).toList());
        return result;
    }
    public List<Map<String,Object>> bounded(ReportFilter f,String permission){
        var q=query(f,permission);var rows=db.queryForList(q.sql+" ORDER BY e.id LIMIT "+(EXPORT_LIMIT+1),q.params);
        if(rows.size()>EXPORT_LIMIT)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Narrow the filters: this operation is limited to "+EXPORT_LIMIT+" enrollments.");return rows;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> preview(ReportFilter f,int page,int size){
        if(page<0||size<1||size>100)throw bad("Use page >= 0 and size between 1 and 100.");
        var q=query(f,"reports.read");
        var totals=db.queryForMap("SELECT count(*) AS total,count(*) FILTER(WHERE status='enrolled') AS enrolled,count(*) FILTER(WHERE \"missingRequirements\">0) AS incomplete FROM ("+q.sql+") x",q.params);
        var vouchers=db.queryForList("SELECT voucher,count(*) AS total FROM ("+q.sql+") x GROUP BY voucher ORDER BY voucher",q.params);
        q.params.addValue("limit",size).addValue("offset",(long)page*size);
        return Map.of("items",db.queryForList(q.sql+" ORDER BY e.id LIMIT :limit OFFSET :offset",q.params),"total",totals.get("total"),"summary",totals,"vouchers",vouchers,"page",page,"size",size);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public byte[] export(ReportFilter f,String format){
        var items=bounded(f,"reports.read");
        var headers=List.of("Student number","Name","Campus","Academic year","Term","Grade level","Section","Program","Voucher","Standing","Missing documents");
        var rows=new ArrayList<List<Object>>();var lines=new ArrayList<String>();lines.add("Matching enrollments: "+items.size());
        for(var item:items){var row=new ArrayList<Object>();for(String k:List.of("studentNumber","name","campus","academicYear","term","gradeLevel","section","program","voucher","status","missingDocuments"))row.add(item.get(k));rows.add(row);lines.add(String.join(" | ",row.stream().map(v->v==null?"":v.toString()).toList()));}
        if(format.equals("csv"))return ReportDocuments.csv(headers,rows);
        if(format.equals("pdf"))return ReportDocuments.pdf("ICI operational enrollment report",lines);
        throw bad("Export format must be csv or pdf.");
    }
}
