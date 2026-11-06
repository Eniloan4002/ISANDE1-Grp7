package ph.edu.ici.setup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static ph.edu.ici.setup.SetupService.Kind.*;
import static ph.edu.ici.setup.SetupService.values;

@RestController
@RequestMapping("/api/setup")
@PreAuthorize("hasAnyRole('ADMIN','REGISTRAR')")
public class SetupController {
    private final SetupService service;
    public SetupController(SetupService service) { this.service=service; }

    public record SubjectInput(@NotBlank @Size(max=40) String code, @NotBlank @Size(max=200) String name,
        @NotNull @DecimalMin("0.01") @DecimalMax("999.99") @Digits(integer=3,fraction=2) BigDecimal units,
        @NotNull Boolean active, @PositiveOrZero Integer version) {
        Map<String,Object> fields() { return values("code",code.trim().toUpperCase(java.util.Locale.ROOT),"name",name.trim(),"units",units,"active",active); }
    }
    public record YearInput(@NotBlank @Size(max=20) String label, @NotNull LocalDate startsOn,
        @NotNull LocalDate endsOn, @PositiveOrZero Integer version) {
        Map<String,Object> fields() { return values("label",label.trim(),"starts_on",startsOn,"ends_on",endsOn); }
    }
    public record TermInput(@NotNull @Positive Long academicYearId, @NotBlank @Size(max=20) String code,
        @NotBlank @Size(max=200) String name, @NotNull LocalDate startsOn, @NotNull LocalDate endsOn,
        @PositiveOrZero Integer version) {
        Map<String,Object> fields() { return values("academic_year_id",academicYearId,"code",code.trim().toUpperCase(java.util.Locale.ROOT),"name",name.trim(),"starts_on",startsOn,"ends_on",endsOn); }
    }
    public record PeriodInput(@NotNull @Positive Long termId, @NotBlank @Size(max=20) String code,
        @NotNull @Min(1) @Max(32767) Integer sequence, @NotNull LocalDate startsOn,
        @NotNull LocalDate endsOn, @PositiveOrZero Integer version) {
        Map<String,Object> fields() { return values("term_id",termId,"code",code.trim().toUpperCase(java.util.Locale.ROOT),"sequence",sequence,"starts_on",startsOn,"ends_on",endsOn); }
    }

    @GetMapping("/subjects") public List<Map<String,Object>> subjects() { return service.list(SUBJECTS,null); }
    @GetMapping("/subjects/{id}") public Map<String,Object> subject(@PathVariable long id) { return service.get(SUBJECTS,id); }
    @PostMapping("/subjects") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> addSubject(@Valid @RequestBody SubjectInput input) { return service.create(SUBJECTS,input.fields()); }
    @PutMapping("/subjects/{id}") public Map<String,Object> updateSubject(@PathVariable long id,@Valid @RequestBody SubjectInput input) { return service.update(SUBJECTS,id,input.version(),input.fields()); }
    @DeleteMapping("/subjects/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSubject(@PathVariable long id,@RequestParam int version) { service.delete(SUBJECTS,id,version); }

    @GetMapping("/academic-years") public List<Map<String,Object>> years() { return service.list(YEARS,null); }
    @GetMapping("/academic-years/{id}") public Map<String,Object> year(@PathVariable long id) { return service.get(YEARS,id); }
    @PostMapping("/academic-years") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> addYear(@Valid @RequestBody YearInput input) { return service.create(YEARS,input.fields()); }
    @PutMapping("/academic-years/{id}") public Map<String,Object> updateYear(@PathVariable long id,@Valid @RequestBody YearInput input) { return service.update(YEARS,id,input.version(),input.fields()); }
    @DeleteMapping("/academic-years/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteYear(@PathVariable long id,@RequestParam int version) { service.delete(YEARS,id,version); }

    @GetMapping("/terms") public List<Map<String,Object>> terms(@RequestParam(required=false) Long academicYearId) { return service.list(TERMS,academicYearId); }
    @GetMapping("/terms/{id}") public Map<String,Object> term(@PathVariable long id) { return service.get(TERMS,id); }
    @PostMapping("/terms") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> addTerm(@Valid @RequestBody TermInput input) { return service.create(TERMS,input.fields()); }
    @PutMapping("/terms/{id}") public Map<String,Object> updateTerm(@PathVariable long id,@Valid @RequestBody TermInput input) { return service.update(TERMS,id,input.version(),input.fields()); }
    @DeleteMapping("/terms/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTerm(@PathVariable long id,@RequestParam int version) { service.delete(TERMS,id,version); }

    @GetMapping("/grading-periods") public List<Map<String,Object>> periods(@RequestParam(required=false) Long termId) { return service.list(PERIODS,termId); }
    @GetMapping("/grading-periods/{id}") public Map<String,Object> period(@PathVariable long id) { return service.get(PERIODS,id); }
    @PostMapping("/grading-periods") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> addPeriod(@Valid @RequestBody PeriodInput input) { return service.create(PERIODS,input.fields()); }
    @PutMapping("/grading-periods/{id}") public Map<String,Object> updatePeriod(@PathVariable long id,@Valid @RequestBody PeriodInput input) { return service.update(PERIODS,id,input.version(),input.fields()); }
    @DeleteMapping("/grading-periods/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePeriod(@PathVariable long id,@RequestParam int version) { service.delete(PERIODS,id,version); }
}
