import { useEffect, useMemo, useState } from 'react';
import { Confirm } from '../EnrollmentWorkspace.jsx';
import { useAuth } from '../auth/AuthContext.jsx';
import { finalizeAndLockScope, getGradingScope, listGradingScopes, updateGradingDeadline } from './gradeFinalizationApi.js';
import './grade-finalization.css';

const filterKeys = ['academicYear', 'gradingPeriod', 'gradeLevel', 'section'];
const filterLabels = { academicYear: 'Academic year', gradingPeriod: 'Grading period', gradeLevel: 'Grade level', section: 'Section' };
const uniqueValues = (items, key) => [...new Set(items.map(item => item[key]))].sort();
const submitted = offering => offering.submittedRecords >= offering.totalRecords;
const localInputValue = value => {
  if (!value) return '';
  const date = new Date(value);
  date.setMinutes(date.getMinutes() - date.getTimezoneOffset());
  return date.toISOString().slice(0, 16);
};
const toIso = value => new Date(value).toISOString();
const formatDate = value => value ? new Date(value).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }) : 'No deadline set';

export default function GradeFinalization() {
  const { user } = useAuth();
  const actor = user?.fullName || 'Registrar staff';
  const [scopes, setScopes] = useState([]);
  const [selectedId, setSelectedId] = useState('');
  const [scope, setScope] = useState(null);
  const [deadlineInput, setDeadlineInput] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [confirmingLock, setConfirmingLock] = useState(false);

  async function refresh(preferredId = selectedId) {
    setError('');
    try {
      const result = await listGradingScopes();
      setScopes(result.items);
      const nextId = result.items.some(item => item.id === preferredId) ? preferredId : result.items[0]?.id || '';
      setSelectedId(nextId);
      if (nextId) {
        const nextScope = await getGradingScope(nextId);
        setScope(nextScope);
        setDeadlineInput(localInputValue(nextScope.deadline));
      } else {
        setScope(null);
      }
    } catch (requestError) {
      setError(requestError.message || 'Grading scopes could not be loaded.');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { refresh(''); }, []);

  // Recheck the mock deadline periodically so a scope locks when its due time passes.
  useEffect(() => {
    if (!selectedId || scope?.lockedAt) return undefined;
    const timer = window.setInterval(async () => {
      try {
        const updated = await getGradingScope(selectedId);
        setScope(updated);
        setScopes(current => current.map(item => item.id === updated.id ? updated : item));
        if (updated.lockedAt) {
          setNotice('The configured deadline passed. This scope was automatically locked in mock data.');
          setDeadlineInput(localInputValue(updated.deadline));
        }
      } catch (requestError) {
        setError(requestError.message || 'Grading scope could not be refreshed.');
      }
    }, 10000);
    return () => window.clearInterval(timer);
  }, [selectedId, scope?.lockedAt]);

  const selectedFilters = scope ? Object.fromEntries(filterKeys.map(key => [key, scope[key]])) : {};
  const filterOptions = useMemo(() => Object.fromEntries(filterKeys.map((key, index) => {
    const candidates = scopes.filter(item => filterKeys.slice(0, index).every(previous => item[previous] === selectedFilters[previous]));
    return [key, uniqueValues(candidates, key)];
  })), [scopes, scope]);

  function chooseFilter(key, value) {
    const index = filterKeys.indexOf(key);
    const previousFilters = { ...selectedFilters, [key]: value };
    const match = scopes.find(item => filterKeys.slice(0, index + 1).every(filter => item[filter] === previousFilters[filter]));
    if (match) refresh(match.id);
  }

  async function saveDeadline(event) {
    event.preventDefault();
    if (!scope || !deadlineInput) return;
    setBusy(true); setError(''); setNotice('');
    try {
      const updated = await updateGradingDeadline(scope.id, toIso(deadlineInput), actor);
      setScope(updated); setScopes(current => current.map(item => item.id === updated.id ? updated : item));
      setDeadlineInput(localInputValue(updated.deadline));
      setNotice(updated.lockReason === 'deadline'
        ? 'The deadline is due. This scope was automatically locked in mock data.'
        : 'Submission deadline saved in mock data.');
    } catch (requestError) { setError(requestError.message || 'The deadline could not be saved.'); }
    finally { setBusy(false); }
  }

  async function confirmLock() {
    if (!scope) return;
    setBusy(true); setError(''); setNotice('');
    try {
      const updated = await finalizeAndLockScope(scope.id, actor);
      setScope(updated); setScopes(current => current.map(item => item.id === updated.id ? updated : item));
      setConfirmingLock(false);
      setNotice('Scope locked in mock data. Submitted grades and submitted INC marks are finalized as recorded; unsubmitted records remain unsubmitted.');
    } catch (requestError) { setError(requestError.message || 'The scope could not be locked.'); }
    finally { setBusy(false); }
  }

  const unsubmittedOfferings = scope?.offerings.filter(offering => !submitted(offering)) || [];
  const isLocked = Boolean(scope?.lockedAt);
  return <div className="grade-finalization-workspace">
    <header className="page-heading"><p className="eyebrow">Academic records / Registrar staff</p><h1>Grade Finalization</h1><p>Set submission deadlines, monitor section progress, and finalize grading scopes.</p></header>
    <div className="finalization-mock-banner" role="status"><strong>MOCK DATA</strong><span>Deadlines, locks, and history are temporary in this browser session. They are not saved to the backend or enforced by teacher endpoints.</span></div>
    {notice && <p className="notice" role="status">{notice}</p>}
    {error && <div className="notice notice-error" role="alert">{error}<button className="button button-outline" onClick={() => refresh()}>Retry</button></div>}
    {loading && <p role="status">Loading grading scopes…</p>}
    {!loading && !error && !scopes.length && <div className="empty-state"><h2>No grading scopes available</h2><p>Mock grading scopes will appear here when available.</p></div>}

    {scope && <>
      <section className="panel" aria-labelledby="scope-selection-title">
        <div className="finalization-panel-heading"><div><h2 id="scope-selection-title">Academic scope</h2><p>Select an academic year, grading period, grade level, and section to manage grade submissions and finalization.</p></div><span className={`status ${isLocked ? 'status-danger' : 'status-success'}`}>{isLocked ? 'Locked' : 'Open'}</span></div>
        <div className="finalization-scope-filters">
          {filterKeys.map(key => <label key={key}>{filterLabels[key]}<select aria-label={filterLabels[key]} value={scope[key]} onChange={event => chooseFilter(key, event.target.value)}>{filterOptions[key].map(value => <option key={value} value={value}>{value}</option>)}</select></label>)}
        </div>
        <dl className="finalization-scope-meta"><div><dt>Submission deadline</dt><dd>{formatDate(scope.deadline)}</dd></div><div><dt>Lock state</dt><dd>{isLocked ? `${scope.lockReason === 'deadline' ? 'Automatic deadline lock' : 'Manual lock'} · ${formatDate(scope.lockedAt)}` : 'Teachers can submit grades while the window is open.'}</dd></div>{isLocked && <div><dt>Finalized by</dt><dd>{scope.lockedBy}</dd></div>}</dl>
      </section>

      <section className="panel" aria-labelledby="deadline-title">
        <div className="finalization-panel-heading"><div><h2 id="deadline-title">Submission deadline</h2><p>Set the deadline for teachers to submit grades for all subjects under the selected section and grading period.</p></div></div>
        <form className="finalization-deadline-form" onSubmit={saveDeadline}><label htmlFor="submission-deadline">Deadline date and time<input id="submission-deadline" type="datetime-local" required value={deadlineInput} disabled={busy || isLocked} onChange={event => setDeadlineInput(event.target.value)}/></label><button className="button button-outline" disabled={busy || isLocked || !deadlineInput}>{busy ? 'Saving…' : 'Save deadline'}</button>{isLocked && <span className="field-help">A locked scope cannot be edited or unlocked here.</span>}</form>
      </section>

      <section className="panel" aria-labelledby="submission-progress-title">
        <div className="finalization-panel-heading"><div><h2 id="submission-progress-title">Subject submission progress</h2><p>{scope.academicYear} / {scope.gradingPeriod} / {scope.gradeLevel} / {scope.section}</p></div><span>{scope.offerings.filter(submitted).length} of {scope.offerings.length} subjects submitted</span></div>
        {unsubmittedOfferings.length > 0 && <p className="finalization-warning" role="status"><strong>{unsubmittedOfferings.length} subject submission{unsubmittedOfferings.length === 1 ? '' : 's'} incomplete.</strong> These are warnings only and do not prevent a manual lock.</p>}
        <div className="finalization-table-wrap"><table className="finalization-table"><thead><tr><th>Subject</th><th>Assigned Teacher</th><th>Submission Status</th><th>Grade Records Submitted</th><th>Unresolved INC</th></tr></thead><tbody>
          {scope.offerings.map(offering => <tr key={offering.id} className={!submitted(offering) ? 'incomplete-submission-row' : ''}><td><strong>{offering.subject}</strong></td><td>{offering.teacher}</td><td><span className={`status ${submitted(offering) ? 'status-success' : 'status-warning'}`}>{offering.submissionStatus}</span></td><td><progress value={offering.submittedRecords} max={offering.totalRecords} aria-label={`${offering.subject}: ${offering.submittedRecords} of ${offering.totalRecords} grade records submitted`}/><small>{offering.submittedRecords} / {offering.totalRecords}</small>{isLocked && <small>Finalized: {offering.finalizedGradeRecords} grades + {offering.finalizedIncRecords} INC · {offering.unsubmittedRecords} remain unsubmitted</small>}</td><td>{offering.unresolvedInc}</td></tr>)}
        </tbody></table></div>
        <p className="finalization-rule-note">Locking finalizes submitted grade records and protects all records in this scope from direct teacher edits. Submitted INC marks remain INC, and records not submitted before locking remain unsubmitted. Later changes to finalized grades must follow the grade correction workflow.</p>
      </section>

      <section className="panel" aria-labelledby="finalization-history-title">
        <div className="finalization-panel-heading"><div><h2 id="finalization-history-title">Finalization history</h2><p>Deadline changes and lock actions for this section and period.</p></div></div>
        {scope.history.length ? <ol className="finalization-history">{scope.history.map(item => <li key={item.id}><strong>{item.action}</strong><span>{item.actor} / {formatDate(item.occurredAt)}</span></li>)}</ol> : <p className="panel-body">No finalization activity recorded for this scope.</p>}
      </section>

      {!isLocked && <div className="finalization-lock-action"><div><strong>Manual Early Finalization</strong><p>Close grade submission for the selected section and grading period before the scheduled deadline. Teachers will no longer be able to modify grades directly. Unsubmitted grade records will remain unsubmitted.</p></div><button className="button" onClick={() => setConfirmingLock(true)}>Lock Section Now</button></div>}
    </>}

    {confirmingLock && scope && <Confirm title="Finalize and lock this scope?" busy={busy} onCancel={() => setConfirmingLock(false)} onConfirm={confirmLock}>
      <p>This will immediately lock <strong>{scope.academicYear} / {scope.gradingPeriod} / {scope.gradeLevel} / {scope.section}</strong> across all {scope.offerings.length} subject offerings.</p>
      <p>Subject teachers will no longer be able to modify grades in this scope. Submitted grades and INC marks will be finalized as recorded; the {unsubmittedOfferings.length} incomplete submission(s) will remain unsubmitted.</p>
    </Confirm>}
  </div>;
}
