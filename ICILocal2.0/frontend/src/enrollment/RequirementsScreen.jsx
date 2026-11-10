// Integrated from Pauline's PR #10: preserve the directory and checklist dialog,
// with authenticated API data and the shared versioned verification editor.
import {useEffect,useRef,useState} from 'react';
import {Link,useSearchParams} from 'react-router';
import {Failure,Pager,Requirements,useDebouncedValue,useResource} from '../EnrollmentWorkspace.jsx';
import './enrollment.css';

function ChecklistDialog({id,onClose,onSaved}){
  const ref=useRef(null),detail=useResource(`/api/intake/applications/${id}`);
  const [revision,setRevision]=useState(0),[notice,setNotice]=useState('');
  useEffect(()=>{const previous=document.activeElement,dialog=ref.current;dialog.showModal();return()=>{dialog.close();previous?.focus();};},[]);
  function saved(message){setNotice(message);setRevision(value=>value+1);detail.reload();onSaved();}
  const data=detail.data;
  const workflow=data?{...data.workflow,application:{...data.workflow.application,guardians:data.profile?.guardians}}:null;
  return <dialog ref={ref} className="requirements-dialog" aria-labelledby="requirements-dialog-title" onCancel={event=>{event.preventDefault();onClose();}}>
    <header className="requirements-dialog-header"><div><p className="eyebrow">Enrollment management</p><h2 id="requirements-dialog-title">Requirements Checklist</h2></div><button className="requirements-close" aria-label="Close checklist" onClick={onClose}>×</button></header>
    <div className="requirements-dialog-body"><Failure error={detail.error} reload={detail.reload}/>{detail.loading&&!data&&<p role="status">Loading checklist…</p>}{notice&&<p className="notice" role="status">{notice}</p>}
      {data&&<><div className="requirements-meta"><span><strong>Student:</strong> {data.firstName} {data.lastName}</span><span><strong>App ID:</strong> {data.applicationNumber}</span><span><strong>Category:</strong> {data.category}</span></div><button className="button button-outline" onClick={detail.reload}>Reload checklist</button><Requirements detail={workflow} onSaved={saved} revision={revision}/><Link className="button button-outline" to={`/enrollment/attendance?applicationId=${id}`}>Continue to attendance</Link></>}
    </div>
  </dialog>;
}
export default function RequirementsScreen(){
  const [params,setParams]=useSearchParams(),[search,setSearch]=useState(''),[status,setStatus]=useState(''),[program,setProgram]=useState(''),[page,setPage]=useState(0);
  const selected=params.get('applicationId');
  const effectiveSearch=useDebouncedValue(search);
  const reference=useResource('/api/enrollment/reference-data');
  const query=new URLSearchParams({search:effectiveSearch,requirementsStatus:status,programId:program,page:String(page),size:'25'});
  const directory=useResource(`/api/enrollment/applications?${query}`);
  const rows=directory.data?.items??[];
  return <div className="enrollment-workspace"><header className="page-heading enrollment-heading"><div><p className="eyebrow">Enrollment management</p><h1>Requirements</h1><p>Track submitted credentials and review their verification history.</p></div><Link className="button button-outline" to="/enrollment/queue">Back to Queue</Link></header>
    <Failure error={reference.error} reload={reference.reload}/>
    <section className="panel enrollment-panel" aria-labelledby="requirements-table-title"><div className="enrollment-panel-heading"><div><h2 id="requirements-table-title">Applicant Requirements</h2><p>Open an applicant to review and update their checklist.</p></div></div>
      <div className="enrollment-filters requirements-filters"><label>Search<input type="search" aria-label="Search requirements" maxLength={200} value={search} onChange={event=>{setSearch(event.target.value);setPage(0);}} placeholder="Name, application number or LRN"/></label>
        <label>Status<select aria-label="Filter by requirement status" value={status} onChange={event=>{setStatus(event.target.value);setPage(0);}}><option value="">All statuses</option>{['Complete','Incomplete','Pending'].map(value=><option key={value}>{value}</option>)}</select></label>
        <label>Program<select aria-label="Filter by program" value={program} onChange={event=>{setProgram(event.target.value);setPage(0);}}><option value="">All programs</option>{reference.data?.programs.map(item=><option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
      </div><Failure error={directory.error} reload={directory.reload}/>{directory.loading&&<p role="status">Loading applications…</p>}
      {!directory.loading&&!directory.error&&(rows.length?<div className="enrollment-table-wrap"><table className="enrollment-table requirements-table"><thead><tr><th>Applicant</th><th>App ID</th><th>Category</th><th>Program</th><th>Overall status</th><th>Last updated</th><th>Action</th></tr></thead><tbody>{rows.map(row=><tr key={row.id}><td><strong>{row.name}</strong><small>LRN {row.lrn||'Not provided'}</small></td><td>{row.applicationNumber}</td><td>{row.category}</td><td>{row.program}</td><td><span className={`status status-${row.requirementsStatus==='Complete'?'success':row.requirementsStatus==='Incomplete'?'danger':'warning'}`}>{row.requirementsStatus}</span></td><td>{row.requirementsUpdatedAt?new Date(row.requirementsUpdatedAt).toLocaleString():'Not recorded'}</td><td><button className="button button-outline enrollment-view" onClick={()=>setParams({applicationId:String(row.id)})} aria-label={`Open ${row.applicationNumber}`}>View</button></td></tr>)}</tbody></table></div>:<div className="empty-state enrollment-empty"><h3>No matching applicants</h3><p>Try changing or clearing the search and filters.</p></div>)}
      <Pager page={page} total={directory.data?.total??0} onChange={setPage} disabled={directory.loading}/>
    </section>{selected&&/^\d+$/.test(selected)&&<ChecklistDialog key={selected} id={selected} onClose={()=>setParams({})} onSaved={directory.reload}/>}
  </div>;
}
