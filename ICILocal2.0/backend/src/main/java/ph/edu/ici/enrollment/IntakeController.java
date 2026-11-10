package ph.edu.ici.enrollment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ph.edu.ici.students.StudentInput.Guardian;

@RestController
@RequestMapping("/api/intake")
@PreAuthorize("@accessPolicy.hasPermission('enrollment.manage')")
public class IntakeController {
    private final IntakeService service;
    public IntakeController(IntakeService service){this.service=service;}
    public record Input(@NotNull @Positive Long campusId,@NotNull @Positive Long termId,
        @NotNull @Positive Long programId,@NotBlank @Pattern(regexp="new|transferee|returnee|continuing") String category,
        @NotBlank @Size(max=150) String firstName,@Size(max=150) String middleName,
        @NotBlank @Size(max=150) String lastName,@NotNull @Past LocalDate birthDate,
        @Pattern(regexp="[0-9]{12}") String lrn,@Size(max=30) String sex,@Size(max=40) String phone,
        @Email @Size(max=254) String email,@NotBlank @Pattern(regexp="QVR|ESC|ALS|REGULAR") String voucher,
        @NotBlank @Pattern(regexp="ACADEMIC|TVL|TVET") String track,
        @NotBlank @Size(max=30) String gradeLevel,@Size(max=200) String district,
        @Size(max=200) String transportRoute,@Size(max=250) String previousSchool,
        @Size(max=100) String householdIncome,@NotNull @Size(max=10) List<@NotNull @Valid Guardian> guardians) {}
    public record ReviewInput(@NotNull @PositiveOrZero Integer version) {}
    @GetMapping("/reference-data") public Map<String,Object> reference(){return service.reference();}
    @GetMapping("/statistics") public Map<String,Object> statistics(){return service.statistics();}
    @PostMapping("/applications") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@Valid @RequestBody Input input){return service.create(input);}
    @GetMapping("/applications/{id}") public Map<String,Object> detail(@PathVariable long id){return service.detail(id);}
    @PostMapping("/applications/{id}/review") public Map<String,Object> review(@PathVariable long id,@Valid @RequestBody ReviewInput input){return service.review(id,input.version());}
}
