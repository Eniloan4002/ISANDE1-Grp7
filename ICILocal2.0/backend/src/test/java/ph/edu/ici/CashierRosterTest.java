package ph.edu.ici;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class CashierRosterTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> PostgresSupport.DATABASE.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres"); r.add("spring.datasource.password", () -> "");
        r.add("ici.auth.secret", () -> PostgresSupport.SECRET); r.add("ici.bootstrap.enabled", () -> false);
    }
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate db;
    @Autowired PasswordEncoder passwords;
    long campus, foreignCampus, program, year, term, section, cashier, first;
    String cashierToken, principalToken, mixedToken;
    String key() { return UUID.randomUUID().toString().replace("-", "").substring(0,12); }
    long staff(String role, long campus) {
        String username = "cashier-" + key();
        long id = db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES (?,?,?,?) RETURNING id", Long.class,
                username, username + "@example.invalid", username, passwords.encode("Cashier-Roster-Test-2026!"));
        db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,?,?)", id, role, campus);
        return id;
    }
    String login(long user) {
        return (String)rest.postForObject("/api/auth/login", Map.of("username", db.queryForObject("SELECT username FROM users WHERE id=?", String.class, user),
                "password", "Cashier-Roster-Test-2026!"), Map.class).get("accessToken");
    }
    long enrollment(String name, String applicationVoucher, String studentVoucher, String status, long enrollmentCampus) {
        long student = db.queryForObject("INSERT INTO students(student_number,first_name,last_name,birth_date,campus_id,program_id,voucher_code) VALUES (?,?,'Rosterstudent','2008-01-01',?,?,?) RETURNING id", Long.class,
                "STU-" + key(), name, enrollmentCampus, program, studentVoucher);
        long application = db.queryForObject("INSERT INTO applications(application_number,campus_id,term_id,program_id,student_id,category,first_name,last_name,birth_date,voucher_code,profile) VALUES (?,?,?,?,?,'new',?,'Rosterstudent','2008-01-01',?,'{\"gradeLevel\":\"11\"}') RETURNING id", Long.class,
                "APP-" + key(), enrollmentCampus, term, program, student, name, applicationVoucher);
        db.update("INSERT INTO orientation_attendance(application_id,attendance_date,status,recorded_by) VALUES (?,'2026-01-05','present',?)", application, cashier);
        db.update("INSERT INTO application_requirements(application_id,requirement_code,status) SELECT ?,code,'submitted' FROM requirement_types WHERE mandatory", application);
        return db.queryForObject("INSERT INTO enrollments(student_id,application_id,campus_id,term_id,program_id,section_id,status,confirmed_at,confirmed_by,attendance_verified_at,attendance_verified_by) VALUES (?,?,?,?,?,?,?,current_timestamp,?,current_timestamp,?) RETURNING id", Long.class,
                student, application, enrollmentCampus, term, program, enrollmentCampus == campus ? section : null, status, cashier, cashier);
    }
    @BeforeEach void setup() {
        campus = db.queryForObject("SELECT id FROM campuses WHERE code='ILIGAN'", Long.class);
        foreignCampus = db.queryForObject("SELECT id FROM campuses WHERE code='BUGO'", Long.class);
        program = db.queryForObject("SELECT id FROM programs WHERE code='STEM'", Long.class);
        year = db.queryForObject("INSERT INTO academic_years(label,starts_on,ends_on) VALUES (?,'2026-01-01','2026-12-31') RETURNING id", Long.class, "YR" + key());
        term = db.queryForObject("INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES (?,'T1','Cashier test term','2026-01-01','2026-12-31') RETURNING id", Long.class, year);
        section = db.queryForObject("INSERT INTO sections(campus_id,term_id,program_id,name,grade_level,capacity) VALUES (?,?,?,?,'11',10000) RETURNING id", Long.class, campus, term, program, "SEC" + key());
        cashier = staff("ROLE_CASHIER_REF", campus); cashierToken = login(cashier);
        principalToken = login(staff("ROLE_PRINCIPAL", campus));
        long mixed = staff("ROLE_CASHIER_REF", foreignCampus);
        db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,'ROLE_PRINCIPAL',?)", mixed, campus); mixedToken = login(mixed);
        first = enrollment("  =SUM(1,2)\nJosé Ñ", "QVR", "ESC", "enrolled", campus);
        enrollment("Esc", "ESC", null, "enrolled", campus);
        enrollment("Als", "ALS", null, "enrolled", campus);
        enrollment("Paying", "REGULAR", null, "enrolled", campus);
        enrollment("No voucher", null, null, "enrolled", campus);
        enrollment("Provisional hidden", "QVR", null, "provisional", campus);
        enrollment("Withdrawn hidden", "QVR", null, "withdrawn", campus);
        enrollment("Completed hidden", "QVR", null, "completed", campus);
        enrollment("Foreign hidden", "QVR", null, "enrolled", foreignCampus);
    }
    String path() { return "/api/cashiering/rosters?termId=" + term; }
    ResponseEntity<Map> get(String path, String token) {
        var headers = new HttpHeaders(); if (token != null) headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }
    ResponseEntity<byte[]> export(String query, String token) {
        var headers = new HttpHeaders(); headers.setBearerAuth(token);
        return rest.exchange("/api/cashiering/rosters/export" + query, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
    }
    @Test void confirmedRosterIsCategorizedPaginatedAndDoesNotDuplicateRows() {
        var response = get(path() + "&size=2", cashierToken);
        assertThat(response.getStatusCode()).as(response.toString()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("total", 5).containsEntry("purpose", "cashiering_reference");
        assertThat((List)response.getBody().get("items")).hasSize(2);
        assertThat((Map)response.getBody().get("voucherGroups")).containsEntry("QVR",1).containsEntry("ESC",1).containsEntry("ALS",1).containsEntry("OTHER",1).containsEntry("UNCLASSIFIED",1);
        var all = new ArrayList<Map>();
        for (int page = 0; page < 3; page++) all.addAll((List<Map>)get(path() + "&size=2&page=" + page, cashierToken).getBody().get("items"));
        assertThat(all).hasSize(5); assertThat(all.stream().map(row -> row.get("enrollmentId")).distinct().count()).isEqualTo(5);
        assertThat(all.stream().map(row -> row.get("voucherGroup")).toList()).containsExactly("QVR", "ESC", "ALS", "OTHER", "UNCLASSIFIED");
        assertThat(all.getFirst()).containsEntry("voucher", "QVR"); // application classification takes precedence over current student default
        for (Map row : all) assertThat(row.keySet()).doesNotContain("email", "phone", "grade", "missingDocuments", "notes", "birthDate");
    }
    @Test void permissionAndCampusStayBoundToTheSameGrant() {
        assertThat(get(path(), null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get(path(), principalToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(path() + "&campusId=" + foreignCampus, cashierToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(path(), mixedToken).getBody()).containsEntry("total",1);
        assertThat(get(path() + "&campusId=" + campus, mixedToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(path() + "&sectionId=" + section, mixedToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(export("?termId=" + term, principalToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    @Test void combinedFiltersAndValidationAreAppliedBeforeExport() {
        String all = path() + "&academicYearId=" + year + "&programId=" + program + "&sectionId=" + section + "&gradeLevel=11&voucher=ALS&campusId=" + campus;
        assertThat(get(all, cashierToken).getBody()).containsEntry("total",1);
        assertThat(get(path() + "&voucher=UNCLASSIFIED", cashierToken).getBody()).containsEntry("total",1);
        assertThat(get(path() + "&voucher=REGULAR", cashierToken).getBody()).containsEntry("total",1);
        assertThat(get(path() + "&voucher=UNKNOWN", cashierToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(path() + "&academicYearId=" + (year + 999999), cashierToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(path() + "&programId=0", cashierToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(path() + "&sectionId=" + section + "&campusId=" + foreignCampus, mixedToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(path() + "&size=101", cashierToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(path() + "&page=-1", cashierToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        long otherYear = db.queryForObject("INSERT INTO academic_years(label,starts_on,ends_on) VALUES (?,'2025-01-01','2025-12-31') RETURNING id", Long.class, "YR" + key());
        assertThat(get(path() + "&academicYearId=" + otherYear, cashierToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void csvIsCompleteFormulaSafeUnicodeAndAuditedWithoutPrivateData() throws Exception {
        var csv = export("?termId=" + term, cashierToken);
        assertThat(csv.getStatusCode()).as(new String(csv.getBody(), StandardCharsets.UTF_8)).isEqualTo(HttpStatus.OK);
        assertThat(csv.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(csv.getHeaders().getFirst("Content-Disposition")).contains("attachment;");
        String text = new String(csv.getBody(), StandardCharsets.UTF_8);
        assertThat(text).contains("'  =SUM(1,2)\nJosé Ñ").contains("QVR").contains("ESC").contains("ALS").contains("REGULAR").contains("UNCLASSIFIED")
                .doesNotContain("hidden").doesNotContain("Missing documents").doesNotContain("Grade value");
        var audit = db.queryForMap("SELECT new_data->>'rows' AS rows,new_data->>'sha256' AS sha,new_data::text AS data FROM audit_logs WHERE table_name='cashier_roster_exports' AND actor_id=? ORDER BY id DESC LIMIT 1", cashier);
        assertThat(audit.get("rows")).isEqualTo("5");
        assertThat(audit.get("sha")).isEqualTo(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(csv.getBody())));
        assertThat(audit.get("data").toString()).doesNotContain("José").doesNotContain("Rosterstudent");
        var filtered = export("?termId=" + term + "&voucher=ESC", cashierToken);
        assertThat(new String(filtered.getBody(), StandardCharsets.UTF_8)).contains("Esc Rosterstudent").doesNotContain("Paying").doesNotContain("José");
    }
    @Test void completeExportRejectsOversizeRatherThanSilentlyTruncating() {
        String prefix = key();
        // Insert prerequisite credentials and attendance before confirming the generated batch.
        db.update("""
            WITH students_added AS (
              INSERT INTO students(student_number,first_name,last_name,birth_date,campus_id,program_id)
              SELECT ?||n,'Bulk','Cashier','2008-01-01',?,? FROM generate_series(1,5000) n RETURNING id,student_number
            ), applications_added AS (
              INSERT INTO applications(application_number,campus_id,term_id,program_id,student_id,category,first_name,last_name,birth_date)
              SELECT student_number,?,?,?,id,'new','Bulk','Cashier','2008-01-01' FROM students_added RETURNING id,student_id
            ) INSERT INTO enrollments(student_id,application_id,campus_id,term_id,program_id,status)
              SELECT student_id,id,?,?,?,'provisional' FROM applications_added
            """, prefix,campus,program,campus,term,program,campus,term,program);
        db.update("INSERT INTO orientation_attendance(application_id,attendance_date,status,recorded_by) SELECT application_id,'2026-01-05','present',? FROM enrollments WHERE term_id=? AND section_id IS NULL AND campus_id=?", cashier,term,campus);
        db.update("INSERT INTO application_requirements(application_id,requirement_code,status) SELECT e.application_id,r.code,'submitted' FROM enrollments e CROSS JOIN requirement_types r WHERE e.term_id=? AND e.section_id IS NULL AND e.campus_id=? AND r.mandatory", term,campus);
        db.update("UPDATE enrollments SET status='enrolled',confirmed_at=current_timestamp,confirmed_by=?,attendance_verified_at=current_timestamp,attendance_verified_by=? WHERE term_id=? AND section_id IS NULL AND campus_id=?",cashier,cashier,term,campus);
        assertThat(get(path() + "&size=1", cashierToken).getBody()).containsEntry("total",5005);
        assertThat(export("?termId=" + term,cashierToken).getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='cashier_roster_exports' AND actor_id=?",Integer.class,cashier)).isZero();
    }
}
