// ICI2-59 Read-only Guidance Student Profiles screen (/reporting/student-profiles).
import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { Failure, Pager, useDebouncedValue } from '../EnrollmentWorkspace.jsx';
import {
  formatGuidanceError,
  getGuidanceStudentHistory,
  listGuidanceCases,
  listGuidanceRisks,
  searchGuidanceStudents,
} from './guidanceApi.js';
import './guidance.css';

const SIGNAL_LABELS = {
  incomplete_grades: 'Incomplete grades',
  failing_grades: 'Failing grades',
  overdue_credentials: 'Overdue credentials',
  consecutive_unexcused_absences: 'Consecutive absences',
};

const CASE_STATUS_META = {
  open: { label: 'Open', tone: 'warning' },
  monitoring: { label: 'Monitoring', tone: 'warning' },
  closed: { label: 'Closed', tone: 'success' },
};

const fullName = row => [row.first_name, row.middle_name, row.last_name].filter(Boolean).join(' ');
const formatDate = value => (value ? new Date(value).toLocaleString() : '—');

function CaseStatusBadge({ status }) {
  const meta = CASE_STATUS_META[String(status || '').toLowerCase()] || { label: status || '—', tone: 'neutral' };
  return <span className={`status status-${meta.tone}`}>{meta.label}</span>;
}

function SignalBadge({ code }) {
  const tone = code === 'failing_grades' || code === 'consecutive_unexcused_absences' ? 'danger' : 'warning';
  return <span className={`status status-${tone}`}>{SIGNAL_LABELS[code] || code}</span>;
}

function StudentProfileDetail({ studentId }) {
  const [historyState, setHistoryState] = useState({ data: null, loading: true, error: '' });
  const [risksState, setRisksState] = useState({ data: null, loading: true, error: '' });
  const [casesState, setCasesState] = useState({ data: null, loading: true, error: '' });
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let live = true;
    setHistoryState(prev => ({ ...prev, loading: true, error: '' }));
    setRisksState(prev => ({ ...prev, loading: true, error: '' }));
    setCasesState(prev => ({ ...prev, loading: true, error: '' }));

    getGuidanceStudentHistory(studentId)
      .then(data => {
        if (live) setHistoryState({ data, loading: false, error: '' });
      })
      .catch(err => {
        if (live && err?.name !== 'AbortError') {
          setHistoryState({
            data: null,
            loading: false,
            error: formatGuidanceError(err, 'Student profile could not be loaded.'),
          });
        }
      });

    listGuidanceRisks({ page: 0, size: 100 })
      .then(data => {
        if (live) setRisksState({ data, loading: false, error: '' });
      })
      .catch(err => {
        if (live && err?.name !== 'AbortError') {
          setRisksState({
            data: null,
            loading: false,
            error: formatGuidanceError(err, 'Risk signals could not be loaded.'),
          });
        }
      });

    listGuidanceCases({ studentId, page: 0, size: 25 })
      .then(data => {
        if (live) setCasesState({ data, loading: false, error: '' });
      })
      .catch(err => {
        if (live && err?.name !== 'AbortError') {
          setCasesState({
            data: null,
            loading: false,
            error: formatGuidanceError(err, 'Counseling cases could not be loaded.'),
          });
        }
      });

    return () => {
      live = false;
    };
  }, [studentId, tick]);

  const history = historyState.data;
  const student = history?.student;
  const enrollmentIds = new Set((history?.terms || []).map(term => String(term.enrollmentId)));

  const studentRisks = (risksState.data?.items || []).filter(
    row => String(row.studentId) === String(studentId) || enrollmentIds.has(String(row.enrollmentId))
  );

  const studentCases = (casesState.data?.items || []).filter(row => {
    if (row.studentId != null) return String(row.studentId) === String(studentId);
    if (student?.studentNumber && row.studentNumber) return row.studentNumber === student.studentNumber;
    return enrollmentIds.has(String(row.enrollmentId));
  });

  function reloadAll() {
    setTick(value => value + 1);
  }

  return (
    <>
      <Failure error={historyState.error} reload={reloadAll} />
      {historyState.loading && !history && <p role="status">Loading student profile…</p>}

      {history && student && (
        <>
          <div className="selected-app">
            <div>
              <p className="eyebrow">{student.studentNumber}</p>
              <h2>{student.name}</h2>
            </div>
            <div className="component-row">
              <Link className="button button-outline" to="/reporting/reports">
                Open At-Risk Reports
              </Link>
              <button
                type="button"
                className="button button-outline"
                disabled={historyState.loading}
                onClick={reloadAll}
              >
                Reload profile
              </button>
            </div>
          </div>

          <div className="guidance-stats">
            <div>
              <span>Program</span>
              <strong>{student.program || '—'}</strong>
            </div>
            <div>
              <span>Campus</span>
              <strong>{student.campus || '—'}</strong>
            </div>
            <div>
              <span>LRN</span>
              <strong>{student.lrn || 'Not recorded'}</strong>
            </div>
            <div>
              <span>Record status</span>
              <strong>{student.active ? 'Active' : 'Archived'}</strong>
            </div>
          </div>

          <section className="panel" aria-labelledby="profile-risks-heading">
            <h2 id="profile-risks-heading">Risk signals</h2>
            <div className="panel-body">
              <Failure error={risksState.error} reload={reloadAll} />
              {risksState.loading && <p role="status">Checking risk signals…</p>}
              {!risksState.loading && !risksState.error && studentRisks.length === 0 && (
                <p className="field-help">No active risk evaluation records for this student.</p>
              )}
              {studentRisks.length > 0 && (
                <div className="setup-table-wrap">
                  <table className="setup-table">
                    <thead>
                      <tr>
                        <th>Term / section</th>
                        <th>Signals</th>
                        <th>Evidence summary</th>
                        <th>Action</th>
                      </tr>
                    </thead>
                    <tbody>
                      {studentRisks.map(row => (
                        <tr key={row.enrollmentId}>
                          <td>
                            <strong>
                              {row.academicYear} / {row.term}
                            </strong>
                            <small>{row.section || 'No section assigned'}</small>
                          </td>
                          <td>
                            {row.signals?.length ? (
                              <div className="guidance-signals">
                                {row.signals.map(code => (
                                  <SignalBadge key={code} code={code} />
                                ))}
                              </div>
                            ) : (
                              <span className="status status-success">No active signals</span>
                            )}
                          </td>
                          <td>
                            INC: <strong>{row.incompleteGrades ?? 0}</strong> · Failing:{' '}
                            <strong>{row.failingGrades ?? 0}</strong> · Missing docs:{' '}
                            <strong>{row.missingRequirements ?? 0}</strong> · Absences:{' '}
                            <strong>{row.consecutiveUnexcusedAbsences ?? 0}</strong>
                          </td>
                          <td>
                            {row.case ? (
                              <Link className="button button-outline" to={`/reporting/guidance?caseId=${row.case.id}`}>
                                View case #{row.case.id}
                              </Link>
                            ) : (
                              <Link className="button button-outline" to="/reporting/reports">
                                Review in At-Risk Reports
                              </Link>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </section>

          <section className="panel" aria-labelledby="profile-cases-heading">
            <h2 id="profile-cases-heading">Counseling cases</h2>
            <div className="panel-body">
              <Failure error={casesState.error} reload={reloadAll} />
              {casesState.loading && <p role="status">Loading counseling cases…</p>}
              {!casesState.loading && !casesState.error && studentCases.length === 0 && (
                <div className="empty-state">
                  <h3>No counseling cases opened</h3>
                  <p>
                    This profile is read-only. To open a confidential counseling case for a flagged learner, go to{' '}
                    <Link to="/reporting/reports">At-Risk Reports</Link>.
                  </p>
                </div>
              )}
              {studentCases.length > 0 && (
                <div className="setup-table-wrap">
                  <table className="setup-table">
                    <thead>
                      <tr>
                        <th>Case</th>
                        <th>Enrollment</th>
                        <th>Status</th>
                        <th>Reason</th>
                        <th>Action</th>
                      </tr>
                    </thead>
                    <tbody>
                      {studentCases.map(item => (
                        <tr key={item.id}>
                          <td>
                            <strong>#{item.id}</strong>
                            {item.createdAt && <small>{formatDate(item.createdAt)}</small>}
                          </td>
                          <td>Enrollment #{item.enrollmentId}</td>
                          <td>
                            <CaseStatusBadge status={item.status} />
                          </td>
                          <td>{item.reason || 'Open case to view details'}</td>
                          <td>
                            <Link className="button button-outline" to={`/reporting/guidance?caseId=${item.id}`}>
                              Open case #{item.id}
                            </Link>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </section>

          <section className="panel" aria-labelledby="profile-enrollments-heading">
            <h2 id="profile-enrollments-heading">Section &amp; enrollment history</h2>
            <div className="panel-body">
              {history.terms?.length ? (
                <div className="setup-table-wrap">
                  <table className="setup-table">
                    <thead>
                      <tr>
                        <th>Academic term</th>
                        <th>Program</th>
                        <th>Grade level</th>
                        <th>Section</th>
                        <th>Adviser</th>
                        <th>Standing</th>
                      </tr>
                    </thead>
                    <tbody>
                      {history.terms.map(term => (
                        <tr key={term.enrollmentId}>
                          <td>
                            <strong>
                              {term.academicYear} / {term.term}
                            </strong>
                            <small>Enrollment #{term.enrollmentId}</small>
                          </td>
                          <td>{term.program || '—'}</td>
                          <td>{term.gradeLevel || 'Not recorded'}</td>
                          <td>
                            {term.section || 'Unassigned'}
                            {term.room ? <small>{term.room}</small> : null}
                          </td>
                          <td>{term.adviser || 'Not assigned'}</td>
                          <td>
                            <span
                              className={`status ${
                                term.status === 'enrolled'
                                  ? 'status-success'
                                  : term.status === 'withdrawn'
                                    ? 'status-danger'
                                    : 'status-warning'
                              }`}
                            >
                              {term.status}
                            </span>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <p className="field-help">No enrollment history recorded for this student.</p>
              )}

              {history.sectionChanges?.length > 0 && (
                <>
                  <h3>Section changes</h3>
                  <ol className="verification-history">
                    {history.sectionChanges.map((change, index) => (
                      <li key={index}>
                        <strong>
                          {change.fromSection || 'Unassigned'} → {change.toSection || 'Unassigned'}
                        </strong>
                        <small>
                          {change.actor || 'System'} · {formatDate(change.occurredAt)}
                        </small>
                      </li>
                    ))}
                  </ol>
                </>
              )}

              {history.credentials && (
                <p className="field-help" style={{ marginTop: 16 }}>
                  <strong>Credentials ({history.credentials.applicationNumber}):</strong>{' '}
                  {history.credentials.overallStatus} ({history.credentials.submitted} of{' '}
                  {history.credentials.mandatoryTotal} mandatory submitted)
                </p>
              )}
            </div>
          </section>
        </>
      )}
    </>
  );
}

export default function StudentProfiles() {
  const [params, setParams] = useSearchParams();
  const [search, setSearch] = useState('');
  const debounced = useDebouncedValue(search.trim(), 250);
  const [page, setPage] = useState(0);
  const [tick, setTick] = useState(0);
  const [listState, setListState] = useState({ data: null, loading: true, error: '' });

  useEffect(() => {
    let live = true;
    setListState(prev => ({ ...prev, loading: true, error: '' }));
    searchGuidanceStudents({ search: debounced, page, size: 10 })
      .then(data => {
        if (live) setListState({ data, loading: false, error: '' });
      })
      .catch(err => {
        if (live && err?.name !== 'AbortError') {
          setListState({
            data: null,
            loading: false,
            error: formatGuidanceError(err, 'Students could not be loaded.'),
          });
        }
      });
    return () => {
      live = false;
    };
  }, [debounced, page, tick]);

  const selectedId = params.get('studentId') || '';
  const rows = listState.data?.items || [];

  return (
    <div className="guidance-workspace">
      <header className="page-heading">
        <p className="eyebrow">Guidance office · Read-only profiles</p>
        <h1>Student Profiles</h1>
        <p>
          Search students by name or student number to review their enrollment history, active risk signals, and counseling cases.
        </p>
      </header>

      <section className="panel" aria-labelledby="profile-search-heading">
        <h2 id="profile-search-heading">Find a student</h2>
        <div className="panel-body">
          <div className="guidance-filters">
            <div style={{ flex: '1 1 280px' }}>
              <label htmlFor="guidance-student-search">Name, LRN or student number</label>
              <input
                id="guidance-student-search"
                type="search"
                maxLength={150}
                value={search}
                onChange={event => {
                  setSearch(event.target.value);
                  setPage(0);
                }}
                placeholder="Search by student name or student number…"
              />
            </div>
            <button
              type="button"
              className="button button-outline"
              disabled={listState.loading}
              onClick={() => setTick(value => value + 1)}
            >
              Refresh directory
            </button>
          </div>

          <Failure error={listState.error} reload={() => setTick(value => value + 1)} />
          {listState.loading && <p role="status">Loading students…</p>}

          {rows.length > 0 && (
            <div className="setup-table-wrap">
              <table className="setup-table">
                <thead>
                  <tr>
                    <th>Student</th>
                    <th>LRN</th>
                    <th>Record</th>
                    <th>Action</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map(row => (
                    <tr
                      key={row.id}
                      className={String(row.id) === selectedId ? 'selected-row' : undefined}
                    >
                      <td>
                        <strong>{fullName(row)}</strong>
                        <small>{row.student_number}</small>
                      </td>
                      <td>{row.lrn || 'Not recorded'}</td>
                      <td>{row.active ? 'Active' : 'Archived'}</td>
                      <td>
                        <button
                          type="button"
                          className="button button-outline"
                          aria-label={`Open profile for ${row.student_number}`}
                          onClick={() => setParams({ studentId: String(row.id) })}
                        >
                          Open profile
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {!listState.loading && !listState.error && rows.length === 0 && (
            <div className="empty-state">
              <h3>No students match this search</h3>
              <p>Try a different name, student number, or LRN.</p>
            </div>
          )}

          <Pager
            page={page}
            size={10}
            total={listState.data?.total ?? 0}
            onChange={setPage}
            disabled={listState.loading}
          />
        </div>
      </section>

      {selectedId && /^\d+$/.test(selectedId) ? (
        <StudentProfileDetail key={selectedId} studentId={selectedId} />
      ) : (
        <p className="notice">
          Select a student above to view their read-only profile, section &amp; enrollment history, risk signals, and counseling cases.
        </p>
      )}
    </div>
  );
}
