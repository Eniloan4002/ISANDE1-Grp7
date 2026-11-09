import { useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';
import { listDuplicateFlags, resolveDuplicateFlag } from './api/duplicates.js';

const FIELD_ROWS = [
  ['fullName', 'Full name'],
  ['dateOfBirth', 'Date of birth'],
  ['sex', 'Sex'],
  ['lrn', 'LRN'],
  ['previousSchool', 'Previous school'],
  ['guardianContact', 'Guardian contact'],
  ['status', 'Status']
];
const MATCH_TYPE_LABEL = { LRN_EXACT: 'Exact LRN match', NAME_DOB: 'Similar name and same birth date' };
const STATUS_LABEL = { OPEN: 'For review', MERGED: 'Merged', REJECTED: 'Rejected' };
const STATUS_CLASS = { OPEN: 'status status-warning', MERGED: 'status status-success', REJECTED: 'status' };
const percent = value => Math.round(value * 100);

export default function DuplicateRecords() {
  const [params] = useSearchParams();
  const deepLink = params.get('app');
  const [flags, setFlags] = useState([]);
  const [selectedId, setSelectedId] = useState(null);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('all');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [confirming, setConfirming] = useState(null); // 'MERGE' | 'REJECT' | null
  const dialog = useRef(null);
  const trigger = useRef(null);
  const comparePanel = useRef(null);
  const mounted = useRef(true);

  async function load() {
    setLoading(true);
    setError('');
    try {
      const rows = await listDuplicateFlags();
      if (mounted.current) setFlags(rows);
    } catch (problem) {
      if (mounted.current) setError(problem.message);
    } finally {
      if (mounted.current) setLoading(false);
    }
  }

  useEffect(() => {
    mounted.current = true;
    load();
    return () => { mounted.current = false; };
  }, []);

  useEffect(() => {
    if (notice) {
      const timer = setTimeout(() => setNotice(''), 5000);
      return () => clearTimeout(timer);
    }
  }, [notice]);

  // Deep link from the Enrollment Queue / Evaluate screen: /duplicates?app=APP-2026-0153
  useEffect(() => {
    if (!deepLink || !flags.length) return;
    const match = flags.find(flag => flag.applicationId === deepLink);
    if (match) setSelectedId(match.id);
  }, [deepLink, flags.length]);

  useEffect(() => {
    if (selectedId !== null) comparePanel.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
  }, [selectedId]);

  useEffect(() => {
    if (confirming) {
      dialog.current.showModal();
      return () => { dialog.current?.close(); trigger.current?.focus(); };
    }
  }, [confirming]);

  const stats = useMemo(() => ({
    active: flags.filter(flag => flag.status === 'OPEN').length,
    resolved: flags.filter(flag => flag.status !== 'OPEN').length,
    high: flags.filter(flag => flag.confidence >= 0.9).length,
    total: flags.length
  }), [flags]);

  const visible = useMemo(() => {
    const term = search.trim().toLowerCase();
    return flags.filter(flag => {
      const open = flag.status === 'OPEN';
      if (statusFilter === 'open' && !open) return false;
      if (statusFilter === 'resolved' && open) return false;
      if (!term) return true;
      return [flag.incoming.fullName, flag.applicationId, flag.existing.fullName, flag.existing.studentId]
        .some(value => String(value || '').toLowerCase().includes(term));
    });
  }, [flags, search, statusFilter]);

  const selected = flags.find(flag => flag.id === selectedId) || null;

  async function resolve() {
    setBusy(true);
    setError('');
    try {
      await resolveDuplicateFlag(selected.id, confirming, selected.version);
      const merged = confirming === 'MERGE';
      setConfirming(null);
      await load();
      setNotice(merged
        ? 'Records merged. Logged in the audit trail.'
        : 'Marked as not a duplicate. The application continues as a new student record.');
    } catch (problem) {
      setConfirming(null);
      setError(problem.message);
    } finally {
      setBusy(false);
    }
  }

  return <>
    <div className="page-heading">
      <h1>Duplicate Records</h1>
      <p>The system flags possible duplicates against the centralized database. Review both records side by side and resolve each flag by merging or rejecting it.</p>
    </div>

    {error && <div className="notice notice-error" role="alert">{error}
      <button className="button button-outline retry-button" onClick={load} disabled={busy || loading}>Reload records</button>
    </div>}
    {loading && <p role="status">Loading duplicate flags…</p>}

    {!loading && <>
      <div className="dup-stats" aria-label="Duplicate statistics">
        <div className="dup-stat"><strong>{stats.active}</strong><span>Active flags</span></div>
        <div className="dup-stat"><strong>{stats.resolved}</strong><span>Resolved</span></div>
        <div className="dup-stat"><strong>{stats.high}</strong><span>High confidence (90%+)</span></div>
        <div className="dup-stat"><strong>{stats.total}</strong><span>Total flagged pairs</span></div>
      </div>

      <section className="panel" aria-labelledby="flags-title">
        <h2 id="flags-title">Flagged duplicate pairs</h2>
        <div className="panel-body">
          <p className="field-help">Select a pair to compare the incoming application against the existing student record.</p>
          <div className="dup-toolbar">
            <div>
              <label htmlFor="dup-search">Search</label>
              <input id="dup-search" type="search" placeholder="Filter by name or ID…" value={search} onChange={event => setSearch(event.target.value)} />
            </div>
            <div>
              <label htmlFor="dup-status">Status</label>
              <select id="dup-status" value={statusFilter} onChange={event => setStatusFilter(event.target.value)}>
                <option value="all">All</option>
                <option value="open">For review</option>
                <option value="resolved">Resolved</option>
              </select>
            </div>
          </div>

          {visible.length ? <div className="setup-table-wrap"><table className="setup-table">
            <thead><tr>
              <th scope="col">Incoming application</th><th scope="col">App ID</th><th scope="col">Existing record</th>
              <th scope="col">Student ID</th><th scope="col">Match</th><th scope="col">Status</th><th scope="col">Action</th>
            </tr></thead>
            <tbody>{visible.map(flag => <tr key={flag.id} aria-selected={flag.id === selectedId}>
              <td><strong>{flag.incoming.fullName}</strong></td>
              <td>{flag.applicationId}</td>
              <td><strong>{flag.existing.fullName}</strong></td>
              <td>{flag.existing.studentId || '—'}</td>
              <td>{percent(flag.confidence)}%</td>
              <td><span className={STATUS_CLASS[flag.status]}>{STATUS_LABEL[flag.status]}</span></td>
              <td><button className="button button-outline" aria-label={`${flag.status === 'OPEN' ? 'Review' : 'View'} ${flag.incoming.fullName}`} onClick={() => setSelectedId(flag.id)}>{flag.status === 'OPEN' ? 'Review' : 'View'}</button></td>
            </tr>)}</tbody>
          </table></div> : <div className="empty-state">
            <h3>{flags.length ? 'No pairs match the current filters' : 'No duplicate flags'}</h3>
            <p>{flags.length ? `${flags.length} total.` : 'Flags appear here when an application matches an existing record.'}</p>
          </div>}
        </div>
      </section>

      {selected && <section className="panel" ref={comparePanel} aria-labelledby="compare-title">
        <h2 id="compare-title">Side-by-side review <span className="dup-meter">{percent(selected.confidence)}% match confidence · {MATCH_TYPE_LABEL[selected.matchType]}</span></h2>
        <div className="dup-compare">
          {[['Incoming application', selected.incoming], ['Existing record', selected.existing]].map(([title, record]) => <div className="dup-col" key={title}>
            <h3>{title}</h3>
            <p className="field-help">{record.summary}</p>
            <dl>{FIELD_ROWS.map(([key, label]) => {
              const matched = selected.matchedFields.includes(key);
              return <div key={key} className={`dup-field${matched ? ' is-match' : ''}`}>
                <dt>{label}</dt><dd>{record[key] || '—'}</dd>
                {matched && <span className="match-tag">Match</span>}
              </div>;
            })}</dl>
          </div>)}
        </div>
        <div className="panel-body">
          {selected.status === 'OPEN'
            ? <>
              <p><strong>Resolve duplicate record.</strong> Matching fields are highlighted.</p>
              <div className="component-row">
                <button className="button button-outline" disabled={busy} onClick={event => { trigger.current = event.currentTarget; setConfirming('REJECT'); }}>Reject — not a duplicate</button>
                <button className="button" disabled={busy} onClick={event => { trigger.current = event.currentTarget; setConfirming('MERGE'); }}>Merge records</button>
              </div>
            </>
            : <p className="notice" role="status">Resolution: <strong>{STATUS_LABEL[selected.status]}</strong>. {selected.status === 'MERGED'
              ? 'Application data was merged into the existing student record.'
              : 'Records are distinct; the application continues as a new student record.'} Logged in the audit trail.</p>}
        </div>
      </section>}
    </>}

    <div className="toast" role="status" aria-live="polite">{notice}</div>

    {confirming && selected && <dialog ref={dialog} aria-labelledby="resolve-title" onCancel={event => { event.preventDefault(); if (!busy) setConfirming(null); }}>
      <h2 id="resolve-title">{confirming === 'MERGE' ? 'Merge these records?' : 'Mark as not a duplicate?'}</h2>
      <p>{confirming === 'MERGE'
        ? `Merge ${selected.applicationId} into student record ${selected.existing.studentId}? The existing student ID is preserved and the action is logged.`
        : `Treat ${selected.applicationId} as a different learner? The application continues as a new student record.`}</p>
      <div className="component-row">
        <button className="button button-outline" disabled={busy} onClick={() => setConfirming(null)}>Cancel</button>
        <button className="button" disabled={busy} onClick={resolve}>{busy ? 'Saving…' : confirming === 'MERGE' ? 'Confirm merge' : 'Confirm'}</button>
      </div>
    </dialog>}
  </>;
}