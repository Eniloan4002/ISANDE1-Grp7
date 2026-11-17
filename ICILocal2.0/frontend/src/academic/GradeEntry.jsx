import { useEffect, useMemo, useRef, useState } from 'react';
import { Confirm, Failure } from '../EnrollmentWorkspace.jsx';
import { approvedNonNumericMarks, loadClassGradebook, loadGradeCatalog, loadGradingPeriods, loadTeacherClasses, saveGradeDraft, submitGrades } from './gradeEntryApi.js';
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
  if (error?.status === 409) return error.message || 'These grades changed or are no longer editable. Reload and try again.';
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

function optionValue(value) {
  return value == null ? '' : String(value);
}

export default function GradeEntry() {
  const [catalog, setCatalog] = useState({ years: [], terms: [] });
  const [filters, setFilters] = useState({ academicYearId: '', termId: '' });
  const [classes, setClasses] = useState([]);
  const [periods, setPeriods] = useState([]);
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
    loadGradeCatalog().then(result => {
      if (!active) return;
      const years = result?.years || [];
      const terms = result?.terms || [];
      setCatalog({ years, terms });
      const year = years[0];
      const term = terms.find(item => optionValue(item.academic_year_id ?? item.academicYearId) === optionValue(year?.id));
      setFilters({ academicYearId: optionValue(year?.id), termId: optionValue(term?.id) });
      if (!year || !term) setLoading(false);
    }).catch(requestFailure => {
      if (!active) return;
      setError(requestError(requestFailure));
      setLoading(false);
    });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (!filters.academicYearId || !filters.termId) return;
    let active = true;
    setLoading(true);
    setError('');
    setBook(null);
    loadTeacherClasses(filters).then(result => {
      if (!active) return;
      setClasses(result.items);
      setSelectedClassId(String(result.items[0]?.id || ''));
      setPeriodId('');
      setPeriods([]);
    }).catch(requestFailure => active && setError(requestError(requestFailure))).finally(() => active && setLoading(false));
    return () => { active = false; };
  }, [filters.academicYearId, filters.termId]);

  useEffect(() => {
    if (!selectedClassId) return;
    let active = true;
    loadGradingPeriods(selectedClassId, filters).then(result => {
      if (!active) return;
      setPeriods(result);
      setPeriodId(String(result[0]?.id || ''));
    }).catch(requestFailure => active && setError(requestError(requestFailure)));
    return () => { active = false; };
  }, [selectedClassId]);

  useEffect(() => {
    if (!selectedClassId || !periodId) return;
    let active = true;
    setLoadingBook(true);
    setError('');
    loadClassGradebook(selectedClassId, periodId, filters).then(result => {
      if (!active) return;
      setBook(result);
      setGrades(result.grades);
      setSaveState('Not saved');
    }).catch(requestFailure => active && setError(requestError(requestFailure))).finally(() => active && setLoadingBook(false));
    return () => { active = false; };
  }, [selectedClassId, periodId]);

  const terms = useMemo(() => catalog.terms.filter(term => optionValue(term.academic_year_id ?? term.academicYearId) === filters.academicYearId), [catalog.terms, filters.academicYearId]);

  function updateYear(value) {
    const term = catalog.terms.find(item => optionValue(item.academic_year_id ?? item.academicYearId) === value);
    setFilters({ academicYearId: value, termId: optionValue(term?.id) });
  }

  const validation = useMemo(() => Object.fromEntries(Object.entries(grades).map(([id, grade]) => [id, validateGrade(grade.value)])), [grades]);
  const hasErrors = Object.values(validation).some(item => item.error);
  const readOnly = book?.submission === 'submitted' || book?.period.status !== 'open';

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
      const result = await saveGradeDraft(selectedClassId, periodId, normalizedGrades(grades), book.students, filters);
      setBook(result); setGrades(result.grades); setSaveState('Draft saved'); setNotice('Draft grades saved.');
    } catch (requestFailure) { setSaveState('Save failed'); setError(requestError(requestFailure)); }
  }

  async function submit() {
    if (hasErrors || readOnly) return;
    setSaveState('Submitting…');
    setError('');
    try {
      await saveGradeDraft(selectedClassId, periodId, normalizedGrades(grades), book.students, filters);
      const result = await submitGrades(selectedClassId, periodId, filters);
      setBook(result); setGrades(result.grades); setSaveState('Submitted'); setNotice('Grades submitted. This grading period is now read-only.');
      setConfirming(false);
    } catch (requestFailure) { setSaveState('Submission failed'); setConfirming(false); setError(requestError(requestFailure)); }
  }

  if (loading) return <p role="status">Loading assigned classes…</p>;
  return <div className="academic-workspace">
    <header className="page-heading">
      <p className="eyebrow">Academic Records · Subject Teacher</p>
      <h1>Grade Entry</h1>
      <p>Encode grading-period marks for classes assigned to your account.</p>
    </header>
    {notice && <div className="notice" role="status">{notice}</div>}
    {error && <Failure error={error} />}
    {!classes.length && !error && <div className="empty-state"><h2>No assigned classes</h2><p>Your Registrar-assigned classes will appear here when available.</p></div>}
    {catalog.years.length > 0 && <section className="panel">
      <div className="panel-body grade-entry-context">
        <label className="assignment-field">Academic year
          <select value={filters.academicYearId} onChange={event => updateYear(event.target.value)} disabled={loadingBook}>
            {catalog.years.map(year => <option key={year.id} value={year.id}>{year.label}</option>)}
          </select>
        </label>
        <label className="assignment-field">Term
          <select value={filters.termId} onChange={event => setFilters(current => ({ ...current, termId: event.target.value }))} disabled={loadingBook}>
            {terms.map(term => <option key={term.id} value={term.id}>{term.name} ({term.code})</option>)}
          </select>
        </label>
        {classes.length > 0 && <label className="assignment-field">Assigned class
          <select value={selectedClassId} onChange={event => { setSelectedClassId(event.target.value); setPeriodId(''); setBook(null); }} disabled={loadingBook}>
            {classes.map(item => <option key={item.id} value={item.id}>{item.subjectCode} — {item.section}</option>)}
          </select>
        </label>}
        {classes.length > 0 && <label className="assignment-field">Grading period
          <select value={periodId} onChange={event => setPeriodId(event.target.value)} disabled={loadingBook}>
            {periods.map(period => <option key={period.id} value={period.id}>{period.label}</option>)}
          </select>
        </label>}
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
    {confirming && <Confirm title="Submit grades?" busy={saveState === 'Submitting…'} onCancel={() => setConfirming(false)} onConfirm={submit}><p>This will make the selected grading period read-only. Draft grades are saved first, and the class cannot be edited after submission.</p></Confirm>}
  </div>;
}
