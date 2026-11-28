// ICI2-73 Cutover Reconciliation screen for the ICI2-40 reconciliation API.
import { useEffect, useId, useMemo, useState } from 'react';
import { Link } from 'react-router';
import { Failure, useResource } from '../EnrollmentWorkspace.jsx';
import { useAuth } from '../auth/AuthContext.jsx';
import { Button, Drawer, FilterChip, StatTile, StatusChip } from '../ui/components.jsx';
import { batchState, discrepancyLabels, loadReconciliation, signOffBatch, sourceLabels } from './reconciliationApi.js';
import '../academic/academic-screens.css';
import '../academic/records-screens.css';
import './reconciliation.css';

const NOTE_LIMIT = 1000;
const stateFilters = [
  { key: '', label: 'All batches' },
  { key: 'ready', label: 'Ready to sign off' },
  { key: 'blocked', label: 'Has discrepancies' },
  { key: 'signed', label: 'Signed off' },
];

const count = value => Number(value ?? 0).toLocaleString();
const sourceLabel = type => sourceLabels[type] ?? String(type ?? 'Unknown source').replaceAll('_', ' ');

function formatTime(value) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

function BatchStatus({ batch }) {
  const state = batchState(batch);
  if (state === 'signed') return <StatusChip tone="success">Signed off</StatusChip>;
  if (state === 'ready') return <StatusChip tone="info">Ready to sign off</StatusChip>;
  if (batch.status === 'staged' || batch.status === 'validated') return <StatusChip tone="attention">Not committed</StatusChip>;
  const issues = batch.discrepancies?.length ?? 0;
  return <StatusChip tone="blocked">{issues === 1 ? '1 discrepancy' : `${issues} discrepancies`}</StatusChip>;
}

/** Legacy Import Review handles the student and grade CSV batches; the other sources have no review screen yet. */
function reviewLink(batch) {
  if (batch.sourceType === 'legacy_student_csv') return `/migration/legacy-imports?batchId=${batch.id}`;
  if (batch.sourceType === 'legacy_grade_matrix_csv') return `/migration/legacy-imports?type=grades&batchId=${batch.id}`;
  return null;
}

function SignOffForm({ batch, onSignedOff }) {
  const noteId = useId();
  const [note, setNote] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      await signOffBatch(batch.id, { version: batch.version, expectedImportedCount: batch.importedCount, note });
      onSignedOff(batch, false);
    } catch (failure) {
      setError(failure.message || 'The batch could not be signed off.');
      // A refusal usually means the batch changed, so show the server's current numbers.
      if (failure.status === 409) onSignedOff(batch, true);
    } finally {
      setBusy(false);
    }
  }

  return <form className="rc-signoff" aria-label="Sign off batch" onSubmit={submit}>
    <label className="rs-field" htmlFor={noteId}>Note (optional)</label>
    <textarea id={noteId} rows={3} maxLength={NOTE_LIMIT} value={note} disabled={busy}
      placeholder="For example: totals checked against the legacy enrolment ledger"
      onChange={event => setNote(event.target.value)} />
    <p className="ac-muted rs-flush">{note.length} / {NOTE_LIMIT} characters</p>
    <label className="rc-confirm">
      <input type="checkbox" checked={confirmed} disabled={busy} onChange={event => setConfirmed(event.target.checked)} />
      <span>I checked that all {count(batch.sourceCount)} legacy source rows are imported. Signing off records my name and the time, and cannot be undone.</span>
    </label>
    {error && <div className="notice notice-error" role="alert">{error}</div>}
    <Button type="submit" disabled={!confirmed || busy}>{busy ? 'Signing off…' : 'Sign off batch'}</Button>
  </form>;
}

function BatchDrawer({ batch, userId, onClose, onSignedOff }) {
  const link = batch ? reviewLink(batch) : null;
  const isGrades = batch?.sourceType === 'legacy_grade_matrix_csv';
  return <Drawer open={Boolean(batch)} onClose={onClose} title={batch ? `Batch #${batch.id}` : ''}>
    {batch && <div className="rc-drawer">
      <div>
        <p className="rc-source">{sourceLabel(batch.sourceType)} · {batch.campus}</p>
        <p className="rc-file">{batch.sourceName}</p>
        <BatchStatus batch={batch} />
      </div>

      <section aria-labelledby="rc-totals">
        <h3 id="rc-totals" className="ac-subheading">Legacy source compared with imported</h3>
        <div className="rc-compare">
          <div><span>Legacy source rows</span><b>{count(batch.sourceCount)}</b></div>
          <div><span>Imported rows</span><b>{count(batch.importedCount)}</b></div>
          <div className={batch.unimportedCount > 0 ? 'rc-gap' : undefined}><span>Not imported</span><b>{count(batch.unimportedCount)}</b></div>
        </div>
        <dl className="ac-fields rc-breakdown">
          <div className="ac-field"><dt>Valid</dt><dd>{count(batch.validCount)}</dd></div>
          <div className="ac-field"><dt>Invalid</dt><dd>{count(batch.invalidCount)}</dd></div>
          <div className="ac-field"><dt>Flagged as possible duplicates</dt><dd>{count(batch.flaggedCount)}</dd></div>
          <div className="ac-field"><dt>Skipped</dt><dd>{count(batch.skippedCount)}</dd></div>
          {isGrades && <div className="ac-field"><dt>Live grade records</dt><dd>{count(batch.liveGradeRecords)}</dd></div>}
          {isGrades && <div className="ac-field"><dt>Voided grade records</dt><dd>{count(batch.voidedGradeRecords)}</dd></div>}
          <div className="ac-field"><dt>Imported on</dt><dd>{formatTime(batch.importedAt)}</dd></div>
        </dl>
      </section>

      <section aria-labelledby="rc-issues">
        <h3 id="rc-issues" className="ac-subheading">Discrepancies</h3>
        {batch.discrepancies?.length
          ? <ul className="rc-issues">{batch.discrepancies.map(item => <li key={item.code}>
            <strong>{discrepancyLabels[item.code] ?? item.code}</strong>
            <span>{item.detail}</span>
          </li>)}</ul>
          : <p className="ac-muted">None. Every legacy source row is accounted for.</p>}
        {link && batch.discrepancies?.length > 0 && <p><Link to={link}>Open this batch in Legacy Import Review</Link></p>}
      </section>

      <section aria-labelledby="rc-signoff-title">
        <h3 id="rc-signoff-title" className="ac-subheading">Sign-off</h3>
        {batch.status === 'reconciled'
          ? <p>Signed off by {batch.signedOffBy != null && String(batch.signedOffBy) === String(userId) ? 'you' : `staff account #${batch.signedOffBy ?? '—'}`} on {formatTime(batch.signedOffAt)}.</p>
          : batch.canSignOff
            ? <SignOffForm key={`${batch.id}-${batch.version}`} batch={batch} onSignedOff={onSignedOff} />
            : <p className="ac-muted">This batch cannot be signed off until every discrepancy is resolved. Correct the rows and upload them as a new batch.</p>}
      </section>
    </div>}
  </Drawer>;
}

export default function CutoverReconciliation() {
  const { user } = useAuth();
  const reference = useResource('/api/enrollment/reference-data');
  const [campusId, setCampusId] = useState('');
  const [sourceType, setSourceType] = useState('');
  const [state, setState] = useState('');
  const [revision, setRevision] = useState(0);
  const [summary, setSummary] = useState({ status: 'loading', data: null, error: '' });
  const [selectedId, setSelectedId] = useState(null);
  const [notice, setNotice] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    setSummary(current => ({ ...current, status: 'loading', error: '' }));
    loadReconciliation(campusId, { signal: controller.signal })
      .then(data => setSummary({ status: 'ready', data, error: '' }))
      .catch(failure => {
        if (failure?.name === 'AbortError') return;
        setSummary({ status: 'error', data: null, error: failure?.message || 'The reconciliation could not be loaded.' });
      });
    return () => controller.abort();
  }, [campusId, revision]);

  const campuses = useMemo(() => {
    const listed = reference.data?.campuses ?? [];
    if (listed.length) return listed.map(item => ({ id: String(item.id), name: item.name }));
    const seen = new Map();
    for (const grant of user?.grants ?? []) if (grant.campusId != null && !seen.has(String(grant.campusId))) seen.set(String(grant.campusId), { id: String(grant.campusId), name: grant.campusName || grant.campusCode || `Campus #${grant.campusId}` });
    return [...seen.values()];
  }, [reference.data?.campuses, user?.grants]);

  const data = summary.data;
  const batches = useMemo(() => data?.batches ?? [], [data]);
  const sources = useMemo(() => [...new Set(batches.map(batch => batch.sourceType))], [batches]);
  const bySource = useMemo(() => batches.filter(batch => !sourceType || batch.sourceType === sourceType), [batches, sourceType]);
  const stateCounts = useMemo(() => {
    const counts = { '': bySource.length, ready: 0, blocked: 0, signed: 0 };
    for (const batch of bySource) counts[batchState(batch)]++;
    return counts;
  }, [bySource]);
  const visible = bySource.filter(batch => !state || batchState(batch) === state);
  const selected = batches.find(batch => batch.id === selectedId) ?? null;
  const totals = data?.totals ?? {};

  function signedOff(batch, refused) {
    if (!refused) setNotice(`Batch #${batch.id} (${batch.sourceName}) is signed off.`);
    setRevision(value => value + 1);
  }

  return <div className="enrollment-workspace ac-screen rs-screen rc-screen">
    <header className="page-heading">
      <p className="eyebrow">Data migration</p>
      <h1>Cutover Reconciliation</h1>
      <p>Compare what each legacy upload contained with what was imported, then sign off each batch. The old system can be retired once every batch is signed off.</p>
    </header>

    <section className="panel" aria-label="Reconciliation filters">
      <div className="ac-filters">
        <label>Campus<select value={campusId} onChange={event => { setCampusId(event.target.value); setSelectedId(null); }}>
          <option value="">All campuses in my scope</option>
          {campuses.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
        </select></label>
        <label>Source<select value={sourceType} onChange={event => setSourceType(event.target.value)}>
          <option value="">All sources</option>
          {sources.map(type => <option key={type} value={type}>{sourceLabel(type)}</option>)}
        </select></label>
      </div>
    </section>

    <Failure error={summary.error} reload={() => setRevision(value => value + 1)} />
    {summary.status === 'loading' && !data && <p role="status">Loading reconciliation…</p>}
    {notice && <p className="notice" role="status">{notice}</p>}

    {data && <>
      {data.readyToDecommission
        ? <div className="rc-banner rc-banner-done" role="status"><strong>Every live batch is signed off.</strong> The legacy system can be retired for this scope.</div>
        : <div className="rc-banner"><strong>{count(totals.reconciledBatches)} of {count(totals.batches)} batches signed off.</strong> The legacy system stays in use until every live batch is signed off.</div>}

      <div className="ac-stats rs-stats-6" aria-label="Reconciliation totals">
        <StatTile label="Live batches" value={count(totals.batches)} hint={data.excludedBatches ? `${count(data.excludedBatches)} discarded or failed not counted` : undefined} />
        <StatTile label="Legacy source rows" value={count(totals.sourceRows)} />
        <StatTile label="Imported rows" value={count(totals.importedRows)} />
        <StatTile label="Not imported" value={count(totals.unimportedRows)} tone={totals.unimportedRows > 0 ? 'attention' : undefined} />
        <StatTile label="Ready to sign off" value={count(totals.readyForSignOff)} />
        <StatTile label="Signed off" value={count(totals.reconciledBatches)} tone={data.readyToDecommission ? 'success' : undefined} />
      </div>

      <section className="panel" aria-labelledby="rc-batches-title">
        <div className="ac-report-head">
          <h2 id="rc-batches-title">Import batches</h2>
          {summary.status === 'loading' && <span className="ac-muted" role="status">Refreshing…</span>}
        </div>
        <div className="rc-chips" role="group" aria-label="Filter by sign-off state">
          {stateFilters.map(item => <FilterChip key={item.key} selected={state === item.key} count={stateCounts[item.key]} onClick={() => setState(item.key)}>{item.label}</FilterChip>)}
        </div>
        {visible.length === 0
          ? <div className="empty-state">
            {batches.length === 0
              ? <><h3>No import batches yet</h3><p>Batches appear here after a legacy file is uploaded in Legacy Import Review.</p></>
              : <><h3>No batches match these filters</h3><p>Choose another source or sign-off state.</p></>}
          </div>
          : <div className="setup-table-wrap">
            <table className="ac-table">
              <thead><tr>
                <th scope="col">Batch</th><th scope="col">Campus</th>
                <th scope="col" className="rs-right">Legacy source</th><th scope="col" className="rs-right">Imported</th><th scope="col" className="rs-right">Not imported</th>
                <th scope="col">Status</th><th scope="col"><span className="rc-hidden">Actions</span></th>
              </tr></thead>
              <tbody>{visible.map(batch => <tr key={batch.id}>
                <td data-label="Batch"><strong>{batch.sourceName}</strong><small>#{batch.id} · {sourceLabel(batch.sourceType)}</small></td>
                <td data-label="Campus">{batch.campus}</td>
                <td data-label="Legacy source" className="rs-right">{count(batch.sourceCount)}</td>
                <td data-label="Imported" className="rs-right">{count(batch.importedCount)}</td>
                <td data-label="Not imported" className={batch.unimportedCount > 0 ? 'rs-right rc-gap' : 'rs-right'}>{count(batch.unimportedCount)}</td>
                <td data-label="Status"><BatchStatus batch={batch} /></td>
                <td data-label="Actions"><div className="ac-actions">
                  <Button variant={batchState(batch) === 'ready' ? 'primary' : 'secondary'} aria-label={`${batchState(batch) === 'ready' ? 'Review and sign off' : 'Review'} batch #${batch.id}`}
                    onClick={() => { setNotice(''); setSelectedId(batch.id); }}>
                    {batchState(batch) === 'ready' ? 'Sign off' : 'Review'}
                  </Button>
                </div></td>
              </tr>)}</tbody>
            </table>
          </div>}
      </section>
    </>}

    <BatchDrawer batch={selected} userId={user?.userId} onClose={() => setSelectedId(null)} onSignedOff={signedOff} />
  </div>;
}
