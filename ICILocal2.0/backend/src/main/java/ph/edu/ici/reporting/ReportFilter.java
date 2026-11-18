package ph.edu.ici.reporting;

public record ReportFilter(Long campusId,Long academicYearId,Long termId,Long gradingPeriodId,
        Long sectionId,String gradeLevel,String voucher,String requirementStatus,String enrollmentStatus) {}
