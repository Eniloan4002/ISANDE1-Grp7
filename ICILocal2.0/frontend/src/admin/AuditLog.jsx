import { useEffect, useMemo, useState } from 'react';
import { Failure, Pager, useDebouncedValue } from '../EnrollmentWorkspace.jsx';
import {
  computeAuditDiff,
  formatAuditValue,
  formatRecordId,
  formatRecordType,
  loadAuditLogs,
  loadAuditReferenceData,
} from './auditLogApi.js';
import './audit-log.css';

const PAGE_SIZE = 25;

function actionTone(operation) {
  const op = String(operation || '').toUpperCase();
  if (op === 'INSERT') return 'success';
  if (op === 'DELETE') return 'danger';
  return 'warning';
}

function formatTimestamp(iso) {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return String(iso);
  return date.toLocaleString();
}

export default function AuditLog() {
  const [reference, setReference] = useState({
    campuses: [],
    users: [],
    recordTypes: [],
    operations: ['INSERT', 'UPDATE', 'DELETE'],
  });
  const [refError, setRefError] = useState(null);
  const [refReload, setRefReload] = useState(0);

  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [actorId, setActorId] = useState('');
  const [recordType, setRecordType] = useState('');
  const [campusId, setCampusId] = useState('');
  const [operation, setOperation] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [reloadTick, setReloadTick] = useState(0);

  const debouncedSearch = useDebouncedValue(search);

  const [entries, setEntries] = useState([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const [selectedId, setSelectedId] = useState(null);
  const [changedOnly, setChangedOnly] = useState(false);

  const dateRangeInvalid = Boolean(from && to && to < from);

  useEffect(() => {
    const controller = new AbortController();
    setRefError(null);
    loadAuditReferenceData(controller.signal)
      .then(data => {
        if (controller.signal.aborted) return;
        setReference({
          campuses: Array.isArray(data?.campuses) ? data.campuses : [],
          users: Array.isArray(data?.users) ? data.users : [],
          recordTypes: Array.isArray(data?.recordTypes) ? data.recordTypes : [],
          operations: Array.isArray(data?.operations) && data.operations.length
            ? data.operations
            : ['INSERT', 'UPDATE', 'DELETE'],
        });
      })
      .catch(err => {
        if (err?.name === 'AbortError' || controller.signal.aborted) return;
        setRefError(err);
      });
    return () => controller.abort();
  }, [refReload]);

  useEffect(() => {
    if (dateRangeInvalid) {
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    loadAuditLogs(
      {
        from,
        to,
        actorId,
        recordType,
        campusId,
        operation,
        search: debouncedSearch,
        page,
        size: PAGE_SIZE,
      },
      controller.signal
    )
      .then(data => {
        if (controller.signal.aborted) return;
        const items = Array.isArray(data?.items) ? data.items : [];
        setEntries(items);
        setTotal(Number(data?.total ?? items.length));
        setSelectedId(prev => (items.some(item => item.id === prev) ? prev : items[0]?.id ?? null));
      })
      .catch(err => {
        if (err?.name === 'AbortError' || controller.signal.aborted) return;
        setError(err);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [from, to, actorId, recordType, campusId, operation, debouncedSearch, page, reloadTick, dateRangeInvalid]);

  const selectedEntry = useMemo(
    () => entries.find(item => item.id === selectedId) || null,
    [entries, selectedId]
  );

  const diffRows = useMemo(() => {
    if (!selectedEntry) return [];
    const allRows = computeAuditDiff(selectedEntry.oldData, selectedEntry.newData);
    return changedOnly ? allRows.filter(row => row.changed) : allRows;
  }, [selectedEntry, changedOnly]);

  const hasActiveFilters = Boolean(from || to || actorId || recordType || campusId || operation || search.trim());

  function clearFilters() {
    setFrom('');
    setTo('');
    setActorId('');
    setRecordType('');
    setCampusId('');
    setOperation('');
    setSearch('');
    setPage(0);
  }

  return (
    <div className="audit-page">
      <header className="audit-header">
        <div>
          <p className="eyebrow">System Administrator · Immutable Activity Trail</p>
          <h1>Audit Log</h1>
          <p>Inspect system changes across records, users, and campuses with before-and-after snapshots.</p>
        </div>
        <span className="audit-readonly-badge">Read-only</span>
      </header>

      <Failure error={refError} reload={() => setRefReload(v => v + 1)} />

      <section className="panel audit-filters-panel" aria-labelledby="audit-filters-heading">
        <div className="audit-detail-head">
          <h2 id="audit-filters-heading">Filter audit activity</h2>
        </div>

        <div className="audit-filters-grid">
          <label className="audit-filter-field">
            From date
            <input
              type="date"
              aria-label="From date"
              value={from}
              onChange={event => {
                setFrom(event.target.value);
                setPage(0);
              }}
            />
          </label>

          <label className="audit-filter-field">
            To date
            <input
              type="date"
              aria-label="To date"
              value={to}
              onChange={event => {
                setTo(event.target.value);
                setPage(0);
              }}
            />
          </label>

          <label className="audit-filter-field">
            User
            <select
              aria-label="Filter by user"
              value={actorId}
              onChange={event => {
                setActorId(event.target.value);
                setPage(0);
              }}
            >
              <option value="">All users</option>
              {reference.users.map(user => (
                <option key={user.id} value={String(user.id)}>
                  {user.fullName} ({user.username})
                </option>
              ))}
            </select>
          </label>

          <label className="audit-filter-field">
            Record type
            <select
              aria-label="Filter by record type"
              value={recordType}
              onChange={event => {
                setRecordType(event.target.value);
                setPage(0);
              }}
            >
              <option value="">All record types</option>
              {reference.recordTypes.map(type => (
                <option key={type} value={type}>
                  {formatRecordType(type)} ({type})
                </option>
              ))}
            </select>
          </label>

          <label className="audit-filter-field">
            Campus
            <select
              aria-label="Filter by campus"
              value={campusId}
              onChange={event => {
                setCampusId(event.target.value);
                setPage(0);
              }}
            >
              <option value="">All campuses</option>
              {reference.campuses.map(campus => (
                <option key={campus.id} value={String(campus.id)}>
                  {campus.name}
                </option>
              ))}
            </select>
          </label>

          <label className="audit-filter-field">
            Action
            <select
              aria-label="Filter by action"
              value={operation}
              onChange={event => {
                setOperation(event.target.value);
                setPage(0);
              }}
            >
              <option value="">All actions</option>
              {reference.operations.map(op => (
                <option key={op} value={op}>
                  {op}
                </option>
              ))}
            </select>
          </label>

          <label className="audit-filter-field">
            Search
            <input
              type="search"
              aria-label="Search audit log"
              maxLength={200}
              placeholder="Record ID, user, or table"
              value={search}
              onChange={event => {
                setSearch(event.target.value);
                setPage(0);
              }}
            />
          </label>
        </div>

        {dateRangeInvalid && (
          <div className="notice notice-error" role="alert">
            End date must be on or after the start date.
          </div>
        )}

        <div className="audit-filter-actions">
          <span className="audit-result-meta">
            {loading ? 'Loading audit records…' : `${total} audit ${total === 1 ? 'entry' : 'entries'} matched`}
          </span>
          <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
            {hasActiveFilters && (
              <button type="button" className="button button-outline" onClick={clearFilters}>
                Clear filters
              </button>
            )}
            <button
              type="button"
              className="button button-outline"
              disabled={loading || dateRangeInvalid}
              onClick={() => setReloadTick(v => v + 1)}
            >
              Refresh
            </button>
          </div>
        </div>
      </section>

      <Failure error={error} reload={() => setReloadTick(v => v + 1)} />

      <div className={`audit-workspace${selectedEntry ? ' has-selection' : ''}`}>
        <section className="panel" aria-labelledby="audit-entries-heading">
          <h2 id="audit-entries-heading">Activity entries</h2>

          {loading && !entries.length && <p role="status">Loading audit entries…</p>}

          {!loading && !error && !dateRangeInvalid && entries.length === 0 && (
            <div className="empty-state">
              <h3>No audit entries found</h3>
              <p>Try widening the date range or clearing active filters.</p>
            </div>
          )}

          {entries.length > 0 && (
            <>
              {/* Desktop table */}
              <div className="audit-table-wrap audit-desktop-only">
                <table className="audit-table">
                  <thead>
                    <tr>
                      <th>When</th>
                      <th>Who</th>
                      <th>Action</th>
                      <th>Record Type</th>
                      <th>Record ID</th>
                      <th>Campus</th>
                      <th>Changes</th>
                    </tr>
                  </thead>
                  <tbody>
                    {entries.map(entry => {
                      const isSelected = entry.id === selectedEntry?.id;
                      const formattedId = formatRecordId(entry.recordId);
                      return (
                        <tr key={entry.id} className={isSelected ? 'is-selected' : undefined}>
                          <td>{formatTimestamp(entry.occurredAt)}</td>
                          <td>
                            <span className="audit-cell-primary">
                              {entry.actorName || 'System / Portal'}
                            </span>
                            {entry.actorUsername && (
                              <span className="audit-cell-sub">@{entry.actorUsername}</span>
                            )}
                          </td>
                          <td>
                            <span className={`status status-${actionTone(entry.operation)}`}>
                              {entry.operation}
                            </span>
                          </td>
                          <td>
                            <span className="audit-cell-primary">{formatRecordType(entry.recordType)}</span>
                            <span className="audit-cell-sub audit-mono">{entry.recordType}</span>
                          </td>
                          <td className="audit-mono">{formattedId}</td>
                          <td>{entry.campusName || 'Global / N/A'}</td>
                          <td>
                            <button
                              type="button"
                              className="button button-outline"
                              aria-pressed={isSelected}
                              aria-label={`Inspect audit entry ${entry.id}`}
                              onClick={() => setSelectedId(entry.id)}
                            >
                              {isSelected ? 'Viewing' : 'Before / After'}
                            </button>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>

              {/* Phone cards */}
              <div className="audit-mobile-cards" aria-label="Audit entries list">
                {entries.map(entry => {
                  const isSelected = entry.id === selectedEntry?.id;
                  return (
                    <article
                      key={entry.id}
                      className={`audit-mobile-card${isSelected ? ' is-selected' : ''}`}
                    >
                      <div className="audit-mobile-card-top">
                        <span className={`status status-${actionTone(entry.operation)}`}>
                          {entry.operation}
                        </span>
                        <span className="audit-cell-sub">{formatTimestamp(entry.occurredAt)}</span>
                      </div>
                      <dl className="audit-mobile-meta">
                        <div>
                          <dt>Who</dt>
                          <dd>{entry.actorName || 'System / Portal'}</dd>
                        </div>
                        <div>
                          <dt>Record Type</dt>
                          <dd>{formatRecordType(entry.recordType)}</dd>
                        </div>
                        <div>
                          <dt>Record ID</dt>
                          <dd className="audit-mono">{formatRecordId(entry.recordId)}</dd>
                        </div>
                        <div>
                          <dt>Campus</dt>
                          <dd>{entry.campusName || 'Global / N/A'}</dd>
                        </div>
                      </dl>
                      <button
                        type="button"
                        className="button button-outline"
                        aria-pressed={isSelected}
                        onClick={() => setSelectedId(entry.id)}
                      >
                        {isSelected ? 'Viewing Before / After' : 'View Before / After'}
                      </button>
                    </article>
                  );
                })}
              </div>

              <Pager
                page={page}
                total={total}
                size={PAGE_SIZE}
                onChange={setPage}
                disabled={loading}
              />
            </>
          )}
        </section>

        {selectedEntry && (
          <aside className="panel audit-detail-panel" aria-labelledby="audit-detail-heading">
            <div className="audit-detail-head">
              <div>
                <p className="eyebrow">Before &amp; After Snapshot</p>
                <h2 id="audit-detail-heading">
                  {formatRecordType(selectedEntry.recordType)} ({formatRecordId(selectedEntry.recordId)})
                </h2>
              </div>
              <span className={`status status-${actionTone(selectedEntry.operation)}`}>
                {selectedEntry.operation}
              </span>
            </div>

            <dl className="audit-detail-meta">
              <div>
                <dt>When</dt>
                <dd>{formatTimestamp(selectedEntry.occurredAt)}</dd>
              </div>
              <div>
                <dt>Who</dt>
                <dd>
                  {selectedEntry.actorName || 'System / Portal'}
                  {selectedEntry.actorUsername ? ` (@${selectedEntry.actorUsername})` : ''}
                </dd>
              </div>
              <div>
                <dt>Table</dt>
                <dd className="audit-mono">{selectedEntry.recordType}</dd>
              </div>
              <div>
                <dt>Campus</dt>
                <dd>{selectedEntry.campusName || 'Global / N/A'}</dd>
              </div>
              <div>
                <dt>Audit ID</dt>
                <dd className="audit-mono">#{selectedEntry.id}</dd>
              </div>
              <div>
                <dt>Raw Key</dt>
                <dd className="audit-mono">{selectedEntry.recordId}</dd>
              </div>
            </dl>

            <div className="audit-diff-controls">
              <label className="audit-toggle">
                <input
                  type="checkbox"
                  checked={changedOnly}
                  onChange={event => setChangedOnly(event.target.checked)}
                />
                <span>Show changed fields only</span>
              </label>
              <span className="audit-cell-sub">
                {diffRows.length} {diffRows.length === 1 ? 'field' : 'fields'} shown
              </span>
            </div>

            {diffRows.length === 0 ? (
              <p className="field-help">No field differences to display for this snapshot.</p>
            ) : (
              <div className="audit-diff-table-wrap">
                <table className="audit-diff-table">
                  <thead>
                    <tr>
                      <th>Field</th>
                      <th>Before</th>
                      <th>After</th>
                    </tr>
                  </thead>
                  <tbody>
                    {diffRows.map(row => (
                      <tr key={row.field} className={row.changed ? 'audit-diff-row-changed' : undefined}>
                        <td className="audit-mono">{row.field}</td>
                        <td>
                          <pre className={`audit-diff-pre${row.changed ? ' audit-diff-before' : ''}`}>
                            {formatAuditValue(row.before)}
                          </pre>
                        </td>
                        <td>
                          <pre className={`audit-diff-pre${row.changed ? ' audit-diff-after' : ''}`}>
                            {formatAuditValue(row.after)}
                          </pre>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </aside>
        )}
      </div>
    </div>
  );
}
