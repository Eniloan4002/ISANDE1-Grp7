package ph.edu.ici.enrollment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.StaffIdentity;
import ph.edu.ici.students.StudentInput;

/**
 * Student-facing online enrollment portal backend.
 * Exposes only non-sensitive enrollment catalogs and accepts new applicant submissions
 * with optional requirement document uploads.
 */
@Service
public class PublicEnrollmentService {
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "jpg", "jpeg", "png");
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
        "application/pdf", "image/jpeg", "image/jpg", "image/png"
    );

    private final NamedParameterJdbcTemplate db;
    private final ObjectMapper json;
    private final DuplicateService duplicates;

    public PublicEnrollmentService(NamedParameterJdbcTemplate db, ObjectMapper json, DuplicateService duplicates) {
        this.db = db;
        this.json = json;
        this.duplicates = duplicates;
    }

    private ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }

    private String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void setActorIfPresent() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        String actorId = auth != null && auth.getPrincipal() instanceof StaffIdentity staff
            ? Long.toString(staff.userId())
            : "";
        db.queryForObject("SELECT set_config('app.actor_id',:actor,true)", Map.of("actor", actorId), String.class);
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw error(HttpStatus.BAD_REQUEST, "Invalid application profile.");
        }
    }

    private String sanitizeFilename(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[\\r\\n\\t\\p{Cntrl}]", "").trim();
        if (name.isBlank()) name = "document";
        return name.length() > 180 ? name.substring(name.length() - 180) : name;
    }

    private String fileExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String sha256Prefix(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8 && i < digest.length; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
    }

    private String formatBytes(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", size / 1024.0);
        return String.format(Locale.ROOT, "%.2f MB", size / (1024.0 * 1024.0));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> referenceData() {
        var result = new LinkedHashMap<String, Object>();
        for (String key : List.of("campuses", "programs", "terms", "vouchers", "requirementTypes")) {
            result.put(key, new ArrayList<Map<String, Object>>());
        }
        db.query("""
            SELECT 'campuses' AS kind, row_to_json(r)::text AS payload FROM (
                SELECT id, code, name FROM campuses WHERE active ORDER BY name
            ) r
            UNION ALL SELECT 'programs', row_to_json(r)::text FROM (
                SELECT id, code, name, category,
                       CASE WHEN category='TVET' THEN 'TVET'
                            WHEN code LIKE 'TVL_%' THEN 'TVL'
                            ELSE 'ACADEMIC' END AS track
                FROM programs ORDER BY name
            ) r
            UNION ALL SELECT 'terms', row_to_json(r)::text FROM (
                SELECT t.id, t.name, t.code, y.label AS "academicYear",
                       t.starts_on AS "startsOn", t.ends_on AS "endsOn"
                FROM academic_terms t
                JOIN academic_years y ON y.id = t.academic_year_id
                ORDER BY t.starts_on DESC, t.id DESC
            ) r
            UNION ALL SELECT 'vouchers', row_to_json(r)::text FROM (
                SELECT code, name FROM vouchers ORDER BY code
            ) r
            UNION ALL SELECT 'requirementTypes', row_to_json(r)::text FROM (
                SELECT code, name, mandatory FROM requirement_types ORDER BY mandatory DESC, code
            ) r
            """, Map.of(), rs -> {
            try {
                @SuppressWarnings("unchecked")
                var rows = (List<Map<String, Object>>) result.get(rs.getString("kind"));
                rows.add(json.readValue(rs.getString("payload"), new TypeReference<Map<String, Object>>() {}));
            } catch (IOException e) {
                throw new IllegalStateException("Invalid enrollment catalog data", e);
            }
        });
        return result;
    }

    @Transactional
    public Map<String, Object> submit(
        PublicEnrollmentController.PortalSubmissionInput input,
        List<String> documentCodes,
        List<MultipartFile> files
    ) {
        var campusRows = db.queryForList("SELECT id, name FROM campuses WHERE id=:id AND active", Map.of("id", input.campusId()));
        if (campusRows.isEmpty()) throw error(HttpStatus.BAD_REQUEST, "Choose an active campus.");
        var programRows = db.queryForList("SELECT id, code, name, category FROM programs WHERE id=:id", Map.of("id", input.programId()));
        if (programRows.isEmpty()) throw error(HttpStatus.BAD_REQUEST, "Choose an existing strand or program.");
        var termRows = db.queryForList("""
            SELECT t.id, concat(y.label, ' / ', t.name) AS label
            FROM academic_terms t JOIN academic_years y ON y.id = t.academic_year_id
            WHERE t.id = :id
            """, Map.of("id", input.termId()));
        if (termRows.isEmpty()) throw error(HttpStatus.BAD_REQUEST, "Choose an existing academic term.");

        String expectedTrack = "TVET".equals(programRows.getFirst().get("category"))
            ? "TVET"
            : programRows.getFirst().get("code").toString().startsWith("TVL_") ? "TVL" : "ACADEMIC";
        if (!expectedTrack.equals(input.track())) {
            throw error(HttpStatus.BAD_REQUEST, "Track must match the selected strand or program.");
        }

        String voucherCode = text(input.voucher()) == null ? "REGULAR" : input.voucher().trim();
        if (!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM vouchers WHERE code=:code)", Map.of("code", voucherCode), Boolean.class))) {
            throw error(HttpStatus.BAD_REQUEST, "Choose a valid voucher classification.");
        }

        String lrn = text(input.lrn());
        if (lrn != null && !lrn.matches("^[0-9]{12}$")) {
            throw error(HttpStatus.BAD_REQUEST, "LRN must be exactly 12 digits when provided.");
        }

        List<StudentInput.Guardian> guardians = input.guardians() == null ? List.of() : input.guardians();
        if (guardians.stream().filter(StudentInput.Guardian::primaryContact).count() > 1) {
            throw error(HttpStatus.BAD_REQUEST, "Choose at most one primary guardian.");
        }

        // Validate uploaded files before inserting any database rows.
        List<String> codes = documentCodes == null ? List.of() : documentCodes;
        List<MultipartFile> uploads = files == null ? List.of() : files;
        if (!uploads.isEmpty() && codes.size() != uploads.size()) {
            throw error(HttpStatus.BAD_REQUEST, "Each uploaded document must specify its requirement code.");
        }

        Set<String> validReqCodes = new HashSet<>(db.queryForList("SELECT code FROM requirement_types", Map.of(), String.class));
        record ValidatedUpload(String code, String filename, String contentType, long sizeBytes, String hash) {}
        List<ValidatedUpload> validatedUploads = new ArrayList<>();
        Set<String> seenCodes = new HashSet<>();

        for (int i = 0; i < uploads.size(); i++) {
            MultipartFile file = uploads.get(i);
            if (file == null || file.isEmpty()) continue;
            String code = text(codes.get(i));
            if (code == null || !validReqCodes.contains(code)) {
                throw error(HttpStatus.BAD_REQUEST, "Unknown requirement document type: " + codes.get(i));
            }
            if (!seenCodes.add(code)) {
                throw error(HttpStatus.BAD_REQUEST, "Duplicate upload for requirement: " + code);
            }
            if (file.getSize() > MAX_FILE_BYTES) {
                throw error(HttpStatus.BAD_REQUEST, "Each uploaded file must be 2 MB or smaller.");
            }
            String safeName = sanitizeFilename(file.getOriginalFilename());
            String ext = fileExtension(safeName);
            String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
            if (!ALLOWED_EXTENSIONS.contains(ext) && !ALLOWED_CONTENT_TYPES.contains(contentType)) {
                throw error(HttpStatus.BAD_REQUEST, "Unsupported file format for " + safeName + ". Upload PDF, JPG, or PNG.");
            }
            byte[] bytes;
            try {
                bytes = file.getBytes();
            } catch (IOException e) {
                throw error(HttpStatus.BAD_REQUEST, "Unable to read uploaded file " + safeName + ".");
            }
            validatedUploads.add(new ValidatedUpload(
                code,
                safeName,
                contentType.isBlank() ? ("pdf".equals(ext) ? "application/pdf" : "image/" + ext) : contentType,
                bytes.length,
                sha256Prefix(bytes)
            ));
        }

        String appNumber = "APP-" + LocalDate.now().getYear() + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        Instant now = Instant.now();

        List<Map<String, Object>> uploadedDocsMeta = new ArrayList<>();
        for (var u : validatedUploads) {
            var doc = new LinkedHashMap<String, Object>();
            doc.put("code", u.code());
            doc.put("originalFilename", u.filename());
            doc.put("contentType", u.contentType());
            doc.put("sizeBytes", u.sizeBytes());
            doc.put("uploadedAt", now.toString());
            uploadedDocsMeta.add(doc);
        }

        Map<String, Object> address = new LinkedHashMap<>();
        address.put("street", text(input.streetAddress()));
        address.put("barangay", text(input.barangay()));
        address.put("city", text(input.cityMunicipality()));
        address.put("province", text(input.province()));

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("submissionChannel", "student_portal");
        profile.put("track", expectedTrack);
        profile.put("gradeLevel", input.gradeLevel().trim());
        profile.put("sex", text(input.sex()));
        profile.put("suffix", text(input.extensionName()));
        profile.put("lrnStatus", text(input.lrnStatus()));
        profile.put("psaBirthCertNo", text(input.psaBirthCertNo()));
        profile.put("indigenousPeople", Boolean.TRUE.equals(input.indigenousPeople()));
        profile.put("indigenousGroup", text(input.indigenousGroup()));
        profile.put("motherTongue", text(input.motherTongue()));
        profile.put("academicHonors", text(input.academicHonors()));
        profile.put("learningModalities", input.learningModalities() == null ? List.of() : input.learningModalities());
        profile.put("learningModalityOther", text(input.learningModalityOther()));
        profile.put("address", address);
        profile.put("district", text(input.cityMunicipality()));
        profile.put("currentlyEnrolled", text(input.currentlyEnrolled()));
        profile.put("previousSchoolAddress", text(input.previousSchoolAddress()));
        profile.put("previousSchoolId", text(input.previousSchoolId()));
        profile.put("referredBy", text(input.referredBy()));
        profile.put("guardians", guardians);
        profile.put("uploadedDocuments", uploadedDocsMeta);

        setActorIfPresent();
        db.queryForObject("SELECT pg_advisory_xact_lock(142014)", Map.of(), Object.class);

        Map<String, Object> params = new HashMap<>();
        params.put("number", appNumber);
        params.put("campus", input.campusId());
        params.put("term", input.termId());
        params.put("program", input.programId());
        params.put("category", input.category());
        params.put("first", input.firstName().trim());
        params.put("middle", text(input.middleName()));
        params.put("last", input.lastName().trim());
        params.put("birth", input.birthDate());
        params.put("lrn", lrn);
        params.put("phone", text(input.phone()));
        params.put("email", text(input.email()));
        params.put("school", text(input.previousSchool()));
        params.put("voucher", voucherCode);
        params.put("profile", encode(profile));

        long id = db.queryForObject("""
            INSERT INTO applications(
                application_number, campus_id, term_id, program_id, category,
                first_name, middle_name, last_name, birth_date, lrn,
                contact_phone, email, previous_school, voucher_code, profile
            ) VALUES (
                :number, :campus, :term, :program, :category,
                :first, :middle, :last, :birth, :lrn,
                :phone, :email, :school, :voucher, CAST(:profile AS jsonb)
            ) RETURNING id
            """, params, Long.class);

        for (var u : validatedUploads) {
            String storageKey = "portal-uploads/" + appNumber + "/" + u.code() + "-" + u.hash();
            String note = "Uploaded via online enrollment portal (" + formatBytes(u.sizeBytes()) + "). Pending registrar verification.";
            db.update("""
                INSERT INTO application_requirements(
                    application_id, requirement_code, status, storage_key, original_filename, notes, version
                ) VALUES (
                    :appId, :code, 'pending', :storageKey, :filename, :notes, 1
                )
                """, Map.of(
                "appId", id,
                "code", u.code(),
                "storageKey", storageKey,
                "filename", u.filename(),
                "notes", note
            ));
        }

        duplicates.scanPublic(id);

        String finalStatus = db.queryForObject(
            "SELECT status FROM applications WHERE id=:id",
            Map.of("id", id),
            String.class
        );

        var response = new LinkedHashMap<String, Object>();
        response.put("id", id);
        response.put("applicationNumber", appNumber);
        response.put("status", finalStatus);
        response.put("submittedAt", LocalDate.now().toString());
        response.put("applicantName", (input.firstName().trim() + " " + input.lastName().trim()).trim());
        response.put("campus", campusRows.getFirst().get("name"));
        response.put("term", termRows.getFirst().get("label"));
        response.put("program", programRows.getFirst().get("name"));
        response.put("gradeLevel", input.gradeLevel().trim());
        response.put("uploadedDocuments", uploadedDocsMeta);
        return response;
    }
}
