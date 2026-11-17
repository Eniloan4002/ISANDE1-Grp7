package ph.edu.ici.academic;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@PreAuthorize("@accessPolicy.hasPermission('enrollment.manage')")
public class AcademicHistoryController {
    private final AcademicHistoryService service;
    public AcademicHistoryController(AcademicHistoryService service){this.service=service;}

    @GetMapping("/api/academic/history/students/{studentId}")
    public Map<String,Object> history(@PathVariable long studentId){return service.history(studentId);}
}