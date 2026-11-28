import { useCallback, useEffect, useMemo, useState } from 'react';
import { useAuth } from '../auth/AuthContext.jsx';
import { Failure } from '../EnrollmentWorkspace.jsx';
import { StatTile } from '../ui/components.jsx';
import { useAcademicContext } from './useAcademicContext.js';
import {
  attendanceStatuses, loadAttendanceClasses, loadCalendarReference, loadClassStudents, loadSchoolDays,
  loadStudentAttendance, maxBatch, saveAttendance, saveSchoolDays,
} from './dailyAttendanceApi.js';
import './academic-screens.css';
import './records-screens.css';

const weekdays = [[1, 'Mon'], [2, 'Tue'], [3, 'Wed'], [4, 'Thu'], [5, 'Fri'], [6, 'Sat'], [0, 'Sun']];

export function formatDay(iso) {
  const [year, month, day] = String(iso).split('-').map(Number);
  if (!year || !month || !day) return String(iso);
  return new Date(Date.UTC(year, month - 1, day)).toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' });
}

/** Every date from `from` to `to` (inclusive, YYYY-MM-DD) that falls on one of the chosen weekdays. */
export function datesBetween(from, to, days) {
  const result = [];
  const start = Date.parse(`${from}T00:00:00Z`);
  const end = Date.parse(`${to}T00:00:00Z`);
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) return result;
  for (let time = start; time <= end && result.length <= 366; time += 86400000) {
    const date = new Date(time);
    if (days.includes(date.getUTCDay())) result.push(date.toISOString().slice(0, 10));
  }
  return result;
}

const todayIso = () => {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
};

function conflictMessage(failure, fallback) {
  if (failure?.status === 409) return `${failure.message || fallback} Your changes are still on screen: reload the saved records, check them, then save again.`;
  return failure?.message || fallback;
}

function AttendanceRecorder() {
  const academic = useAcademicContext();
  const { filters } = academic;
  const [classes, setClasses] = useState({ status: 'idle', items: [], error: '' });
  const [classId, setClassId] = useState('');
  const [roster, setRoster] = useState({ status: 'idle', students: [], days: [], error: '' });
  const [saved, setSaved] = useState({});
  const [date, setDate] = useState('');
  const [drafts, setDrafts] = useState({});
  const [saving, setSaving] = useState(false);
  const [result, setResult] = useState({ error: '', done: '' });

  useEffect(() => {
    if (!academic.ready) return undefined;
    let active = true;
    setClasses({ status: 'loading', items: [], error: '' });
    setClassId('');
    loadAttendanceClasses(filters)
      .then(items => active && setClasses({ status: 'ready', items, error: '' }))
      .catch(failure => active && setClasses({ status: 'error', items: [], error: failure?.message || 'Your classes could not be loaded.' }));
    return () => { active = false; };
  }, [academic.ready, filters]);

  const selected = classes.items.find(item => String(item.id) === classId);

  const loadSaved = useCallback(async students => {
    const lists = await Promise.all(students.map(student => loadStudentAttendance(student.enrollmentId)));
    const next = {};
    students.forEach((student, index) => {
      next[student.enrollmentId] = Object.fromEntries(lists[index].map(item => [item.date, { status: item.status, version: item.version }]));
    });
    return next;
  }, []);

  useEffect(() => {
    if (!selected) { setRoster({ status: 'idle', students: [], days: [], error: '' }); return undefined; }
    let active = true;
    setRoster({ status: 'loading', students: [], days: [], error: '' });
    setDrafts({});
    setResult({ error: '', done: '' });
    Promise.all([loadClassStudents(selected.id, filters), loadSchoolDays(selected.campusId, selected.termId)])
      .then(async ([students, calendar]) => {
        const records = await loadSaved(students);
        if (!active) return;
        const days = calendar.filter(day => day.schoolDay).map(day => day.date);
        const today = todayIso();
        const pick = days.includes(today) ? today : [...days].reverse().find(day => day <= today) ?? days[0] ?? '';
        setSaved(records);
        setDate(pick);
        setRoster({ status: 'ready', students, days, error: '' });
      })
      .catch(failure => active && setRoster({ status: 'error', students: [], days: [], error: failure?.message || 'The class roster could not be loaded.' }));
    return () => { active = false; };
    // `selected` is derived from classId and the loaded classes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [classId, classes.items, loadSaved]);

  const marks = drafts[date] ?? {};
  const savedFor = id => saved[id]?.[date];
  const effective = id => marks[id] ?? savedFor(id)?.status ?? '';
  const changed = roster.students.filter(student => marks[student.enrollmentId] && marks[student.enrollmentId] !== savedFor(student.enrollmentId)?.status);
  const counts = useMemo(() => {
    const total = { present: 0, absent: 0, excused: 0, unmarked: 0 };
    for (const student of roster.students) total[effective(student.enrollmentId) || 'unmarked']++;
    return total;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [roster.students, drafts, saved, date]);

  function mark(enrollmentId, status) {
    setResult({ error: '', done: '' });
    setDrafts(current => ({ ...current, [date]: { ...(current[date] ?? {}), [enrollmentId]: status } }));
  }
  function markRemainingPresent() {
    setDrafts(current => {
      const next = { ...(current[date] ?? {}) };
      for (const student of roster.students) if (!effective(student.enrollmentId)) next[student.enrollmentId] = 'present';
      return { ...current, [date]: next };
    });
  }
  async function reloadSaved() {
    try {
      setSaved(await loadSaved(roster.students));
      setResult({ error: '', done: 'Saved attendance reloaded. Your unsaved marks are kept.' });
    } catch (failure) {
      setResult({ error: failure?.message || 'Saved attendance could not be reloaded.', done: '' });
    }
  }
  async function save() {
    const entries = changed.map(student => ({
      enrollmentId: Number(student.enrollmentId), date, status: marks[student.enrollmentId], note: null,
      version: savedFor(student.enrollmentId)?.version ?? 0,
    }));
    if (entries.length === 0) return;
    if (entries.length > maxBatch) { setResult({ error: `One save can hold at most ${maxBatch} entries.`, done: '' }); return; }
    if (new Set(entries.map(entry => entry.enrollmentId)).size !== entries.length) {
      setResult({ error: 'A student appears more than once in this save. Each student can be marked once per date.', done: '' });
      return;
    }
    setSaving(true);
    setResult({ error: '', done: '' });
    try {
      await saveAttendance(entries);
      const records = await loadSaved(roster.students);
      setSaved(records);
      setDrafts(current => ({ ...current, [date]: {} }));
      setResult({ error: '', done: `Saved attendance for ${entries.length} ${entries.length === 1 ? 'student' : 'students'} on ${formatDay(date)}.` });
    } catch (failure) {
      const message = /duplicate/i.test(failure?.message ?? '')
        ? 'This save marks a student more than once for the same date. Each student can be marked once per date.'
        : conflictMessage(failure, 'Attendance could not be saved.');
      setResult({ error: message, done: '' });
    } finally {
      setSaving(false);
    }
  }

  return <>
    <Failure error={academic.error || classes.error} />
    <section className="panel rs-context rs-context-class" aria-label="Class and date">
      <label>Academic year<select value={filters.academicYearId} onChange={event => academic.setYear(event.target.value)}>
        {academic.catalog.years.map(year => <option key={year.id} value={year.id}>{year.label}</option>)}
      </select></label>
      <label>Term<select value={filters.termId} onChange={event => academic.setTerm(event.target.value)}>
        {academic.terms.map(term => <option key={term.id} value={term.id}>{term.name}</option>)}
      </select></label>
      <label className="rs-wide">Class<select value={classId} onChange={event => setClassId(event.target.value)} disabled={classes.status !== 'ready'}>
        <option value="">{classes.status === 'loading' ? 'Loading classes…' : 'Choose a class'}</option>
        {classes.items.map(item => <option key={item.id} value={item.id}>{item.section} · {item.subjectCode} {item.subject}</option>)}
      </select></label>
      <label>Date<select value={date} onChange={event => { setDate(event.target.value); setResult({ error: '', done: '' }); }} disabled={roster.status !== 'ready' || roster.days.length === 0}>
        {roster.days.length === 0 && <option value="">No school days</option>}
        {roster.days.map(day => <option key={day} value={day}>{formatDay(day)}</option>)}
      </select></label>
    </section>

    {classes.status === 'ready' && classes.items.length === 0 && <div className="empty-state"><h3>No classes assigned this term</h3><p>Classes appear here once the registrar assigns you to them.</p></div>}
    <Failure error={roster.error} />
    {roster.status === 'loading' && <p role="status">Loading class roster…</p>}
    {roster.status === 'ready' && roster.days.length === 0 && <div className="empty-state"><h3>No school days on the calendar</h3><p>Attendance can only be recorded on dates the registrar has set as school days for this campus and term.</p></div>}
    {roster.status === 'ready' && roster.days.length > 0 && <>
      <div className="ac-stats" aria-label="Attendance summary">
        <StatTile label="Present" value={counts.present} tone="success" />
        <StatTile label="Absent" value={counts.absent} tone={counts.absent ? 'attention' : undefined} />
        <StatTile label="Excused" value={counts.excused} />
        <StatTile label="Not marked" value={counts.unmarked} />
      </div>
      <section className="panel" aria-labelledby="attendance-title">
        <div className="ac-report-head">
          <h2 id="attendance-title">{selected?.section} · {formatDay(date)}</h2>
          <div className="ac-filter-actions">
            <button type="button" className="ui-btn ui-btn-secondary" onClick={markRemainingPresent} disabled={counts.unmarked === 0}>Mark the rest present</button>
          </div>
        </div>
        {roster.students.length === 0
          ? <div className="empty-state"><h3>No students in this class</h3></div>
          : <div className="setup-table-wrap"><table className="ac-table">
            <thead><tr><th scope="col">Student</th><th scope="col" className="rs-right">Attendance</th></tr></thead>
            <tbody>{roster.students.map(student => {
              const id = student.enrollmentId;
              const pending = marks[id] && marks[id] !== savedFor(id)?.status;
              return <tr key={id}>
                <td data-label="Student"><strong>{student.name}</strong><small>{student.studentNumber}{pending ? ' · unsaved' : savedFor(id) ? ' · saved' : ''}</small></td>
                <td data-label="Attendance">
                  <fieldset className="rs-marks" aria-label={`Attendance for ${student.name}`}>
                    {attendanceStatuses.map(([value, label]) => <label key={value}>
                      <input type="radio" name={`mark-${id}`} value={value} checked={effective(id) === value} onChange={() => mark(id, value)} />{label}
                    </label>)}
                  </fieldset>
                </td>
              </tr>;
            })}</tbody>
          </table></div>}
        <div className="ac-pager">
          <span className="ac-muted">{changed.length ? `${changed.length} unsaved ${changed.length === 1 ? 'change' : 'changes'}` : 'No unsaved changes'}</span>
          <div className="rs-actions">
            <button type="button" className="ui-btn ui-btn-secondary" onClick={reloadSaved} disabled={saving}>Reload saved attendance</button>
            <button type="button" className="ui-btn ui-btn-primary" onClick={save} disabled={saving || changed.length === 0}>{saving ? 'Saving…' : 'Save attendance'}</button>
          </div>
        </div>
        {result.error && <div className="notice notice-error" role="alert">{result.error}</div>}
        {result.done && <p className="notice" role="status">{result.done}</p>}
      </section>
    </>}
  </>;
}

function SchoolDayCalendar() {
  const [reference, setReference] = useState({ campuses: [], terms: [] });
  const [referenceError, setReferenceError] = useState('');
  const [campusId, setCampusId] = useState('');
  const [termId, setTermId] = useState('');
  const [calendar, setCalendar] = useState({ status: 'idle', items: [], error: '' });
  const [edits, setEdits] = useState({});
  const [range, setRange] = useState({ from: '', to: '', days: [1, 2, 3, 4, 5] });
  const [reason, setReason] = useState('');
  const [saving, setSaving] = useState(false);
  const [result, setResult] = useState({ error: '', done: '' });
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    let active = true;
    loadCalendarReference().then(data => {
      if (!active) return;
      setReference({ campuses: data?.campuses ?? [], terms: data?.terms ?? [] });
      setCampusId(current => current || String(data?.campuses?.[0]?.id ?? ''));
      setTermId(current => current || String(data?.terms?.[0]?.id ?? ''));
    }).catch(failure => active && setReferenceError(failure?.message || 'Campuses and terms could not be loaded.'));
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (!campusId || !termId) return undefined;
    let active = true;
    setCalendar(current => ({ ...current, status: 'loading', error: '' }));
    loadSchoolDays(campusId, termId)
      .then(items => active && setCalendar({ status: 'ready', items, error: '' }))
      .catch(failure => active && setCalendar({ status: 'error', items: [], error: failure?.message || 'The calendar could not be loaded.' }));
    return () => { active = false; };
  }, [campusId, termId, revision]);

  const term = reference.terms.find(item => String(item.id) === termId);
  const byDate = useMemo(() => Object.fromEntries(calendar.items.map(item => [item.date, item])), [calendar.items]);
  const allDates = useMemo(() => [...new Set([...Object.keys(byDate), ...Object.keys(edits)])].sort(), [byDate, edits]);
  const pending = Object.entries(edits).filter(([day, schoolDay]) => byDate[day]?.schoolDay !== schoolDay);
  const isSchoolDay = day => edits[day] ?? byDate[day]?.schoolDay ?? false;

  function changeScope(setter, value) {
    setter(value);
    setEdits({});
    setResult({ error: '', done: '' });
  }
  function addRange(event) {
    event.preventDefault();
    const from = range.from || term?.startsOn;
    const to = range.to || term?.endsOn;
    if (!from || !to) { setResult({ error: 'Choose the first and last date to add.', done: '' }); return; }
    if ((term?.startsOn && from < term.startsOn) || (term?.endsOn && to > term.endsOn)) {
      setResult({ error: `Dates must fall within the term (${formatDay(term.startsOn)} to ${formatDay(term.endsOn)}).`, done: '' });
      return;
    }
    const dates = datesBetween(from, to, range.days);
    if (dates.length === 0) { setResult({ error: 'No dates in that range fall on the chosen weekdays.', done: '' }); return; }
    setEdits(current => ({ ...current, ...Object.fromEntries(dates.map(day => [day, true])) }));
    setResult({ error: '', done: `${dates.length} ${dates.length === 1 ? 'date' : 'dates'} marked as school days. Save to keep them.` });
  }
  async function save() {
    if (!reason.trim()) { setResult({ error: 'Give a reason for this calendar change.', done: '' }); return; }
    if (pending.length > 366) { setResult({ error: 'Save at most 366 dates at a time.', done: '' }); return; }
    setSaving(true);
    setResult({ error: '', done: '' });
    try {
      const reply = await saveSchoolDays(campusId, termId, pending.map(([day, schoolDay]) => ({ date: day, schoolDay, version: byDate[day]?.version ?? 0 })), reason);
      setCalendar({ status: 'ready', items: reply?.items ?? [], error: '' });
      setEdits({});
      setReason('');
      setResult({ error: '', done: `Saved ${pending.length} calendar ${pending.length === 1 ? 'change' : 'changes'}.` });
    } catch (failure) {
      setResult({ error: conflictMessage(failure, 'The calendar could not be saved.'), done: '' });
    } finally {
      setSaving(false);
    }
  }

  return <>
    <Failure error={referenceError} />
    <section className="panel rs-context" aria-label="Calendar scope">
      <label>Campus<select value={campusId} onChange={event => changeScope(setCampusId, event.target.value)}>
        {reference.campuses.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
      </select></label>
      <label>Term<select value={termId} onChange={event => changeScope(setTermId, event.target.value)}>
        {reference.terms.map(item => <option key={item.id} value={item.id}>{item.academicYear ? `${item.academicYear} · ` : ''}{item.name}</option>)}
      </select></label>
    </section>
    <Failure error={calendar.error} reload={() => setRevision(value => value + 1)} />
    <form className="panel rs-section" aria-label="Add school days" onSubmit={addRange}>
      <header><div><h2>Add school days</h2><p className="ac-muted">Weekends and holidays are never assumed. Only the dates you add here count as school days.</p></div></header>
      <div className="rs-grid">
        <label>From<input type="date" value={range.from} min={term?.startsOn} max={term?.endsOn} onChange={event => setRange({ ...range, from: event.target.value })} /></label>
        <label>To<input type="date" value={range.to} min={term?.startsOn} max={term?.endsOn} onChange={event => setRange({ ...range, to: event.target.value })} /></label>
      </div>
      <fieldset className="rs-marks rs-start" aria-label="Weekdays to include">
        {weekdays.map(([value, label]) => <label key={value}><input type="checkbox" checked={range.days.includes(value)}
          onChange={event => setRange({ ...range, days: event.target.checked ? [...range.days, value] : range.days.filter(day => day !== value) })} />{label}</label>)}
      </fieldset>
      <div className="rs-actions"><button type="submit" className="ui-btn ui-btn-secondary">Add dates</button></div>
    </form>
    <section className="panel rs-section" aria-labelledby="calendar-title">
      <header><div><h2 id="calendar-title">School-day calendar</h2><p className="ac-muted">Untick a date to make it a non-school day. A date that already has attendance cannot be unticked.</p></div></header>
      {calendar.status === 'loading' && <p role="status">Loading calendar…</p>}
      {calendar.status === 'ready' && allDates.length === 0 && <div className="empty-state"><h3>No school days set for this term</h3><p>Add a range of dates above.</p></div>}
      {allDates.length > 0 && <ul className="rs-days">{allDates.map(day => <li key={day}><label>
        <input type="checkbox" checked={isSchoolDay(day)} onChange={event => setEdits(current => ({ ...current, [day]: event.target.checked }))} />
        <span>{formatDay(day)}{day in edits && byDate[day]?.schoolDay !== edits[day] ? ' *' : ''}</span>
      </label></li>)}</ul>}
      <label className="rs-field">Reason for the change<textarea rows={2} maxLength={2000} value={reason} onChange={event => setReason(event.target.value)} placeholder="For example: approved school calendar, memo reference" /></label>
      <div className="rs-actions">
        <span className="ac-muted">{pending.length ? `${pending.length} unsaved ${pending.length === 1 ? 'change' : 'changes'} (marked *)` : 'No unsaved changes'}</span>
        <button type="button" className="ui-btn ui-btn-secondary" onClick={() => setRevision(value => value + 1)} disabled={saving}>Reload saved calendar</button>
        <button type="button" className="ui-btn ui-btn-primary" onClick={save} disabled={saving || pending.length === 0}>{saving ? 'Saving…' : 'Save calendar'}</button>
      </div>
      {result.error && <div className="notice notice-error" role="alert">{result.error}</div>}
      {result.done && <p className="notice" role="status">{result.done}</p>}
    </section>
  </>;
}

export default function DailyAttendance() {
  const { roles = [] } = useAuth();
  const canRecord = roles.includes('teacher');
  const canEditCalendar = roles.includes('registrar') || roles.includes('admin');
  const [tab, setTab] = useState(canRecord ? 'record' : 'calendar');
  const tabs = [canRecord && ['record', 'Record attendance'], canEditCalendar && ['calendar', 'School-day calendar']].filter(Boolean);
  return <div className="enrollment-workspace ac-screen rs-screen">
    <header className="page-heading">
      <p className="eyebrow">Academic records</p>
      <h1>Daily Attendance</h1>
      <p>Daily class attendance on school days. This is separate from first-week enrollment attendance.</p>
    </header>
    {tabs.length > 1 && <div className="rs-tabs" role="tablist" aria-label="Daily attendance views">
      {tabs.map(([key, label]) => <button key={key} type="button" role="tab" aria-selected={tab === key} onClick={() => setTab(key)}>{label}</button>)}
    </div>}
    {tab === 'record' && canRecord && <AttendanceRecorder />}
    {tab === 'calendar' && canEditCalendar && <SchoolDayCalendar />}
    {!canRecord && !canEditCalendar && <div className="empty-state"><h3>Nothing to do here</h3><p>Your role cannot record attendance or change the calendar.</p></div>}
  </div>;
}
