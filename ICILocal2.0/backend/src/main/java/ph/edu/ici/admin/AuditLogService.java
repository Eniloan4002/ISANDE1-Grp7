package ph.edu.ici.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.AccessPolicy;

/**
 * Read-only audit log inspection service for System Administrators.
 * Queries the append-only audit_logs table with campus-scoped visibility and filters.
 */
@Service
public class AuditLogService {
    private static final Set<String> SENSITIVE_KEYS = Set.of("password_hash", "token_hash", "password");
    private static final List<String> ALLOWED_OPERATIONS = List.of("", "INSERT", "UPDATE", "DELETE");

    private final NamedParameterJdbcTemplate db;
    private final AccessPolicy accessPolicy;
    private final ObjectMapper json;

    public AuditLogService(NamedParameterJdbcTemplate db, AccessPolicy accessPolicy, ObjectMapper json) {
        this.db = db;
        this.accessPolicy = accessPolicy;
        this.json = json;
    }

    private List<Long> scope() {
        return accessPolicy.campuses("users.manage");
    }

    private ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }

    private Map<String, Object> parseJsonPayload(Object raw) {
        if (raw == null) return null;
        String text = raw.toString();
        if (text.isBlank()) return null;
        try {
            Map<String, Object> parsed = json.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {});
            for (String key : SENSITIVE_KEYS) {
                parsed.remove(key);
            }
            return parsed;
        } catch (Exception e) {
            return Map.of("_raw", text);
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> referenceData() {
        var campusesScope = scope();
        if (campusesScope.isEmpty()) {
            return Map.of(
                "campuses", List.of(),
                "users", List.of(),
                "recordTypes", List.of(),
                "operations", List.of("INSERT", "UPDATE", "DELETE")
            );
        }
        var campuses = db.queryForList(
            "SELECT id, code, name FROM campuses WHERE active AND id IN (:scope) ORDER BY name",
            Map.of("scope", campusesScope)
        );
        var users = db.queryForList("""
            SELECT DISTINCT u.id, u.username, u.full_name AS "fullName"
            FROM users u
            JOIN user_role_grants g ON g.user_id = u.id
            WHERE g.campus_id IN (:scope)
            ORDER BY u.full_name, u.username
            """, Map.of("scope", campusesScope));
        var recordTypes = db.queryForList(
            "SELECT DISTINCT table_name FROM audit_logs ORDER BY table_name",
            Map.of(),
            String.class
        );
        return Map.of(
            "campuses", campuses,
            "users", users,
            "recordTypes", recordTypes,
            "operations", List.of("INSERT", "UPDATE", "DELETE")
        );
    }

    private static final String AUDIT_FROM = """
        FROM audit_logs a
        LEFT JOIN users u ON u.id = a.actor_id
        LEFT JOIN LATERAL (
          SELECT COALESCE(
            CASE WHEN coalesce(a.new_data, a.old_data)->>'campus_id' ~ '^[0-9]+$'
                 THEN (coalesce(a.new_data, a.old_data)->>'campus_id')::bigint END,
            (SELECT app.campus_id FROM applications app
              WHERE coalesce(a.new_data, a.old_data)->>'application_id' ~ '^[0-9]+$'
                AND app.id = (coalesce(a.new_data, a.old_data)->>'application_id')::bigint),
            (SELECT en.campus_id FROM enrollments en
              WHERE coalesce(a.new_data, a.old_data)->>'enrollment_id' ~ '^[0-9]+$'
                AND en.id = (coalesce(a.new_data, a.old_data)->>'enrollment_id')::bigint),
            (SELECT sec.campus_id FROM sections sec
              WHERE coalesce(a.new_data, a.old_data)->>'section_id' ~ '^[0-9]+$'
                AND sec.id = (coalesce(a.new_data, a.old_data)->>'section_id')::bigint),
            (SELECT st.campus_id FROM students st
              WHERE coalesce(a.new_data, a.old_data)->>'student_id' ~ '^[0-9]+$'
                AND st.id = (coalesce(a.new_data, a.old_data)->>'student_id')::bigint)
          ) AS campus_id
        ) ctx ON true
        LEFT JOIN campuses c ON c.id = ctx.campus_id
        """;

    @Transactional(readOnly = true)
    public Map<String, Object> list(
        LocalDate from,
        LocalDate to,
        Long actorId,
        String recordType,
        Long campusId,
        String operation,
        String search,
        int page,
        int size
    ) {
        if (page < 0 || page > 1000000 || size < 1 || size > 100) {
            throw error(HttpStatus.BAD_REQUEST, "Use a nonnegative page and a size of 1 to 100.");
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw error(HttpStatus.BAD_REQUEST, "End date must be on or after the start date.");
        }
        String normalizedOp = operation == null ? "" : operation.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_OPERATIONS.contains(normalizedOp)) {
            throw error(HttpStatus.BAD_REQUEST, "Operation must be INSERT, UPDATE, or DELETE.");
        }
        String normalizedType = recordType == null ? "" : recordType.trim();
        if (normalizedType.length() > 100) {
            throw error(HttpStatus.BAD_REQUEST, "Record type filter is too long.");
        }
        String normalizedSearch = search == null ? "" : search.trim();
        if (normalizedSearch.length() > 200) {
            throw error(HttpStatus.BAD_REQUEST, "Search value must be at most 200 characters.");
        }

        var campusesScope = scope();
        if (campusesScope.isEmpty()) {
            return Map.of("items", List.of(), "total", 0L, "page", page, "size", size);
        }
        if (campusId != null && !campusesScope.contains(campusId)) {
            throw error(HttpStatus.FORBIDDEN, "You are not authorized to inspect audit logs for that campus.");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("scope", campusesScope);
        params.put("limit", size);
        params.put("offset", (long) page * size);

        StringBuilder where = new StringBuilder("""
             WHERE (
               ctx.campus_id IN (:scope)
               OR (ctx.campus_id IS NULL AND (
                 a.actor_id IS NULL
                 OR EXISTS (SELECT 1 FROM user_role_grants g WHERE g.user_id = a.actor_id AND g.campus_id IN (:scope))
               ))
             )
            """);

        if (from != null) {
            where.append(" AND a.occurred_at::date >= :fromDate");
            params.put("fromDate", from);
        }
        if (to != null) {
            where.append(" AND a.occurred_at::date <= :toDate");
            params.put("toDate", to);
        }
        if (actorId != null) {
            where.append(" AND a.actor_id = :actorId");
            params.put("actorId", actorId);
        }
        if (!normalizedType.isBlank()) {
            where.append(" AND a.table_name = :recordType");
            params.put("recordType", normalizedType);
        }
        if (campusId != null) {
            where.append("""
                 AND (
                   ctx.campus_id = :campusId
                   OR (ctx.campus_id IS NULL AND EXISTS (
                     SELECT 1 FROM user_role_grants g WHERE g.user_id = a.actor_id AND g.campus_id = :campusId
                   ))
                 )
                """);
            params.put("campusId", campusId);
        }
        if (!normalizedOp.isBlank()) {
            where.append(" AND a.operation = :operation");
            params.put("operation", normalizedOp);
        }
        if (!normalizedSearch.isBlank()) {
            where.append("""
                 AND strpos(lower(concat_ws(' ', a.table_name, a.record_id, a.operation, u.full_name, u.username, c.name)), :search) > 0
                """);
            params.put("search", normalizedSearch.toLowerCase(Locale.ROOT));
        }

        var rows = db.queryForList("""
            SELECT count(*) OVER() AS "_total",
                   a.id,
                   a.occurred_at AS "occurredAt",
                   a.actor_id AS "actorId",
                   u.username AS "actorUsername",
                   u.full_name AS "actorName",
                   a.operation,
                   a.table_name AS "recordType",
                   a.record_id AS "recordId",
                   ctx.campus_id AS "campusId",
                   c.name AS "campusName",
                   a.old_data::text AS "oldDataRaw",
                   a.new_data::text AS "newDataRaw"
            """ + AUDIT_FROM + where + " ORDER BY a.occurred_at DESC, a.id DESC LIMIT :limit OFFSET :offset", params);

        long total = rows.isEmpty()
            ? (page == 0 ? 0L : db.queryForObject("SELECT count(*) " + AUDIT_FROM + where, params, Long.class))
            : ((Number) rows.getFirst().get("_total")).longValue();

        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (var row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            Object ts = row.get("occurredAt");
            item.put("occurredAt", ts instanceof Timestamp t ? t.toInstant().toString() : Objects.toString(ts, null));
            item.put("actorId", row.get("actorId"));
            item.put("actorUsername", row.get("actorUsername"));
            item.put("actorName", row.get("actorName"));
            item.put("operation", row.get("operation"));
            item.put("recordType", row.get("recordType"));
            item.put("recordId", row.get("recordId"));
            item.put("campusId", row.get("campusId"));
            item.put("campusName", row.get("campusName"));
            item.put("oldData", parseJsonPayload(row.get("oldDataRaw")));
            item.put("newData", parseJsonPayload(row.get("newDataRaw")));
            items.add(item);
        }

        return Map.of("items", items, "total", total, "page", page, "size", size);
    }
}
