import {useState} from 'react';
import {useNavigate} from 'react-router';
import {useAuth} from './auth/AuthContext.jsx';
import {Failure} from './EnrollmentWorkspace.jsx';
export default function AccountSecurity(){
 const navigate=useNavigate();
 const {user,changePassword}=useAuth(),[current,setCurrent]=useState(''),[password,setPassword]=useState(''),[repeat,setRepeat]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState('');
 async function save(event){event.preventDefault();setError('');if(password!==repeat){setError('New passwords do not match.');return;}setBusy(true);try{await changePassword(current,password);navigate('/sign-in',{replace:true,state:null});}catch(e){setError(e.message);setCurrent('');}finally{setBusy(false);}}
 return <><header className="page-heading"><p className="eyebrow">Staff account</p><h1>Account Security</h1><p>Change your password. Existing sessions will end after a successful change.</p></header>{user?.passwordChangeRequired&&<p className="notice">You must replace the temporary password before accessing other workflows.</p>}<section className="panel"><h2>Change password</h2><form className="panel-body" onSubmit={save}><Failure error={error}/>{[['current-password','Current password',current,setCurrent],['new-password','New password',password,setPassword],['repeat-password','Confirm new password',repeat,setRepeat]].map(([id,label,value,set])=><div key={id}><label htmlFor={id}>{label}</label><input id={id} type="password" autoComplete={id==='current-password'?'current-password':'new-password'} required minLength={id==='current-password'?undefined:12} maxLength={72} disabled={busy} value={value} onChange={event=>set(event.target.value)}/></div>)}<p className="field-help">Use at least 12 characters and no more than 72 UTF-8 bytes. Sign in again after saving.</p><button className="button" disabled={busy}>{busy?'Saving…':'Change password'}</button></form></section></>;
}
