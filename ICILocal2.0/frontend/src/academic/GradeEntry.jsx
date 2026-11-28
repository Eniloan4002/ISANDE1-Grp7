// ICI2-101: Grade Entry with one sticky context bar (class, period, term, status chips, progress, save state,
// actions) above a compact score grid, so a teacher sees at least eight students at 1440×900.
import { useEffect, useMemo, useRef, useState } from 'react';
import { Confirm, Failure } from '../EnrollmentWorkspace.jsx';
import { Button, ProgressMeter, StatusChip } from '../ui/components.jsx';
import { approvedNonNumericMarks, loadClassGradebook, loadGradingPeriods, loadTeacherClasses, saveGradeDraft, submitGrades } from './gradeEntryApi.js';
import { useAcademicContext } from './useAcademicContext.js';
import { shortStudentNumber } from './studentNumber.js';
import './grade-entry.css';

const numericPattern = /^\d{1,3}(\.\d{1,2})?$/;
const markLabels = { INC: 'Incomplete', NG: 'No grade', DRP: 'Dropped' };

export function validateGrade(value) {
  const normalized = value.trim().toUpperCase();
  if (!normalized) return { kind: 'empty', error: '' };
  if (approvedNonNumericMarks.includes(normalized)) return { kind: 'mark', error: '' };
  if (/^\d+(\.\d*)?$/.test(normalized) && Number(normalized) > 100) return { kind: 'invalid', error: 'Max is 100.00' };
  if (/^\d+\.\d{3,}$/.test(normalized)) return { kind: 'invalid', error: 'Use up to 2 decimal places' };
  if (!numericPattern.test(normalized)) return { kind: 'invalid', error: `Enter 0.00–100.00 or ${approvedNonNumericMarks.join(', ')}` };
  return { kind: 'numeric', error: '' };
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

const saveLabels = {
  saved: 'All changes saved',
  dirty: 'Unsaved changes',
  saving: 'Saving…',
  failed: 'Not saved. Try again.',
  submitting: 'Submitting…',
  submitted: 'Submitted',
};

function rowChip(result, value) {
  if (result.error) return <StatusChip tone="blocked">Fix value</StatusChip>;
  if (result.kind === 'empty') return <StatusChip tone="neutral">Not graded</StatusChip>;
  if (result.kind === 'mark') return <StatusChip tone="attention">{markLabels[value.trim().toUpperCase()] || 'Mark'}</StatusChip>;
  return <StatusChip tone="success">Graded</StatusChip>;
}

function periodChip(book, readOnly) {
  if (book.submission === 'submitted') return <StatusChip tone="info">Submitted · read-only</StatusChip>;
  if (readOnly) return <StatusChip tone="blocked">Period locked</StatusChip>;
  return <StatusChip tone="success">Period open</StatusChip>;
}

export default function GradeEntry() {
  // The Assigned Classes screen links here with ?classId=&academicYearId=&termId=. Read once on arrival.
  const requested = useRef(typeof window === 'undefined' ? new URLSearchParams() : new URLSearchParams(window.location.search));
  const academic = useAcademicContext({ academicYearId: requested.current.get('academicYearId'), termId: requested.current.get('termId') });
  const { filters } = academic;
  const [classes, setClasses] = useState([]);
  const [classesLoaded, setClassesLoaded] = useState(false);
  const [periods, setPeriods] = useState([]);
  const [selectedClassId, setSelectedClassId] = useState('');
  const [periodId, setPeriodId] = useState('');
  const [book, setBook] = useState(null);
  const [grades, setGrades] = useState({});
  const [loadingBook, setLoadingBook] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [saveState, setSaveState] = useState('saved');
  const [confirming, setConfirming] = useState(false);
  const inputRefs = useRef({});

  useEffect(() => {
    if (!academic.ready) return undefined;
    let active = true;
    setClassesLoaded(false);
    setError('');
    setBook(null);
    setSelectedClassId(''); // so the period load reruns even when the new term has the same first class
    setPeriodId('');
    loadTeacherClasses(filters).then(result => {
      if (!active) return;
      setClasses(result.items);
      const wanted = result.items.find(item => String(item.id) === requested.current.get('classId'));
      requested.current = new URLSearchParams(); // later year or term changes go back to the first class
      setSelectedClassId(String((wanted || result.items[0])?.id || ''));
      setPeriodId('');
      setPeriods([]);
    }).catch(requestFailure => active && setError(requestError(requestFailure))).finally(() => active && setClassesLoaded(true));
    return () => { active = false; };
  }, [academic.ready, filters.academicYearId, filters.termId]);

  useEffect(() => {
    if (!selectedClassId) return undefined;
    let active = true;
    loadGradingPeriods(selectedClassId, filters).then(result => {
      if (!active) return;
      setPeriods(result);
      setPeriodId(String(result[0]?.id || ''));
    }).catch(requestFailure => active && setError(requestError(requestFailure)));
    return () => { active = false; };
  }, [selectedClassId]);

  useEffect(() => {
    if (!selectedClassId || !periodId) return undefined;
    let active = true;
    setLoadingBook(true);
    setError('');
    loadClassGradebook(selectedClassId, periodId, filters).then(result => {
      if (!active) return;
      setBook(result);
      setGrades(result.grades);
      setSaveState(result.submission === 'submitted' ? 'submitted' : 'saved');
    }).catch(requestFailure => active && setError(requestError(requestFailure))).finally(() => active && setLoadingBook(false));
    return () => { active = false; };
  }, [selectedClassId, periodId]);

  const validation = useMemo(() => Object.fromEntries(Object.entries(grades).map(([id, grade]) => [id, validateGrade(grade.value)])), [grades]);
  const resultFor = id => validation[id] || { kind: 'empty', error: '' };
  const hasErrors = Object.values(validation).some(item => item.error);
  const readOnly = book?.submission === 'submitted' || book?.period.status !== 'open';
  const busy = saveState === 'saving' || saveState === 'submitting';

  const progress = useMemo(() => {
    const students = book?.students || [];
    const kinds = students.map(student => (validation[student.id] || { kind: 'empty' }).kind);
    const graded = kinds.filter(kind => kind === 'numeric').length;
    const attention = kinds.filter(kind => kind === 'invalid' || kind === 'mark').length;
    const text = `${graded} of ${students.length} graded${attention ? ` · ${attention} ${attention === 1 ? 'needs' : 'need'} attention` : ''}`;
    return { graded, total: students.length, text };
  }, [book, validation]);

  const termOptions = useMemo(() => academic.catalog.years.flatMap(year => academic.catalog.terms
    .filter(term => String(term.academic_year_id ?? term.academicYearId) === String(year.id))
    .map(term => ({ id: String(term.id), yearId: String(year.id), label: `${year.label} · ${term.name}` }))), [academic.catalog]);

  function changeTerm(termId) {
    const option = termOptions.find(item => item.id === termId);
    if (!option) return;
    if (option.yearId !== filters.academicYearId) academic.setYear(option.yearId);
    academic.setTerm(option.id);
  }

  function changeClass(classId) {
    setSelectedClassId(classId);
    setPeriodId('');
    setBook(null);
    setNotice('');
  }

  function updateGrade(studentId, value) {
    const upper = value.toUpperCase();
    setGrades(current => ({ ...current, [studentId]: { value: upper, kind: validateGrade(upper).kind } }));
    setSaveState('dirty');
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
    setSaveState('saving');
    setError('');
    try {
      const result = await saveGradeDraft(selectedClassId, periodId, normalizedGrades(grades), book.students, filters);
      setBook(result); setGrades(result.grades); setSaveState('saved'); setNotice('Draft grades saved.');
    } catch (requestFailure) { setSaveState('failed'); setError(requestError(requestFailure)); }
  }

  async function submit() {
    if (hasErrors || readOnly) return;
    setSaveState('submitting');
    setError('');
    try {
      await saveGradeDraft(selectedClassId, periodId, normalizedGrades(grades), book.students, filters);
      const result = await submitGrades(selectedClassId, periodId, filters);
      setBook(result); setGrades(result.grades); setSaveState('submitted'); setNotice('Grades submitted. This grading period is now read-only.');
      setConfirming(false);
    } catch (requestFailure) { setSaveState('failed'); setConfirming(false); setError(requestError(requestFailure)); }
  }

  const loadingClasses = !academic.loaded || (academic.ready && !classesLoaded);
  const hasClasses = classes.length > 0;

  return <div className="academic-workspace ge-screen">
    <header className="page-heading ge-heading">
      <p className="eyebrow">Academic Records · Subject Teacher</p>
      <h1>Grade Entry</h1>
    </header>
    <Failure error={academic.error || error} />
    {loadingClasses && !academic.error && <p role="status">Loading assigned classes…</p>}
    {academic.loaded && !academic.error && !academic.ready && <div className="empty-state"><h2>No academic term available</h2><p>Ask the registrar to set up an academic year and term.</p></div>}

    {academic.ready && <section className="ge-bar" aria-label="Grade entry context">
      <div className="ge-bar-row">
        <label className="ge-field ge-field-class">Class
          <select value={selectedClassId} onChange={event => changeClass(event.target.value)} disabled={!hasClasses || loadingBook || busy}>
            {!hasClasses && <option value="">No assigned classes</option>}
            {classes.map(item => <option key={item.id} value={item.id}>{item.subjectCode} {item.subject} · {item.gradeLevel} {item.section}</option>)}
          </select>
        </label>
        <label className="ge-field">Grading period
          <select value={periodId} onChange={event => setPeriodId(event.target.value)} disabled={!periods.length || loadingBook || busy}>
            {periods.map(period => <option key={period.id} value={period.id}>{period.label}</option>)}
          </select>
        </label>
        <label className="ge-field">Term
          <select value={filters.termId} onChange={event => changeTerm(event.target.value)} disabled={loadingBook || busy}>
            {termOptions.map(term => <option key={term.id} value={term.id}>{term.label}</option>)}
          </select>
        </label>
        {book && !loadingBook && <div className="ge-chips">
          {periodChip(book, readOnly)}
          <StatusChip tone="neutral">{book.period.deadline === 'Not set' ? 'No deadline' : `Due ${book.period.deadline}`}</StatusChip>
        </div>}
      </div>
      {book && !loadingBook && <div className="ge-bar-row ge-bar-status">
        <div className="ge-progress"><ProgressMeter label="Grading progress" value={progress.graded} max={progress.total} valueText={progress.text} /></div>
        <span className={`ge-save ge-save-${saveState}`} role="status">{saveLabels[saveState]}</span>
        <div className="ge-actions">
          <Button variant="secondary" onClick={save} disabled={readOnly || hasErrors || busy}>Save draft</Button>
          <Button onClick={() => setConfirming(true)} disabled={readOnly || hasErrors || busy}>Submit grades</Button>
        </div>
      </div>}
    </section>}

    {notice && <div className="notice ge-notice" role="status">{notice}</div>}
    {classesLoaded && !hasClasses && !error && <div className="empty-state"><h2>No assigned classes</h2><p>Your Registrar-assigned classes will appear here when available.</p></div>}
    {loadingBook && <p role="status">Loading grade book…</p>}

    {book && !loadingBook && <section className="panel ge-sheet" aria-label="Score sheet">
      <div className="ge-sheet-head">
        <h2>Score sheet</h2>
        <p>Arrow keys, Tab or Enter move between cells. Grades are 0.00–100.00, or {approvedNonNumericMarks.join(', ')}.</p>
      </div>
      {hasErrors && <p className="ge-sheet-alert" role="alert">Fix the highlighted grades before saving or submitting.</p>}
      <div className="ge-grid">
        <table className="ge-table">
          <thead><tr><th scope="col">Student</th><th scope="col">Student no.</th><th scope="col" className="ge-num">{book.period.label} grade</th><th scope="col">Status</th></tr></thead>
          <tbody>
            {book.students.map((student, index) => {
              const item = grades[student.id] || { value: '', kind: 'empty' };
              const result = resultFor(student.id);
              const errorId = `grade-error-${student.id}`;
              return <tr key={student.id} className={result.error ? 'has-error' : undefined}>
                <th scope="row">{student.name}</th>
                <td className="ge-number" title={student.studentNumber}>{shortStudentNumber(student.studentNumber)}</td>
                <td className="ge-num">
                  <div className="ge-cell">
                    {result.error && <span className="ge-error" id={errorId}>{result.error}</span>}
                    <input
                      ref={element => { inputRefs.current[student.id] = element; }}
                      aria-label={`Grade for ${student.name}`}
                      aria-invalid={result.error ? 'true' : 'false'}
                      aria-describedby={result.error ? errorId : undefined}
                      value={item.value}
                      disabled={readOnly}
                      inputMode="decimal"
                      autoComplete="off"
                      onChange={event => updateGrade(student.id, event.target.value)}
                      onKeyDown={event => handleKeyDown(event, index)}
                    />
                  </div>
                </td>
                <td>{rowChip(result, item.value)}</td>
              </tr>;
            })}
          </tbody>
        </table>
      </div>
    </section>}
    {confirming && <Confirm title="Submit grades?" busy={saveState === 'submitting'} onCancel={() => setConfirming(false)} onConfirm={submit}><p>This will make the selected grading period read-only. Draft grades are saved first, and the class cannot be edited after submission.</p></Confirm>}
  </div>;
}
