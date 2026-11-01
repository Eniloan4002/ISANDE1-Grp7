import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import App from '../App.jsx';
import { AuthProvider } from '../auth/AuthContext.jsx';
import { readClaims, rolesFromClaims } from '../auth/jwt.js';
import { api, configureApiAuth } from '../api/client.js';
import { pages } from '../routes/routeConfig.js';

function tokenFor(claims) {
  const payload = btoa(JSON.stringify(claims)).replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `eyJhbGciOiJub25lIn0.${payload}.signature`;
}

const future = () => Math.floor(Date.now() / 1000) + 3600;

function show(path, token = null) {
  return render(<MemoryRouter initialEntries={[path]}><AuthProvider initialToken={token}><App /></AuthProvider></MemoryRouter>);
}

afterEach(() => {
  cleanup();
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
  });
});

describe('routes', () => {
  it('reserves every prototype page with a unique clean path', () => {
    expect(pages).toHaveLength(25);
    expect(new Set(pages.map(page => page.path)).size).toBe(pages.length);
    expect(pages.every(page => page.allowedRoles.length > 0 && !page.path.endsWith('.html'))).toBe(true);
  });

  it('sends unauthenticated visitors to the sign-in placeholder', () => {
    show('/academic/grade-entry');
    expect(screen.getByRole('heading', { name: 'Sign in to ICI Local' })).toBeInTheDocument();
  });

  it('allows the matching role and hides other navigation sections', () => {
    show('/academic/grade-entry', tokenFor({ role: 'teacher', exp: future() }));
    expect(screen.getByRole('heading', { name: 'Grade Entry' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Assigned Classes' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'User Accounts' })).not.toBeInTheDocument();
  });

  it('denies a role that lacks access', () => {
    show('/administration/users', tokenFor({ role: 'teacher', exp: future() }));
    expect(screen.getByRole('heading', { name: 'Access denied' })).toBeInTheDocument();
  });

  it.each(pages)('opens $path for its assigned role', page => {
    show(page.path, tokenFor({ role: page.allowedRoles[0], exp: future() }));
    expect(screen.getByRole('heading', { name: page.label })).toBeInTheDocument();
  });

  it('opens and closes mobile navigation', () => {
    const original = window.matchMedia;
    window.matchMedia = query => ({ matches: query === '(max-width: 700px)', addEventListener: () => {}, removeEventListener: () => {} });
    try {
      const { container } = show('/academic/grade-entry', tokenFor({ role: 'teacher', exp: future() }));
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
