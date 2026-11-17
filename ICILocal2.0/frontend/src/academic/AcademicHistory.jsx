import {useState} from 'react';
import {useSearchParams} from 'react-router';
import {Failure,Pager,useDebouncedValue,useResource} from '../EnrollmentWorkspace.jsx';
import {gradeFor,gradeText,periodColumns,statusLabel} from './historyFormat.js';

const tones={Complete:'success',enrolled:'success',submitted:'success',Incomplete:'danger',missing:'danger',invalid:'danger'};
const Status=({value})=><span className={`status status-${tones[value]??'warning'}`}>{statusLabel(value)}</span>;
const fullName=row=>[row.first_name,row.middle_name,row.last_name].filter(Boolean).join(' ');

function StudentPicker({selected,onSelect}){
  const [search,setSearch]=useState(''),[page,setPage]=useState(0);
  const query=useDebouncedValue(search.trim());
  const list=useResource(`/api/students?${new URLSearchParams({search:query,page:String(page),size:'10'})}`);
  const rows=list.data?.items??[];
  return <section className="panel"><h2>Find a student</h2><div className="panel-body">
    <label htmlFor="history-search">Name, LRN or student number</label>
    <input id="history-search" type="search" maxLength={150} value={search} onChange={event=>{setSearch(event.target.value);setPage(0);}}/>
    <Failure error={list.error} reload={list.reload}/>
    {list.loading&&<p role="status">Loading students…</p>}
    {rows.length>0&&<div className="setup-table-wrap"><table className="setup-table"><thead><tr><th>Student</th><th>LRN</th><th>Record</th><th>Action</th></tr></thead><tbody>
      {rows.map(row=><tr key={row.id} className={String(row.id)===selected?'selected-row':''}>
        <td><strong>{fullName(row)}</strong><small>{row.student_number}</small></td><td>{row.lrn||'Not recorded'}</td>
        <td>{row.active?'Active':'Archived'}</td>
        <td><button className="button button-outline" aria-label={`Open history for ${row.student_number}`} onClick={()=>onSelect(String(row.id))}>Open history</button></td>
      </tr>)}
    </tbody></table></div>}
    {!list.loading&&!list.error&&rows.length===0&&<p>No students match this search.</p>}
    <Pager page={page} size={10} total={list.data?.total??0} onChange={setPage} disabled={list.loading}/>
  </div></section>;
}

function TermGrades({term}){
  const columns=periodColumns(term);
  return <section className="panel"><h2>{term.academicYear} / {term.term}</h2><div className="panel-body">
    <p className="field-help">{term.program} / {term.section?`${term.section}, Grade ${String(term.gradeLevel).replace(/^Grade\s+/i,'')}`:'No section assigned'} / <Status value={term.status}/></p>
    {term.subjects.length?<div className="setup-table-wrap"><table className="setup-table"><thead><tr><th>Code</th><th>Subject</th><th>Units</th>{columns.map(column=><th key={column.sequence}>{column.period}</th>)}</tr></thead><tbody>
      {term.subjects.map(subject=><tr key={subject.code}><td>{subject.code}</td><td>{subject.name}</td><td>{subject.units}</td>{columns.map(column=><td key={column.sequence} className="grade-cell">{gradeText(gradeFor(subject,column.sequence))}</td>)}</tr>)}
    </tbody></table></div>:<p>No class records for this term.</p>}
  </div></section>;
}

function History({studentId}){
  const history=useResource(`/api/academic/history/students/${studentId}`);
  const [exporting,setExporting]=useState(false),[exportError,setExportError]=useState('');
  const data=history.data;
  async function exportPdf(){
    setExporting(true);setExportError('');
    try{const {downloadHistoryPdf}=await import('./historyPdf.js');downloadHistoryPdf(data);}
    catch(error){setExportError(error.message||'The PDF could not be created.');}
    finally{setExporting(false);}
  }
  return <>
    <Failure error={history.error} reload={history.reload}/>
    {history.loading&&!data&&<p role="status">Loading academic history…</p>}
    {data&&<>
      <div className="selected-app"><div><p className="eyebrow">{data.student.studentNumber}</p><h2>{data.student.name}</h2></div>
        <div className="component-row"><button className="button" disabled={exporting} onClick={exportPdf}>{exporting?'Preparing PDF…':'Export PDF summary'}</button><button className="button button-outline" onClick={history.reload}>Reload history</button></div></div>
      <Failure error={exportError}/>
      <div className="enrollment-stats">
        <div><span>Program</span><strong>{data.student.program}</strong></div>
        <div><span>Campus</span><strong>{data.student.campus}</strong></div>
        <div><span>LRN</span><strong>{data.student.lrn||'Not recorded'}</strong></div>
        <div><span>Record</span><strong>{data.student.active?'Active':'Archived'}</strong></div>
      </div>
      {data.terms.length?data.terms.map(term=><TermGrades key={term.enrollmentId} term={term}/>):<div className="notice">This student has no enrollment records yet.</div>}
      {data.terms.length>0&&<p className="field-help">* Submitted by the teacher, not yet finalized. Draft grades are not shown.</p>}

      <section className="panel"><h2>Section history</h2><div className="panel-body">
        {data.terms.length?<div className="setup-table-wrap"><table className="setup-table"><thead><tr><th>Term</th><th>Section</th><th>Grade level</th><th>Adviser</th><th>Room</th></tr></thead><tbody>
          {data.terms.map(term=><tr key={term.enrollmentId}><td>{term.academicYear} / {term.term}</td><td>{term.section||'Unassigned'}</td><td>{term.gradeLevel||'Not recorded'}</td><td>{term.adviser||'Not assigned'}</td><td>{term.room||'Not recorded'}</td></tr>)}
        </tbody></table></div>:<p>No section records.</p>}
        {data.sectionChanges.length>0&&<><h3>Section changes</h3><ol className="verification-history">{data.sectionChanges.map((change,index)=><li key={index}><strong>{change.fromSection||'Unassigned'} → {change.toSection||'Unassigned'}</strong><small>{change.actor||'System'} / {new Date(change.occurredAt).toLocaleString()}</small></li>)}</ol></>}
      </div></section>

      <section className="panel"><h2>Credential completion</h2><div className="panel-body">
        {data.credentials?<>
          <p>Application {data.credentials.applicationNumber}: <Status value={data.credentials.overallStatus}/> / {data.credentials.submitted} of {data.credentials.mandatoryTotal} mandatory credentials submitted</p>
          <div className="setup-table-wrap"><table className="setup-table"><thead><tr><th>Credential</th><th>Requirement</th><th>Status</th></tr></thead><tbody>
            {data.credentials.items.map(item=><tr key={item.code}><td>{item.name}</td><td>{item.mandatory?'Mandatory':'Optional'}</td><td><Status value={item.status}/></td></tr>)}
          </tbody></table></div>
        </>:<p>No application record is linked to this student.</p>}
      </div></section>
    </>}
  </>;
}

export default function AcademicHistory(){
  const [params,setParams]=useSearchParams();
  const selected=params.get('studentId')??'';
  return <><header className="page-heading"><p className="eyebrow">Academic records</p><h1>Academic History</h1><p>Review a student's subjects, grades, sections and credentials in one place.</p></header>
    <StudentPicker selected={selected} onSelect={id=>setParams({studentId:id})}/>
    {selected?<History key={selected} studentId={selected}/>:<p className="notice">Open a student above to see their consolidated history.</p>}
  </>;
}
