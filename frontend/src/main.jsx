import React, { StrictMode, useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Navigate, NavLink, Outlet, Route, Routes, useLocation } from 'react-router';
import './styles.css';
import AcademicSetup from './AcademicSetup.jsx';
import {AuthProvider,LoginPage,RequireSetupStaff,useAuth} from './Auth.jsx';

export function Button({ variant = 'primary', ...props }) {
  return <button className={`button button-${variant}`} {...props} />;
}
export function Status({ children, tone = 'neutral' }) {
  return <span className={`status status-${tone}`}>{children}</span>;
}
export function Panel({ title, children }) {
  return <section className="panel"><h2>{title}</h2><div className="panel-body">{children}</div></section>;
}

const modules = [
  { path: '/', label: 'Workspace', group: 'Overview' },
  { path: '/enrollment', label: 'Enrollment management', group: 'Student services' },
  { path: '/academic', label: 'Academic records', group: 'Student services' },
  { path: '/reporting', label: 'Reporting & monitoring', group: 'Student services' },
  { path: '/administration', label: 'Administration', group: 'System' },
  { path: '/setup', label: 'Academic setup', group: 'System' },
  { path: '/design-system', label: 'Interface components', group: 'System' },
];

function Shell() {
  const {user,signOut}=useAuth();
  const [logoutError,setLogoutError]=useState('');
  const [open, setOpen] = useState(false);
  const location = useLocation();
  const content = useRef(null);
  useEffect(() => { setOpen(false); content.current?.focus(); }, [location.pathname]);
  const active = modules.find(item => item.path === location.pathname);
  return <div className="app-shell">
    <a className="skip-link" href="#content">Skip to content</a>
    <aside className={`sidebar ${open ? 'is-open' : ''}`} id="main-navigation" aria-label="Main navigation">
      <div className="brand"><span className="brand-mark">ICI</span><div><strong>ICI Local 2.0</strong><small>Student information system</small></div></div>
      <p className="campus-label">Iligan Computer Institute</p>
      <nav>{['Overview', 'Student services', 'System'].map(group => <div className="nav-group" key={group}><p>{group}</p>{modules.filter(item => item.group === group).map(item => <NavLink end={item.path === '/'} key={item.path} to={item.path}>{item.label}</NavLink>)}</div>)}</nav>
      <div className="sidebar-footer">Iligan Main · Kapatagan · Bugo</div>
    </aside>
    <div className="workspace"><header className="topbar"><Button variant="outline" className="menu-button button button-outline" aria-expanded={open} aria-controls="main-navigation" onClick={() => setOpen(!open)}>Menu</Button><span>{active?.label || 'Page not found'}</span><div className="staff-session">{user?<><span>{user.fullName}</span><Button variant="outline" onClick={async()=>{try{await signOut();setLogoutError('');}catch(error){setLogoutError(error.message);}}}>Sign out</Button></>:<NavLink to="/login">Staff sign in</NavLink>}<Status tone="warning">Development workspace</Status></div></header>
      {logoutError&&<p className="notice notice-error" role="alert">Sign-out could not be confirmed. {logoutError}</p>}
      <main id="content" ref={content} tabIndex="-1"><div className="breadcrumb">ICI Local / {active?.label || 'Page not found'}</div><Outlet /></main>
      <footer className="workspace-footer">Implementation foundation · No live student records connected</footer>
    </div>
  </div>;
}

function Workspace() {
  return <><div className="page-heading"><h1>A foundation for student services</h1><p>Enrollment, academic records and reporting in one institutional workspace.</p></div>
    <div className="notice">The React application is being built. Use the archived high-fidelity prototype to review the established workflows.</div>
    <Panel title="Implementation workspace"><div className="module-list">{[
      ['Enrollment management', 'Applicant intake, credentials, attendance verification and section allocation.', '/enrollment'],
      ['Academic records', 'Advisory rosters, subject grades, corrections and academic histories.', '/academic'],
      ['Reporting & monitoring', 'Registrar summaries and student intervention reporting.', '/reporting'],
    ].map(([name, description, path]) => <div className="module-row" key={path}><div><h3>{name}</h3><p>{description}</p></div><NavLink className="text-link" to={path}>Open module</NavLink></div>)}</div></Panel>
    <div className="two-columns"><Panel title="Preserved design reference"><p>The complete HTML prototype and cross-module demonstration are archived with a checksum inventory.</p><p>From the repository root, serve the archive on port 8080 using the README instructions.</p></Panel><Panel title="Next integration steps"><ol><li>Review the relational schema with the team.</li><li>Implement authentication and campus authorization.</li><li>Connect module screens to Spring Boot APIs.</li></ol></Panel></div>
  </>;
}

const moduleDetails = {
  enrollment: ['Enrollment management', ['Applicant intake and duplicate review', 'Requirement verification and document history', 'Attendance verification and enrollment confirmation', 'Section allocation and capacity management']],
  academic: ['Academic records', ['Assigned classes and advisory rosters', 'Grade encoding and grading-period locks', 'Grade corrections and incomplete resolution', 'Academic history and withdrawal clearances']],
  reporting: ['Reporting & monitoring', ['Enrollment and missing-document summaries', 'At-risk monitoring and intervention notes', 'Transcript request and issuance controls', 'Cashiering reference exports']],
  administration: ['Administration', ['User accounts and campus-scoped roles', 'Academic years, terms and sections', 'Institutional configuration', 'Append-only record-change audit trail']],
};
function ModulePage({ name }) {
  const [title, workflows] = moduleDetails[name];
  return <><div className="page-heading"><h1>{title}</h1><p>Implementation scope carried forward from the high-fidelity prototype.</p></div><Panel title="Module integration"><Status tone="warning">API integration pending</Status><p>No live records are available yet. These workflows are documented for implementation:</p><ul className="workflow-list">{workflows.map(item => <li key={item}>{item}</li>)}</ul>{name === 'reporting' && <div className="notice">Honor eligibility is deferred until ICI supplies an approved policy. No official honor computation is enabled.</div>}</Panel></>;
}

function Dialog({ onClose }) {
  const ref = useRef(null);
  const trigger = useRef(document.activeElement);
  useEffect(() => {
    const dialog = ref.current;
    dialog.showModal();
    return () => {
      dialog.close();
      trigger.current?.focus();
    };
  }, []);
  return <dialog ref={ref} aria-labelledby="dialog-title" onCancel={event => { event.preventDefault(); onClose(); }}><h2 id="dialog-title">Confirm your action</h2><p>Dialogs provide a focused confirmation before a record changes.</p><Button onClick={onClose}>Close dialog</Button></dialog>;
}
function DesignSystem() {
  const [dialog, setDialog] = useState(false);
  const [toast, setToast] = useState('');
  useEffect(() => { if (toast) { const timer = setTimeout(() => setToast(''), 4000); return () => clearTimeout(timer); } }, [toast]);
  return <><div className="page-heading"><h1>Interface components</h1><p>Shared foundations for consistent, accessible school workflows.</p></div><div className="two-columns">
    <Panel title="Actions and feedback"><div className="component-row"><Button onClick={() => setToast('Example action completed.')}>Show notification</Button><Button variant="outline" onClick={() => setDialog(true)}>Open dialog</Button><Button disabled>Unavailable action</Button></div><div className="component-row"><Status tone="success">Complete</Status><Status tone="warning">Pending</Status><Status tone="danger">Needs review</Status><Status>Draft</Status></div></Panel>
    <Panel title="Form controls"><label htmlFor="sample-name">Student name</label><input id="sample-name" placeholder="Enter a student name"/><label htmlFor="sample-campus">Campus</label><select id="sample-campus"><option>Iligan Main</option><option>Kapatagan</option><option>Bugo — CDO</option></select><p className="field-help">Examples only. No student data is saved.</p></Panel>
    <Panel title="Brand palette"><div className="swatches">{['primary', 'slate', 'coral', 'mint', 'charcoal', 'mist'].map(name => <div key={name}><span style={{ background: `var(--brand-${name})` }} />{name}</div>)}</div></Panel>
    <Panel title="Record states"><div className="empty-state"><h3>No records to display</h3><p>Connect the module API to load authorized records.</p></div><p role="status" aria-live="polite">Loading example: retrieving records…</p><div className="notice notice-error" role="alert">Example error: records could not be loaded. Try again.</div></Panel>
  </div>{dialog && <Dialog onClose={() => setDialog(false)} />}<div className="toast" role="status" aria-live="polite">{toast}</div></>;
}

function App() {
  return <Routes><Route path="login" element={<LoginPage/>}/><Route element={<Shell />}><Route index element={<Workspace />}/>{Object.keys(moduleDetails).map(name => <Route key={name} path={name} element={<ModulePage name={name}/>}/>)}<Route path="setup" element={<RequireSetupStaff><AcademicSetup /></RequireSetupStaff>}/><Route path="design-system" element={<DesignSystem />}/><Route path="home" element={<Navigate to="/" replace />}/><Route path="*" element={<><h1>Page not found</h1><p>Choose a module from the navigation.</p><NavLink to="/">Return to workspace</NavLink></>}/></Route></Routes>;
}

createRoot(document.getElementById('root')).render(<StrictMode><BrowserRouter><AuthProvider><App /></AuthProvider></BrowserRouter></StrictMode>);
