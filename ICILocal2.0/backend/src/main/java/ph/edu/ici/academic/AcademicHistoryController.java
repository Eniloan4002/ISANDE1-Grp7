package ph.edu.ici.academic;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@PreAuthorize("@accessPolicy.hasPermission('enrollment.manage') or @accessPolicy.hasPermission('reports.read')")
public class AcademicHistoryController {
    private final AcademicHistoryService service;
    public AcademicHistoryController(AcademicHistoryService service){this.service=service;}

    @GetMapping("/api/academic/history/students")
    public Map<String,Object> students(@RequestParam(defaultValue="") String search,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size){
        return service.students(search,page,size);
    }

    @GetMapping("/api/academic/history/students/{studentId}")
    public Map<String,Object> history(@PathVariable long studentId){return service.history(studentId);}
}