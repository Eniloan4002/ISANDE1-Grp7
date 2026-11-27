package ph.edu.ici.reporting;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
@PreAuthorize("@accessPolicy.hasPermission('reports.read')")
public class ReportController {
    private final ReportService reports;public ReportController(ReportService reports){this.reports=reports;}
    @GetMapping("/filter-options")
    public Map<String,Object> filterOptions(){return reports.filterOptions();}
    @GetMapping("/enrollments")
    public Map<String,Object> enrollments(@ModelAttribute ReportFilter filter,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return reports.preview(filter,page,size);}
    @GetMapping("/enrollments/export")
    public ResponseEntity<byte[]> export(@ModelAttribute ReportFilter filter,@RequestParam(defaultValue="csv") String format){
        byte[] bytes=reports.export(filter,format);return download(bytes,format,"ici-enrollments");
    }
    public static ResponseEntity<byte[]> download(byte[] body,String format,String name){
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+name+"."+format+"\"")
                .header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(format.equals("pdf")?MediaType.APPLICATION_PDF:new MediaType("text","csv",java.nio.charset.StandardCharsets.UTF_8)).body(body);
    }
}
