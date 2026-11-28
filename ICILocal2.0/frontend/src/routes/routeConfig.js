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
        { path: '/enrollment/intake', label: 'Applicant Intake Form' },
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
        { path: '/enrollment/sections', label: 'Section Configuration' },
      ] },
      { label: 'Academic Records', pages: [
        { path: '/academic/assignments', label: 'Teacher & Adviser Assignments' },
        { path: '/academic/registrar-records', label: 'Academic History' },
        { path: '/academic/incomplete-grades', label: 'Incomplete Grades' },
        { path: '/academic/grade-approvals', label: 'Grade Approvals' },
        { path: '/academic/finalize-grades', label: 'Grade Finalization' },
        { path: '/academic/withdrawal-approvals', label: 'Withdrawal Approvals' },
        { path: '/academic/daily-attendance', label: 'Daily Attendance' },
        { path: '/documents/official', label: 'Official Documents' },
      ] },
      { label: 'Reporting', pages: [
        { path: '/enrollment/reporting-monitoring', label: 'Reporting & Monitoring' },
        { path: '/reporting/honor-roll', label: 'Honor Roll' },
      ] },
      { label: 'Data Migration', pages: [
        { path: '/migration/legacy-imports', label: 'Legacy Import Review' },
      ] },
    ],
  },
  {
    role: 'adviser', label: 'Teacher Adviser', office: 'Teacher Adviser Portal', initials: 'TA',
    groups: [{ label: 'Academic Records', pages: [
      { path: '/academic/advisory', label: 'Advisory Class' },
      { path: '/academic/class-records', label: 'Class Records' },
      { path: '/academic/student-monitoring', label: 'Student Monitoring' },
      { path: '/academic/withdrawal-approvals', label: 'Withdrawal Approvals' },
    ] }],
  },
  {
    role: 'teacher', label: 'Subject Teacher', office: 'Subject Teacher Portal', initials: 'ST',
    groups: [{ label: 'Academic Records', pages: [
      { path: '/academic/grade-entry', label: 'Grade Entry' },
      { path: '/academic/incomplete-grades', label: 'Incomplete Grades' },
      { path: '/academic/class-list', label: 'Assigned Classes' },
      { path: '/academic/progress-reports', label: 'Grade Corrections' },
      { path: '/academic/daily-attendance', label: 'Daily Attendance' },
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
    role: 'principal', label: 'Principal', office: "Principal's Office", initials: 'PR',
    groups: [
      { label: 'Academic Oversight', pages: [
        { path: '/academic/registrar-records', label: 'Academic History' },
        { path: '/academic/grade-approvals', label: 'Grade Approvals' },
        { path: '/academic/withdrawal-approvals', label: 'Withdrawal Approvals' },
      ] },
      { label: 'Operational Reporting', pages: [
        { path: '/enrollment/reporting-monitoring', label: 'Reporting & Monitoring' },
        { path: '/reporting/honor-roll', label: 'Honor Roll' },
      ] },
    ],
  },
  {
    role: 'cashier_ref', label: 'Cashiering Reference', office: 'Cashiering Reference Office', initials: 'CR',
    groups: [{ label: 'Read-only Reference', pages: [
      { path: '/cashiering/students', label: 'Enrolled Student Reference' },
      { path: '/cashiering/clearances', label: 'Clearance Status' },
    ] }],
  },
  {
    role: 'admin', label: 'System Administrator', office: 'System Administration', initials: 'SA',
    groups: [{ label: 'Enrollment', pages: [
      {path:'/enrollment/duplicate',label:'Duplicate Records'},
      {path:'/enrollment/queue',label:'Enrollment Queue'},
      {path:'/enrollment/intake',label:'Applicant Intake Form'},
      {path:'/enrollment/requirements',label:'Requirements'},
      {path:'/enrollment/attendance',label:'First-Week Attendance'},
      {path:'/enrollment/sections',label:'Section Configuration'},
      {path:'/enrollment/enrolled',label:'Enrolled & Assigned'},
    ] }, { label: 'Administration', pages: [
      { path: '/administration/users', label: 'User Accounts' },
      { path: '/administration/system-settings', label: 'System Settings' },
      { path: '/administration/audit-log', label: 'Audit Log' },
    ] }, { label: 'Data Migration', pages: [
      { path: '/migration/legacy-imports', label: 'Legacy Import Review' },
    ] }],
  },
];

export const knownRoles = roleSections.map(({ role }) => role);
// Shared screens keep one route with the union of explicitly authorized roles.
const routeMap=new Map();
for(const {role,groups} of roleSections)for(const group of groups)for(const page of group.pages){
  if(!routeMap.has(page.path))routeMap.set(page.path,{...page,allowedRoles:[]});
  routeMap.get(page.path).allowedRoles.push(role);
}
// Icon keys are drawn by ui/icons.jsx; a missing key falls back to a neutral dot.
const iconByPath={
  '/enrollment/submission':'intake','/enrollment/queue':'queue','/enrollment/intake':'intake','/enrollment/evaluate':'reqs','/enrollment/duplicate':'dupes',
  '/enrollment/requirements':'reqs','/enrollment/attendance':'attend','/enrollment/academic-records':'book','/enrollment/enrolled':'place','/enrollment/sections':'sections',
  '/enrollment/reporting-monitoring':'chart','/academic/assignments':'people','/academic/registrar-records':'history','/academic/incomplete-grades':'alert',
  '/academic/grade-approvals':'shield','/academic/finalize-grades':'lock','/academic/advisory':'people','/academic/class-records':'book','/academic/student-monitoring':'chart',
  '/academic/grade-entry':'edit','/academic/class-list':'book','/academic/progress-reports':'shield','/academic/withdrawal-approvals':'alert',
  '/academic/daily-attendance':'attend','/documents/official':'book','/reporting/honor-roll':'chart',
  '/reporting/guidance':'book','/reporting/student-profiles':'people','/reporting/reports':'chart','/cashiering/students':'people','/cashiering/clearances':'shield',
  '/administration/users':'people','/administration/system-settings':'settings','/administration/audit-log':'history',
  '/setup':'settings','/design-system':'place','/account/security':'lock',
};
const prototypePages=[...routeMap.values()];

export const pages=[...prototypePages,{path:'/setup',label:'Academic setup',allowedRoles:['admin','registrar']},{path:'/design-system',label:'Interface components',allowedRoles:knownRoles},{path:'/account/security',label:'Account Security',allowedRoles:knownRoles}];

const sharedPaths=['/setup','/design-system','/account/security'];

/**
 * One menu model for every role: the groups of each granted role (merged by label, each page once),
 * then a Shared group. Optional `badge` on a page entry shows a count in the side panel.
 */
export function navGroupsFor(roles){
  const groups=new Map();
  const seen=new Set();
  const add=(label,page)=>{
    if(seen.has(page.path))return;
    seen.add(page.path);
    if(!groups.has(label))groups.set(label,[]);
    groups.get(label).push({path:page.path,label:page.label,icon:iconByPath[page.path]??'dot',badge:page.badge});
  };
  for(const section of roleSections){
    if(!roles.includes(section.role))continue;
    for(const group of section.groups)for(const page of group.pages)add(group.label,page);
  }
  for(const page of pages)if(sharedPaths.includes(page.path)&&page.allowedRoles.some(role=>roles.includes(role)))add('Shared',page);
  return [...groups].map(([label,items])=>({label,items}));
}

/** The nav entry that should look active for a pathname (applicant details belong to the queue). */
export function activeNavPath(pathname){
  return /^\/enrollment\/applicants\/\d+$/.test(pathname??'')?'/enrollment/queue':pathname;
}

export function firstAllowedPath(roles) {
  if(roles.includes('admin'))return '/setup';
  if(roles.includes('teacher') && !roles.includes('registrar'))return '/academic/grade-entry';
  return pages.find(page => page.allowedRoles.some(role => roles.includes(role)))?.path ?? '/access-denied';
}

export function canAccessPath(path,roles){
  return pages.some(page=>page.path===path&&page.allowedRoles.some(role=>roles.includes(role)))
    || (/^\/enrollment\/applicants\/\d+$/.test(path??'')&&roles.some(role=>['admin','registrar'].includes(role)));
}
