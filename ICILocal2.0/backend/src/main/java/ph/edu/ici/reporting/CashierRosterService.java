package ph.edu.ici.reporting;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.AccessPolicy;
import ph.edu.ici.auth.StaffIdentity;

/** On-demand reference exports; this service neither calculates nor records payments. */
@Service
public class CashierRosterService {
    public static final int EXPORT_LIMIT = 5000;
    private final NamedParameterJdbcTemplate db;
    private final AccessPolicy access;
    public CashierRosterService(NamedParameterJdbcTemplate db, AccessPolicy access) {
        this.db = db;
        this.access = access;
    }
    private record Query(String sql, MapSqlParameterSource params) {}
    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
    private boolean exists(String sql, MapSqlParameterSource parameters) {
        return Boolean.TRUE.equals(db.queryForObject(sql, parameters, Boolean.class));
    }
    private Query query(CashierRosterFilter filter) {
        var scope = access.campuses("cashier.read");
        if (scope.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "A cashier reference permission-bearing campus grant is required.");
        if (filter.campusId() != null && !scope.contains(filter.campusId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Campus is outside your cashiering scope.");
        for (Long id : Arrays.asList(filter.campusId(), filter.academicYearId(), filter.termId(),
                filter.programId(), filter.sectionId()))
            if (id != null && id <= 0) throw bad("Filter identifiers must be positive.");
        if (filter.gradeLevel() != null && (filter.gradeLevel().isBlank() || filter.gradeLevel().length() > 30))
            throw bad("Grade level must be nonblank and at most 30 characters.");
        var p = new MapSqlParameterSource("scope", scope);
        if (filter.academicYearId() != null && !exists("SELECT EXISTS(SELECT 1 FROM academic_years WHERE id=:year)",
                new MapSqlParameterSource("year", filter.academicYearId())))
            throw bad("Academic year was not found.");
        if (filter.termId() != null) {
            var term = db.queryForList("SELECT academic_year_id FROM academic_terms WHERE id=:term",
                    Map.of("term", filter.termId()));
            if (term.isEmpty()) throw bad("Academic term was not found.");
            if (filter.academicYearId() != null && ((Number)term.getFirst().get("academic_year_id")).longValue() != filter.academicYearId())
                throw bad("Term does not belong to the academic year.");
        }
        if (filter.programId() != null && !exists("SELECT EXISTS(SELECT 1 FROM programs WHERE id=:program)",
                new MapSqlParameterSource("program", filter.programId()))) throw bad("Program was not found.");
        if (filter.sectionId() != null) {
            var sections = db.queryForList("SELECT s.campus_id,s.term_id,s.program_id,t.academic_year_id FROM sections s JOIN academic_terms t ON t.id=s.term_id WHERE s.id=:section AND s.campus_id IN (:scope)",
                    new MapSqlParameterSource("section", filter.sectionId()).addValue("scope", scope));
            if (sections.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Section was not found.");
            var s = sections.getFirst();
            Object[][] checks = {{"campus_id", filter.campusId()}, {"term_id", filter.termId()},
                    {"program_id", filter.programId()}, {"academic_year_id", filter.academicYearId()}};
            for (var check : checks)
                if (check[1] != null && ((Number)s.get(check[0])).longValue() != ((Number)check[1]).longValue())
                    throw bad("Section does not match the selected campus, period or program.");
        }
        if (filter.voucher() != null && !filter.voucher().equals("UNCLASSIFIED") &&
                !exists("SELECT EXISTS(SELECT 1 FROM vouchers WHERE code=:voucher)",
                        new MapSqlParameterSource("voucher", filter.voucher())))
            throw bad("Voucher must be a configured classification or UNCLASSIFIED.");
        String sql = """
            SELECT e.id AS "enrollmentId",st.student_number AS "studentNumber",
                   concat_ws(' ',st.first_name,st.middle_name,st.last_name,st.suffix) AS name,
                   c.id AS "campusId",c.name AS campus,y.id AS "academicYearId",y.label AS "academicYear",
                   t.id AS "termId",t.name AS term,p.id AS "programId",p.code AS program,
                   s.id AS "sectionId",coalesce(s.name,'') AS section,
                   coalesce(s.grade_level,a.profile->>'gradeLevel','') AS "gradeLevel",
                   coalesce(a.voucher_code,st.voucher_code,'UNCLASSIFIED') AS voucher,
                   CASE WHEN coalesce(a.voucher_code,st.voucher_code) IN ('QVR','ESC','ALS')
                        THEN coalesce(a.voucher_code,st.voucher_code)
                        WHEN coalesce(a.voucher_code,st.voucher_code) IS NULL THEN 'UNCLASSIFIED'
                        ELSE 'OTHER' END AS "voucherGroup",
                   e.confirmed_at AS "confirmedAt"
            FROM enrollments e
            JOIN students st ON st.id=e.student_id JOIN applications a ON a.id=e.application_id
            JOIN campuses c ON c.id=e.campus_id JOIN academic_terms t ON t.id=e.term_id
            JOIN academic_years y ON y.id=t.academic_year_id JOIN programs p ON p.id=e.program_id
            LEFT JOIN sections s ON s.id=e.section_id
            WHERE e.status='enrolled' AND e.confirmed_at IS NOT NULL AND e.confirmed_by IS NOT NULL
              AND e.attendance_verified_at IS NOT NULL AND e.attendance_verified_by IS NOT NULL
              AND e.campus_id IN (:scope)
            """;
        Object[][] filters = {{"e.campus_id", "campus", filter.campusId()},
                {"y.id", "year", filter.academicYearId()}, {"e.term_id", "term", filter.termId()},
                {"e.program_id", "program", filter.programId()}, {"e.section_id", "section", filter.sectionId()},
                {"coalesce(s.grade_level,a.profile->>'gradeLevel','')", "grade", filter.gradeLevel()},
                {"coalesce(a.voucher_code,st.voucher_code,'UNCLASSIFIED')", "voucher", filter.voucher()}};
        for (var f : filters) if (f[2] != null) {
            sql += " AND " + f[0] + "=:" + f[1];
            p.addValue(f[1].toString(), f[2]);
        }
        return new Query(sql, p);
    }
    private static final String ORDER = " ORDER BY CASE \"voucherGroup\" WHEN 'QVR' THEN 1 WHEN 'ESC' THEN 2 WHEN 'ALS' THEN 3 WHEN 'OTHER' THEN 4 ELSE 5 END,voucher,\"campusId\",\"termId\",\"studentNumber\",\"enrollmentId\"";

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> preview(CashierRosterFilter filter, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw bad("Use page >= 0 and size between 1 and 100.");
        var q = query(filter);
        long total = db.queryForObject("SELECT count(*) FROM (" + q.sql + ") roster", q.params, Long.class);
        var byCode = db.queryForList("SELECT voucher,\"voucherGroup\",count(*) AS total FROM (" + q.sql + ") roster GROUP BY voucher,\"voucherGroup\" ORDER BY voucher", q.params);
        var groups = new LinkedHashMap<String,Long>();
        for (String group : List.of("QVR","ESC","ALS","OTHER","UNCLASSIFIED")) groups.put(group, 0L);
        for (var row : byCode) groups.merge(row.get("voucherGroup").toString(), ((Number)row.get("total")).longValue(), Long::sum);
        q.params.addValue("limit", size).addValue("offset", (long)page * size);
        var items = db.queryForList("SELECT * FROM (" + q.sql + ") roster" + ORDER + " LIMIT :limit OFFSET :offset", q.params);
        Instant asOf = db.queryForObject("SELECT transaction_timestamp()", Map.of(), java.sql.Timestamp.class).toInstant();
        return Map.of("items", items, "total", total, "voucherGroups", groups, "vouchers", byCode,
                "page", page, "size", size, "asOf", asOf.toString(), "purpose", "cashiering_reference");
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public byte[] export(CashierRosterFilter filter) {
        var q = query(filter);
        var items = db.queryForList("SELECT * FROM (" + q.sql + ") roster" + ORDER + " LIMIT " + (EXPORT_LIMIT + 1), q.params);
        if (items.size() > EXPORT_LIMIT) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                "Narrow the filters: cashiering export is limited to " + EXPORT_LIMIT + " confirmed enrollments.");
        var headers = List.of("Voucher group", "Voucher classification", "Student number", "Name", "Campus",
                "Academic year", "Term", "Program", "Grade level", "Section", "Confirmed at");
        var rows = new ArrayList<List<Object>>();
        for (var item : items) {
            var row = new ArrayList<Object>();
            for (String field : List.of("voucherGroup","voucher","studentNumber","name","campus","academicYear",
                    "term","program","gradeLevel","section","confirmedAt")) row.add(item.get(field));
            rows.add(row);
        }
        byte[] bytes = ReportDocuments.csv(headers, rows);
        String digest;
        try { digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        long actor = ((StaffIdentity)SecurityContextHolder.getContext().getAuthentication().getPrincipal()).userId();
        // Store minimal export provenance, not a second copy of names or confidential student records.
        db.update("INSERT INTO audit_logs(table_name,record_id,operation,new_data,actor_id) VALUES ('cashier_roster_exports',:id,'EXPORT',jsonb_build_object('rows',:rows,'sha256',:digest,'scope',cast(:scope AS jsonb),'campusId',cast(:campus AS bigint),'academicYearId',cast(:year AS bigint),'termId',cast(:term AS bigint),'programId',cast(:program AS bigint),'sectionId',cast(:section AS bigint),'gradeLevel',cast(:grade AS text),'voucher',cast(:voucher AS text)),:actor)",
                new MapSqlParameterSource("id", UUID.randomUUID().toString()).addValue("rows", items.size())
                        .addValue("digest", digest).addValue("scope", access.campuses("cashier.read").toString())
                        .addValue("campus", filter.campusId()).addValue("year", filter.academicYearId()).addValue("term", filter.termId())
                        .addValue("program", filter.programId()).addValue("section", filter.sectionId()).addValue("grade", filter.gradeLevel())
                        .addValue("voucher", filter.voucher()).addValue("actor", actor));
        return bytes;
    }
}
