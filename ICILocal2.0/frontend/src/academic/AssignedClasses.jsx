import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { Failure } from '../EnrollmentWorkspace.jsx';
import { Drawer, StatusChip } from '../ui/components.jsx';
import { loadTeacherClasses } from './gradeEntryApi.js';
import { loadClassRoster, loadClassSummary } from './classListApi.js';
import { useAcademicContext } from './useAcademicContext.js';
import './academic-screens.css';

const stateChip = {
  draft: { tone: 'attention', label: 'Draft' },
  submitted: { tone: 'info', label: 'Submitted' },
  finalized: { tone: 'success', label: 'Finalized' },
  none: { tone: 'neutral', label: 'No grading period' },
};

function gradeEntryLink(item, filters) {
  const query = new URLSearchParams({ classId: String(item.id), academicYearId: filters.academicYearId, termId: filters.termId });
  return `/academic/grade-entry?${query}`;
}

function SubmissionCell({ summary }) {
  if (summary.status === 'loading') return <span className="ac-muted" role="status">Loading…</span>;
  if (summary.status === 'error') return <span className="ac-muted">Unavailable</span>;
  const chip = stateChip[summary.state] || stateChip.draft;
  return <span className="ac-submission">
    <StatusChip tone={chip.tone}>{chip.label}</StatusChip>
    {summary.period && <small>{summary.period.label}{summary.period.status === 'locked' ? ' · locked' : ''}</small>}
  </span>;
}

function ClassRow({ item, filters, onOpen }) {
  const [summary, setSummary] = useState({ status: 'loading' });
  useEffect(() => {
    let active = true;
    setSummary({ status: 'loading' });
    loadClassSummary(item.id, filters)
      .then(result => active && setSummary({ status: 'ready', ...result }))
      .catch(() => active && setSummary({ status: 'error' }));
    return () => { active = false; };
  }, [item.id, filters.academicYearId, filters.termId]);

  return <tr>
    <td data-label="Subject"><strong>{item.subject}</strong><small>{item.subjectCode}</small></td>
    <td data-label="Section"><strong>{item.section}</strong><small>{item.gradeLevel}{item.room ? ` · Room ${item.room}` : ''}</small></td>
    <td data-label="Students">{summary.status === 'ready' ? summary.studentCount : summary.status === 'error' ? '—' : '…'}</td>
    <td data-label="Grade submission"><SubmissionCell summary={summary} /></td>
    <td data-label="Actions" className="ac-actions">
      <button type="button" className="ui-btn ui-btn-secondary" onClick={() => onOpen(item)} aria-label={`View roster for ${item.subject}, ${item.section}`}>View roster</button>
      <Link className="ui-btn ui-btn-primary" to={gradeEntryLink(item, filters)} aria-label={`Open grade entry for ${item.subject}, ${item.section}`}>Grade entry</Link>
    </td>
  </tr>;
}

function RosterDrawer({ item, filters, onClose }) {
  const [state, setState] = useState({ status: 'loading', students: [], error: '' });
  useEffect(() => {
    let active = true;
    setState({ status: 'loading', students: [], error: '' });
    loadClassRoster(item.id, filters)
      .then(result => active && setState({ status: 'ready', students: result.students, error: '' }))
      .catch(failure => active && setState({ status: 'error', students: [], error: failure?.message || 'The roster could not be loaded.' }));
    return () => { active = false; };
  }, [item.id]);

  return <Drawer open onClose={onClose} title={`${item.subject} · ${item.section}`}
    footer={<Link className="ui-btn ui-btn-primary" to={gradeEntryLink(item, filters)}>Open grade entry</Link>}>
    {state.status === 'loading' && <p role="status">Loading roster…</p>}
    {state.status === 'error' && <div className="notice notice-error" role="alert">{state.error}</div>}
    {state.status === 'ready' && (state.students.length === 0
      ? <div className="empty-state"><h3>No students in this class</h3><p>Students appear here once they are enrolled and placed in the section.</p></div>
      : <>
        <p className="ac-muted">{state.students.length} student{state.students.length === 1 ? '' : 's'} · read-only</p>
        <ul className="ac-roster">
          {state.students.map(student => <li key={student.studentNumber}>
            <div><strong>{student.name}</strong><small>{student.studentNumber}</small></div>
            <StatusChip tone={student.enrollmentStatus === 'Enrolled' ? 'success' : 'attention'}>{student.enrollmentStatus}</StatusChip>
          </li>)}
        </ul>
      </>)}
  </Drawer>;
}

export default function AssignedClasses() {
  const academic = useAcademicContext();
  const { filters } = academic;
  const [state, setState] = useState({ status: 'idle', items: [], error: '' });
  const [open, setOpen] = useState(null);

  useEffect(() => {
    if (!academic.ready) return undefined;
    let active = true;
    setState({ status: 'loading', items: [], error: '' });
    loadTeacherClasses(filters)
      .then(result => active && setState({ status: 'ready', items: result.items, error: '' }))
      .catch(failure => active && setState({ status: 'error', items: [], error: failure?.status === 403 ? 'You are not authorized to view assigned classes.' : failure?.message || 'The classes could not be loaded.' }));
    return () => { active = false; };
  }, [academic.ready, filters.academicYearId, filters.termId]);

  const loading = !academic.loaded || state.status === 'loading';
  return <div className="academic-workspace ac-screen">
    <header className="page-heading">
      <p className="eyebrow">Academic Records</p>
      <h1>Assigned Classes</h1>
      <p>The classes you teach this term, with each class's roster and where grade submission stands.</p>
    </header>
    <Failure error={academic.error || state.error} reload={() => window.location.reload()} />
    <section className="panel ac-context" aria-label="Academic context">
      <label>Academic year
        <select value={filters.academicYearId} onChange={event => academic.setYear(event.target.value)} disabled={!academic.loaded}>
          {academic.catalog.years.map(year => <option key={year.id} value={year.id}>{year.label}</option>)}
        </select>
      </label>
      <label>Term
        <select value={filters.termId} onChange={event => academic.setTerm(event.target.value)} disabled={!academic.loaded}>
          {academic.terms.map(term => <option key={term.id} value={term.id}>{term.name} ({term.code})</option>)}
        </select>
      </label>
    </section>
    {loading && !academic.error && <p role="status">Loading classes…</p>}
    {academic.loaded && !academic.error && !academic.ready && <div className="empty-state"><h3>No academic term available</h3><p>Ask the registrar to set up an academic year and term.</p></div>}
    {state.status === 'ready' && (state.items.length === 0
      ? <div className="empty-state"><h3>No classes assigned</h3><p>You have no classes in this term. If that is unexpected, contact the registrar.</p></div>
      : <section className="panel" aria-label="Assigned classes">
        <div className="setup-table-wrap">
          <table className="ac-table">
            <thead><tr><th scope="col">Subject</th><th scope="col">Section</th><th scope="col">Students</th><th scope="col">Grade submission</th><th scope="col"><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>{state.items.map(item => <ClassRow key={item.id} item={item} filters={filters} onOpen={setOpen} />)}</tbody>
          </table>
        </div>
      </section>)}
    {open && <RosterDrawer item={open} filters={filters} onClose={() => setOpen(null)} />}
  </div>;
}
