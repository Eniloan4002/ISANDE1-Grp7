import { Navigate, Outlet, Route, Routes, useLocation } from 'react-router';
import AcademicSetup from './AcademicSetup.jsx';
import DuplicateRecords from './DuplicateRecords.jsx';
import DesignSystem from './components/DesignSystem.jsx';
import { LoginPage } from './Auth.jsx';
import { useAuth } from './auth/AuthContext.jsx';
import { AppShell } from './components/AppShell.jsx';
import { firstAllowedPath, pages } from './routes/routeConfig.js';

function RequireAuth() {
  const { isAuthenticated,loading } = useAuth();
  const location = useLocation();
  if(loading)return <p role="status">Checking staff session…</p>;
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
    <p>This route is ready for the {page.label.toLowerCase()} screen.</p>
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
        {pages.map(page => <Route key={page.path} path={page.path} element={
          <RoleGate allowedRoles={page.allowedRoles}>{page.path==='/setup'?<AcademicSetup />:page.path==='/design-system'?<DesignSystem />:page.path==='/enrollment/duplicate'?<DuplicateRecords />:<Placeholder page={page} />}</RoleGate>
        } />)}
      </Route>
    </Route>
    {import.meta.env.DEV && <Route path="/preview/duplicates" element={<main style={{ padding: 24 }}><DuplicateRecords /></main>} />}
    <Route path="*" element={<NotFound />} />
  </Routes>;
}