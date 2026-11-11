// Temporary ICI2-23 development fixture.
// The shape mirrors the planned adviser-roster response and must be replaced
// by a backend data-access implementation when the roster API is available.
export const advisoryRosterFixture = {
  source: 'development-fixture',
  academicYear: '2026-2027',
  term: 'First Semester',
  adviser: { name: null },
  section: { name: 'STEM 11-A', gradeLevel: 'Grade 11' },
  students: [
    {
      studentNumber: 'STU-2026-0001',
      name: 'Ana Garcia',
      contactNumber: '0917 555 0148',
      enrollmentStatus: 'Enrolled',
      requirementsStatus: 'Complete',
      academicStanding: 'Good standing',
      academicFlag: null,
    },
    {
      studentNumber: 'STU-2026-0002',
      name: 'Miguel Santos',
      contactNumber: '0918 555 0214',
      enrollmentStatus: 'Enrolled',
      requirementsStatus: 'Incomplete',
      academicStanding: 'Needs follow-up',
      academicFlag: 'Missing Form 137',
    },
    {
      studentNumber: 'STU-2026-0003',
      name: 'Leah Ramos',
      contactNumber: '0919 555 0321',
      enrollmentStatus: 'Pre-enlisted',
      requirementsStatus: 'Complete',
      academicStanding: 'At risk',
      academicFlag: 'Below passing threshold',
    },
  ],
};
