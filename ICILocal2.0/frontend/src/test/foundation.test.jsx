import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import App from '../App.jsx';
import { AuthProvider } from '../auth/AuthContext.jsx';
import { readClaims, rolesFromClaims } from '../auth/jwt.js';
import { api, configureApiAuth } from '../api/client.js';
import {setAccessToken} from '../api.js';
import { pages } from '../routes/routeConfig.js';

function tokenFor(claims) {
  const payload = btoa(JSON.stringify(claims)).replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `eyJhbGciOiJub25lIn0.${payload}.signature`;
}

const future = () => Math.floor(Date.now() / 1000) + 3600;
const roleCodes = { admin: 'ROLE_ADMIN', registrar: 'ROLE_REGISTRAR', adviser: 'ROLE_ADVISER', teacher: 'ROLE_TEACHER', guidance: 'ROLE_GUIDANCE' };

async function show(path, token = null) {
  setAccessToken(token);
  const claims=readClaims(token);
  const grantedRoles=Array.isArray(claims?.roles)?claims.roles:claims?.role?[claims.role]:[];
  api.defaults.adapter=async config=>({status:200,statusText:'OK',headers:{},config,data:config.url.startsWith('/setup/')?[]:{userId:1,username:'test',fullName:'Test Staff',grants:grantedRoles.map(role=>({campusId:1,campusCode:'ILIGAN',role}))}});
  let result;
  await act(async()=>{result=render(<MemoryRouter initialEntries={[path]}><AuthProvider><App /></AuthProvider></MemoryRouter>);});
  return result;
}

afterEach(() => {
  cleanup();
  setAccessToken(null);
  configureApiAuth(null, null);
});

describe('JWT claims', () => {
  it('rejects malformed, expired, and not-yet-valid tokens', () => {
    expect(readClaims('bad')).toBeNull();
    expect(readClaims(tokenFor({ role: 'teacher', exp: future() - 7200 }))).toBeNull();
    expect(readClaims(tokenFor({ role: 'teacher', exp: future(), nbf: future() + 100 }))).toBeNull();
    expect(readClaims(tokenFor({ role: 'teacher' }))).toBeNull();
  });

  it('normalizes only known roles', () => {
    expect(rolesFromClaims({roles:['ROLE_REGISTRAR','ROLE_ADMIN','ROLE_TEACHER','ROLE_ADMIN']})).toEqual(['registrar','admin','teacher']);
    expect(rolesFromClaims({roles:['registrar','admin','ROLE_UNKNOWN','student']})).toEqual([]);
    expect(rolesFromClaims({roles:['ROLE_PRINCIPAL','ROLE_ADVISER','ROLE_GUIDANCE','ROLE_CASHIER_REF']})).toEqual(['principal','adviser','guidance','cashier_ref']);
  });
});

describe('routes', () => {
  it('reserves every prototype page with a unique clean path', () => {
    expect(pages).toHaveLength(27);
    expect(new Set(pages.map(page => page.path)).size).toBe(pages.length);
    expect(pages.every(page => page.allowedRoles.length > 0 && !page.path.endsWith('.html'))).toBe(true);
  });

  it('sends unauthenticated visitors to the staff sign-in screen', async () => {
    await show('/academic/grade-entry');
    expect(screen.getByRole('heading', { name: 'Staff sign in' })).toBeInTheDocument();
  });

  it('allows the matching role and hides other navigation sections', async () => {
    await show('/academic/grade-entry', tokenFor({ role: 'ROLE_TEACHER', exp: future() }));
    expect(screen.getByRole('heading', { name: 'Grade Entry' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Assigned Classes' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'User Accounts' })).not.toBeInTheDocument();
  });

  it('denies a role that lacks access', async () => {
    await show('/administration/users', tokenFor({ role: 'ROLE_TEACHER', exp: future() }));
    expect(screen.getByRole('heading', { name: 'Access denied' })).toBeInTheDocument();
  });

  it.each(pages.filter(page=>page.allowedRoles.some(role=>roleCodes[role])))('opens $path for its assigned staff role', async page => {
    await show(page.path, tokenFor({ role: roleCodes[page.allowedRoles.find(role=>roleCodes[role])], exp: future() }));
    expect(screen.getByRole('heading', { name: page.label })).toBeInTheDocument();
  });

  it.each(['ROLE_PRINCIPAL','ROLE_CASHIER_REF'])('does not grant %s a reserved reporting route', async role => {
    await show('/reporting/reports', tokenFor({ role, exp: future() }));
    expect(screen.getByRole('heading', { name: 'Access denied' })).toBeInTheDocument();
  });

  it.each(['ROLE_PRINCIPAL','ROLE_CASHIER_REF'])('recognizes %s but gives it no module landing route', async role => {
    await show('/', tokenFor({ role, exp: future() }));
    expect(screen.getByRole('heading', { name: 'Access denied' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Academic setup' })).not.toBeInTheDocument();
  });

  it.each([
    ['ROLE_ADMIN','/academic/grade-entry'],
    ['ROLE_REGISTRAR','/academic/advisory'],
    ['ROLE_PRINCIPAL','/setup'],
    ['ROLE_ADVISER','/enrollment/queue'],
    ['ROLE_TEACHER','/reporting/guidance'],
    ['ROLE_GUIDANCE','/academic/class-list'],
    ['ROLE_CASHIER_REF','/administration/users'],
  ])('denies direct navigation by %s to %s', async (role,path) => {
    await show(path, tokenFor({ role, exp: future() }));
    expect(screen.getByRole('heading', { name: 'Access denied' })).toBeInTheDocument();
  });

  it('keeps the student placeholder unreachable from staff role codes', async () => {
    await show('/enrollment/submission', tokenFor({ role: 'student', exp: future() }));
    expect(screen.getByRole('heading', { name: 'Access denied' })).toBeInTheDocument();
  });

  it('opens and closes mobile navigation', async () => {
    const original = window.matchMedia;
    window.matchMedia = query => ({ matches: query === '(max-width: 700px)', addEventListener: () => {}, removeEventListener: () => {} });
    try {
      const { container } = await show('/academic/grade-entry', tokenFor({ role: 'ROLE_TEACHER', exp: future() }));
      expect(screen.getByRole('button',{name:'Toggle navigation'})).toHaveAttribute('aria-expanded','false');
      fireEvent.click(screen.getByRole('button', { name: 'Toggle navigation' }));
      expect(container.querySelector('.app-shell')).toHaveClass('mobile-open');
      fireEvent.click(screen.getByRole('link', { name: 'Assigned Classes' }));
      expect(container.querySelector('.app-shell')).not.toHaveClass('mobile-open');
    } finally {
      window.matchMedia = original;
    }
  });
});

describe('Axios client', () => {
  it('adds the bearer token to requests and clears auth on a 401', async () => {
    const unauthorized = vi.fn();
    configureApiAuth('sample-token', unauthorized);
    api.defaults.adapter = async config => {
      expect(config.headers.get('Authorization')).toBe('Bearer sample-token');
      throw { config, response: { status: 401 } };
    };
    const rejection = await api.get('/example').catch(error => error);
    expect(rejection.response.status).toBe(401);
    expect(unauthorized).toHaveBeenCalledOnce();
  });
});
