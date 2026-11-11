import { useEffect, useMemo, useRef, useState } from 'react';
import { Confirm, Failure } from '../EnrollmentWorkspace.jsx';
import { loadClassGradebook, loadTeacherClasses, saveGradeDraft, submitGrades } from './gradeEntryApi.js';
import { approvedNonNumericMarks } from './gradeEntryFixture.js';
import './grade-entry.css';

const numericPattern = /^\d{1,3}(\.\d{1,2})?$/;

function validateGrade(value) {
  const normalized = value.trim().toUpperCase();
  if (!normalized) return { kind: 'empty', error: '' };
  if (approvedNonNumericMarks.includes(normalized)) return { kind: 'mark', error: '' };
  if (!numericPattern.test(normalized)) return { kind: 'invalid', error: 'Enter 0.00–100.00 or an approved mark.' };
  const number = Number(normalized);
  return number >= 0 && number <= 100
    ? { kind: 'numeric', error: '' }
    : { kind: 'invalid', error: 'Numeric grades must be between 0.00 and 100.00.' };
}

function normalizedGrades(grades) {
  return Object.fromEntries(Object.entries(grades).map(([studentId, grade]) => {
    const value = grade.value.trim().toUpperCase();
    return [studentId, { value, kind: validateGrade(value).kind }];
  }));
}

function requestError(error) {
  if (error?.status === 403) return 'You are not authorized to access this assigned class.';
  if (error?.status === 409) return 'These grades are no longer editable because they were submitted.';
  return error?.message || 'The grade-entry request failed.';
}

function GradeInput({ student, grade, readOnly, onChange, onKeyDown, inputRef }) {
  const validation = validateGrade(grade.value);
  return <div className="grade-cell">
    <input
      ref={inputRef}
      aria-label={`Grade for ${student.name}`}
      aria-invalid={validation.error ? 'true' : 'false'}
      value={grade.value}
      disabled={readOnly}
      inputMode="decimal"
      onChange={event => onChange(event.target.value)}
      onKeyDown={onKeyDown}
    />
    <small>{validation.error || (validation.kind === 'mark' ? 'Non-numeric mark' : '\u00a0')}</small>
  </div>;
}

export default function GradeEntry() {
  const [classes, setClasses] = useState([]);
  const [selectedClassId, setSelectedClassId] = useState('');
  const [periodId, setPeriodId] = useState('');
  const [book, setBook] = useState(null);
  const [grades, setGrades] = useState({});
  const [loading, setLoading] = useState(true);
  const [loadingBook, setLoadingBook] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [saveState, setSaveState] = useState('Not saved');
  const [confirming, setConfirming] = useState(false);
  const inputRefs = useRef({});

  useEffect(() => {
    let active = true;
    loadTeacherClasses().then(result => {
      if (!active) return;
      setClasses(result.items);
      setSelectedClassId(String(result.items[0]?.id || ''));
    }).catch(requestFailure => active && setError(requestError(requestFailure))).finally(() => active && setLoading(false));
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (!selectedClassId) return;
    const selected = classes.find(item => String(item.id) === selectedClassId);
    setPeriodId(current => current || selected?.periods?.[0]?.id || 'q1');
  }, [classes, selectedClassId]);

  useEffect(() => {
    if (!selectedClassId || !periodId) return;
    let active = true;
    setLoadingBook(true);
    setError('');
    loadClassGradebook(selectedClassId, periodId).then(result => {
      if (!active) return;
      setBook(result);
      setGrades(result.grades);
      setSaveState('Not saved');
    }).catch(requestFailure => active && setError(requestError(requestFailure))).finally(() => active && setLoadingBook(false));
    return () => { active = false; };
  }, [selectedClassId, periodId]);

  const validation = useMemo(() => Object.fromEntries(Object.entries(grades).map(([id, grade]) => [id, validateGrade(grade.value)])), [grades]);
  const hasErrors = Object.values(validation).some(item => item.error);
  const readOnly = book?.submission === 'submitted' || book?.period.status !== 'open';
  const selectedClass = classes.find(item => String(item.id) === selectedClassId);

  function updateGrade(studentId, value) {
    const upper = value.toUpperCase();
    setGrades(current => ({ ...current, [studentId]: { value: upper, kind: validateGrade(upper).kind } }));
    setSaveState('Unsaved changes');
    setNotice('');
  }

  function moveFocus(studentIndex, direction) {
    const target = book?.students[studentIndex + direction];
    if (target) inputRefs.current[target.id]?.focus();
  }

  function handleKeyDown(event, studentIndex) {
    if (event.key === 'ArrowDown' || event.key === 'Enter') { event.preventDefault(); moveFocus(studentIndex, 1); }
    if (event.key === 'ArrowUp') { event.preventDefault(); moveFocus(studentIndex, -1); }
    if (event.key === 'Tab' && !event.shiftKey && studentIndex < book.students.length - 1) { event.preventDefault(); moveFocus(studentIndex, 1); }
    if (event.key === 'Tab' && event.shiftKey && studentIndex > 0) { event.preventDefault(); moveFocus(studentIndex, -1); }
  }

  async function save() {
    if (hasErrors || readOnly) return;
    setSaveState('Saving…');
    setError('');
    try {
      const result = await saveGradeDraft(selectedClassId, periodId, normalizedGrades(grades));
      setBook(result); setGrades(result.grades); setSaveState('Draft saved (mock only)'); setNotice('Draft saved in the temporary frontend fixture. No database record was changed.');
    } catch (requestFailure) { setSaveState('Save failed'); setError(requestError(requestFailure)); }
  }

  async function submit() {
    if (hasErrors || readOnly) return;
    setSaveState('Submitting…');
    setError('');
    try {
      const result = await submitGrades(selectedClassId, periodId, normalizedGrades(grades));
      setBook(result); setGrades(result.grades); setSaveState('Submitted (mock only)'); setNotice('Grades marked submitted in the temporary frontend fixture. No school record was submitted.');
      setConfirming(false);
    } catch (requestFailure) { setSaveState('Submission failed'); setError(requestError(requestFailure)); }
  }

  if (loading) return <p role="status">Loading assigned classes…</p>;
  return <div className="academic-workspace">
    <header className="page-heading">
      <p className="eyebrow">Academic Records · Subject Teacher</p>
      <h1>Grade Entry</h1>
      <p>Encode grading-period marks for classes assigned to your account.</p>
    </header>
    <div className="notice grade-entry-note"><strong>Mock-only workflow:</strong> grades are held in temporary frontend memory until the GRADE API is available.</div>
    {notice && <div className="notice" role="status">{notice}</div>}
    {error && <Failure error={error} />}
    {!classes.length && !error && <div className="empty-state"><h2>No assigned classes</h2><p>Your Registrar-assigned classes will appear here when available.</p></div>}
    {classes.length > 0 && <section className="panel">
      <div className="panel-body grade-entry-context">
        <label className="assignment-field">Assigned class
          <select value={selectedClassId} onChange={event => { setSelectedClassId(event.target.value); setPeriodId(''); }} disabled={loadingBook}>
            {classes.map(item => <option key={item.id} value={item.id}>{item.subjectCode} — {item.section}</option>)}
          </select>
        </label>
        <label className="assignment-field">Grading period
          <select value={periodId} onChange={event => setPeriodId(event.target.value)} disabled={loadingBook}>
            {(selectedClass?.periods || []).map(period => <option key={period.id} value={period.id}>{period.label}</option>)}
          </select>
        </label>
      </div>
    </section>}
    {loadingBook && <p role="status">Loading grade book…</p>}
    {book && !loadingBook && <><section className="panel">
      <div className="panel-body"><dl className="grade-entry-meta">
        <div><dt>Subject</dt><dd>{book.class.subjectCode} · {book.class.subject}</dd></div>
        <div><dt>Section</dt><dd>{book.class.gradeLevel} · {book.class.section}</dd></div>
        <div><dt>Access</dt><dd><span className={`status ${readOnly ? 'status-warning' : 'status-success'}`}>{readOnly ? (book.submission === 'submitted' ? 'Submitted · read-only' : 'Period locked') : 'Period open'}</span></dd></div>
        <div><dt>Deadline</dt><dd>{book.period.deadline}</dd></div>
      </dl></div>
    </section><section className="panel">
      <h2>Student score sheet</h2>
      <div className="panel-body">
        <p className="field-help">Use Arrow keys, Tab, or Enter to move between student cells. Numeric grades accept 0.00–100.00; approved marks are validated separately.</p>
        <div className="grade-entry-grid"><table className="grade-entry-table"><thead><tr><th>Student</th><th>Student number</th><th>{book.period.label}</th><th>Status</th></tr></thead><tbody>
          {book.students.map((student, index) => {
            const item = grades[student.id] || { value: '', kind: 'empty' };
            const result = validation[student.id];
            return <tr key={student.id} className={result.error ? 'has-error' : ''}><td><strong>{student.name}</strong></td><td>{student.studentNumber}</td><td><GradeInput student={student} grade={item} readOnly={readOnly} onChange={value => updateGrade(student.id, value)} onKeyDown={event => handleKeyDown(event, index)} inputRef={element => { inputRefs.current[student.id] = element; }} /></td><td><span className={`status ${result.error ? 'status-danger' : result.kind === 'empty' ? 'status-warning' : 'status-success'}`}>{result.error ? 'Invalid' : result.kind === 'empty' ? 'Missing score' : result.kind === 'mark' ? 'Incomplete mark' : 'Valid'}</span></td></tr>;
          })}
        </tbody></table></div>
        <div className="grade-entry-actions"><span role="status">Save status: {saveState}</span><div className="component-row"><button className="button button-outline" onClick={save} disabled={readOnly || hasErrors || saveState === 'Saving…'}>Save draft</button><button className="button" onClick={() => setConfirming(true)} disabled={readOnly || hasErrors}>Submit grades</button></div></div>
        {hasErrors && <p className="field-error" role="alert">Correct invalid grades before saving or submitting.</p>}
      </div>
    </section></>}
    {confirming && <Confirm title="Submit grades?" busy={saveState === 'Submitting…'} onCancel={() => setConfirming(false)} onConfirm={submit}><p>This will make the selected grading period read-only. This frontend demo will only update temporary mock state.</p></Confirm>}
  </div>;
}
