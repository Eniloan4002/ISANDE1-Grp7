import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { configureApiAuth } from '../api/client.js';
import { readClaims, rolesFromClaims } from './jwt.js';

const AuthContext = createContext(null);

export function AuthProvider({ children, initialToken = null }) {
  const [accessToken, updateToken] = useState(initialToken);
  const claims = useMemo(() => readClaims(accessToken), [accessToken]);
  const roles = useMemo(() => rolesFromClaims(claims), [claims]);
  const logout = useCallback(() => {
    configureApiAuth(null, null);
    updateToken(null);
  }, []);
  const setAccessToken = useCallback(token => {
    const nextToken = readClaims(token) ? token : null;
    configureApiAuth(nextToken, logout);
    updateToken(nextToken);
  }, [logout]);

  useEffect(() => {
    configureApiAuth(claims ? accessToken : null, logout);
    return () => configureApiAuth(null, null);
  }, [accessToken, claims, logout]);

  useEffect(() => {
    if (!claims) return undefined;
    let timer;
    const scheduleExpiry = () => {
      const remaining = claims.exp * 1000 - Date.now();
      if (remaining <= 0) logout();
      else timer = window.setTimeout(scheduleExpiry, Math.min(remaining, 2147483647));
    };
    scheduleExpiry();
    return () => window.clearTimeout(timer);
  }, [claims, logout]);

  const value = useMemo(() => ({
    accessToken: claims ? accessToken : null,
    claims,
    roles,
    isAuthenticated: Boolean(claims),
    setAccessToken,
    logout,
  }), [accessToken, claims, roles, setAccessToken, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used within AuthProvider');
  return context;
}
