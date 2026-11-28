import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { useState } from 'react';
import App from '../App.jsx';
import { AuthProvider } from '../auth/AuthContext.jsx';
import { api, configureApiAuth } from '../api/client.js';
import { setAccessToken } from '../api.js';
import { TopBarControls } from '../components/AppShell.jsx';
import { activeNavPath, knownRoles, navGroupsFor, pages } from '../routes/routeConfig.js';
import { Dialog, Drawer, FilterChip, Popover, ProgressMeter, StatusChip } from '../ui/components.jsx';

const future = () => Math.floor(Date.now() / 1000) + 3600;
function tokenFor(claims) {
  const payload = btoa(JSON.stringify(claims)).replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `eyJhbGciOiJub25lIn0.${payload}.signature`;
}
async function showAs(role, path) {
  setAccessToken(tokenFor({ roles: [role], exp: future() }));
  api.defaults.adapter=async config=>({status:200,statusText:'OK',headers:{},config,data:config.url.startsWith('/setup/')?[]:config.url.startsWith('/admin/accounts/reference-data')?{roles:[],campuses:[]}:config.url.startsWith('/admin/accounts')?{items:[],total:0}:(config.url.startsWith('/enrollment/reference-data')||config.url.startsWith('/intake/reference-data'))?{campuses:[],terms:[],programs:[],advisers:[],vouchers:[]}:config.url.startsWith('/duplicate-cases')||config.url.startsWith('/enrollment/applications')||config.url.startsWith('/sections')?{items:[],total:0}:{userId:1,username:'test',fullName:'Test Staff',grants:[role].map(role=>({campusId:1,campusCode:'ILIGAN',role}))}});
  await act(async () => { render(<MemoryRouter initialEntries={[path]}><AuthProvider><App /></AuthProvider></MemoryRouter>); });
}
afterEach(() => { cleanup(); setAccessToken(null); configureApiAuth(null, null); });

describe('shared side panel', () => {
  const pairs = knownRoles.flatMap(role => pages.filter(page => page.allowedRoles.includes(role)).map(page => [role, page.path]));

  it('covers every role and every signed-in route', () => {
    expect(knownRoles).toHaveLength(8);
    expect(pairs.length).toBeGreaterThanOrEqual(60);
  });

  it.each(pairs)('%s sees %s inside the shell with that page marked current', async (role, path) => {
    await showAs(role, path);
    const nav = screen.getByRole('navigation', { name: 'Pages' });
    expect(screen.getByRole('complementary', { name: 'Main navigation' })).toContainElement(nav);
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
    const current = within(nav).getByRole('link', { current: 'page' });
    expect(current.getAttribute('href')).toBe(path);
  });

  it('builds the same menu structure from route config for each role', () => {
    for (const role of knownRoles) {
      const groups = navGroupsFor([role]);
      expect(groups.length).toBeGreaterThan(1);
      expect(groups.at(-1).label).toBe('Shared');
      expect(groups.every(group => group.items.every(item => item.path && item.label && item.icon))).toBe(true);
    }
    expect(navGroupsFor([]).length).toBe(0);
  });

  it('lists a page once when a user holds several roles', () => {
    const paths = navGroupsFor(['registrar', 'principal', 'teacher']).flatMap(group => group.items.map(item => item.path));
    expect(new Set(paths).size).toBe(paths.length);
  });

  it('keeps the queue active on applicant details', () => {
    expect(activeNavPath('/enrollment/applicants/12')).toBe('/enrollment/queue');
    expect(activeNavPath('/enrollment/intake')).toBe('/enrollment/intake');
  });

  it('shows the signed-in name in the top bar and user card', async () => {
    await showAs('teacher', '/academic/grade-entry');
    expect(screen.getAllByText('Test Staff')).toHaveLength(2);
  });

  it('provides a top-bar slot for screens that own campus or term scope', async () => {
    await showAs('teacher', '/academic/grade-entry');
    expect(document.querySelector('.topbar .topbar-controls')).toBeInTheDocument();
    expect(TopBarControls).toBeTypeOf('function');
  });
});

describe('design tokens', () => {
  const css = readFileSync(resolve(process.cwd(), 'src/ui/tokens.css'), 'utf8');
  const token = name => css.match(new RegExp(`${name}:\\s*(#[0-9A-Fa-f]{6})`))[1];
  const lum = hex => {
    const channel = i => { const v = parseInt(hex.slice(1 + i * 2, 3 + i * 2), 16) / 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; };
    return 0.2126 * channel(0) + 0.7152 * channel(1) + 0.0722 * channel(2);
  };
  const ratio = (a, b) => { const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x); return (hi + 0.05) / (lo + 0.05); };

  it.each([
    ['--ici-blue', '--surface'], ['--ici-blue', '--blue-50'], ['--ici-blue', '--blue-100'], ['--surface', '--ici-blue'],
    ['--ink', '--surface'], ['--ink', '--canvas'], ['--ink-2', '--surface'], ['--ink-2', '--canvas'], ['--ink-nav', '--surface'], ['--ink-3', '--surface'],
    ['--success', '--success-bg'], ['--attention', '--attention-bg'], ['--blocked', '--blocked-bg'], ['--info', '--info-bg'], ['--neutral', '--neutral-bg'],
  ])('%s on %s is at least 4.5:1', (fg, bg) => {
    expect(ratio(token(fg), token(bg))).toBeGreaterThanOrEqual(4.5);
  });

  it('declares the focus ring, radii and font', () => {
    expect(css).toMatch(/--focus-ring:\s*2px solid var\(--ici-blue\)/);
    expect(css).toMatch(/--focus-offset:\s*2px/);
    expect(css).toMatch(/--radius-control:\s*8px/);
    expect(css).toMatch(/--radius-card:\s*12px/);
    expect(css).toMatch(/--radius-full:\s*999px/);
    expect(css).toMatch(/--font-sans:\s*'Inter'/);
  });
});

describe('shared components', () => {
  it('chips always carry a word next to the dot', () => {
    render(<StatusChip tone="blocked">Duplicate flag</StatusChip>);
    expect(screen.getByText('Duplicate flag')).toBeVisible();
  });

  it('filter chip reports pressed state and count', () => {
    render(<FilterChip selected count={7}>Waiting</FilterChip>);
    expect(screen.getByRole('button', { name: /Waiting/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('7')).toBeInTheDocument();
  });

  it('progress meter exposes its value', () => {
    render(<ProgressMeter label="Submitted" value={7} max={10} />);
    expect(screen.getByRole('meter', { name: 'Submitted' })).toHaveAttribute('aria-valuenow', '7');
  });

  it('popover closes on Escape and returns focus to its button', () => {
    render(<Popover label="Hint"><p>Help text</p></Popover>);
    const button = screen.getByRole('button', { name: 'Hint' });
    fireEvent.click(button);
    expect(screen.getByText('Help text')).toBeInTheDocument();
    expect(button).toHaveAttribute('aria-expanded', 'true');
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByText('Help text')).not.toBeInTheDocument();
    expect(button).toHaveFocus();
  });

  function Harness({ Surface }) {
    const [open, setOpen] = useState(false);
    return <><button type="button" onClick={() => setOpen(true)}>Launch</button><Surface open={open} onClose={() => setOpen(false)} title="Details">Body</Surface></>;
  }
  it.each([['Dialog', Dialog], ['Drawer', Drawer]])('%s opens, is named, and returns focus on cancel', (_, Surface) => {
    render(<Harness Surface={Surface} />);
    const launch = screen.getByRole('button', { name: 'Launch' });
    launch.focus();
    fireEvent.click(launch);
    const dialog = document.querySelector('dialog');
    expect(dialog).toHaveAttribute('aria-labelledby');
    expect(document.getElementById(dialog.getAttribute('aria-labelledby'))).toHaveTextContent('Details');
    fireEvent(dialog, new Event('cancel', { cancelable: true }));
    expect(document.querySelector('dialog')).toBeNull();
    expect(launch).toHaveFocus();
  });
});
