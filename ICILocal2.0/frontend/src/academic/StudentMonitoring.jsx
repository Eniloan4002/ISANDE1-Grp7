// ICI2-67 Student Monitoring screen for Teacher Advisers (/academic/student-monitoring).
import { useEffect, useMemo, useState } from 'react';
import { Confirm, Failure } from '../EnrollmentWorkspace.jsx';
import { loadAdvisoryCatalog, loadAdvisoryRoster } from './advisoryRoster.js';
import { loadAdviserRisks, referStudentToGuidance } from './studentMonitoringApi.js';
import '../reporting/guidance.css';

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

const optionValue = value => (value == null ? '' : String(value));

function SignalBadge({ code }) {
  const tone = code === 'failing_grades' || code === 'consecutive_unexcused_absences' ? 'danger' : 'warning';
  return <span className={`status status-${tone}`}>{SIGNAL_LABELS[code] || code}</span>;
}

function StudentMonitoringDetail({ student, onReferred }) {
  const [reason, setReason] = useState('');
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  useEffect(() => {
    setReason('');
    setConfirming(false);
    setError('');
    setNotice('');
  }, [student.enrollmentId, student.studentNumber]);

  const signals = student.signals || [];
  const unconfigured = student.unconfiguredSignals || [];
  const alreadyReferred = Boolean(student.referredToGuidance);

  async function submitReferral() {
    if (!student.enrollmentId || !reason.trim()) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await referStudentToGuidance({
        enrollmentId: student.enrollmentId,
        reason: reason.trim(),
      });
      setConfirming(false);
      setReason('');
      setNotice(
        `Referral submitted for ${student.name}. Guidance Staff will handle confidential counseling follow-up.`
      );
      onReferred(student.enrollmentId);
    } catch (err) {
      setConfirming(false);
      setError(err?.message || 'Referral to Guidance could not be submitted.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel" aria-labelledby="monitoring-detail-heading">
      <h2 id="monitoring-detail-heading">Student monitoring details — {student.name}</h2>
      <div className="panel-body">
        <div className="selected-app" style={{ paddingTop: 0 }}>
          <div>
            <p className="eyebrow">
              {student.studentNumber} · {student.section || 'Advisory section'}
            </p>
            <h2>
              {student.name}{' '}
              <span className={`status ${student.atRisk ? 'status-danger' : 'status-success'}`}>
                {student.atRisk ? 'At risk' : 'No active signals'}
              </span>
            </h2>
          </div>
          <div>
            <span className={`status ${alreadyReferred ? 'status-warning' : ''}`}>
              {alreadyReferred ? 'Referred to Guidance' : 'Not referred'}
            </span>
          </div>
        </div>

        <dl className="guidance-meta-grid">
          <div>
            <dt>Enrollment status</dt>
            <dd>{student.enrollmentStatus || student.status || '—'}</dd>
          </div>
          <div>
            <dt>Requirements status</dt>
            <dd>{student.requirementsStatus || (student.missingRequirements > 0 ? `${student.missingRequirements} missing` : 'Complete')}</dd>
          </div>
          <div>
            <dt>Program / grade</dt>
            <dd>
              {student.program || '—'}
              {student.gradeLevel ? ` · ${student.gradeLevel}` : ''}
            </dd>
          </div>
        </dl>

        <h3>Risk signals in your advisory section</h3>
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
                ? `${student.incompleteGrades} submitted or finalized grade(s) carry an INC mark.`
                : 'No incomplete grades flagged.'}
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
                ? 'Failing grade threshold is not configured for this term.'
                : student.failingGrades > 0
                  ? `${student.failingGrades} subject grade(s) fall below the campus failing threshold.`
                  : 'No failing grades flagged.'}
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
              <span>Missing credentials</span>
              <span className={`status ${student.missingRequirements > 0 ? 'status-warning' : 'status-success'}`}>
                {student.missingRequirements ?? 0} missing
              </span>
            </h4>
            <p>
              {student.missingRequirements > 0
                ? `Missing mandatory document(s): ${student.missingDocuments || 'Check enrollment requirements'}.`
                : 'Mandatory credentials are complete.'}
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
                ? 'Consecutive absence threshold is not configured for this term.'
                : `${student.consecutiveUnexcusedAbsences ?? 0} consecutive unexcused absence(s) recorded on school days.`}
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

        {alreadyReferred ? (
          <div className="guidance-confidential-banner" role="status">
            <strong>Referred to Guidance Office:</strong> This student already has a Guidance referral for this term. Confidential counseling notes and intervention details are restricted to Guidance Staff only.
          </div>
        ) : student.enrollmentId ? (
          <form
            onSubmit={event => {
              event.preventDefault();
              if (reason.trim()) setConfirming(true);
            }}
          >
            <h3>Refer student to Guidance</h3>
            <p className="field-help" style={{ marginTop: 0 }}>
              Submitting a referral opens a confidential case for Guidance Staff. Advisers cannot view confidential counseling notes after referral.
            </p>
            <label htmlFor="referral-reason">Referral reason</label>
            <textarea
              id="referral-reason"
              className="guidance-textarea"
              required
              maxLength={2000}
              disabled={busy}
              value={reason}
              onChange={event => setReason(event.target.value)}
              placeholder="Summarize the academic, attendance, or credential concern for Guidance follow-up…"
            />
            <button type="submit" className="button" disabled={busy || !reason.trim()}>
              Refer to Guidance
            </button>
          </form>
        ) : (
          <p className="field-help">
            Risk evaluation is not available for this roster entry yet. Refresh once enrollment records synchronize.
          </p>
        )}

        {confirming && (
          <Confirm
            title={`Refer ${student.name} to Guidance?`}
            busy={busy}
            onCancel={() => setConfirming(false)}
            onConfirm={submitReferral}
          >
            <p>
              This will send a referral for <strong>{student.name}</strong> ({student.studentNumber}) to the Guidance Office. Confidential counseling notes remain restricted to Guidance Staff.
            </p>
            <p>{reason}</p>
          </Confirm>
        )}
      </div>
    </section>
  );
}

export default function StudentMonitoring() {
  const [catalog, setCatalog] = useState({ years: [], terms: [] });
  const [filters, setFilters] = useState({ academicYearId: '', termId: '' });
  const [sections, setSections] = useState([]);
  const [sectionId, setSectionId] = useState('');
  const [signalFilter, setSignalFilter] = useState('all');
  const [search, setSearch] = useState('');
  const [selectedKey, setSelectedKey] = useState('');

  const [loadingRoster, setLoadingRoster] = useState(true);
  const [loadingRisks, setLoadingRisks] = useState(false);
  const [rosterError, setRosterError] = useState('');
  const [risksError, setRisksError] = useState('');
  const [riskData, setRiskData] = useState(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let active = true;
    loadAdvisoryCatalog()
      .then(result => {
        if (!active) return;
        const years = result?.years || [];
        const terms = result?.terms || [];
        setCatalog({ years, terms });
        const year = years[0];
        const term = terms.find(
          item => optionValue(item.academic_year_id ?? item.academicYearId) === optionValue(year?.id)
        );
        setFilters({
          academicYearId: optionValue(year?.id),
          termId: optionValue(term?.id),
        });
        if (!year || !term) setLoadingRoster(false);
      })
      .catch(err => {
        if (!active) return;
        setRosterError(err?.message || 'Academic terms could not be loaded.');
        setLoadingRoster(false);
      });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    if (!filters.academicYearId || !filters.termId) return undefined;
    const controller = new AbortController();
    setLoadingRoster(true);
    setRosterError('');
    loadAdvisoryRoster({ ...filters, signal: controller.signal })
      .then(result => {
        const list = result?.sections || [];
        setSections(list);
        setSectionId(current =>
          list.some(item => optionValue(item.id) === current) ? current : optionValue(list[0]?.id)
        );
      })
      .catch(err => {
        if (err.name === 'AbortError' || err.name === 'CanceledError') return;
        setSections([]);
        setSectionId('');
        setRosterError(err?.message || 'Advisory sections could not be loaded.');
      })
      .finally(() => setLoadingRoster(false));
    return () => controller.abort();
  }, [filters.academicYearId, filters.termId, tick]);

  useEffect(() => {
    if (!filters.academicYearId || !filters.termId || !sectionId) {
      setRiskData(null);
      return;
    }
    let active = true;
    setLoadingRisks(true);
    setRisksError('');
    loadAdviserRisks({
      academicYearId: filters.academicYearId,
      termId: filters.termId,
      sectionId,
      page: 0,
      size: 100,
    })
      .then(result => {
        if (active) setRiskData(result);
      })
      .catch(err => {
        if (active && err?.name !== 'AbortError') {
          setRiskData(null);
          setRisksError(err?.message || 'Risk signals for your advisory section could not be loaded.');
        }
      })
      .finally(() => {
        if (active) setLoadingRisks(false);
      });
    return () => {
      active = false;
    };
  }, [filters.academicYearId, filters.termId, sectionId, tick]);

  const termsForYear = useMemo(
    () =>
      catalog.terms.filter(
        term => optionValue(term.academic_year_id ?? term.academicYearId) === filters.academicYearId
      ),
    [catalog.terms, filters.academicYearId]
  );

  const activeSection = sections.find(item => optionValue(item.id) === sectionId) || null;

  // Merge advisory roster students with section-scoped risk evaluations
  const mergedStudents = useMemo(() => {
    const riskItems = riskData?.items || [];
    const byNumber = new Map(riskItems.map(item => [item.studentNumber, item]));
    const rosterStudents = activeSection?.students || [];
    const seenNumbers = new Set();
    const combined = rosterStudents.map(student => {
      seenNumbers.add(student.studentNumber);
      const risk = byNumber.get(student.studentNumber);
      return {
        key: risk?.enrollmentId ? String(risk.enrollmentId) : student.studentNumber,
        enrollmentId: risk?.enrollmentId ?? null,
        studentNumber: student.studentNumber,
        name: risk?.name || student.name,
        section: risk?.section || activeSection?.name || '',
        program: risk?.program || activeSection?.program || '',
        gradeLevel: risk?.gradeLevel || activeSection?.gradeLevel || '',
        enrollmentStatus: student.enrollmentStatus || risk?.status || '',
        requirementsStatus: student.requirementsStatus || '',
        signals: risk?.signals || [],
        unconfiguredSignals: risk?.unconfiguredSignals || [],
        incompleteGrades: risk?.incompleteGrades ?? 0,
        failingGrades: risk?.failingGrades ?? 0,
        missingRequirements: risk?.missingRequirements ?? 0,
        missingDocuments: risk?.missingDocuments || '',
        consecutiveUnexcusedAbsences: risk?.consecutiveUnexcusedAbsences ?? 0,
        referredToGuidance: Boolean(risk?.referredToGuidance),
        atRisk: Boolean(risk?.atRisk),
      };
    });
    for (const risk of riskItems) {
      if (!seenNumbers.has(risk.studentNumber)) {
        combined.push({
          key: String(risk.enrollmentId),
          enrollmentId: risk.enrollmentId,
          studentNumber: risk.studentNumber,
          name: risk.name,
          section: risk.section || activeSection?.name || '',
          program: risk.program || '',
          gradeLevel: risk.gradeLevel || '',
          enrollmentStatus: risk.status || '',
          requirementsStatus: risk.missingRequirements > 0 ? 'Incomplete' : 'Complete',
          signals: risk.signals || [],
          unconfiguredSignals: risk.unconfiguredSignals || [],
          incompleteGrades: risk.incompleteGrades ?? 0,
          failingGrades: risk.failingGrades ?? 0,
          missingRequirements: risk.missingRequirements ?? 0,
          missingDocuments: risk.missingDocuments || '',
          consecutiveUnexcusedAbsences: risk.consecutiveUnexcusedAbsences ?? 0,
          referredToGuidance: Boolean(risk.referredToGuidance),
          atRisk: Boolean(risk.atRisk),
        });
      }
    }
    return combined;
  }, [activeSection, riskData]);

  const visibleStudents = useMemo(() => {
    const query = search.trim().toLowerCase();
    return mergedStudents.filter(student => {
      if (query && !`${student.name} ${student.studentNumber}`.toLowerCase().includes(query)) {
        return false;
      }
      if (signalFilter === 'all') return true;
      if (signalFilter === 'at_risk_only') return student.atRisk;
      return student.signals.includes(signalFilter);
    });
  }, [mergedStudents, search, signalFilter]);

  const selectedStudent =
    mergedStudents.find(item => item.key === selectedKey) || null;

  const atRiskCount = mergedStudents.filter(item => item.atRisk).length;
  const referredCount = mergedStudents.filter(item => item.referredToGuidance).length;

  function handleYearChange(nextYearId) {
    const firstTerm = catalog.terms.find(
      item => optionValue(item.academic_year_id ?? item.academicYearId) === nextYearId
    );
    setFilters({ academicYearId: nextYearId, termId: optionValue(firstTerm?.id) });
    setSelectedKey('');
  }

  return (
    <div className="guidance-workspace">
      <header className="page-heading">
        <p className="eyebrow">Academic records / Teacher adviser</p>
        <h1>Student Monitoring</h1>
        <p>
          Monitor INC grades, failing grades, missing credentials, and absences for your advisory section, and refer students to Guidance.
        </p>
      </header>

      <div className="guidance-confidential-banner" role="note">
        <strong>Advisory scope &amp; privacy:</strong> Risk indicators shown here are limited strictly to your assigned advisory sections. You can refer a student to Guidance, while confidential counseling notes remain restricted to Guidance Staff.
      </div>

      <div className="guidance-stats">
        <div>
          <span>Section students</span>
          <strong>{mergedStudents.length}</strong>
        </div>
        <div>
          <span>Flagged at risk</span>
          <strong>{atRiskCount}</strong>
        </div>
        <div>
          <span>Referred to Guidance</span>
          <strong>{referredCount}</strong>
        </div>
        <div>
          <span>Evaluated as of</span>
          <strong>{riskData?.asOf || 'Today'}</strong>
        </div>
      </div>

      <section className="panel" aria-labelledby="monitoring-filters-heading">
        <h2 id="monitoring-filters-heading">Advisory section risk monitor</h2>
        <div className="panel-body">
          <div className="guidance-filters">
            <div>
              <label htmlFor="mon-year">Academic year</label>
              <select
                id="mon-year"
                disabled={loadingRoster || catalog.years.length === 0}
                value={filters.academicYearId}
                onChange={event => handleYearChange(event.target.value)}
              >
                {catalog.years.length === 0 && <option value="">No academic years</option>}
                {catalog.years.map(year => (
                  <option key={year.id} value={year.id}>
                    {year.label}
                  </option>
                ))}
              </select>
            </div>

            <div>
              <label htmlFor="mon-term">Term</label>
              <select
                id="mon-term"
                disabled={loadingRoster || termsForYear.length === 0}
                value={filters.termId}
                onChange={event => {
                  setFilters(prev => ({ ...prev, termId: event.target.value }));
                  setSelectedKey('');
                }}
              >
                {termsForYear.length === 0 && <option value="">No terms</option>}
                {termsForYear.map(term => (
                  <option key={term.id} value={term.id}>
                    {term.name}
                  </option>
                ))}
              </select>
            </div>

            <div>
              <label htmlFor="mon-section">Advisory section</label>
              <select
                id="mon-section"
                disabled={loadingRoster || sections.length === 0}
                value={sectionId}
                onChange={event => {
                  setSectionId(event.target.value);
                  setSelectedKey('');
                }}
              >
                {sections.length === 0 && <option value="">No assigned sections</option>}
                {sections.map(sec => (
                  <option key={sec.id} value={sec.id}>
                    {sec.name} ({sec.gradeLevel || sec.program || 'Section'})
                  </option>
                ))}
              </select>
            </div>

            <div>
              <label htmlFor="mon-signal">Signal filter</label>
              <select
                id="mon-signal"
                value={signalFilter}
                onChange={event => setSignalFilter(event.target.value)}
              >
                <option value="all">All section students</option>
                <option value="at_risk_only">Flagged at risk only</option>
                {Object.entries(SIGNAL_LABELS).map(([code, label]) => (
                  <option key={code} value={code}>
                    {label}
                  </option>
                ))}
              </select>
            </div>

            <div>
              <label htmlFor="mon-search">Search student</label>
              <input
                id="mon-search"
                type="search"
                value={search}
                onChange={event => setSearch(event.target.value)}
                placeholder="Name or student number…"
              />
            </div>

            <button
              type="button"
              className="button button-outline"
              disabled={loadingRoster || loadingRisks}
              onClick={() => setTick(value => value + 1)}
            >
              Refresh
            </button>
          </div>

          <Failure error={rosterError} reload={() => setTick(value => value + 1)} />
          <Failure error={risksError} reload={() => setTick(value => value + 1)} />
          {(loadingRoster || loadingRisks) && <p role="status">Loading advisory monitoring data…</p>}

          {!loadingRoster && !rosterError && sections.length === 0 && (
            <div className="empty-state">
              <h3>No advisory sections assigned</h3>
              <p>You do not have an assigned advisory section for the selected academic year and term.</p>
            </div>
          )}

          {!loadingRoster && !loadingRisks && sections.length > 0 && visibleStudents.length === 0 && (
            <div className="empty-state">
              <h3>No students match this filter</h3>
              <p>Try clearing the search box or choosing “All section students”.</p>
            </div>
          )}

          {visibleStudents.length > 0 && (
            <div className="setup-table-wrap">
              <table className="setup-table">
                <thead>
                  <tr>
                    <th>Student</th>
                    <th>Risk signals</th>
                    <th>Indicators</th>
                    <th>Guidance referral</th>
                    <th>Action</th>
                  </tr>
                </thead>
                <tbody>
                  {visibleStudents.map(student => (
                    <tr
                      key={student.key}
                      className={student.key === selectedKey ? 'selected-row' : undefined}
                    >
                      <td>
                        <strong>{student.name}</strong>
                        <small>{student.studentNumber}</small>
                      </td>
                      <td>
                        {student.signals.length ? (
                          <div className="guidance-signals">
                            {student.signals.map(code => (
                              <SignalBadge key={code} code={code} />
                            ))}
                          </div>
                        ) : (
                          <span className="status status-success">No active signals</span>
                        )}
                      </td>
                      <td>
                        INC: <strong>{student.incompleteGrades}</strong> · Failing:{' '}
                        <strong>{student.failingGrades}</strong> · Missing:{' '}
                        <strong>{student.missingRequirements}</strong> · Absences:{' '}
                        <strong>{student.consecutiveUnexcusedAbsences}</strong>
                      </td>
                      <td>
                        {student.referredToGuidance ? (
                          <span className="status status-warning">Referred</span>
                        ) : (
                          <span className="field-help">Not referred</span>
                        )}
                      </td>
                      <td>
                        <button
                          type="button"
                          className="button button-outline"
                          onClick={() => setSelectedKey(student.key)}
                        >
                          {student.referredToGuidance
                            ? `View ${student.name}`
                            : `Monitor ${student.name}`}
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

      {selectedStudent ? (
        <StudentMonitoringDetail
          student={selectedStudent}
          onReferred={() => setTick(value => value + 1)}
        />
      ) : (
        <p className="notice">
          Select a student from your advisory section above to review their risk indicators or submit a referral to Guidance.
        </p>
      )}
    </div>
  );
}
