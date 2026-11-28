import { Navigate, Outlet, Route, Routes, useLocation } from 'react-router';
import DuplicateRecords from './DuplicateRecords.jsx';
import AttendanceScreen from './enrollment/AttendanceScreen.jsx';
import AccountSecurity from './AccountSecurity.jsx';
import StaffAccounts from './UserAccounts.jsx';
import AcademicSetup from './AcademicSetup.jsx';
import EnrollmentWorkspace,{SectionConfiguration} from './EnrollmentWorkspace.jsx';
import {EnrollmentWorkspace as IntakeWorkspace} from './enrollment/EnrollmentScreens.jsx';
import RequirementsScreen from './enrollment/RequirementsScreen.jsx';
import EnrollmentSubmission from './enrollment/EnrollmentSubmission.jsx';
import AdvisoryClass from './academic/AdvisoryClass.jsx';
import DesignSystem from './components/DesignSystem.jsx';
import GradeEntry from './academic/GradeEntry.jsx';
import AcademicAssignments from './academic/AcademicAssignments.jsx';
import AcademicHistory from './academic/AcademicHistory.jsx';
import IncompleteGrades from './academic/IncompleteGrades.jsx';
import GradeFinalization from './academic/GradeFinalization.jsx';
import GradeApprovals from './academic/GradeApprovals.jsx';
import GradeCorrections from './academic/GradeCorrections.jsx';
import WithdrawalsWorkspace from './academic/WithdrawalsWorkspace.jsx';
import StudentMonitoring from './academic/StudentMonitoring.jsx';
import { AtRiskReports, GuidanceRecords } from './reporting/GuidanceWorkspace.jsx';
import StudentProfiles from './reporting/StudentProfiles.jsx';
import LegacyImports from './imports/LegacyImports.jsx';
import AuditLog from './admin/AuditLog.jsx';
import SystemSettings from './admin/SystemSettings.jsx';
import { LoginPage } from './Auth.jsx';
import { useAuth } from './auth/AuthContext.jsx';
import { AppShell } from './components/AppShell.jsx';
import { firstAllowedPath, pages } from './routes/routeConfig.js';
import AssignedClasses from './academic/AssignedClasses.jsx';
import EnrollmentRecords from './enrollment/EnrollmentRecords.jsx';
import ReportingMonitoring from './enrollment/ReportingMonitoring.jsx';
import ClassRecords from './academic/ClassRecords.jsx';
import HonorRoll from './reporting/HonorRoll.jsx';
import OfficialDocuments from './documents/OfficialDocuments.jsx';

// Screens added from Sprint 2 on register here by path. Anything not listed falls through to the
// original chain below, so new screens stay out of that ternary and merge without conflicts.
const screenByPath = {
  '/academic/class-list': <AssignedClasses />,
  '/enrollment/academic-records': <EnrollmentRecords />,
  '/enrollment/reporting-monitoring': <ReportingMonitoring />,
  '/academic/class-records': <ClassRecords />,
  '/reporting/honor-roll': <HonorRoll />,
  '/documents/official': <OfficialDocuments />,
};

function RequireAuth() {
  const { isAuthenticated,loading,user } = useAuth();
  const location = useLocation();
  if(loading)return <p role="status">Checking staff session…</p>;
  if(isAuthenticated&&user?.passwordChangeRequired&&location.pathname!=='/account/security')return <Navigate to="/account/security" replace/>;
  return isAuthenticated ? <Outlet /> : <Navigate to="/sign-in" replace state={{ from: location }} />;
}

function RoleGate({ allowedRoles, children }) {
  const { roles } = useAuth();
  return allowedRoles.some(role => roles.includes(role)) ? children : <Navigate to="/access-denied" replace />;
}

function HomeRedirect() {
  const { roles } = useAuth();
  return <Navigate to={firstAllowedPath(roles)} replace />;
}

function Placeholder({ page }) {
  return <section className="content-card" aria-labelledby="page-title">
    <p className="eyebrow">Screen placeholder</p>
    <h1 id="page-title">{page.label}</h1>
    <p>You are signed in. This workflow is still under development; no records are available on this screen yet.</p>
  </section>;
}

function AccessDenied() {
  return <section className="content-card"><h1>Access denied</h1><p>Your assigned role does not allow this screen.</p></section>;
}

function NotFound() {
  return <main className="standalone-message"><h1>Page not found</h1><p>Check the address and try again.</p></main>;
}

export default function App() {
  return <Routes>
    <Route path="/login" element={<Navigate to="/sign-in" replace />} />
    <Route path="/sign-in" element={<LoginPage />} />
    <Route path="/apply" element={<EnrollmentSubmission standalone />} />
    <Route element={<RequireAuth />}>
      <Route element={<AppShell />}>
        <Route index element={<HomeRedirect />} />
        <Route path="/access-denied" element={<AccessDenied />} />
        <Route path="/enrollment/applicants/:applicationId" element={<RoleGate allowedRoles={['admin','registrar']}><IntakeWorkspace screen="details"/></RoleGate>}/>
        <Route path="/account/security" element={<AccountSecurity />}/>
        {pages.filter(page=>page.path!=='/account/security').map(page => <Route key={page.path} path={page.path} element={
          <RoleGate allowedRoles={page.allowedRoles}>{screenByPath[page.path]??(page.path==='/enrollment/duplicate'?<DuplicateRecords />:page.path==='/account/security'?<AccountSecurity />:page.path==='/administration/users'?<StaffAccounts />:page.path==='/administration/system-settings'?<SystemSettings />:page.path==='/administration/audit-log'?<AuditLog />:page.path==='/setup'?<AcademicSetup />:page.path==='/academic/assignments'?<AcademicAssignments />:page.path==='/academic/grade-entry'?<GradeEntry />:page.path==='/academic/incomplete-grades'?<IncompleteGrades />:page.path==='/academic/finalize-grades'?<GradeFinalization />:page.path==='/academic/grade-approvals'?<GradeApprovals />:page.path==='/design-system'?<DesignSystem />:page.path==='/enrollment/queue'?<IntakeWorkspace screen="queue" />:page.path==='/enrollment/evaluate'?<IntakeWorkspace screen="queue" title="Evaluate Applications" />:page.path==='/enrollment/intake'?<IntakeWorkspace screen="intake" />:page.path==='/enrollment/requirements'?<RequirementsScreen />:page.path==='/enrollment/submission'?<EnrollmentSubmission />:page.path==='/enrollment/attendance'?<AttendanceScreen />:page.path==='/enrollment/enrolled'?<EnrollmentWorkspace mode="allocation" />:page.path==='/enrollment/sections'?<SectionConfiguration />:page.path==='/academic/advisory'?<AdvisoryClass />:page.path==='/academic/student-monitoring'?<StudentMonitoring />:page.path==='/academic/registrar-records'?<AcademicHistory />:page.path==='/academic/progress-reports'?<GradeCorrections />:page.path==='/academic/withdrawal-approvals'?<WithdrawalsWorkspace mode="approvals" />:page.path==='/cashiering/clearances'?<WithdrawalsWorkspace mode="clearances" />:page.path==='/reporting/guidance'?<GuidanceRecords />:page.path==='/reporting/student-profiles'?<StudentProfiles />:page.path==='/reporting/reports'?<AtRiskReports />:page.path==='/migration/legacy-imports'?<LegacyImports />:<Placeholder page={page} />)}</RoleGate>
        } />)}
      </Route>
    </Route>
    <Route path="*" element={<NotFound />} />
  </Routes>;
}