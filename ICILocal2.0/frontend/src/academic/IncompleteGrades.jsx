import { useEffect, useMemo, useState } from 'react';
import { useAuth } from '../auth/AuthContext.jsx';
import { addFollowUp, finalizeCompletion, getIncompleteCase, listIncompleteCases, proposeCompletion, statusLabel } from './incompleteGradesApi.js';
import './incomplete-grades.css';

const filterFields = [
  ['academicYear', 'Academic year'], ['gradingPeriod', 'Grading period'], ['gradeLevel', 'Grade level'],
  ['section', 'Section'], ['subject', 'Subject'], ['teacher', 'Teacher'], ['status', 'Case status'],
];
const teacherFilterFields = filterFields.filter(([key]) => key !== 'teacher');
const blankFilters = Object.fromEntries(filterFields.map(([key]) => [key, '']));
const unresolved = item => item.status !== 'completed';
const formatDate = value => new Date(value).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
const Status = ({ value }) => <span className={`status ${value === 'completed' ? 'status-success' : value === 'grade_correction' ? 'status-danger' : 'status-warning'}`}>{statusLabel[value] || value}</span>;

export default function IncompleteGrades() {
  const { roles, user } = useAuth();
  const isTeacher = !roles.includes('registrar') && roles.includes('teacher');
  const role = isTeacher ? 'teacher' : 'registrar';
  const actor = user?.fullName || (isTeacher ? 'Subject teacher' : 'Registrar staff');
  const [data, setData] = useState(null);
  const [selectedId, setSelectedId] = useState('');
  const [selected, setSelected] = useState(null);
  const [search, setSearch] = useState('');
  const [filters, setFilters] = useState(blankFilters);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState('');
  const [detailError, setDetailError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [followUp, setFollowUp] = useState({ note: '', method: 'Email' });
  const [completion, setCompletion] = useState({ grade: '', note: '' });

  async function reload(keepSelection = true) {
    setLoading(true);
    setError('');
    try {
      const result = await listIncompleteCases({ role });
      setData(result);
      if (!keepSelection || !result.items.some(item => item.id === selectedId)) setSelectedId('');
    } catch (requestError) {
      setError(requestError.message || 'Incomplete-grade cases could not be loaded.');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { reload(false); }, [role]);

  useEffect(() => {
    if (!selectedId) { setSelected(null); return; }
    let active = true;
    setDetailLoading(true);
    setSelected(null);
    setDetailError('');
    getIncompleteCase(selectedId, role).then(item => { if (active) setSelected(item); })
      .catch(requestError => { if (active) setDetailError(requestError.message); })
      .finally(() => { if (active) setDetailLoading(false); });
    return () => { active = false; };
  }, [selectedId, role, data]);

  const items = data?.items || [];
  const visibleFilterFields = isTeacher ? teacherFilterFields : filterFields;
  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase();
    return items.filter(item => {
      const matchesSearch = !term || [item.id, item.studentName, item.studentNumber, item.subject, item.subjectCode, item.section, item.teacher, item.academicYear].some(value => String(value).toLowerCase().includes(term));
      const matchesStatus = filters.status ? item.status === filters.status : unresolved(item);
      const matchesFilters = visibleFilterFields.every(([key]) => !filters[key] || key === 'status' || String(item[key]) === filters[key]);
      return matchesStatus && matchesSearch && matchesFilters;
    });
  }, [items, search, filters, visibleFilterFields]);

  function setFilter(key, value) { setFilters(current => ({ ...current, [key]: value })); }

  async function saveFollowUp(event) {
    event.preventDefault();
    if (!selected || !followUp.note.trim()) return;
    setBusy(true); setNotice(''); setDetailError('');
    try {
      const updated = await addFollowUp(selected.id, { ...followUp, actor });
      setSelected(updated); setFollowUp({ note: '', method: 'Email' });
      setNotice('Follow-up saved in mock data. No message was sent to the teacher.');
      await reload();
    } catch (requestError) { setDetailError(requestError.message); }
    finally { setBusy(false); }
  }

  async function submitCompletion(event) {
    event.preventDefault();
    const grade = Number(completion.grade);
    if (!Number.isFinite(grade) || grade < 0 || grade > 100) { setDetailError('Enter a proposed grade between 0 and 100.'); return; }
    setBusy(true); setNotice(''); setDetailError('');
    try {
      const updated = await proposeCompletion(selected.id, { ...completion, actor });
      setCompletion({ grade: '', note: '' }); setSelected(updated);
      setNotice(updated.periodLocked
        ? 'Proposal recorded in mock data. The locked period requires ICI-27 grade correction approval; this screen did not apply the mark.'
        : 'Proposed grade recorded in mock data for registrar review.');
      await reload();
    } catch (requestError) { setDetailError(requestError.message); }
    finally { setBusy(false); }
  }

  async function finalize(event) {
    event.preventDefault();
    const grade = completion.grade === '' ? undefined : Number(completion.grade);
    if (grade !== undefined && (!Number.isFinite(grade) || grade < 0 || grade > 100)) { setDetailError('Enter a final grade between 0 and 100.'); return; }
    setBusy(true); setNotice(''); setDetailError('');
    try {
      const updated = await finalizeCompletion(selected.id, { grade, note: completion.note, actor });
      setSelected(updated); setCompletion({ grade: '', note: '' });
      setNotice('Final mark and closure recorded in mock data only.');
      await reload();
    } catch (requestError) { setDetailError(requestError.message); }
    finally { setBusy(false); }
  }

  const picked = items.find(item => item.id === selectedId);
  return <div className="incomplete-workspace">
    <header className="page-heading">
      <p className="eyebrow">Academic records / {isTeacher ? 'Subject teacher' : 'Registrar staff'}</p>
      <h1>{isTeacher ? 'My Incomplete Grade Cases' : 'Incomplete Grade Monitoring'}</h1>
      <p>Track unresolved INC marks across active and historical academic periods.</p>
    </header>

    <div className="incomplete-mock-banner" role="status"><strong>MOCK DATA</strong><span>Changes are temporary in this browser session. No backend records are read or updated, and no communication is sent.</span></div>

    {notice && <p className="notice" role="status">{notice}</p>}
    {error && <div className="notice notice-error" role="alert">{error} <button className="button button-outline" onClick={() => reload()}>Retry</button></div>}

    <section className="panel" aria-labelledby="incomplete-list-title">
      <div className="incomplete-panel-heading"><div><h2 id="incomplete-list-title">Incomplete Grade Cases</h2><p>Review students with incomplete grades, monitor completion progress, and record follow-up actions.</p></div><button className="button button-outline" onClick={() => reload()}>Reload mock cases</button></div>
      <div className="incomplete-filters">
        <label>Search<input type="search" value={search} onChange={event => setSearch(event.target.value)} placeholder="Student, case, subject or teacher" /></label>
        {visibleFilterFields.map(([key, label]) => <label key={key}>{label}<select value={filters[key]} onChange={event => setFilter(key, event.target.value)}><option value="">All {label.toLowerCase()}s</option>{(data?.filters[key] || []).map(value => <option key={value} value={value}>{key === 'status' ? statusLabel[value] : value}</option>)}</select></label>)}
        <button className="button button-outline" onClick={() => { setSearch(''); setFilters(blankFilters); }}>Reset filters</button>
      </div>
      <p className="incomplete-count" role="status">Showing {loading ? '…' : filtered.length} {filters.status === 'completed' ? 'completed cases' : filters.status ? 'matching cases' : 'incomplete cases'}</p>
      {loading && !data && <p className="panel-body" role="status">Loading incomplete-grade cases…</p>}
      {!loading && filtered.length > 0 && <div className="incomplete-table-wrap"><table className="incomplete-table"><thead><tr><th>Case / student</th><th>Subject / section</th><th>Period</th><th>Teacher</th><th>Status</th><th>Action</th></tr></thead><tbody>
        {filtered.map(item => <tr key={item.id} className={item.id === selectedId ? 'selected-row' : ''}>
          <td><strong>{item.id}</strong><small>{item.studentName} / {item.studentNumber}</small></td>
          <td>{item.subject}<small>{item.section} / {item.gradeLevel}</small></td>
          <td>{item.academicYear}<small>{item.gradingPeriod}</small></td><td>{item.teacher}</td><td><Status value={item.status}/></td>
          <td><button className="button button-outline" onClick={() => { setSelectedId(item.id); setNotice(''); }} aria-label={`Review ${item.id}`}>Review</button></td>
        </tr>)}
      </tbody></table></div>}
      {!loading && !error && filtered.length === 0 && <div className="empty-state incomplete-empty"><h3>No cases match these filters</h3><p>Try another search, change a filter, or reset the filters.</p></div>}
    </section>

    {!picked && !loading && filtered.length > 0 && <p className="notice incomplete-select-prompt" role="status">Select a case with Review to see its details and actions.</p>}

    {picked && <section className="incomplete-detail-grid" aria-label="Selected incomplete grade case">
      <section className="panel"><div className="incomplete-panel-heading"><div><p className="eyebrow">Case details</p><h2>{picked.id}</h2><p>{picked.studentName} / {picked.subject}</p></div><Status value={selected?.status || picked.status}/></div>
        {detailLoading && <p className="panel-body" role="status">Loading case details…</p>}
        <div className="panel-body"><dl className="incomplete-details">
          <div><dt>Student</dt><dd>{picked.studentName}<small>{picked.studentNumber}</small></dd></div><div><dt>Grade / section</dt><dd>{picked.gradeLevel} / {picked.section}</dd></div>
          <div><dt>Subject</dt><dd>{picked.subjectCode} / {picked.subject}</dd></div><div><dt>Assigned teacher</dt><dd>{picked.teacher}</dd></div>
          <div><dt>Academic period</dt><dd>{picked.academicYear} / {picked.gradingPeriod}</dd></div><div><dt>Current mark</dt><dd>{selected?.currentMark || picked.currentMark}</dd></div>
          <div><dt>Grade period</dt><dd>{picked.periodLocked ? 'Locked' : 'Editable (mock rule)'}</dd></div><div><dt>Proposed grade</dt><dd>{selected?.proposedGrade ?? picked.proposedGrade ?? 'None recorded'}</dd></div>
        </dl>
        <h3>Follow-up history</h3>
        {selected?.followUps?.length ? <ol className="incomplete-history">{selected.followUps.map(entry => <li key={entry.id}><strong>{entry.note}</strong><small>{entry.by} / {entry.method} / {formatDate(entry.at)}</small></li>)}</ol> : <p>No follow-up history recorded.</p>}
        </div>
      </section>

      <section className="panel"><div className="incomplete-panel-heading"><div><h2>Case actions</h2><p>{isTeacher ? 'Record a follow-up or submit a completion proposal for registrar review.' : 'Record follow-ups, review proposals, and finalize eligible cases.'}</p></div></div><div className="panel-body">
        {detailError && <p className="notice notice-error" role="alert">{detailError}</p>}
        {detailLoading && <p role="status">Loading case details…</p>}
        {selected?.status === 'completed' && <p className="notice">This case is completed. The mock final mark is {selected.currentMark}.</p>}
        {selected?.periodLocked && selected?.status !== 'completed' && <p className="notice notice-error">This period is locked. Finalization is unavailable here; use ICI-27 grade correction review. The correction workflow is not implemented in this mock.</p>}

        <form className="incomplete-action-form" onSubmit={saveFollowUp}><h3>Log a follow-up</h3><label>Communication type<select value={followUp.method} onChange={event => setFollowUp(value => ({ ...value, method: event.target.value }))} disabled={busy}><option>Email</option><option>Phone</option><option>In person</option><option>System note</option></select></label><label>Follow-up note<textarea required maxLength={2000} value={followUp.note} onChange={event => setFollowUp(value => ({ ...value, note: event.target.value }))} placeholder="Record the contact, update, or next completion step." disabled={busy}/></label><button className="button button-outline" disabled={busy || !followUp.note.trim()}>{busy ? 'Saving…' : 'Save follow-up'}</button></form>

        {isTeacher && selected?.status !== 'completed' && <form className="incomplete-action-form" onSubmit={submitCompletion}><h3>Propose completed grade</h3><label>Proposed grade<input required type="number" min="0" max="100" step="0.01" value={completion.grade} onChange={event => setCompletion(value => ({ ...value, grade: event.target.value }))} disabled={busy}/></label><label>Completion note<textarea maxLength="2000" value={completion.note} onChange={event => setCompletion(value => ({ ...value, note: event.target.value }))} placeholder="Summarize the completed work and verification." disabled={busy}/></label><button className="button" disabled={busy}>{busy ? 'Submitting…' : 'Submit proposal'}</button><p className="field-help">The proposal is mock data and does not change the grade record.</p></form>}

        {!isTeacher && selected?.status !== 'completed' && !selected?.periodLocked && <form className="incomplete-action-form" onSubmit={finalize}><h3>Finalize completion</h3><label>Final grade<input type="number" min="0" max="100" step="0.01" value={completion.grade} onChange={event => setCompletion(value => ({ ...value, grade: event.target.value }))} placeholder={selected?.proposedGrade == null ? 'Enter final grade' : String(selected.proposedGrade)} disabled={busy}/></label><label>Resolution note<textarea maxLength="2000" value={completion.note} onChange={event => setCompletion(value => ({ ...value, note: event.target.value }))} placeholder="Record the verification and resolution." disabled={busy}/></label><button className="button" disabled={busy}>{busy ? 'Finalizing…' : 'Record final mark and close'}</button><p className="field-help">Available only for editable periods. This action updates mock data only.</p></form>}
      </div></section>
    </section>}
  </div>;
}
