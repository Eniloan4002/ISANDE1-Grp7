// ICI2-58 Guidance At-Risk Dashboard (/reporting/reports) & Confidential Counseling Cases (/reporting/guidance).
import { useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { Failure, Pager } from '../EnrollmentWorkspace.jsx';
import { useAuth } from '../auth/AuthContext.jsx';
import {
  addGuidanceCaseNote,
  createGuidanceCase,
  exportGuidanceCase,
  formatGuidanceError,
  getGuidanceCase,
  listGuidanceCases,
  listGuidanceRisks,
  updateGuidanceCase,
} from './guidanceApi.js';
import GuidancePolicyPanel from './GuidancePolicyPanel.jsx';
import './guidance.css';

const SIGNAL_LABELS = {
  incomplete_grades: 'Incomplete grades',
  failing_grades: 'Failing grades',
  overdue_credentials: 'Overdue credentials',
  consecutive_unexcused_absences: 'Consecutive absences',
};

const UNCONFIGURED_LABELS = {
  failing_grade_threshold: 'Failing grade threshold not configured',
  credential_due_date: 'Credential due date not configured',
  consecutive_absence_threshold: 'Consecutive absence threshold not configured',
  school_calendar_gaps: 'School calendar has unconfigured dates',
};

const CASE_STATUS_META = {
  open: { label: 'Open', tone: 'warning' },
  monitoring: { label: 'Monitoring', tone: 'warning' },
  closed: { label: 'Closed', tone: 'success' },
};

const formatDate = value => (value ? new Date(value).toLocaleString() : '—');

function CaseStatusBadge({ status }) {
  const meta = CASE_STATUS_META[String(status || '').toLowerCase()] || { label: status || '—', tone: 'neutral' };
  return <span className={`status status-${meta.tone}`}>{meta.label}</span>;
}

function SignalBadge({ code }) {
  const tone = code === 'failing_grades' || code === 'consecutive_unexcused_absences' ? 'danger' : 'warning';
  return <span className={`status status-${tone}`}>{SIGNAL_LABELS[code] || code}</span>;
}

function RiskDetailsPanel({ student, onCaseOpened }) {
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  useEffect(() => {
    setReason('');
    setError('');
    setNotice('');
  }, [student.enrollmentId]);

  const signals = student.signals || [];
  const unconfigured = student.unconfiguredSignals || [];
  const existingCase = student.case;

  async function openCase(event) {
    event.preventDefault();
    if (!reason.trim()) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const created = await createGuidanceCase({
        enrollmentId: student.enrollmentId,
        reason: reason.trim(),
      });
      setReason('');
      setNotice(`Confidential counseling case #${created.id} opened for ${student.name}.`);
      onCaseOpened(created);
    } catch (err) {
      setError(formatGuidanceError(err, 'Counseling case could not be opened.'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel" aria-labelledby="risk-details-heading">
      <h2 id="risk-details-heading">Student risk details — {student.name}</h2>
      <div className="panel-body">
        <div className="selected-app" style={{ paddingTop: 0 }}>
          <div>
            <p className="eyebrow">
              {student.studentNumber} · {student.campus} · {student.academicYear} / {student.term}
            </p>
            <h2>
              {student.name}{' '}
              <span className={`status ${student.atRisk ? 'status-danger' : 'status-success'}`}>
                {student.atRisk ? 'At risk' : 'No active signals'}
              </span>
            </h2>
          </div>
          {existingCase && (
            <div className="component-row">
              <Link className="button" to={`/reporting/guidance?caseId=${existingCase.id}`}>
                Open counseling case #{existingCase.id}
              </Link>
            </div>
          )}
        </div>

        <dl className="guidance-meta-grid">
          <div>
            <dt>Program / grade</dt>
            <dd>
              {student.program || '—'} · {student.gradeLevel || 'Grade not set'}
            </dd>
          </div>
          <div>
            <dt>Section</dt>
            <dd>{student.section || 'Unassigned'}</dd>
          </div>
          <div>
            <dt>Enrollment status</dt>
            <dd>{student.status}</dd>
          </div>
          <div>
            <dt>Counseling case</dt>
            <dd>
              {existingCase ? (
                <>
                  Case #{existingCase.id} <CaseStatusBadge status={existingCase.status} />
                </>
              ) : (
                'None opened'
              )}
            </dd>
          </div>
        </dl>

        <h3>Evidence by risk signal</h3>
        <div className="guidance-evidence-grid">
          <article className={`guidance-evidence-card${signals.includes('incomplete_grades') ? ' is-triggered' : ''}`}>
            <h4>
              <span>Incomplete grades</span>
              <span className={`status ${student.incompleteGrades > 0 ? 'status-warning' : 'status-success'}`}>
                {student.incompleteGrades ?? 0} INC
              </span>
            </h4>
            <p>
              {student.incompleteGrades > 0
                ? `${student.incompleteGrades} submitted or finalized subject grade(s) currently carry an INC mark.`
                : 'No submitted or finalized incomplete grades recorded.'}
            </p>
          </article>

          <article
            className={`guidance-evidence-card${
              signals.includes('failing_grades')
                ? ' is-triggered'
                : unconfigured.includes('failing_grade_threshold')
                  ? ' is-unconfigured'
                  : ''
            }`}
          >
            <h4>
              <span>Failing grades</span>
              <span className={`status ${student.failingGrades > 0 ? 'status-danger' : 'status-success'}`}>
                {student.failingGrades ?? 0} failing
              </span>
            </h4>
            <p>
              {unconfigured.includes('failing_grade_threshold')
                ? 'Failing grade threshold has not been configured for this campus and term.'
                : student.failingGrades > 0
                  ? `${student.failingGrades} submitted or finalized grade(s) fall below the campus failing threshold.`
                  : 'No submitted or finalized grades fall below the configured threshold.'}
            </p>
          </article>

          <article
            className={`guidance-evidence-card${
              signals.includes('overdue_credentials')
                ? ' is-triggered'
                : unconfigured.includes('credential_due_date')
                  ? ' is-unconfigured'
                  : ''
            }`}
          >
            <h4>
              <span>Overdue credentials</span>
              <span className={`status ${student.missingRequirements > 0 ? 'status-warning' : 'status-success'}`}>
                {student.missingRequirements ?? 0} missing
              </span>
            </h4>
            <p>
              {unconfigured.includes('credential_due_date')
                ? 'Credential due date has not been configured for this campus and term.'
                : student.missingRequirements > 0
                  ? `Missing mandatory document(s): ${student.missingDocuments || 'See enrollment checklist'}.`
                  : 'All mandatory credentials are marked submitted.'}
            </p>
          </article>

          <article
            className={`guidance-evidence-card${
              signals.includes('consecutive_unexcused_absences')
                ? ' is-triggered'
                : unconfigured.includes('consecutive_absence_threshold') || unconfigured.includes('school_calendar_gaps')
                  ? ' is-unconfigured'
                  : ''
            }`}
          >
            <h4>
              <span>Consecutive unexcused absences</span>
              <span
                className={`status ${
                  signals.includes('consecutive_unexcused_absences') ? 'status-danger' : 'status-success'
                }`}
              >
                {student.consecutiveUnexcusedAbsences ?? 0} day(s)
              </span>
            </h4>
            <p>
              {unconfigured.includes('consecutive_absence_threshold')
                ? 'Consecutive absence threshold has not been configured for this campus and term.'
                : unconfigured.includes('school_calendar_gaps')
                  ? `Trailing streak: ${student.consecutiveUnexcusedAbsences ?? 0} day(s). Note: some dates in this term are not configured in the school calendar.`
                  : `${student.consecutiveUnexcusedAbsences ?? 0} consecutive unexcused absence(s) on configured school days up to the evaluation date.`}
            </p>
          </article>
        </div>

        {unconfigured.length > 0 && (
          <p className="notice" role="status">
            <strong>Unconfigured policy signals:</strong>{' '}
            {unconfigured.map(code => UNCONFIGURED_LABELS[code] || code).join('; ')}.
          </p>
        )}

        {notice && (
          <p className="notice" role="status">
            {notice}
          </p>
        )}
        <Failure error={error} />

        {!existingCase && (
          <form onSubmit={openCase}>
            <h3>Open confidential counseling case</h3>
            <label htmlFor="open-case-reason">Initial referral / intervention reason</label>
            <textarea
              id="open-case-reason"
              className="guidance-textarea"
              required
              maxLength={2000}
              disabled={busy}
              value={reason}
              onChange={event => setReason(event.target.value)}
              placeholder="Document the risk indicators and initial counseling plan…"
            />
            <button type="submit" className="button" disabled={busy || !reason.trim()}>
              {busy ? 'Opening case…' : 'Open counseling case'}
            </button>
          </form>
        )}
      </div>
    </section>
  );
}

function CaseDetailPanel({ caseId, onUpdated }) {
  const [detail, setDetail] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [tick, setTick] = useState(0);

  const [status, setStatus] = useState('open');
  const [statusReason, setStatusReason] = useState('');
  const [noteBody, setNoteBody] = useState('');
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');
  const [exportBusy, setExportBusy] = useState('');

  useEffect(() => {
    let live = true;
    setLoading(true);
    setError('');
    setActionError('');
    getGuidanceCase(caseId)
      .then(data => {
        if (!live) return;
        setDetail(data);
        setStatus(data.status || 'open');
        setStatusReason(data.reason || '');
      })
      .catch(err => {
        if (live && err?.name !== 'AbortError') {
          setDetail(null);
          setError(formatGuidanceError(err, 'Counseling case details could not be loaded.'));
        }
      })
      .finally(() => {
        if (live) setLoading(false);
      });
    return () => {
      live = false;
    };
  }, [caseId, tick]);

  async function handleStatusSave(event) {
    event.preventDefault();
    if (!detail || !statusReason.trim()) return;
    setBusy(true);
    setActionError('');
    setNotice('');
    try {
      const updated = await updateGuidanceCase(detail.id, {
        status,
        reason: statusReason.trim(),
        version: detail.version,
      });
      setDetail(updated);
      setStatus(updated.status);
      setStatusReason(updated.reason || '');
      setNotice(`Case #${detail.id} status updated to ${updated.status}.`);
      onUpdated();
    } catch (err) {
      setActionError(formatGuidanceError(err, 'Case status could not be updated.'));
    } finally {
      setBusy(false);
    }
  }

  async function handleAddNote(event) {
    event.preventDefault();
    if (!detail || !noteBody.trim()) return;
    setBusy(true);
    setActionError('');
    setNotice('');
    try {
      const updated = await addGuidanceCaseNote(detail.id, {
        note: noteBody.trim(),
        version: detail.version,
      });
      setDetail(updated);
      setNoteBody('');
      setNotice('Intervention note logged in confidential case record.');
      onUpdated();
    } catch (err) {
      setActionError(formatGuidanceError(err, 'Intervention note could not be saved.'));
    } finally {
      setBusy(false);
    }
  }

  async function handleExport(format) {
    if (!detail) return;
    setExportBusy(format);
    setActionError('');
    try {
      await exportGuidanceCase(detail.id, format);
    } catch (err) {
      setActionError(formatGuidanceError(err, `Case ${format.toUpperCase()} export failed.`));
    } finally {
      setExportBusy('');
    }
  }

  const studentNumber = detail?.student_number ?? detail?.studentNumber ?? '—';
  const enrollmentId = detail?.enrollment_id ?? detail?.enrollmentId ?? '—';

  return (
    <section className="panel" aria-labelledby="case-detail-heading">
      <h2 id="case-detail-heading">Confidential counseling case #{caseId}</h2>
      <div className="panel-body">
        <Failure error={error} reload={() => setTick(value => value + 1)} />
        <Failure error={actionError} />
        {notice && (
          <p className="notice" role="status">
            {notice}
          </p>
        )}
        {loading && !detail && <p role="status">Loading counseling case…</p>}

        {detail && (
          <>
            <div className="selected-app" style={{ paddingTop: 0 }}>
              <div>
                <p className="eyebrow">
                  Case #{detail.id} · Enrollment #{enrollmentId} · Version {detail.version}
                </p>
                <h2>
                  {detail.name} <CaseStatusBadge status={detail.status} />
                </h2>
              </div>
              <div className="component-row">
                <button
                  type="button"
                  className="button"
                  disabled={Boolean(exportBusy)}
                  onClick={() => handleExport('pdf')}
                >
                  {exportBusy === 'pdf' ? 'Exporting PDF…' : 'Export PDF'}
                </button>
                <button
                  type="button"
                  className="button button-outline"
                  disabled={Boolean(exportBusy)}
                  onClick={() => handleExport('csv')}
                >
                  {exportBusy === 'csv' ? 'Exporting CSV…' : 'Export CSV'}
                </button>
                <button
                  type="button"
                  className="button button-outline"
                  disabled={loading || busy}
                  onClick={() => setTick(value => value + 1)}
                >
                  Reload case
                </button>
              </div>
            </div>

            <dl className="guidance-meta-grid">
              <div>
                <dt>Student number</dt>
                <dd>{studentNumber}</dd>
              </div>
              <div>
                <dt>Status</dt>
                <dd>{detail.status}</dd>
              </div>
              <div>
                <dt>Opened</dt>
                <dd>{formatDate(detail.created_at ?? detail.createdAt)}</dd>
              </div>
              <div>
                <dt>Last updated</dt>
                <dd>{formatDate(detail.updated_at ?? detail.updatedAt)}</dd>
              </div>
            </dl>

            <div className="guidance-two-col">
              <section className="panel">
                <h2>Update case status</h2>
                <form className="panel-body" onSubmit={handleStatusSave}>
                  <label htmlFor="case-status-select">Case status</label>
                  <select
                    id="case-status-select"
                    disabled={busy}
                    value={status}
                    onChange={event => setStatus(event.target.value)}
                  >
                    <option value="open">Open</option>
                    <option value="monitoring">Monitoring</option>
                    <option value="closed">Closed</option>
                  </select>

                  <label htmlFor="case-status-reason">Case summary / status reason</label>
                  <textarea
                    id="case-status-reason"
                    className="guidance-textarea"
                    required
                    maxLength={2000}
                    disabled={busy}
                    value={statusReason}
                    onChange={event => setStatusReason(event.target.value)}
                  />

                  <button type="submit" className="button" disabled={busy || !statusReason.trim()}>
                    {busy ? 'Saving…' : 'Save case status'}
                  </button>
                </form>
              </section>

              <section className="panel">
                <h2>Log intervention / note</h2>
                <form className="panel-body" onSubmit={handleAddNote}>
                  <label htmlFor="case-note-input">Intervention or counseling note</label>
                  <textarea
                    id="case-note-input"
                    className="guidance-textarea"
                    required
                    maxLength={2000}
                    disabled={busy}
                    value={noteBody}
                    onChange={event => setNoteBody(event.target.value)}
                    placeholder="Record counseling session outcome, parent conference, or follow-up action…"
                  />
                  <button type="submit" className="button" disabled={busy || !noteBody.trim()}>
                    {busy ? 'Logging…' : 'Log intervention note'}
                  </button>
                </form>
              </section>
            </div>

            <h3>Intervention history ({detail.notes?.length ?? 0})</h3>
            {detail.notes?.length ? (
              <ol className="verification-history">
                {detail.notes.map(item => (
                  <li key={item.id}>
                    <strong>{item.author || `Staff #${item.authorId}`}</strong>
                    <small>{formatDate(item.createdAt)}</small>
                    <p>{item.note}</p>
                  </li>
                ))}
              </ol>
            ) : (
              <p className="field-help">No intervention notes have been logged on this case yet.</p>
            )}
          </>
        )}
      </div>
    </section>
  );
}

export function AtRiskReports() {
  const { user, roles } = useAuth();
  const canConfigure = roles.includes('admin') || roles.includes('registrar');

  const [campusId, setCampusId] = useState('');
  const [termId, setTermId] = useState('');
  const [signalFilter, setSignalFilter] = useState('all');
  const [asOf, setAsOf] = useState('');
  const [selectedEnrollmentId, setSelectedEnrollmentId] = useState('');
  const [tick, setTick] = useState(0);

  const [risksState, setRisksState] = useState({ data: null, loading: true, error: '' });
  const [knownCampuses, setKnownCampuses] = useState([]);
  const [knownTerms, setKnownTerms] = useState([]);

  const grantsKey = (user?.grants || [])
    .map(grant => `${grant.campusId ?? ''}:${grant.campusName || grant.campusCode || ''}`)
    .join('|');

  useEffect(() => {
    const grantCampuses = [];
    const seen = new Set();
    for (const grant of user?.grants || []) {
      if (grant.campusId != null && !seen.has(grant.campusId)) {
        seen.add(grant.campusId);
        grantCampuses.push({
          id: grant.campusId,
          name: grant.campusName || grant.campusCode || `Campus #${grant.campusId}`,
        });
      }
    }
    if (grantCampuses.length) {
      setKnownCampuses(prev => {
        const merged = new Map(prev.map(item => [String(item.id), item]));
        let changed = false;
        grantCampuses.forEach(item => {
          if (!merged.has(String(item.id))) {
            merged.set(String(item.id), item);
            changed = true;
          }
        });
        return changed ? [...merged.values()] : prev;
      });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [grantsKey]);

  useEffect(() => {
    let live = true;
    setRisksState(prev => ({ ...prev, loading: true, error: '' }));
    listGuidanceRisks({ campusId, termId, asOf, page: 0, size: 100 })
      .then(data => {
        if (!live) return;
        setRisksState({ data, loading: false, error: '' });
        const items = data?.items || [];
        if (items.length) {
          setKnownCampuses(prev => {
            const merged = new Map(prev.map(item => [String(item.id), item]));
            let changed = false;
            items.forEach(row => {
              if (row.campusId != null) {
                const key = String(row.campusId);
                const name = row.campus || `Campus #${row.campusId}`;
                if (!merged.has(key) || merged.get(key).name !== name) {
                  merged.set(key, { id: row.campusId, name });
                  changed = true;
                }
              }
            });
            return changed ? [...merged.values()] : prev;
          });
          setKnownTerms(prev => {
            const merged = new Map(prev.map(item => [String(item.id), item]));
            let changed = false;
            items.forEach(row => {
              if (row.termId != null) {
                const key = String(row.termId);
                const next = {
                  id: row.termId,
                  name: row.term || `Term #${row.termId}`,
                  academicYear: row.academicYear || '',
                };
                const existing = merged.get(key);
                if (!existing || existing.name !== next.name || existing.academicYear !== next.academicYear) {
                  merged.set(key, next);
                  changed = true;
                }
              }
            });
            return changed ? [...merged.values()] : prev;
          });
        }
      })
      .catch(err => {
        if (live && err?.name !== 'AbortError') {
          setRisksState({
            data: null,
            loading: false,
            error: formatGuidanceError(err, 'At-risk student data could not be loaded.'),
          });
        }
      });
    return () => {
      live = false;
    };
  }, [campusId, termId, asOf, tick]);

  const allItems = risksState.data?.items || [];
  const visibleItems = useMemo(() => {
    if (signalFilter === 'all') return allItems;
    if (signalFilter === 'at_risk_only') return allItems.filter(item => item.atRisk);
    return allItems.filter(item => (item.signals || []).includes(signalFilter));
  }, [allItems, signalFilter]);

  const selectedStudent =
    visibleItems.find(item => String(item.enrollmentId) === String(selectedEnrollmentId)) ||
    allItems.find(item => String(item.enrollmentId) === String(selectedEnrollmentId)) ||
    null;

  const activeCaseCount = allItems.filter(item => item.case && item.case.status !== 'closed').length;

  return (
    <div className="guidance-workspace">
      <header className="page-heading">
        <p className="eyebrow">Guidance office · At-risk monitoring</p>
        <h1>Reports</h1>
        <p>
          Identify students with incomplete grades, failing grades, overdue credentials, or consecutive unexcused absences and open confidential counseling cases.
        </p>
      </header>

      <div className="guidance-confidential-banner" role="note">
        <strong>Confidential Guidance Workspace:</strong> Risk evaluations and counseling records are visible only to authorized Guidance Staff. Manage active cases in{' '}
        <Link to="/reporting/guidance">Guidance Records</Link>.
      </div>

      <div className="guidance-stats">
        <div>
          <span>Evaluated students</span>
          <strong>{risksState.data?.total ?? 0}</strong>
        </div>
        <div>
          <span>Flagged at risk</span>
          <strong>{risksState.data?.atRiskTotal ?? 0}</strong>
        </div>
        <div>
          <span>Active counseling cases</span>
          <strong>{activeCaseCount}</strong>
        </div>
        <div>
          <span>Evaluated as of</span>
          <strong>{risksState.data?.asOf || 'Today'}</strong>
        </div>
      </div>

      <section className="panel" aria-labelledby="risk-criteria-heading">
        <h2 id="risk-criteria-heading">At-risk student list</h2>
        <div className="panel-body">
          <div className="guidance-filters">
            <div>
              <label htmlFor="risk-campus-filter">Campus</label>
              <select
                id="risk-campus-filter"
                value={campusId}
                onChange={event => setCampusId(event.target.value)}
              >
                <option value="">All authorized campuses</option>
                {knownCampuses.map(campus => (
                  <option key={campus.id} value={campus.id}>
                    {campus.name}
                  </option>
                ))}
              </select>
            </div>

            <div>
              <label htmlFor="risk-term-filter">Term</label>
              <select
                id="risk-term-filter"
                value={termId}
                onChange={event => setTermId(event.target.value)}
              >
                <option value="">All terms</option>
                {knownTerms.map(term => (
                  <option key={term.id} value={term.id}>
                    {term.academicYear ? `${term.academicYear} / ` : ''}
                    {term.name}
                  </option>
                ))}
              </select>
            </div>

            <div>
              <label htmlFor="risk-signal-filter">Signal type</label>
              <select
                id="risk-signal-filter"
                value={signalFilter}
                onChange={event => setSignalFilter(event.target.value)}
              >
                <option value="all">All students</option>
                <option value="at_risk_only">Flagged at risk only</option>
                {Object.entries(SIGNAL_LABELS).map(([code, label]) => (
                  <option key={code} value={code}>
                    {label}
                  </option>
                ))}
              </select>
            </div>

            <div>
              <label htmlFor="risk-as-of">Evaluation date</label>
              <input
                id="risk-as-of"
                type="date"
                value={asOf}
                onChange={event => setAsOf(event.target.value)}
              />
            </div>

            <button
              type="button"
              className="button button-outline"
              disabled={risksState.loading}
              onClick={() => setTick(value => value + 1)}
            >
              Refresh risks
            </button>
          </div>

          <Failure error={risksState.error} reload={() => setTick(value => value + 1)} />
          {risksState.loading && <p role="status">Evaluating student risk signals…</p>}

          {!risksState.loading && !risksState.error && visibleItems.length === 0 && (
            <div className="empty-state">
              <h3>No students match these criteria</h3>
              <p>Try widening the campus, term, or signal type filter above.</p>
            </div>
          )}

          {visibleItems.length > 0 && (
            <div className="setup-table-wrap">
              <table className="setup-table">
                <thead>
                  <tr>
                    <th>Student</th>
                    <th>Campus / term</th>
                    <th>Risk signals</th>
                    <th>Counseling case</th>
                    <th>Action</th>
                  </tr>
                </thead>
                <tbody>
                  {visibleItems.map(row => (
                    <tr
                      key={row.enrollmentId}
                      className={String(row.enrollmentId) === String(selectedEnrollmentId) ? 'selected-row' : undefined}
                    >
                      <td>
                        <strong>{row.name}</strong>
                        <small>
                          {row.studentNumber} · {row.section || 'No section'}
                        </small>
                      </td>
                      <td>
                        {row.campus}
                        <small>
                          {row.academicYear} / {row.term}
                        </small>
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
                        {row.case ? (
                          <>
                            <CaseStatusBadge status={row.case.status} />{' '}
                            <small>Case #{row.case.id}</small>
                          </>
                        ) : (
                          <span className="field-help">None</span>
                        )}
                      </td>
                      <td>
                        <button
                          type="button"
                          className="button button-outline"
                          onClick={() => setSelectedEnrollmentId(String(row.enrollmentId))}
                        >
                          Inspect {row.name}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </section>

      {selectedStudent && (
        <RiskDetailsPanel
          student={selectedStudent}
          onCaseOpened={() => setTick(value => value + 1)}
        />
      )}

      <GuidancePolicyPanel
        campuses={knownCampuses}
        terms={knownTerms}
        initialCampusId={campusId || selectedStudent?.campusId || ''}
        initialTermId={termId || selectedStudent?.termId || ''}
        canConfigure={canConfigure}
        onSaved={() => setTick(value => value + 1)}
      />
    </div>
  );
}

export function GuidanceRecords() {
  const [params, setParams] = useSearchParams();
  const [statusFilter, setStatusFilter] = useState('');
  const [page, setPage] = useState(0);
  const [tick, setTick] = useState(0);
  const [listState, setListState] = useState({ data: null, loading: true, error: '' });

  useEffect(() => {
    let live = true;
    setListState(prev => ({ ...prev, loading: true, error: '' }));
    listGuidanceCases({ status: statusFilter, page, size: 25 })
      .then(data => {
        if (live) setListState({ data, loading: false, error: '' });
      })
      .catch(err => {
        if (live && err?.name !== 'AbortError') {
          setListState({
            data: null,
            loading: false,
            error: formatGuidanceError(err, 'Counseling cases could not be loaded.'),
          });
        }
      });
    return () => {
      live = false;
    };
  }, [statusFilter, page, tick]);

  const items = listState.data?.items || [];
  const selectedCaseId = params.get('caseId') || (items[0]?.id != null ? String(items[0].id) : '');

  return (
    <div className="guidance-workspace">
      <header className="page-heading">
        <p className="eyebrow">Guidance office · Confidential records</p>
        <h1>Guidance Records</h1>
        <p>
          Track confidential counseling cases, log intervention notes, update case status, and export official case summaries.
        </p>
      </header>

      <div className="guidance-confidential-banner" role="note">
        <strong>Confidentiality notice:</strong> Counseling case records and intervention notes are restricted to Guidance Staff. To open a new case for a flagged learner, go to{' '}
        <Link to="/reporting/reports">At-Risk Reports</Link>.
      </div>

      <section className="panel" aria-labelledby="counseling-cases-heading">
        <h2 id="counseling-cases-heading">Counseling cases</h2>
        <div className="panel-body">
          <div className="guidance-filters">
            <div>
              <label htmlFor="cases-status-filter">Case status</label>
              <select
                id="cases-status-filter"
                value={statusFilter}
                onChange={event => {
                  setStatusFilter(event.target.value);
                  setPage(0);
                }}
              >
                <option value="">All statuses</option>
                <option value="open">Open</option>
                <option value="monitoring">Monitoring</option>
                <option value="closed">Closed</option>
              </select>
            </div>
            <button
              type="button"
              className="button button-outline"
              disabled={listState.loading}
              onClick={() => setTick(value => value + 1)}
            >
              Refresh cases
            </button>
            <Link className="button" to="/reporting/reports">
              Open case from At-Risk Reports
            </Link>
          </div>

          <Failure error={listState.error} reload={() => setTick(value => value + 1)} />
          {listState.loading && <p role="status">Loading counseling cases…</p>}

          {!listState.loading && !listState.error && items.length === 0 && (
            <div className="empty-state">
              <h3>No counseling cases found</h3>
              <p>Open a counseling case from a flagged student in At-Risk Reports to begin tracking interventions.</p>
            </div>
          )}

          {items.length > 0 && (
            <div className="setup-table-wrap">
              <table className="setup-table">
                <thead>
                  <tr>
                    <th>Case</th>
                    <th>Student</th>
                    <th>Enrollment</th>
                    <th>Status</th>
                    <th>Action</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map(row => (
                    <tr
                      key={row.id}
                      className={String(row.id) === String(selectedCaseId) ? 'selected-row' : undefined}
                    >
                      <td>
                        <strong>#{row.id}</strong>
                        <small>Version {row.version}</small>
                      </td>
                      <td>
                        <strong>{row.name}</strong>
                        <small>{row.studentNumber}</small>
                      </td>
                      <td>Enrollment #{row.enrollmentId}</td>
                      <td>
                        <CaseStatusBadge status={row.status} />
                      </td>
                      <td>
                        <button
                          type="button"
                          className="button button-outline"
                          onClick={() => setParams({ caseId: String(row.id) })}
                        >
                          Open case #{row.id}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <Pager
            page={page}
            total={listState.data?.total ?? 0}
            onChange={setPage}
            disabled={listState.loading}
          />
        </div>
      </section>

      {selectedCaseId && /^\d+$/.test(selectedCaseId) && (
        <CaseDetailPanel
          key={selectedCaseId}
          caseId={selectedCaseId}
          onUpdated={() => setTick(value => value + 1)}
        />
      )}
    </div>
  );
}
