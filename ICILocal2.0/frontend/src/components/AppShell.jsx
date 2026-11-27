import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Link, NavLink, Outlet, useLocation } from 'react-router';
import { useAuth } from '../auth/AuthContext.jsx';
import { activeNavPath, navGroupsFor, roleSections } from '../routes/routeConfig.js';
import { Icon } from '../ui/icons.jsx';

// Below this width the side panel collapses behind the menu button (ICI2-96).
const NARROW = '(max-width: 900px)';

const TopBarSlot = createContext(null);

/**
 * Screens that own campus or term scope render their selectors here and they appear in the top bar:
 * <TopBarControls><label>Campus <select …/></label></TopBarControls>
 */
export function TopBarControls({ children }) {
  const slot = useContext(TopBarSlot);
  return slot ? createPortal(children, slot) : null;
}

function SidePanel({ groups, activePath, who, userName, inert, panelRef }) {
  return <aside className="sidebar" id="primary-navigation" aria-label="Main navigation" inert={inert} ref={panelRef}>
    <Link className="sidebar-brand" to="/" aria-label="ICI Local home">
      <span className="sidebar-logo" aria-hidden="true">ICI</span>
      <span className="sidebar-brand-copy"><strong>ICI Local</strong><small>Student information</small></span>
    </Link>
    <nav className="sidebar-nav" aria-label="Pages">
      {groups.map(group => <div className="nav-group" key={group.label} role="group" aria-labelledby={`nav-${group.label.replace(/\W+/g, '-')}`}>
        <p className="nav-label" id={`nav-${group.label.replace(/\W+/g, '-')}`}>{group.label}</p>
        {group.items.map(page => {
          const active = activePath === page.path;
          return <NavLink key={page.path} className={`nav-item${active ? ' active' : ''}`} to={page.path} aria-current={active ? 'page' : undefined}>
            <Icon name={page.icon} />
            <span className="nav-item-label">{page.label}</span>
            {page.badge ? <span className="nav-badge" aria-label={`${page.badge} waiting`}>{page.badge}</span> : null}
          </NavLink>;
        })}
      </div>)}
    </nav>
    <div className="user-card">
      <span className="avatar" aria-hidden="true">{who.initials}</span>
      <span className="user-card-copy"><strong>{userName || who.label}</strong><small>{userName ? `${who.label} · ${who.office}` : who.office}</small></span>
    </div>
  </aside>;
}

export function AppShell() {
  const { roles, logout, user } = useAuth();
  const location = useLocation();
  const [logoutError, setLogoutError] = useState('');
  const [isNarrow, setIsNarrow] = useState(() => window.matchMedia(NARROW).matches);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [slot, setSlot] = useState(null);
  const toggle = useRef(null);
  const panel = useRef(null);
  const restoreFocus = useRef(false);

  const groups = navGroupsFor(roles);
  const activePath = activeNavPath(location.pathname);
  const sections = roleSections.filter(section => roles.includes(section.role));
  const currentSection = sections.find(section => section.groups.some(group => group.pages.some(page => page.path === activePath))) ?? sections[0];
  const who = { label: currentSection?.label ?? 'User', office: currentSection?.office ?? 'ICI Local', initials: currentSection?.initials ?? 'ICI' };
  const userName = user?.fullName || user?.name || user?.username || '';

  const closeNav = useCallback(() => { restoreFocus.current = true; setMobileOpen(false); }, []);
  useEffect(() => setMobileOpen(false), [location.pathname]);
  useEffect(() => {
    const media = window.matchMedia(NARROW);
    const onChange = () => { setIsNarrow(media.matches); if (!media.matches) setMobileOpen(false); };
    media.addEventListener('change', onChange);
    return () => media.removeEventListener('change', onChange);
  }, []);
  useEffect(() => {
    if (!mobileOpen) return undefined;
    const onKey = event => { if (event.key === 'Escape') closeNav(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [mobileOpen, closeNav]);
  // Opening moves focus into the panel; Escape or the scrim hands it back to the menu button.
  useEffect(() => {
    if (mobileOpen) panel.current?.querySelector('.nav-item.active, .nav-item')?.focus();
    else if (restoreFocus.current) { restoreFocus.current = false; toggle.current?.focus(); }
  }, [mobileOpen]);

  return <div className={`app-shell${mobileOpen ? ' mobile-open' : ''}`}>
    <a className="skip-link" href="#main-content">Skip to main content</a>
    {mobileOpen && <button className="nav-scrim" type="button" aria-label="Close navigation" tabIndex={-1} onClick={closeNav} />}
    <SidePanel groups={groups} activePath={activePath} who={who} userName={userName} inert={isNarrow && !mobileOpen} panelRef={panel} />
    <div className="main-frame" inert={isNarrow && mobileOpen}>
      <header className="topbar">
        <button ref={toggle} className="menu-toggle" type="button" aria-label="Toggle navigation" aria-controls="primary-navigation" aria-expanded={isNarrow ? mobileOpen : true} onClick={() => setMobileOpen(value => !value)}><Icon name="menu" size={20} /></button>
        <div className="topbar-controls" ref={setSlot} />
        <div className="topbar-actions">
          <span className="profile"><span className="profile-name">{userName || who.label}</span></span>
          <button className="sign-out" type="button" onClick={async () => { try { await logout(); setLogoutError(''); } catch (error) { setLogoutError('Sign-out could not be confirmed. ' + error.message); } }}>Sign out</button>
        </div>
      </header>
      <TopBarSlot.Provider value={slot}>
        <main className="main-content" id="main-content" tabIndex={-1}>
          {logoutError && <p className="notice notice-error" role="alert">{logoutError}</p>}
          <Outlet />
        </main>
      </TopBarSlot.Provider>
    </div>
  </div>;
}

