package ph.edu.ici.reporting;

/** All filters are optional; rows always remain within the cashier permission's campus scope. */
public record CashierRosterFilter(Long campusId, Long academicYearId, Long termId,
        Long programId, Long sectionId, String gradeLevel, String voucher) {}
