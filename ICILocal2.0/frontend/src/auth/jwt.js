import { knownRoles } from '../routes/routeConfig.js';

// Browser decoding supports UI decisions only. The API must verify signatures and permissions.
export function readClaims(token, nowSeconds = Date.now() / 1000) {
  if (typeof token !== 'string') return null;
  const parts = token.split('.');
  if (parts.length !== 3 || parts.some(part => !part)) return null;

  try {
    const payload = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const bytes = Uint8Array.from(atob(payload), char => char.charCodeAt(0));
    const claims = JSON.parse(new TextDecoder().decode(bytes));
    if (!claims || typeof claims !== 'object' || Array.isArray(claims)) return null;
    if (typeof claims.exp !== 'number' || !Number.isFinite(claims.exp) || claims.exp <= nowSeconds) return null;
    if (claims.nbf !== undefined && (typeof claims.nbf !== 'number' || claims.nbf > nowSeconds)) return null;
    return claims;
  } catch {
    return null;
  }
}

export function rolesFromClaims(claims) {
  if (!claims) return [];
  const raw = Array.isArray(claims.roles) ? claims.roles : [claims.role];
  return [...new Set(raw.filter(role => typeof role === 'string' && knownRoles.includes(role)))];
}
