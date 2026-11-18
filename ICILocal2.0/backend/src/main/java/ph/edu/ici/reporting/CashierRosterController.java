package ph.edu.ici.reporting;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cashiering/rosters")
@PreAuthorize("@accessPolicy.hasPermission('cashier.read')")
public class CashierRosterController {
    private final CashierRosterService rosters;
    public CashierRosterController(CashierRosterService rosters) { this.rosters = rosters; }

    @GetMapping
    public Map<String, Object> preview(@ModelAttribute CashierRosterFilter filter,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="25") int size) {
        return rosters.preview(filter, page, size);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@ModelAttribute CashierRosterFilter filter) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ici-cashiering-roster.csv\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(rosters.export(filter));
    }
}
