package ph.edu.ici.students;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;

public record StudentInput(
    @NotNull @Positive Long campusId,
    @NotNull @Positive Long programId,
    @NotBlank @Pattern(regexp="QVR|ESC|ALS|REGULAR") String voucherCode,
    @NotBlank @Pattern(regexp="ACADEMIC|TVL|TVET") String track,
    @Pattern(regexp="[0-9]{12}") String lrn,
    @NotBlank @Size(max=150) String firstName,
    @Size(max=150) String middleName,
    @NotBlank @Size(max=150) String lastName,
    @Size(max=30) String suffix,
    @NotNull @Past LocalDate birthDate,
    @Size(max=30) String sex,
    @Size(max=40) String phone,
    @Email @Size(max=254) String email,
    @NotNull @Valid Address address,
    @Size(max=250) String previousSchoolName,
    @Size(max=50) String previousSchoolId,
    @Size(max=500) String previousSchoolAddress,
    @Size(max=100) String householdIncomeBracket,
    @NotNull @Size(max=10) List<@NotNull @Valid Guardian> guardians,
    @NotNull Boolean active,
    @PositiveOrZero Integer version
) {
    public record Address(@Size(max=250) String street,@Size(max=150) String barangay,
        @Size(max=150) String city,@Size(max=150) String province,@Size(max=20) String postalCode) {}
    public record Guardian(@NotBlank @Size(max=250) String fullName,
        @NotBlank @Size(max=80) String relationship,@Size(max=40) String phone,
        @Size(max=500) String address,boolean primaryContact) {}
}
