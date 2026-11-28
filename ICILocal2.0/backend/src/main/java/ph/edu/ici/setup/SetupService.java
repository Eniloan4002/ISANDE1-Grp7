package ph.edu.ici.setup;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.security.core.context.SecurityContextHolder;
import ph.edu.ici.auth.StaffIdentity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SetupService {
    public enum Kind {
        SUBJECTS("subjects", List.of("code", "name", "units", "active"), "code"),
        YEARS("academic_years", List.of("label", "starts_on", "ends_on"), "starts_on DESC"),
        TERMS("academic_terms", List.of("academic_year_id", "code", "name", "starts_on", "ends_on"), "starts_on"),
        PERIODS("grading_periods", List.of("term_id", "code", "sequence", "starts_on", "ends_on"), "sequence");
        final String table;
        final List<String> columns;
        final String order;
        Kind(String table, List<String> columns, String order) {
            this.table = table; this.columns = columns; this.order = order;
        }
        String select() { return "id," + String.join(",", columns) + ",version,created_at,updated_at,created_by,updated_by"; }
    }

    private final JdbcTemplate jdbc;
    public SetupService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> list(Kind kind, Long parentId) {
        String filter = parentId == null ? "" : switch (kind) {
            case TERMS -> " WHERE academic_year_id=?";
            case PERIODS -> " WHERE term_id=?";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This resource has no parent filter.");
        };
        String sql = "SELECT " + kind.select() + " FROM " + kind.table + filter + " ORDER BY " + kind.order + ",id";
        return parentId == null ? jdbc.queryForList(sql) : jdbc.queryForList(sql, parentId);
    }

    public Map<String, Object> get(Kind kind, long id) {
        return jdbc.queryForList("SELECT " + kind.select() + " FROM " + kind.table + " WHERE id=?", id)
            .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found."));
    }

    @Transactional
    public Map<String, Object> create(Kind kind, Map<String, Object> values) {
        attributeActor();
        if (kind == Kind.PERIODS) checkPeriodDates(values, null);
        // Only enum-owned table and column names enter SQL; all input values are bound.
        List<Object> args = kind.columns.stream().map(values::get).toList();
        String placeholders = String.join(",", kind.columns.stream().map(column -> "?").toList());
        Long id = jdbc.queryForObject("INSERT INTO " + kind.table + " (" + String.join(",", kind.columns)
            + ") VALUES (" + placeholders + ") RETURNING id", Long.class, args.toArray());
        return get(kind, id);
    }

    @Transactional
    public Map<String, Object> update(Kind kind, long id, Integer version, Map<String, Object> values) {
        attributeActor();
        if (version == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The record version is required when saving changes.");
        if (kind == Kind.PERIODS) checkPeriodDates(values, id);
        List<Object> args = new ArrayList<>(kind.columns.stream().map(values::get).toList());
        args.add(id); args.add(version);
        String setters = String.join(",", kind.columns.stream().map(column -> column + "=?").toList());
        int count = jdbc.update("UPDATE " + kind.table + " SET " + setters + ",version=version+1 WHERE id=? AND version=?", args.toArray());
        if (count == 0) { get(kind, id); throw stale(); }
        return get(kind, id);
    }

    @Transactional
    public void delete(Kind kind, long id, int version) {
        attributeActor();
        int count = jdbc.update("DELETE FROM " + kind.table + " WHERE id=? AND version=?", id, version);
        if (count == 0) { get(kind, id); throw stale(); }
    }

    /** A term's grading periods must not overlap, and their dates must follow their sequence order. */
    private void checkPeriodDates(Map<String, Object> values, Long ownId) {
        Object termId = values.get("term_id");
        if (!(values.get("starts_on") instanceof LocalDate starts) || !(values.get("ends_on") instanceof LocalDate ends)) return;
        if (ends.isBefore(starts)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The grading period end date cannot be before its start date.");
        // Serialize concurrent period edits within a term so two requests cannot both pass the check.
        jdbc.queryForList("SELECT id FROM academic_terms WHERE id=? FOR UPDATE", termId);
        long own = ownId == null ? -1 : ownId;
        List<Map<String, Object>> overlaps = jdbc.queryForList("SELECT code FROM grading_periods WHERE term_id=? AND id<>? AND starts_on<=? AND ends_on>=? ORDER BY sequence",
            termId, own, ends, starts);
        if (!overlaps.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "These dates overlap grading period " + overlaps.get(0).get("code") + " in the same term. Grading periods in a term cannot share dates.");
        int sequence = ((Number) values.get("sequence")).intValue();
        List<Map<String, Object>> outOfOrder = jdbc.queryForList("SELECT code FROM grading_periods WHERE term_id=? AND id<>? AND ((sequence<? AND starts_on>?) OR (sequence>? AND ends_on<?)) ORDER BY sequence",
            termId, own, sequence, ends, sequence, starts);
        if (!outOfOrder.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "These dates do not match the sequence order. A grading period with a lower sequence number must come earlier than grading period "
            + outOfOrder.get(0).get("code") + ". Adjust the dates or the sequence number.");
    }

    private ResponseStatusException stale() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "This record changed in another session. Reload it before trying again.");
    }

    private void attributeActor() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication!=null && authentication.getPrincipal() instanceof StaffIdentity identity) {
            jdbc.queryForObject("SELECT set_config('app.actor_id',?,true)",String.class,Long.toString(identity.userId()));
        }
    }

    static Map<String, Object> values(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i=0; i<pairs.length; i+=2) values.put((String)pairs[i], pairs[i+1]);
        return values;
    }
}
