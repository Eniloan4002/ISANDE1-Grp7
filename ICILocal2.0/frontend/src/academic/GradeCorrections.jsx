// ICI2-55 Grade Corrections screen (Subject Teacher request submission & tracking, backed by ICI2-26).
import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { Failure, Pager } from '../EnrollmentWorkspace.jsx';
import { loadGradeCatalog, loadGradingPeriods, loadTeacherClasses } from './gradeEntryApi.js';
import {
  downloadCorrectionEvidence,
  getGradeCorrectionDetail,
  listMyGradeCorrections,
  loadCorrectionGradebook,
  submitGradeCorrection,
  validateEvidenceFile,
  validateProposedGrade,
} from './teacherGradeCorrectionsApi.js';
import './grade-corrections.css';

const STATUS_META = {
  pending: { label: 'Pending', tone: 'warning' },
  applied: { label: 'Approved', tone: 'success' },
  approved: { label: 'Approved', tone: 'success' },
  rejected: { label: 'Rejected', tone: 'danger' },
};

const optionValue = value => (value == null ? '' : String(value));
const formatDate = value => (value ? new Date(value).toLocaleString() : '—');
const formatGrade = (value, mark) => {
  if (value !== null && value !== undefined && value !== '') {
    const num = Number(value);
    return Number.isNaN(num) ? String(value) : num.toFixed(2);
  }
  return mark || 'No grade';
};
const formatBytes = bytes => {
  if (bytes == null) return '';
  if (bytes < 1024) return `${bytes} B`;
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
};

function StatusBadge({ status }) {
  const meta = STATUS_META[String(status || '').toLowerCase()] || { label: status || 'Unknown', tone: 'neutral' };
  return <span className={`status status-${meta.tone}`}>{meta.label}</span>;
}

function RequestDetailPanel({ requestId, onReloadList }) {
  const [detail, setDetail] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [downloadBusy, setDownloadBusy] = useState(false);
  const [downloadError, setDownloadError] = useState('');
  const [tick, setTick] = useState(0);

  useEffect(() => {
    if (!requestId) {
      setDetail(null);
      setError('');
      setLoading(false);
      return;
    }
    let live = true;
    setLoading(true);
    setError('');
    setDownloadError('');
    getGradeCorrectionDetail(requestId)
      .then(data => {
        if (live) setDetail(data);
      })
      .catch(err => {
        if (live && err?.name !== 'AbortError') {
          setDetail(null);
          setError(err?.message || 'Correction details could not be loaded.');
        }
      })
      .finally(() => {
        if (live) setLoading(false);
      });
    return () => {
      live = false;
    };
  }, [requestId, tick]);

  async function handleDownload() {
    if (!detail) return;
    setDownloadBusy(true);
    setDownloadError('');
    try {
      await downloadCorrectionEvidence(detail.id);
    } catch (err) {
      setDownloadError(err?.message || 'The supporting evidence could not be downloaded.');
    } finally {
      setDownloadBusy(false);
    }
  }

  const grade = detail?.grade || {};
  const status = String(detail?.status || '').toLowerCase();
  const isApproved = status === 'applied' || status === 'approved';
  const isRejected = status === 'rejected';

  return (
    <section className="panel" aria-labelledby="request-detail-heading">
      <h2 id="request-detail-heading">
        {detail ? `Request #${detail.id}` : 'Request details & history'}
      </h2>
      <div className="panel-body">
        {!requestId && (
          <div className="empty-state">
            <h3>No request selected</h3>
            <p>Submit a correction request or select one from My requests below to inspect its status, reviewer remarks, and uploaded evidence.</p>
          </div>
        )}
        <Failure error={error} reload={() => setTick(value => value + 1)} />
        {loading && !detail && <p role="status">Loading request details…</p>}
        {detail && (
          <>
            <div className="component-row" style={{ alignItems: 'center', justifyContent: 'space-between' }}>
              <StatusBadge status={detail.status} />
              <div className="table-actions">
                <button
                  type="button"
                  className="button button-outline"
                  disabled={downloadBusy}
                  onClick={handleDownload}
                >
                  {downloadBusy ? 'Downloading…' : 'Download evidence'}
                </button>
                <button
                  type="button"
                  className="button button-outline"
                  disabled={loading}
                  onClick={() => {
                    setTick(value => value + 1);
                    onReloadList?.();
                  }}
                >
                  Reload
                </button>
              </div>
            </div>

            <Failure error={downloadError} />

            <ul className="corrections-timeline" aria-label="Request progress">
              <li className="is-done">
                <strong>Submitted by subject teacher</strong>
                <small>
                  {detail.requestedByName || 'Subject Teacher'} · {formatDate(detail.requestedAt)}
                </small>
              </li>
              <li className={isApproved ? 'is-done' : isRejected ? 'is-rejected' : 'is-current'}>
                <strong>
                  {isApproved
                    ? 'Approved & applied by Registrar'
                    : isRejected
                      ? 'Rejected by Registrar'
                      : 'Awaiting Registrar review'}
                </strong>
                <small>
                  {detail.reviewedAt
                    ? `Decided ${formatDate(detail.reviewedAt)}`
                    : 'Your submitted evidence is queued for Registrar verification.'}
                </small>
              </li>
            </ul>

            <div className="setup-table-wrap">
              <table className="setup-table">
                <tbody>
                  <tr>
                    <th>Student</th>
                    <td>
                      <strong>{grade.studentName || '—'}</strong>
                      {grade.studentNumber ? ` (${grade.studentNumber})` : ''}
                    </td>
                  </tr>
                  <tr>
                    <th>Subject / section</th>
                    <td>
                      {grade.subject || '—'}
                      {grade.section ? ` · ${grade.section}` : ''}
                    </td>
                  </tr>
                  <tr>
                    <th>Term / period</th>
                    <td>
                      {grade.academicYear || '—'}
                      {grade.gradingPeriod ? ` · ${grade.gradingPeriod}` : ''}
                    </td>
                  </tr>
                  <tr>
                    <th>Grade change</th>
                    <td>
                      <strong>{formatGrade(detail.originalValue, detail.originalMark)}</strong> →{' '}
                      <strong>{formatGrade(detail.proposedValue, null)}</strong>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div className="corrections-remarks-box">
              <strong>Your reason</strong>
              <p>{detail.justification || '—'}</p>
            </div>

            <div className="corrections-remarks-box">
              <strong>Reviewer remarks</strong>
              <p>
                {detail.reviewReason ||
                  (status === 'pending'
                    ? 'Awaiting Registrar review.'
                    : 'No reviewer remarks recorded.')}
              </p>
            </div>
          </>
        )}
      </div>
    </section>
  );
}

export default function GradeCorrections() {
  const [params, setParams] = useSearchParams();

  // Academic context + eligible grade picker state
  const [catalog, setCatalog] = useState({ years: [], terms: [] });
  const [filters, setFilters] = useState({ academicYearId: '', termId: '' });
  const [classes, setClasses] = useState([]);
  const [selectedClassId, setSelectedClassId] = useState('');
  const [periods, setPeriods] = useState([]);
  const [selectedPeriodId, setSelectedPeriodId] = useState('');
  const [gradebook, setGradebook] = useState(null);
  const [loadingContext, setLoadingContext] = useState(true);
  const [loadingGrades, setLoadingGrades] = useState(false);
  const [contextError, setContextError] = useState('');

  // Submission form state
  const [selectedGradeId, setSelectedGradeId] = useState('');
  const [proposedGrade, setProposedGrade] = useState('');
  const [justification, setJustification] = useState('');
  const [evidenceFile, setEvidenceFile] = useState(null);
  const [fieldErrors, setFieldErrors] = useState({ grade: '', proposed: '', justification: '', evidence: '' });
  const [submitBusy, setSubmitBusy] = useState(false);
  const [submitError, setSubmitError] = useState('');
  const [submitNotice, setSubmitNotice] = useState('');
  const fileInputRef = useRef(null);

  // My requests list state
  const [statusFilter, setStatusFilter] = useState('all');
  const [page, setPage] = useState(0);
  const [requestsState, setRequestsState] = useState({ data: null, loading: true, error: '' });
  const [listTick, setListTick] = useState(0);

  // Load academic years & terms
  useEffect(() => {
    let active = true;
    loadGradeCatalog()
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
        if (!year || !term) setLoadingContext(false);
      })
      .catch(err => {
        if (!active) return;
        setContextError(err?.message || 'Academic terms could not be loaded.');
        setLoadingContext(false);
      });
    return () => {
      active = false;
    };
  }, []);

  // Load teacher's classes for selected year & term
  useEffect(() => {
    if (!filters.academicYearId || !filters.termId) return;
    let active = true;
    setLoadingContext(true);
    setContextError('');
    loadTeacherClasses(filters)
      .then(result => {
        if (!active) return;
        const items = result?.items || [];
        setClasses(items);
        setSelectedClassId(current =>
          items.some(item => optionValue(item.id) === current) ? current : optionValue(items[0]?.id)
        );
        if (!items.length) {
          setPeriods([]);
          setSelectedPeriodId('');
          setGradebook(null);
        }
      })
      .catch(err => {
        if (!active) return;
        setContextError(err?.message || 'Assigned classes could not be loaded.');
        setClasses([]);
        setSelectedClassId('');
      })
      .finally(() => {
        if (active) setLoadingContext(false);
      });
    return () => {
      active = false;
    };
  }, [filters]);

  // Load grading periods for selected class
  useEffect(() => {
    if (!selectedClassId || !filters.academicYearId || !filters.termId) {
      setPeriods([]);
      setSelectedPeriodId('');
      return;
    }
    let active = true;
    setContextError('');
    loadGradingPeriods(selectedClassId, filters)
      .then(result => {
        if (!active) return;
        const list = result || [];
        setPeriods(list);
        const preferredLocked = list.find(period => period.status === 'locked');
        setSelectedPeriodId(current =>
          list.some(period => optionValue(period.id) === current)
            ? current
            : optionValue((preferredLocked || list[0])?.id)
        );
      })
      .catch(err => {
        if (!active) return;
        setContextError(err?.message || 'Grading periods could not be loaded.');
        setPeriods([]);
        setSelectedPeriodId('');
      });
    return () => {
      active = false;
    };
  }, [selectedClassId, filters]);

  // Load gradebook rows for selected class & period
  useEffect(() => {
    if (!selectedClassId || !selectedPeriodId || !filters.academicYearId || !filters.termId) {
      setGradebook(null);
      setSelectedGradeId('');
      return;
    }
    let active = true;
    setLoadingGrades(true);
    setContextError('');
    loadCorrectionGradebook(selectedClassId, selectedPeriodId, filters)
      .then(book => {
        if (!active) return;
        setGradebook(book);
        const firstEligible = (book.students || []).find(item => item.eligible);
        setSelectedGradeId(firstEligible ? String(firstEligible.gradeId) : '');
      })
      .catch(err => {
        if (!active) return;
        setContextError(err?.message || 'Grades for this class could not be loaded.');
        setGradebook(null);
        setSelectedGradeId('');
      })
      .finally(() => {
        if (active) setLoadingGrades(false);
      });
    return () => {
      active = false;
    };
  }, [selectedClassId, selectedPeriodId, filters]);

  // Load "My requests" list
  useEffect(() => {
    let active = true;
    setRequestsState(prev => ({ ...prev, loading: true, error: '' }));
    listMyGradeCorrections({ status: statusFilter, page, size: 25 })
      .then(data => {
        if (active) setRequestsState({ data, loading: false, error: '' });
      })
      .catch(err => {
        if (active && err?.name !== 'AbortError') {
          setRequestsState({
            data: null,
            loading: false,
            error: err?.message || 'Your correction requests could not be loaded.',
          });
        }
      });
    return () => {
      active = false;
    };
  }, [statusFilter, page, listTick]);

  const termsForYear = useMemo(
    () =>
      catalog.terms.filter(
        item => optionValue(item.academic_year_id ?? item.academicYearId) === filters.academicYearId
      ),
    [catalog.terms, filters.academicYearId]
  );

  const eligibleGrades = useMemo(
    () => (gradebook?.students || []).filter(item => item.eligible),
    [gradebook]
  );

  const selectedGrade = useMemo(
    () => eligibleGrades.find(item => String(item.gradeId) === String(selectedGradeId)) || null,
    [eligibleGrades, selectedGradeId]
  );

  const requests = requestsState.data?.items || [];
  const selectedRequestId =
    params.get('requestId') || (requests[0]?.id != null ? String(requests[0].id) : '');

  function handleYearChange(nextYearId) {
    const firstTerm = catalog.terms.find(
      item => optionValue(item.academic_year_id ?? item.academicYearId) === nextYearId
    );
    setFilters({ academicYearId: nextYearId, termId: optionValue(firstTerm?.id) });
  }

  function handleFileChange(event) {
    const file = event.target.files?.[0] || null;
    setEvidenceFile(file);
    setSubmitError('');
    setFieldErrors(prev => ({
      ...prev,
      evidence: file ? validateEvidenceFile(file) : '',
    }));
  }

  function handleClear() {
    setProposedGrade('');
    setJustification('');
    setEvidenceFile(null);
    if (fileInputRef.current) fileInputRef.current.value = '';
    setFieldErrors({ grade: '', proposed: '', justification: '', evidence: '' });
    setSubmitError('');
    setSubmitNotice('');
  }

  async function handleSubmit(event) {
    event.preventDefault();
    setSubmitError('');
    setSubmitNotice('');

    const nextErrors = {
      grade: selectedGrade ? '' : 'Select a finalized or locked grade to correct.',
      proposed: validateProposedGrade(proposedGrade),
      justification: justification.trim()
        ? justification.trim().length > 2000
          ? 'Reason must be at most 2,000 characters.'
          : ''
        : 'Enter a clear reason explaining why the finalized grade must change.',
      evidence: validateEvidenceFile(evidenceFile),
    };
    setFieldErrors(nextErrors);

    if (Object.values(nextErrors).some(Boolean)) {
      return;
    }

    setSubmitBusy(true);
    try {
      const created = await submitGradeCorrection({
        gradeId: selectedGrade.gradeId,
        gradeVersion: selectedGrade.version,
        proposedValue: proposedGrade.trim(),
        justification: justification.trim(),
        evidence: evidenceFile,
      });
      setProposedGrade('');
      setJustification('');
      setEvidenceFile(null);
      if (fileInputRef.current) fileInputRef.current.value = '';
      setFieldErrors({ grade: '', proposed: '', justification: '', evidence: '' });
      setSubmitNotice(
        `Correction request #${created.id} submitted for ${selectedGrade.name}. It is now awaiting Registrar review.`
      );
      setParams({ requestId: String(created.id) });
      setListTick(value => value + 1);
    } catch (err) {
      setSubmitError(err?.message || 'The correction request could not be submitted.');
    } finally {
      setSubmitBusy(false);
    }
  }

  return (
    <div className="corrections-workspace">
      <header className="page-heading">
        <p className="eyebrow">Academic records / Subject teacher</p>
        <h1>Grade Corrections</h1>
        <p>
          Submit a traceable grade correction request with supporting evidence for a finalized or locked grade, and track Registrar decisions.
        </p>
      </header>

      <div className="corrections-policy" role="note">
        <strong>Locked-grade rule:</strong> Finalized or locked grades cannot be edited directly. Submit a proposed numeric grade (0.00–100.00), your justification, and supporting evidence (PDF, PNG, or JPEG, max 1 MB). For open periods, edit grades directly in{' '}
        <Link to="/academic/grade-entry">Grade Entry</Link>.
      </div>

      <div className="corrections-grid">
        <section className="panel" aria-labelledby="new-correction-heading">
          <h2 id="new-correction-heading">New correction request</h2>
          <form className="panel-body" onSubmit={handleSubmit} noValidate>
            <Failure error={contextError} />
            {submitError && (
              <div className="notice notice-error" role="alert" style={{ marginBottom: 16 }}>
                {submitError}
              </div>
            )}
            {submitNotice && (
              <p className="notice" role="status">
                {submitNotice}
              </p>
            )}

            <div className="corrections-context-grid">
              <div>
                <label htmlFor="corr-year">Academic year</label>
                <select
                  id="corr-year"
                  disabled={loadingContext || submitBusy || catalog.years.length === 0}
                  value={filters.academicYearId}
                  onChange={event => handleYearChange(event.target.value)}
                >
                  <option value="">Select academic year…</option>
                  {catalog.years.map(year => (
                    <option key={year.id} value={year.id}>
                      {year.label}
                    </option>
                  ))}
                </select>
              </div>

              <div>
                <label htmlFor="corr-term">Academic term</label>
                <select
                  id="corr-term"
                  disabled={loadingContext || submitBusy || termsForYear.length === 0}
                  value={filters.termId}
                  onChange={event => setFilters(prev => ({ ...prev, termId: event.target.value }))}
                >
                  <option value="">Select term…</option>
                  {termsForYear.map(term => (
                    <option key={term.id} value={term.id}>
                      {term.name}
                    </option>
                  ))}
                </select>
              </div>

              <div>
                <label htmlFor="corr-class">Assigned class</label>
                <select
                  id="corr-class"
                  disabled={loadingContext || submitBusy || classes.length === 0}
                  value={selectedClassId}
                  onChange={event => setSelectedClassId(event.target.value)}
                >
                  <option value="">Select class…</option>
                  {classes.map(item => (
                    <option key={item.id} value={item.id}>
                      {item.subjectCode ? `${item.subjectCode} — ` : ''}
                      {item.subject} ({item.section})
                    </option>
                  ))}
                </select>
              </div>

              <div>
                <label htmlFor="corr-period">Grading period</label>
                <select
                  id="corr-period"
                  disabled={loadingContext || submitBusy || periods.length === 0}
                  value={selectedPeriodId}
                  onChange={event => setSelectedPeriodId(event.target.value)}
                >
                  <option value="">Select period…</option>
                  {periods.map(period => (
                    <option key={period.id} value={period.id}>
                      {period.label} ({period.status === 'locked' ? 'Locked' : 'Open'})
                    </option>
                  ))}
                </select>
              </div>
            </div>

            {loadingGrades && <p role="status">Loading class grades…</p>}

            {!loadingGrades && gradebook && eligibleGrades.length === 0 && (
              <p className="notice" role="status">
                {gradebook.periodLocked
                  ? 'No submitted or finalized grades are available for correction in this class and grading period.'
                  : 'This grading period is still open. Edit grades directly in Grade Entry instead of submitting a formal correction.'}
              </p>
            )}

            <label htmlFor="corr-grade">Finalized or locked grade</label>
            <select
              id="corr-grade"
              disabled={submitBusy || loadingGrades || eligibleGrades.length === 0}
              aria-invalid={fieldErrors.grade ? 'true' : 'false'}
              value={selectedGradeId}
              onChange={event => {
                setSelectedGradeId(event.target.value);
                setFieldErrors(prev => ({ ...prev, grade: '' }));
              }}
            >
              <option value="">Select student grade…</option>
              {eligibleGrades.map(item => (
                <option key={item.gradeId} value={item.gradeId}>
                  {item.name} ({item.studentNumber}) — {formatGrade(item.value, item.mark)} [{item.state}]
                </option>
              ))}
            </select>
            {fieldErrors.grade && (
              <p className="field-error" role="alert">
                {fieldErrors.grade}
              </p>
            )}

            {selectedGrade && (
              <div className="corrections-selected-grade">
                <dl>
                  <div>
                    <dt>Student</dt>
                    <dd>
                      {selectedGrade.name} ({selectedGrade.studentNumber})
                    </dd>
                  </div>
                  <div>
                    <dt>Current recorded grade</dt>
                    <dd>{formatGrade(selectedGrade.value, selectedGrade.mark)}</dd>
                  </div>
                  <div>
                    <dt>Grade state</dt>
                    <dd>{selectedGrade.state}</dd>
                  </div>
                </dl>
              </div>
            )}

            <label htmlFor="corr-proposed">Proposed grade (0.00–100.00)</label>
            <input
              id="corr-proposed"
              type="number"
              min="0"
              max="100"
              step="0.01"
              inputMode="decimal"
              disabled={submitBusy}
              aria-invalid={fieldErrors.proposed ? 'true' : 'false'}
              value={proposedGrade}
              onChange={event => {
                const next = event.target.value;
                setProposedGrade(next);
                setFieldErrors(prev => ({
                  ...prev,
                  proposed: next.trim() ? validateProposedGrade(next) : '',
                }));
              }}
              placeholder="e.g. 91.25"
            />
            {fieldErrors.proposed && (
              <p className="field-error" role="alert">
                {fieldErrors.proposed}
              </p>
            )}

            <label htmlFor="corr-reason">Reason for correction</label>
            <textarea
              id="corr-reason"
              className="corrections-textarea"
              maxLength={2000}
              disabled={submitBusy}
              aria-invalid={fieldErrors.justification ? 'true' : 'false'}
              value={justification}
              onChange={event => {
                setJustification(event.target.value);
                if (fieldErrors.justification && event.target.value.trim()) {
                  setFieldErrors(prev => ({ ...prev, justification: '' }));
                }
              }}
              placeholder="Explain the grading error or completed requirement being documented…"
            />
            {fieldErrors.justification && (
              <p className="field-error" role="alert">
                {fieldErrors.justification}
              </p>
            )}

            <label htmlFor="corr-evidence">Supporting evidence (PDF, PNG, or JPEG · max 1 MB)</label>
            <input
              ref={fileInputRef}
              id="corr-evidence"
              type="file"
              accept="application/pdf,image/png,image/jpeg,.pdf,.png,.jpg,.jpeg"
              disabled={submitBusy}
              aria-invalid={fieldErrors.evidence ? 'true' : 'false'}
              onChange={handleFileChange}
            />
            {evidenceFile && !fieldErrors.evidence && (
              <span className="corrections-file-meta">
                Selected: {evidenceFile.name} ({formatBytes(evidenceFile.size)})
              </span>
            )}
            {fieldErrors.evidence && (
              <p className="field-error" role="alert">
                {fieldErrors.evidence}
              </p>
            )}

            <div className="component-row">
              <button type="submit" className="button" disabled={submitBusy}>
                {submitBusy ? 'Submitting…' : 'Submit correction request'}
              </button>
              <button
                type="button"
                className="button button-outline"
                disabled={submitBusy}
                onClick={handleClear}
              >
                Clear form
              </button>
            </div>
          </form>
        </section>

        <RequestDetailPanel
          requestId={selectedRequestId}
          onReloadList={() => setListTick(value => value + 1)}
        />
      </div>

      <section className="panel" aria-labelledby="my-requests-heading">
        <h2 id="my-requests-heading">My requests</h2>
        <div className="panel-body">
          <div className="corrections-filters">
            <div>
              <label htmlFor="my-requests-status">Filter by status</label>
              <select
                id="my-requests-status"
                value={statusFilter}
                onChange={event => {
                  setStatusFilter(event.target.value);
                  setPage(0);
                }}
              >
                <option value="all">All statuses</option>
                <option value="pending">Pending</option>
                <option value="applied">Approved</option>
                <option value="rejected">Rejected</option>
              </select>
            </div>
            <button
              type="button"
              className="button button-outline"
              disabled={requestsState.loading}
              onClick={() => setListTick(value => value + 1)}
            >
              Refresh requests
            </button>
          </div>

          <Failure error={requestsState.error} reload={() => setListTick(value => value + 1)} />
          {requestsState.loading && <p role="status">Loading your requests…</p>}

          {!requestsState.loading && !requestsState.error && requests.length === 0 && (
            <div className="empty-state">
              <h3>No correction requests found</h3>
              <p>Requests you submit for finalized or locked grades will appear here with their Registrar review status and remarks.</p>
            </div>
          )}

          {requests.length > 0 && (
            <div className="setup-table-wrap">
              <table className="setup-table">
                <thead>
                  <tr>
                    <th>Request</th>
                    <th>Student</th>
                    <th>Subject / period</th>
                    <th>Grade change</th>
                    <th>Status</th>
                    <th>Reviewer remarks</th>
                    <th>Action</th>
                  </tr>
                </thead>
                <tbody>
                  {requests.map(item => (
                    <tr
                      key={item.id}
                      className={String(item.id) === String(selectedRequestId) ? 'selected-row' : undefined}
                    >
                      <td>
                        <strong>#{item.id}</strong>
                        <small>{formatDate(item.requestedAt)}</small>
                      </td>
                      <td>
                        <strong>{item.studentName || '—'}</strong>
                        <small>{item.studentNumber || '—'}</small>
                      </td>
                      <td>
                        {item.subject || '—'}
                        <small>
                          {item.section || '—'} · {item.gradingPeriod || '—'}
                        </small>
                      </td>
                      <td>
                        {formatGrade(item.originalValue ?? item.value, item.originalMark ?? item.mark)} →{' '}
                        <strong>{formatGrade(item.proposedValue, null)}</strong>
                      </td>
                      <td>
                        <StatusBadge status={item.status} />
                      </td>
                      <td>
                        {item.reviewReason ? (
                          <span>{item.reviewReason}</span>
                        ) : item.status === 'pending' ? (
                          <span className="field-help">Awaiting review</span>
                        ) : (
                          <span className="field-help">Open details to view</span>
                        )}
                      </td>
                      <td>
                        <button
                          type="button"
                          className="button button-outline"
                          onClick={() => setParams({ requestId: String(item.id) })}
                        >
                          View #{item.id}
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
            total={requestsState.data?.total ?? 0}
            onChange={setPage}
            disabled={requestsState.loading}
          />
        </div>
      </section>
    </div>
  );
}
