// Integrated from Pauline's PR #10. ICI2-98 moved the checklist into a right-hand drawer so the list stays visible.
import {useEffect,useRef,useState} from 'react';
import {Link,useSearchParams} from 'react-router';
import {Failure,Pager,useDebouncedValue,useResource} from '../EnrollmentWorkspace.jsx';
import RequirementsDrawer from './RequirementsDrawer.jsx';
import './enrollment.css';

export default function RequirementsScreen(){
  const [params,setParams]=useSearchParams(),[search,setSearch]=useState(''),[status,setStatus]=useState(params.get('requirements')==='open'?'Open':''),[program,setProgram]=useState(''),[page,setPage]=useState(0);
  const selected=params.get('applicationId');
  const effectiveSearch=useDebouncedValue(search);
  const reference=useResource('/api/enrollment/reference-data');
  const query=new URLSearchParams({search:effectiveSearch,requirementsStatus:status,programId:program,page:String(page),size:'25'});
  const directory=useResource(`/api/enrollment/applications?${query}`);
  const rows=directory.data?.items??[];
  // Return focus to the row that opened the drawer once the drawer has unmounted. A save reloads the list and
  // replaces the row's button, so find it by application id rather than keeping the element.
  const lastOpened=useRef(null);
  useEffect(()=>{
    if(selected){lastOpened.current=selected;return;}
    if(lastOpened.current){document.querySelector(`[data-open-application="${lastOpened.current}"]`)?.focus();lastOpened.current=null;}
  },[selected]);
  return <div className="enrollment-workspace"><header className="page-heading enrollment-heading"><div><p className="eyebrow">Enrollment management</p><h1>Requirements</h1><p>Track submitted credentials and review their verification history.</p></div><Link className="button button-outline" to="/enrollment/queue">Back to Queue</Link></header>
    <Failure error={reference.error} reload={reference.reload}/>
    <section className="panel enrollment-panel" aria-labelledby="requirements-table-title"><div className="enrollment-panel-heading"><div><h2 id="requirements-table-title">Applicant Requirements</h2><p>Open an applicant to review and update their checklist.</p></div></div>
      <div className="enrollment-filters requirements-filters"><label>Search<input type="search" aria-label="Search requirements" maxLength={200} value={search} onChange={event=>{setSearch(event.target.value);setPage(0);}} placeholder="Name, application number or LRN"/></label>
        <label>Status<select aria-label="Filter by requirement status" value={status} onChange={event=>{setStatus(event.target.value);setPage(0);}}><option value="">All statuses</option><option value="Open">Open (pending or incomplete)</option>{['Complete','Incomplete','Pending'].map(value=><option key={value}>{value}</option>)}</select></label>
        <label>Program<select aria-label="Filter by program" value={program} onChange={event=>{setProgram(event.target.value);setPage(0);}}><option value="">All programs</option>{reference.data?.programs.map(item=><option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
      </div><Failure error={directory.error} reload={directory.reload}/>{directory.loading&&<p role="status">Loading applications…</p>}
      {!directory.loading&&!directory.error&&(rows.length?<div className="enrollment-table-wrap"><table className="enrollment-table requirements-table"><thead><tr><th>Applicant</th><th>App ID</th><th>Category</th><th>Program</th><th>Overall status</th><th>Last updated</th><th>Action</th></tr></thead><tbody>{rows.map(row=><tr key={row.id}><td><strong>{row.name}</strong><small>LRN {row.lrn||'Not provided'}</small></td><td>{row.applicationNumber}</td><td>{row.category}</td><td>{row.program}</td><td><span className={`status status-${row.requirementsStatus==='Complete'?'success':row.requirementsStatus==='Incomplete'?'danger':'warning'}`}>{row.requirementsStatus}</span></td><td>{row.requirementsUpdatedAt?new Date(row.requirementsUpdatedAt).toLocaleString():'Not recorded'}</td><td><button className="button button-outline enrollment-view" data-open-application={row.id} onClick={()=>setParams({applicationId:String(row.id)})} aria-label={`Open ${row.applicationNumber}`}>View</button></td></tr>)}</tbody></table></div>:<div className="empty-state enrollment-empty"><h3>No matching applicants</h3><p>Try changing or clearing the search and filters.</p></div>)}
      <Pager page={page} total={directory.data?.total??0} onChange={setPage} disabled={directory.loading}/>
    </section>{selected&&/^\d+$/.test(selected)&&<RequirementsDrawer key={selected} id={selected} onClose={()=>setParams({})} onSaved={directory.reload}/>}
  </div>;
}
