package ph.edu.ici.imports;

import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import ph.edu.ici.requirements.RequirementService;

/**
 * What one document-log row would do to one application's checklist, decided from live data. Used by staging and again by the commit
 * recheck, so both answer the same question. Read-only. The current state of the item comes from {@link RequirementService#checklist}, the
 * same read the checklist screen uses; nothing that differs from what is recorded is ever applied without an explicit review decision.
 */
@Component
final class DocumentLogPlanner {
    static final String RECORD="record",NOOP="noop";
    /** Conflict kinds that need an {@code apply} decision: a different state, or the same state with different notes. */
    static final String CONFLICT="conflict",NOTES="notes";

    /**
     * @param action      null when the row cannot be applied (see the errors in {@code problems})
     * @param studentId   the application's student (every imported row must name one)
     * @param itemVersion the checklist item's version when planned (0 = never recorded)
     * @param conflict    null, {@link #CONFLICT} or {@link #NOTES}
     */
    record Plan(String action,Long applicationId,String applicationStatus,Long studentId,Integer itemVersion,boolean recorded,String currentStatus,String currentNotes,
                String conflict,boolean regression,List<RowProblem> problems) {
        boolean ok(){return action!=null;}
        static Plan refused(List<RowProblem> problems){return new Plan(null,null,null,null,null,false,null,null,null,false,problems);}
    }
    private final NamedParameterJdbcTemplate db;private final RequirementService requirements;
    DocumentLogPlanner(NamedParameterJdbcTemplate db,RequirementService requirements){this.db=db;this.requirements=requirements;}

    /**
     * @param termId            the row's term (from the file or the upload), or null when the row names its application number
     * @param studentId         the student the row's identifiers resolved to, or null when the row names only an application number
     * @param applicationNumber the row's application number, or null
     * @param status            the state to record, or null when the row's state cell is blank (nothing to record)
     * @param note              the note the import would store (composed by staging)
     */
    Plan plan(long campusId,Long termId,Long studentId,String applicationNumber,String code,String status,String note){
        List<RowProblem> problems=new ArrayList<>();
        Map<String,Object> app;
        if(applicationNumber!=null){
            var found=db.queryForList("SELECT id,student_id,term_id,status FROM applications WHERE application_number=:n AND campus_id=:campus",Map.of("n",applicationNumber,"campus",campusId));
            if(found.isEmpty())return Plan.refused(List.of(RowProblem.error("APPLICATION_NOT_FOUND","applicationNumber","No application with this number exists at this campus. Applications are never created by the import.")));
            app=found.getFirst();
            if(studentId!=null&&!Objects.equals(n(app.get("student_id")),studentId))problems.add(RowProblem.error("CONTEXT_MISMATCH","applicationNumber","The application number belongs to a different student than the row's student number or LRN."));
            if(termId!=null&&!Objects.equals(n(app.get("term_id")),termId))problems.add(RowProblem.error("CONTEXT_MISMATCH","term","The application is for a different term than the row's term."));
        }else{
            if(studentId==null||termId==null)return Plan.refused(List.of(RowProblem.error("APPLICATION_NOT_FOUND",null,"The row names neither an application number nor a student and term.")));
            var found=db.queryForList("SELECT id,student_id,term_id,status FROM applications WHERE student_id=:s AND term_id=:t AND campus_id=:campus ORDER BY id",Map.of("s",studentId,"t",termId,"campus",campusId));
            if(found.isEmpty())return Plan.refused(List.of(RowProblem.error("APPLICATION_NOT_FOUND",null,"The student has no application in this term at this campus. Applications are never created by the import.")));
            if(found.size()>1)return Plan.refused(List.of(RowProblem.error("AMBIGUOUS_APPLICATION",null,"The student has more than one application in this term at this campus. Add an application number column to choose one.")));
            app=found.getFirst();
        }
        if(app.get("student_id")==null)problems.add(RowProblem.error("APPLICATION_NOT_LINKED","applicationNumber","The application is not linked to a student yet. Link it through intake before importing its documents."));
        String appStatus=(String)app.get("status");
        if("rejected".equals(appStatus)||"withdrawn".equals(appStatus))problems.add(RowProblem.error("APPLICATION_CLOSED",null,"The application is "+appStatus+"; its documents are not imported."));
        if(problems.stream().anyMatch(p->RowProblem.ERROR.equals(p.severity())))return Plan.refused(problems);
        long applicationId=n(app.get("id"));Long student=n(app.get("student_id"));
        var item=requirements.checklist(applicationId).items().stream().filter(i->i.code().equals(code)).findFirst().orElse(null);
        if(item==null)return Plan.refused(List.of(RowProblem.error("UNMAPPED_VALUE","document","The document is not in the requirement catalog.")));
        String current=item.status();String notes=item.notes();
        if(status==null){
            problems.add(RowProblem.warning("NOTHING_TO_RECORD","status","The state cell is blank, which means no record; nothing will be written."));
            return new Plan(NOOP,applicationId,appStatus,student,item.version(),item.recorded(),current,notes,null,false,problems);
        }
        if(!item.recorded()){
            if("pending".equals(status)){
                problems.add(RowProblem.warning("NOTHING_TO_RECORD","status","The document is already pending because nothing is recorded for it; nothing will be written."));
                return new Plan(NOOP,applicationId,appStatus,student,item.version(),false,current,notes,null,false,problems);
            }
            return new Plan(RECORD,applicationId,appStatus,student,item.version(),false,current,notes,null,false,problems);
        }
        if(status.equals(current)){
            if(Objects.equals(note,notes)){
                problems.add(RowProblem.warning("ALREADY_RECORDED",null,"The document already has this state and note; nothing will be written."));
                return new Plan(NOOP,applicationId,appStatus,student,item.version(),true,current,notes,null,false,problems);
            }
            problems.add(RowProblem.warning("NOTES_WOULD_CHANGE","note","The document already has this state, but its notes differ and would be replaced. Apply with a reason, or skip the row."));
            return new Plan(RECORD,applicationId,appStatus,student,item.version(),true,current,notes,NOTES,false,problems);
        }
        boolean regression="submitted".equals(current);
        problems.add(RowProblem.warning("CONFLICTS_WITH_RECORDED","status",regression
            ?"The document is recorded as submitted (verified) and the file gives another state, which would remove the verification. Apply with a reason, or skip the row."
            :"The document is recorded with a different state. Apply with a reason, or skip the row."));
        if(regression&&"enrolled".equals(appStatus))problems.add(RowProblem.warning("APPLICATION_ENROLLED",null,"The application is already enrolled; changing a verified document does not undo the enrollment."));
        return new Plan(RECORD,applicationId,appStatus,student,item.version(),true,current,notes,CONFLICT,regression,problems);
    }
    private static Long n(Object value){return value==null?null:((Number)value).longValue();}
}
