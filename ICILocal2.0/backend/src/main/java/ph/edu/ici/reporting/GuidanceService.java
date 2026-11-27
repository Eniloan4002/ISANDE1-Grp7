package ph.edu.ici.reporting;

import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.*;
import static ph.edu.ici.reporting.GuidanceController.*;

@Service
public class GuidanceService {
    private final NamedParameterJdbcTemplate db;private final AccessPolicy access;private final ReportService reports;
    public GuidanceService(NamedParameterJdbcTemplate db,AccessPolicy access,ReportService reports){this.db=db;this.access=access;this.reports=reports;}
    private ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    private long actor(){return ((StaffIdentity)SecurityContextHolder.getContext().getAuthentication().getPrincipal()).userId();}
    private void audit(){db.queryForObject("SELECT set_config('app.actor_id',:actor,true)",Map.of("actor",Long.toString(actor())),String.class);}
    private List<Long> scope(String permission){var s=access.campuses(permission);if(s.isEmpty())throw error(HttpStatus.FORBIDDEN,"A permission-bearing campus grant is required.");return s;}
    private void allowed(long campus,String permission){if(!access.campuses(permission).contains(campus))throw error(HttpStatus.FORBIDDEN,"Campus is outside your authorized scope.");}
    private void version(Map<String,Object> row,int expected){if(((Number)row.getOrDefault("version",0)).intValue()!=expected)throw error(HttpStatus.CONFLICT,"This record changed. Reload before saving.");}
    private Map<String,Object> term(long id,boolean lock){var rows=db.queryForList("SELECT id,starts_on,ends_on FROM academic_terms WHERE id=:id"+(lock?" FOR UPDATE":""),Map.of("id",id));if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Academic term not found.");return rows.getFirst();}
    private LocalDate date(Object value){return value instanceof LocalDate d?d:((java.sql.Date)value).toLocalDate();}
    private Map<String,Object> rawPolicy(long campus,long term){var rows=db.queryForList("SELECT failing_below AS \"failingBelow\",consecutive_absences AS \"consecutiveAbsences\",credentials_due_on AS \"credentialsDueOn\",reason,version FROM guidance_policies WHERE campus_id=:campus AND term_id=:term",Map.of("campus",campus,"term",term));return rows.isEmpty()?new LinkedHashMap<>(Map.of("version",0)):rows.getFirst();}
    @Transactional(readOnly=true)
    public Map<String,Object> policy(long campus,long term){
        if(!access.campuses("guidance.manage").contains(campus)&&!access.campuses("guidance.configure").contains(campus))throw error(HttpStatus.FORBIDDEN,"Campus is outside your guidance scope.");term(term,false);
        var result=new LinkedHashMap<>(rawPolicy(campus,term));result.put("configured",((Number)result.get("version")).intValue()>0);result.put("campusId",campus);result.put("termId",term);return result;
    }
    @Transactional
    public Map<String,Object> policy(long campus,long term,PolicyInput input){
        allowed(campus,"guidance.configure");term(term,true);version(rawPolicy(campus,term),input.version());audit();
        var params=new MapSqlParameterSource("campus",campus).addValue("term",term).addValue("fail",input.failingBelow()).addValue("absences",input.consecutiveAbsences()).addValue("due",input.credentialsDueOn()).addValue("reason",input.reason().trim());
        db.update("""
            INSERT INTO guidance_policies(campus_id,term_id,failing_below,consecutive_absences,credentials_due_on,reason)
            VALUES(:campus,:term,:fail,:absences,:due,:reason) ON CONFLICT(campus_id,term_id) DO UPDATE SET
            failing_below=EXCLUDED.failing_below,consecutive_absences=EXCLUDED.consecutive_absences,credentials_due_on=EXCLUDED.credentials_due_on,reason=EXCLUDED.reason,version=guidance_policies.version+1
            """,params);return policy(campus,term);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> calendar(long campus,long term){
        boolean permitted=List.of("guidance.manage","guidance.configure","enrollment.manage","grades.encode").stream().anyMatch(p->access.campuses(p).contains(campus));
        if(!permitted)throw error(HttpStatus.FORBIDDEN,"Campus is outside your calendar scope.");term(term,false);
        return Map.of("campusId",campus,"termId",term,"items",db.queryForList("SELECT school_date AS date,is_school_day AS \"schoolDay\",reason,version FROM school_calendar_days WHERE campus_id=:campus AND term_id=:term ORDER BY school_date",Map.of("campus",campus,"term",term)));
    }
    @Transactional
    public Map<String,Object> calendar(long campus,long term,CalendarInput input){
        allowed(campus,"guidance.configure");var parent=term(term,true);var seen=new HashSet<LocalDate>();audit();
        for(var day:input.days().stream().sorted(Comparator.comparing(CalendarDay::date)).toList()){
            if(!seen.add(day.date()))throw error(HttpStatus.BAD_REQUEST,"Each date must appear only once.");
            if(day.date().isBefore(date(parent.get("starts_on")))||day.date().isAfter(date(parent.get("ends_on"))))throw error(HttpStatus.BAD_REQUEST,"Calendar dates must belong to the term.");
            var p=new MapSqlParameterSource("campus",campus).addValue("term",term).addValue("date",day.date()).addValue("school",day.schoolDay()).addValue("reason",input.reason().trim());
            var existing=db.queryForList("SELECT version FROM school_calendar_days WHERE campus_id=:campus AND term_id=:term AND school_date=:date FOR UPDATE",p);version(existing.isEmpty()?Map.of("version",0):existing.getFirst(),day.version());
            db.update("INSERT INTO school_calendar_days(campus_id,term_id,school_date,is_school_day,reason) VALUES(:campus,:term,:date,:school,:reason) ON CONFLICT(campus_id,term_id,school_date) DO UPDATE SET is_school_day=EXCLUDED.is_school_day,reason=EXCLUDED.reason,version=school_calendar_days.version+1",p);
        }return calendar(campus,term);
    }
    private Map<String,Object> enrollment(long id,boolean mutation,boolean guidanceOnly){
        var rows=db.queryForList("SELECT e.*,st.student_number,concat_ws(' ',st.first_name,st.middle_name,st.last_name) AS name FROM enrollments e JOIN students st ON st.id=e.student_id WHERE e.id=:id"+(mutation?" FOR UPDATE OF e":""),Map.of("id",id));
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Enrollment not found.");var row=rows.getFirst();long campus=((Number)row.get("campus_id")).longValue();
        boolean permitted=guidanceOnly?access.campuses("guidance.manage").contains(campus):access.campuses("enrollment.manage").contains(campus)||(!mutation&&access.campuses("guidance.manage").contains(campus));
        if(!permitted&&!guidanceOnly&&access.campuses("grades.encode").contains(campus))permitted=Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM class_memberships cm JOIN class_offerings co ON co.id=cm.class_id WHERE cm.enrollment_id=:id AND co.teacher_id=:actor)",Map.of("id",id,"actor",actor()),Boolean.class));
        if(!permitted)throw error(HttpStatus.NOT_FOUND,"Enrollment not found.");return row;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> attendance(long id){enrollment(id,false,false);return Map.of("enrollmentId",id,"items",db.queryForList("SELECT attendance_date AS date,status,note,version,updated_by AS \"recordedBy\",updated_at AS \"recordedAt\" FROM daily_academic_attendance WHERE enrollment_id=:id ORDER BY attendance_date",Map.of("id",id)));}
    @Transactional
    public Map<String,Object> attendance(AttendanceImport input){
        record CalendarKey(long campus,long term,LocalDate date){}
        var seen=new HashSet<String>();audit();
        var days=new TreeSet<CalendarKey>(Comparator.comparingLong(CalendarKey::campus).thenComparingLong(CalendarKey::term).thenComparing(CalendarKey::date));
        // All shared calendar locks precede enrollment locks, in calendar-edit order.
        for(var item:input.entries()){
            if(!seen.add(item.enrollmentId()+":"+item.date()))throw error(HttpStatus.BAD_REQUEST,"Duplicate enrollment/date in import.");
            var e=enrollment(item.enrollmentId(),false,false);
            days.add(new CalendarKey(((Number)e.get("campus_id")).longValue(),((Number)e.get("term_id")).longValue(),item.date()));
        }
        for(var day:days){
            var rows=db.queryForList("SELECT is_school_day FROM school_calendar_days WHERE campus_id=:campus AND term_id=:term AND school_date=:date FOR UPDATE",Map.of("campus",day.campus(),"term",day.term(),"date",day.date()));
            if(rows.isEmpty()||!Boolean.TRUE.equals(rows.getFirst().get("is_school_day")))throw error(HttpStatus.BAD_REQUEST,"Attendance requires an explicitly configured school day.");
        }
        for(var item:input.entries().stream().sorted(Comparator.comparing(AttendanceEntry::enrollmentId).thenComparing(AttendanceEntry::date)).toList()){
            var e=enrollment(item.enrollmentId(),true,false);if(!Set.of("provisional","enrolled").contains(e.get("status")))throw error(HttpStatus.CONFLICT,"Attendance requires an active enrollment.");
            var p=new MapSqlParameterSource("enrollment",item.enrollmentId()).addValue("date",item.date()).addValue("campus",e.get("campus_id")).addValue("term",e.get("term_id")).addValue("status",item.status()).addValue("note",item.note());
            var existing=db.queryForList("SELECT version FROM daily_academic_attendance WHERE enrollment_id=:enrollment AND attendance_date=:date FOR UPDATE",p);version(existing.isEmpty()?Map.of("version",0):existing.getFirst(),item.version());
            db.update("INSERT INTO daily_academic_attendance(enrollment_id,attendance_date,status,note) VALUES(:enrollment,:date,:status,:note) ON CONFLICT(enrollment_id,attendance_date) DO UPDATE SET status=EXCLUDED.status,note=EXCLUDED.note,version=daily_academic_attendance.version+1",p);
        }return Map.of("saved",input.entries().size());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> risks(ReportFilter filter,LocalDate asOf,int page,int size){
        if(page<0||size<1||size>100)throw error(HttpStatus.BAD_REQUEST,"Use page >= 0 and size between 1 and 100.");
        LocalDate day=asOf==null?db.queryForObject("SELECT current_date",Map.of(),LocalDate.class):asOf;
        boolean guidanceRole=access.hasPermission("guidance.manage");
        var candidates=reports.bounded(filter,guidanceRole?"guidance.manage":"advisory.read");
        if(!guidanceRole){
            var adviserCampuses=access.campuses("advisory.read");
            var ownedSections=adviserCampuses.isEmpty()?Set.<Long>of():new HashSet<>(db.queryForList("SELECT id FROM sections WHERE adviser_id=:actor AND campus_id IN (:scope)",Map.of("actor",actor(),"scope",adviserCampuses),Long.class));
            candidates=candidates.stream().filter(r->r.get("sectionId")!=null&&ownedSections.contains(((Number)r.get("sectionId")).longValue())).toList();
        }
        if(candidates.isEmpty())return Map.of("items",List.of(),"total",0,"atRiskTotal",0,"page",page,"size",size,"asOf",day);
        var ids=candidates.stream().map(r->((Number)r.get("enrollmentId")).longValue()).toList();
        var grades=db.queryForList("SELECT enrollment_id,value,mark FROM grades WHERE enrollment_id IN (:ids) AND state IN ('submitted','finalized')"+(filter.gradingPeriodId()==null?"":" AND grading_period_id=:period"),new MapSqlParameterSource("ids",ids).addValue("period",filter.gradingPeriodId()));
        var attendance=db.queryForList("SELECT enrollment_id,attendance_date,status FROM daily_academic_attendance WHERE enrollment_id IN (:ids) AND attendance_date<=:day",Map.of("ids",ids,"day",day));
        var caseRows=db.queryForList("SELECT id,enrollment_id,status,version FROM guidance_cases WHERE enrollment_id IN (:ids)",Map.of("ids",ids));
        var gradeMap=new HashMap<Long,List<Map<String,Object>>>();for(var g:grades)gradeMap.computeIfAbsent(((Number)g.get("enrollment_id")).longValue(),k->new ArrayList<>()).add(g);
        var attendanceMap=new HashMap<Long,Map<LocalDate,String>>();for(var a:attendance)attendanceMap.computeIfAbsent(((Number)a.get("enrollment_id")).longValue(),k->new HashMap<>()).put(date(a.get("attendance_date")),a.get("status").toString());
        var caseMap=new HashMap<Long,Map<String,Object>>();for(var c:caseRows)caseMap.put(((Number)c.get("enrollment_id")).longValue(),c);
        var policyCache=new HashMap<String,Map<String,Object>>();var calendarCache=new HashMap<String,Map<LocalDate,Boolean>>();var riskRows=new ArrayList<Map<String,Object>>();
        for(var row:candidates){
            if(!Set.of("provisional","enrolled").contains(row.get("status")))continue;
            long id=((Number)row.get("enrollmentId")).longValue(),campus=((Number)row.get("campusId")).longValue(),term=((Number)row.get("termId")).longValue();String key=campus+":"+term;
            var policy=policyCache.computeIfAbsent(key,k->rawPolicy(campus,term));var signals=new ArrayList<String>();var unconfigured=new ArrayList<String>();
            var marks=gradeMap.getOrDefault(id,List.of());long inc=marks.stream().filter(g->"INC".equals(g.get("mark"))).count();if(inc>0)signals.add("incomplete_grades");
            long failures=0;if(policy.get("failingBelow")==null)unconfigured.add("failing_grade_threshold");else{double threshold=((Number)policy.get("failingBelow")).doubleValue();failures=marks.stream().filter(g->g.get("value")!=null&&((Number)g.get("value")).doubleValue()<threshold).count();if(failures>0)signals.add("failing_grades");}
            if(policy.get("credentialsDueOn")==null)unconfigured.add("credential_due_date");else if(day.isAfter(date(policy.get("credentialsDueOn")))&&((Number)row.get("missingRequirements")).intValue()>0)signals.add("overdue_credentials");
            int consecutive=0;boolean calendarComplete=true;
            if(policy.get("consecutiveAbsences")==null)unconfigured.add("consecutive_absence_threshold");else{
                var calendar=calendarCache.computeIfAbsent(key,k->{var m=new HashMap<LocalDate,Boolean>();for(var d:db.queryForList("SELECT school_date,is_school_day FROM school_calendar_days WHERE campus_id=:campus AND term_id=:term AND school_date<=:day",Map.of("campus",campus,"term",term,"day",day)))m.put(date(d.get("school_date")),(Boolean)d.get("is_school_day"));return m;});
                LocalDate start=date(row.get("termStartsOn")),end=day.isBefore(date(row.get("termEndsOn")))?day:date(row.get("termEndsOn"));
                if(java.time.temporal.ChronoUnit.DAYS.between(start,end)>3660)throw error(HttpStatus.BAD_REQUEST,"Guidance calendar evaluation is limited to ten-year terms.");
                var records=attendanceMap.getOrDefault(id,Map.of());
                for(LocalDate cursor=start;!cursor.isAfter(end);cursor=cursor.plusDays(1)){
                    Boolean school=calendar.get(cursor);if(school==null){calendarComplete=false;consecutive=0;}else if(school){consecutive="absent".equals(records.get(cursor))?consecutive+1:0;}
                }
                if(!calendarComplete)unconfigured.add("school_calendar_gaps");if(consecutive>=((Number)policy.get("consecutiveAbsences")).intValue())signals.add("consecutive_unexcused_absences");
            }
            var item=new LinkedHashMap<String,Object>(row);item.put("signals",signals);item.put("unconfiguredSignals",unconfigured);item.put("incompleteGrades",inc);item.put("failingGrades",failures);item.put("consecutiveUnexcusedAbsences",consecutive);item.put("referredToGuidance",caseMap.containsKey(id));item.put("case",guidanceRole?caseMap.get(id):null);item.put("atRisk",!signals.isEmpty());riskRows.add(item);
        }
        int offset=(int)Math.min((long)page*size,riskRows.size());return Map.of("items",riskRows.subList(offset,Math.min(offset+size,riskRows.size())),"total",riskRows.size(),"atRiskTotal",riskRows.stream().filter(r->Boolean.TRUE.equals(r.get("atRisk"))).count(),"page",page,"size",size,"asOf",day);
    }
    private Map<String,Object> caseRow(long id,boolean lock){
        var rows=db.queryForList("SELECT c.*,e.campus_id,e.student_id,st.student_number,concat_ws(' ',st.first_name,st.middle_name,st.last_name) AS name FROM guidance_cases c JOIN enrollments e ON e.id=c.enrollment_id JOIN students st ON st.id=e.student_id WHERE c.id=:id AND e.campus_id IN (:scope)"+(lock?" FOR UPDATE OF c":""),Map.of("id",id,"scope",scope("guidance.manage")));
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Guidance case not found.");return rows.getFirst();
    }
    @Transactional
    public Map<String,Object> createCase(CaseInput input){
        enrollment(input.enrollmentId(),true,true);audit();long id=db.queryForObject("INSERT INTO guidance_cases(enrollment_id,reason) VALUES(:enrollment,:reason) RETURNING id",Map.of("enrollment",input.enrollmentId(),"reason",input.reason().trim()),Long.class);return caseDetail(id);
    }
    @Transactional
    public Map<String,Object> refer(CaseInput input){
        var adviserScope=scope("advisory.read");
        var rows=db.queryForList("""
            SELECT e.id,e.status FROM enrollments e JOIN sections s ON s.id=e.section_id
            WHERE e.id=:enrollment AND s.adviser_id=:actor AND e.campus_id IN (:scope) FOR UPDATE OF e
            """,Map.of("enrollment",input.enrollmentId(),"actor",actor(),"scope",adviserScope));
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Advisory student enrollment not found.");
        if(!Set.of("provisional","enrolled").contains(rows.getFirst().get("status")))throw error(HttpStatus.CONFLICT,"Only active enrollments can be referred to Guidance.");
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM guidance_cases WHERE enrollment_id=:enrollment)",Map.of("enrollment",input.enrollmentId()),Boolean.class)))
            throw error(HttpStatus.CONFLICT,"This student has already been referred to Guidance for this term.");
        audit();
        db.update("INSERT INTO guidance_cases(enrollment_id,reason) VALUES(:enrollment,:reason)",Map.of("enrollment",input.enrollmentId(),"reason",input.reason().trim()));
        return Map.of("referred",true,"enrollmentId",input.enrollmentId());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> cases(String status,Long studentId,int page,int size){
        if(page<0||size<1||size>100)throw error(HttpStatus.BAD_REQUEST,"Use page >= 0 and size between 1 and 100.");if(status!=null&&!Set.of("open","monitoring","closed").contains(status))throw error(HttpStatus.BAD_REQUEST,"Invalid counseling status.");
        var p=new MapSqlParameterSource("scope",scope("guidance.manage")).addValue("status",status).addValue("studentId",studentId).addValue("size",size).addValue("offset",(long)page*size);
        String from=" FROM guidance_cases c JOIN enrollments e ON e.id=c.enrollment_id JOIN students st ON st.id=e.student_id WHERE e.campus_id IN (:scope)"+(status==null?"":" AND c.status=:status")+(studentId==null?"":" AND e.student_id=:studentId");
        return Map.of("items",db.queryForList("SELECT c.id,c.enrollment_id AS \"enrollmentId\",e.student_id AS \"studentId\",c.status,c.reason,c.version,c.created_at AS \"createdAt\",st.student_number AS \"studentNumber\",concat_ws(' ',st.first_name,st.middle_name,st.last_name) AS name"+from+" ORDER BY c.id DESC LIMIT :size OFFSET :offset",p),"total",db.queryForObject("SELECT count(*)"+from,p,Long.class),"page",page,"size",size);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> caseDetail(long id){
        var row=new LinkedHashMap<>(caseRow(id,false));var notes=db.queryForList("SELECT n.id,n.body AS note,n.author_id AS \"authorId\",u.full_name AS author,n.created_at AS \"createdAt\" FROM guidance_case_notes n JOIN users u ON u.id=n.author_id WHERE n.case_id=:id ORDER BY n.id LIMIT 1001",Map.of("id",id));
        if(notes.size()>1000)throw error(HttpStatus.PAYLOAD_TOO_LARGE,"This case has more than 1,000 notes; an archival export is required.");row.put("notes",notes);return row;
    }
    @Transactional
    public Map<String,Object> changeCase(long id,CaseChange input){
        var row=caseRow(id,true);version(row,input.version());audit();db.update("UPDATE guidance_cases SET status=:status,reason=:reason,version=version+1 WHERE id=:id",Map.of("id",id,"status",input.status(),"reason",input.reason().trim()));return caseDetail(id);
    }
    @Transactional
    public Map<String,Object> note(long id,NoteInput input){
        var row=caseRow(id,true);version(row,input.version());audit();db.update("INSERT INTO guidance_case_notes(case_id,body,author_id) VALUES(:id,:note,:actor)",Map.of("id",id,"note",input.note().trim(),"actor",actor()));db.update("UPDATE guidance_cases SET version=version+1 WHERE id=:id",Map.of("id",id));return caseDetail(id);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public byte[] exportCase(long id,String format){
        var item=caseDetail(id);var rows=new ArrayList<List<Object>>();rows.add(List.of("Case",id));rows.add(List.of("Student",item.get("name")));rows.add(List.of("Student number",item.get("student_number")));rows.add(List.of("Status",item.get("status")));rows.add(List.of("Reason",item.get("reason")));
        for(var note:(List<Map<String,Object>>)item.get("notes"))rows.add(List.of(note.get("createdAt")+" / "+note.get("author"),note.get("note")));
        if(format.equals("csv"))return ReportDocuments.csv(List.of("Field / author","Value"),rows);
        if(format.equals("pdf"))return ReportDocuments.pdf("Confidential guidance case "+id,rows.stream().map(r->r.get(0)+": "+r.get(1)).toList());
        throw error(HttpStatus.BAD_REQUEST,"Export format must be csv or pdf.");
    }
}
