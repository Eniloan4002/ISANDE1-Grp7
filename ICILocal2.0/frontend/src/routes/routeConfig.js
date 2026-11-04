// Keep paths, permissions, and navigation together as screens move into React.
export const roleSections = [
  {
    role: 'student', label: 'Prospective Student', office: 'Student Portal', initials: 'PS',
    groups: [{ label: 'Enrollment', pages: [
      { path: '/enrollment/submission', label: 'Enrollment Submission' },
    ] }],
  },
  {
    role: 'registrar', label: 'Registrar Staff', office: "Registrar's Office", initials: 'RS',
    groups: [
      { label: 'Applications', pages: [
        { path: '/enrollment/queue', label: 'Enrollment Queue' },
        { path: '/enrollment/evaluate', label: 'Evaluate Applications' },
        { path: '/enrollment/duplicate', label: 'Duplicate Records' },
      ] },
      { label: 'Validation', pages: [
        { path: '/enrollment/requirements', label: 'Requirements' },
        { path: '/enrollment/attendance', label: 'First-Week Attendance' },
      ] },
      { label: 'Enrollment', pages: [
        { path: '/enrollment/academic-records', label: 'Enrollment Records' },
        { path: '/enrollment/enrolled', label: 'Enrolled & Assigned' },
      ] },
      { label: 'Academic Records', pages: [
        { path: '/academic/registrar-records', label: 'Academic History' },
        { path: '/academic/incomplete-grades', label: 'Incomplete Grades' },
        { path: '/academic/grade-approvals', label: 'Grade Approvals' },
        { path: '/academic/finalize-grades', label: 'Grade Finalization' },
      ] },
      { label: 'Reporting', pages: [
        { path: '/enrollment/reporting-monitoring', label: 'Reporting & Monitoring' },
      ] },
    ],
  },
  {
    role: 'adviser', label: 'Teacher Adviser', office: 'Teacher Adviser Portal', initials: 'TA',
    groups: [{ label: 'Academic Records', pages: [
      { path: '/academic/advisory', label: 'Advisory Class' },
      { path: '/academic/class-records', label: 'Class Records' },
      { path: '/academic/student-monitoring', label: 'Student Monitoring' },
    ] }],
  },
  {
    role: 'teacher', label: 'Subject Teacher', office: 'Subject Teacher Portal', initials: 'ST',
    groups: [{ label: 'Academic Records', pages: [
      { path: '/academic/grade-entry', label: 'Grade Entry' },
      { path: '/academic/class-list', label: 'Assigned Classes' },
      { path: '/academic/progress-reports', label: 'Grade Corrections' },
    ] }],
  },
  {
    role: 'guidance', label: 'Guidance Staff', office: 'Guidance Office', initials: 'GS',
    groups: [{ label: 'Reporting', pages: [
      { path: '/reporting/guidance', label: 'Guidance Records' },
      { path: '/reporting/student-profiles', label: 'Student Profiles' },
      { path: '/reporting/reports', label: 'Reports' },
    ] }],
  },
  {
    role: 'admin', label: 'System Administrator', office: 'System Administration', initials: 'SA',
    groups: [{ label: 'Administration', pages: [
      { path: '/administration/users', label: 'User Accounts' },
      { path: '/administration/system-settings', label: 'System Settings' },
      { path: '/administration/audit-log', label: 'Audit Log' },
    ] }],
  },
];

export const knownRoles = roleSections.map(({ role }) => role);
const prototypePages = roleSections.flatMap(({ role, groups }) =>
  groups.flatMap(({ pages: groupPages }) => groupPages.map(page => ({ ...page, allowedRoles: [role] }))));

export const pages=[...prototypePages,{path:'/setup',label:'Academic setup',allowedRoles:['admin','registrar']},{path:'/design-system',label:'Interface components',allowedRoles:knownRoles}];

export function firstAllowedPath(roles) {
  if(roles.some(role=>['admin','registrar'].includes(role)))return '/setup';
  return pages.find(page => page.allowedRoles.some(role => roles.includes(role)))?.path ?? '/access-denied';
}
