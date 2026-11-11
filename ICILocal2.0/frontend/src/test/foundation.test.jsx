import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import App from '../App.jsx';
import { AuthProvider } from '../auth/AuthContext.jsx';
import { readClaims, rolesFromClaims } from '../auth/jwt.js';
import { api, configureApiAuth } from '../api/client.js';
import {setAccessToken} from '../api.js';
import { pages, firstAllowedPath } from '../routes/routeConfig.js';

function tokenFor(claims) {
  const payload = btoa(JSON.stringify(claims)).replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `eyJhbGciOiJub25lIn0.${payload}.signature`;
}

const future = () => Math.floor(Date.now() / 1000) + 3600;

async function show(path, token = null) {
  setAccessToken(token);
  api.defaults.adapter=async config=>({status:200,statusText:'OK',headers:{},config,data:config.url.startsWith('/setup/')?[]:config.url.startsWith('/admin/accounts/reference-data')?{roles:[],campuses:[]}:config.url.startsWith('/admin/accounts')?{items:[],total:0}:(config.url.startsWith('/enrollment/reference-data')||config.url.startsWith('/intake/reference-data'))?{campuses:[],terms:[],programs:[],advisers:[],vouchers:[]}:config.url.startsWith('/duplicate-cases')||config.url.startsWith('/enrollment/applications')||config.url.startsWith('/sections')?{items:[],total:0}:{userId:1,username:'test',fullName:'Test Staff',grants:rolesFromClaims(readClaims(token)).map(role=>({campusId:1,campusCode:'ILIGAN',role}))}});
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
    expect(rolesFromClaims(readClaims(tokenFor({ roles: ['registrar', 'admin', 'unknown', 'admin'], exp: future() })))).toEqual(['registrar', 'admin']);
    expect(rolesFromClaims({roles:['ROLE_ADMIN','ROLE_REGISTRAR','ROLE_TEACHER']})).toEqual(['admin','registrar','teacher']);
  });
});

describe('routes', () => {
  it('reserves every prototype page with a unique clean path', () => {
    expect(pages).toHaveLength(33);
    expect(new Set(pages.map(page => page.path)).size).toBe(pages.length);
    expect(pages.every(page => page.allowedRoles.length > 0 && !page.path.endsWith('.html'))).toBe(true);
  });

  it('sends unauthenticated visitors to the staff sign-in screen', async () => {
    await show('/academic/grade-entry');
    expect(screen.getByRole('heading', { name: 'Staff sign in' })).toBeInTheDocument();
  });

  it('allows the matching role and hides other navigation sections', async () => {
    await show('/academic/grade-entry', tokenFor({ role: 'teacher', exp: future() }));
    expect(screen.getByRole('heading', { name: 'Grade Entry' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Assigned Classes' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'User Accounts' })).not.toBeInTheDocument();
  });

  it('denies a role that lacks access', async () => {
    await show('/administration/users', tokenFor({ role: 'teacher', exp: future() }));
    expect(screen.getByRole('heading', { name: 'Access denied' })).toBeInTheDocument();
  });

  it.each(pages)('opens $path for its assigned role', async page => {
    await show(page.path, tokenFor({ role: page.allowedRoles[0], exp: future() }));
    expect(screen.getByRole('heading', { name: page.label })).toBeInTheDocument();
  });

  it('opens and closes mobile navigation', async () => {
    const original = window.matchMedia;
    window.matchMedia = query => ({ matches: query === '(max-width: 700px)', addEventListener: () => {}, removeEventListener: () => {} });
    try {
      const { container } = await show('/academic/grade-entry', tokenFor({ role: 'teacher', exp: future() }));
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

describe('documented staff role boundaries',()=>{
  it('maps all seven backend staff roles',()=>{
    expect(rolesFromClaims({roles:['ROLE_ADMIN','ROLE_REGISTRAR','ROLE_PRINCIPAL','ROLE_ADVISER','ROLE_TEACHER','ROLE_GUIDANCE','ROLE_CASHIER_REF']})).toEqual(['admin','registrar','principal','adviser','teacher','guidance','cashier_ref']);
    expect(firstAllowedPath(['registrar'])).toBe('/enrollment/queue');
    expect(firstAllowedPath(['principal'])).toBe('/academic/registrar-records');
    expect(firstAllowedPath(['cashier_ref'])).toBe('/cashiering/students');
  });
  it.each(['principal','cashier_ref','teacher','adviser','guidance'])('keeps %s out of administrator setup',async role=>{
    await show('/setup',tokenFor({roles:[role],exp:future()}));
    expect(screen.getByRole('heading',{name:'Access denied'})).toBeInTheDocument();
  });
  it('shares principal review without giving grade entry access',async()=>{
    const review=pages.find(page=>page.path==='/academic/grade-approvals');
    expect(review.allowedRoles).toEqual(['registrar','principal']);
    await show('/academic/grade-entry',tokenFor({roles:['principal'],exp:future()}));
    expect(screen.getByRole('heading',{name:'Access denied'})).toBeInTheDocument();
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


describe('sign-in return URL permissions',()=>{
  it('sends a teacher to their landing screen after a saved administrator-only URL',async()=>{
    setAccessToken(null);
    const profile={userId:77,username:'teacher-test',fullName:'Teacher Test',grants:[{role:'ROLE_TEACHER',campusId:1,campusCode:'ILIGAN'}]};
    api.defaults.adapter=async config=>({status:200,statusText:'OK',headers:{},config,data:config.url==='/auth/login'?{accessToken:tokenFor({roles:['ROLE_TEACHER'],exp:future()}),user:profile}:profile});
    render(<MemoryRouter initialEntries={['/administration/users']}><AuthProvider><App/></AuthProvider></MemoryRouter>);
    fireEvent.change(await screen.findByLabelText('Username'),{target:{value:'teacher-test'}});fireEvent.change(screen.getByLabelText('Password',{exact:true}),{target:{value:'ChosenPassword123'}});fireEvent.click(screen.getByRole('button',{name:'Sign in',exact:true}));
    expect(await screen.findByRole('heading',{name:'Grade Entry',exact:true})).toBeInTheDocument();expect(screen.queryByRole('heading',{name:'Access denied'})).not.toBeInTheDocument();
    expect(screen.getByText(/Mock-only workflow/)).toBeInTheDocument();
  });
});

it('forces a temporary-password session onto Account Security before business routes',async()=>{
 setAccessToken(tokenFor({roles:['ROLE_TEACHER'],exp:future()}));
 const profile={userId:91,username:'recovered-teacher',fullName:'Recovered Teacher',passwordChangeRequired:true,grants:[{role:'ROLE_TEACHER',campusId:1}]};
 api.defaults.adapter=async config=>({status:200,statusText:'OK',headers:{},config,data:profile});
 render(<MemoryRouter initialEntries={['/academic/grade-entry']}><AuthProvider><App/></AuthProvider></MemoryRouter>);
 expect(await screen.findByRole('heading',{name:'Account Security',exact:true})).toBeInTheDocument();expect(screen.queryByRole('heading',{name:'Grade Entry',exact:true})).not.toBeInTheDocument();
});
