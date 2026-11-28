package ph.edu.ici.admin;

import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Read-only System Administrator endpoints for inspecting system audit logs. */
@RestController
@RequestMapping("/api/admin/audit-log")
@PreAuthorize("@accessPolicy.hasPermission('users.manage')")
public class AuditLogController {
    private final AuditLogService service;

    public AuditLogController(AuditLogService service) {
        this.service = service;
    }

    @GetMapping("/reference-data")
    public Map<String, Object> referenceData() {
        return service.referenceData();
    }

    @GetMapping
    public Map<String, Object> list(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) Long actorId,
        @RequestParam(required = false) Long userId,
        @RequestParam(defaultValue = "") String recordType,
        @RequestParam(required = false) Long campusId,
        @RequestParam(defaultValue = "") String operation,
        @RequestParam(defaultValue = "") String search,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        Long effectiveActorId = actorId != null ? actorId : userId;
        return service.list(from, to, effectiveActorId, recordType, campusId, operation, search, page, size);
    }
}
