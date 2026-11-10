package ph.edu.ici.enrollment;

import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.StaffIdentity;
import ph.edu.ici.auth.AccessPolicy;
import ph.edu.ici.requirements.RequirementService;
import static ph.edu.ici.enrollment.EnrollmentController.*;

@Service
public class EnrollmentService {
    private final NamedParameterJdbcTemplate db;
    private final AccessPolicy accessPolicy;
    private final RequirementService requirements;
    private static final com.fasterxml.jackson.databind.ObjectMapper CATALOG_JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    public EnrollmentService(NamedParameterJdbcTemplate db,RequirementService requirements,AccessPolicy accessPolicy){this.accessPolicy=accessPolicy;this.db=db;this.requirements=requirements;}
    private StaffIdentity identity(){return (StaffIdentity)SecurityContextHolder.getContext().getAuthentication().getPrincipal();}
    private List<Long> scope(){return accessPolicy.campuses("enrollment.manage");}
    private ResponseStatusException error(HttpStatus status,String text){return new ResponseStatusException(status,text);}
    private void actor(){db.queryForObject("SELECT set_config('app.actor_id',:actor,true)",Map.of("actor",Long.toString(identity().userId())),String.class);}
    private void version(Map<String,Object> row,int expected){if(((Number)row.get("version")).intValue()!=expected)throw error(HttpStatus.CONFLICT,"This record changed in another session. Reload before saving.");}
    private long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private void paging(int page,int size){if(page<0||page>1000000||size<1||size>100)throw error(HttpStatus.BAD_REQUEST,"Use a nonnegative page and a size of 1 to 100.");}
    private Map<String,Object> application(long id,boolean lock){
        var rows=db.queryForList("SELECT * FROM applications WHERE id=:id AND campus_id IN (:scope)"+(lock?" FOR UPDATE":""),Map.of("id",id,"scope",scope()));
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Application not found.");return rows.getFirst();
    }
    @Transactional(readOnly=true)
    public Map<String,Object> reference(){return reference(false);}
    @Transactional(readOnly=true)
    public Map<String,Object> reference(boolean includeVouchers){
        // One wire exchange for ordered catalog rows; no cross-request authorization cache.
        var result=new LinkedHashMap<String,Object>();
        for(String key:List.of("campuses","programs","terms","advisers"))result.put(key,new ArrayList<Map<String,Object>>());
        if(includeVouchers)result.put("vouchers",new ArrayList<Map<String,Object>>());
        db.query("""
            SELECT 'campuses' AS kind,row_to_json(r)::text AS payload FROM (SELECT id,code,name FROM campuses WHERE active AND id IN (:scope) ORDER BY name) r
            UNION ALL SELECT 'programs',row_to_json(r)::text FROM (SELECT id,code,name,category FROM programs ORDER BY name) r
            UNION ALL SELECT 'terms',row_to_json(r)::text FROM (SELECT t.id,t.name,t.code,y.label AS "academicYear",t.starts_on AS "startsOn",t.ends_on AS "endsOn" FROM academic_terms t JOIN academic_years y ON y.id=t.academic_year_id ORDER BY t.starts_on DESC,t.id DESC) r
            UNION ALL SELECT 'advisers',row_to_json(r)::text FROM (SELECT DISTINCT u.id,u.full_name AS name,g.campus_id AS "campusId" FROM users u JOIN user_role_grants g ON g.user_id=u.id WHERE u.active AND g.role_code='ROLE_ADVISER' AND g.campus_id IN (:scope) ORDER BY u.full_name) r
            UNION ALL SELECT 'vouchers',row_to_json(r)::text FROM (SELECT code,name FROM vouchers WHERE :vouchers ORDER BY code) r
            """,Map.of("scope",scope(),"vouchers",includeVouchers),rs->{
                try{
                    @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)result.get(rs.getString("kind"));
                    rows.add(CATALOG_JSON.readValue(rs.getString("payload"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));
                }catch(java.io.IOException e){throw new IllegalStateException("Invalid catalog data",e);}
            });
        return result;
    }

    public Map<String,Object> window(long campus,long term){
        if(!scope().contains(campus))throw error(HttpStatus.NOT_FOUND,"Campus not found.");
        var rows=db.queryForList("SELECT starts_on AS \"startsOn\",ends_on AS \"endsOn\",approval_note AS \"approvalNote\",orientation_starts_on AS \"orientationStartsOn\",orientation_ends_on AS \"orientationEndsOn\",version FROM enrollment_attendance_windows WHERE campus_id=:campus AND term_id=:term",Map.of("campus",campus,"term",term));
        if(rows.isEmpty())return Map.of("configured",false,"version",0);var result=new LinkedHashMap<>(rows.getFirst());result.put("configured",true);return result;
    }
    @Transactional public Map<String,Object> saveWindow(long campus,long term,WindowInput input){
        if(!scope().contains(campus))throw error(HttpStatus.NOT_FOUND,"Campus not found.");
        if(input.endsOn().isBefore(input.startsOn()))throw error(HttpStatus.BAD_REQUEST,"End date must be on or after the start date.");
        if((input.orientationStartsOn()==null)!=(input.orientationEndsOn()==null)||input.orientationStartsOn()!=null&&input.orientationEndsOn().isBefore(input.orientationStartsOn()))throw error(HttpStatus.BAD_REQUEST,"Supply both orientation dates in chronological order, or leave both unset.");
        // Serialize policy edits with attendance/confirmation at the campus-term level.
        db.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))",Map.of("key","attendance-window:"+campus+":"+term),Object.class);
        var current=window(campus,term);if(((Number)current.get("version")).intValue()!=input.version())throw error(HttpStatus.CONFLICT,"Attendance dates changed. Reload before saving.");
        var p=new HashMap<String,Object>();p.put("campus",campus);p.put("term",term);p.put("start",input.startsOn());p.put("end",input.endsOn());p.put("note",input.approvalNote().trim());p.put("orientationStart",input.orientationStartsOn());p.put("orientationEnd",input.orientationEndsOn());
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM enrollments e JOIN applications a ON a.id=e.application_id WHERE e.campus_id=:campus AND e.term_id=:term AND e.status='enrolled' AND NOT EXISTS(SELECT 1 FROM orientation_attendance o WHERE o.application_id=a.id AND o.status='present' AND (o.attendance_date BETWEEN :start AND :end OR o.attendance_date BETWEEN :orientationStart AND :orientationEnd)))",p,Boolean.class)))throw error(HttpStatus.CONFLICT,"These dates would invalidate confirmed attendance. Preserve the approved historical window.");
        actor();db.update("INSERT INTO enrollment_attendance_windows(campus_id,term_id,starts_on,ends_on,approval_note,orientation_starts_on,orientation_ends_on) VALUES(:campus,:term,:start,:end,:note,:orientationStart,:orientationEnd) ON CONFLICT(campus_id,term_id) DO UPDATE SET starts_on=:start,ends_on=:end,approval_note=:note,orientation_starts_on=:orientationStart,orientation_ends_on=:orientationEnd,version=enrollment_attendance_windows.version+1",p);return window(campus,term);
    }
    private void policyLock(Map<String,Object> a){db.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))",Map.of("key","attendance-window:"+a.get("campus_id")+":"+a.get("term_id")),Object.class);}
    private boolean present(Map<String,Object> a){return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM orientation_attendance o LEFT JOIN enrollment_attendance_windows w ON w.campus_id=:campus AND w.term_id=:term WHERE o.application_id=:id AND o.status='present' AND (w.campus_id IS NULL OR (o.attendance_date BETWEEN w.starts_on AND w.ends_on OR o.attendance_date BETWEEN w.orientation_starts_on AND w.orientation_ends_on)))",Map.of("id",a.get("id"),"campus",a.get("campus_id"),"term",a.get("term_id")),Boolean.class));}

    private static final String APPLICATION_FROM="""
        FROM applications a JOIN campuses c ON c.id=a.campus_id JOIN programs p ON p.id=a.program_id
        JOIN academic_terms t ON t.id=a.term_id JOIN academic_years y ON y.id=t.academic_year_id
        LEFT JOIN students st ON st.id=a.student_id
        LEFT JOIN enrollments e ON e.application_id=a.id LEFT JOIN sections s ON s.id=e.section_id
        CROSS JOIN LATERAL (
          SELECT CASE WHEN count(*) FILTER(WHERE rt.mandatory)=0 THEN 'Pending'
            WHEN count(*) FILTER(WHERE rt.mandatory AND coalesce(ar.status,'pending')<>'submitted')=0 THEN 'Complete'
            WHEN count(*) FILTER(WHERE rt.mandatory AND ar.status IN ('missing','invalid'))>0 THEN 'Incomplete' ELSE 'Pending' END AS status,max(ar.updated_at) AS updated_at
          FROM requirement_types rt LEFT JOIN application_requirements ar ON ar.requirement_code=rt.code AND ar.application_id=a.id
        ) creds
        """;
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> applications(String search,Long campus,Long term,Long program,String completeness,String status,String district,String route,int page,int size){
        paging(page,size);
        if(search.length()>200||district.length()>200||route.length()>200)throw error(HttpStatus.BAD_REQUEST,"Search values must be at most 200 characters.");
        if(!List.of("","Complete","Incomplete","Pending").contains(completeness)||!List.of("","submitted","duplicate_flag","under_review","pre_enlisted","enrolled","rejected","withdrawn").contains(status))throw error(HttpStatus.BAD_REQUEST,"Unknown application or requirement status.");
        Map<String,Object> params=new HashMap<>();params.put("scope",scope());params.put("limit",size);params.put("offset",(long)page*size);
        String where=" WHERE a.campus_id IN (:scope)";
        if(campus!=null){where+=" AND a.campus_id=:campus";params.put("campus",campus);}
        if(term!=null){where+=" AND a.term_id=:term";params.put("term",term);}
        if(program!=null){where+=" AND a.program_id=:program";params.put("program",program);}
        if(!search.isBlank()){where+=" AND (strpos(lower(concat_ws(' ',a.application_number,a.first_name,a.last_name,a.lrn,st.student_number)),:search)>0)";params.put("search",search.trim().toLowerCase(Locale.ROOT));}
        if(!completeness.isBlank()){where+=" AND creds.status=:completeness";params.put("completeness",completeness);}
        if(!status.isBlank()){where+=" AND a.status=:status";params.put("status",status);}
        if(!district.isBlank()){where+=" AND strpos(lower(coalesce(a.profile->>'district','')),:district)>0";params.put("district",district.trim().toLowerCase(Locale.ROOT));}
        if(!route.isBlank()){where+=" AND strpos(lower(coalesce(a.profile->>'transportRoute','')),:route)>0";params.put("route",route.trim().toLowerCase(Locale.ROOT));}
        var items=db.queryForList("""
            SELECT count(*) OVER() AS "_total",a.id,a.application_number AS "applicationNumber",concat_ws(' ',a.first_name,a.last_name) AS name,
              a.campus_id AS "campusId",c.name AS campus,a.term_id AS "termId",concat(y.label,' / ',t.name) AS term,
              a.program_id AS "programId",p.code AS program,a.status,a.version,
              a.first_name AS "firstName",a.middle_name AS "middleName",a.last_name AS "lastName",a.lrn,a.category,
              a.submitted_at::date AS "submittedAt",a.updated_at AS "updatedAt",p.category AS "programCategory",
              CASE WHEN p.category='TVET' THEN 'TVET' WHEN p.code LIKE 'TVL_%' THEN 'TVL' ELSE 'ACADEMIC' END AS track,
              a.student_id AS "studentId",st.student_number AS "studentNumber",a.contact_phone AS phone,a.email,
              coalesce(a.profile->>'gradeLevel','') AS "gradeLevel",coalesce(a.profile->>'district','') AS district,
              coalesce(a.profile->>'transportRoute','') AS "transportRoute",creds.status AS "requirementsStatus",creds.updated_at AS "requirementsUpdatedAt",
              EXISTS(SELECT 1 FROM orientation_attendance o WHERE o.application_id=a.id AND o.status='present') AS "attendancePresent",
              e.id AS "enrollmentId",e.version AS "enrollmentVersion",e.status AS "enrollmentStatus",
              e.section_id AS "sectionId",s.name AS section
            """+APPLICATION_FROM+where+" ORDER BY a.submitted_at DESC,a.id DESC LIMIT :limit OFFSET :offset",params);
        // Carry the filtered total with the page in the same database exchange.
        // An out-of-range page has no carrier row, so retain an exact-count fallback.
        long total=items.isEmpty()?(page==0?0:db.queryForObject("SELECT count(*) "+APPLICATION_FROM+where,params,Long.class)):((Number)items.getFirst().get("_total")).longValue();
        for(var item:items)item.remove("_total");
        return Map.of("items",items,"total",total,"page",page,"size",size);
    }
    private List<String> blockers(Map<String,Object> app,boolean complete,boolean present){
        boolean active=app.get("student_id")==null||Boolean.TRUE.equals(db.queryForObject("SELECT active FROM students WHERE id=:id",Map.of("id",app.get("student_id")),Boolean.class));
        boolean duplicate=Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM duplicate_cases WHERE (application_id=:id OR matched_application_id=:id) AND status='open')",Map.of("id",app.get("id")),Boolean.class));
        return blockers(app,complete,present,active,duplicate);
    }
    private List<String> blockers(Map<String,Object> app,boolean complete,boolean present,boolean active,boolean duplicate){
        List<String> result=new ArrayList<>();
        if(!"pre_enlisted".equals(app.get("status")))result.add("Application must be Pre-Enlisted.");
        if(app.get("student_id")==null)result.add("Link a verified student master record through intake first.");
        else if(!active)result.add("The linked student is archived.");
        if(!complete)result.add("Submit every mandatory credential.");
        if(!present)result.add("Record present orientation / first-week attendance.");
        if(duplicate)result.add("Resolve open duplicate cases first.");
        return result;
    }
    private Map<String,Object> assemble(Map<String,Object> app){
        long id=number(app,"id");
        var checklist=requirements.checklistForApplication(app);
        var attendance=db.queryForList("SELECT attendance_date AS date,status,version,recorded_by AS \"recordedBy\",recorded_at AS \"recordedAt\" FROM orientation_attendance WHERE application_id=:id ORDER BY attendance_date DESC",Map.of("id",id));
        var flags=db.queryForMap("""
            SELECT EXISTS(SELECT 1 FROM orientation_attendance o LEFT JOIN enrollment_attendance_windows w
              ON w.campus_id=:campus AND w.term_id=:term WHERE o.application_id=:id AND o.status='present'
              AND (w.campus_id IS NULL OR o.attendance_date BETWEEN w.starts_on AND w.ends_on
                   OR o.attendance_date BETWEEN w.orientation_starts_on AND w.orientation_ends_on)) AS present,
              EXISTS(SELECT 1 FROM students WHERE id=:student AND active) AS active,
              EXISTS(SELECT 1 FROM duplicate_cases WHERE (application_id=:id OR matched_application_id=:id) AND status='open') AS duplicate
            """,new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("id",id).addValue("campus",app.get("campus_id")).addValue("term",app.get("term_id")).addValue("student",app.get("student_id")));
        boolean present=Boolean.TRUE.equals(flags.get("present"));
        var enrollment=db.queryForList("SELECT e.id,e.status,e.version,e.section_id AS \"sectionId\",s.name AS section,e.confirmed_at AS \"confirmedAt\",e.confirmed_by AS \"confirmedBy\" FROM enrollments e LEFT JOIN sections s ON s.id=e.section_id WHERE e.application_id=:id",Map.of("id",id));
        var blockers=blockers(app,checklist.summary().credentialsComplete(),present,Boolean.TRUE.equals(flags.get("active")),Boolean.TRUE.equals(flags.get("duplicate")));
        Map<String,Object> result=new LinkedHashMap<>();
        var summary=new LinkedHashMap<String,Object>(Map.of("id",id,"applicationNumber",app.get("application_number"),"name",app.get("first_name")+" "+app.get("last_name"),"status",app.get("status"),"version",app.get("version"),"campusId",app.get("campus_id"),"termId",app.get("term_id"),"programId",app.get("program_id")));
        summary.put("phone",app.get("contact_phone"));summary.put("email",app.get("email"));result.put("application",summary);
        result.put("attendanceWindow",window(number(app,"campus_id"),number(app,"term_id")));result.put("attendance",attendance);result.put("attendancePresent",present);result.put("checklist",checklist);
        result.put("enrollment",enrollment.isEmpty()?null:enrollment.getFirst());result.put("blockers",blockers);result.put("canConfirm",blockers.isEmpty());return result;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> detail(long id){return assemble(application(id,false));}
    // Package-local composition avoids reading the same scoped application twice.
    Map<String,Object> detailForApplication(Map<String,Object> app){
        if(!scope().contains(number(app,"campus_id")))throw error(HttpStatus.NOT_FOUND,"Application not found.");
        return assemble(app);
    }


    @Transactional
    public Map<String,Object> attendance(long id,LocalDate date,AttendanceInput input){
        var app=application(id,true);policyLock(app);
        var dates=window(number(app,"campus_id"),number(app,"term_id"));
        boolean firstWeek=Boolean.TRUE.equals(dates.get("configured"))&&!date.isBefore(LocalDate.parse(dates.get("startsOn").toString()))&&!date.isAfter(LocalDate.parse(dates.get("endsOn").toString()));
        boolean orientation=dates.get("orientationStartsOn")!=null&&!date.isBefore(LocalDate.parse(dates.get("orientationStartsOn").toString()))&&!date.isAfter(LocalDate.parse(dates.get("orientationEndsOn").toString()));
        if(Boolean.TRUE.equals(dates.get("configured"))&&!firstWeek&&!orientation)throw error(HttpStatus.BAD_REQUEST,"Attendance date is outside the approved orientation/first-week window.");
        if(date.isAfter(LocalDate.now()))throw error(HttpStatus.BAD_REQUEST,"Attendance cannot be recorded for a future date.");
        if(List.of("rejected","withdrawn").contains(app.get("status")))throw error(HttpStatus.CONFLICT,"Attendance cannot be edited for a closed application.");
        var params=new HashMap<String,Object>();params.put("id",id);params.put("date",date);
        var rows=db.queryForList("SELECT status,version FROM orientation_attendance WHERE application_id=:id AND attendance_date=:date",params);
        if(rows.isEmpty()){if(input.version()!=0)throw error(HttpStatus.CONFLICT,"Attendance changed. Reload before saving.");}
        else {version(rows.getFirst(),input.version());if(input.status().equals(rows.getFirst().get("status")))return assemble(app);}
        if("enrolled".equals(app.get("status"))&&!"present".equals(input.status())&&Boolean.FALSE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM orientation_attendance WHERE application_id=:id AND attendance_date<>:date AND status='present')",params,Boolean.class)))throw error(HttpStatus.CONFLICT,"A confirmed enrollment must retain verified present attendance.");
        actor();params.put("status",input.status());params.put("actor",identity().userId());
        db.update("""
          INSERT INTO orientation_attendance(application_id,attendance_date,status,recorded_by,version)
          VALUES(:id,:date,:status,:actor,1)
          ON CONFLICT(application_id,attendance_date) DO UPDATE SET status=:status,recorded_by=:actor,recorded_at=current_timestamp,version=orientation_attendance.version+1
          """,params);
        return assemble(app);
    }
    @Transactional
    public Map<String,Object> confirm(long id,int expected){
        var app=application(id,true);policyLock(app);version(app,expected);
        if("enrolled".equals(app.get("status")))return assemble(app);
        boolean complete=requirements.checklist(id).summary().credentialsComplete();
        boolean present=present(app);
        var blockers=blockers(app,complete,present);
        if(!blockers.isEmpty())throw error(HttpStatus.CONFLICT,String.join(" ",blockers));
        // Lock the master identity so archive operations cannot race confirmation.
        var student=db.queryForList("SELECT active FROM students WHERE id=:id FOR UPDATE",Map.of("id",app.get("student_id"))).getFirst();
        if(!Boolean.TRUE.equals(student.get("active")))throw error(HttpStatus.CONFLICT,"The linked student is archived.");
        actor();var params=new HashMap<String,Object>(app);params.put("actor",identity().userId());
        var existing=db.queryForList("SELECT id,status FROM enrollments WHERE application_id=:id FOR UPDATE",params);
        if(existing.isEmpty())db.update("""
          INSERT INTO enrollments(student_id,application_id,campus_id,term_id,program_id,status,attendance_verified_at,attendance_verified_by,confirmed_at,confirmed_by)
          VALUES(:student_id,:id,:campus_id,:term_id,:program_id,'enrolled',current_timestamp,:actor,current_timestamp,:actor)
          """,params);
        else{
            if(!"provisional".equals(existing.getFirst().get("status")))throw error(HttpStatus.CONFLICT,"Only provisional enrollments can be confirmed.");
            db.update("UPDATE enrollments SET status='enrolled',attendance_verified_at=current_timestamp,attendance_verified_by=:actor,confirmed_at=current_timestamp,confirmed_by=:actor,version=version+1 WHERE application_id=:id",params);
        }
        db.update("UPDATE applications SET status='enrolled',version=version+1 WHERE id=:id",params);
        return assemble(application(id,false));
    }

    private static final String SECTION_SELECT="""
        SELECT s.id,s.campus_id AS "campusId",c.name AS campus,s.term_id AS "termId",concat(y.label,' / ',t.name) AS term,
          s.program_id AS "programId",p.code AS program,s.name,s.grade_level AS "gradeLevel",s.room,s.capacity,
          s.adviser_id AS "adviserId",u.full_name AS adviser,s.version,
          (SELECT count(*) FROM enrollments e WHERE e.section_id=s.id AND e.status IN ('provisional','enrolled')) AS occupied,
          s.capacity-(SELECT count(*) FROM enrollments e WHERE e.section_id=s.id AND e.status IN ('provisional','enrolled')) AS available
        FROM sections s JOIN campuses c ON c.id=s.campus_id JOIN academic_terms t ON t.id=s.term_id
        JOIN academic_years y ON y.id=t.academic_year_id JOIN programs p ON p.id=s.program_id LEFT JOIN users u ON u.id=s.adviser_id
        """;
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> sections(Long campus,Long term,Long program,int page,int size){
        paging(page,size);Map<String,Object> params=new HashMap<>();params.put("scope",scope());params.put("limit",size);params.put("offset",(long)page*size);
        String where=" WHERE s.campus_id IN (:scope)";
        if(campus!=null){where+=" AND s.campus_id=:campus";params.put("campus",campus);}
        if(term!=null){where+=" AND s.term_id=:term";params.put("term",term);}
        if(program!=null){where+=" AND s.program_id=:program";params.put("program",program);}
        long total=db.queryForObject("SELECT count(*) FROM sections s"+where,params,Long.class);
        return Map.of("items",db.queryForList(SECTION_SELECT+where+" ORDER BY s.name,s.id LIMIT :limit OFFSET :offset",params),"total",total,"page",page,"size",size);
    }
    public Map<String,Object> section(long id){
        var rows=db.queryForList(SECTION_SELECT+" WHERE s.id=:id AND s.campus_id IN (:scope)",Map.of("id",id,"scope",scope()));
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Section not found.");return rows.getFirst();
    }
    private Map<String,Object> lockSection(long id){
        var rows=db.queryForList("SELECT * FROM sections WHERE id=:id AND campus_id IN (:scope) FOR UPDATE",Map.of("id",id,"scope",scope()));
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Section not found.");return rows.getFirst();
    }
    private Map<String,Object> sectionParams(SectionInput input){
        if(!scope().contains(input.campusId()))throw error(HttpStatus.NOT_FOUND,"Campus not found.");
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM campuses WHERE id=:id AND active)",Map.of("id",input.campusId()),Boolean.class)))throw error(HttpStatus.BAD_REQUEST,"Choose an active campus.");
        if(input.adviserId()!=null&&!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM users u JOIN user_role_grants g ON g.user_id=u.id WHERE u.id=:id AND u.active AND g.role_code='ROLE_ADVISER' AND g.campus_id=:campus)",Map.of("id",input.adviserId(),"campus",input.campusId()),Boolean.class)))throw error(HttpStatus.BAD_REQUEST,"Choose an active adviser assigned to this campus.");
        Map<String,Object> p=new HashMap<>();p.put("campus",input.campusId());p.put("term",input.termId());p.put("program",input.programId());p.put("name",input.name().trim());p.put("grade",input.gradeLevel().trim());p.put("room",input.room()==null?null:input.room().trim());p.put("capacity",input.capacity());p.put("adviser",input.adviserId());return p;
    }
    @Transactional
    public Map<String,Object> createSection(SectionInput input){
        var p=sectionParams(input);actor();
        long id=db.queryForObject("INSERT INTO sections(campus_id,term_id,program_id,name,grade_level,room,capacity,adviser_id) VALUES(:campus,:term,:program,:name,:grade,:room,:capacity,:adviser) RETURNING id",p,Long.class);
        return section(id);
    }
    @Transactional
    public Map<String,Object> updateSection(long id,SectionInput input){
        var old=lockSection(id);if(input.version()==null)throw error(HttpStatus.BAD_REQUEST,"Version is required for edits.");version(old,input.version());
        if(number(old,"campus_id")!=input.campusId()||number(old,"term_id")!=input.termId()||number(old,"program_id")!=input.programId())throw error(HttpStatus.BAD_REQUEST,"Campus, term and program are fixed. Create a new section for another scope.");
        if(!Objects.equals(old.get("grade_level"),input.gradeLevel().trim())&&Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM enrollments WHERE section_id=:id)",Map.of("id",id),Boolean.class)))throw error(HttpStatus.CONFLICT,"A referenced section's grade level cannot change.");
        long occupied=db.queryForObject("SELECT count(*) FROM enrollments WHERE section_id=:id AND status IN ('provisional','enrolled')",Map.of("id",id),Long.class);
        if(input.capacity()<occupied)throw error(HttpStatus.CONFLICT,"Capacity cannot be less than occupied seats.");
        var p=sectionParams(input);p.put("id",id);actor();db.update("UPDATE sections SET name=:name,grade_level=:grade,room=:room,capacity=:capacity,adviser_id=:adviser,version=version+1 WHERE id=:id",p);return section(id);
    }
    @Transactional
    public void deleteSection(long id,int expected){var row=lockSection(id);version(row,expected);actor();db.update("DELETE FROM sections WHERE id=:id",Map.of("id",id));}
    @Transactional
    public Map<String,Object> assign(long id,AssignmentInput input){
        var rows=db.queryForList("SELECT application_id FROM enrollments WHERE id=:id AND campus_id IN (:scope)",Map.of("id",id,"scope",scope()));
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Enrollment not found.");
        var app=application(number(rows.getFirst(),"application_id"),true);
        var enrollment=db.queryForList("SELECT * FROM enrollments WHERE id=:id FOR UPDATE",Map.of("id",id)).getFirst();version(enrollment,input.version());
        if(!"enrolled".equals(enrollment.get("status")))throw error(HttpStatus.CONFLICT,"Confirm enrollment before allocating a section.");
        Object old=enrollment.get("section_id");if(Objects.equals(old,input.sectionId()))return assemble(app);
        // Stable order prevents deadlocks for concurrent swaps between sections.
        var ids=new TreeSet<Long>();if(old!=null)ids.add(((Number)old).longValue());if(input.sectionId()!=null)ids.add(input.sectionId());
        Map<String,Object> target=null;for(long sectionId:ids){var row=lockSection(sectionId);if(Objects.equals(sectionId,input.sectionId()))target=row;}
        if(target!=null){
            if(number(target,"campus_id")!=number(enrollment,"campus_id")||number(target,"term_id")!=number(enrollment,"term_id")||number(target,"program_id")!=number(enrollment,"program_id"))throw error(HttpStatus.CONFLICT,"Section must match the enrollment's campus, term and program.");
            String grade=db.queryForObject("SELECT coalesce(profile->>'gradeLevel','') FROM applications WHERE id=:id",Map.of("id",app.get("id")),String.class);
            if(grade==null||grade.isBlank())throw error(HttpStatus.CONFLICT,"Record the applicant's gradeLevel through intake before section assignment.");
            if(!normalizeGrade(grade).equals(normalizeGrade((String)target.get("grade_level"))))throw error(HttpStatus.CONFLICT,"Section grade level does not match this applicant.");
            long count=db.queryForObject("SELECT count(*) FROM enrollments WHERE section_id=:id AND status IN ('provisional','enrolled')",Map.of("id",target.get("id")),Long.class);
            if(count>=number(target,"capacity"))throw error(HttpStatus.CONFLICT,"This section is full. Choose another section.");
        }
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM class_memberships WHERE enrollment_id=:id)",Map.of("id",id),Boolean.class)))throw error(HttpStatus.CONFLICT,"This enrollment has academic class memberships. Use the academic transfer workflow to preserve grades.");
        actor();Map<String,Object> p=new HashMap<>();p.put("id",id);p.put("section",input.sectionId());db.update("UPDATE enrollments SET section_id=:section,version=version+1 WHERE id=:id",p);return assemble(app);
    }
    private String normalizeGrade(String grade){return grade.trim().toLowerCase(Locale.ROOT).replaceFirst("^grade\\s+","");}
}
