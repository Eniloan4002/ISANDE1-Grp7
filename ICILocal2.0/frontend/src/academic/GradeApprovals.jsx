// ICI2-27 Grade Correction Review & Approval Dashboard (registrar approves or rejects teacher requests from ICI2-26).
import {useEffect,useState} from 'react';
import {useSearchParams} from 'react-router';
import {Confirm,Failure,Pager} from '../EnrollmentWorkspace.jsx';
import {useAuth} from '../auth/AuthContext.jsx';
import {decideGradeCorrection,downloadEvidence,getGradeCorrection,listGradeCorrections} from '../api/gradeCorrections.js';

const when=value=>new Date(value).toLocaleString();
const kb=size=>`${Math.max(1,Math.round(size/1024))} KB`;
// A grade is either a numeric value or a mark (INC, NG, DRP), so format both and only diff numbers.
const show=value=>value==null?'No grade':typeof value==='number'?value.toFixed(2):String(value);
const change=(from,to)=>typeof from==='number'&&typeof to==='number'?` (${to-from>0?'+':''}${(to-from).toFixed(2)})`:'';

function useLoad(loader,deps){
 const [state,setState]=useState({data:null,loading:true,error:''}),[tick,setTick]=useState(0);
 useEffect(()=>{
  let live=true;
  setState(prev=>({...prev,loading:true,error:''}));
  loader().then(data=>live&&setState({data,loading:false,error:''})).catch(e=>live&&setState({data:null,loading:false,error:e.message||'Request failed.'}));
  return ()=>{live=false;};
 // eslint-disable-next-line react-hooks/exhaustive-deps
 },[...deps,tick]);
 return {...state,reload:()=>setTick(value=>value+1)};
}

function Review({id,canDecide,onSaved}){
 const detail=useLoad(()=>getGradeCorrection(id),[id]);
 const [action,setAction]=useState(''),[remarks,setRemarks]=useState(''),[busy,setBusy]=useState(false),[confirm,setConfirm]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 const data=detail.data;
 async function decide(){
  setBusy(true);setError('');
  try{
   await decideGradeCorrection(id,{action,remarks,version:data.version});
   setConfirm(false);setAction('');setRemarks('');
   setNotice(action==='approve'?'Correction approved. The grade was updated and the decision was added to the audit trail.':'Correction rejected. The grade was not changed and the decision was added to the audit trail.');
   detail.reload();onSaved();
  }catch(e){setConfirm(false);setError(e.message);}
  finally{setBusy(false);}
 }
 async function download(file){
  setError('');
  try{await downloadEvidence(id,file);}catch(e){setError(e.message||'The file could not be downloaded.');}
 }
 return <section className="panel"><h2>Review correction request {data?.requestNumber??`#${id}`}</h2><div className="panel-body">
  <Failure error={detail.error} reload={detail.reload}/><Failure error={error}/>
  {notice&&<p className="notice" role="status">{notice}</p>}
  {detail.loading&&!data&&<p role="status">Loading request…</p>}
  {data&&<>
   <div className="setup-table-wrap"><table className="setup-table"><tbody>
    <tr><th>Student</th><td>{data.studentName} ({data.studentNumber})</td></tr>
    <tr><th>Subject / section</th><td>{data.subject} / {data.section}</td></tr>
    <tr><th>Grading period</th><td>{data.gradingPeriod}</td></tr>
    <tr><th>Requested by</th><td>{data.requestedBy} on {when(data.submittedAt)}</td></tr>
    <tr><th>Grade change</th><td><strong>{show(data.currentGrade)}</strong> to <strong>{show(data.requestedGrade)}</strong>{change(data.currentGrade,data.requestedGrade)}</td></tr>
    <tr><th>Status</th><td>{data.status}</td></tr>
   </tbody></table></div>
   <h3>Teacher's reason</h3><p>{data.reason}</p>
   <h3>Supporting evidence</h3>
   {data.evidence.length===0?<p>No files were attached to this request.</p>:<ul>{data.evidence.map(file=><li key={file.id}>{file.name} ({kb(file.size)}) <button type="button" className="button button-outline" onClick={()=>download(file)}>Download {file.name}</button></li>)}</ul>}
   <div className="component-row"><button className="button button-outline" disabled={busy||detail.loading} onClick={detail.reload}>Reload request</button></div>
   {data.status==='pending'&&canDecide&&<form onSubmit={event=>{event.preventDefault();setConfirm(true);}}>
    <p>Approving updates the student's grade and records your decision in the audit trail. Rejecting leaves the grade unchanged.</p>
    <label htmlFor="grade-action">Decision</label>
    <select id="grade-action" required value={action} disabled={busy} onChange={event=>setAction(event.target.value)}><option value="">Choose a decision</option><option value="approve">Approve: change the grade to {show(data.requestedGrade)}</option><option value="reject">Reject: keep the grade at {show(data.currentGrade)}</option></select>
    <label htmlFor="grade-remarks">Decision remarks</label>
    <textarea id="grade-remarks" required maxLength={2000} value={remarks} disabled={busy} onChange={event=>setRemarks(event.target.value)}/>
    <button className="button" disabled={busy||!action||!remarks.trim()}>Review decision</button>
   </form>}
   {data.status==='pending'&&!canDecide&&<p className="notice">Your role can view this request but not decide it.</p>}
   <h3>Audit trail</h3>
   <ol className="verification-history">{data.history.map(event=><li key={event.id}><strong>{event.status}</strong> / {event.actor||'System'} / {when(event.occurredAt)}<p>{event.reason||'No remarks'}</p></li>)}</ol>
  </>}
  {confirm&&<Confirm title={action==='approve'?'Approve grade correction?':'Reject grade correction?'} busy={busy} onCancel={()=>setConfirm(false)} onConfirm={decide}>
   <p>{action==='approve'?`Change ${data.studentName}'s ${data.subject} grade from ${show(data.currentGrade)} to ${show(data.requestedGrade)}.`:`Keep ${data.studentName}'s ${data.subject} grade at ${show(data.currentGrade)}.`}</p><p>{remarks}</p>
  </Confirm>}
 </div></section>;
}

export default function GradeApprovals(){
 const {roles}=useAuth();
 const canDecide=roles.includes('registrar');
 const [params,setParams]=useSearchParams(),[search,setSearch]=useState(''),[status,setStatus]=useState('pending'),[page,setPage]=useState(0);
 const selected=params.get('requestId');
 const list=useLoad(()=>listGradeCorrections({status,page}),[status,page]);
 const visible=(list.data?.items??[]).filter(row=>`${row.studentName} ${row.studentNumber} ${row.requestNumber} ${row.subject}`.toLowerCase().includes(search.trim().toLowerCase()));
 return <>
  <header className="page-heading"><p className="eyebrow">Academic records</p><h1>Grade Approvals</h1><p>Review post-lock grade correction requests and record a traceable decision.</p></header>
  <div className="component-row"><label>Request status<select value={status} onChange={event=>{setStatus(event.target.value);setPage(0);}}>{['pending','approved','rejected','all'].map(value=><option key={value}>{value}</option>)}</select></label></div>
  <section className="panel"><h2>Review queue</h2><div className="panel-body">
   <div className="dup-toolbar"><div><label htmlFor="grade-search">Search this page</label><input id="grade-search" type="search" value={search} onChange={event=>setSearch(event.target.value)} placeholder="Filter by student, subject or request number"/></div><p className="field-help">{list.data?.total??0} requests in the selected status. Search filters the current page.</p></div>
   <Failure error={list.error} reload={list.reload}/>
   {list.loading&&<p role="status">Loading requests…</p>}
   <div className="setup-table-wrap"><table className="setup-table"><thead><tr><th>Request</th><th>Student</th><th>Subject / section</th><th>Grade change</th><th>Status</th><th>Review</th></tr></thead><tbody>{visible.map(row=><tr key={row.id}>
    <td><strong>{row.requestNumber}</strong><small>{when(row.submittedAt)}</small></td>
    <td><strong>{row.studentName}</strong><small>{row.studentNumber}</small></td>
    <td>{row.subject}<small>{row.section} / {row.gradingPeriod}</small></td>
    <td>{show(row.currentGrade)} to {show(row.requestedGrade)}</td>
    <td>{row.status}</td>
    <td><button className="button button-outline" onClick={()=>setParams({...Object.fromEntries(params),requestId:String(row.id)})}>{row.status==='pending'&&canDecide?'Review':'View'} request {row.id}</button></td>
   </tr>)}</tbody></table></div>
   {!list.loading&&visible.length===0&&<p>No matching requests on this page. Teachers submit requests from their Grade Corrections screen.</p>}
   <Pager page={page} total={list.data?.total??0} onChange={setPage}/>
  </div></section>
  {selected&&/^\d+$/.test(selected)&&<Review key={selected} id={selected} canDecide={canDecide} onSaved={list.reload}/>}
 </>;
}