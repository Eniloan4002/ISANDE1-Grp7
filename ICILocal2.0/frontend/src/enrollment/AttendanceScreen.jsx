import { useMemo, useState } from 'react';
import { Link } from 'react-router';
import './enrollment.css';

const attendanceDays = [
  { day: 'Mon', date: 'Aug 3, 2026' },
  { day: 'Tue', date: 'Aug 4, 2026' },
  { day: 'Wed', date: 'Aug 5, 2026' },
  { day: 'Thu', date: 'Aug 6, 2026' },
  { day: 'Fri', date: 'Aug 7, 2026' },
];
const attendanceStatuses = ['Present', 'Absent', 'Pending'];

function fullName(applicant) {
  return [applicant.firstName, applicant.middleName, applicant.lastName].filter(Boolean).join(' ');
}

function attendanceStatus(applicant, attendance) {
  const log = attendance[applicant.id] || attendanceDays.map(({ day, date }) => ({ day, date, status: 'Pending' }));
  const present = log.filter(item => item.status === 'Present').length;
  const absent = log.filter(item => item.status === 'Absent').length;
  const verified = log.length === attendanceDays.length && log.every(item => item.status === 'Present');
  const requirementsComplete = applicant.requirements === 'Complete';
  if (requirementsComplete && verified) return { key: 'ready', label: 'Ready to confirm', tone: 'success', present, absent, verified, requirementsComplete };
  if (!requirementsComplete && present === 0 && absent === 0) return { key: 'not-started', label: 'Not started', tone: 'neutral', present, absent, verified, requirementsComplete };
  if (!requirementsComplete) return { key: 'docs', label: 'Needs documents', tone: 'warning', present, absent, verified, requirementsComplete };
  return { key: 'attendance', label: 'Needs attendance', tone: absent ? 'danger' : 'warning', present, absent, verified, requirementsComplete };
}

function statusClass(status) {
  return status === 'Present' ? 'success' : status === 'Absent' ? 'danger' : 'warning';
}

function canConfirmStatus(status) {
  return ['pre-enlisted', 'pending requirements'].includes(status?.toLowerCase());
}

export default function AttendanceScreen({ applicants, attendance, updateAttendance, confirmEnrollment, basePath = '/enrollment' }) {
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('all');
  const [selectedId, setSelectedId] = useState(null);
  const [notice, setNotice] = useState('');
  const rows = useMemo(() => applicants.map(applicant => ({
    applicant,
    state: attendanceStatus(applicant, attendance),
  })).filter(({ applicant, state }) => {
    const query = search.trim().toLowerCase();
    const matchesSearch = !query || `${fullName(applicant)} ${applicant.id} ${applicant.lrn || ''}`.toLowerCase().includes(query);
    return matchesSearch && (statusFilter === 'all' || state.key === statusFilter);
  }), [applicants, attendance, search, statusFilter]);
  const selectedApplicant = applicants.find(applicant => applicant.id === selectedId);
  const selectedLog = selectedApplicant
    ? attendance[selectedApplicant.id] || attendanceDays.map(({ day, date }) => ({ day, date, status: 'Pending' }))
    : [];
  const selectedState = selectedApplicant ? attendanceStatus(selectedApplicant, attendance) : null;
  const counts = useMemo(() => ({
    total: applicants.length,
    ready: applicants.filter(applicant => attendanceStatus(applicant, attendance).key === 'ready').length,
    docs: applicants.filter(applicant => ['docs', 'not-started'].includes(attendanceStatus(applicant, attendance).key)).length,
    attendance: applicants.filter(applicant => attendanceStatus(applicant, attendance).key === 'attendance').length,
  }), [applicants, attendance]);

  function changeDay(index, value) {
    if (!selectedApplicant) return;
    updateAttendance(selectedApplicant.id, index, value);
    setNotice(`${fullName(selectedApplicant)} — ${attendanceDays[index].day} marked ${value}.`);
  }

  function confirm() {
    if (!selectedApplicant || !selectedState?.requirementsComplete || !selectedState.verified) return;
    const confirmed = confirmEnrollment(selectedApplicant.id);
    setNotice(confirmed ? `${fullName(selectedApplicant)} is now officially enrolled.` : 'Enrollment could not be confirmed.');
  }

  return <div className="enrollment-workspace">
    <header className="page-heading enrollment-heading">
      <div><p className="eyebrow">ENROLLMENT MANAGEMENT</p><h1>First-Week Attendance</h1><p>Monitor first-week attendance. The student can be confirmed only when requirements are complete and all five attendance days are verified.</p></div>
      <Link className="button button-secondary" to={`${basePath}/queue`}>← Back to Queue</Link>
    </header>
    {notice && <p className="requirements-notice" role="status">{notice}</p>}
    <div className="enrollment-stats attendance-stats" aria-label="Attendance statistics">
      <section className="enrollment-stat"><strong>{counts.total}</strong><div><span>Total monitored</span><small>Week 1 · Aug 3–7, 2026</small></div></section>
      <section className="enrollment-stat"><strong>{counts.ready}</strong><div><span>Ready to confirm</span><small>Both conditions met</small></div></section>
      <section className="enrollment-stat"><strong>{counts.docs}</strong><div><span>Needs documents</span><small>Requirements incomplete</small></div></section>
      <section className="enrollment-stat"><strong>{counts.attendance}</strong><div><span>Needs attendance</span><small>Not yet verified</small></div></section>
    </div>
    <section className="panel enrollment-panel" aria-labelledby="attendance-list-title">
      <div className="enrollment-panel-heading"><div><h2 id="attendance-list-title">Applicants Under Monitoring</h2><p>Select a student to review their Week 1 attendance log and confirmation gate.</p></div></div>
      <div className="enrollment-filters attendance-filters">
        <label>Search<input type="search" aria-label="Search attendance" placeholder="Name, application ID, or LRN" value={search} onChange={event => setSearch(event.target.value)} /></label>
        <label>Status<select aria-label="Filter attendance status" value={statusFilter} onChange={event => setStatusFilter(event.target.value)}><option value="all">All statuses</option><option value="ready">Ready to confirm</option><option value="docs">Needs documents</option><option value="attendance">Needs attendance</option><option value="not-started">Not started</option></select></label>
      </div>
      {rows.length ? <div className="enrollment-table-wrap"><table className="enrollment-table attendance-table">
        <thead><tr><th>Student</th><th>App ID</th><th>Category</th><th>Status</th><th>Action</th></tr></thead>
        <tbody>{rows.map(({ applicant, state }) => <tr key={applicant.id} className={selectedId === applicant.id ? 'attendance-selected-row' : undefined}>
          <td><strong>{applicant.lastName}, {applicant.firstName}</strong><small>{applicant.lrn ? `LRN ${applicant.lrn}` : 'LRN not provided'}</small></td>
          <td>{applicant.id}</td><td>{applicant.category}</td>
          <td><span className={`status status-${state.tone}`}>{state.label}</span><small>{state.present}/{attendanceDays.length} present · Requirements: {applicant.requirements}</small></td>
          <td><button className="button button-outline enrollment-view" type="button" onClick={() => setSelectedId(applicant.id)}>Monitor</button></td>
        </tr>)}</tbody>
      </table></div> : <div className="empty-state enrollment-empty"><h3>No matching applicants</h3><p>Try changing or clearing the search and filters.</p></div>}
      <p className="requirements-result-count">Showing {rows.length} of {applicants.length} applicants under first-week monitoring</p>
    </section>
    <section className="panel attendance-log-panel" aria-labelledby="attendance-log-title">
      <div className="enrollment-panel-heading"><div><h2 id="attendance-log-title">Week 1 — Attendance Log</h2><p>{selectedApplicant ? `Grade 11 · Week 1 · Aug 3–7, 2026` : 'Select a student above.'}</p></div></div>
      {selectedApplicant && selectedState ? <div className="attendance-log-body">
        <div className="attendance-selected-banner">Monitoring: <strong>{fullName(selectedApplicant)}</strong> · {selectedApplicant.id}</div>
        <div className="attendance-day-list">{selectedLog.map((item, index) => <div className="attendance-day-row" key={item.day}><span><strong>{item.day}</strong><small>{item.date || '—'}</small></span><select aria-label={`${item.day} attendance`} className={`attendance-status-${statusClass(item.status)}`} value={item.status} onChange={event => changeDay(index, event.target.value)}>{attendanceStatuses.map(status => <option key={status}>{status}</option>)}</select></div>)}</div>
        <div className="attendance-gate-note">Enrollment confirmation requires both complete mandatory documents and verified first-week attendance.</div>
        <div className="attendance-gate" aria-label="Enrollment confirmation gate">
          <span>1 · Requirements: <strong className={selectedState.requirementsComplete ? 'gate-ok' : 'gate-no'}>{selectedApplicant.requirements}</strong></span>
          <span>2 · Attendance: <strong className={selectedState.verified ? 'gate-ok' : 'gate-no'}>{selectedState.verified ? 'Verified' : 'Not verified'}</strong></span>
          <span>Overall: <strong className={selectedState.requirementsComplete && selectedState.verified ? 'gate-ok' : 'gate-no'}>{selectedState.requirementsComplete && selectedState.verified ? 'Both met' : 'Not met'}</strong></span>
        </div>
        <div className="attendance-actions">
          <button className="button button-secondary" type="button" onClick={() => setSelectedId(null)}>Close</button>
          <button className="button" type="button" disabled={!canConfirmStatus(selectedApplicant.status) || !selectedState.requirementsComplete || !selectedState.verified} onClick={confirm}>{selectedApplicant.status === 'Enrolled' ? 'Already enrolled' : 'Both met — Confirm enrollment'}</button>
        </div>
        {selectedApplicant.confirmationAudit && <p className="attendance-confirmation-audit" role="status">Confirmed by {selectedApplicant.confirmationAudit.confirmedBy} on {new Date(selectedApplicant.confirmationAudit.confirmedAt).toLocaleString('en-PH')}.</p>}
        {!canConfirmStatus(selectedApplicant.status) && <p className="attendance-help">Only Pre-enlisted or Pending Requirements applicants can be confirmed from this screen.</p>}
      </div> : <div className="empty-state attendance-empty"><h3>No student selected</h3><p>Select Monitor from the list to record attendance.</p></div>}
    </section>
  </div>;
}
