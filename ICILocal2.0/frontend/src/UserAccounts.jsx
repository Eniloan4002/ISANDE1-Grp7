import React, { useEffect, useRef, useState } from 'react';
import { apiRequest } from './api.js';
import './userAccounts.css';

const base='/api/admin/users';
const blank=()=>({username:'',email:'',fullName:'',password:'',grants:[]});
const request=(path='',options={})=>apiRequest(base+path,options);

export default function UserAccounts() {
  const [accounts,setAccounts]=useState([]),[options,setOptions]=useState({roles:[],campuses:[]});
  const [form,setForm]=useState(blank),[editing,setEditing]=useState(null),[resetting,setResetting]=useState(null),[newPassword,setNewPassword]=useState('');
  const resetRef=useRef(null);
  const [loading,setLoading]=useState(true),[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
  async function load() {
    setLoading(true);
    try { const [users,reference]=await Promise.all([request(),request('/options')]);setAccounts(users);setOptions(reference); }
    catch(e){setError(e.message);} finally {setLoading(false);}
  }
  useEffect(()=>{load();},[]);
  useEffect(()=>{if(resetting)resetRef.current?.scrollIntoView({behavior:'smooth',block:'start'});},[resetting]);
  function clearForm(){setEditing(null);setForm(blank());}
  function edit(user){setEditing(user);setForm({username:user.username,email:user.email,fullName:user.fullName,password:'',grants:user.grants});setError('');setNotice('');window.scrollTo({top:0,behavior:'smooth'});}
  function toggleGrant(campusId,role,checked) {
    setForm(current=>({...current,grants:checked?[...current.grants,{campusId,role}]:current.grants.filter(g=>g.campusId!==campusId||g.role!==role)}));
  }
  async function save(event){
    event.preventDefault();setError('');setNotice('');
    if(!form.grants.length){setError('Choose at least one role and campus.');return;}
    setBusy(true);
    try {await request(editing?`/${editing.id}`:'',{method:editing?'PUT':'POST',body:JSON.stringify(form)});setNotice(editing?'Account updated.':'Account created.');clearForm();await load();}
    catch(e){setError(e.message);}finally{setBusy(false);}
  }
  async function changeStatus(user){
    const active=!user.active;
    if(!window.confirm(`${active?'Activate':'Deactivate'} ${user.fullName}?${active?'':' They will be signed out.'}`))return;
    setBusy(true);setError('');setNotice('');
    try{await request(`/${user.id}/status`,{method:'PUT',body:JSON.stringify({active})});setNotice(`Account ${active?'activated':'deactivated'}.`);await load();}
    catch(e){setError(e.message);}finally{setBusy(false);}
  }
  async function resetPassword(event){
    event.preventDefault();
    if(!window.confirm(`Reset the password for ${resetting.fullName}? Existing sessions will be signed out.`))return;
    setBusy(true);setError('');setNotice('');
    try{await request(`/${resetting.id}/password`,{method:'PUT',body:JSON.stringify({password:newPassword})});setResetting(null);setNewPassword('');setNotice('Password reset. Share the new password securely with the staff member.');}
    catch(e){setError(e.message);}finally{setBusy(false);}
  }
  const roleName=code=>options.roles.find(role=>role.code===code)?.name||code;
  const campusName=id=>options.campuses.find(campus=>campus.id===id)?.name||'Unavailable campus';
  return <>
    <div className="page-heading"><h1>User Accounts</h1><p>Manage school personnel accounts and their campus access.</p></div>
    {error&&<div className="notice notice-error" role="alert">{error}</div>}
    {notice&&<div className="notice" role="status">{notice}</div>}
    {loading?<p role="status">Loading accounts…</p>:<div className="account-layout">
      <section className="panel"><h2>{editing?'Edit account':'Create account'}</h2><form className="panel-body account-form" onSubmit={save}>
        <label htmlFor="account-name">Full name</label><input id="account-name" required maxLength={200} value={form.fullName} disabled={busy} onChange={e=>setForm({...form,fullName:e.target.value})}/>
        <label htmlFor="account-username">Username</label><input id="account-username" required maxLength={100} autoComplete="off" value={form.username} disabled={busy} onChange={e=>setForm({...form,username:e.target.value})}/>
        <label htmlFor="account-email">Email</label><input id="account-email" type="email" required maxLength={254} value={form.email} disabled={busy} onChange={e=>setForm({...form,email:e.target.value})}/>
        {!editing&&<><label htmlFor="account-password">Initial password</label><input id="account-password" type="password" autoComplete="new-password" required minLength={12} value={form.password} disabled={busy} onChange={e=>setForm({...form,password:e.target.value})}/><p className="field-help">At least 12 characters and at most 72 UTF-8 bytes. Share it securely with the staff member.</p></>}
        <fieldset className="account-grants"><legend>Roles and campuses</legend><p className="field-help">Choose at least one role for a campus.</p>
          {options.campuses.map(campus=><fieldset key={campus.id}><legend>{campus.name}</legend>{options.roles.map(role=><label className="account-grant" key={role.code}><input type="checkbox" disabled={busy} checked={form.grants.some(g=>g.campusId===campus.id&&g.role===role.code)} onChange={e=>toggleGrant(campus.id,role.code,e.target.checked)}/>{role.name}</label>)}</fieldset>)}
        </fieldset><div className="component-row"><button className="button" disabled={busy}>{busy?'Saving…':editing?'Save changes':'Create account'}</button>{editing&&<button type="button" className="button button-outline" disabled={busy} onClick={clearForm}>Cancel edit</button>}</div>
      </form></section>
      <section className="panel"><h2>Personnel accounts</h2><div className="panel-body">{accounts.length?<div className="setup-table-wrap"><table className="setup-table"><thead><tr><th scope="col">Name</th><th scope="col">Access</th><th scope="col">Status</th><th scope="col">Actions</th></tr></thead><tbody>{accounts.map(user=><tr key={user.id}><td><strong>{user.fullName}</strong><br/>{user.username}<br/>{user.email}</td><td>{user.grants.map(g=><div key={`${g.campusId}-${g.role}`}>{roleName(g.role)} · {campusName(g.campusId)}</div>)}</td><td>{user.active?'Active':'Inactive'}</td><td><div className="table-actions"><button type="button" className="button button-outline" disabled={busy} aria-label={`Edit ${user.fullName}`} onClick={()=>edit(user)}>Edit</button><button type="button" className="button button-outline" disabled={busy} aria-label={`${user.active?'Deactivate':'Activate'} ${user.fullName}`} onClick={()=>changeStatus(user)}>{user.active?'Deactivate':'Activate'}</button><button type="button" className="button button-outline" disabled={busy} aria-label={`Reset password for ${user.fullName}`} onClick={()=>{setResetting(user);setNewPassword('');setError('');}}>Reset password</button></div></td></tr>)}</tbody></table></div>:<p>No accounts are available in your campus scope.</p>}</div></section>
    </div>}
    {resetting&&<section ref={resetRef} className="panel account-reset" aria-labelledby="reset-password-title"><h2 id="reset-password-title">Reset password for {resetting.fullName}</h2><form className="panel-body account-form" onSubmit={resetPassword}><label htmlFor="reset-password">New password</label><input id="reset-password" type="password" autoComplete="new-password" minLength={12} required value={newPassword} disabled={busy} onChange={e=>setNewPassword(e.target.value)}/><p className="field-help">At least 12 characters and at most 72 UTF-8 bytes. Existing sessions will be signed out.</p><div className="component-row"><button className="button" disabled={busy}>Reset password</button><button type="button" className="button button-outline" disabled={busy} onClick={()=>{setResetting(null);setNewPassword('');}}>Cancel</button></div></form></section>}
  </>;
}
