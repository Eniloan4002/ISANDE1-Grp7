// ICI2-64 Legacy Import Review screen for ICI2-37 (Student CSV) + ICI2-38 (Grade Matrix).
import { useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';
import { Failure, Pager, useResource } from '../EnrollmentWorkspace.jsx';
import { useAuth } from '../auth/AuthContext.jsx';
import {
  commitImportBatch,
  discardImportBatch,
  getImportBatch,
  listImportBatchErrors,
  listImportBatchRows,
  listImportBatches,
  listImportMappings,
  reviewImportRow,
  uploadImportBatch,
  voidImportBatch,
} from '../api/legacyImports.js';
import ImportMappingsPanel from './ImportMappingsPanel.jsx';
import './legacy-imports.css';

const BATCH_STATUSES = ['staged', 'validated', 'imported', 'reconciled', 'failed'];
const ROW_STATUSES = ['duplicate_flagged', 'invalid', 'valid', 'skipped', 'imported'];

const STATUS_TONES = {
  imported: 'success',
  reconciled: 'success',
  valid: 'success',
  validated: 'warning',
  staged: 'warning',
  duplicate_flagged: 'warning',
  failed: 'danger',
  invalid: 'danger',
  skipped: 'neutral',
  discarded: 'danger',
  voided: 'danger',
};

const formatDate = value => (value ? new Date(value).toLocaleString() : '—');
const formatBytes = bytes => {
  if (bytes == null) return '—';
  if (bytes < 1024) return `${bytes} B`;
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
};
const formatStatus = value => (value ? String(value).replaceAll('_', ' ') : '—');

function StatusBadge({ value, label }) {
  const key = String(value || '').toLowerCase();
  const tone = STATUS_TONES[key] || 'neutral';
  return <span className={`status status-${tone}`}>{label || formatStatus(value)}</span>;
}

function ActionDialog({
  title,
  confirmLabel = 'Confirm',
  confirmDisabled = false,
  danger = false,
  busy = false,
  onCancel,
  onConfirm,
  children,
}) {
  const ref = useRef(null);
  useEffect(() => {
    const previous = document.activeElement;
    const dialog = ref.current;
    dialog.showModal();
    return () => {
      dialog.close();
      previous?.focus();
    };
  }, []);

  return (
    <dialog
      ref={ref}
      aria-labelledby="import-dialog-title"
      onCancel={event => {
        event.preventDefault();
        if (!busy) onCancel();
      }}
    >
      <h2 id="import-dialog-title">{title}</h2>
      {children}
      <div className="component-row">
        <button type="button" className="button button-outline" disabled={busy} onClick={onCancel}>
          Cancel
        </button>
        <button
          type="button"
          className={`button${danger ? ' delete-action' : ''}`}
          style={danger ? { background: '#8c303c', borderColor: '#8c303c', color: '#fff' } : undefined}
          disabled={busy || confirmDisabled}
          onClick={onConfirm}
        >
          {busy ? 'Working…' : confirmLabel}
        </button>
      </div>
    </dialog>
  );
}

function useAsyncLoad(loader, deps) {
  const [state, setState] = useState({ data: null, loading: true, error: '' });
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let live = true;
    setState(prev => ({ ...prev, loading: true, error: '' }));
    loader()
      .then(data => {
        if (live) setState({ data, loading: false, error: '' });
      })
      .catch(err => {
        if (live && err?.name !== 'AbortError') {
          setState({ data: null, loading: false, error: err?.message || 'Request failed.' });
        }
      });
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);

  return { ...state, reload: () => setTick(value => value + 1) };
}

function RowReviewPanel({ kind, batchId, batchStatus, row, isAdmin, onClose, onSaved }) {
  const isGrades = kind === 'grades';
  const evidence = Array.isArray(row.duplicateEvidence) ? row.duplicateEvidence : [];
  const visibleStudents = evidence.filter(
    item => item.kind === 'student' && item.campusVisible !== false && item.id != null
  );
  const hasHiddenEvidence = evidence.some(item => item.campusVisible === false);
  const hasExactLrnMatch = evidence.some(item => item.rule === 'LRN_EXACT');
  const canLinkOrDistinct = evidence.length > 0 && ['duplicate_flagged', 'valid'].includes(row.status);
  const canLink = isGrades
    ? row.status === 'duplicate_flagged' && visibleStudents.length > 0
    : canLinkOrDistinct;
  const canDistinct = !isGrades && canLinkOrDistinct;
  const canSkip = ['duplicate_flagged', 'valid'].includes(row.status);
  const canReview = batchStatus === 'validated' && (canSkip || canLink || canDistinct);
  const blockedCrossCampus = !isGrades && hasHiddenEvidence && !isAdmin;

  const [decision, setDecision] = useState('');
  const [studentId, setStudentId] = useState(visibleStudents.length === 1 ? String(visibleStudents[0].id) : '');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    setDecision('');
    setStudentId(visibleStudents.length === 1 ? String(visibleStudents[0].id) : '');
    setReason('');
    setError('');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [row.id, row.version]);

  async function submitReview(event) {
    event.preventDefault();
    if (!decision || !reason.trim()) return;
    setBusy(true);
    setError('');
    try {
      await reviewImportRow(batchId, row.id, {
        kind,
        version: row.version,
        decision,
        reason: reason.trim(),
        studentId: decision === 'same_person' ? Number(studentId) : undefined,
      });
      onSaved(`Decision "${formatStatus(decision)}" saved for row #${row.rowNumber}.`);
    } catch (requestError) {
      setError(requestError.message || 'Review decision could not be saved.');
    } finally {
      setBusy(false);
    }
  }

  const rawEntries = Object.entries(row.rawValues || {});
  const norm = row.normalizedValues || {};
  const gradeCells = isGrades && Array.isArray(norm.cells) ? norm.cells : [];
  const normalizedEntries = isGrades
    ? [
        ['studentId', norm.studentId],
        ['matchedBy', norm.matchedBy],
        ['gradeLevel', norm.context?.gradeLevel],
        ['section', norm.context?.section],
        ['termId', norm.context?.termId],
        ['blankCells', norm.blankCells],
      ].filter(([, value]) => value !== undefined && value !== null && value !== '')
    : Object.entries(norm);

  return (
    <div className="imports-row-detail" aria-label={`Row ${row.rowNumber} review`}>
      <div className="imports-row-detail-head">
        <div>
          <h3>
            Row #{row.rowNumber} <StatusBadge value={row.status} />
          </h3>
          <small className="field-help">
            Row ID #{row.id} · Version {row.version}
            {row.studentId ? ` · Linked Student #${row.studentId}` : ''}
          </small>
        </div>
        <button type="button" className="button button-outline" onClick={onClose}>
          Close row detail
        </button>
      </div>

      <div className="imports-kv-grid">
        <div className="imports-kv-box">
          <h4>Raw CSV values</h4>
          {rawEntries.length ? (
            <dl className="imports-kv-list">
              {rawEntries.map(([key, value]) => (
                <div key={key} style={{ display: 'contents' }}>
                  <dt>{key}</dt>
                  <dd>{value === '' || value == null ? '—' : String(value)}</dd>
                </div>
              ))}
            </dl>
          ) : (
            <p className="field-help">No raw values recorded.</p>
          )}
        </div>

        <div className="imports-kv-box">
          <h4>{isGrades ? 'Matched student & row context' : 'Normalized student fields'}</h4>
          {normalizedEntries.length ? (
            <dl className="imports-kv-list">
              {normalizedEntries.map(([key, value]) => (
                <div key={key} style={{ display: 'contents' }}>
                  <dt>{key}</dt>
                  <dd>{value === '' || value == null ? '—' : String(value)}</dd>
                </div>
              ))}
            </dl>
          ) : (
            <p className="field-help">Normalized values are only stored for valid or flagged rows.</p>
          )}
        </div>
      </div>

      {isGrades && gradeCells.length > 0 && (
        <div style={{ marginBottom: 16 }}>
          <h4>Parsed grade cells ({gradeCells.length})</h4>
          <div className="setup-table-wrap">
            <table className="setup-table">
              <thead>
                <tr>
                  <th>Source column</th>
                  <th>Record kind</th>
                  <th>Grade / mark</th>
                </tr>
              </thead>
              <tbody>
                {gradeCells.map((cell, index) => (
                  <tr key={`${cell.column}-${index}`}>
                    <td>{cell.column}</td>
                    <td>{formatStatus(cell.kind)}</td>
                    <td>
                      <strong>{cell.value != null ? cell.value : cell.mark ?? '—'}</strong>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {row.messages?.length > 0 && (
        <div style={{ marginBottom: 16 }}>
          <h4>Validation messages</h4>
          <ul className="imports-problems-list">
            {row.messages.map((msg, index) => (
              <li key={`${msg.code}-${index}`}>
                <strong>{msg.code}</strong>
                {msg.field ? ` (${msg.field})` : ''} [{msg.severity}]: {msg.detail}
              </li>
            ))}
          </ul>
        </div>
      )}

      {evidence.length > 0 && (
        <div style={{ marginBottom: 16 }}>
          <h4>{isGrades ? 'Identity candidates' : 'Duplicate evidence'}</h4>
          <ul className="imports-evidence-list">
            {evidence.map((item, index) => (
              <li key={`${item.kind}-${item.id ?? item.rowNumber ?? index}`} className="imports-evidence-card">
                <strong>{formatStatus(item.kind)}</strong>
                <StatusBadge value="duplicate_flagged" label={item.rule} />
                {typeof item.nameScore === 'number' && (
                  <span> · Name similarity {Math.round(item.nameScore * 100)}%</span>
                )}
                <div className="field-help" style={{ marginTop: 6 }}>
                  {item.campusVisible !== false ? (
                    item.kind === 'student' ? (
                      <>Existing student ID #{item.id}</>
                    ) : item.kind === 'application' ? (
                      <>Existing application ID #{item.id}</>
                    ) : item.kind === 'staged_row' ? (
                      <>Staged batch #{item.batchId}, row #{item.rowNumber}</>
                    ) : (
                      <>Same-file row #{item.rowNumber}</>
                    )
                  ) : (
                    <>Record belongs to another campus outside your scope.</>
                  )}
                </div>
              </li>
            ))}
          </ul>
        </div>
      )}

      {row.review && (
        <p className="notice">
          <strong>Recorded decision:</strong> {formatStatus(row.review.decision)}
          {row.review.matchedStudentId ? ` → Student #${row.review.matchedStudentId}` : ''} ·{' '}
          {formatDate(row.review.reviewedAt)}
          <br />
          Reason: {row.review.reason}
        </p>
      )}

      {canReview && (
        <form onSubmit={submitReview}>
          <h4>Record review decision</h4>
          <Failure error={error} />
          {blockedCrossCampus && (
            <p className="notice notice-error" role="alert">
              This row matches a record at a campus you cannot view. An administrator with all-campus access must decide same-person or distinct.
            </p>
          )}
          <label htmlFor={`row-decision-${row.id}`}>Decision</label>
          <select
            id={`row-decision-${row.id}`}
            required
            disabled={busy}
            value={decision}
            onChange={event => setDecision(event.target.value)}
          >
            <option value="">Choose a decision…</option>
            {canLink && (
              <option
                value="same_person"
                disabled={visibleStudents.length === 0 || blockedCrossCampus}
              >
                {isGrades
                  ? 'Same person — link grade row to existing student'
                  : 'Same person — link to existing student (no duplicate created)'}
              </option>
            )}
            {canDistinct && (
              <option value="distinct" disabled={hasExactLrnMatch || blockedCrossCampus}>
                Distinct person — allow creating a new student at commit
              </option>
            )}
            {canSkip && <option value="skip">Skip row — exclude this row from commit</option>}
          </select>

          {isGrades && row.status === 'duplicate_flagged' && (
            <p className="field-help">
              Grade rows never create new students — link this row to a candidate student or skip it.
            </p>
          )}

          {!isGrades && hasExactLrnMatch && canLinkOrDistinct && (
            <p className="field-help">
              “Distinct person” is disabled because an exact LRN match was found; link to the existing student or skip the row.
            </p>
          )}

          {decision === 'same_person' && (
            <>
              <label htmlFor={`row-student-${row.id}`}>Matched existing student</label>
              <select
                id={`row-student-${row.id}`}
                required
                disabled={busy}
                value={studentId}
                onChange={event => setStudentId(event.target.value)}
              >
                <option value="">Select matched student…</option>
                {visibleStudents.map(item => (
                  <option key={item.id} value={item.id}>
                    Student #{item.id} ({item.rule}
                    {typeof item.nameScore === 'number'
                      ? `, ${Math.round(item.nameScore * 100)}% name match`
                      : ''}
                    )
                  </option>
                ))}
              </select>
            </>
          )}

          <label htmlFor={`row-reason-${row.id}`}>Review reason (required)</label>
          <textarea
            id={`row-reason-${row.id}`}
            className="imports-textarea"
            required
            maxLength={2000}
            disabled={busy}
            value={reason}
            onChange={event => setReason(event.target.value)}
            placeholder="Explain why this row should be linked, marked distinct, or skipped…"
          />

          <button
            type="submit"
            className="button"
            disabled={busy || !decision || !reason.trim() || (decision === 'same_person' && !studentId)}
          >
            {busy ? 'Saving decision…' : 'Save row decision'}
          </button>
        </form>
      )}
    </div>
  );
}

function BatchDetailWorkspace({ kind, batchId, campusNameFor, isAdmin, onBatchChanged }) {
  const isGrades = kind === 'grades';
  const detail = useAsyncLoad(() => getImportBatch(batchId, kind), [batchId, kind]);
  const [subTab, setSubTab] = useState('rows');
  const [rowStatus, setRowStatus] = useState('');
  const [rowPage, setRowPage] = useState(0);
  const [selectedRowId, setSelectedRowId] = useState(null);
  const [errorSeverity, setErrorSeverity] = useState('');
  const [errorRowFilter, setErrorRowFilter] = useState('');
  const [errorPage, setErrorPage] = useState(0);

  const [actionBusy, setActionBusy] = useState(false);
  const [actionError, setActionError] = useState('');
  const [actionNotice, setActionNotice] = useState('');
  const [confirmCommit, setConfirmCommit] = useState(false);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const [discardReason, setDiscardReason] = useState('');
  const [confirmVoid, setConfirmVoid] = useState(false);
  const [voidReason, setVoidReason] = useState('');

  const rows = useAsyncLoad(
    () => listImportBatchRows(batchId, { kind, status: rowStatus, page: rowPage, size: 25 }),
    [batchId, kind, rowStatus, rowPage]
  );

  const errors = useAsyncLoad(
    () =>
      listImportBatchErrors(batchId, {
        kind,
        severity: errorSeverity,
        rowNumber: /^\d+$/.test(errorRowFilter.trim()) ? Number(errorRowFilter.trim()) : '',
        page: errorPage,
        size: 25,
      }),
    [batchId, kind, errorSeverity, errorRowFilter, errorPage]
  );

  const batch = detail.data;
  const isVoided = Boolean(batch?.voidedAt);
  const isDiscarded = Boolean(
    batch?.discarded || batch?.batchExceptions?.some(item => item.code === 'DISCARDED')
  );
  const discardedException = batch?.batchExceptions?.find(item => item.code === 'DISCARDED');
  const canCommit =
    batch &&
    batch.status === 'validated' &&
    !isDiscarded &&
    !isVoided &&
    Number(batch.flaggedCount) === 0 &&
    Number(batch.validCount) > 0;
  const canDiscard =
    batch && !isDiscarded && !isVoided && ['staged', 'validated'].includes(batch.status);
  const canVoid = isGrades && batch && batch.status === 'imported' && !isVoided;

  const selectedRow = (rows.data?.items || []).find(item => item.id === selectedRowId) || null;

  function reloadAll() {
    detail.reload();
    rows.reload();
    errors.reload();
  }

  async function handleCommit() {
    if (!batch) return;
    setActionBusy(true);
    setActionError('');
    setActionNotice('');
    try {
      const result = await commitImportBatch(batch.id, {
        kind,
        version: batch.version,
        expectedValidCount: batch.validCount,
      });
      setConfirmCommit(false);
      setSelectedRowId(null);
      if (isGrades) {
        setActionNotice(
          result.alreadyImported
            ? `Grade batch #${batch.id} was already imported (${result.recordsCreated} record(s) across ${result.rowsImported} row(s)).`
            : `Grade batch #${batch.id} committed: ${result.recordsCreated} legacy grade record(s) created across ${result.rowsImported} row(s)${result.recordsAlreadyRecorded ? `, ${result.recordsAlreadyRecorded} already recorded` : ''}.`
        );
      } else {
        setActionNotice(
          result.alreadyImported
            ? `Batch #${batch.id} was already imported (${result.created} created, ${result.linked} linked).`
            : `Batch #${batch.id} committed: ${result.created} student(s) created, ${result.linked} linked, ${result.skipped} skipped.`
        );
      }
      reloadAll();
      onBatchChanged();
    } catch (requestError) {
      setConfirmCommit(false);
      setActionError(requestError.message || 'Commit could not be completed.');
      reloadAll();
      onBatchChanged();
    } finally {
      setActionBusy(false);
    }
  }

  async function handleDiscard() {
    if (!batch || !discardReason.trim()) return;
    setActionBusy(true);
    setActionError('');
    setActionNotice('');
    try {
      await discardImportBatch(batch.id, {
        kind,
        version: batch.version,
        reason: discardReason.trim(),
      });
      setConfirmDiscard(false);
      setDiscardReason('');
      setSelectedRowId(null);
      setActionNotice(
        `Batch #${batch.id} discarded. The file checksum is now free for re-upload and staged rows remain preserved for history.`
      );
      reloadAll();
      onBatchChanged();
    } catch (requestError) {
      setConfirmDiscard(false);
      setActionError(requestError.message || 'Batch could not be discarded.');
      reloadAll();
      onBatchChanged();
    } finally {
      setActionBusy(false);
    }
  }

  async function handleVoid() {
    if (!batch || !voidReason.trim()) return;
    setActionBusy(true);
    setActionError('');
    setActionNotice('');
    try {
      const result = await voidImportBatch(batch.id, {
        version: batch.version,
        reason: voidReason.trim(),
      });
      setConfirmVoid(false);
      setVoidReason('');
      setSelectedRowId(null);
      setActionNotice(
        result.alreadyVoided
          ? `Grade batch #${batch.id} was already voided.`
          : `Grade batch #${batch.id} voided: ${result.voidedRecords} legacy grade record(s) marked voided. The file checksum is now free for re-upload.`
      );
      reloadAll();
      onBatchChanged();
    } catch (requestError) {
      setConfirmVoid(false);
      setActionError(requestError.message || 'Batch could not be voided.');
      reloadAll();
      onBatchChanged();
    } finally {
      setActionBusy(false);
    }
  }

  return (
    <section className="panel" aria-label={`Import batch ${batchId} review`}>
      <h2>{isGrades ? `Grade matrix batch #${batchId}` : `Student import batch #${batchId}`}</h2>
      <div className="panel-body">
        <Failure error={detail.error} reload={detail.reload} />
        <Failure error={actionError} />
        {actionNotice && (
          <p className="notice" role="status">
            {actionNotice}
          </p>
        )}
        {detail.loading && !batch && <p role="status">Loading batch details…</p>}

        {batch && (
          <>
            <div className="selected-app" style={{ paddingTop: 0 }}>
              <div>
                <p className="eyebrow">
                  {isGrades ? 'Grade Matrix' : 'Student CSV'} · Batch #{batch.id} ·{' '}
                  {campusNameFor(batch.campusId)} · Version {batch.version}
                </p>
                <h2>
                  {batch.sourceName}{' '}
                  <span className="imports-status-row">
                    <StatusBadge value={batch.status} />
                    {isDiscarded && <StatusBadge value="discarded" label="Discarded" />}
                    {isVoided && <StatusBadge value="voided" label="Voided" />}
                  </span>
                </h2>
              </div>
              <div className="component-row">
                {batch.status === 'validated' && !isDiscarded && !isVoided && (
                  <button
                    type="button"
                    className="button"
                    disabled={actionBusy || !canCommit}
                    onClick={() => setConfirmCommit(true)}
                  >
                    Commit batch ({batch.validCount})
                  </button>
                )}
                {canVoid && (
                  <button
                    type="button"
                    className="button button-outline delete-action"
                    disabled={actionBusy}
                    onClick={() => {
                      setVoidReason('');
                      setConfirmVoid(true);
                    }}
                  >
                    Void batch
                  </button>
                )}
                {canDiscard && (
                  <button
                    type="button"
                    className="button button-outline delete-action"
                    disabled={actionBusy}
                    onClick={() => {
                      setDiscardReason('');
                      setConfirmDiscard(true);
                    }}
                  >
                    Discard batch
                  </button>
                )}
                <button
                  type="button"
                  className="button button-outline"
                  disabled={actionBusy || detail.loading}
                  onClick={reloadAll}
                >
                  Reload batch
                </button>
              </div>
            </div>

            {isVoided && (
              <div className="imports-discard-banner" role="status">
                <strong>Committed grade batch voided</strong>
                <p>{batch.voidReason || 'All legacy grade records from this batch were voided.'}</p>
                <p className="field-help">
                  {batch.voidedBy != null ? `Voided by staff #${batch.voidedBy}` : 'Voided'}
                  {batch.voidedAt ? ` on ${formatDate(batch.voidedAt)}` : ''}.
                  {batch.voidedRecordCount != null ? ` ${batch.voidedRecordCount} record(s) voided.` : ''} File checksum is freed for re-upload.
                </p>
              </div>
            )}

            {isDiscarded && (
              <div className="imports-discard-banner" role="status">
                <strong>Batch discarded</strong>
                <p>{batch.discardReason || discardedException?.detail || 'This staged batch was discarded.'}</p>
                {(batch.discardedBy != null || batch.discardedAt) && (
                  <p className="field-help">
                    {batch.discardedBy != null ? `Discarded by staff #${batch.discardedBy}` : 'Discarded'}
                    {batch.discardedAt ? ` on ${formatDate(batch.discardedAt)}` : ''}. File checksum is freed for re-upload; staged rows are kept for audit history.
                  </p>
                )}
              </div>
            )}

            {batch.mapping && !batch.mapping.realFileValidated && (
              <p className="notice" role="status">
                Mapping <strong>{batch.mapping.name}</strong> has not been marked as real-file validated yet. Verify staged values carefully before committing.
              </p>
            )}

            {batch.status === 'validated' && !isDiscarded && batch.flaggedCount > 0 && (
              <p className="notice" role="status">
                <strong>Commit blocked:</strong> {batch.flaggedCount} row(s){' '}
                {isGrades
                  ? 'need an identity decision. Link each flagged row to an existing student or skip it before committing.'
                  : 'are flagged as possible duplicates. Link, mark distinct, or skip every flagged row before committing.'}
              </p>
            )}

            {batch.status === 'validated' && !isDiscarded && batch.flaggedCount === 0 && batch.validCount === 0 && (
              <p className="notice" role="status">
                This batch has no valid importable rows. Fix the source CSV or discard the batch to upload a corrected file.
              </p>
            )}

            <div className="imports-stats">
              <div>
                <span>Source rows</span>
                <strong>{batch.sourceCount ?? 0}</strong>
              </div>
              <div>
                <span>Valid (ready)</span>
                <strong>{batch.validCount ?? 0}</strong>
              </div>
              <div>
                <span>{isGrades ? 'Identity flagged' : 'Duplicate flagged'}</span>
                <strong>{batch.flaggedCount ?? 0}</strong>
              </div>
              <div>
                <span>Invalid</span>
                <strong>{batch.invalidCount ?? 0}</strong>
              </div>
              <div>
                <span>Skipped</span>
                <strong>{batch.skippedCount ?? 0}</strong>
              </div>
              <div>
                <span>Imported</span>
                <strong>{batch.importedCount ?? 0}</strong>
              </div>
            </div>

            <dl className="imports-meta-grid">
              <div>
                <dt>Mapping used</dt>
                <dd>
                  {batch.mapping ? `${batch.mapping.name} (v${batch.mapping.version})` : 'Not recorded'}
                </dd>
              </div>
              <div>
                <dt>File size</dt>
                <dd>{formatBytes(batch.fileSizeBytes)}</dd>
              </div>
              <div>
                <dt>Checksum</dt>
                <dd title={batch.checksum || ''}>
                  {batch.checksum ? `${String(batch.checksum).slice(0, 16)}…` : '—'}
                </dd>
              </div>
              <div>
                <dt>Staged at</dt>
                <dd>{formatDate(batch.startedAt)}</dd>
              </div>
              {batch.importedAt && (
                <div>
                  <dt>Imported at</dt>
                  <dd>{formatDate(batch.importedAt)}</dd>
                </div>
              )}
              {isGrades && (
                <>
                  <div>
                    <dt>Grade cells</dt>
                    <dd>
                      {batch.recordCount ?? 0} parsed · {batch.blankCellCount ?? 0} blank
                    </dd>
                  </div>
                  <div>
                    <dt>Stored records</dt>
                    <dd>
                      {batch.liveRecordCount ?? 0} live · {batch.voidedRecordCount ?? 0} voided
                    </dd>
                  </div>
                  {batch.uploadContext && (
                    <div>
                      <dt>Upload context</dt>
                      <dd>
                        {[
                          batch.uploadContext.gradeLevel,
                          batch.uploadContext.section,
                          batch.uploadContext.termId ? `Term #${batch.uploadContext.termId}` : null,
                          batch.uploadContext.academicYearId
                            ? `Year #${batch.uploadContext.academicYearId}`
                            : null,
                        ]
                          .filter(Boolean)
                          .join(' · ') || 'From CSV columns'}
                      </dd>
                    </div>
                  )}
                </>
              )}
            </dl>

            {isGrades && batch.columns?.length > 0 && (
              <div className="imports-error-chips" aria-label="Grade columns parsed">
                {batch.columns.map(col => (
                  <span key={col.column} className="imports-chip">
                    {col.column}: {col.records}
                  </span>
                ))}
              </div>
            )}

            {batch.batchExceptions?.length > 0 && (
              <div className="notice notice-error" role="alert" style={{ marginBottom: 16 }}>
                <strong>Batch-level exceptions</strong>
                <ul className="imports-problems-list">
                  {batch.batchExceptions.map((item, index) => (
                    <li key={`${item.code}-${index}`}>
                      <strong>{item.code}</strong>
                      {item.field ? ` (${item.field})` : ''} [{item.severity}]: {item.detail}
                    </li>
                  ))}
                </ul>
              </div>
            )}

            {batch.errorSummary?.length > 0 && (
              <div className="imports-error-chips" aria-label="Error summary">
                {batch.errorSummary.map(item => (
                  <span
                    key={`${item.code}-${item.severity}`}
                    className={`imports-chip imports-chip-${item.severity === 'error' ? 'error' : 'warning'}`}
                  >
                    {item.code}: {item.count}
                  </span>
                ))}
              </div>
            )}

            <div className="setup-tabs imports-subtabs" role="tablist" aria-label="Batch inspection views">
              <button
                type="button"
                role="tab"
                aria-selected={subTab === 'rows'}
                onClick={() => setSubTab('rows')}
              >
                Staged rows <span>({batch.sourceCount ?? 0})</span>
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={subTab === 'errors'}
                onClick={() => setSubTab('errors')}
              >
                Errors &amp; warnings <span>({errors.data?.total ?? 0})</span>
              </button>
            </div>

            {subTab === 'rows' && (
              <div>
                <div className="imports-filters">
                  <div>
                    <label htmlFor="row-status-filter">Row status</label>
                    <select
                      id="row-status-filter"
                      value={rowStatus}
                      onChange={event => {
                        setRowStatus(event.target.value);
                        setRowPage(0);
                        setSelectedRowId(null);
                      }}
                    >
                      <option value="">All row statuses</option>
                      {ROW_STATUSES.map(value => (
                        <option key={value} value={value}>
                          {formatStatus(value)}
                        </option>
                      ))}
                    </select>
                  </div>
                  <button
                    type="button"
                    className="button button-outline"
                    disabled={rows.loading}
                    onClick={rows.reload}
                  >
                    Refresh rows
                  </button>
                </div>

                <Failure error={rows.error} reload={rows.reload} />
                {rows.loading && <p role="status">Loading staged rows…</p>}

                {!rows.loading && !rows.error && (rows.data?.items || []).length === 0 && (
                  <div className="empty-state">
                    <h3>No staged rows found</h3>
                    <p>Try clearing the row status filter to view all records in this batch.</p>
                  </div>
                )}

                {(rows.data?.items || []).length > 0 && (
                  <div className="setup-table-wrap">
                    <table className="setup-table">
                      <thead>
                        <tr>
                          <th>Row</th>
                          <th>Student record</th>
                          <th>{isGrades ? 'Context & grades' : 'Classification'}</th>
                          <th>Status</th>
                          <th>Messages / review</th>
                          <th>Action</th>
                        </tr>
                      </thead>
                      <tbody>
                        {(rows.data?.items || []).map(row => {
                          const norm = row.normalizedValues || {};
                          const raw = row.rawValues || {};
                          const displayName =
                            [norm.lastName, norm.firstName].filter(Boolean).join(', ') ||
                            [raw['Last Name'], raw['First Name']].filter(Boolean).join(', ') ||
                            (norm.studentId ? `Student #${norm.studentId}` : 'Unparsed row');
                          const displayNumber =
                            norm.studentNumber ||
                            raw['Student No'] ||
                            raw['student_number'] ||
                            (norm.studentId ? `Student #${norm.studentId}` : '—');
                          const cellCount = Array.isArray(norm.cells) ? norm.cells.length : 0;
                          return (
                            <tr
                              key={row.id}
                              className={selectedRowId === row.id ? 'selected-row' : undefined}
                            >
                              <td>#{row.rowNumber}</td>
                              <td>
                                <strong>{displayName}</strong>
                                <small>
                                  {displayNumber}
                                  {norm.lrn ? ` · LRN ${norm.lrn}` : ''}
                                  {!isGrades && norm.birthDate ? ` · DOB ${norm.birthDate}` : ''}
                                  {isGrades && norm.matchedBy ? ` · matched by ${norm.matchedBy}` : ''}
                                </small>
                              </td>
                              <td>
                                {isGrades ? (
                                  <>
                                    {norm.context?.gradeLevel || '—'}
                                    {norm.context?.section ? ` / ${norm.context.section}` : ''}
                                    <small>
                                      {cellCount} grade(s)
                                      {norm.blankCells != null ? ` · ${norm.blankCells} blank` : ''}
                                    </small>
                                  </>
                                ) : (
                                  <>
                                    {norm.programCode || '—'}
                                    <small>
                                      {norm.voucherCode || '—'}
                                      {norm.track ? ` / ${norm.track}` : ''}
                                    </small>
                                  </>
                                )}
                              </td>
                              <td>
                                <StatusBadge value={row.status} />
                              </td>
                              <td>
                                {row.review ? (
                                  <>
                                    <strong>{formatStatus(row.review.decision)}</strong>
                                    <small>{row.review.reason}</small>
                                  </>
                                ) : row.messages?.length ? (
                                  <>
                                    <strong>{row.messages[0].code}</strong>
                                    <small>
                                      {row.messages[0].detail}
                                      {row.messages.length > 1
                                        ? ` (+${row.messages.length - 1} more)`
                                        : ''}
                                    </small>
                                  </>
                                ) : (
                                  <span className="field-help">No issues</span>
                                )}
                              </td>
                              <td>
                                <button
                                  type="button"
                                  className="button button-outline"
                                  onClick={() =>
                                    setSelectedRowId(selectedRowId === row.id ? null : row.id)
                                  }
                                >
                                  {selectedRowId === row.id
                                    ? 'Hide'
                                    : batch.status === 'validated' &&
                                        ['duplicate_flagged', 'valid'].includes(row.status)
                                      ? `Review row #${row.rowNumber}`
                                      : `Inspect row #${row.rowNumber}`}
                                </button>
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>
                )}

                <Pager
                  page={rowPage}
                  total={rows.data?.total ?? 0}
                  onChange={next => {
                    setRowPage(next);
                    setSelectedRowId(null);
                  }}
                  disabled={rows.loading}
                />

                {selectedRow && (
                  <RowReviewPanel
                    kind={kind}
                    batchId={batch.id}
                    batchStatus={batch.status}
                    row={selectedRow}
                    isAdmin={isAdmin}
                    onClose={() => setSelectedRowId(null)}
                    onSaved={message => {
                      setActionNotice(message);
                      setActionError('');
                      setSelectedRowId(null);
                      reloadAll();
                      onBatchChanged();
                    }}
                  />
                )}
              </div>
            )}

            {subTab === 'errors' && (
              <div>
                <div className="imports-filters">
                  <div>
                    <label htmlFor="error-severity-filter">Severity</label>
                    <select
                      id="error-severity-filter"
                      value={errorSeverity}
                      onChange={event => {
                        setErrorSeverity(event.target.value);
                        setErrorPage(0);
                      }}
                    >
                      <option value="">All severities</option>
                      <option value="error">Error</option>
                      <option value="warning">Warning</option>
                    </select>
                  </div>
                  <div>
                    <label htmlFor="error-row-filter">Row number</label>
                    <input
                      id="error-row-filter"
                      type="number"
                      min={1}
                      value={errorRowFilter}
                      onChange={event => {
                        setErrorRowFilter(event.target.value);
                        setErrorPage(0);
                      }}
                      placeholder="All rows"
                    />
                  </div>
                  <button
                    type="button"
                    className="button button-outline"
                    disabled={errors.loading}
                    onClick={errors.reload}
                  >
                    Refresh errors
                  </button>
                </div>

                <Failure error={errors.error} reload={errors.reload} />
                {errors.loading && <p role="status">Loading batch exceptions…</p>}

                {!errors.loading && !errors.error && (errors.data?.items || []).length === 0 && (
                  <div className="empty-state">
                    <h3>No errors or warnings recorded</h3>
                    <p>No exceptions match the selected filter.</p>
                  </div>
                )}

                {(errors.data?.items || []).length > 0 && (
                  <div className="setup-table-wrap">
                    <table className="setup-table">
                      <thead>
                        <tr>
                          <th>Row</th>
                          <th>Severity</th>
                          <th>Code</th>
                          <th>Field</th>
                          <th>Detail</th>
                        </tr>
                      </thead>
                      <tbody>
                        {(errors.data?.items || []).map(item => (
                          <tr key={item.id}>
                            <td>{item.rowNumber != null ? `#${item.rowNumber}` : 'Batch'}</td>
                            <td>
                              <StatusBadge
                                value={item.severity === 'error' ? 'invalid' : 'duplicate_flagged'}
                                label={item.severity}
                              />
                            </td>
                            <td>
                              <strong>{item.code}</strong>
                            </td>
                            <td>{item.field || '—'}</td>
                            <td>{item.detail}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}

                <Pager
                  page={errorPage}
                  total={errors.data?.total ?? 0}
                  onChange={setErrorPage}
                  disabled={errors.loading}
                />
              </div>
            )}
          </>
        )}

        {confirmCommit && batch && (
          <ActionDialog
            title={`Commit batch #${batch.id}?`}
            confirmLabel={isGrades ? 'Commit legacy grades' : 'Commit to student records'}
            busy={actionBusy}
            onCancel={() => setConfirmCommit(false)}
            onConfirm={handleCommit}
          >
            <p>
              {isGrades ? (
                <>
                  This will write <strong>{batch.recordCount ?? 0}</strong> legacy grade record(s) across{' '}
                  <strong>{batch.validCount}</strong> valid row(s) from <strong>{batch.sourceName}</strong> into permanent legacy storage in one transaction.
                </>
              ) : (
                <>
                  This will create new student records or link confirmed same-person rows for{' '}
                  <strong>{batch.validCount}</strong> valid row(s) from <strong>{batch.sourceName}</strong> in one transaction.
                </>
              )}
            </p>
            <p className="field-help">
              Invalid ({batch.invalidCount}) and skipped ({batch.skippedCount}) rows will not be imported. The server rechecks for new conflicts before writing.
            </p>
          </ActionDialog>
        )}

        {confirmDiscard && batch && (
          <ActionDialog
            title={`Discard batch #${batch.id}?`}
            confirmLabel="Confirm discard"
            confirmDisabled={!discardReason.trim()}
            danger
            busy={actionBusy}
            onCancel={() => setConfirmDiscard(false)}
            onConfirm={handleDiscard}
          >
            <p>
              Discarding marks <strong>{batch.sourceName}</strong> as failed with a <code>DISCARDED</code> note. Staged rows are kept for audit history and the file checksum is freed for re-upload.
            </p>
            <div className="imports-dialog-form">
              <label htmlFor="discard-batch-reason">Discard reason (required)</label>
              <textarea
                id="discard-batch-reason"
                className="imports-textarea"
                required
                maxLength={isGrades ? 1000 : 2000}
                disabled={actionBusy}
                value={discardReason}
                onChange={event => setDiscardReason(event.target.value)}
                placeholder="e.g. Uploaded under the wrong campus or superseded by a corrected CSV"
              />
            </div>
          </ActionDialog>
        )}

        {confirmVoid && batch && (
          <ActionDialog
            title={`Void committed grade batch #${batch.id}?`}
            confirmLabel="Confirm void"
            confirmDisabled={!voidReason.trim()}
            danger
            busy={actionBusy}
            onCancel={() => setConfirmVoid(false)}
            onConfirm={handleVoid}
          >
            <p>
              Voiding marks all <strong>{batch.liveRecordCount ?? batch.recordCount ?? 0}</strong> live legacy grade record(s) from{' '}
              <strong>{batch.sourceName}</strong> as voided in one transaction. The batch and its voided records stay in audit history, and the file checksum is freed so a corrected file can be uploaded.
            </p>
            <div className="imports-dialog-form">
              <label htmlFor="void-batch-reason">Void reason (required)</label>
              <textarea
                id="void-batch-reason"
                className="imports-textarea"
                required
                maxLength={2000}
                disabled={actionBusy}
                value={voidReason}
                onChange={event => setVoidReason(event.target.value)}
                placeholder="e.g. Wrong academic term selected during upload; re-uploading corrected matrix"
              />
            </div>
          </ActionDialog>
        )}
      </div>
    </section>
  );
}

export default function LegacyImports() {
  const { user, roles } = useAuth();
  const isAdmin = roles.includes('admin');
  const [params, setParams] = useSearchParams();
  const kind = params.get('type') === 'grades' ? 'grades' : 'students';
  const isGrades = kind === 'grades';
  const [activeTab, setActiveTab] = useState('batches');

  const reference = useResource('/api/enrollment/reference-data');
  const yearsResource = useResource('/api/setup/academic-years');
  const mappingsState = useAsyncLoad(() => listImportMappings(kind), [kind]);

  const campuses = useMemo(() => {
    const refCampuses = reference.data?.campuses || [];
    if (refCampuses.length > 0) return refCampuses;
    const seen = new Map();
    for (const grant of user?.grants || []) {
      if (grant.campusId != null && !seen.has(grant.campusId)) {
        seen.set(grant.campusId, {
          id: grant.campusId,
          code: grant.campusCode || '',
          name: grant.campusName || grant.campusCode || `Campus #${grant.campusId}`,
        });
      }
    }
    return [...seen.values()];
  }, [reference.data?.campuses, user?.grants]);

  const academicYears = Array.isArray(yearsResource.data) ? yearsResource.data : [];
  const allTerms = reference.data?.terms || [];

  const campusNameFor = id => {
    const match = campuses.find(item => String(item.id) === String(id));
    return match ? match.name : `Campus #${id}`;
  };

  // Upload form state
  const [uploadCampusId, setUploadCampusId] = useState('');
  const [uploadMappingId, setUploadMappingId] = useState('');
  const [uploadYearId, setUploadYearId] = useState('');
  const [uploadTermId, setUploadTermId] = useState('');
  const [uploadGradeLevel, setUploadGradeLevel] = useState('');
  const [uploadSection, setUploadSection] = useState('');
  const [uploadFile, setUploadFile] = useState(null);
  const [uploadBusy, setUploadBusy] = useState(false);
  const [uploadError, setUploadError] = useState(null);
  const [uploadNotice, setUploadNotice] = useState('');
  const fileInputRef = useRef(null);

  useEffect(() => {
    if (!uploadCampusId && campuses.length > 0) {
      setUploadCampusId(String(campuses[0].id));
    }
  }, [campuses, uploadCampusId]);

  useEffect(() => {
    setUploadMappingId('');
    setUploadError(null);
    setUploadNotice('');
    setPage(0);
  }, [kind]);

  const filteredTerms = useMemo(() => {
    if (!uploadYearId) return allTerms;
    const selectedYear = academicYears.find(year => String(year.id) === String(uploadYearId));
    return allTerms.filter(term => {
      if (term.academicYearId != null) return String(term.academicYearId) === String(uploadYearId);
      if (selectedYear?.label && term.academicYear) return term.academicYear === selectedYear.label;
      return true;
    });
  }, [allTerms, academicYears, uploadYearId]);

  // Batch list filters
  const [filterCampusId, setFilterCampusId] = useState('');
  const [filterStatus, setFilterStatus] = useState('');
  const [page, setPage] = useState(0);

  const batchesState = useAsyncLoad(
    () => listImportBatches({ kind, campusId: filterCampusId, status: filterStatus, page, size: 25 }),
    [kind, filterCampusId, filterStatus, page]
  );

  const selectedBatchId = params.get('batchId');
  const mappings = mappingsState.data?.items || [];
  const activeMappings = mappings.filter(item => item.active);

  function switchKind(nextKind) {
    setParams(nextKind === 'grades' ? { type: 'grades' } : {});
  }

  function selectBatch(id) {
    setParams(isGrades ? { type: 'grades', batchId: String(id) } : { batchId: String(id) });
  }

  async function handleUpload(event) {
    event.preventDefault();
    if (!uploadCampusId || !uploadFile) return;
    setUploadBusy(true);
    setUploadError(null);
    setUploadNotice('');
    try {
      const created = await uploadImportBatch({
        kind,
        campusId: Number(uploadCampusId),
        mappingId: uploadMappingId ? Number(uploadMappingId) : undefined,
        academicYearId: isGrades && uploadYearId ? Number(uploadYearId) : undefined,
        termId: isGrades && uploadTermId ? Number(uploadTermId) : undefined,
        gradeLevel: isGrades ? uploadGradeLevel : undefined,
        section: isGrades ? uploadSection : undefined,
        file: uploadFile,
      });
      setUploadFile(null);
      if (fileInputRef.current) fileInputRef.current.value = '';
      setUploadNotice(
        isGrades
          ? `Dry-run grade batch #${created.id} staged for ${created.sourceName} (${created.validCount} valid, ${created.flaggedCount} flagged, ${created.invalidCount} invalid; ${created.recordCount ?? 0} grade cell(s)). Nothing has been written to legacy grade records yet.`
          : `Dry-run batch #${created.id} staged for ${created.sourceName} (${created.validCount} valid, ${created.flaggedCount} flagged, ${created.invalidCount} invalid). Nothing has been written to student records yet.`
      );
      batchesState.reload();
      selectBatch(created.id);
    } catch (requestError) {
      setUploadError(requestError);
      if (requestError?.batchId) {
        batchesState.reload();
      }
    } finally {
      setUploadBusy(false);
    }
  }

  return (
    <div className="imports-workspace">
      <header className="page-heading">
        <p className="eyebrow">Data migration</p>
        <h1>Legacy Import Review</h1>
        <p>
          Upload legacy student CSVs and historical grade matrices for dry-run validation, resolve flagged rows, and commit, discard, or void batches.
        </p>
      </header>

      <div className="imports-kind-switcher" role="group" aria-label="Import workflow type">
        <button
          type="button"
          className={`button ${!isGrades ? '' : 'button-outline'}`}
          aria-pressed={!isGrades}
          onClick={() => switchKind('students')}
        >
          Student CSV (ICI2-37)
        </button>
        <button
          type="button"
          className={`button ${isGrades ? '' : 'button-outline'}`}
          aria-pressed={isGrades}
          onClick={() => switchKind('grades')}
        >
          Grade Matrix (ICI2-38)
        </button>
      </div>

      <div className="setup-tabs" role="tablist" aria-label="Legacy import sections">
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === 'batches'}
          onClick={() => setActiveTab('batches')}
        >
          Import Batches <span>({batchesState.data?.total ?? 0})</span>
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={activeTab === 'mappings'}
          onClick={() => setActiveTab('mappings')}
        >
          Column Mappings <span>({mappings.length})</span>
        </button>
      </div>

      {activeTab === 'batches' && (
        <>
          <section className="panel">
            <h2>
              {isGrades
                ? 'Upload historical grade matrix CSV (dry run)'
                : 'Upload legacy student CSV (dry run)'}
            </h2>
            <div className="panel-body">
              <p className="field-help" style={{ marginTop: 0 }}>
                {isGrades ? (
                  <>
                    Uploads always run as a <strong>dry run</strong>: rows are matched to existing students of the selected campus and validated without writing to <code>legacy_grade_records</code> until committed. Supply academic year, term, and grade level below or include them as CSV columns (up to 5 MB, 300 columns, 250,000 grade cells).
                  </>
                ) : (
                  <>
                    Uploads always run as a <strong>dry run</strong>: rows are parsed, validated, and checked for duplicates without creating student records until you explicitly commit the batch. Maximum 5 MB and 20,000 rows (<code>.csv</code> only).
                  </>
                )}
              </p>

              {uploadNotice && (
                <p className="notice" role="status">
                  {uploadNotice}
                </p>
              )}

              {uploadError && (
                <div className="notice notice-error" role="alert" style={{ marginBottom: 16 }}>
                  <p style={{ margin: 0 }}>{uploadError.message}</p>
                  {uploadError.problems?.length > 0 && (
                    <ul className="imports-problems-list">
                      {uploadError.problems.map((problem, index) => (
                        <li key={`${problem.code}-${index}`}>
                          <strong>{problem.code}</strong>
                          {problem.header ? ` (${problem.header})` : ''}: {problem.detail}
                        </li>
                      ))}
                    </ul>
                  )}
                  {uploadError.batchId && (
                    <button
                      type="button"
                      className="button button-outline retry-button"
                      onClick={() => selectBatch(uploadError.batchId)}
                    >
                      {uploadError.code === 'DUPLICATE_FILE'
                        ? `Open existing batch #${uploadError.batchId}`
                        : `Inspect failed batch #${uploadError.batchId}`}
                    </button>
                  )}
                </div>
              )}

              <form className="imports-upload-form" onSubmit={handleUpload}>
                <div>
                  <label htmlFor="upload-campus">Campus</label>
                  <select
                    id="upload-campus"
                    required
                    disabled={uploadBusy || campuses.length === 0}
                    value={uploadCampusId}
                    onChange={event => setUploadCampusId(event.target.value)}
                  >
                    <option value="">Select campus…</option>
                    {campuses.map(campus => (
                      <option key={campus.id} value={campus.id}>
                        {campus.name}
                      </option>
                    ))}
                  </select>
                </div>

                <div>
                  <label htmlFor="upload-mapping">Column mapping</label>
                  <select
                    id="upload-mapping"
                    disabled={uploadBusy || mappingsState.loading}
                    value={uploadMappingId}
                    onChange={event => setUploadMappingId(event.target.value)}
                  >
                    <option value="">Default active mapping</option>
                    {activeMappings.map(mapping => (
                      <option key={mapping.id} value={mapping.id}>
                        {mapping.name}
                        {mapping.isDefault ? ' (Default)' : ''}
                        {!mapping.realFileValidated ? ' [Unvalidated]' : ''}
                      </option>
                    ))}
                  </select>
                </div>

                <div>
                  <label htmlFor="upload-csv-file">CSV file</label>
                  <input
                    ref={fileInputRef}
                    id="upload-csv-file"
                    type="file"
                    accept=".csv,text/csv"
                    disabled={uploadBusy}
                    onChange={event => setUploadFile(event.target.files?.[0] || null)}
                  />
                </div>

                <button
                  type="submit"
                  className="button"
                  disabled={uploadBusy || !uploadCampusId || !uploadFile}
                >
                  {uploadBusy ? 'Staging CSV…' : 'Run dry-run upload'}
                </button>

                {isGrades && (
                  <div className="imports-grade-context">
                    <div>
                      <label htmlFor="upload-academic-year">Academic year (optional)</label>
                      <select
                        id="upload-academic-year"
                        disabled={uploadBusy}
                        value={uploadYearId}
                        onChange={event => {
                          setUploadYearId(event.target.value);
                          setUploadTermId('');
                        }}
                      >
                        <option value="">From CSV column</option>
                        {academicYears.map(year => (
                          <option key={year.id} value={year.id}>
                            {year.label || year.name || `Year #${year.id}`}
                          </option>
                        ))}
                      </select>
                    </div>

                    <div>
                      <label htmlFor="upload-term">Academic term (optional)</label>
                      <select
                        id="upload-term"
                        disabled={uploadBusy}
                        value={uploadTermId}
                        onChange={event => setUploadTermId(event.target.value)}
                      >
                        <option value="">From CSV column</option>
                        {filteredTerms.map(term => (
                          <option key={term.id} value={term.id}>
                            {term.academicYear ? `${term.academicYear} / ` : ''}
                            {term.name}
                          </option>
                        ))}
                      </select>
                    </div>

                    <div>
                      <label htmlFor="upload-grade-level">Grade level (optional)</label>
                      <input
                        id="upload-grade-level"
                        maxLength={30}
                        disabled={uploadBusy}
                        value={uploadGradeLevel}
                        onChange={event => setUploadGradeLevel(event.target.value)}
                        placeholder="e.g. Grade 11"
                      />
                    </div>

                    <div>
                      <label htmlFor="upload-section">Section (optional)</label>
                      <input
                        id="upload-section"
                        maxLength={150}
                        disabled={uploadBusy}
                        value={uploadSection}
                        onChange={event => setUploadSection(event.target.value)}
                        placeholder="e.g. 11-STEM-A"
                      />
                    </div>
                  </div>
                )}
              </form>
            </div>
          </section>

          <section className="panel">
            <h2>{isGrades ? 'Grade matrix batch queue' : 'Student CSV batch queue'}</h2>
            <div className="panel-body">
              <div className="imports-filters">
                <div>
                  <label htmlFor="batch-campus-filter">Campus</label>
                  <select
                    id="batch-campus-filter"
                    value={filterCampusId}
                    onChange={event => {
                      setFilterCampusId(event.target.value);
                      setPage(0);
                    }}
                  >
                    <option value="">All authorized campuses</option>
                    {campuses.map(campus => (
                      <option key={campus.id} value={campus.id}>
                        {campus.name}
                      </option>
                    ))}
                  </select>
                </div>

                <div>
                  <label htmlFor="batch-status-filter">Batch status</label>
                  <select
                    id="batch-status-filter"
                    value={filterStatus}
                    onChange={event => {
                      setFilterStatus(event.target.value);
                      setPage(0);
                    }}
                  >
                    <option value="">All statuses</option>
                    {BATCH_STATUSES.map(value => (
                      <option key={value} value={value}>
                        {formatStatus(value)}
                      </option>
                    ))}
                  </select>
                </div>

                <button
                  type="button"
                  className="button button-outline"
                  disabled={batchesState.loading}
                  onClick={batchesState.reload}
                >
                  Refresh queue
                </button>
              </div>

              <Failure error={batchesState.error} reload={batchesState.reload} />
              {batchesState.loading && <p role="status">Loading import batches…</p>}

              {!batchesState.loading && !batchesState.error && (batchesState.data?.items || []).length === 0 && (
                <div className="empty-state">
                  <h3>No import batches found</h3>
                  <p>
                    {isGrades
                      ? 'Upload a historical grade matrix CSV above to stage your first dry-run batch.'
                      : 'Upload a legacy student CSV above to stage your first dry-run batch.'}
                  </p>
                </div>
              )}

              {(batchesState.data?.items || []).length > 0 && (
                <div className="setup-table-wrap">
                  <table className="setup-table">
                    <thead>
                      <tr>
                        <th>Batch / File</th>
                        <th>Campus</th>
                        <th>Status</th>
                        <th>Row counts</th>
                        <th>Action</th>
                      </tr>
                    </thead>
                    <tbody>
                      {(batchesState.data?.items || []).map(item => (
                        <tr
                          key={item.id}
                          className={String(item.id) === selectedBatchId ? 'selected-row' : undefined}
                        >
                          <td>
                            <strong>{item.sourceName}</strong>
                            <small>
                              Batch #{item.id} · {formatDate(item.startedAt)}
                            </small>
                          </td>
                          <td>{campusNameFor(item.campusId)}</td>
                          <td>
                            <span className="imports-status-row">
                              <StatusBadge value={item.status} />
                              {item.discarded && <StatusBadge value="discarded" label="Discarded" />}
                              {item.voidedAt && <StatusBadge value="voided" label="Voided" />}
                            </span>
                          </td>
                          <td>
                            <strong>{item.sourceCount ?? 0} total</strong>
                            <div className="imports-count-summary">
                              <span>{item.validCount ?? 0} valid</span>
                              <span>· {item.flaggedCount ?? 0} flagged</span>
                              <span>· {item.invalidCount ?? 0} invalid</span>
                              <span>· {item.skippedCount ?? 0} skipped</span>
                              <span>· {item.importedCount ?? 0} imported</span>
                            </div>
                          </td>
                          <td>
                            <button
                              type="button"
                              className="button button-outline"
                              onClick={() => selectBatch(item.id)}
                            >
                              {item.status === 'validated' && !item.discarded && !item.voidedAt
                                ? `Review batch #${item.id}`
                                : `View batch #${item.id}`}
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
                total={batchesState.data?.total ?? 0}
                onChange={setPage}
                disabled={batchesState.loading}
              />
            </div>
          </section>

          {selectedBatchId && /^\d+$/.test(selectedBatchId) ? (
            <BatchDetailWorkspace
              key={`${kind}-${selectedBatchId}`}
              kind={kind}
              batchId={selectedBatchId}
              campusNameFor={campusNameFor}
              isAdmin={isAdmin}
              onBatchChanged={batchesState.reload}
            />
          ) : (
            <p className="notice">
              Select a batch from the queue above or upload a CSV file to review staged rows, resolve flagged records, commit, discard, or void.
            </p>
          )}
        </>
      )}

      {activeTab === 'mappings' && (
        <ImportMappingsPanel
          kind={kind}
          mappings={mappings}
          loading={mappingsState.loading}
          error={mappingsState.error}
          reload={mappingsState.reload}
          isAdmin={isAdmin}
        />
      )}
    </div>
  );
}
