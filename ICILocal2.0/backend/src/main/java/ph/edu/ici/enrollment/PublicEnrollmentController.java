package ph.edu.ici.enrollment;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.students.StudentInput.Guardian;

/**
 * Unauthenticated prospective-student enrollment endpoints.
 * Permits prospective applicants to fetch active enrollment catalogs and submit
 * an online enrollment application with requirement document uploads.
 */
@RestController
@RequestMapping("/api/public/enrollment")
public class PublicEnrollmentController {
    private final PublicEnrollmentService service;
    private final ObjectMapper json;
    private final Validator validator;

    public PublicEnrollmentController(PublicEnrollmentService service, ObjectMapper json, Validator validator) {
        this.service = service;
        this.json = json;
        this.validator = validator;
    }

    public record PortalSubmissionInput(
        @NotNull @Positive Long campusId,
        @NotNull @Positive Long termId,
        @NotNull @Positive Long programId,
        @NotBlank @Pattern(regexp = "new|transferee|returnee|continuing") String category,
        @NotBlank @Pattern(regexp = "ACADEMIC|TVL|TVET") String track,
        @NotBlank @Size(max = 30) String gradeLevel,
        @Pattern(regexp = "QVR|ESC|ALS|REGULAR") String voucher,
        @Size(max = 20) String lrnStatus,
        @Size(max = 80) String psaBirthCertNo,
        @Pattern(regexp = "^$|^[0-9]{12}$") String lrn,
        @NotBlank @Size(max = 150) String firstName,
        @Size(max = 150) String middleName,
        @NotBlank @Size(max = 150) String lastName,
        @Size(max = 30) String extensionName,
        @NotNull @Past LocalDate birthDate,
        @NotBlank @Size(max = 30) String sex,
        @NotBlank @Size(max = 40) String phone,
        @NotBlank @Email @Size(max = 254) String email,
        Boolean indigenousPeople,
        @Size(max = 150) String indigenousGroup,
        @Size(max = 100) String motherTongue,
        @Size(max = 200) String academicHonors,
        @Size(max = 10) List<@Size(max = 60) String> learningModalities,
        @Size(max = 120) String learningModalityOther,
        @NotBlank @Size(max = 250) String streetAddress,
        @NotBlank @Size(max = 150) String province,
        @NotBlank @Size(max = 150) String cityMunicipality,
        @NotBlank @Size(max = 150) String barangay,
        @Size(max = 10) String currentlyEnrolled,
        @Size(max = 250) String previousSchool,
        @Size(max = 500) String previousSchoolAddress,
        @Size(max = 50) String previousSchoolId,
        @Size(max = 150) String referredBy,
        @NotNull @Size(min = 1, max = 10) List<@NotNull @Valid Guardian> guardians
    ) {}

    @GetMapping("/reference-data")
    public Map<String, Object> referenceData() {
        return service.referenceData();
    }

    @PostMapping(value = "/submissions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> submitMultipart(
        @RequestPart("application") String applicationJson,
        @RequestParam(value = "documentCodes", required = false) List<String> documentCodes,
        @RequestPart(value = "files", required = false) List<MultipartFile> files
    ) {
        PortalSubmissionInput input;
        try {
            input = json.readValue(applicationJson, PortalSubmissionInput.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid enrollment application payload.");
        }
        Set<ConstraintViolation<PortalSubmissionInput>> violations = validator.validate(input);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .sorted()
                .findFirst()
                .orElse("Check the required application fields.");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return service.submit(input, documentCodes, files);
    }

    @PostMapping(value = "/submissions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> submitJson(@Valid @RequestBody PortalSubmissionInput input) {
        return service.submit(input, List.of(), List.of());
    }
}
