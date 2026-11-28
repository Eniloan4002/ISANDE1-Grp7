// ICI2-105: the screens that are not regular workflows: not found, access denied and the placeholder for a
// planned screen. Inside the shell they share one empty-state layout; signed out, they use the sign-in card.
import { Link, useLocation } from 'react-router';
import { useAuth } from '../auth/AuthContext.jsx';
import { firstAllowedPath, pages, roleSections } from '../routes/routeConfig.js';
import { BrandMark } from './BrandMark.jsx';
import './status-pages.css';

const roleLabel = role => roleSections.find(section => section.role === role)?.label ?? role;
const pageLabel = path => pages.find(page => page.path === path)?.label;
const joinWords = items => items.length < 2 ? items.join('') : `${items.slice(0, -1).join(', ')} or ${items[items.length - 1]}`;

function Icon({ kind }) {
  const paths = {
    search: <><circle cx="11" cy="11" r="7" /><path d="M20 20l-3.5-3.5" /></>,
    lock: <><rect x="5" y="11" width="14" height="9" rx="2" /><path d="M8 11V8a4 4 0 0 1 8 0v3" /></>,
    build: <><path d="M4 20h16" /><path d="M6 20V10l6-5 6 5v10" /><path d="M10 20v-5h4v5" /></>,
  };
  return <span className="status-page-icon" aria-hidden="true"><svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">{paths[kind]}</svg></span>;
}

/** Shared layout for status screens inside the shell. */
export function StatusPage({ icon, eyebrow, title, children, actions }) {
  return <section className="status-page" aria-labelledby="status-page-title">
    <Icon kind={icon} />
    {eyebrow && <p className="status-page-eyebrow">{eyebrow}</p>}
    <h1 id="status-page-title">{title}</h1>
    <div className="status-page-body">{children}</div>
    {actions && <div className="status-page-actions">{actions}</div>}
  </section>;
}

/** The first screen a signed-in user can open, as a link. */
function HomeLink({ roles, className = 'ui-btn ui-btn-primary' }) {
  const path = firstAllowedPath(roles);
  if (path === '/access-denied') return null;
  return <Link className={className} to={path}>Go to {pageLabel(path) ?? 'your first page'}</Link>;
}

export function AccessDenied() {
  const { roles } = useAuth();
  const { state } = useLocation();
  const needed = (state?.allowedRoles ?? []).map(roleLabel);
  const requested = state?.from ? pageLabel(state.from) ?? state.from : '';
  const yours = roles.map(roleLabel);
  return <StatusPage icon="lock" eyebrow="Error 403" title="Access denied" actions={<HomeLink roles={roles} />}>
    <p>{requested ? <><strong>{requested}</strong> is</> : 'This page is'} {needed.length ? <>only for <strong>{joinWords(needed)}</strong> accounts.</> : 'not available to your account.'}</p>
    <p>{yours.length ? <>You are signed in as <strong>{joinWords(yours)}</strong>.</> : 'Your account has no staff role yet.'} If you need this page, ask the system administrator to update your role.</p>
  </StatusPage>;
}

/** 404 inside the shell (signed in). */
export function NotFound() {
  const { roles } = useAuth();
  const { pathname } = useLocation();
  return <StatusPage icon="search" eyebrow="Error 404" title="Page not found" actions={<HomeLink roles={roles} />}>
    <p>There is no page at <code>{pathname}</code>. It may have moved, or the link may be mistyped.</p>
    <p>Use the side panel to find what you need.</p>
  </StatusPage>;
}

/** 404 for visitors who are not signed in: the sign-in card layout, no side panel. */
export function StandaloneNotFound() {
  const { pathname } = useLocation();
  return <div className="auth-screen">
    <main className="auth-card" aria-labelledby="status-page-title">
      <BrandMark />
      <Icon kind="search" />
      <p className="status-page-eyebrow">Error 404</p>
      <h1 id="status-page-title">Page not found</h1>
      <p className="auth-muted">There is no page at <code>{pathname}</code>. Check the address, or use one of these links.</p>
      <div className="auth-actions">
        <Link className="ui-btn ui-btn-primary" to="/sign-in">Staff sign in</Link>
        <Link className="ui-btn ui-btn-secondary" to="/apply">Submit an enrollment application</Link>
      </div>
    </main>
  </div>;
}

/** Placeholder for a planned screen: what it will do and which ticket owns it. */
export function Placeholder({ page }) {
  const { roles } = useAuth();
  return <StatusPage icon="build" eyebrow="Coming soon" title={page.label} actions={<HomeLink roles={roles} className="ui-btn ui-btn-secondary" />}>
    <p>{page.summary ?? 'This screen is planned but has not been built yet. No records are shown here.'}</p>
    {page.ticket && <p className="status-page-ticket">Tracked in <strong>{page.ticket}</strong></p>}
  </StatusPage>;
}
