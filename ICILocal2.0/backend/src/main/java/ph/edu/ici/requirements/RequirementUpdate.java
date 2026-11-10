package ph.edu.ici.requirements;

import jakarta.validation.constraints.*;

public record RequirementUpdate(
    @NotBlank @Pattern(regexp="submitted|missing|pending|invalid") String status,
    @NotNull @PositiveOrZero Integer version,
    @Size(max=2000) String notes
) {}
