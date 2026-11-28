// ICI2-56 Withdrawal Approvals & Clearance screens (Registrar initiation, Adviser endorsement, Principal & Cashier clearance).
import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import { Confirm, Failure, Pager, useDebouncedValue } from '../EnrollmentWorkspace.jsx';
import { useAuth } from '../auth/AuthContext.jsx';
import {
  createWithdrawal,
  decideAdviserEndorsement,
  decideOfficeClearance,
  formatWithdrawalError,
  getApplicationEnrollment,
  getReleaseEligibility,
  getWithdrawal,
  listAdviserEndorsements,
  listWithdrawals,
  searchEnrolledApplications,
} from './withdrawalsApi.js';
import './withdrawals.css';

const OFFICE_LABELS = {
  adviser: '1. Teacher Adviser',
  principal: '2. Principal',
  cashier: '3. Cashiering Office',
};

const WITHDRAWAL_STATUS_META = {
  pending: { label: 'Pending clearance', tone: 'warning' },
  approved: { label: 'Principal approved', tone: 'warning' },
  completed: { label: 'Completed (withdrawn)', tone: 'success' },
  rejected: { label: 'Rejected', tone: 'danger' },
};

const CLEARANCE_STATUS_META = {
  pending: { label: 'Pending', tone: 'warning' },
  cleared: { label: 'Cleared', tone: 'success' },
  rejected: { label: 'Rejected', tone: 'danger' },
};

const formatDate = value => (value ? new Date(value).toLocaleString() : '—');

function StatusBadge({ value, kind = 'withdrawal' }) {
  const table = kind === 'clearance' ? CLEARANCE_STATUS_META : WITHDRAWAL_STATUS_META;
  const meta = table[String(value || '').toLowerCase()] || { label: value || '—', tone: 'neutral' };
  return <span className={`status status-${meta.tone}`}>{meta.label}</span>;
}

function useLoad(loader, deps) {
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
          setState({ data: null, loading: false, error: formatWithdrawalError(err) });
        }
      });
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);

  return { ...state, reload: () => setTick(value => value + 1) };
}

function StartWithdrawalPanel({ onCreated }) {
  const [search, setSearch] = useState('');
  const debounced = useDebouncedValue(search, 250);
  const [page, setPage] = useState(0);
  const enrolledList = useLoad(
    () => searchEnrolledApplications({ search: debounced, page, size: 10 }),
    [debounced, page]
  );

  const [selectedApp, setSelectedApp] = useState(null);
  const [enrollmentInfo, setEnrollmentInfo] = useState(null);
  const [loadingEnrollment, setLoadingEnrollment] = useState(false);
  const [enrollmentError, setEnrollmentError] = useState('');

  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [submitError, setSubmitError] = useState('');
  const [notice, setNotice] = useState('');
  const [confirming, setConfirming] = useState(false);

  async function pickApplication(app) {
    setSelectedApp(app);
    setEnrollmentInfo(null);
    setEnrollmentError('');
    setSubmitError('');
    setNotice('');
    setLoadingEnrollment(true);
    try {
      const detail = await getApplicationEnrollment(app.id);
      setEnrollmentInfo(detail?.enrollment || null);
      if (!detail?.enrollment) {
        setEnrollmentError('No active enrollment record was found for this application.');
      }
    } catch (err) {
      setEnrollmentError(formatWithdrawalError(err, 'Enrollment details could not be loaded.'));
    } finally {
      setLoadingEnrollment(false);
    }
  }

  async function submitWithdrawal() {
    if (!enrollmentInfo || !reason.trim()) return;
    setBusy(true);
    setSubmitError('');
    try {
      const created = await createWithdrawal({
        enrollmentId: enrollmentInfo.id,
        enrollmentVersion: enrollmentInfo.version ?? 0,
        reason: reason.trim(),
      });
      setConfirming(false);
      setReason('');
      setSelectedApp(null);
      setEnrollmentInfo(null);
      setNotice(
        `Withdrawal request #${created.id} started for ${created.studentName}. It is now awaiting Teacher Adviser endorsement.`
      );
      onCreated(created.id);
    } catch (err) {
      setConfirming(false);
      setSubmitError(formatWithdrawalError(err, 'Withdrawal request could not be started.'));
    } finally {
      setBusy(false);
    }
  }

  const rows = enrolledList.data?.items || [];

  return (
    <section className="panel" aria-labelledby="start-withdrawal-heading">
      <h2 id="start-withdrawal-heading">Start withdrawal request</h2>
      <div className="panel-body">
        <p className="field-help" style={{ marginTop: 0 }}>
          Find an enrolled student with an assigned section adviser to initiate the clearance chain (Adviser → Principal → Cashier).
        </p>

        {notice && (
          <p className="notice" role="status">
            {notice}
          </p>
        )}
        <Failure error={submitError} />

        <div className="withdrawals-student-picker">
          <div className="withdrawals-filters">
            <div style={{ flex: '1 1 280px' }}>
              <label htmlFor="withdrawal-student-search">Find an enrolled student</label>
              <input
                id="withdrawal-student-search"
                type="search"
                maxLength={150}
                value={search}
                onChange={event => {
                  setSearch(event.target.value);
                  setPage(0);
                }}
                placeholder="Search by name, application number, or LRN…"
              />
            </div>
            <button
              type="button"
              className="button button-outline"
              disabled={enrolledList.loading}
              onClick={enrolledList.reload}
            >
              Refresh students
            </button>
          </div>

          <Failure error={enrolledList.error} reload={enrolledList.reload} />
          {enrolledList.loading && <p role="status">Loading enrolled students…</p>}

          {!enrolledList.loading && !enrolledList.error && rows.length === 0 && (
            <p className="field-help">No enrolled students match this search.</p>
          )}

          {rows.length > 0 && (
            <div className="setup-table-wrap">
              <table className="setup-table">
                <thead>
                  <tr>
                    <th>Student</th>
                    <th>Campus / program</th>
                    <th>Section</th>
                    <th>Action</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map(row => (
                    <tr
                      key={row.id}
                      className={selectedApp?.id === row.id ? 'selected-row' : undefined}
                    >
                      <td>
                        <strong>{row.name}</strong>
                        <small>{row.applicationNumber}</small>
                      </td>
                      <td>
                        {row.campus || '—'}
                        <small>{row.program || '—'}</small>
                      </td>
                      <td>{row.section || 'Unassigned'}</td>
                      <td>
                        <button
                          type="button"
                          className="button button-outline"
                          disabled={busy}
                          onClick={() => pickApplication(row)}
                        >
                          {selectedApp?.id === row.id ? 'Selected' : `Select ${row.name}`}
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
            size={10}
            total={enrolledList.data?.total ?? 0}
            onChange={setPage}
            disabled={enrolledList.loading}
          />
        </div>

        {loadingEnrollment && <p role="status">Loading enrollment record…</p>}
        <Failure error={enrollmentError} />

        {selectedApp && enrollmentInfo && (
          <form
            onSubmit={event => {
              event.preventDefault();
              if (reason.trim()) setConfirming(true);
            }}
          >
            <div className="withdrawals-selected-enrollment">
              <strong>{selectedApp.name}</strong> ({selectedApp.applicationNumber}) · Enrollment #
              {enrollmentInfo.id} · Status: <strong>{enrollmentInfo.status}</strong> · Section:{' '}
              <strong>{enrollmentInfo.section || 'Unassigned'}</strong>
            </div>

            {!enrollmentInfo.sectionId && (
              <p className="notice notice-error" role="alert">
                Assign a section with an adviser before starting a withdrawal request.
              </p>
            )}

            <label htmlFor="withdrawal-reason">Reason for withdrawal</label>
            <textarea
              id="withdrawal-reason"
              className="withdrawals-textarea"
              required
              maxLength={2000}
              disabled={busy}
              value={reason}
              onChange={event => setReason(event.target.value)}
              placeholder="Explain why the student is requesting withdrawal from this term…"
            />

            <div className="component-row">
              <button
                type="submit"
                className="button"
                disabled={busy || !reason.trim() || !enrollmentInfo.sectionId}
              >
                Start withdrawal request
              </button>
              <button
                type="button"
                className="button button-outline"
                disabled={busy}
                onClick={() => {
                  setSelectedApp(null);
                  setEnrollmentInfo(null);
                  setReason('');
                  setSubmitError('');
                }}
              >
                Cancel selection
              </button>
            </div>
          </form>
        )}

        {confirming && selectedApp && enrollmentInfo && (
          <Confirm
            title="Start withdrawal request?"
            busy={busy}
            onCancel={() => setConfirming(false)}
            onConfirm={submitWithdrawal}
          >
            <p>
              Start a formal withdrawal clearance request for <strong>{selectedApp.name}</strong> (Enrollment #{enrollmentInfo.id})?
            </p>
            <p>{reason}</p>
          </Confirm>
        )}
      </div>
    </section>
  );
}

function AdviserEndorsementsPanel({ selectedId, onSelect, refreshKey }) {
  const [page, setPage] = useState(0);
  const list = useLoad(() => listAdviserEndorsements({ page, size: 25 }), [page, refreshKey]);
  const items = list.data?.items || [];

  return (
    <section className="panel" aria-labelledby="adviser-endorsements-heading">
      <h2 id="adviser-endorsements-heading">Pending endorsements for my sections</h2>
      <div className="panel-body">
        <div className="component-row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
          <p className="field-help" style={{ margin: 0 }}>
            Withdrawal requests awaiting your Teacher Adviser endorsement before advancing to the Principal.
          </p>
          <button
            type="button"
            className="button button-outline"
            disabled={list.loading}
            onClick={list.reload}
          >
            Refresh endorsements
          </button>
        </div>

        <Failure error={list.error} reload={list.reload} />
        {list.loading && <p role="status">Loading pending endorsements…</p>}

        {!list.loading && !list.error && items.length === 0 && (
          <div className="empty-state">
            <h3>No pending endorsements</h3>
            <p>There are currently no student withdrawal requests awaiting your section endorsement.</p>
          </div>
        )}

        {items.length > 0 && (
          <div className="setup-table-wrap">
            <table className="setup-table">
              <thead>
                <tr>
                  <th>Withdrawal</th>
                  <th>Student</th>
                  <th>Section</th>
                  <th>Reason</th>
                  <th>Action</th>
                </tr>
              </thead>
              <tbody>
                {items.map(item => (
                  <tr
                    key={item.withdrawalId}
                    className={String(item.withdrawalId) === String(selectedId) ? 'selected-row' : undefined}
                  >
                    <td>
                      <strong>#{item.withdrawalId}</strong>
                      <small>{formatDate(item.requestedAt)}</small>
                    </td>
                    <td>
                      <strong>{item.student}</strong>
                      <small>{item.studentNumber}</small>
                    </td>
                    <td>{item.section || '—'}</td>
                    <td>{item.reason}</td>
                    <td>
                      <button
                        type="button"
                        className="button button-outline"
                        onClick={() => onSelect(String(item.withdrawalId))}
                      >
                        Review endorsement #{item.withdrawalId}
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
          total={list.data?.total ?? 0}
          onChange={setPage}
          disabled={list.loading}
        />
      </div>
    </section>
  );
}

function WithdrawalDetailPanel({ withdrawalId, officeRole, canCheckRelease, onUpdated }) {
  const detailState = useLoad(() => getWithdrawal(withdrawalId), [withdrawalId]);
  const releaseState = useLoad(
    () => (canCheckRelease ? getReleaseEligibility(withdrawalId) : Promise.resolve(null)),
    [withdrawalId, canCheckRelease]
  );

  const [decision, setDecision] = useState('');
  const [notes, setNotes] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [confirming, setConfirming] = useState(false);

  const data = detailState.data;
  const clearances = data?.clearances || [];
  const byOffice = Object.fromEntries(clearances.map(item => [item.office, item]));

  const adviserRow = byOffice.adviser;
  const principalRow = byOffice.principal;
  const cashierRow = byOffice.cashier;

  const activeOffice =
    officeRole === 'adviser'
      ? 'adviser'
      : officeRole === 'principal'
        ? 'principal'
        : officeRole === 'cashier_ref'
          ? 'cashier'
          : null;

  const ownClearance = activeOffice ? byOffice[activeOffice] : null;
  const previousOffice =
    activeOffice === 'principal' ? 'adviser' : activeOffice === 'cashier' ? 'principal' : null;
  const previousCleared = !previousOffice || byOffice[previousOffice]?.status === 'cleared';
  const withdrawalActive = data && ['pending', 'approved'].includes(data.status);
  const canActNow = Boolean(
    activeOffice && withdrawalActive && ownClearance?.status === 'pending' && previousCleared
  );
  const waitingOnPrevious = Boolean(
    activeOffice && withdrawalActive && ownClearance?.status === 'pending' && !previousCleared
  );

  const releaseEligible = releaseState.data
    ? Boolean(releaseState.data.eligible)
    : Boolean(data?.credentialReleaseEligible);
  const releaseReason =
    releaseState.data?.reason ||
    (releaseEligible
      ? 'Withdrawal clearances complete; other document rules still apply.'
      : 'Credential release is blocked by unresolved or rejected withdrawal clearance.');

  function reloadAll() {
    detailState.reload();
    if (canCheckRelease) releaseState.reload();
  }

  async function submitDecision() {
    if (!data || !ownClearance || !decision || !notes.trim()) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      if (activeOffice === 'adviser') {
        await decideAdviserEndorsement(data.id, {
          version: ownClearance.version,
          decision,
          notes: notes.trim(),
        });
      } else {
        await decideOfficeClearance(data.id, activeOffice, {
          version: ownClearance.version,
          withdrawalVersion: data.version,
          decision,
          notes: notes.trim(),
        });
      }
      setConfirming(false);
      setDecision('');
      setNotes('');
      setNotice(
        decision === 'cleared'
          ? activeOffice === 'cashier'
            ? 'Cashier clearance recorded. Withdrawal is now completed and credentials are eligible for release.'
            : activeOffice === 'principal'
              ? 'Principal approval recorded. Request has advanced to the Cashiering Office.'
              : 'Adviser endorsement recorded. Request has advanced to the Principal.'
          : 'Rejection recorded with your remarks. Withdrawal clearance is closed.'
      );
      reloadAll();
      onUpdated();
    } catch (err) {
      setConfirming(false);
      setError(formatWithdrawalError(err, 'Clearance decision could not be saved.'));
    } finally {
      setBusy(false);
    }
  }

  function stepClass(office, row) {
    if (row?.status === 'cleared') return 'step-cleared';
    if (row?.status === 'rejected') return 'step-rejected';
    if (!withdrawalActive) return '';
    if (office === 'adviser' && row?.status === 'pending') return 'step-active';
    if (office === 'principal' && row?.status === 'pending' && adviserRow?.status === 'cleared') {
      return 'step-active';
    }
    if (office === 'cashier' && row?.status === 'pending' && principalRow?.status === 'cleared') {
      return 'step-active';
    }
    return '';
  }

  return (
    <section className="panel" aria-labelledby="withdrawal-detail-heading">
      <h2 id="withdrawal-detail-heading">Withdrawal clearance #{withdrawalId}</h2>
      <div className="panel-body">
        <Failure error={detailState.error} reload={reloadAll} />
        <Failure error={error} />
        {notice && (
          <p className="notice" role="status">
            {notice}
          </p>
        )}
        {detailState.loading && !data && <p role="status">Loading withdrawal details…</p>}

        {data && (
          <>
            <div className="selected-app" style={{ paddingTop: 0 }}>
              <div>
                <p className="eyebrow">
                  Withdrawal #{data.id} · Enrollment #{data.enrollmentId} · Version {data.version}
                </p>
                <h2>
                  {data.studentName} <StatusBadge value={data.status} />
                </h2>
              </div>
              <div className="component-row">
                <button
                  type="button"
                  className="button button-outline"
                  disabled={busy || detailState.loading}
                  onClick={reloadAll}
                >
                  Reload clearance
                </button>
              </div>
            </div>

            <dl className="withdrawals-meta-grid">
              <div>
                <dt>Student number</dt>
                <dd>{data.studentNumber || '—'}</dd>
              </div>
              <div>
                <dt>Section</dt>
                <dd>{data.section || 'Unassigned'}</dd>
              </div>
              <div>
                <dt>Enrollment status</dt>
                <dd>{data.enrollmentStatus || '—'}</dd>
              </div>
              <div>
                <dt>Requested at</dt>
                <dd>{formatDate(data.requestedAt)}</dd>
              </div>
            </dl>

            <p style={{ marginTop: 0 }}>
              <strong>Withdrawal reason:</strong> {data.reason}
            </p>

            <div
              className={`withdrawals-release-box${releaseEligible ? '' : ' is-blocked'}`}
              role="status"
            >
              <strong>
                Record release eligibility:{' '}
                {releaseEligible ? 'Eligible for release' : 'Blocked'}
              </strong>
              <p>{releaseReason}</p>
            </div>

            <h3>Clearance chain (Adviser → Principal → Cashier)</h3>
            <ol className="withdrawals-chain">
              {['adviser', 'principal', 'cashier'].map(office => {
                const row = byOffice[office];
                const waitingLabel =
                  office === 'principal' && row?.status === 'pending' && adviserRow?.status !== 'cleared'
                    ? 'Waiting for Teacher Adviser endorsement'
                    : office === 'cashier' && row?.status === 'pending' && principalRow?.status !== 'cleared'
                      ? 'Waiting for Principal approval'
                      : null;
                return (
                  <li key={office} className={`withdrawals-step ${stepClass(office, row)}`}>
                    <div className="withdrawals-step-head">
                      <strong>{OFFICE_LABELS[office]}</strong>
                      <StatusBadge value={row?.status || 'pending'} kind="clearance" />
                    </div>
                    <p className="withdrawals-step-meta">
                      {row?.actedAt
                        ? `${row.actorName || `Staff #${row.actedBy}`} · ${formatDate(row.actedAt)}`
                        : waitingLabel || 'Awaiting office decision'}
                    </p>
                    {row?.notes && <p className="withdrawals-step-notes">{row.notes}</p>}
                  </li>
                );
              })}
            </ol>

            {waitingOnPrevious && (
              <p className="notice" role="status">
                {activeOffice === 'principal'
                  ? 'This request is still waiting for the Teacher Adviser endorsement before Principal approval can be recorded.'
                  : 'This request is still waiting for Principal approval before Cashier clearance can be recorded.'}
              </p>
            )}

            {canActNow && (
              <form
                className="withdrawals-action-box"
                onSubmit={event => {
                  event.preventDefault();
                  if (decision && notes.trim()) setConfirming(true);
                }}
              >
                <h3>
                  {activeOffice === 'adviser'
                    ? 'Record Teacher Adviser endorsement'
                    : activeOffice === 'principal'
                      ? 'Record Principal approval'
                      : 'Record Cashier clearance'}
                </h3>
                <p className="field-help" style={{ marginTop: 0 }}>
                  {activeOffice === 'cashier'
                    ? 'Clearing marks the withdrawal completed and transitions the term enrollment to withdrawn (permanent student identity is preserved).'
                    : 'Your decision and remarks are recorded in the permanent audit trail.'}
                </p>

                <label htmlFor="clearance-decision">Decision</label>
                <select
                  id="clearance-decision"
                  required
                  disabled={busy}
                  value={decision}
                  onChange={event => setDecision(event.target.value)}
                >
                  <option value="">Choose a decision…</option>
                  <option value="cleared">
                    {activeOffice === 'adviser'
                      ? 'Endorse withdrawal (clear adviser step)'
                      : activeOffice === 'principal'
                        ? 'Approve withdrawal (clear principal step)'
                        : 'Clear account & complete withdrawal'}
                  </option>
                  <option value="rejected">
                    {activeOffice === 'adviser'
                      ? 'Decline endorsement (reject withdrawal)'
                      : activeOffice === 'principal'
                        ? 'Reject withdrawal'
                        : 'Reject clearance (block release)'}
                  </option>
                </select>

                <label htmlFor="clearance-notes">Remarks (required)</label>
                <textarea
                  id="clearance-notes"
                  className="withdrawals-textarea"
                  required
                  maxLength={2000}
                  disabled={busy}
                  value={notes}
                  onChange={event => setNotes(event.target.value)}
                  placeholder="Enter clearance notes, verification reference, or reason for declining…"
                />

                <button
                  type="submit"
                  className="button"
                  disabled={busy || !decision || !notes.trim()}
                >
                  Review decision
                </button>
              </form>
            )}

            <h3>Audit history</h3>
            {data.history?.length ? (
              <ol className="verification-history">
                {data.history.map(event => (
                  <li key={event.id}>
                    <strong>
                      {event.office ? `${event.office} clearance` : 'Withdrawal'} ·{' '}
                      {event.newStatus || event.operation}
                    </strong>
                    <small>
                      {event.actorName || 'System'} · {formatDate(event.occurredAt)}
                    </small>
                    {event.reason && <p>{event.reason}</p>}
                  </li>
                ))}
              </ol>
            ) : (
              <p className="field-help">No audit history entries recorded yet.</p>
            )}
          </>
        )}

        {confirming && data && (
          <Confirm
            title={
              decision === 'cleared'
                ? `Confirm ${activeOffice} clearance?`
                : `Confirm ${activeOffice} rejection?`
            }
            busy={busy}
            onCancel={() => setConfirming(false)}
            onConfirm={submitDecision}
          >
            <p>
              {decision === 'cleared'
                ? `Mark ${OFFICE_LABELS[activeOffice]} as cleared for ${data.studentName} (Withdrawal #${data.id}).`
                : `Mark ${OFFICE_LABELS[activeOffice]} as rejected for ${data.studentName} (Withdrawal #${data.id}). Credential release will remain blocked.`}
            </p>
            <p>{notes}</p>
          </Confirm>
        )}
      </div>
    </section>
  );
}

export default function WithdrawalsWorkspace({ mode = 'approvals' }) {
  const { roles } = useAuth();
  const [params, setParams] = useSearchParams();

  const isRegistrar = roles.includes('registrar') || roles.includes('admin');
  const isAdviser = roles.includes('adviser');
  const isPrincipal = roles.includes('principal');
  const isCashier = roles.includes('cashier_ref');

  // Determine the acting clearance office for this screen & signed-in role
  const officeRole =
    mode === 'clearances'
      ? isCashier
        ? 'cashier_ref'
        : null
      : isPrincipal
        ? 'principal'
        : isAdviser
          ? 'adviser'
          : isCashier
            ? 'cashier_ref'
            : null;

  const canCheckRelease = isRegistrar || isPrincipal || isCashier;

  const [statusFilter, setStatusFilter] = useState(
    mode === 'clearances' ? 'approved' : isPrincipal ? 'pending' : ''
  );
  const [page, setPage] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);

  const listState = useLoad(
    () => listWithdrawals({ status: statusFilter, page, size: 25 }),
    [statusFilter, page, refreshKey]
  );

  const selectedId = params.get('withdrawalId') || '';
  const items = listState.data?.items || [];

  const title = mode === 'clearances' ? 'Clearance Status' : 'Withdrawal Approvals';
  const eyebrow =
    mode === 'clearances'
      ? 'Cashiering reference'
      : isPrincipal
        ? 'Academic oversight / Principal'
        : isAdviser
          ? 'Academic records / Teacher adviser'
          : 'Academic records / Registrar';
  const subtitle =
    mode === 'clearances'
      ? 'Review principal-approved withdrawals, record final cashiering clearance, and check record release eligibility.'
      : isRegistrar && !isPrincipal && !isAdviser
        ? 'Start student withdrawal requests, track Adviser → Principal → Cashier clearance progress, and check record release eligibility.'
        : isAdviser && !isPrincipal
          ? 'Review pending withdrawal endorsements for your advisory sections and monitor clearance progress.'
          : 'Review adviser-endorsed withdrawals in order (Adviser → Principal → Cashier) and track record release eligibility.';

  function selectWithdrawal(id) {
    setParams({ withdrawalId: String(id) });
  }

  function handleMutated() {
    setRefreshKey(value => value + 1);
  }

  return (
    <div className="withdrawals-workspace">
      <header className="page-heading">
        <p className="eyebrow">{eyebrow}</p>
        <h1>{title}</h1>
        <p>{subtitle}</p>
      </header>

      {mode !== 'clearances' && isRegistrar && (
        <StartWithdrawalPanel
          onCreated={id => {
            setStatusFilter('');
            setPage(0);
            handleMutated();
            selectWithdrawal(id);
          }}
        />
      )}

      {mode !== 'clearances' && isAdviser && (
        <AdviserEndorsementsPanel
          selectedId={selectedId}
          onSelect={selectWithdrawal}
          refreshKey={refreshKey}
        />
      )}

      <section className="panel" aria-labelledby="withdrawal-queue-heading">
        <h2 id="withdrawal-queue-heading">
          {mode === 'clearances' ? 'Withdrawal clearance queue' : 'Withdrawal requests'}
        </h2>
        <div className="panel-body">
          <div className="withdrawals-filters">
            <div>
              <label htmlFor="withdrawal-status-filter">Status</label>
              <select
                id="withdrawal-status-filter"
                value={statusFilter}
                onChange={event => {
                  setStatusFilter(event.target.value);
                  setPage(0);
                }}
              >
                <option value="">All statuses</option>
                <option value="pending">Pending (Adviser / Principal)</option>
                <option value="approved">Principal approved (awaiting Cashier)</option>
                <option value="completed">Completed (withdrawn)</option>
                <option value="rejected">Rejected</option>
              </select>
            </div>
            <button
              type="button"
              className="button button-outline"
              disabled={listState.loading}
              onClick={listState.reload}
            >
              Refresh queue
            </button>
          </div>

          <Failure error={listState.error} reload={listState.reload} />
          {listState.loading && <p role="status">Loading withdrawal requests…</p>}

          {!listState.loading && !listState.error && items.length === 0 && (
            <div className="empty-state">
              <h3>No withdrawal requests found</h3>
              <p>Try choosing “All statuses” above or wait for a Registrar-initiated withdrawal.</p>
            </div>
          )}

          {items.length > 0 && (
            <div className="setup-table-wrap">
              <table className="setup-table">
                <thead>
                  <tr>
                    <th>Request</th>
                    <th>Student</th>
                    <th>Section</th>
                    <th>Reason</th>
                    <th>Status</th>
                    <th>Action</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map(row => (
                    <tr
                      key={row.id}
                      className={String(row.id) === selectedId ? 'selected-row' : undefined}
                    >
                      <td>
                        <strong>#{row.id}</strong>
                        <small>{formatDate(row.requestedAt)}</small>
                      </td>
                      <td>
                        <strong>{row.studentName}</strong>
                        <small>{row.studentNumber}</small>
                      </td>
                      <td>{row.section || 'Unassigned'}</td>
                      <td>{row.reason}</td>
                      <td>
                        <StatusBadge value={row.status} />
                      </td>
                      <td>
                        <button
                          type="button"
                          className="button button-outline"
                          onClick={() => selectWithdrawal(row.id)}
                        >
                          {['pending', 'approved'].includes(row.status) && officeRole
                            ? `Review #${row.id}`
                            : `View #${row.id}`}
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

      {selectedId && /^\d+$/.test(selectedId) ? (
        <WithdrawalDetailPanel
          key={selectedId}
          withdrawalId={selectedId}
          officeRole={officeRole}
          canCheckRelease={canCheckRelease}
          onUpdated={handleMutated}
        />
      ) : (
        <p className="notice">
          Select a withdrawal request above to inspect its Adviser → Principal → Cashier clearance chain, record a decision, or check record release eligibility.
        </p>
      )}
    </div>
  );
}
