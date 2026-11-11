import { Navigate, Outlet, Route, Routes, useLocation } from 'react-router';
import DuplicateRecords from './DuplicateRecords.jsx';
import AttendanceScreen from './enrollment/AttendanceScreen.jsx';
import AccountSecurity from './AccountSecurity.jsx';
import StaffAccounts from './UserAccounts.jsx';
import AcademicSetup from './AcademicSetup.jsx';
import EnrollmentWorkspace,{SectionConfiguration} from './EnrollmentWorkspace.jsx';
import {EnrollmentWorkspace as IntakeWorkspace} from './enrollment/EnrollmentScreens.jsx';
import RequirementsScreen from './enrollment/RequirementsScreen.jsx';
import AdvisoryClass from './academic/AdvisoryClass.jsx';
import DesignSystem from './components/DesignSystem.jsx';
import { LoginPage } from './Auth.jsx';
import { useAuth } from './auth/AuthContext.jsx';
import { AppShell } from './components/AppShell.jsx';
import { firstAllowedPath, pages } from './routes/routeConfig.js';

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
    <Route element={<RequireAuth />}>
      <Route element={<AppShell />}>
        <Route index element={<HomeRedirect />} />
        <Route path="/access-denied" element={<AccessDenied />} />
        <Route path="/enrollment/applicants/:applicationId" element={<RoleGate allowedRoles={['admin','registrar']}><IntakeWorkspace screen="details"/></RoleGate>}/>
        <Route path="/account/security" element={<AccountSecurity />}/>
        {pages.filter(page=>page.path!=='/account/security').map(page => <Route key={page.path} path={page.path} element={
          <RoleGate allowedRoles={page.allowedRoles}>{page.path==='/enrollment/duplicate'?<DuplicateRecords />:page.path==='/account/security'?<AccountSecurity />:page.path==='/administration/users'?<StaffAccounts />:page.path==='/setup'?<AcademicSetup />:page.path==='/design-system'?<DesignSystem />:page.path==='/enrollment/queue'?<IntakeWorkspace screen="queue" />:page.path==='/enrollment/evaluate'?<IntakeWorkspace screen="queue" title="Evaluate Applications" />:page.path==='/enrollment/intake'?<IntakeWorkspace screen="intake" />:page.path==='/enrollment/requirements'?<RequirementsScreen />:page.path==='/enrollment/attendance'?<AttendanceScreen />:page.path==='/enrollment/enrolled'?<EnrollmentWorkspace mode="allocation" />:page.path==='/enrollment/sections'?<SectionConfiguration />:page.path==='/academic/advisory'?<AdvisoryClass />:<Placeholder page={page} />}</RoleGate>
        } />)}
      </Route>
    </Route>
    <Route path="*" element={<NotFound />} />
  </Routes>;
}
