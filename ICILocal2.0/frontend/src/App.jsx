import { Navigate, Outlet, Route, Routes, useLocation } from 'react-router';
import { useAuth } from './auth/AuthContext.jsx';
import { AppShell } from './components/AppShell.jsx';
import { firstAllowedPath, pages } from './routes/routeConfig.js';

function RequireAuth() {
  const { isAuthenticated } = useAuth();
  const location = useLocation();
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

function SignIn() {
  const { isAuthenticated, roles } = useAuth();
  const location = useLocation();
  const requestedPath = location.state?.from?.pathname;
  const requestedPage = pages.find(page => page.path === requestedPath);
  const destination = requestedPage?.allowedRoles.some(role => roles.includes(role)) ? requestedPath : firstAllowedPath(roles);
  if (isAuthenticated) return <Navigate to={destination} replace />;
  return <main className="entry-layout">
    <section className="entry-branding">
      <div className="brand-lockup"><span className="brand-mark">ICI</span><span><strong>ICI Local 2.0</strong><small>Student Information System</small></span></div>
      <div><h1>Iligan Computer Institute</h1><p>Integrated Student Records and Enrollment Management System</p></div>
    </section>
    <section className="entry-panel" aria-labelledby="sign-in-title">
      <p className="eyebrow">Authentication pending</p>
      <h2 id="sign-in-title">Sign in to ICI Local</h2>
      <p>The login screen and backend authentication will connect here in a later task.</p>
    </section>
  </main>;
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
    <Route path="/sign-in" element={<SignIn />} />
    <Route element={<RequireAuth />}>
      <Route element={<AppShell />}>
        <Route index element={<HomeRedirect />} />
        <Route path="/access-denied" element={<AccessDenied />} />
        {pages.map(page => <Route key={page.path} path={page.path} element={
          <RoleGate allowedRoles={page.allowedRoles}><Placeholder page={page} /></RoleGate>
        } />)}
      </Route>
    </Route>
    <Route path="*" element={<NotFound />} />
  </Routes>;
}
