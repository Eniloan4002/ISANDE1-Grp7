import React,{createContext,useContext,useEffect,useState} from 'react';
import {Navigate,useLocation,useNavigate} from 'react-router';
import {apiRequest,hasAccessToken,setAccessToken,getAccessToken} from './api.js';
import {readClaims,rolesFromClaims} from './auth/jwt.js';
import {firstAllowedPath,canAccessPath} from './routes/routeConfig.js';
const AuthContext=createContext(null);
export const useAuth=()=>useContext(AuthContext);
export function AuthProvider({children}) {
  const [user,setUser]=useState(null);
  const [loading,setLoading]=useState(hasAccessToken);
  const [sessionMessage,setSessionMessage]=useState('');
  useEffect(()=>{
    let active=true;
    const expired=()=>{setAccessToken('');setUser(null);setSessionMessage('Your session ended. Sign in again to continue.');};
    window.addEventListener('auth:expired',expired);
    if(hasAccessToken())apiRequest('/api/auth/me').then(profile=>{if(active)setUser(profile);}).catch(()=>{if(active)expired();}).finally(()=>{if(active)setLoading(false);});
    return()=>{active=false;window.removeEventListener('auth:expired',expired);};
  },[]);
  useEffect(()=>{
    if(!user)return;
    const claims=readClaims(getAccessToken());
    if(!claims){window.dispatchEvent(new Event('auth:expired'));return;}
    let timer;const schedule=()=>{const remaining=claims.exp*1000-Date.now();if(remaining<=0)window.dispatchEvent(new Event('auth:expired'));else timer=setTimeout(schedule,Math.min(remaining,2147483647));};schedule();return()=>clearTimeout(timer);
  },[user]);
  async function signIn(username,password) {
    const result=await apiRequest('/api/auth/login',{method:'POST',body:JSON.stringify({username,password})});
    setAccessToken(result.accessToken);setUser(result.user);setSessionMessage('');return result.user;
  }
  async function signOut() {
    // Preserve a retryable session if the server cannot confirm revocation.
    await apiRequest('/api/auth/logout',{method:'POST'});
    setAccessToken('');setUser(null);setSessionMessage('You have signed out.');
  }
  async function changePassword(currentPassword,newPassword){
    await apiRequest('/api/auth/change-password',{method:'POST',body:JSON.stringify({currentPassword,newPassword})});
    setAccessToken('');setUser(null);setSessionMessage('Password changed. Sign in with your new password.');
  }
  const roles=rolesFromClaims({roles:user?.grants.map(grant=>grant.role)||[]});
  return <AuthContext.Provider value={{user,loading,signIn,signOut,changePassword,sessionMessage,roles,isAuthenticated:Boolean(user),logout:signOut}}>{children}</AuthContext.Provider>;
}
export function RequireSetupStaff({children}) {
  const {user,loading}=useAuth();const location=useLocation();
  if(loading)return <p role="status">Checking staff session…</p>;
  if(!user)return <Navigate to="/login" replace state={{from:location.pathname}}/>;
  if(!user.grants.some(grant=>['ROLE_ADMIN','ROLE_REGISTRAR'].includes(grant.role)))return <><h1>Access restricted</h1><p>Your account is signed in but does not have academic setup access.</p></>;
  return children;
}
export function LoginPage() {
  const {user,loading,signIn,sessionMessage}=useAuth();
  const [username,setUsername]=useState(''),[password,setPassword]=useState(''),[error,setError]=useState(''),[busy,setBusy]=useState(false);
  const [passwordVisible,setPasswordVisible]=useState(false);
  const location=useLocation(),navigate=useNavigate();
  const from=location.state?.from;
  const requested=typeof from==='string'?from:from?`${from.pathname}${from.search||''}${from.hash||''}`:undefined;
  const requestedPath=requested?.split(/[?#]/)[0];
  const roles=rolesFromClaims({roles:user?.grants.map(grant=>grant.role)||[]});
  const destination=user?.passwordChangeRequired?'/account/security':canAccessPath(requestedPath,roles)?requested:firstAllowedPath(roles);
  if(user&&!loading)return <Navigate to={destination} replace/>;
  async function submit(event){event.preventDefault();setBusy(true);setPasswordVisible(false);setError('');try{const profile=await signIn(username.trim(),password);const signedInRoles=rolesFromClaims({roles:profile.grants.map(grant=>grant.role)});setPassword('');navigate(profile.passwordChangeRequired?'/account/security':canAccessPath(requestedPath,signedInRoles)?requested:firstAllowedPath(signedInRoles),{replace:true});}catch(error){setError(error.message);setPassword('');}finally{setBusy(false);}}
  return <div className="login-page"><section className="login-brand"><div className="brand"><span className="brand-mark">ICI</span><strong>ICI Local 2.0</strong></div><h1>Iligan Computer Institute</h1><p>Student records and enrollment management</p><small>Iligan Main / Kapatagan / Bugo</small></section><main className="login-panel"><h2>Staff sign in</h2><p>Use your school personnel account to continue.</p><p className="field-help">Forgot your password? Contact your school administrator for identity verification and a temporary password.</p>{sessionMessage&&<p role="status">{sessionMessage}</p>}{error&&<div role="alert" className="notice notice-error">{error}</div>}<form onSubmit={submit}><label htmlFor="login-username">Username</label><input id="login-username" autoComplete="username" maxLength={100} required value={username} disabled={busy||loading} onChange={event=>setUsername(event.target.value)}/><div className="password-label-row"><label htmlFor="login-password">Password</label><button className="password-visibility-toggle" type="button" aria-controls="login-password" aria-pressed={passwordVisible} disabled={busy||loading} onClick={()=>setPasswordVisible(visible=>!visible)}>{passwordVisible?'Hide password':'Show password'}</button></div><input id="login-password" type={passwordVisible?'text':'password'} autoComplete="current-password" required maxLength={72} value={password} disabled={busy||loading} onChange={event=>setPassword(event.target.value)}/><button className="button" disabled={busy||loading}>{busy?'Signing in…':'Sign in'}</button></form></main></div>;
}
