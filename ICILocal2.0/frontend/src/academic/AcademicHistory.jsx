// ICI2-103: Academic History as master-detail. Searchable student list on the left, the selected student's
// 360° record on the right (header, summary tiles, Grades / Sections / Credentials / Notes tabs).
// The selected student and tab live in the URL (?studentId=&tab=) so a record can be linked to.
import { useId, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';
import { Failure, Pager, useDebouncedValue, useResource } from '../EnrollmentWorkspace.jsx';
import { Button, StatTile, StatusChip } from '../ui/components.jsx';
import { gradeFor, gradeText, periodColumns, statusLabel } from './historyFormat.js';
import { shortStudentNumber } from './studentNumber.js';
import './academic-history.css';

const PAGE_SIZE = 12;
const TABS = [
  { id: 'grades', label: 'Grades' },
  { id: 'sections', label: 'Sections' },
  { id: 'credentials', label: 'Credentials' },
  { id: 'notes', label: 'Notes' },
];
const toneFor = { Complete: 'success', enrolled: 'success', completed: 'success', submitted: 'success', Incomplete: 'blocked', missing: 'blocked', invalid: 'blocked', withdrawn: 'neutral', dropped: 'neutral' };
const Status = ({ value }) => <StatusChip tone={toneFor[value] ?? 'attention'}>{statusLabel(value)}</StatusChip>;
const fullName = row => [row.first_name, row.middle_name, row.last_name].filter(Boolean).join(' ');
const formatDate = value => value ? new Date(value).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }) : '';

/** Subject state from its period grades: finalized, submitted, incomplete or no grades yet. */
export function subjectState(subject) {
  const grades = subject.grades || [];
  if (!grades.length) return { tone: 'neutral', label: 'No grades' };
  if (grades.some(grade => grade.mark === 'INC')) return { tone: 'attention', label: 'Incomplete' };
  if (grades.every(grade => grade.state === 'finalized')) return { tone: 'success', label: 'Finalized' };
  return { tone: 'info', label: 'Submitted' };
}

/**
 * Unofficial general average: the mean of subject averages, using only subjects whose period grades are all
 * finalized and numeric. Returns null when no subject qualifies, so nothing is shown rather than a guess.
 */
export function generalAverage(term) {
  const finals = (term?.subjects || []).map(subject => {
    const grades = subject.grades || [];
    if (!grades.length || grades.some(grade => grade.state !== 'finalized' || grade.value == null)) return null;
    return grades.reduce((sum, grade) => sum + Number(grade.value), 0) / grades.length;
  }).filter(value => value != null);
  if (!finals.length) return null;
  return { value: finals.reduce((sum, value) => sum + value, 0) / finals.length, subjects: finals.length };
}

function StudentList({ selected, onSelect }) {
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const query = useDebouncedValue(search.trim());
  const list = useResource(`/api/academic/history/students?${new URLSearchParams({ search: query, page: String(page), size: String(PAGE_SIZE) })}`);
  const rows = list.data?.items ?? [];
  return <section className="ah-list" aria-label="Students">
    <div className="ah-search">
      <label htmlFor="history-search">Find a student</label>
      <input id="history-search" type="search" maxLength={150} placeholder="Name, LRN or student number" value={search} onChange={event => { setSearch(event.target.value); setPage(0); }} />
    </div>
    <Failure error={list.error} reload={list.reload} />
    {list.loading && !rows.length && <p className="ah-muted ah-pad" role="status">Loading students…</p>}
    {!list.loading && !list.error && rows.length === 0 && <p className="ah-muted ah-pad">No students match this search.</p>}
    {rows.length > 0 && <ul className="ah-students">
      {rows.map(row => {
        const isSelected = String(row.id) === selected;
        return <li key={row.id}>
          <button type="button" className={isSelected ? 'is-selected' : undefined} aria-current={isSelected ? 'true' : undefined} onClick={() => onSelect(String(row.id))}>
            <strong>{fullName(row)}</strong>
            <small>
              <span>LRN {row.lrn || 'not recorded'}</span>
              <span>{row.section || shortStudentNumber(row.student_number)}</span>
            </small>
            {!row.active && <StatusChip tone="neutral">Archived</StatusChip>}
          </button>
        </li>;
      })}
    </ul>}
    <div className="ah-pager"><Pager page={page} size={PAGE_SIZE} total={list.data?.total ?? 0} onChange={setPage} disabled={list.loading} /></div>
  </section>;
}

function Tabs({ id, active, onChange, counts }) {
  const refs = useRef({});
  function onKeyDown(event) {
    const index = TABS.findIndex(tab => tab.id === active);
    const step = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0;
    if (!step && event.key !== 'Home' && event.key !== 'End') return;
    event.preventDefault();
    const next = event.key === 'Home' ? TABS[0] : event.key === 'End' ? TABS[TABS.length - 1] : TABS[(index + step + TABS.length) % TABS.length];
    onChange(next.id);
    refs.current[next.id]?.focus();
  }
  return <div className="ah-tabs" role="tablist" aria-label="Record sections" onKeyDown={onKeyDown}>
    {TABS.map(tab => <button key={tab.id} ref={element => { refs.current[tab.id] = element; }} type="button" role="tab" id={`${id}-${tab.id}`} aria-selected={tab.id === active} aria-controls={`${id}-panel`} tabIndex={tab.id === active ? 0 : -1} onClick={() => onChange(tab.id)}>
      {tab.label}{counts[tab.id] != null && <span className="ah-tab-count" aria-hidden="true">{counts[tab.id]}</span>}
    </button>)}
  </div>;
}

function TermGrades({ term }) {
  const columns = periodColumns(term);
  return <section className="ah-block" aria-label={`${term.academicYear} ${term.term}`}>
    <header className="ah-block-head">
      <h3>{term.academicYear} · {term.term}</h3>
      <span className="ah-muted">{term.section ? `${term.gradeLevel ? `${term.gradeLevel} · ` : ''}${term.section}` : 'No section assigned'}</span>
      <Status value={term.status} />
    </header>
    {term.subjects.length ? <div className="ah-table-wrap"><table className="ah-table">
      <thead><tr><th scope="col">Subject</th>{columns.map(column => <th key={column.sequence} scope="col" className="ah-num">{column.period}</th>)}<th scope="col">State</th></tr></thead>
      <tbody>{term.subjects.map(subject => {
        const state = subjectState(subject);
        return <tr key={subject.code}>
          <th scope="row"><strong>{subject.name}</strong><small>{subject.code}{subject.units != null ? ` · ${subject.units} units` : ''}</small></th>
          {columns.map(column => <td key={column.sequence} className="ah-num">{gradeText(gradeFor(subject, column.sequence))}</td>)}
          <td><StatusChip tone={state.tone}>{state.label}</StatusChip></td>
        </tr>;
      })}</tbody>
    </table></div> : <p className="ah-muted ah-pad">No class records for this term.</p>}
  </section>;
}

function GradesTab({ data }) {
  if (!data.terms.length) return <div className="empty-state"><h3>No enrollment records yet</h3><p>Grades appear here once the student is enrolled in a class.</p></div>;
  return <>
    {data.terms.map(term => <TermGrades key={term.enrollmentId} term={term} />)}
    <p className="ah-muted">* Submitted by the teacher, not yet finalized. Draft grades are not shown.</p>
  </>;
}

function SectionsTab({ data }) {
  return <>
    <section className="ah-block" aria-label="Section history">
      <header className="ah-block-head"><h3>Section history</h3></header>
      {data.terms.length ? <div className="ah-table-wrap"><table className="ah-table">
        <thead><tr><th scope="col">Term</th><th scope="col">Section</th><th scope="col">Grade level</th><th scope="col">Adviser</th><th scope="col">Room</th></tr></thead>
        <tbody>{data.terms.map(term => <tr key={term.enrollmentId}>
          <th scope="row">{term.academicYear} · {term.term}</th><td>{term.section || 'Unassigned'}</td><td>{term.gradeLevel || 'Not recorded'}</td><td>{term.adviser || 'Not assigned'}</td><td>{term.room || 'Not recorded'}</td>
        </tr>)}</tbody>
      </table></div> : <p className="ah-muted ah-pad">No section records.</p>}
    </section>
    {data.sectionChanges.length > 0 && <section className="ah-block" aria-label="Section changes">
      <header className="ah-block-head"><h3>Section changes</h3></header>
      <ol className="ah-timeline">{data.sectionChanges.map((change, index) => <li key={index}>
        <strong>{change.fromSection || 'Unassigned'} → {change.toSection || 'Unassigned'}</strong>
        <small>{change.actor || 'System'} · {formatDate(change.occurredAt)}</small>
      </li>)}</ol>
    </section>}
  </>;
}

function CredentialsTab({ data }) {
  const credentials = data.credentials;
  if (!credentials) return <div className="empty-state"><h3>No application linked</h3><p>Credentials are tracked on the student's enrollment application.</p></div>;
  return <section className="ah-block" aria-label="Credentials">
    <header className="ah-block-head">
      <h3>Application {credentials.applicationNumber}</h3>
      <span className="ah-muted">{credentials.submitted} of {credentials.mandatoryTotal} mandatory submitted</span>
      <Status value={credentials.overallStatus} />
    </header>
    <div className="ah-table-wrap"><table className="ah-table">
      <thead><tr><th scope="col">Credential</th><th scope="col">Requirement</th><th scope="col">Status</th></tr></thead>
      <tbody>{credentials.items.map(item => <tr key={item.code}><th scope="row">{item.name}</th><td>{item.mandatory ? 'Mandatory' : 'Optional'}</td><td><Status value={item.status} /></td></tr>)}</tbody>
    </table></div>
  </section>;
}

function NotesTab({ data }) {
  const notes = [
    ...(data.incompleteCases || []).map(item => ({ key: `inc-${item.id}`, when: item.resolvedAt, title: `Incomplete grade · ${item.subjectCode} ${item.period}`, detail: item.status === 'resolved' ? `Resolved ${formatDate(item.resolvedAt)}` : statusLabel(item.status), tone: item.status === 'resolved' ? 'success' : 'attention', chip: item.status === 'resolved' ? 'Resolved' : 'Open' })),
    ...(data.withdrawals || []).map(item => ({ key: `wd-${item.id}`, when: item.requestedAt, title: 'Withdrawal request', detail: `Requested ${formatDate(item.requestedAt)}`, tone: 'neutral', chip: statusLabel(item.status) })),
    ...(data.adviserChanges || []).map((item, index) => ({ key: `adv-${index}`, when: item.occurredAt, title: `Adviser change · ${item.section}`, detail: `${item.previousAdviser || 'None'} → ${item.newAdviser || 'None'} · ${item.actor || 'System'} · ${formatDate(item.occurredAt)}`, tone: 'info', chip: 'Adviser' })),
  ].sort((a, b) => String(b.when || '9999').localeCompare(String(a.when || '9999'))); // undated (still open) items first
  if (!notes.length) return <div className="empty-state"><h3>No notes on this record</h3><p>Incomplete-grade cases, withdrawals and adviser changes appear here.</p></div>;
  return <section className="ah-block" aria-label="Notes">
    <ul className="ah-notes">{notes.map(note => <li key={note.key}>
      <div><strong>{note.title}</strong><small>{note.detail}</small></div>
      <StatusChip tone={note.tone}>{note.chip}</StatusChip>
    </li>)}</ul>
  </section>;
}

function StudentRecord({ studentId, tab, onTab }) {
  const history = useResource(`/api/academic/history/students/${studentId}`);
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState('');
  const tabsId = useId();
  const data = history.data;

  const summary = useMemo(() => {
    if (!data) return null;
    const latest = data.terms[0];
    const average = generalAverage(data.terms.find(term => generalAverage(term)));
    const openIncomplete = (data.incompleteCases || []).filter(item => item.status !== 'resolved').length;
    const notes = (data.incompleteCases || []).length + (data.withdrawals || []).length + (data.adviserChanges || []).length;
    return { latest, average, openIncomplete, notes };
  }, [data]);

  async function printRecord() {
    setExporting(true); setExportError('');
    try { const { downloadHistoryPdf } = await import('./historyPdf.js'); downloadHistoryPdf(data); }
    catch (error) { setExportError(error.message || 'The PDF could not be created.'); }
    finally { setExporting(false); }
  }

  if (history.error) return <Failure error={history.error} reload={history.reload} />;
  if (!data) return <p className="ah-muted" role="status">Loading academic history…</p>;
  const { student } = data;
  const credentials = data.credentials;
  return <article className="ah-record" aria-label={`Academic record for ${student.name}`}>
    <header className="ah-record-head">
      <div>
        <h2>{student.name}</h2>
        <p className="ah-muted">
          <span title={student.studentNumber}>{shortStudentNumber(student.studentNumber)}</span>
          <span>LRN {student.lrn || 'not recorded'}</span>
          <span>{student.program}{student.programName ? ` · ${student.programName}` : ''}</span>
          <span>{student.campus}</span>
        </p>
      </div>
      <div className="ah-record-actions">
        <StatusChip tone={student.active ? 'success' : 'neutral'}>{student.active ? 'Active' : 'Archived'}</StatusChip>
        {summary.latest && <Status value={summary.latest.status} />}
        <Button variant="secondary" disabled={exporting} onClick={printRecord}>{exporting ? 'Preparing PDF…' : 'Print record'}</Button>
      </div>
    </header>
    <Failure error={exportError} />

    <div className="ah-stats">
      <StatTile label="General average" value={summary.average ? summary.average.value.toFixed(2) : '—'} hint={summary.average ? `Unofficial · ${summary.average.subjects} finalized subject${summary.average.subjects === 1 ? '' : 's'}` : 'Needs finalized grades'} />
      <StatTile label="Subjects" value={summary.latest ? summary.latest.subjects.length : 0} hint={summary.latest ? `${summary.latest.academicYear} · ${summary.latest.term}` : 'No enrollment yet'} />
      <StatTile label="Incomplete" value={summary.openIncomplete} hint={summary.openIncomplete ? 'Open cases' : 'None open'} tone={summary.openIncomplete ? 'attention' : undefined} />
      <StatTile label="Credentials" value={credentials ? `${credentials.submitted}/${credentials.mandatoryTotal}` : '—'} hint={credentials ? statusLabel(credentials.overallStatus) : 'No application linked'} tone={credentials?.overallStatus === 'Incomplete' ? 'blocked' : credentials?.complete ? 'success' : undefined} />
    </div>

    <Tabs id={tabsId} active={tab} onChange={onTab} counts={{ grades: data.terms.length || null, notes: summary.notes || null }} />
    <div className="ah-tabpanel" role="tabpanel" id={`${tabsId}-panel`} aria-labelledby={`${tabsId}-${tab}`} tabIndex={0}>
      {tab === 'grades' && <GradesTab data={data} />}
      {tab === 'sections' && <SectionsTab data={data} />}
      {tab === 'credentials' && <CredentialsTab data={data} />}
      {tab === 'notes' && <NotesTab data={data} />}
    </div>
  </article>;
}

export default function AcademicHistory() {
  const [params, setParams] = useSearchParams();
  const selected = params.get('studentId') ?? '';
  const tab = TABS.some(item => item.id === params.get('tab')) ? params.get('tab') : 'grades';
  const detail = useRef(null);
  function select(id) {
    setParams({ studentId: id, tab });
    // On narrow screens the record sits below the list, so bring it into view.
    if (window.matchMedia?.('(max-width: 900px)').matches) requestAnimationFrame(() => detail.current?.scrollIntoView?.({ block: 'start' }));
  }
  const changeTab = next => setParams({ studentId: selected, tab: next }, { replace: true });

  return <div className="academic-workspace ah-screen">
    <header className="page-heading ah-heading">
      <p className="eyebrow">Academic Records</p>
      <h1>Academic History</h1>
    </header>
    <div className="ah-layout">
      <StudentList selected={selected} onSelect={select} />
      <div className="ah-detail" ref={detail}>
        {selected
          ? <StudentRecord key={selected} studentId={selected} tab={tab} onTab={changeTab} />
          : <div className="empty-state ah-empty"><h2>Select a student</h2><p>Choose a student from the list to see their grades, sections, credentials and notes.</p></div>}
      </div>
    </div>
  </div>;
}
