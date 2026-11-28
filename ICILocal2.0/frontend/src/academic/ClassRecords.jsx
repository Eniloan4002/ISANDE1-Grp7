import { useEffect, useMemo, useRef, useState } from 'react';
import { Failure } from '../EnrollmentWorkspace.jsx';
import { StatTile } from '../ui/components.jsx';
import { loadAdvisorySections, loadClassRecords } from './classRecordsApi.js';
import { useAcademicContext } from './useAcademicContext.js';
import './academic-screens.css';

// DepEd's passing grade. The school's own threshold can differ; this matches the default used elsewhere.
export const FAILING_BELOW = 75;

/** How one cell reads. The word is always shown, so colour is never the only signal. */
export function describeGrade(cell) {
  if (!cell) return { kind: 'missing', text: '—', note: 'Not yet submitted' };
  if (cell.mark === 'INC') return { kind: 'inc', text: 'INC', note: 'Incomplete' };
  if (cell.mark) return { kind: 'mark', text: cell.mark, note: '' };
  const value = Number(cell.value);
  if (Number.isNaN(value)) return { kind: 'missing', text: '—', note: 'Not yet submitted' };
  return { kind: value < FAILING_BELOW ? 'fail' : 'pass', text: value.toFixed(2), note: value < FAILING_BELOW ? 'Failing' : '' };
}

function GradeCell({ cell }) {
  const grade = describeGrade(cell);
  return <td className={`ac-grade ac-grade-${grade.kind}`}>
    <span>{grade.text}</span>{grade.note && grade.kind !== 'missing' && <small>{grade.note}</small>}
    {grade.kind === 'missing' && <span className="sr-only">{grade.note}</span>}
  </td>;
}

export default function ClassRecords() {
  const academic = useAcademicContext();
  const { filters } = academic;
  const [sections, setSections] = useState({ status: 'idle', items: [], error: '' });
  const [sectionId, setSectionId] = useState('');
  const [periodId, setPeriodId] = useState('');
  const [records, setRecords] = useState({ status: 'idle', data: null, error: '' });
  const [revision, setRevision] = useState(0);
  const adoptedPeriod = useRef('');

  useEffect(() => {
    if (!academic.ready) return undefined;
    const controller = new AbortController();
    setSections({ status: 'loading', items: [], error: '' });
    setRecords({ status: 'idle', data: null, error: '' });
    loadAdvisorySections(filters, controller.signal)
      .then(items => {
        setSections({ status: 'ready', items, error: '' });
        setSectionId(current => (items.some(item => String(item.id) === current) ? current : String(items[0]?.id ?? '')));
        setPeriodId('');
      })
      .catch(failure => {
        if (failure?.name === 'AbortError') return;
        setSections({ status: 'error', items: [], error: failure?.message || 'Your advisory sections could not be loaded.' });
      });
    return () => controller.abort();
  }, [academic.ready, filters.academicYearId, filters.termId]);

  useEffect(() => {
    if (!sectionId) return undefined;
    // Picking the server's default period after the first load must not fetch the same page again.
    if (periodId && periodId === adoptedPeriod.current) { adoptedPeriod.current = ''; return undefined; }
    const controller = new AbortController();
    setRecords(current => ({ ...current, status: 'loading', error: '' }));
    loadClassRecords(filters, sectionId, periodId, controller.signal)
      .then(data => {
        setRecords({ status: 'ready', data, error: '' });
        if (!periodId) {
          adoptedPeriod.current = String(data.period?.id ?? '');
          setPeriodId(adoptedPeriod.current);
        }
      })
      .catch(failure => {
        if (failure?.name === 'AbortError') return;
        setRecords({ status: 'error', data: null, error: failure?.message || 'The class records could not be loaded.' });
      });
    return () => controller.abort();
  }, [sectionId, periodId, filters.academicYearId, filters.termId, revision]);

  const data = records.data;
  const counts = useMemo(() => {
    const result = { inc: 0, fail: 0 };
    for (const student of data?.students ?? []) {
      const kinds = Object.values(student.grades ?? {}).map(cell => describeGrade(cell).kind);
      if (kinds.includes('inc')) result.inc += 1;
      if (kinds.includes('fail')) result.fail += 1;
    }
    return result;
  }, [data]);

  const loading = !academic.loaded || sections.status === 'loading';
  return <div className="academic-workspace ac-screen">
    <header className="page-heading">
      <p className="eyebrow">Academic Records</p>
      <h1>Class Records</h1>
      <p>Every student's submitted grades across all subjects for your advisory section. Read-only.</p>
    </header>
    <Failure error={academic.error || sections.error} reload={() => window.location.reload()} />
    <section className="panel ac-context" aria-label="Class record filters">
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
      {sections.items.length > 1 && <label>Section
        <select value={sectionId} onChange={event => { setSectionId(event.target.value); setPeriodId(''); }}>
          {sections.items.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
        </select>
      </label>}
      {data?.periods?.length > 0 && <label>Grading period
        <select value={periodId} onChange={event => setPeriodId(event.target.value)}>
          {data.periods.map(period => <option key={period.id} value={period.id}>{period.code}</option>)}
        </select>
      </label>}
    </section>

    {loading && !academic.error && <p role="status">Loading class records…</p>}
    {academic.loaded && !academic.error && !academic.ready && <div className="empty-state"><h3>No academic term available</h3><p>Ask the registrar to set up an academic year and term.</p></div>}
    {sections.status === 'ready' && sections.items.length === 0 && <div className="empty-state"><h3>No advisory section assigned</h3><p>You are not the adviser of a section in this term. Contact the registrar if this is unexpected.</p></div>}
    <Failure error={records.error} reload={() => setRevision(value => value + 1)} />
    {records.status === 'loading' && !data && <p role="status">Loading grades…</p>}

    {data && <>
      <section className="panel ac-overview" aria-label="Section overview">
        <div><span className="eyebrow">Advisory section</span><h2>{data.section.name}</h2><p className="ac-muted">{data.section.gradeLevel} · {data.period.code}</p></div>
      </section>
      <div className="ac-stats" aria-label="Grade flags">
        <StatTile label="Students" value={data.students.length} />
        <StatTile label="With an INC" value={counts.inc} tone={counts.inc > 0 ? 'attention' : undefined} />
        <StatTile label={`Failing (below ${FAILING_BELOW})`} value={counts.fail} tone={counts.fail > 0 ? 'blocked' : undefined} />
      </div>
      {data.students.length === 0
        ? <div className="empty-state"><h3>No students in this section</h3><p>Students appear here once they are enrolled and placed in the section.</p></div>
        : data.subjects.length === 0
          ? <div className="empty-state"><h3>No subjects set up</h3><p>The registrar has not assigned teachers to subjects for this section yet.</p></div>
          : <section className="panel" aria-label="Grade matrix">
            <div className="ac-matrix-wrap" tabIndex={0} role="region" aria-label="Grades by student and subject, scrolls sideways">
              <table className="ac-matrix">
                <thead><tr>
                  <th scope="col" className="ac-sticky">Student</th>
                  {data.subjects.map(subject => <th scope="col" key={subject.classId}><span>{subject.name}</span><small>{subject.code}</small></th>)}
                </tr></thead>
                <tbody>{data.students.map(student => <tr key={student.enrollmentId}>
                  <th scope="row" className="ac-sticky"><strong>{student.name}</strong><small>{student.studentNumber}</small></th>
                  {data.subjects.map(subject => <GradeCell key={subject.classId} cell={student.grades?.[String(subject.classId)]} />)}
                </tr>)}</tbody>
              </table>
            </div>
            <p className="ac-legend ac-muted">Only submitted grades are shown. <b>INC</b> marks an incomplete grade. A grade below {FAILING_BELOW} is flagged <b>Failing</b>. <b>—</b> means the teacher has not submitted yet.</p>
          </section>}
    </>}
  </div>;
}
