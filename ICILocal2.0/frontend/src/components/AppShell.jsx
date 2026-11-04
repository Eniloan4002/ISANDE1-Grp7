import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router';
import { useAuth } from '../auth/AuthContext.jsx';
import { roleSections,pages } from '../routes/routeConfig.js';

export function AppShell() {
  const { roles, logout } = useAuth();
  const location = useLocation();
  const [logoutError,setLogoutError]=useState('');
  const [isMobile,setIsMobile]=useState(()=>window.matchMedia('(max-width: 700px)').matches);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [desktopCollapsed, setDesktopCollapsed] = useState(false);
  const [openGroups, setOpenGroups] = useState({});
  const sections = roleSections.filter(section => roles.includes(section.role));
  const currentSection = sections.find(section => section.groups.some(group => group.pages.some(page => page.path === location.pathname))) ?? sections[0];

  useEffect(() => setMobileOpen(false), [location.pathname]);
  useEffect(() => {
    const media = window.matchMedia('(max-width: 700px)');
    const closeOnDesktop = () => { setIsMobile(media.matches);if (!media.matches) setMobileOpen(false); };
    media.addEventListener('change', closeOnDesktop);
    return () => media.removeEventListener('change', closeOnDesktop);
  }, []);

  return <div className={`app-shell${mobileOpen ? ' mobile-open' : ''}${desktopCollapsed ? ' desktop-collapsed' : ''}`}>
    {mobileOpen && <button className="nav-scrim" type="button" aria-label="Close navigation" onClick={() => setMobileOpen(false)} />}
    <aside className="sidebar" id="primary-navigation" aria-label="Main navigation">
      <NavLink className="brand" to="/" aria-label="ICI Local home">
        <span className="brand-mark">ICI</span><span className="brand-copy"><strong>ICI Local</strong><small>Student Information System</small></span>
      </NavLink>
      <nav className="sidebar-nav">
        {sections.map(section => <div className="role-section" key={section.role}>
          <p className="nav-label">{section.label}</p>
          {section.groups.map(group => {
            const key = `${section.role}:${group.label}`;
            const active = group.pages.some(page => page.path === location.pathname);
            const open = openGroups[key] ?? true;
            return <div className="nav-group" key={key}>
              <button className="nav-group-toggle" type="button" aria-expanded={open} onClick={() => setOpenGroups(previous => ({ ...previous, [key]: !open }))}>
                <span>{group.label}</span><span aria-hidden="true">{open ? '−' : '+'}</span>
              </button>
              {open && <div className="nav-group-items">{group.pages.map(page => <NavLink key={page.path} className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`} to={page.path} aria-current={location.pathname === page.path ? 'page' : undefined}>{page.label}</NavLink>)}</div>}
            </div>;
          })}
        </div>)}
        <div className="role-section"><p className="nav-label">Shared workspace</p>{pages.filter(page=>['/setup','/design-system'].includes(page.path)&&page.allowedRoles.some(role=>roles.includes(role))).map(page=><NavLink className="nav-item" key={page.path} to={page.path}>{page.label}</NavLink>)}</div>
      </nav>
    </aside>
    <div className="main-frame">
      <header className="topbar">
        <button className="menu-toggle" type="button" aria-label="Toggle navigation" aria-controls="primary-navigation" aria-expanded={isMobile?mobileOpen:!desktopCollapsed} onClick={() => {
          if (window.matchMedia('(max-width: 700px)').matches) setMobileOpen(value => !value);
          else setDesktopCollapsed(value => !value);
        }}><svg className="hamburger" aria-hidden="true" width="20" height="20" viewBox="0 0 24 24" fill="none"><path d="M4 7h16M4 12h16M4 17h16" stroke="currentColor" strokeWidth="2" strokeLinecap="round" /></svg></button>
        <div className="institution"><strong>ILIGAN COMPUTER INSTITUTE</strong><small>{currentSection?.office ?? 'ICI Local'}</small></div>
        <div className="topbar-actions"><span className="profile"><span className="avatar">{currentSection?.initials ?? 'ICI'}</span><span className="profile-name">{currentSection?.label ?? 'User'}</span></span><button className="sign-out" type="button" onClick={async()=>{try{await logout();setLogoutError('');}catch(error){setLogoutError('Sign-out could not be confirmed. '+error.message);}}}>Sign out</button></div>
      </header>
      <main className="main-content">{logoutError&&<p className="notice notice-error" role="alert">{logoutError}</p>}<div className="breadcrumb">ICI Local / {pages.find(page=>page.path===location.pathname)?.label||'Workspace'}</div><Outlet /></main>
    </div>
  </div>;
}
